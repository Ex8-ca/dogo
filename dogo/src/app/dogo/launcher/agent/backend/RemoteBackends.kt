/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent.backend

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Talks to any OpenAI-compatible server: Hermes Agent's API server, OpenRouter,
 * Groq, Ollama, LM Studio, llama-server and friends.
 *
 * Only the fields every one of them accepts are sent, plus reasoning effort
 * in the form each server is known to understand: Hermes reads
 * `model_options.reasoning.effort`, OpenRouter reads `reasoning.effort`.
 * Other servers get nothing extra, because unknown fields are a 400 on some.
 */
class OpenAiCompatibleBackend(
    private val agent: ResolvedAgent,
    private val client: OkHttpClient = RemoteHttp.defaultClient,
) : AgentBackend,
    ModelLister {

    override val label: String = "${agent.preset.displayName} · ${agent.model}"

    private fun headers(): Map<String, String> = buildMap {
        if (agent.apiKey.isNotBlank()) put("Authorization", "Bearer ${agent.apiKey}")
        if (agent.preset.id == AgentCatalog.OPENROUTER.id) put("X-Title", "Dogo")
    }

    override suspend fun reply(system: String, turns: List<ChatTurn>, options: ReplyOptions): Result<String> = resultOf {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        turns.forEach { turn ->
            val content: Any = if (turn.images.isEmpty()) {
                turn.text
            } else {
                JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", turn.text))
                    turn.images.forEach { jpeg ->
                        put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$jpeg")))
                    }
                }
            }
            messages.put(
                JSONObject()
                    .put("role", if (turn.role == ChatTurn.Role.USER) "user" else "assistant")
                    .put("content", content),
            )
        }
        val body = JSONObject()
            .put("model", agent.model)
            .put("messages", messages)
            .put("stream", false)
        options.effort?.let { effort ->
            val reasoning = JSONObject().put("effort", effort)
            when (agent.preset.id) {
                AgentCatalog.HERMES.id -> body.put("model_options", JSONObject().put("reasoning", reasoning))
                AgentCatalog.OPENROUTER.id -> body.put("reasoning", reasoning)
            }
        }

        val response = RemoteHttp.postJson(client, "${agent.baseUrl}/chat/completions", headers(), body)
        val message = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw AgentBackendException("The agent's reply had no message in it.")
        contentText(message.opt("content")).ifBlank {
            throw AgentBackendException("The agent sent back an empty reply.")
        }
    }

    override suspend fun listModels(): Result<List<String>> = resultOf {
        val response = RemoteHttp.get(client, "${agent.baseUrl}/models", headers())
        modelIds(response)
    }

    /** `content` is a string on most servers, an array of parts on some. */
    private fun contentText(content: Any?): String = when (content) {
        is String -> content

        is JSONArray -> (0 until content.length()).joinToString("") { index ->
            content.optJSONObject(index)?.optString("text").orEmpty()
        }

        else -> ""
    }.trim()
}

/** Talks to Anthropic's Messages API. */
class AnthropicBackend(
    private val agent: ResolvedAgent,
    private val client: OkHttpClient = RemoteHttp.defaultClient,
) : AgentBackend,
    ModelLister {

    override val label: String = "${agent.preset.displayName} · ${agent.model}"

    private fun headers(): Map<String, String> = mapOf(
        "x-api-key" to agent.apiKey,
        "anthropic-version" to API_VERSION,
    )

    override suspend fun reply(system: String, turns: List<ChatTurn>, options: ReplyOptions): Result<String> = resultOf {
        val messages = JSONArray()
        turns.forEach { turn ->
            val content: Any = if (turn.images.isEmpty()) {
                turn.text
            } else {
                JSONArray().apply {
                    turn.images.forEach { jpeg ->
                        put(
                            JSONObject().put("type", "image").put(
                                "source",
                                JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", jpeg),
                            ),
                        )
                    }
                    put(JSONObject().put("type", "text").put("text", turn.text))
                }
            }
            messages.put(
                JSONObject()
                    .put("role", if (turn.role == ChatTurn.Role.USER) "user" else "assistant")
                    .put("content", content),
            )
        }
        val body = JSONObject()
            .put("model", agent.model)
            .put("max_tokens", MAX_TOKENS)
            .put("system", system)
            .put("messages", messages)

        val response = RemoteHttp.postJson(client, "${agent.baseUrl}/messages", headers(), body)
        val blocks = response.optJSONArray("content")
            ?: throw AgentBackendException("Claude's reply had no content in it.")
        (0 until blocks.length())
            .mapNotNull { blocks.optJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .joinToString("") { it.optString("text") }
            .trim()
            .ifBlank { throw AgentBackendException("Claude sent back an empty reply.") }
    }

    override suspend fun listModels(): Result<List<String>> = resultOf {
        modelIds(RemoteHttp.get(client, "${agent.baseUrl}/models", headers()))
    }

    companion object {
        const val API_VERSION = "2023-06-01"

        // Copying a phone from a photo can propose a dozen actions.
        const val MAX_TOKENS = 2048
    }
}

/** Both APIs list models as `{"data": [{"id": ...}, ...]}`. */
private fun modelIds(response: JSONObject): List<String> {
    val data = response.optJSONArray("data") ?: return emptyList()
    return (0 until data.length())
        .mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }
        .distinct()
        .sorted()
}
