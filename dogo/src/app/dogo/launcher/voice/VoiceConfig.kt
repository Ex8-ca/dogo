/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.voice

import android.content.Context
import app.dogo.launcher.agent.backend.KeystoreSecretBox
import app.dogo.launcher.agent.backend.SecretBox
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Where the voice key comes from.
 *
 * [Source.TOKEN_SERVER] (recommended): the OpenAI key stays on your computer.
 * Dogo asks a small token service there for a ten-minute client secret,
 * authenticating with [serverKey] (the same key as Hermes).
 *
 * [Source.API_KEY]: Dogo holds an OpenAI key itself, encrypted with the
 * Android Keystore.
 */
data class VoiceConfig(
    val source: Source = Source.TOKEN_SERVER,
    val tokenUrl: String = "",
    val serverKey: String = "",
    val apiKey: String = "",
    val model: String = RealtimeProtocol.DEFAULT_MODEL,
    val voice: String = RealtimeProtocol.DEFAULT_VOICE,
) {
    enum class Source { TOKEN_SERVER, API_KEY }

    /** Why voice can't start yet, or null if it can. */
    fun problem(): String? = when (source) {
        Source.TOKEN_SERVER -> when {
            !tokenUrl.trim().startsWith("https://") -> "Enter the token service address (https://...)."
            serverKey.isBlank() -> "Enter the key for the token service (the same key as Hermes)."
            else -> null
        }

        Source.API_KEY -> if (apiKey.isBlank()) "Paste an OpenAI API key." else null
    }

    fun toJson(): String = JSONObject()
        .put("source", source.name)
        .put("tokenUrl", tokenUrl)
        .put("serverKey", serverKey)
        .put("apiKey", apiKey)
        .put("model", model)
        .put("voice", voice)
        .toString()

    companion object {
        fun fromJson(json: String?): VoiceConfig {
            if (json.isNullOrBlank()) return VoiceConfig()
            return runCatching {
                val o = JSONObject(json)
                VoiceConfig(
                    source = runCatching { Source.valueOf(o.optString("source")) }.getOrDefault(Source.TOKEN_SERVER),
                    tokenUrl = o.optString("tokenUrl"),
                    serverKey = o.optString("serverKey"),
                    apiKey = o.optString("apiKey"),
                    model = o.optString("model").ifBlank { RealtimeProtocol.DEFAULT_MODEL },
                    voice = o.optString("voice").takeIf { it in RealtimeProtocol.VOICES } ?: RealtimeProtocol.DEFAULT_VOICE,
                )
            }.getOrDefault(VoiceConfig())
        }

        /** The token service next to a Hermes address, e.g. https://host:10000/v1 -> https://host:10000/dogo/realtime/token. */
        fun tokenUrlNextTo(hermesUrl: String): String? {
            val base = hermesUrl.trim().takeIf { it.startsWith("https://") } ?: return null
            val origin = Regex("^(https://[^/]+)").find(base)?.value ?: return null
            return "$origin/dogo/realtime/token"
        }
    }
}

/** Keeps [VoiceConfig] sealed with the Keystore; it holds keys. */
class VoiceStore(context: Context, private val secrets: SecretBox = KeystoreSecretBox()) {
    private val prefs = context.applicationContext.getSharedPreferences("dogo_voice", Context.MODE_PRIVATE)

    fun load(): VoiceConfig = VoiceConfig.fromJson(prefs.getString(KEY, null)?.let { secrets.open(it) })

    fun save(config: VoiceConfig) {
        prefs.edit().putString(KEY, secrets.seal(config.toJson())).apply()
    }

    private companion object {
        const val KEY = "config_sealed"
    }
}

object VoiceTokens {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    /** The token to open a Realtime socket with. */
    suspend fun fetch(config: VoiceConfig): Result<String> = withContext(Dispatchers.IO) {
        config.problem()?.let { return@withContext Result.failure(IllegalStateException(it)) }
        if (config.source == VoiceConfig.Source.API_KEY) return@withContext Result.success(config.apiKey.trim())
        runCatching {
            val request = Request.Builder()
                .url(config.tokenUrl.trim())
                .header("Authorization", "Bearer ${config.serverKey.trim()}")
                .post(JSONObject().put("model", config.model).toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body.string()
                val json = runCatching { JSONObject(body) }.getOrNull()
                check(resp.isSuccessful) {
                    json?.optString("error")?.ifBlank { null } ?: when (resp.code) {
                        401 -> "The token service refused the key."
                        404 -> "No token service at that address."
                        else -> "The token service said ${resp.code}."
                    }
                }
                json?.optString("value")?.takeIf { it.startsWith("ek_") } ?: error("The token service sent no key.")
            }
        }
    }
}
