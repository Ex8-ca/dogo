/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.tiles

/** Turns a checked [TileConfig] back into dogo-tiles.conf text, and into short change lines for an approval card. */
object TileWriter {
    const val MAX_DESCRIBE_LINES = 12

    /**
     * Canonical text: a comment header, one `general` block, then one `page` line per page sorted by number,
     * with tiles in reading order (row, then column). Parsing the result gives the same pages and style and no
     * issues, provided every tile kind is built in and known to the parser, or a valid widget.
     */
    fun write(config: TileConfig): String {
        val sb = StringBuilder()
        sb.append("# Dogo tiles: one page per line, tiles are kind WxH+column+row.\n")
        val s = config.style
        sb.append("general {\n")
        sb.append("    gaps = ").append(s.gaps).append('\n')
        sb.append("    rounding = ").append(s.rounding).append('\n')
        sb.append("    border = ").append(s.border.toConfigText()).append('\n')
        sb.append("    border_size = ").append(s.borderSize).append('\n')
        sb.append("    blur = ").append(if (s.blur) "on" else "off").append('\n')
        sb.append("}\n")
        for (page in config.pages.sortedBy { it.number }) {
            sb.append("page = ").append(page.number).append(", grid ").append(page.columns).append('x').append(page.rows)
            for (t in page.tiles.inReadingOrder()) {
                sb.append(", ").append(t.kind).append(' ')
                    .append(t.width).append('x').append(t.height).append('+').append(t.x).append('+').append(t.y)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Short plain-English lines for what [new] changes compared with [old] (null means nothing set yet), such as
     * `Page 1: adds clock (4x2), removes weather` or `Gaps 8 -> 12`. Empty when nothing differs. At most
     * [MAX_DESCRIBE_LINES] lines; the last one says how many more were left out.
     */
    fun describe(old: TileConfig?, new: TileConfig): List<String> {
        val before = old ?: TileConfig(emptyList(), TileStyle(), emptyList())
        val lines = ArrayList<String>()
        styleLines(before.style, new.style, lines)

        val oldPages = before.pages.associateBy { it.number }
        val newPages = new.pages.associateBy { it.number }
        for (n in (oldPages.keys + newPages.keys).toSortedSet()) {
            val a = oldPages[n]
            val b = newPages[n]
            when {
                a == null && b != null -> lines.add(
                    if (b.tiles.isEmpty()) {
                        "Page $n: new empty page"
                    } else {
                        "Page $n: new page with ${b.tiles.inReadingOrder().joinToString(", ") { label(it, true) }}"
                    },
                )

                a != null && b == null -> lines.add("Page $n: removed")

                a != null && b != null -> pageLines(n, a, b, lines)
            }
        }

        if (lines.size <= MAX_DESCRIBE_LINES) return lines
        val shown = lines.subList(0, MAX_DESCRIBE_LINES - 1).toMutableList()
        shown.add("...and ${lines.size - shown.size} more changes")
        return shown
    }

    private fun styleLines(a: TileStyle, b: TileStyle, out: MutableList<String>) {
        if (a.gaps != b.gaps) out.add("Gaps ${a.gaps} -> ${b.gaps}")
        if (a.rounding != b.rounding) out.add("Rounding ${a.rounding} -> ${b.rounding}")
        if (a.border != b.border) out.add("Border ${a.border.toConfigText()} -> ${b.border.toConfigText()}")
        if (a.borderSize != b.borderSize) out.add("Border size ${a.borderSize} -> ${b.borderSize}")
        if (a.blur != b.blur) out.add("Blur ${onOff(a.blur)} -> ${onOff(b.blur)}")
    }

    private fun onOff(v: Boolean): String = if (v) "on" else "off"

    private fun pageLines(n: Int, a: TilePage, b: TilePage, out: MutableList<String>) {
        if (a.columns != b.columns || a.rows != b.rows) {
            out.add("Page $n: grid ${a.columns}x${a.rows} -> ${b.columns}x${b.rows}")
        }
        val oldCounts = counts(a.tiles)
        val newCounts = counts(b.tiles)
        val added = ArrayList<String>()
        val removed = ArrayList<String>()
        for ((key, count) in newCounts) {
            repeat(count - (oldCounts[key] ?: 0)) { added.add(key.label(true)) }
        }
        for ((key, count) in oldCounts) {
            repeat(count - (newCounts[key] ?: 0)) { removed.add(key.label(false)) }
        }
        val parts = ArrayList<String>()
        if (added.isNotEmpty()) parts.add("adds ${added.joinToString(", ")}")
        if (removed.isNotEmpty()) parts.add("removes ${removed.joinToString(", ")}")
        if (parts.isEmpty() && a.tiles.inReadingOrder() != b.tiles.inReadingOrder()) parts.add("moves tiles")
        if (parts.isNotEmpty()) out.add("Page $n: ${parts.joinToString(", ")}")
    }

    private data class Key(val kind: String, val width: Int, val height: Int) {
        fun label(withSize: Boolean): String {
            val name = TileWriter.displayName(kind)
            return if (withSize) "$name (${width}x$height)" else name
        }
    }

    private fun counts(tiles: List<TileSpec>): Map<Key, Int> {
        val map = LinkedHashMap<Key, Int>()
        for (t in tiles.inReadingOrder()) {
            val key = Key(t.kind, t.width, t.height)
            map[key] = (map[key] ?: 0) + 1
        }
        return map
    }

    private fun label(t: TileSpec, withSize: Boolean): String = Key(t.kind, t.width, t.height).label(withSize)

    /** Built-in names as they are; a widget as `widget <ClassName>`, kept short for a card. */
    internal fun displayName(kind: String): String {
        if (!kind.startsWith(TileSpec.WIDGET_PREFIX)) return shortText(kind)
        val provider = kind.substring(TileSpec.WIDGET_PREFIX.length)
        val cls = provider.substringAfter('/', "").substringAfterLast('.').ifEmpty { provider.substringBefore('/') }
        return "widget " + shortText(cls)
    }
}
