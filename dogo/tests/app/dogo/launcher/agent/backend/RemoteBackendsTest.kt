/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent.backend

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Exercises the real HTTP code against a local server, so request shape,
 * headers, and error handling are checked end to end without the network.
 */
class RemoteBackendsTest {

    private data class Recorded(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Recorded>()

    /** path -> (status, body). */
    private val routes = mutableMapOf<String, Pair<Int, String>>()

    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    private val base: String
        get() = "http://127.0.0.1:${server.address.port}/v1"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += Recorded(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.first() },
                body,
            )
            val (status, reply) = routes[exchange.requestURI.path] ?: (404 to """{"error":"no route"}""")
            val bytes = reply.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    private fun agent(preset: AgentPreset, model: String = "test-model", key: String = "k-123") = preset.resolve(AgentConfig(baseUrl = base, model = model, apiKey = key))

    private val turns = listOf(
        ChatTurn(ChatTurn.Role.USER, "hi"),
        ChatTurn(ChatTurn.Role.ASSISTANT, "hello"),
        ChatTurn(ChatTurn.Role.USER, "make it dark"),
    )

    // ---- OpenAI-compatible --------------------------------------------------

    @Test
    fun `openai backend sends the conversation and returns the reply`() = runBlocking {
        routes["/v1/chat/completions"] = 200 to
            """{"choices":[{"message":{"role":"assistant","content":"{\"action\": \"set_theme\", \"params\": {\"theme\": \"dark\"}}"}}]}"""

        val reply = OpenAiCompatibleBackend(agent(AgentCatalog.HERMES, model = "hermes-agent"), client)
            .reply("SYSTEM", turns)

        assertThat(reply.getOrThrow()).contains("set_theme")
        val request = requests.single()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/v1/chat/completions")
        assertThat(request.headers["authorization"]).isEqualTo("Bearer k-123")

        val body = JSONObject(request.body)
        assertThat(body.getString("model")).isEqualTo("hermes-agent")
        assertThat(body.getBoolean("stream")).isFalse()
        val messages = body.getJSONArray("messages")
        assertThat(messages.length()).isEqualTo(4)
        assertThat(messages.getJSONObject(0).getString("role")).isEqualTo("system")
        assertThat(messages.getJSONObject(0).getString("content")).isEqualTo("SYSTEM")
        assertThat(messages.getJSONObject(2).getString("role")).isEqualTo("assistant")
        assertThat(messages.getJSONObject(3).getString("content")).isEqualTo("make it dark")
    }

    @Test
    fun `openai backend accepts content as an array of parts`() = runBlocking {
        routes["/v1/chat/completions"] = 200 to
            """{"choices":[{"message":{"content":[{"type":"text","text":"Dogo "},{"type":"text","text":"connected"}]}}]}"""
        val reply = OpenAiCompatibleBackend(agent(AgentCatalog.CUSTOM), client).reply("s", turns)
        assertThat(reply.getOrThrow()).isEqualTo("Dogo connected")
    }

    @Test
    fun `no key means no authorization header`() = runBlocking {
        routes["/v1/chat/completions"] = 200 to """{"choices":[{"message":{"content":"ok"}}]}"""
        OpenAiCompatibleBackend(agent(AgentCatalog.OLLAMA, key = ""), client).reply("s", turns).getOrThrow()
        assertThat(requests.single().headers).doesNotContainKey("authorization")
    }

    @Test
    fun `openrouter requests identify the app`() = runBlocking {
        routes["/v1/chat/completions"] = 200 to """{"choices":[{"message":{"content":"ok"}}]}"""
        OpenAiCompatibleBackend(agent(AgentCatalog.OPENROUTER), client).reply("s", turns).getOrThrow()
        assertThat(requests.single().headers["x-title"]).isEqualTo("Dogo")
    }

    @Test
    fun `a rejected key becomes a readable failure`() = runBlocking {
        routes["/v1/chat/completions"] = 401 to """{"error":{"message":"Invalid API key"}}"""
        val result = OpenAiCompatibleBackend(agent(AgentCatalog.HERMES), client).reply("s", turns)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertThat(message).contains("rejected the API key")
        assertThat(message).contains("Invalid API key")
    }

    @Test
    fun `a wrong path says to check the address`() = runBlocking {
        val result = OpenAiCompatibleBackend(agent(AgentCatalog.CUSTOM), client).reply("s", turns)
        assertThat(result.exceptionOrNull()?.message).contains("Check the address")
    }

