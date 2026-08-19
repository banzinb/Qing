package com.zhousl.aether.data

import com.zhousl.aether.runtime.RuntimeRouter
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONArray
import org.json.JSONObject

data class AetherToolExecutionResult(
    val toolName: String,
    val argumentsJson: String,
    val rawOutput: String,
    val visibleOutput: String = AetherToolExecutor.sanitizeToolOutputForConversation(toolName, rawOutput),
) {
    val isError: Boolean = !AetherToolExecutor.inferToolOutputOk(visibleOutput)
}

/**
 * Adapter for Aether-owned host capabilities. Pi Coding Agent owns filesystem,
 * shell, Skill, Extension, and image-reading mechanics; Qing keeps the
 * multi-backend web search and URL fetch tools here.
 */
class AetherToolExecutor(
    private val runtimeRouter: RuntimeRouter,
    private val webToolsClient: WebToolsClient? = null,
    private val agentModeController: AgentModeController? = null,
) {
    suspend fun execute(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        toolName: String,
        argumentsJson: String,
        selfManagementTool: AetherSelfManagementTool? = null,
        agentModeEnabled: Boolean = false,
        currentRuntimeId: LocalRuntimeId = settings.defaultRuntimeId ?: LocalRuntimeId.Alpine,
        onRuntimeChanged: suspend (LocalRuntimeId) -> Unit = {},
        onProgress: (suspend (String) -> Unit)? = null,
    ): AetherToolExecutionResult {
        val rawOutput = when (toolName) {

            "fetch_web_url" -> executeFetchWebUrl(argumentsJson)
            "web_search", "tavily_search" -> executeWebSearch(
                settings = settings,
                argumentsJson = argumentsJson,
            )

            "agent_display" -> if (agentModeEnabled) {
                agentModeController?.execute(
                    settings = settings,
                    workspaceDirectory = workspaceDirectory,
                    termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                    argumentsJson = argumentsJson,
                ) ?: unavailableToolOutput(toolName)
            } else {
                JSONObject()
                    .put("ok", false)
                    .put("errmsg", "Agent Mode is not enabled for this chat.")
                    .toString()
            }

            "aether_runtime_manage" -> executeRuntimeManage(
                settings = settings,
                currentRuntimeId = currentRuntimeId,
                workspaceDirectory = workspaceDirectory,
                termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                argumentsJson = argumentsJson,
                onRuntimeChanged = onRuntimeChanged,
            )

            in SelfManagementToolNames -> selfManagementTool?.execute(
                toolName = toolName,
                argumentsJson = argumentsJson,
            ) ?: unavailableToolOutput(toolName)

            else -> JSONObject()
                .put("ok", false)
                .put("error", "Unknown Aether host tool '$toolName'.")
                .toString()
        }
        return AetherToolExecutionResult(toolName, argumentsJson, rawOutput)
    }

    private suspend fun executeRuntimeManage(
        settings: AppSettings,
        currentRuntimeId: LocalRuntimeId,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        argumentsJson: String,
        onRuntimeChanged: suspend (LocalRuntimeId) -> Unit,
    ): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("errmsg", "Invalid JSON arguments.").toString()
        val action = arguments.optString("action").trim()
        if (action == "status") {
            val states = LocalRuntimeId.entries.associateWith { runtimeId ->
                runtimeRouter.runtimeById(runtimeId).inspectSetup()
            }
            return JSONObject().apply {
                put("ok", true)
                put("action", "status")
                put("runtime", currentRuntimeId.storageValue)
                put("cwd", runtimeCwd(currentRuntimeId, workspaceDirectory, termuxWorkspaceDirectory))
                put(
                    "available",
                    JSONObject().apply {
                        states.forEach { (runtimeId, state) -> put(runtimeId.storageValue, state.isReady) }
                    },
                )
            }.toString()
        }
        if (action != "set") {
            return JSONObject().put("ok", false).put("errmsg", "action must be 'status' or 'set'.").toString()
        }
        val requested = LocalRuntimeId.fromStorage(arguments.optString("runtime"))
            ?: return JSONObject().put("ok", false).put("errmsg", "runtime must be 'alpine' or 'termux'.").toString()
        val setup = runtimeRouter.runtimeById(requested).inspectSetup()
        val enabled = settings.enabledRuntimeIds.isEmpty() || requested in settings.enabledRuntimeIds
        if (!setup.isReady || !enabled) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "${requested.displayName} runtime is unavailable.")
                put("detail", setup.detail)
                put("runtime", currentRuntimeId.storageValue)
                put("cwd", runtimeCwd(currentRuntimeId, workspaceDirectory, termuxWorkspaceDirectory))
            }.toString()
        }
        onRuntimeChanged(requested)
        return JSONObject().apply {
            put("ok", true)
            put("action", "set")
            put("runtime", requested.storageValue)
            put("cwd", runtimeCwd(requested, workspaceDirectory, termuxWorkspaceDirectory))
        }.toString()
    }



    private suspend fun executeFetchWebUrl(argumentsJson: String): String {
        val client = webToolsClient ?: return toolUnavailableOutput("fetch_web_url")
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Arguments were not valid JSON.")
            }.toString()

        val url = arguments.optString("url").trim()
        if (url.isBlank()) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Missing required 'url' argument.")
            }.toString()
        }

        val maxChars = when {
            arguments.has("max_chars") -> arguments.optInt("max_chars")
            arguments.has("maxChars") -> arguments.optInt("maxChars")
            else -> 20_000
        }

        val page = client.fetchUrlAsMarkdown(
            url = url,
            maxChars = maxChars,
        ).getOrElse { throwable ->
            return toolFailureOutput(throwable, "Couldn't fetch the URL.") {
                put("url", url)
            }
        }

        return JSONObject().apply {
            put("ok", true)
            put("request_url", page.requestUrl)
            put("final_url", page.finalUrl)
            put("title", page.title)
            put("content_type", page.contentType)
            put("markdown", page.markdown)
            put("truncated", page.wasTruncated)
            put(
                "stdout",
                buildString {
                    append("Fetched ")
                    append(page.title.ifBlank { page.finalUrl })
                    if (page.wasTruncated) {
                        append(" (truncated)")
                    }
                },
            )
        }.toString()
    }

    private suspend fun executeWebSearch(
        settings: AppSettings,
        argumentsJson: String,
    ): String {
        val client = webToolsClient ?: return toolUnavailableOutput("web_search")
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Arguments were not valid JSON.")
            }.toString()

        val query = arguments.optString("query").trim()
        if (query.isBlank()) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Missing required 'query' argument.")
            }.toString()
        }
        val maxResults = arguments.optInt("max_results", arguments.optInt("maxResults", 5))
            .coerceIn(1, 20)

        val selectedBackend = settings.searchBackend
        val response = when (selectedBackend) {
            SearchBackend.Tavily -> client.searchTavily(
                apiKey = settings.tavilyApiKey,
                baseUrl = settings.tavilyBaseUrl,
                request = buildTavilySearchRequest(arguments, query),
            ).getOrElse { throwable ->
                return toolFailureOutput(throwable, "Tavily search failed.") {
                    put("query", query)
                }
            }

            SearchBackend.Bing -> client.searchBing(
                query = query,
                maxResults = maxResults,
            ).getOrElse { throwable ->
                return toolFailureOutput(throwable, "Bing search failed.") {
                    put("query", query)
                }
            }

            SearchBackend.DuckDuckGo -> client.searchDuckDuckGo(
                query = query,
                maxResults = maxResults,
            ).getOrElse { throwable ->
                return toolFailureOutput(throwable, "DuckDuckGo search failed.") {
                    put("query", query)
                }
            }

            SearchBackend.SearXNG -> client.searchSearxng(
                baseUrl = settings.searxngBaseUrl,
                apiKey = settings.searxngApiKey,
                query = query,
                maxResults = maxResults,
            ).getOrElse { throwable ->
                return toolFailureOutput(throwable, "SearXNG search failed.") {
                    put("query", query)
                }
            }

            SearchBackend.Auto -> if (settings.tavilyApiKey.isNotBlank()) {
                client.searchTavily(
                    apiKey = settings.tavilyApiKey,
                    baseUrl = settings.tavilyBaseUrl,
                    request = buildTavilySearchRequest(arguments, query),
                ).getOrElse { throwable ->
                    return toolFailureOutput(throwable, "Tavily search failed.") {
                        put("query", query)
                    }
                }
            } else {
                client.searchBing(
                    query = query,
                    maxResults = maxResults,
                ).getOrElse { throwable ->
                    return toolFailureOutput(throwable, "Bing search failed.") {
                        put("query", query)
                    }
                }
            }
        }

        val effectiveBackend = if (selectedBackend == SearchBackend.Auto) {
            if (settings.tavilyApiKey.isNotBlank()) SearchBackend.Tavily else SearchBackend.Bing
        } else {
            selectedBackend
        }
        response.put("ok", true)
        response.put("backend", effectiveBackend.storageValue)
        response.put("stdout", buildWebSearchSummary(response))
        return response.toString()
    }

    private fun buildTavilySearchRequest(
        arguments: JSONObject,
        query: String,
    ): TavilySearchRequest = TavilySearchRequest(
        query = query,
        topic = arguments.stringValue("topic").ifBlank { "general" },
        searchDepth = arguments.stringValue("search_depth", "searchDepth").ifBlank { "basic" },
        maxResults = arguments.intValue("max_results", "maxResults") ?: 5,
        timeRange = arguments.stringValue("time_range", "timeRange").ifBlank { null },
        includeAnswer = arguments.booleanValue("include_answer", "includeAnswer") ?: true,
        includeRawContent = arguments.booleanValue("include_raw_content", "includeRawContent") ?: false,
        includeDomains = arguments.stringArrayValue("include_domains", "includeDomains"),
        excludeDomains = arguments.stringArrayValue("exclude_domains", "excludeDomains"),
        country = arguments.stringValue("country").ifBlank { null },
        startDate = arguments.stringValue("start_date", "startDate").ifBlank { null },
        endDate = arguments.stringValue("end_date", "endDate").ifBlank { null },
    )
    companion object {
        val hostToolNames: Set<String> = setOf("agent_display", "fetch_web_url", "web_search", "tavily_search", *SelfManagementToolNames.toTypedArray())

        fun supports(toolName: String): Boolean = toolName in hostToolNames

        fun hostToolDefinitions(
            selfManagementTool: AetherSelfManagementTool? = null,
            agentModeEnabled: Boolean = false,
        ): JSONArray = JSONArray().apply {
            selfManagementTool?.toolDefinitions()?.forEach { definition ->
                put(
                    flattenOpenAiToolDefinition(
                        definition = definition,
                        executionMode = if (
                            definition.optJSONObject("function")?.optString("name") == "aether_config_get"
                        ) "parallel" else "sequential",
                    ),
                )
            }
            if (agentModeEnabled) put(agentModeToolDefinition())

            put(
                webToolDefinition(
                    name = "fetch_web_url",
                    description = "Fetch a specific HTTP or HTTPS URL and return the page content converted to Markdown. Use this when the user gives you a URL or you need the contents of one page.",
                    required = listOf("url"),
                    properties = JSONObject().apply {
                        put("url", stringProperty("The HTTP or HTTPS URL to fetch."))
                        put("max_chars", integerProperty("Optional maximum number of Markdown characters to return."))
                        put("maxChars", integerProperty("Alias of max_chars."))
                    },
                ),
            )
            put(
                webToolDefinition(
                    name = "web_search",
                    description = "Search the public web and return ranked results. Works out of the box with the built-in free search backend; Tavily, Bing, DuckDuckGo, or a self-hosted SearXNG instance can be selected in Settings.",
                    required = listOf("query"),
                    properties = JSONObject().apply {
                        put("query", stringProperty("The search query to execute."))
                        put("topic", stringProperty("Optional search topic: general, news, or finance."))
                        put("search_depth", stringProperty("Optional search depth: basic, advanced, fast, or ultra-fast."))
                        put("max_results", integerProperty("Optional maximum number of results to return, between 1 and 20."))
                        put("time_range", stringProperty("Optional recency filter, such as day, week, month, or year. Do not combine this with start_date or end_date."))
                        put("include_answer", booleanProperty("Whether the backend should include a synthesized answer."))
                        put("include_raw_content", booleanProperty("Whether each result should include raw page content in Markdown."))
                        put("include_domains", stringArrayProperty("Optional list of domains to include."))
                        put("exclude_domains", stringArrayProperty("Optional list of domains to exclude."))
                        put("country", stringProperty("Optional lowercase Tavily country value for localized general search, such as united states or china. Leave null when unsure."))
                        put("start_date", stringProperty("Optional start date in YYYY-MM-DD format. Do not combine this with time_range."))
                        put("end_date", stringProperty("Optional end date in YYYY-MM-DD format. Do not combine this with time_range."))
                    },
                ),
            )
        }

        fun sanitizeToolOutputForConversation(toolName: String, output: String): String {
            if (toolName != "agent_display") return output
            val parsed = runCatching { JSONObject(output) }.getOrNull() ?: return output
            if (!parsed.has("screenshot_base64")) return output
            parsed.remove("screenshot_base64")
            parsed.put("screenshot_injected_into_next_model_request", true)
            return parsed.toString()
        }

        fun inferToolOutputOk(output: String): Boolean {
            val parsed = runCatching { JSONObject(output) }.getOrNull() ?: return true
            return parsed.optBoolean("ok", !parsed.optBoolean("err", false))
        }
    }
}

