/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ring

import org.json.JSONArray
import org.json.JSONObject

// The Dogo Ring: a radial menu that opens under your thumb when you touch and
// hold an empty spot on the home screen. Inspired by Tenkai Launcher's pies.
// Everything in this file is plain Kotlin so it can be unit tested off-device.

/** How the ring is drawn. All styles take their colour from Dogo's accent (e.g. an Omarchy theme). */
enum class RingStyle(val wireName: String, val title: String) {
    /** Neon double ring, glowing selection and a finger trail. */
    GLOW("glow", "Glow"),

    /** Translucent scope with a sweep beam that swings to your selection. */
    RADAR("radar", "Radar"),

    /** Orange ring with embers that rise from your finger. */
    FIRE("fire", "Fire"),

    /** Neon cyan double ring; lightning jumps from the hub to your choice. */
    ELECTRIC("electric", "Electric"),

    /** Flat dark disc, no effects. */
    MINIMAL("minimal", "Minimal"),
    ;

    companion object {
        fun fromWire(value: String?): RingStyle? {
            val v = value?.trim()?.lowercase() ?: return null
            // Tenkai's names for the same looks.
            val name = when (v) {
                "magic", "sparkle" -> "glow"
                "dark", "plain" -> "minimal"
                else -> v
            }
            return entries.firstOrNull { it.wireName == name }
        }
    }
}

/** A named group of apps, by package name, as the user (or agent) arranged it. */
data class RingGroup(val name: String, val apps: List<String>)

data class RingConfig(
    val enabled: Boolean = true,
    val style: RingStyle = RingStyle.GLOW,
    /** How long to hold still before the ring opens. Shorter than a long-press. */
    val holdMs: Long = DEFAULT_HOLD_MS,
    /** null means "group my apps automatically". */
    val groups: List<RingGroup>? = null,
) {
    val isAutomatic: Boolean get() = groups == null

    fun toJson(): String = JSONObject().apply {
        put("enabled", enabled)
        put("style", style.wireName)
        put("holdMs", holdMs)
        groups?.let { list ->
            put(
                "groups",
                JSONArray().apply {
                    list.forEach { g -> put(JSONObject().put("name", g.name).put("apps", JSONArray(g.apps))) }
                },
            )
        }
    }.toString()

    companion object {
        const val DEFAULT_HOLD_MS = 160L
        val HOLD_RANGE = 80L..400L

        /** Never throws: a damaged file falls back to [fallback]. */
        fun fromJson(json: String?, fallback: RingConfig = RingConfig()): RingConfig {
            if (json.isNullOrBlank()) return fallback
            return runCatching {
                val o = JSONObject(json)
                RingConfig(
                    enabled = o.optBoolean("enabled", fallback.enabled),
                    style = RingStyle.fromWire(o.optString("style")) ?: fallback.style,
                    holdMs = o.optLong("holdMs", fallback.holdMs).coerceIn(HOLD_RANGE),
                    groups = o.optJSONArray("groups")?.let { parseGroups(it) },
                )
            }.getOrDefault(fallback)
        }

        fun parseGroups(array: JSONArray): List<RingGroup> = (0 until array.length()).mapNotNull { i ->
            val g = array.optJSONObject(i) ?: return@mapNotNull null
            val name = g.optString("name").trim()
            val apps = g.optJSONArray("apps")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).trim().ifEmpty { null } } }.orEmpty()
            if (name.isEmpty()) null else RingGroup(name.take(MAX_NAME), apps.distinct())
        }

        const val MAX_NAME = 24
    }
}

/** What a slice of the ring does. */
sealed interface RingItem {
    val label: String

    data class App(val pkg: String, override val label: String) : RingItem

    data class Group(override val label: String, val children: List<RingItem>) : RingItem

    data class Command(val command: RingCommand, override val label: String) : RingItem
}

enum class RingCommand {
    /** Start a voice call (the light in the middle). */
    TALK,
    ASK_DOGO,
    APP_DRAWER,

    /** Wallpaper, widgets, home settings: the items from the old long-press menu. */
    HOME_OPTIONS,
    DOGO_OPTIONS,
}
