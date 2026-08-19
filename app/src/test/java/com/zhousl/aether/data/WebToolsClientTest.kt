package com.zhousl.aether.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebToolsClientTest {
    @Test
    fun fetchUrlAsMarkdownConvertsHtmlIntoMarkdown() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(
                    """
                    <html>
                      <head>
                        <title>Example Page</title>
                      </head>
                      <body>
                        <header>Top nav</header>
                        <main>
                          <article>
                            <h1>Hello from Aether</h1>
                            <p>Read the <a href="/docs">relative link</a>.</p>
                          </article>
                        </main>
                      </body>
                    </html>
                    """.trimIndent(),
                ),
        )
        server.start()

        try {
            val client = WebToolsClient()
            val result = client.fetchUrlAsMarkdown(server.url("/page").toString()).getOrThrow()

            assertEquals("Example Page", result.title)
            assertEquals(server.url("/page").toString(), result.finalUrl)
            assertTrue(result.markdown.contains("Example Page"))
            assertTrue(result.markdown.contains("Hello from Aether"))
            assertTrue(result.markdown.contains("relative link"))
            assertTrue(result.markdown.contains(server.url("/docs").toString()))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun tavilySearchUsesBearerAuthAndSearchEndpoint() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "query": "android agent",
                      "answer": "summary",
                      "results": [
                        {
                          "title": "Result One",
                          "url": "https://example.com/result",
                          "content": "Snippet"
                        }
                      ],
                      "response_time": "0.42",
                      "usage": { "credits": 1 },
                      "request_id": "req-123"
                    }
                    """.trimIndent(),
                ),
        )
        server.start()

        try {
            val client = WebToolsClient(tavilyBaseUrl = server.url("/").toString())
            val response = client.searchTavily(
                apiKey = "tvly-test",
                request = TavilySearchRequest(
                    query = "android agent",
                    searchDepth = "advanced",
                    includeRawContent = true,
                    includeDomains = listOf("example.com"),
                ),
            ).getOrThrow()

            assertEquals("summary", response.getString("answer"))

            val request = server.takeRequest()
            assertEquals("/search", request.path)
            assertEquals("Bearer tvly-test", request.getHeader("Authorization"))

            val payload = JSONObject(request.body.readUtf8())
            assertEquals("android agent", payload.getString("query"))
            assertEquals("advanced", payload.getString("search_depth"))
            assertEquals("markdown", payload.getString("include_raw_content"))
            assertEquals("basic", payload.getString("include_answer"))
            assertEquals(true, payload.getBoolean("include_favicon"))
            assertEquals("example.com", payload.getJSONArray("include_domains").getString(0))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun tavilySearchAcceptsFullSearchEndpoint() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("""{"query":"android agent","results":[]}"""),
        )
        server.start()

        try {
            val client = WebToolsClient()
            client.searchTavily(
                apiKey = "tvly-test",
                baseUrl = server.url("/proxy/tavily/search").toString(),
                request = TavilySearchRequest(query = "android agent"),
            ).getOrThrow()

            val request = server.takeRequest()
            assertEquals("/proxy/tavily/search", request.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun bingSearchParsesRssResults() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/rss+xml")
                .setBody(
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <rss version="2.0"><channel>
                      <item>
                        <title>Kotlin &amp; Compose</title>
                        <link>https://kotlinlang.org/</link>
                        <description>Multiplatform <b>guide</b></description>
                      </item>
                      <item>
                        <title>Second</title>
                        <link>https://example.com/2</link>
                        <description>Another result</description>
                      </item>
                    </channel></rss>
                    """.trimIndent(),
                ),
        )
        server.start()
        try {
            val client = WebToolsClient()
            val response = client.searchBing(
                query = "kotlin",
                maxResults = 5,
                baseUrl = server.url("/").toString(),
            ).getOrThrow()
            val results = response.getJSONArray("results")
            assertEquals(2, results.length())
            val first = results.getJSONObject(0)
            assertEquals("Kotlin & Compose", first.getString("title"))
            assertEquals("https://kotlinlang.org/", first.getString("url"))
            assertEquals("Multiplatform guide", first.getString("content"))
            val request = server.takeRequest()
            assertTrue(request.path!!.contains("format=rss"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun duckDuckGoSearchParsesHtmlResults() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(
                    """
                    <html><body>
                      <div class="result">
                        <h2><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fguide">Example Guide</a></h2>
                        <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fguide">Short snippet text</a>
                      </div>
                    </body></html>
                    """.trimIndent(),
                ),
        )
        server.start()
        try {
            val client = WebToolsClient()
            val response = client.searchDuckDuckGo(
                query = "aether",
                maxResults = 5,
                baseUrl = server.url("/").toString(),
            ).getOrThrow()
            val first = response.getJSONArray("results").getJSONObject(0)
            assertEquals("Example Guide", first.getString("title"))
            assertEquals("https://example.com/guide", first.getString("url"))
            assertEquals("Short snippet text", first.getString("content"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun searxngSearchUsesBearerAndParsesResults() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"results":[
                      {"title":"Aether","url":"https://aether.example","content":"Mobile agent"}
                    ]}
                    """.trimIndent(),
                ),
        )
        server.start()
        try {
            val client = WebToolsClient()
            val response = client.searchSearxng(
                baseUrl = server.url("/searxng").toString(),
                apiKey = "searx-secret",
                query = "aether",
                maxResults = 5,
            ).getOrThrow()
            val first = response.getJSONArray("results").getJSONObject(0)
            assertEquals("Aether", first.getString("title"))
            assertEquals("https://aether.example", first.getString("url"))
            val request = server.takeRequest()
            assertEquals("/searxng/search", request.requestUrl?.encodedPath)
            assertEquals("Bearer searx-secret", request.getHeader("Authorization"))
            assertTrue(request.requestUrl?.queryParameter("format") == "json")
        } finally {
            server.shutdown()
        }
    }
}