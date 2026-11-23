/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent

import app.dogo.launcher.ring.RingConfig
import app.dogo.launcher.ring.RingGroup
import app.dogo.launcher.tiles.TileParser
import org.json.JSONObject

/**
 * A single configuration change the agent proposes.
 *
 * Each action maps onto exactly one user-visible change, so the setup UI can
 * show the user a checklist and apply nothing until they approve it.
 */
sealed class Action {

    /** Dogo's own light/dark theme. Needs no special access. */
    data class SetTheme(val theme: String) : Action()

    /**
     * The whole phone's dark mode (Settings > Display). Needs Shizuku, root or
     * system access; plain apps can't change it.
     */
    data class SetSystemDarkMode(val enabled: Boolean) : Action()

    /** A flat colour, or a top-to-bottom gradient when [color2] is set. */
    data class SetWallpaper(val color: String, val color2: String? = null) : Action()

    /**
     * Puts a widget on the home screen. [provider] is "package/class" or just a
     * package (its first widget). [size] is like "4x2"; [page] counts from 0.
     */
    data class AddWidget(val provider: String, val size: String? = null, val page: Int? = null) : Action()

    /** Grid, icons, dock, accent and the other look-and-feel switches. */
    data class StyleHome(val style: HomeStyle) : Action()

    /**
     * Arranges widgets on the home screen from a tile file (see tiles/TileParser). [text] is data,
     * never run; Dogo checks it line by line and shows what would change before anything moves.
     */
    data class WriteTiles(val text: String) : Action()

    /** Replaces the dock's apps, left to right. */
    data class SetDock(val apps: List<String>) : Action()

    data class AddToHome(val pkg: String) : Action()

    data class HideApp(val pkg: String) : Action()

    data class CreateFolder(val name: String, val apps: List<String>) : Action()

    data class SetAnimationScale(val scale: Float) : Action()

    /**
     * Arranges the Dogo Ring (the radial menu on the home screen): named
     * groups of apps and, optionally, its style.
     */
    data class ArrangeRing(val groups: List<RingGroup>, val style: String?) : Action()

    /**
     * An action offered by an enabled plugin, named `pluginId:action`.
     * [paramsJson] is passed through to the plugin untouched.
     */
    data class Plugin(val pluginId: String, val name: String, val paramsJson: String) : Action()

    object Unknown : Action()
}

/**
 * Reads and writes the JSON action blocks the on-device model emits.
 *
 * Model output is untrusted input: it may be truncated, wrapped in prose, or
 * fenced as markdown. Nothing in here throws — anything that cannot be
 * understood becomes [Action.Unknown], which the caller simply skips.
 */
object ActionExecutor {

    private const val KEY_ACTION = "action"
    private const val KEY_PARAMS = "params"

    /**
     * Parses the first action object found in [json], which may be bare JSON or
     * JSON embedded in surrounding prose.
     */
    fun parseAction(json: String): Action {
        val block = extractFirstJsonAction(json) ?: return Action.Unknown
        return try {
            parseObject(JSONObject(block))
        } catch (e: Exception) {
            Action.Unknown
        }
    }

    /**
     * Parses every action in [text], in order, skipping anything that isn't
     * one. The system prompt asks for one action per line, so a single reply
     * can propose several changes.
     */
    fun parseActions(text: String): List<Action> {
        val actions = mutableListOf<Action>()
        var index = text.indexOf('{')
        while (index >= 0) {
            val end = findMatchingBrace(text, index)
            if (end < 0) {
                // A stray "{" in prose; a later object may still be balanced.
                index = text.indexOf('{', index + 1)
                continue
            }
            val candidate = text.substring(index, end + 1)
            val action = if (candidate.contains("\"$KEY_ACTION\"")) {
                try {
                    parseObject(JSONObject(candidate))
                } catch (e: Exception) {
                    Action.Unknown
                }
            } else {
                Action.Unknown
            }
            if (action != Action.Unknown) {
                actions += action
                // Skip past this object so its nested params aren't rescanned.
                index = text.indexOf('{', end + 1)
            } else {
                index = text.indexOf('{', index + 1)
            }
        }
        return actions
    }

    private fun parseObject(obj: JSONObject): Action {
        val name = obj.optString(KEY_ACTION)
        // Require the params object: a bare action name says nothing about what
        // to change, and guessing defaults would silently do the wrong thing.
        val params = obj.optJSONObject(KEY_PARAMS) ?: return Action.Unknown

        return when (name) {
            "set_theme" -> Action.SetTheme(params.optString("theme"))

            "set_system_dark_mode" -> {
                // Require an explicit boolean; guessing would flip the phone.
                if (!params.has("enabled")) return Action.Unknown
                val raw = params.opt("enabled")
                val enabled = when (raw) {
                    is Boolean -> raw
                    is String -> raw.toBooleanStrictOrNull() ?: return Action.Unknown
                    else -> return Action.Unknown
                }
                Action.SetSystemDarkMode(enabled)
            }

            "set_wallpaper" -> Action.SetWallpaper(
                params.optString("color"),
                (params.optString("color2").ifBlank { params.optString("gradient_to") }).trim().ifEmpty { null },
            )

            "add_widget" -> {
                val provider = params.optString("widget").ifBlank { params.optString("provider") }.trim()
                if (provider.isEmpty()) return Action.Unknown
                val page = if (params.has("page")) params.optInt("page", -1).takeIf { it >= 0 } else null
                Action.AddWidget(provider, params.optString("size").trim().ifEmpty { null }, page)
            }

            "style_home" -> {
                val style = HomeStyle.fromJson(params)
                if (style.isEmpty) return Action.Unknown
                Action.StyleHome(style)
            }

            "write_tiles" -> {
                val text = params.optString("file").ifBlank { params.optString("tiles") }
                if (text.isBlank()) return Action.Unknown
                Action.WriteTiles(text.take(TileParser.MAX_CHARS + 1))
            }

            "set_dock" -> {
                val appsArray = params.optJSONArray("apps") ?: return Action.Unknown
                val apps = (0 until appsArray.length()).mapNotNull { appsArray.optString(it).trim().ifEmpty { null } }
                if (apps.isEmpty()) return Action.Unknown
                Action.SetDock(apps.distinct())
            }

            "add_to_home" -> Action.AddToHome(params.optString("package"))

            "hide_app" -> Action.HideApp(params.optString("package"))

            "set_animation_scale" ->
                Action.SetAnimationScale(params.optDouble("scale", 1.0).toFloat())

            "create_folder" -> {
                val appsArray = params.optJSONArray("apps")
                val apps = (0 until (appsArray?.length() ?: 0)).mapNotNull { index ->
                    appsArray?.optString(index)?.takeIf { it.isNotBlank() }
                }
                Action.CreateFolder(params.optString("name"), apps)
            }

            "arrange_ring" -> {
                val groups = params.optJSONArray("groups")?.let { RingConfig.parseGroups(it) }.orEmpty()
                val style = params.optString("style").trim().ifEmpty { null }
                if (groups.isEmpty() && style == null) return Action.Unknown
                Action.ArrangeRing(groups, style)
            }

            else -> pluginAction(name, params)
        }
    }