private fun runtimeCwd(
    runtimeId: LocalRuntimeId,
    workspaceDirectory: String,
    termuxWorkspaceDirectory: String,
): String = if (runtimeId == LocalRuntimeId.Termux) termuxWorkspaceDirectory else workspaceDirectory

private val SelfManagementToolNames = setOf(
    "aether_config_get",
    "aether_config_set",
    "aether_skill_manage",
    "aether_termux_manage",
    "aether_runtime_manage",
    "aether_agent_mode_manage",
    "aether_scheduled_task_manage",
    "aether_extension_manage",
    "aether_developer_manage",
)

private fun unavailableToolOutput(toolName: String): String = JSONObject()
    .put("ok", false)
    .put("errmsg", "Host dependency for '$toolName' is not available.")
    .toString()

private fun flattenOpenAiToolDefinition(
    definition: JSONObject,
    executionMode: String,
): JSONObject {
    val function = definition.optJSONObject("function") ?: JSONObject()
    return JSONObject().apply {
        put("name", function.optString("name"))
        put("description", function.optString("description"))
        put("parameters", relaxStrictOptionalParameters(function.optJSONObject("parameters")))
        put("execution_mode", executionMode)
    }
}

private fun relaxStrictOptionalParameters(parameters: JSONObject?): JSONObject {
    val relaxed = JSONObject((parameters ?: JSONObject().put("type", "object")).toString())
    val properties = relaxed.optJSONObject("properties") ?: return relaxed
    val required = relaxed.optJSONArray("required") ?: return relaxed
    relaxed.put(
        "required",
        JSONArray().apply {
            for (index in 0 until required.length()) {
                val name = required.optString(index)
                if (name.isNotBlank() && !properties.optJSONObject(name).allowsNull()) put(name)
            }
        },
    )
    return relaxed
}

