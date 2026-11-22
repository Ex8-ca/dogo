/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.tiles

/** A frame colour. Never contains `#`, because `#` starts a comment in dogo-tiles.conf. */
sealed class TileColour {
    /** The wallpaper accent colour, resolved at draw time. */
    object Accent : TileColour() {
        override fun toString(): String = "Accent"
    }

    /** No colour at all. */
    object None : TileColour() {
        override fun toString(): String = "None"
    }

    /** Each channel is 0..255. `rgb(rrggbb)` is [Rgba] with [a] = 255. */
    data class Rgba(val r: Int, val g: Int, val b: Int, val a: Int = 255) : TileColour()
}

/**
 * One box on a page. [x] and [y] are 0-based columns and rows; [width] and [height] are in cells.
 * [kind] is a built-in name or `widget:<package>/<class>`.
 */
data class TileSpec(val kind: String, val width: Int, val height: Int, val x: Int, val y: Int) {
    val isWidget: Boolean get() = kind.startsWith(WIDGET_PREFIX)

    /** `package/class` for a widget tile, null for a built-in one. */
    val widgetProvider: String? get() = if (isWidget) kind.substring(WIDGET_PREFIX.length) else null

    companion object {
        const val WIDGET_PREFIX = "widget:"
    }
}

/** One home-screen page: a [columns] x [rows] grid and the [tiles] on it, in reading order (row, then column). */
data class TilePage(val number: Int, val columns: Int, val rows: Int, val tiles: List<TileSpec>)

/** Look shared by all tiles. Sizes are in dp. */
data class TileStyle(
    val gaps: Int = 8,
    val rounding: Int = 18,
    val border: TileColour = TileColour.Accent,
    val borderSize: Int = 2,
    val blur: Boolean = false,
)

/** A problem found while reading the file. [line] is 1-based; 0 means the file as a whole. */
data class TileIssue(val line: Int, val message: String)

/**
 * The checked content of a dogo-tiles.conf. [pages] are sorted by number. [issues] lists everything that
 * was dropped, in plain English.
 */
data class TileConfig(val pages: List<TilePage>, val style: TileStyle, val issues: List<TileIssue>) {
    /** True when there are no pages (the style alone does not count). */
    val isEmpty: Boolean get() = pages.isEmpty()

    fun page(n: Int): TilePage? = pages.firstOrNull { it.number == n }
}

internal fun List<TileSpec>.inReadingOrder(): List<TileSpec> = sortedWith(compareBy<TileSpec>({ it.y }, { it.x }))

/** The text form used in dogo-tiles.conf: `accent`, `none`, `rgb(rrggbb)` or `rgba(rrggbbaa)`. */
internal fun TileColour.toConfigText(): String = when (this) {
    TileColour.Accent -> "accent"

    TileColour.None -> "none"

    is TileColour.Rgba -> {
        val rgb = hex2(r) + hex2(g) + hex2(b)
        if (a == 255) "rgb($rgb)" else "rgba($rgb${hex2(a)})"
    }
}

private fun hex2(v: Int): String {
    val digits = "0123456789abcdef"
    val c = v.coerceIn(0, 255)
    return "" + digits[c / 16] + digits[c % 16]
}

/**
 * Makes untrusted text safe to quote in a message: printable ASCII only (anything else becomes `?`) and at most
 * [max] characters, with an ellipsis when it was cut.
 */
internal fun shortText(raw: String, max: Int = 24): String {
    val cut = raw.length > max
    val head = if (cut) raw.substring(0, max) else raw
    val sb = StringBuilder(head.length + 1)
    for (ch in head) sb.append(if (ch in ' '..'~') ch else '?')
    if (cut) sb.append('\u2026')
    return sb.toString()
}

/** Turns a tile grid position into pixels. Pure integer maths, no Android types. */
object TileGeometry {
    /**
     * Pixel rectangle of [tile] inside an area of [areaWidthPx] x [areaHeightPx], as `[left, top, right, bottom]`
     * (right and bottom exclusive). The area is split into the page's columns x rows cells with [gapPx] between
     * neighbouring cells and none at the outer edge. The last column and row absorb the rounding remainder, so
     * the grid fills the area edge to edge and tiles never overlap. A gap too big to leave every cell at least one
     * pixel is reduced; nothing here throws.
     */
    fun cellRect(page: TilePage, tile: TileSpec, areaWidthPx: Int, areaHeightPx: Int, gapPx: Int): IntArray {
        val xs = axis(tile.x, tile.width, page.columns, areaWidthPx, gapPx)
        val ys = axis(tile.y, tile.height, page.rows, areaHeightPx, gapPx)
        return intArrayOf(xs[0], ys[0], xs[1], ys[1])
    }

    private fun axis(start: Int, span: Int, count: Int, total: Int, gap: Int): IntArray {
        val t = maxOf(total, 0)
        val n = maxOf(count, 1)
        val maxGap = if (n > 1) maxOf(0, (t - n) / (n - 1)) else 0
        val g = minOf(maxOf(gap, 0), maxGap)
        val cell = (t - g * (n - 1)) / n
        val first = start.coerceIn(0, n - 1)
        val last = (start + maxOf(span, 1) - 1).coerceIn(first, n - 1)
        val low = first * (cell + g)
        val high = if (last == n - 1) t else last * (cell + g) + cell
        return intArrayOf(low, high)
    }
}
