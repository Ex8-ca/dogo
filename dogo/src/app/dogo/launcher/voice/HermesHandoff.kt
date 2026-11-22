/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.voice

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hands a spoken request to Hermes and waits for the answer, the way Hermes
 * Vessel's use_tools does: POST {base}/runs, then poll {base}/runs/{id}. One
 * Hermes conversation is kept per call through [sessionId].
 *
 * [baseUrl] is the Hermes API address Dogo already uses, e.g.
 * https://host:10000/v1.
 */
class HermesHandoff(
    private val baseUrl: String,
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient,
    private val pollMs: Long = 1_000,
    private val timeoutMs: Long = 240_000,
) {
    var sessionId: String? = null
        private set

    /** Always returns something the voice can say; failures become plain sentences. */
    suspend fun ask(request: String): String = withContext(Dispatchers.IO) {
        val root = baseUrl.trimEnd('/')
        val body = JSONObject().put("input", request).apply { sessionId?.let { put("session_id", it) } }
        val runId = runCatching {
            client.newCall(post("$root/runs", body)).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) return@withContext "Hermes refused the request (${resp.code})."
                JSONObject(text).optString("run_id").ifBlank { null }
            }
        }.getOrElse { return@withContext "I couldn't reach Hermes (${it.javaClass.simpleName})." }
            ?: return@withContext "Hermes accepted the request but gave no run id."

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(pollMs)
            val run = runCatching {
                client.newCall(get("$root/runs/$runId")).execute().use { JSONObject(it.body.string()) }
            }.getOrNull() ?: continue
            run.optString("session_id").ifBlank { null }?.let { sessionId = it }
            when (run.optString("status")) {
                "completed" -> return@withContext outputText(run.opt("output")).trim().ifEmpty { "Hermes finished but said nothing." }
                "failed", "cancelled" -> return@withContext "Hermes couldn't finish (${run.optString("status")}): ${outputText(run.opt("error")).take(300)}"
            }
        }
        "Hermes is still working on that; it's taking longer than I can wait on this call."
    }

    private fun post(url: String, body: JSONObject) = Request.Builder().url(url)
        .header("Authorization", "Bearer $apiKey")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

    private fun get(url: String) = Request.Builder().url(url).header("Authorization", "Bearer $apiKey").get().build()

    companion object {
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        }

        /** Hermes run output may be a string, an object with text, or a list of either. */
        fun outputText(output: Any?): String = when (output) {
            null, JSONObject.NULL -> ""

            is String -> output

            is JSONObject -> listOf("text", "content", "final_response", "message").firstNotNullOfOrNull { k ->
                output.opt(k) as? String
            } ?: output.toString().take(4000)

            is JSONArray -> (0 until output.length()).joinToString("\n") { outputText(output.opt(it)) }

            else -> output.toString()
        }
    }
}
