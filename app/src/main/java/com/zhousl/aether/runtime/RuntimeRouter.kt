package com.zhousl.aether.runtime

import com.zhousl.aether.data.AppSettings
import com.zhousl.aether.data.LocalRuntimeId
import org.json.JSONObject

class RuntimeRouter(
    private val termuxRuntime: LocalRuntime,
    private val alpineRuntime: LocalRuntime,
    private val embeddedTermuxRuntime: LocalRuntime? = null,
) {
    fun runtimeFor(
        settings: AppSettings,
        environment: String?,
    ): LocalRuntime? {
        val requested = environment?.trim().orEmpty().lowercase()
        val runtimeId = when (requested) {
            "", "default" -> settings.defaultRuntimeId
                ?: chooseOnlyEnabled(settings)
                ?: legacyDefault(settings)
            "termux" -> LocalRuntimeId.Termux
            "alpine" -> LocalRuntimeId.Alpine
            "embedded_termux", "qing_termux" -> LocalRuntimeId.EmbeddedTermux
            else -> null
        } ?: return null

        if (
            requested != "termux" &&
            requested != "alpine" &&
            requested != "embedded_termux" &&
            requested != "qing_termux" &&
            settings.enabledRuntimeIds.isNotEmpty()
        ) {
            if (runtimeId !in settings.enabledRuntimeIds) return null
        }
        return runtimeById(runtimeId)
    }

    fun runtimeById(runtimeId: LocalRuntimeId): LocalRuntime = when (runtimeId) {
        LocalRuntimeId.Termux -> termuxRuntime
        LocalRuntimeId.Alpine -> alpineRuntime
        LocalRuntimeId.EmbeddedTermux -> embeddedTermuxRuntime ?: termuxRuntime
    }

    /**
     * Resolve a runtime that is actually usable (installed/ready).
     * When no explicit environment is requested, falls back through enabled
     * runtimes in priority order: EmbeddedTermux -> Alpine -> Termux.
     */
    suspend fun resolveUsableRuntime(
        settings: AppSettings,
        environment: String?,
    ): LocalRuntime? {
        val candidates = buildList {
            runtimeFor(settings, environment)?.let(::add)
            val requested = environment?.trim().orEmpty().lowercase()
            if (requested.isEmpty() || requested == "default") {
                val enabled = settings.enabledRuntimeIds
                if (enabled.isNotEmpty()) {
                    listOf(
                        LocalRuntimeId.EmbeddedTermux,
                        LocalRuntimeId.Alpine,
                        LocalRuntimeId.Termux,
                    ).forEach { id ->
                        if (id in enabled && none { it.id == id }) add(runtimeById(id))
                    }
                }
            }
        }
        for (candidate in candidates) {
            if (candidate.inspectSetup().isReady) return candidate
        }
        return candidates.firstOrNull()
    }

    fun runtimeForRunId(runId: String): Pair<LocalRuntime, String>? {
        val separatorIndex = runId.indexOf(':')
        if (separatorIndex <= 0) return null
        val runtimeId = LocalRuntimeId.fromStorage(runId.substring(0, separatorIndex)) ?: return null
        return runtimeById(runtimeId) to runId.substring(separatorIndex + 1)
    }

    fun runtimeWorkspaceDirectory(
        settings: AppSettings,
        termuxWorkspaceDirectory: String,
        environment: String? = null,
    ): String {
        val runtime = runtimeFor(settings, environment)
            ?: runtimeFor(settings, null)
            ?: termuxRuntime
        return when (runtime.id) {
            LocalRuntimeId.Termux -> termuxWorkspaceDirectory
            LocalRuntimeId.Alpine -> runtime.workspaceRoot
            LocalRuntimeId.EmbeddedTermux -> runtime.workspaceRoot
        }
    }

    fun setupRequiredError(environment: String? = null): String =
        JSONObject().apply {
            put("ok", false)
            put("errmsg", "No local runtime is configured for this tool call.")
            put("hint", "Configure Alpine or Termux in Settings, or pass environment as 'alpine' or 'termux' after setup.")
            if (!environment.isNullOrBlank()) put("environment", environment)
        }.toString()
    private fun chooseOnlyEnabled(settings: AppSettings): LocalRuntimeId? =
        settings.enabledRuntimeIds.singleOrNull()

    private fun legacyDefault(settings: AppSettings): LocalRuntimeId? = when {
        settings.termuxSetupCompleted -> LocalRuntimeId.Termux
        settings.alpineSetupCompleted -> LocalRuntimeId.Alpine
        else -> null
    }
}

fun JSONObject.runtimeEnvironment(): String =
    optString("environment").trim()
        .ifBlank { optString("runtime").trim() }

