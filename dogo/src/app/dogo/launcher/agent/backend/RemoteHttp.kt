/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent.backend

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Shared HTTP plumbing for the remote backends. */
internal object RemoteHttp {

    /**
     * Generous read timeout: an agent like Hermes may think, or call tools,
     * before it answers.
     */
    val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .build()
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun get(client: OkHttpClient, url: String, headers: Map<String, String>): JSONObject {
        val request = Request.Builder().url(url.toHttpUrl()).get().apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        return execute(client, request)
    }

    suspend fun postJson(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
    ): JSONObject {
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .post(body.toString().toRequestBody(JSON))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return execute(client, request)
    }

    private suspend fun execute(client: OkHttpClient, request: Request): JSONObject = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw AgentBackendException(describeHttp(response.code, text, request.url))
                }
                try {
                    JSONObject(text)
                } catch (e: Exception) {
                    throw AgentBackendException(
                        "${request.url.host} answered, but not with JSON. Is this the API address?",
                        e,
                    )
                }
            }
        } catch (e: AgentBackendException) {
            throw e
        } catch (e: IOException) {
            throw AgentBackendException(describeIo(e, request.url), e)
        }
    }

    fun describeHttp(code: Int, body: String, url: HttpUrl): String {
        val detail = errorDetail(body)?.let { ": $it" }.orEmpty()
        return when (code) {
            401, 403 -> "${url.host} rejected the API key (HTTP $code)$detail"
            404 -> "Nothing at $url (HTTP 404). Check the address; it usually ends in /v1."
            429 -> "${url.host} is rate limiting requests (HTTP 429). Try again shortly."
            else -> "${url.host} returned HTTP $code$detail"
        }
    }

    fun describeIo(e: IOException, url: HttpUrl): String = when (e) {
        is UnknownHostException -> "Can't find ${url.host}. Check the address and your connection."

        is ConnectException -> buildString {
            append("Couldn't connect to ${url.host}:${url.port}. Is the server running?")
            if (Endpoint.isLoopback(url.host)) {
                append(" If it runs on your computer, connect over USB and run ")
                append("`adb reverse tcp:${url.port} tcp:${url.port}`.")
            }
        }

        is SocketTimeoutException -> "Timed out waiting for ${url.host}."

        is SSLException -> "Secure connection to ${url.host} failed: ${e.message}"

        is UnknownServiceException ->
            if (e.message.orEmpty().contains("CLEARTEXT", ignoreCase = true)) {
                "Plain http to ${url.host} is blocked. Use https, or 127.0.0.1 with adb reverse."
            } else {
                e.message ?: "Request to ${url.host} failed."
            }

        else -> e.message ?: "Request to ${url.host} failed."
    }

    /** Pulls a human-readable message out of the common error body shapes. */
    private fun errorDetail(body: String): String? {
        val parsed = try {
            JSONObject(body)
        } catch (e: Exception) {
            null
        }
        val message = parsed?.let { json ->
            json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json.optString("error").takeIf { it.isNotBlank() && !it.startsWith("{") }
                ?: json.optString("message").takeIf { it.isNotBlank() }
        } ?: body.trim().takeIf { it.isNotEmpty() && !it.startsWith("<") }
        return message?.take(200)
    }
}
