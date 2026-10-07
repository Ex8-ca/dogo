package app.dogo.launcher.agent

import org.json.JSONObject

/**
 * A single configuration change the agent proposes.
 *
 * Each action maps onto exactly one user-visible change, so the setup UI can
 * show the user a checklist and apply nothing until they approve it.
 */
sealed class Action {

    data class SetTheme(val theme: String) : Action()

    data class SetWallpaper(val color: String) : Action()

    data class AddToHome(val pkg: String) : Action()

    data class HideApp(val pkg: String) : Action()

    data class CreateFolder(val name: String, val apps: List<String>) : Action()

    data class SetAnimationScale(val scale: Float) : Action()

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

    private fun parseObject(obj: JSONObject): Action {
        val name = obj.optString(KEY_ACTION)
        // Require the params object: a bare action name says nothing about what
        // to change, and guessing defaults would silently do the wrong thing.
        val params = obj.optJSONObject(KEY_PARAMS) ?: return Action.Unknown

        return when (name) {
            "set_theme" -> Action.SetTheme(params.optString("theme"))

            "set_wallpaper" -> Action.SetWallpaper(params.optString("color"))

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

            else -> Action.Unknown
        }
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

        is Action.SetWallpaper ->
            """{"action":"set_wallpaper","params":{"color":${quote(action.color)}}}"""

        is Action.AddToHome ->
            """{"action":"add_to_home","params":{"package":${quote(action.pkg)}}}"""

        is Action.HideApp ->
            """{"action":"hide_app","params":{"package":${quote(action.pkg)}}}"""

        is Action.CreateFolder ->
            """{"action":"create_folder","params":{"name":${quote(action.name)},""" +
                """"apps":${action.apps.toJsonArray()}}}"""

        is Action.SetAnimationScale ->
            """{"action":"set_animation_scale","params":{"scale":${action.scale}}}"""

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