private fun JSONObject?.allowsNull(): Boolean = when (val type = this?.opt("type")) {
    "null" -> true
    is JSONArray -> (0 until type.length()).any { type.optString(it) == "null" }
    else -> false
}

private fun agentModeToolDefinition(): JSONObject = JSONObject().apply {
    put("name", "agent_display")
    put(
        "description",
        "Operate Aether Agent Mode on an isolated Android virtual display. Use this only when Agent Mode is selected in the chat composer.",
    )
    put(
        "parameters",
        JSONObject().apply {
            put("type", "object")
            put(
                "properties",
                JSONObject().apply {
                    put("action", stringProperty("One of: list_apps, start, status, launch, tap, swipe, key, text, screenshot, stop."))
                    put("query", stringProperty("For list_apps: optional app label, package, or activity filter."))
                    put("include_system", booleanProperty("For list_apps: whether to include system apps."))
                    put("max_results", integerProperty("For list_apps: maximum number of apps to return."))
                    put("target", stringProperty("For launch: package name or exact app label."))
                    listOf("x", "y", "x1", "y1", "x2", "y2", "duration_ms").forEach { key ->
                        put(key, integerProperty("Normalized coordinate or gesture duration for $key."))
                    }
                    put("key", stringProperty("For key: Android key code name or number."))
                    put("text", stringProperty("For text: text to type into the focused field."))
                },
            )
            put("required", JSONArray().put("action"))
            put("additionalProperties", false)
        },
    )
    put("execution_mode", "sequential")
}

