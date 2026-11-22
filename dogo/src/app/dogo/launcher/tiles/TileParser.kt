/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.tiles

/**
 * Reads `dogo-tiles.conf`, a small Hyprland-style text file that the agent writes and the user may edit.
 *
 * The file is data, never code. Every line, tile and setting is checked on its own against fixed limits: a bad
 * one is dropped with a plain-English [TileIssue] and the rest still applies. [parse] never throws.
 *
 * ```
 * # a comment; colours never use "#"
 * general { gaps = 8  rounding = 18  border = accent  border_size = 2  blur = off }
 * page = 1, grid 4x8, clock 4x2+0+0, widget:com.example.cal/.CalendarWidget 4x3+0+2
 * ```
 *
 * Issue messages never repeat more than 24 characters of the file's own text, and only printable ASCII, so
 * hostile text cannot ride along in them.
 */
object TileParser {
    const val MAX_CHARS = 8000
    const val MAX_LINE_CHARS = 400
    const val MAX_LINES = 200
    const val MAX_TILES_PER_PAGE = 12
    const val MAX_PAGES = 8
    const val MAX_COLUMNS = 8
    const val MAX_ROWS = 16

    /**
     * Parses [text]. [knownKinds] are the allowed built-in tile names; `widget:<package>/<class>` is always
     * allowed. Pages come back sorted by number and each page's tiles in reading order (row, then column).
     */
    fun parse(text: String, knownKinds: Set<String> = emptySet()): TileConfig {
        val reader = Reader(knownKinds)
        try {
            reader.read(text)
        } catch (e: RuntimeException) {
            reader.issue(0, "The file could not be read completely, so part of it was ignored.")
        }
        return TileConfig(
            pages = reader.pages.values.sortedBy { it.number },
            style = reader.style,
            issues = reader.issues.toList(),
        )
    }
}

private const val MAX_ISSUES = 50
private const val MAX_NUMBER_DIGITS = 4
private const val WIDGET_PREFIX = "widget:"

private val BUILTIN_KIND = Regex("[a-z][a-z0-9_]{0,23}")
private val WIDGET_KIND = Regex("widget:[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+/[A-Za-z0-9_.\\$]{1,120}")
private val TILE = Regex("(\\S+)\\s+([0-9]+)[xX]([0-9]+)\\+([0-9]+)\\+([0-9]+)")
private val GRID = Regex("grid\\s+([0-9]{1,3})\\s*[xX]\\s*([0-9]{1,3})")
private val SMALL_INT = Regex("[0-9]{1,4}")
private val RGB = Regex("rgb\\(([0-9a-f]{6})\\)")
private val RGBA = Regex("rgba\\(([0-9a-f]{8})\\)")
private val GENERAL_OPEN = Regex("general\\s*\\{(.*)", RegexOption.DOT_MATCHES_ALL)
private val PAGE_LINE = Regex("page\\s*=(.*)", RegexOption.DOT_MATCHES_ALL)
private val PAIR = Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([^\\s=]+)")

private enum class Mode { TOP, EXPECT_BRACE, IN_BLOCK }

private class Reader(private val known: Set<String>) {
    val issues = ArrayList<TileIssue>()
    val pages = HashMap<Int, TilePage>()
    var style = TileStyle()
    private var overflowReported = false

    fun issue(line: Int, message: String) {
        if (issues.size < MAX_ISSUES) {
            issues.add(TileIssue(line, message))
        } else if (!overflowReported) {
            overflowReported = true
            issues.add(TileIssue(0, "There are more problems than can be listed here."))
        }
    }

    fun read(text: String) {
        var body = text
        if (body.length > TileParser.MAX_CHARS) {
            body = body.substring(0, TileParser.MAX_CHARS)
            issue(0, "The file is longer than ${TileParser.MAX_CHARS} characters, so the rest was ignored.")
        }
        val lines = body.split('\n')
        var mode = Mode.TOP
        for (i in lines.indices) {
            val lineNo = i + 1
            if (i >= TileParser.MAX_LINES) {
                if (lines.subList(i, lines.size).any { stripComment(it).isNotBlank() }) {
                    issue(0, "Only the first ${TileParser.MAX_LINES} lines are read, so the rest was ignored.")
                }
                break
            }
            val raw = lines[i]
            if (raw.length > TileParser.MAX_LINE_CHARS) {
                issue(lineNo, "Line $lineNo is longer than ${TileParser.MAX_LINE_CHARS} characters and was skipped.")
                continue
            }
            val line = stripComment(raw).trim()
            if (line.isEmpty()) continue
            mode = handle(line, lineNo, mode)
        }
        if (mode != Mode.TOP) issue(0, "The general block was never closed with }.")
    }

