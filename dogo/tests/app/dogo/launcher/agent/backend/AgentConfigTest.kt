/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent.backend

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AgentConfigTest {

    // ---- catalog ------------------------------------------------------------

    @Test
    fun `on-device is first, so it is the obvious default`() {
        assertThat(AgentCatalog.all.first()).isEqualTo(AgentCatalog.ON_DEVICE)
        assertThat(AgentCatalog.ON_DEVICE.leavesDevice).isFalse()
    }

    @Test
    fun `agent ids are unique and findable`() {
        val ids = AgentCatalog.all.map { it.id }
        assertThat(ids).containsNoDuplicates()
        AgentCatalog.all.forEach { assertThat(AgentCatalog.find(it.id)).isEqualTo(it) }
        assertThat(AgentCatalog.find("nope")).isNull()
        assertThat(AgentCatalog.find(null)).isNull()
    }

    @Test
    fun `every hosted preset defaults to https`() {
        AgentCatalog.all
            .filter { it.defaultBaseUrl.isNotEmpty() && !it.defaultBaseUrl.contains("127.0.0.1") }
            .forEach { assertThat(it.defaultBaseUrl).startsWith("https://") }
    }

    @Test
    fun `Hermes defaults to its API server through adb reverse`() {
        val hermes = AgentCatalog.HERMES.resolve(null)
        assertThat(hermes.baseUrl).isEqualTo("http://127.0.0.1:8642/v1")
        assertThat(hermes.model).isEqualTo("hermes-agent")
        assertThat(hermes.problems()).containsExactly(AgentProblem.KEY_MISSING)
    }

    // ---- resolution and validation -----------------------------------------

    @Test
    fun `overrides win over preset defaults, blanks do not`() {
        val agent = AgentCatalog.OPENROUTER.resolve(AgentConfig(baseUrl = "  ", model = "a/b", apiKey = " k "))
        assertThat(agent.baseUrl).isEqualTo(AgentCatalog.OPENROUTER.defaultBaseUrl)
        assertThat(agent.model).isEqualTo("a/b")
        assertThat(agent.apiKey).isEqualTo("k")
        assertThat(agent.problems()).isEmpty()
    }

    @Test
    fun `on-device never has problems`() {
        assertThat(AgentCatalog.ON_DEVICE.resolve(null).problems()).isEmpty()
    }

    @Test
    fun `custom needs an address and a model but not a key`() {
        assertThat(AgentCatalog.CUSTOM.resolve(null).problems())
            .containsExactly(AgentProblem.URL_MISSING, AgentProblem.MODEL_MISSING)
        val ok = AgentCatalog.CUSTOM.resolve(AgentConfig(baseUrl = "https://llm.example.com/v1", model = "m"))
        assertThat(ok.problems()).isEmpty()
    }

    @Test
    fun `plain http is refused except to this phone`() {
        // Android blocks cleartext anyway; say why before the request fails.
        val lan = AgentCatalog.CUSTOM.resolve(AgentConfig(baseUrl = "http://192.168.1.2:8642/v1", model = "m"))
        assertThat(lan.problems()).containsExactly(AgentProblem.URL_INSECURE)

        val local = AgentCatalog.CUSTOM.resolve(AgentConfig(baseUrl = "http://localhost:11434/v1", model = "m"))
        assertThat(local.problems()).isEmpty()
    }

    @Test
    fun `garbage addresses are reported, not thrown`() {
        val agent = AgentCatalog.CUSTOM.resolve(AgentConfig(baseUrl = "ht!tp://", model = "m"))
        assertThat(agent.problems()).containsExactly(AgentProblem.URL_INVALID)
    }

    @Test
    fun `every problem explains itself`() {
        AgentProblem.entries.forEach { assertThat(it.message.length).isGreaterThan(15) }
    }

    // ---- address normalisation ---------------------------------------------

    @Test
    fun `normalises what people type into an address field`() {
        assertThat(Endpoint.normalize("https://api.example.com/v1/")).isEqualTo("https://api.example.com/v1")
        assertThat(Endpoint.normalize("api.example.com")).isEqualTo("https://api.example.com/v1")
        assertThat(Endpoint.normalize("127.0.0.1:8642")).isEqualTo("http://127.0.0.1:8642/v1")
        assertThat(Endpoint.normalize("localhost:11434/v1")).isEqualTo("http://localhost:11434/v1")
        // An explicit path is respected: some gateways don't use /v1.
        assertThat(Endpoint.normalize("https://gw.example.com/openai")).isEqualTo("https://gw.example.com/openai")
        assertThat(Endpoint.normalize("   ")).isEmpty()
    }

    // ---- persistence and secrecy -------------------------------------------

    @Test
    fun `configs round-trip through their encoding`() {
        val configs = mapOf(
            "hermes" to AgentConfig(apiKey = "secret-1"),
            "custom" to AgentConfig(baseUrl = "https://x.example/v1", model = "m", apiKey = null),
        )
        assertThat(AgentConfig.decode(AgentConfig.encode(configs))).isEqualTo(configs)
    }

    @Test
    fun `corrupt encodings read as no overrides`() {
        assertThat(AgentConfig.decode(null)).isEmpty()
        assertThat(AgentConfig.decode("")).isEmpty()
        assertThat(AgentConfig.decode("{not json")).isEmpty()
    }

    @Test
    fun `keys never appear in toString`() {
        val config = AgentConfig(apiKey = "sk-very-secret")
        assertThat(config.toString()).doesNotContain("sk-very-secret")
        assertThat(AgentCatalog.OPENROUTER.resolve(config).toString()).doesNotContain("sk-very-secret")
    }
}