private fun stringProperty(description: String): JSONObject = JSONObject()
    .put("type", "string")
    .put("description", description)

private fun integerProperty(description: String): JSONObject = JSONObject()
    .put("type", "integer")
    .put("description", description)

private fun booleanProperty(description: String): JSONObject = JSONObject()
    .put("type", "boolean")
    .put("description", description)


private fun toolFailureOutput(
    throwable: Throwable,
    fallbackMessage: String,
    configure: JSONObject.() -> Unit = {},
): String {
    if (throwable is CancellationException) throw throwable
    return JSONObject().apply {
        put("ok", false)
        configure()
        put("errmsg", throwable.message ?: fallbackMessage)
    }.toString()
}

private fun toolUnavailableOutput(toolName: String): String = JSONObject()
    .put("ok", false)
    .put("errmsg", "Host dependency for '$toolName' is not available.")
    .toString()

private fun buildWebSearchSummary(response: JSONObject): String = buildString {
    val answer = response.optString("answer").trim()
    if (answer.isNotBlank()) {
        append(answer)
    }

    val results = response.optJSONArray("results") ?: JSONArray()
    if (results.length() > 0) {
        if (isNotEmpty()) append("\n\n")
        append("Top results:")
        for (index in 0 until minOf(results.length(), 5)) {
            val result = results.optJSONObject(index) ?: continue
            append("\n")
            append(index + 1)
            append(". ")
            append(result.optString("title").ifBlank { result.optString("url") })
            val url = result.optString("url").trim()
            if (url.isNotBlank()) {
                append(" - ")
                append(url)
            }
            val snippet = result.optString("content").trim()
            if (snippet.isNotBlank()) {
                append("\n")
                append(snippet.take(280))
            }
        }
    }
}

