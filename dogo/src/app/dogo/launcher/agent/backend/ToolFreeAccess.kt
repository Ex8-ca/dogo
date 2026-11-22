/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent.backend

import app.dogo.launcher.agent.AgentOptions
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * The one place that decides where untrusted text (notifications, screens) may be sent.
 *
 * Allowed: a reviewed direct inference host ([ToolFreePolicy]), or, when Hermes is the chosen
 * agent, its separate tools-off route ([ToolFreeRoute]) after the server has confirmed it has no
 * tools. Everything else is refused. Callers must use [backend], never the chosen agent directly.
 */
object ToolFreeAccess {
    private const val ROUTE_NAME = "Hermes (tools off)"

    private class Verified(val key: String, val at: Long)

    @Volatile
    private var verified: Verified? = null

    /** The agent that would receive untrusted text for [options]; for naming the destination. */
    fun effectiveAgent(options: AgentOptions): ResolvedAgent {
        val chosen = options.resolvedAgent()
        return if (!ToolFreePolicy.supports(options.agentId, chosen.baseUrl) && options.agentId == AgentCatalog.HERMES.id) {
            safeAgent(options)
        } else {
            chosen
        }
    }

    fun safeAgent(options: AgentOptions): ResolvedAgent = AgentCatalog.HERMES_SAFE.resolve(options.agentConfigs[AgentCatalog.HERMES_SAFE.id])

    /** A quick check with no network: is there a route that could be used at all? */
    fun supported(options: AgentOptions): Boolean = when {
        ToolFreePolicy.supports(options.agentId, options.resolvedAgent().baseUrl) -> true

        options.agentId == AgentCatalog.HERMES.id ->
            ToolFreeRoute.settingsCheck(options.resolvedAgent(), safeAgent(options)) is ToolFreeRoute.Check.Safe

        else -> false
    }

    /** A short label for the screen, such as "Hermes (tools off) · https://host/p/dogosafe/v1". */
    fun describe(options: AgentOptions): String {
        val agent = effectiveAgent(options)
        return if (agent.preset.id == AgentCatalog.HERMES_SAFE.id) "$ROUTE_NAME · ${agent.baseUrl.ifBlank { "(no address yet)" }}" else ""
    }

    /** Why untrusted text cannot be sent right now, for the user. */
    fun reason(options: AgentOptions): String = when {
        options.agentId == AgentCatalog.HERMES.id -> (
            ToolFreeRoute.settingsCheck(options.resolvedAgent(), safeAgent(options)) as? ToolFreeRoute.Check.Refused
            )?.reason ?: ToolFreeRoute.UNAVAILABLE

        else -> ToolFreeRoute.UNAVAILABLE
    }

    /**
     * Asks the route what tools it has. Anything other than "none" is a refusal. A good answer is
     * trusted for five minutes, then asked again.
     */
    suspend fun verify(
        main: ResolvedAgent,
        safe: ResolvedAgent,
        now: Long = System.currentTimeMillis(),
        fetch: suspend (String) -> JSONObject,
    ): ToolFreeRoute.Check {
        val settings = ToolFreeRoute.settingsCheck(main, safe)
        if (settings is ToolFreeRoute.Check.Refused) return settings
        val key = "${safe.baseUrl}|${safe.apiKey.hashCode()}"
        verified?.let { if (it.key == key && now - it.at in 0..ToolFreeRoute.CACHE_MS) return ToolFreeRoute.Check.Safe }
        val answer = try {
            fetch("${safe.baseUrl.trimEnd('/')}/toolsets")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            verified = null
            return ToolFreeRoute.Check.Refused("Couldn't ask the tools-off route what tools it has: ${e.message.orEmpty().take(120)}")
        }
        val check = ToolFreeRoute.evaluate(answer)
        verified = if (check is ToolFreeRoute.Check.Safe) Verified(key, now) else null
        return check
    }

    /** A backend that is allowed to see untrusted text, or the reason it is not. */
    suspend fun backend(options: AgentOptions, client: OkHttpClient = RemoteHttp.defaultClient): Result<AgentBackend> = resultOf {
        val chosen = options.resolvedAgent()
        if (ToolFreePolicy.supports(options.agentId, chosen.baseUrl)) {
            return@resultOf checkNotNull(AgentBackends.remote(chosen, client)) { ToolFreeRoute.UNAVAILABLE }
        }
        if (options.agentId != AgentCatalog.HERMES.id) throw AgentBackendException(ToolFreeRoute.UNAVAILABLE)
        val safe = safeAgent(options)
        val check = verify(chosen, safe) { url -> RemoteHttp.get(client, url, mapOf("Authorization" to "Bearer ${safe.apiKey}")) }
        if (check is ToolFreeRoute.Check.Refused) throw AgentBackendException(check.reason)
        OpenAiCompatibleBackend(safe, client)
    }

    /** For tests and for "Test route": forget what was verified. */
    internal fun forget() {
        verified = null
    }

    /** "Test route": always asks the server, ignoring anything remembered. */
    suspend fun check(options: AgentOptions, client: OkHttpClient = RemoteHttp.defaultClient): ToolFreeRoute.Check {
        forget()
        val safe = safeAgent(options)
        return verify(options.resolvedAgent(), safe) { url -> RemoteHttp.get(client, url, mapOf("Authorization" to "Bearer ${safe.apiKey}")) }
    }
}