    @Test
    fun `an empty reply is a failure, not a blank bubble`() = runBlocking {
        routes["/v1/chat/completions"] = 200 to """{"choices":[{"message":{"content":"   "}}]}"""
        val result = OpenAiCompatibleBackend(agent(AgentCatalog.CUSTOM), client).reply("s", turns)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `a non-json answer is explained`() = runBlocking {
        routes["/v1/chat/completions"] = 200 to "<html>login page</html>"
        val result = OpenAiCompatibleBackend(agent(AgentCatalog.CUSTOM), client).reply("s", turns)
        assertThat(result.exceptionOrNull()?.message).contains("not with JSON")
    }

    @Test
    fun `nothing listening on this phone suggests adb reverse`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort } // free port, nothing bound
        val dead = AgentCatalog.HERMES.resolve(AgentConfig(baseUrl = "http://127.0.0.1:$port/v1", apiKey = "k"))
        val result = OpenAiCompatibleBackend(dead, client).reply("s", turns)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertThat(message).contains("Couldn't connect")
        assertThat(message).contains("adb reverse tcp:$port tcp:$port")
    }

    @Test
    fun `lists models sorted and de-duplicated`() = runBlocking {
        routes["/v1/models"] = 200 to """{"object":"list","data":[{"id":"b"},{"id":"a"},{"id":"b"},{"id":""}]}"""
        val models = OpenAiCompatibleBackend(agent(AgentCatalog.GROQ), client).listModels()
        assertThat(models.getOrThrow()).containsExactly("a", "b").inOrder()
        assertThat(requests.single().method).isEqualTo("GET")
    }

    // ---- Anthropic ----------------------------------------------------------

    @Test
    fun `anthropic backend uses the messages API shape`() = runBlocking {
        routes["/v1/messages"] = 200 to
            """{"content":[{"type":"thinking","thinking":"..."},{"type":"text","text":"Dogo connected"}]}"""

        val reply = AnthropicBackend(agent(AgentCatalog.ANTHROPIC, model = "claude-x"), client).reply("SYSTEM", turns)

        assertThat(reply.getOrThrow()).isEqualTo("Dogo connected")
        val request = requests.single()
        assertThat(request.path).isEqualTo("/v1/messages")
        assertThat(request.headers["x-api-key"]).isEqualTo("k-123")
        assertThat(request.headers["anthropic-version"]).isEqualTo(AnthropicBackend.API_VERSION)
        assertThat(request.headers).doesNotContainKey("authorization")

        val body = JSONObject(request.body)
        assertThat(body.getString("system")).isEqualTo("SYSTEM")
        assertThat(body.getInt("max_tokens")).isGreaterThan(0)
        val messages = body.getJSONArray("messages")
        // No system role inside messages for Anthropic.
        assertThat(messages.length()).isEqualTo(3)
        assertThat(messages.getJSONObject(0).getString("role")).isEqualTo("user")
    }

    @Test
    fun `thinking effort is sent the way each server understands it`() = runBlocking {
        routes["/v1/chat/completions"] = 200 to """{"choices":[{"message":{"content":"ok"}}]}"""
        val high = ReplyOptions(effort = "high")

        OpenAiCompatibleBackend(agent(AgentCatalog.HERMES), client).reply("s", turns, high).getOrThrow()
        OpenAiCompatibleBackend(agent(AgentCatalog.OPENROUTER), client).reply("s", turns, high).getOrThrow()
        OpenAiCompatibleBackend(agent(AgentCatalog.CUSTOM), client).reply("s", turns, high).getOrThrow()
        OpenAiCompatibleBackend(agent(AgentCatalog.HERMES), client).reply("s", turns).getOrThrow()

        val (hermes, openrouter, custom, hermesDefault) = requests.map { JSONObject(it.body) }
        assertThat(hermes.getJSONObject("model_options").getJSONObject("reasoning").getString("effort")).isEqualTo("high")
        assertThat(openrouter.getJSONObject("reasoning").getString("effort")).isEqualTo("high")
        // Unknown servers get nothing extra: unknown fields are a 400 on some.
        assertThat(custom.has("reasoning")).isFalse()
        assertThat(custom.has("model_options")).isFalse()
        assertThat(hermesDefault.has("model_options")).isFalse()
    }

    @Test
    fun `factory picks the backend matching the agent kind`() {
        assertThat(AgentBackends.remote(agent(AgentCatalog.HERMES))).isInstanceOf(OpenAiCompatibleBackend::class.java)
        assertThat(AgentBackends.remote(agent(AgentCatalog.ANTHROPIC))).isInstanceOf(AnthropicBackend::class.java)
        assertThat(AgentBackends.remote(AgentCatalog.ON_DEVICE.resolve(null))).isNull()
    }
}