private fun stringArrayProperty(description: String): JSONObject = JSONObject()
    .put("type", "array")
    .put("description", description)
    .put("items", JSONObject().put("type", "string"))

private fun webToolDefinition(
    name: String,
    description: String,
    required: List<String>,
    properties: JSONObject,
): JSONObject = JSONObject().apply {
    put("name", name)
    put("description", description)
    put(
        "parameters",
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject(properties.toString()))
            put("required", JSONArray(required))
            put("additionalProperties", false)
        },
    )
    put("execution_mode", "parallel")
}

private fun JSONObject.cleanOptionalString(key: String): String {
    if (!has(key) || isNull(key)) return ""
    val value = optString(key).trim()
    return value.takeUnless { it.equals("null", ignoreCase = true) || it.equals("undefined", ignoreCase = true) }
        .orEmpty()
}

private fun JSONObject.stringValue(
    primaryKey: String,
    aliasKey: String? = null,
): String {
    val primary = cleanOptionalString(primaryKey)
    if (primary.isNotBlank()) return primary
    return aliasKey?.let { cleanOptionalString(it) }.orEmpty()
}

private fun JSONObject.intValue(
    primaryKey: String,
    aliasKey: String? = null,
): Int? = when {
    hasUsableValue(primaryKey) -> optInt(primaryKey)
    aliasKey != null && hasUsableValue(aliasKey) -> optInt(aliasKey)
    else -> null
}

private fun JSONObject.booleanValue(
    primaryKey: String,
    aliasKey: String? = null,
): Boolean? = when {
    hasUsableValue(primaryKey) -> optBoolean(primaryKey)
    aliasKey != null && hasUsableValue(aliasKey) -> optBoolean(aliasKey)
    else -> null
}

private fun JSONObject.stringArrayValue(
    primaryKey: String,
    aliasKey: String? = null,
): List<String> {
    val array = when {
        hasUsableValue(primaryKey) -> optJSONArray(primaryKey)
        aliasKey != null && hasUsableValue(aliasKey) -> optJSONArray(aliasKey)
        else -> null
    } ?: return emptyList()

    return buildList {
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotEmpty()) {
                add(value)
            }
        }
    }
}

private fun JSONObject.hasUsableValue(key: String): Boolean =
    has(key) && !isNull(key) && !cleanOptionalString(key).equals("null", ignoreCase = true)