    private val PLUGIN_ACTION = Regex("^([a-z0-9]+(?:[.-][a-z0-9]+)*\\.[a-z0-9-]+):([a-z][a-z0-9_]{0,40})$")

    /** `io.github.you.timer:start_timer` -> [Action.Plugin]; anything else is unknown. */
    private fun pluginAction(name: String, params: JSONObject): Action {
        val match = PLUGIN_ACTION.matchEntire(name) ?: return Action.Unknown
        return Action.Plugin(match.groupValues[1], match.groupValues[2], params.toString())
    }

    /**
     * Returns the first balanced `{...}` block in [text] that looks like an
     * action, or null if there is none.
     *
     * This scans rather than regex-matches because action objects nest a
     * `params` object, and because braces also show up inside quoted strings.
     */
    fun extractFirstJsonAction(text: String): String? {
        var index = text.indexOf('{')
        while (index >= 0) {
            val end = findMatchingBrace(text, index)
            if (end > index) {
                val candidate = text.substring(index, end + 1)
                if (candidate.contains("\"$KEY_ACTION\"")) {
                    return candidate
                }
            }
            index = text.indexOf('{', index + 1)
        }
        return null
    }

    /**
     * Index of the `}` closing the `{` at [start], or -1 if unbalanced.
     */
    private fun findMatchingBrace(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false

                inString && c == '\\' -> escaped = true

                c == '"' -> inString = !inString

                inString -> Unit

                c == '{' -> depth++

                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /**
     * Renders [action] back to the JSON the model is asked to emit.
     *
     * Built by hand rather than via [JSONObject] because JSONObject does not
     * guarantee key order, and a stable representation keeps this round-trippable.
     */
    fun toJson(action: Action): String = when (action) {
        is Action.SetTheme ->
            """{"action":"set_theme","params":{"theme":${quote(action.theme)}}}"""

        is Action.SetSystemDarkMode ->
            """{"action":"set_system_dark_mode","params":{"enabled":${action.enabled}}}"""

        is Action.SetWallpaper -> {
            val second = action.color2?.let { ""","color2":${quote(it)}""" }.orEmpty()
            """{"action":"set_wallpaper","params":{"color":${quote(action.color)}$second}}"""
        }

        is Action.AddWidget -> {
            val size = action.size?.let { ""","size":${quote(it)}""" }.orEmpty()
            val page = action.page?.let { ""","page":$it""" }.orEmpty()
            """{"action":"add_widget","params":{"widget":${quote(action.provider)}$size$page}}"""
        }

        is Action.StyleHome -> """{"action":"style_home","params":${action.style.toJson()}}"""

        is Action.WriteTiles -> """{"action":"write_tiles","params":{"file":${quote(action.text)}}}"""

        is Action.SetDock -> """{"action":"set_dock","params":{"apps":${action.apps.toJsonArray()}}}"""

        is Action.AddToHome ->
            """{"action":"add_to_home","params":{"package":${quote(action.pkg)}}}"""

        is Action.HideApp ->
            """{"action":"hide_app","params":{"package":${quote(action.pkg)}}}"""

        is Action.CreateFolder ->
            """{"action":"create_folder","params":{"name":${quote(action.name)},""" +
                """"apps":${action.apps.toJsonArray()}}}"""

        is Action.SetAnimationScale ->
            """{"action":"set_animation_scale","params":{"scale":${action.scale}}}"""

        is Action.ArrangeRing -> {
            val groups = action.groups.joinToString(",", "[", "]") { g -> """{"name":${quote(g.name)},"apps":${g.apps.toJsonArray()}}""" }
            val style = action.style?.let { ""","style":${quote(it)}""" }.orEmpty()
            """{"action":"arrange_ring","params":{"groups":$groups$style}}"""
        }

        is Action.Plugin -> """{"action":${quote("${action.pluginId}:${action.name}")},"params":${action.paramsJson}}"""

        Action.Unknown -> """{"action":"unknown","params":{}}"""
    }

    /** JSON string literal, with quotes and escaping. */
    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == '\b' -> append("\\b")
                c == '\u000C' -> append("\\f")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private fun List<String>.toJsonArray(): String = joinToString(separator = ",", prefix = "[", postfix = "]") { quote(it) }
}
