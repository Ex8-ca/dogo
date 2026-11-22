/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.themes

/**
 * An Omarchy theme, read from its `colors.toml`.
 *
 * Omarchy themes are a folder with `colors.toml` (a flat list of
 * `key = "#rrggbb"` pairs plus `mode = "dark"|"light"`) and a `backgrounds/`
 * folder of wallpapers. Dogo maps the parts a phone can use: light/dark,
 * accent colour, and a wallpaper.
 */
data class OmarchyTheme(
    val name: String,
    val dark: Boolean,
    val accent: Int,
    val background: Int,
    val foreground: Int,
    val colors: Map<String, Int>,
) {
    companion object {
        /**
         * Parses the subset of TOML that `colors.toml` uses: comments, blank
         * lines, and `key = "value"` pairs. Never throws; returns null when
         * the file has no usable colours.
         */
        fun parse(name: String, toml: String): OmarchyTheme? {
            val values = mutableMapOf<String, String>()
            for (raw in toml.lineSequence()) {
                val line = stripComment(raw).trim()
                if (line.isEmpty() || line.startsWith("[")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim().let { v ->
                    if (v.startsWith('"')) v.drop(1).substringBefore('"') else v.substringBefore(' ')
                }
                if (key.isNotEmpty()) values[key] = value
            }
            val colors = values.mapNotNull { (k, v) -> parseColor(v)?.let { k to it } }.toMap()
            val background = colors["background"] ?: return null
            val foreground = colors["foreground"] ?: return null
            val accent = colors["accent"] ?: colors["blue"] ?: colors["color4"] ?: foreground
            val dark = when (values["mode"]?.lowercase()) {
                "dark" -> true
                "light" -> false
                else -> luminance(background) < 0.5
            }
            return OmarchyTheme(name, dark, accent, background, foreground, colors)
        }

        /**
         * Drops a trailing `# comment`. "#" also starts colour values, so a
         * "#" inside quotes is kept.
         */
        internal fun stripComment(line: String): String {
            var inString = false
            line.forEachIndexed { i, c ->
                when {
                    c == '"' -> inString = !inString
                    c == '#' && !inString -> return line.substring(0, i)
                }
            }
            return line
        }

        /** `#rgb`, `#rrggbb` or `#aarrggbb` as an opaque ARGB int. */
        fun parseColor(value: String): Int? {
            val hex = value.trim().removePrefix("#")
            if (hex.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) return null
            val rgb = when (hex.length) {
                3 -> hex.map { "$it$it" }.joinToString("")
                6 -> hex
                8 -> hex.substring(2)
                else -> return null
            }
            return (0xFF000000.toInt()) or rgb.toInt(16)
        }

        /** Relative luminance, 0 (black) to 1 (white). */
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val c = ((color shr shift) and 0xFF) / 255.0
                return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }

        /** "tokyo-night" -> "Tokyo Night". */
        fun displayName(slug: String): String = slug.split('-', '_', ' ').filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }
}
