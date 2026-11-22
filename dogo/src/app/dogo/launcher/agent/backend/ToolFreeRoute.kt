/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent.backend

import org.json.JSONObject

/**
 * A Hermes route whose server has no tools at all, for reading untrusted text.
 *
 * Hermes picks its tools from the server's own config, never from the request, so a prompt cannot
 * turn them off. A separate Hermes profile with an empty tool list is the safe route; see
 * docs/hermes-tools-off-route.md. The phone cannot see that config, but the server will say which
 * toolsets are on, and this refuses anything but "none".
 */
object ToolFreeRoute {
    /** The saved settings for this route live under this id, next to the other agents. */
    const val CONFIG_ID = "hermes-safe"

    /** How long a good answer is trusted before the server is asked again. */
    const val CACHE_MS = 5 * 60 * 1000L

    sealed interface Check {
        data object Safe : Check

        /** Something is wrong with the route; [reason] is for the user. */
        data class Refused(val reason: String) : Check
    }

    /** The settings are usable and clearly not the normal Hermes route. */
    fun settingsCheck(main: ResolvedAgent, safe: ResolvedAgent): Check {
        // A blank address quietly becomes the preset's default (the normal Hermes route), so it must be typed in.
        if (safe.baseUrl.isBlank() || safe.baseUrl == AgentCatalog.HERMES.defaultBaseUrl || safe.apiKey.isBlank()) {
            return Check.Refused("Add the address and key of your tools-off Hermes route in Dogo Options.")
        }
        if (safe.problems().isNotEmpty()) {
            return Check.Refused(safe.problems().first().message)
        }
        if (safe.baseUrl.trimEnd('/') == main.baseUrl.trimEnd('/') || safe.apiKey == main.apiKey) {
            return Check.Refused("The tools-off route must use its own address and its own key, not the normal Hermes ones.")
        }
        return Check.Safe
    }

    /** Reads the answer of `GET <route>/toolsets`. Anything unexpected is a refusal. */
    fun evaluate(response: JSONObject?): Check {
        val rows = response?.optJSONArray("data")
            ?: return Check.Refused("The route did not say which tools it has, so it can't be trusted.")
        if (rows.length() == 0) {
            return Check.Refused("The route reported no toolset list, so it can't be trusted.")
        }
        val enabled = mutableListOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i)
                ?: return Check.Refused("The route's tool list was not readable, so it can't be trusted.")
            val tools = row.optJSONArray("tools")?.length() ?: 0
            if (!row.has("enabled")) {
                return Check.Refused("The route's tool list was incomplete, so it can't be trusted.")
            }
            if (row.optBoolean("enabled", true) || tools > 0) {
                enabled += row.optString("name").take(24).ifBlank { "?" }
            }
        }
        if (enabled.isNotEmpty()) {
            val shown = enabled.take(4).joinToString(", ")
            val more = if (enabled.size > 4) " and ${enabled.size - 4} more" else ""
            return Check.Refused("That route still has tools turned on ($shown$more). Turn them off on the server first.")
        }
        return Check.Safe
    }

    val UNAVAILABLE: String =
        "Reading notifications and screens needs a route with no tools. Set up the tools-off Hermes route in Dogo Options, " +
            "or choose Groq, OpenRouter or Anthropic. The inbox and alert rules still work."
}