    private fun stripComment(s: String): String {
        val hash = s.indexOf('#')
        return if (hash >= 0) s.substring(0, hash) else s
    }

    private fun handle(line: String, lineNo: Int, mode: Mode): Mode {
        if (mode == Mode.EXPECT_BRACE) {
            if (line.startsWith("{")) return block(line.substring(1), lineNo)
            issue(lineNo, "The general block needs a { to open it.")
            return top(line, lineNo)
        }
        if (mode == Mode.IN_BLOCK) return block(line, lineNo)
        return top(line, lineNo)
    }

    private fun top(line: String, lineNo: Int): Mode {
        if (line == "general") return Mode.EXPECT_BRACE
        val general = GENERAL_OPEN.matchEntire(line)
        if (general != null) return block(general.groupValues[1], lineNo)
        val page = PAGE_LINE.matchEntire(line)
        if (page != null) {
            pageLine(page.groupValues[1], lineNo)
            return Mode.TOP
        }
        issue(lineNo, "Unknown setting '${shortText(firstWord(line))}' on line $lineNo was ignored.")
        return Mode.TOP
    }

    private fun firstWord(s: String): String = s.takeWhile { !it.isWhitespace() && it != '=' }.take(60).ifEmpty { "?" }

    // ---- general block ----

    /** [content] is what follows `{` or a whole line inside the block. Returns the mode after it. */
    private fun block(content: String, lineNo: Int): Mode {
        val close = content.indexOf('}')
        val inside = if (close >= 0) content.substring(0, close) else content
        if (inside.isNotBlank()) generalSettings(inside.trim(), lineNo)
        if (close < 0) return Mode.IN_BLOCK
        if (content.substring(close + 1).isNotBlank()) {
            issue(lineNo, "Unknown setting after the closing } on line $lineNo was ignored.")
        }
        return Mode.TOP
    }

    private fun generalSettings(inside: String, lineNo: Int) {
        val signs = inside.count { it == '=' }
        if (signs == 0) {
            issue(lineNo, "Unknown setting '${shortText(firstWord(inside))}' in the general block on line $lineNo was ignored.")
            return
        }
        if (signs == 1) {
            applyGeneral(inside.substringBefore('=').trim(), inside.substringAfter('=').trim(), lineNo)
            return
        }
        for (m in PAIR.findAll(inside)) applyGeneral(m.groupValues[1], m.groupValues[2], lineNo)
        if (PAIR.replace(inside, " ").isNotBlank()) {
            issue(lineNo, "Part of the general block on line $lineNo could not be read and was ignored.")
        }
    }

    private fun applyGeneral(key: String, value: String, lineNo: Int) {
        when (key) {
            "gaps" -> {
                val n = readInt(key, value, 0, 32, lineNo)
                if (n != null) style = style.copy(gaps = n)
            }

            "rounding" -> {
                val n = readInt(key, value, 0, 48, lineNo)
                if (n != null) style = style.copy(rounding = n)
            }

            "border_size" -> {
                val n = readInt(key, value, 0, 8, lineNo)
                if (n != null) style = style.copy(borderSize = n)
            }

            "border" -> {
                val colour = parseColour(value)
                if (colour != null) {
                    style = style.copy(border = colour)
                } else {
                    issue(lineNo, "'border' on line $lineNo must be accent, none, rgb(rrggbb) or rgba(rrggbbaa) (found '${shortText(value)}').")
                }
            }

            "blur" -> {
                when (value.lowercase()) {
                    "on" -> style = style.copy(blur = true)
                    "off" -> style = style.copy(blur = false)
                    else -> issue(lineNo, "'blur' on line $lineNo must be on or off (found '${shortText(value)}').")
                }
            }

            else -> issue(lineNo, "Unknown setting '${shortText(key)}' in the general block on line $lineNo was ignored.")
        }
    }

    private fun readInt(key: String, value: String, low: Int, high: Int, lineNo: Int): Int? {
        val n = if (SMALL_INT.matches(value)) value.toInt() else null
        if (n == null || n < low || n > high) {
            issue(lineNo, "'$key' on line $lineNo must be a whole number from $low to $high (found '${shortText(value)}').")
            return null
        }
        return n
    }

