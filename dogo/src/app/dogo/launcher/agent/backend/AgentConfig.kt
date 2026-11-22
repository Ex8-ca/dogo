/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent.backend

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * The user's overrides for one agent. Null fields fall back to the preset.
 *
 * Kept per agent, so switching from Hermes to OpenRouter and back does not lose
 * the Hermes address or key.
 */
data class AgentConfig(
    val baseUrl: String? = null,
    val model: String? = null,
    val apiKey: String? = null,
) {
    // Never let a key reach a log line through string templates or crash reports.
    override fun toString(): String = "AgentConfig(baseUrl=$baseUrl, model=$model, apiKey=${redact(apiKey)})"

    companion object {
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_MODEL = "model"
        private const val KEY_API_KEY = "apiKey"

        fun encode(configs: Map<String, AgentConfig>): String {
            val root = JSONObject()
            configs.forEach { (id, config) ->
                val entry = JSONObject()
                config.baseUrl?.let { entry.put(KEY_BASE_URL, it) }
                config.model?.let { entry.put(KEY_MODEL, it) }
                config.apiKey?.let { entry.put(KEY_API_KEY, it) }
                root.put(id, entry)
            }
            return root.toString()
        }

        /** Never throws: a corrupt blob reads as "no overrides". */
        fun decode(json: String?): Map<String, AgentConfig> {
            if (json.isNullOrBlank()) return emptyMap()
            return try {
                val root = JSONObject(json)
                root.keys().asSequence().associateWith { id ->
                    val entry = root.optJSONObject(id) ?: JSONObject()
                    AgentConfig(
                        baseUrl = entry.optStringOrNull(KEY_BASE_URL),
                        model = entry.optStringOrNull(KEY_MODEL),
                        apiKey = entry.optStringOrNull(KEY_API_KEY),
                    )
                }
            } catch (e: Exception) {
                emptyMap()
            }
        }

        private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
    }
}

/** An agent with its defaults applied and its address normalised. */
data class ResolvedAgent(
    val preset: AgentPreset,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
) {
    override fun toString(): String = "ResolvedAgent(${preset.id}, baseUrl=$baseUrl, model=$model, apiKey=${redact(apiKey)})"

    /** What is wrong with this configuration, if anything. Empty means usable. */
    fun problems(): List<AgentProblem> {
        if (preset.kind == AgentKind.ON_DEVICE) return emptyList()
        if (preset.kind == AgentKind.TERMUX_CLI) {
            return if (TermuxCli.find(model) == null) listOf(AgentProblem.CLI_UNKNOWN) else emptyList()
        }
        val problems = mutableListOf<AgentProblem>()
        val url = baseUrl.toHttpUrlOrNull()
        when {
            baseUrl.isBlank() -> problems += AgentProblem.URL_MISSING
            url == null -> problems += AgentProblem.URL_INVALID
            !url.isHttps && !Endpoint.isLoopback(url.host) -> problems += AgentProblem.URL_INSECURE
        }
        if (model.isBlank()) problems += AgentProblem.MODEL_MISSING
        if (preset.key == KeyRequirement.REQUIRED && apiKey.isBlank()) problems += AgentProblem.KEY_MISSING
        return problems
    }
}

enum class AgentProblem(val message: String) {
    URL_MISSING("Enter the agent's address."),
    URL_INVALID("The agent's address isn't a valid http(s) URL."),
    URL_INSECURE(
        "Plain http only works for 127.0.0.1 on this phone. Use an https address, " +
            "or connect over USB with adb reverse and keep 127.0.0.1.",
    ),
    MODEL_MISSING("Choose a model. Test the connection to see what the server offers."),
    KEY_MISSING("This agent needs an API key."),
    CLI_UNKNOWN("Choose which CLI agent to run: claude, opencode, codex or hermes."),
}

fun AgentPreset.resolve(config: AgentConfig?): ResolvedAgent = ResolvedAgent(
    preset = this,
    baseUrl = Endpoint.normalize(config?.baseUrl?.takeIf { it.isNotBlank() } ?: defaultBaseUrl),
    model = (config?.model?.takeIf { it.isNotBlank() } ?: defaultModel).trim(),
    apiKey = config?.apiKey.orEmpty().trim(),
)

object Endpoint {

    private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1", "[::1]")

    fun isLoopback(host: String): Boolean = host.lowercase() in LOOPBACK

    /**
     * Tidies what people actually type into an address field.
     *
     * Adds a scheme when there is none (http for this phone, https anywhere
     * else), drops trailing slashes, and appends `/v1` to a bare host, which is
     * where every supported API lives.
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        // Decide about the scheme before trimming slashes, or "ht!tp://"
        // would lose its "//" and be mistaken for a bare host.
        var url = if (trimmed.contains("://")) {
            trimmed
        } else {
            val host = trimmed.substringBefore('/').substringBeforeLast(':')
            (if (isLoopback(host)) "http://" else "https://") + trimmed
        }
        url = url.trimEnd('/')
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (parsed.encodedPath == "/") url += "/v1"
        return url
    }
}

internal fun redact(secret: String?): String = when {
    secret.isNullOrEmpty() -> "<none>"
    else -> "<redacted>"
}
