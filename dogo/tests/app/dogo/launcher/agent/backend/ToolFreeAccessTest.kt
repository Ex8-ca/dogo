/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent.backend

import app.dogo.launcher.agent.AgentOptions
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Before
import org.junit.Test

class ToolFreeAccessTest {
    private val none = JSONObject("""{"data":[{"name":"web","enabled":false,"tools":[]}]}""")
    private val withTools = JSONObject("""{"data":[{"name":"terminal","enabled":true,"tools":["terminal"]}]}""")

    private val mainConfig = AgentConfig(baseUrl = "https://host.example:10000/v1", apiKey = "main-key")
    private val safeConfig = AgentConfig(baseUrl = "https://host.example:10000/p/dogosafe/v1", apiKey = "safe-key")

    private fun hermes(safe: AgentConfig? = safeConfig) = AgentOptions(
        agentId = AgentCatalog.HERMES.id,
        agentConfigs = buildMap {
            put(AgentCatalog.HERMES.id, mainConfig)
            if (safe != null) put(AgentCatalog.HERMES_SAFE.id, safe)
        },
    )

    private fun verify(options: AgentOptions, now: Long, fetch: suspend (String) -> JSONObject) = runBlocking {
        ToolFreeAccess.verify(options.resolvedAgent(), ToolFreeAccess.safeAgent(options), now, fetch)
    }

    @Before
    fun reset() = ToolFreeAccess.forget()

    @Test
    fun `the tools-off route is never offered as a chat agent`() {
        assertThat(AgentCatalog.all).doesNotContain(AgentCatalog.HERMES_SAFE)
        assertThat(AgentCatalog.find(AgentCatalog.HERMES_SAFE.id)).isNull()
    }

    @Test
    fun `plain Hermes is not allowed to read untrusted text`() {
        assertThat(ToolFreeAccess.supported(hermes(safe = null))).isFalse()
        assertThat(ToolFreeAccess.reason(hermes(safe = null))).contains("tools-off")
    }

    @Test
    fun `Hermes with its own route set up is allowed, and names the route`() {
        val options = hermes()

        assertThat(ToolFreeAccess.supported(options)).isTrue()
        assertThat(ToolFreeAccess.effectiveAgent(options).baseUrl).contains("/p/dogosafe/")
        assertThat(ToolFreeAccess.describe(options)).contains("tools off")
    }

    @Test
    fun `a route that reuses the normal address or key is not allowed`() {
        assertThat(ToolFreeAccess.supported(hermes(safe = safeConfig.copy(apiKey = "main-key")))).isFalse()
        assertThat(ToolFreeAccess.supported(hermes(safe = safeConfig.copy(baseUrl = mainConfig.baseUrl)))).isFalse()
        assertThat(ToolFreeAccess.supported(hermes(safe = safeConfig.copy(baseUrl = null)))).isFalse()
    }

    @Test
    fun `reviewed direct hosts still work and other agents are refused`() {
        val groq = AgentOptions(agentId = "groq", agentConfigs = mapOf("groq" to AgentConfig(baseUrl = "https://api.groq.com/openai/v1", apiKey = "k")))
        val custom = AgentOptions(agentId = "custom", agentConfigs = mapOf("custom" to AgentConfig(baseUrl = "https://example.org/v1", apiKey = "k")))

        assertThat(ToolFreeAccess.supported(groq)).isTrue()
        assertThat(ToolFreeAccess.supported(custom)).isFalse()
        assertThat(ToolFreeAccess.supported(AgentOptions())).isFalse()
    }

    @Test
    fun `the safe route is only trusted when the server says it has no tools`() {
        assertThat(verify(hermes(), 0) { none }).isEqualTo(ToolFreeRoute.Check.Safe)
        ToolFreeAccess.forget()
        assertThat(verify(hermes(), 0) { withTools }).isInstanceOf(ToolFreeRoute.Check.Refused::class.java)
    }

    @Test
    fun `it asks the server for toolsets on the safe route, with no double slash`() {
        val urls = mutableListOf<String>()

        verify(hermes(), 0) { url ->
            urls += url
            none
        }

        assertThat(urls).containsExactly("https://host.example:10000/p/dogosafe/v1/toolsets")
    }

    @Test
    fun `a good answer is remembered for five minutes and then checked again`() {
        var calls = 0
        val fetch: suspend (String) -> JSONObject = {
            calls++
            none
        }

        verify(hermes(), 0, fetch)
        verify(hermes(), ToolFreeRoute.CACHE_MS - 1, fetch)
        assertThat(calls).isEqualTo(1)
        verify(hermes(), ToolFreeRoute.CACHE_MS + 1, fetch)
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `a bad answer is never remembered, so the next try asks again`() {
        var calls = 0
        verify(hermes(), 0) {
            calls++
            withTools
        }
        verify(hermes(), 1) {
            calls++
            withTools
        }

        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `tools turned on after a good check are caught once the memory runs out`() {
        verify(hermes(), 0) { none }

        val later = verify(hermes(), ToolFreeRoute.CACHE_MS + 5) { withTools }

        assertThat(later).isInstanceOf(ToolFreeRoute.Check.Refused::class.java)
    }

    @Test
    fun `changing the key or address forgets the earlier good answer`() {
        var calls = 0
        val fetch: suspend (String) -> JSONObject = {
            calls++
            none
        }
        verify(hermes(), 0, fetch)
        verify(hermes(safeConfig.copy(apiKey = "another-key")), 1, fetch)
        verify(hermes(safeConfig.copy(baseUrl = "https://host.example:10000/p/other/v1")), 2, fetch)

        assertThat(calls).isEqualTo(3)
    }

    @Test
    fun `a network failure refuses the route and is not remembered`() {
        val failed = verify(hermes(), 0) { throw IOException("connection reset") }

        assertThat(failed).isInstanceOf(ToolFreeRoute.Check.Refused::class.java)
        assertThat((failed as ToolFreeRoute.Check.Refused).reason).contains("connection reset")
        var asked = false
        verify(hermes(), 1) {
            asked = true
            none
        }
        assertThat(asked).isTrue()
    }

    @Test
    fun `incomplete settings never reach the network`() {
        var asked = false

        val result = verify(hermes(safe = null), 0) {
            asked = true
            none
        }

        assertThat(result).isInstanceOf(ToolFreeRoute.Check.Refused::class.java)
        assertThat(asked).isFalse()
    }

    @Test
    fun `the backend for Hermes is refused when the route is missing, without any network call`() {
        val result = runBlocking { ToolFreeAccess.backend(hermes(safe = null)) }

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(AgentBackendException::class.java)
    }

    @Test
    fun `the alert destination changes when the route changes, so consent is asked again`() {
        val direct = hermes(safe = safeConfig)
        val moved = hermes(safe = safeConfig.copy(baseUrl = "https://host.example:10000/p/other/v1"))

        assertThat(ToolFreeAccess.effectiveAgent(direct).baseUrl).isNotEqualTo(ToolFreeAccess.effectiveAgent(moved).baseUrl)
        assertThat(ToolFreeAccess.effectiveAgent(direct).preset.id).isEqualTo(AgentCatalog.HERMES_SAFE.id)
    }
}
