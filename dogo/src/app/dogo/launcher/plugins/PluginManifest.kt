/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.plugins

import org.json.JSONArray
import org.json.JSONObject

/**
 * A Dogo plugin's `dogo-plugin.json`.
 *
 * Deliberately the same shape as an Omarchy plugin's `manifest.json`
 * (schemaVersion, namespaced id, name, version, author, license, description,
 * kinds, entryPoints), so an Omarchy author recognises it at a glance. The
 * differences are Android ones: entry points name Android components, and a
 * plugin must declare up front which capabilities it needs, because a phone
 * holds more private data than a desktop bar does.
 */
data class PluginManifest(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val license: String,
    val description: String,
    val kinds: Set<Kind>,
    val entryPoints: Map<Kind, String>,
    val capabilities: Set<Capability>,
    val actions: List<ActionSpec>,
    val homepage: String?,
    /** Set when ported from an Omarchy plugin, like Omarchy's own `omarchy` block. */
    val portedFrom: String?,
) {
    /**
     * What a plugin can be. Mirrors Omarchy's kinds where a phone has an
     * equivalent: bar-widget -> widget, panel -> panel, service -> service.
     */
    enum class Kind(val wire: String, val omarchyEquivalent: String?) {
        /** Adds actions the agent can propose (e.g. "start a timer"). */
        AGENT_ACTIONS("agent-actions", null),

        /** Adds facts to the agent's prompt (e.g. "next calendar event"). */
        AGENT_CONTEXT("agent-context", null),

        /** A home-screen widget (an Android AppWidget). */
        WIDGET("widget", "bar-widget"),

        /** A sheet opened from Dogo. */
        PANEL("panel", "panel"),

        /** Background work. */
        SERVICE("service", "service"),
        ;

        companion object {
            fun fromWire(value: String): Kind? = entries.firstOrNull { it.wire == value || it.omarchyEquivalent == value }
        }
    }

    /** What a plugin may be given. Each one is shown to the user before enabling. */
    enum class Capability(val wire: String, val title: String) {
        NETWORK("network", "Use the internet"),
        READ_APPS("read-apps", "See which apps are installed"),
        READ_SCREEN("read-screen", "Read text on the screen"),
        NOTIFICATIONS("notifications", "Show notifications"),
        SYSTEM_SETTINGS("system-settings", "Change system settings (needs Shizuku or root)"),
        ;

        companion object {
            fun fromWire(value: String): Capability? = entries.firstOrNull { it.wire == value }
        }
    }

    /** One action an agent-actions plugin offers. [params] are names and short descriptions. */
    data class ActionSpec(val name: String, val description: String, val params: Map<String, String>)

    /** Plugin actions are namespaced so two plugins can't collide with each other or with Dogo. */
    fun qualifiedAction(action: String): String = "$id:$action"

    companion object {
        const val SCHEMA_VERSION = 1
        const val ASSET_NAME = "dogo-plugin.json"

        /** The intent a plugin's entry-point components answer. */
        const val ACTION_PLUGIN = "app.dogo.launcher.PLUGIN"

        private val ID = Regex("^[a-z0-9]+(?:[.-][a-z0-9]+)*\\.[a-z0-9-]+$")
        private val ACTION_NAME = Regex("^[a-z][a-z0-9_]{0,40}$")

        /** Parses and validates; returns the problems instead of throwing. */
        fun parse(json: String): Result<PluginManifest> = runCatching {
            val o = JSONObject(json)
            val schema = o.optInt("schemaVersion", -1)
            require(schema == SCHEMA_VERSION) { "schemaVersion must be $SCHEMA_VERSION (got $schema)." }
            val id = o.optString("id")
            require(ID.matches(id)) { "id must be namespaced, like \"io.github.you.plugin\" (got \"$id\")." }

            val kinds = o.optJSONArray("kinds").strings().map { k ->
                Kind.fromWire(k) ?: throw IllegalArgumentException("Unknown kind \"$k\".")
            }.toSet()
            require(kinds.isNotEmpty()) { "kinds must list at least one kind." }

            val entryJson = o.optJSONObject("entryPoints") ?: JSONObject()
            val entryPoints = entryJson.keys().asSequence().associate { key ->
                val kind = Kind.entries.firstOrNull { camel(it.wire) == key || it.wire == key }
                    ?: throw IllegalArgumentException("Unknown entry point \"$key\".")
                kind to entryJson.getString(key)
            }
            kinds.forEach { kind ->
                require(kind in entryPoints) { "kinds includes ${kind.wire} but entryPoints has no \"${camel(kind.wire)}\"." }
            }

            val capabilities = o.optJSONArray("capabilities").strings().map { c ->
                Capability.fromWire(c) ?: throw IllegalArgumentException("Unknown capability \"$c\".")
            }.toSet()

            val actions = o.optJSONArray("actions").objects().map { a ->
                val name = a.optString("name")
                require(ACTION_NAME.matches(name)) { "Action name \"$name\" must be snake_case." }
                val params = a.optJSONObject("params")?.let { p -> p.keys().asSequence().associateWith { p.optString(it) } }.orEmpty()
                ActionSpec(name, a.optString("description"), params)
            }
            require(Kind.AGENT_ACTIONS !in kinds || actions.isNotEmpty()) { "An agent-actions plugin must declare its actions." }
            require(actions.map { it.name }.toSet().size == actions.size) { "Action names must be unique." }

            PluginManifest(
                schemaVersion = schema,
                id = id,
                name = o.optString("name").ifBlank { throw IllegalArgumentException("name is required.") },
                version = o.optString("version").ifBlank { throw IllegalArgumentException("version is required.") },
                author = o.optString("author"),
                license = o.optString("license").ifBlank { throw IllegalArgumentException("license is required (an SPDX id).") },
                description = o.optString("description"),
                kinds = kinds,
                entryPoints = entryPoints,
                capabilities = capabilities,
                actions = actions,
                homepage = o.optString("homepage").takeIf { it.isNotBlank() },
                portedFrom = o.optJSONObject("omarchy")?.optString("portedFrom")?.takeIf { it.isNotBlank() },
            )
        }

        /** "agent-actions" -> "agentActions", matching Omarchy's entryPoints keys. */
        fun camel(wire: String): String = wire.split('-').mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar { it.uppercase() } }.joinToString("")

        private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

        private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