    private fun parseColour(value: String): TileColour? {
        val v = value.lowercase()
        if (v == "accent") return TileColour.Accent
        if (v == "none") return TileColour.None
        val rgb = RGB.matchEntire(v)
        if (rgb != null) {
            val h = rgb.groupValues[1]
            return TileColour.Rgba(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16), 255)
        }
        val rgba = RGBA.matchEntire(v)
        if (rgba != null) {
            val h = rgba.groupValues[1]
            return TileColour.Rgba(
                h.substring(0, 2).toInt(16),
                h.substring(2, 4).toInt(16),
                h.substring(4, 6).toInt(16),
                h.substring(6, 8).toInt(16),
            )
        }
        return null
    }

    // ---- page lines ----

    private fun pageLine(rest: String, lineNo: Int) {
        val parts = rest.split(',').map { it.trim() }
        val numberText = parts[0]
        val number = if (SMALL_INT.matches(numberText)) numberText.toInt() else -1
        if (number < 1 || number > TileParser.MAX_PAGES) {
            issue(lineNo, "The page number on line $lineNo must be 1 to ${TileParser.MAX_PAGES} (found '${shortText(numberText)}'), so the line was ignored.")
            return
        }
        if (pages.containsKey(number)) {
            issue(lineNo, "Page $number is already set earlier in the file, so line $lineNo was ignored.")
            return
        }
        val grid = GRID.matchEntire(parts.getOrNull(1) ?: "")
        val columns = grid?.groupValues?.get(1)?.toIntOrNull()
        val rows = grid?.groupValues?.get(2)?.toIntOrNull()
        if (columns == null || rows == null ||
            columns < 1 || columns > TileParser.MAX_COLUMNS ||
            rows < 1 || rows > TileParser.MAX_ROWS
        ) {
            issue(
                lineNo,
                "Page $number on line $lineNo needs a grid like 'grid 4x8' with 1 to ${TileParser.MAX_COLUMNS} " +
                    "columns and 1 to ${TileParser.MAX_ROWS} rows, so the line was ignored.",
            )
            return
        }
        val accepted = ArrayList<TileSpec>()
        var tileNo = 0
        for (i in 2 until parts.size) {
            val part = parts[i]
            if (part.isEmpty()) continue
            tileNo++
            val tile = readTile(part, number, tileNo, columns, rows, accepted, lineNo)
            if (tile != null) accepted.add(tile)
        }
        pages[number] = TilePage(number, columns, rows, accepted.inReadingOrder())
    }

    private fun readTile(
        part: String,
        pageNumber: Int,
        tileNo: Int,
        columns: Int,
        rows: Int,
        accepted: List<TileSpec>,
        lineNo: Int,
    ): TileSpec? {
        val where = "Tile $tileNo on page $pageNumber"
        val m = TILE.matchEntire(part)
        if (m == null) {
            issue(lineNo, "$where isn't written like 'clock 4x2+0+0' and was dropped.")
            return null
        }
        val kind = m.groupValues[1]
        if (kind.startsWith(WIDGET_PREFIX)) {
            if (!WIDGET_KIND.matches(kind)) {
                issue(lineNo, "$where has a widget name that isn't valid and was dropped.")
                return null
            }
        } else if (!BUILTIN_KIND.matches(kind) || kind !in known) {
            issue(lineNo, "$where uses an unknown tile type '${shortText(kind)}' and was dropped.")
            return null
        }
        val numbers = listOf(m.groupValues[2], m.groupValues[3], m.groupValues[4], m.groupValues[5])
        if (numbers.any { it.length > MAX_NUMBER_DIGITS }) {
            issue(lineNo, "$where has a size or position that is not a sensible number and was dropped.")
            return null
        }
        val w = numbers[0].toInt()
        val h = numbers[1].toInt()
        val x = numbers[2].toInt()
        val y = numbers[3].toInt()
        if (w < 1 || h < 1) {
            issue(lineNo, "$where must be at least 1 cell wide and 1 cell high and was dropped.")
            return null
        }
        if (x + w > columns || y + h > rows) {
            issue(lineNo, "$where doesn't fit the grid and was dropped.")
            return null
        }
        for (other in accepted) {
            if (x < other.x + other.width && other.x < x + w && y < other.y + other.height && other.y < y + h) {
                issue(lineNo, "$where overlaps the ${shortText(other.kind)} tile and was dropped.")
                return null
            }
        }
        if (accepted.size >= TileParser.MAX_TILES_PER_PAGE) {
            issue(lineNo, "$where was dropped because a page holds at most ${TileParser.MAX_TILES_PER_PAGE} tiles.")
            return null
        }
        return TileSpec(kind, w, h, x, y)
    }
}
