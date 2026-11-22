/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent

import org.json.JSONObject

/**
 * How the home screen looks: the knobs Lawnchair already has, gathered so the
 * agent can set several at once (for example to copy a phone from a photo).
 *
 * Every field is optional; null means "leave it as it is". Pure data so the
 * parsing and limits can be unit tested without a device.
 */
data class HomeStyle(
    val columns: Int? = null,
    val rows: Int? = null,
    val dockColumns: Int? = null,
    /** 0.5 to 1.5 times the normal icon size. */
    val iconSize: Float? = null,
    val labels: Boolean? = null,
    val iconShape: String? = null,
    /** Show the dock (hotseat) at all. */
    val dock: Boolean? = null,
    /** The search bar under the dock. */
    val searchBar: Boolean? = null,
    /** The "At a glance" date/time line at the top of the first page. */
    val glance: Boolean? = null,
    /** "#RRGGBB" accent colour. */
    val accent: String? = null,
    /** Package of an installed icon pack, or "" for system icons. */
    val iconPack: String? = null,
    /** Numbers on notification dots. */
    val notificationCounts: Boolean? = null,
) {
    val isEmpty: Boolean get() = this == HomeStyle()

    /** The same request with every number pulled into the range the launcher supports. */
    fun clamped(): HomeStyle = copy(
        columns = columns?.coerceIn(GRID_RANGE),
        rows = rows?.coerceIn(GRID_RANGE),
        dockColumns = dockColumns?.coerceIn(GRID_RANGE),
        iconSize = iconSize?.coerceIn(ICON_SIZE_RANGE),
        iconShape = iconShape?.trim()?.lowercase()?.takeIf { it in ICON_SHAPES },
        accent = accent?.trim()?.takeIf { HEX.matches(it) },
    )

    /** Plain-language list of what will change, for the approval card. */
    fun describe(): String = buildList {
        if (columns != null || rows != null) add("${columns ?: "same"} × ${rows ?: "same"} grid")
        dockColumns?.let { add("$it dock slots") }
        iconSize?.let { add("icons at ${(it * 100).toInt()}%") }
        labels?.let { add(if (it) "show labels" else "hide labels") }
        iconShape?.let { add("$it icons") }
        dock?.let { add(if (it) "show the dock" else "hide the dock") }
        searchBar?.let { add(if (it) "search bar on" else "no search bar") }
        glance?.let { add(if (it) "date and time at the top" else "no date line") }
        accent?.let { add("accent $it") }
        iconPack?.let { add(if (it.isEmpty()) "system icons" else "icon pack $it") }
        notificationCounts?.let { add(if (it) "counts on badges" else "plain badges") }
    }.joinToString(", ")

    fun toJson(): JSONObject = JSONObject().apply {
        columns?.let { put("columns", it) }
        rows?.let { put("rows", it) }
        dockColumns?.let { put("dock_columns", it) }
        iconSize?.let { put("icon_size", it.toDouble()) }
        labels?.let { put("labels", it) }
        iconShape?.let { put("icon_shape", it) }
        dock?.let { put("dock", it) }
        searchBar?.let { put("search_bar", it) }
        glance?.let { put("glance", it) }
        accent?.let { put("accent", it) }
        iconPack?.let { put("icon_pack", it) }
        notificationCounts?.let { put("notification_counts", it) }
    }

    companion object {
        val GRID_RANGE = 3..10
        val ICON_SIZE_RANGE = 0.5f..1.5f
        private val HEX = Regex("^#[0-9a-fA-F]{6}$")

        /** Lawnchair's shapes, by the names its settings use. */
        val ICON_SHAPES = listOf(
            "system", "circle", "square", "squircle", "sammy", "teardrop", "cylinder", "cupertino",
            "octagon", "hexagon", "diamond", "egg", "verysunny", "complexclover", "foursidedcookie",
            "sevensidedcookie", "arch", "cloudy", "flower", "heart", "leaf", "meow", "pebble",
            "roundedhexagon",
        )

        /** Never throws; anything it can't read is left as null. */
        fun fromJson(o: JSONObject): HomeStyle = HomeStyle(
            columns = o.intOrNull("columns"),
            rows = o.intOrNull("rows"),
            dockColumns = o.intOrNull("dock_columns"),
            iconSize = o.floatOrNull("icon_size"),
            labels = o.boolOrNull("labels"),
            iconShape = o.optString("icon_shape").trim().ifEmpty { null },
            dock = o.boolOrNull("dock"),
            searchBar = o.boolOrNull("search_bar"),
            glance = o.boolOrNull("glance"),
            accent = o.optString("accent").trim().ifEmpty { null },
            iconPack = if (o.has("icon_pack")) o.optString("icon_pack").trim() else null,
            notificationCounts = o.boolOrNull("notification_counts"),
        )

        private fun JSONObject.intOrNull(key: String): Int? = when (val v = opt(key)) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }

        private fun JSONObject.floatOrNull(key: String): Float? = when (val v = opt(key)) {
            is Number -> v.toFloat()
            is String -> v.trim().removeSuffix("%").toFloatOrNull()?.let { if (it > 3f) it / 100f else it }
            else -> null
        }

        private fun JSONObject.boolOrNull(key: String): Boolean? = when (val v = opt(key)) {
            is Boolean -> v

            is String -> when (v.trim().lowercase()) {
                "true", "yes", "on", "show" -> true
                "false", "no", "off", "hide" -> false
                else -> null
            }

            else -> null
        }
    }
}

/** A widget size like "4x2". Null when it isn't one. */
data class WidgetSize(val columns: Int, val rows: Int) {
    override fun toString() = "${columns}x$rows"

    companion object {
        private val PATTERN = Regex("^\\s*(\\d{1,2})\\s*[x×*by ]+\\s*(\\d{1,2})\\s*$", RegexOption.IGNORE_CASE)

        fun parse(text: String?): WidgetSize? {
            val m = PATTERN.matchEntire(text ?: return null) ?: return null
            val c = m.groupValues[1].toInt()
            val r = m.groupValues[2].toInt()
            return if (c in 1..10 && r in 1..10) WidgetSize(c, r) else null
        }
    }
}
