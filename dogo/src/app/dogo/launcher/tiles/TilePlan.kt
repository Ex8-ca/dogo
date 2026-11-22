/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.tiles

/** A tile widget Dogo placed earlier. [page] is 1-based like the file, [provider] is `package/class`, cells are 0-based. */
data class PlacedTile(val page: Int, val provider: String, val x: Int, val y: Int, val width: Int, val height: Int)

/** A block of grid cells, 0-based. */
data class CellRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The launcher page [number] (1-based) as it is now. [occupied] holds only cells used by things Dogo did NOT place for
 * tiles (the user's icons, folders and widgets); the caller leaves Dogo's own tile widgets out. [reservedTopRow] is true
 * on page 1, where the top row belongs to the date line.
 */
data class PageState(val number: Int, val columns: Int, val rows: Int, val occupied: List<CellRect>, val reservedTopRow: Boolean)

/** Smallest and largest size, in cells, a widget provider accepts. A max of 0 or less means no limit on that side. */
data class WidgetLimits(val minWidth: Int, val minHeight: Int, val maxWidth: Int, val maxHeight: Int)

/**
 * One thing to do. [Add], [Move], [Resize], [Remove] and [SetGrid] are the steps of a plan; [Refuse] says why a tile
 * was left out and only ever appears in [TilePlan.refused].
 *
 * [Move], [Resize] and [Remove] name the tile by page and provider; the optional old cell ([Remove.x], [Resize.fromX]
 * and so on, -1 when unknown) tells two tiles of the same provider on one page apart.
 */
sealed interface TileStep {
    data class Add(val page: Int, val spec: TileSpec) : TileStep

    data class Move(val page: Int, val provider: String, val fromX: Int, val fromY: Int, val toX: Int, val toY: Int) : TileStep

    /** [spec] is the final size and position, so a resize may also move. */
    data class Resize(val page: Int, val provider: String, val spec: TileSpec, val fromX: Int = -1, val fromY: Int = -1) : TileStep

    data class Remove(val page: Int, val provider: String, val x: Int = -1, val y: Int = -1) : TileStep

    data class SetGrid(val columns: Int, val rows: Int) : TileStep

    /** [kind] is the tile's kind from the file; [reason] is plain fixed text that never repeats the file's own words. */
    data class Refuse(val page: Int, val kind: String, val reason: String) : TileStep
}

/**
 * What to do to turn the launcher into the tile file: [steps] in the order to apply them (no [TileStep.Refuse] in
 * there), and [refused] tiles with reasons. [gridFromColumns] and [gridFromRows] are the launcher grid before a
 * [TileStep.SetGrid] (0 when there is no such step), used only for the wording.
 */
data class TilePlan(
    val steps: List<TileStep>,
    val refused: List<TileStep.Refuse>,
    val gridFromColumns: Int = 0,
    val gridFromRows: Int = 0,
) {
    /** True when there is nothing to do and nothing to report. */
    val isEmpty: Boolean get() = steps.isEmpty() && refused.isEmpty()

    /**
     * Plain-English lines for an approval card: the steps in order, then what was not placed. At most
     * [MAX_DESCRIBE_LINES] lines; when there are more the last line is `...and N more`. Widget names are the class
     * part of the provider, cut at 24 characters, printable ASCII only.
     */
    fun describe(): List<String> {
        val lines = ArrayList<String>()
        for (step in steps) lines.add(line(step))
        for (r in refused) lines.add(line(r))
        if (lines.size <= MAX_DESCRIBE_LINES) return lines
        val shown = ArrayList<String>(lines.subList(0, MAX_DESCRIBE_LINES - 1))
        shown.add("...and ${lines.size - shown.size} more")
        return shown
    }

    private fun line(step: TileStep): String = when (step) {
        is TileStep.SetGrid ->
            if (gridFromColumns > 0 && gridFromRows > 0) {
                "Grid ${gridFromColumns}x$gridFromRows becomes ${step.columns}x${step.rows}"
            } else {
                "Grid becomes ${step.columns}x${step.rows}"
            }

        is TileStep.Add -> "Page ${step.page}: add ${tileName(step.spec.kind)} (${step.spec.width}x${step.spec.height})"

        is TileStep.Move -> "Page ${step.page}: move ${tileName(step.provider)}"

        is TileStep.Resize -> "Page ${step.page}: resize ${tileName(step.spec.kind)} (${step.spec.width}x${step.spec.height})"

        is TileStep.Remove -> "Page ${step.page}: remove ${tileName(step.provider)}"

        is TileStep.Refuse -> "Not placed: ${tileName(step.kind)} on page ${step.page} (${planText(step.reason, MAX_REASON_CHARS)})"
    }

    companion object {
        const val MAX_DESCRIBE_LINES = 12
        const val MAX_NAME_CHARS = 24
        private const val MAX_REASON_CHARS = 80
    }
}

/** At most [max] characters of [raw], printable ASCII only (anything else becomes `?`), no ellipsis. */
private fun planText(raw: String, max: Int): String {
    val sb = StringBuilder()
    for (ch in raw.take(max)) sb.append(if (ch in ' '..'~') ch else '?')
    return sb.toString()
}

/** `widget:com.x/.ClockWidget`, `com.x/.ClockWidget` or a built-in name, shown as `ClockWidget`. */
private fun tileName(kindOrProvider: String): String {
    val provider = if (kindOrProvider.startsWith(TileSpec.WIDGET_PREFIX)) {
        kindOrProvider.substring(TileSpec.WIDGET_PREFIX.length)
    } else {
        kindOrProvider
    }
    val cls = provider.substringAfterLast('/').substringAfterLast('.')
    val name = planText(cls.ifEmpty { provider }, TilePlan.MAX_NAME_CHARS)
    return name.ifEmpty { "?" }
}

private val READING_ORDER = compareBy<TileSpec>({ it.y }, { it.x })

private fun cellsOverlap(a: CellRect, b: CellRect): Boolean {
    if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) return false
    return a.x.toLong() < b.x.toLong() + b.width && b.x.toLong() < a.x.toLong() + a.width &&
        a.y.toLong() < b.y.toLong() + b.height && b.y.toLong() < a.y.toLong() + a.height
}

/**
 * Compares a parsed tile file with what Dogo placed before and with the launcher pages as they are now, and says what
 * to do. Pure: no Android types, no side effects, the same input gives the same plan, and [plan] never throws (if
 * something unexpected goes wrong, the result is an empty plan with one refusal using [FAILED_REASON]).
 *
 * Step order: [TileStep.SetGrid], then removes, then moves and resizes, then adds; each group by page, then row, then
 * column. A move or resize may target cells another tile only leaves later in the same group; the caller must cope
 * with that (the plan guarantees only that the final layout has no overlaps).
 */
object TilePlanner {
    const val BUILT_IN = "built-in tiles are not available yet"
    const val GRID_MISMATCH = "all tile pages must use the same grid"
    const val NOT_INSTALLED = "that widget is not installed"
    const val TOP_ROW = "the top row of page 1 is kept for the date line"
    const val OCCUPIED = "that spot has your own icons or widgets"
    const val OVERLAP = "overlaps another tile"
    const val NO_FIT = "that tile does not fit the grid"
    const val FAILED_REASON = "the tiles could not be planned"

    /**
     * [config] is the new file, [previous] what Dogo placed earlier, [pages] the launcher pages now (page N of the
     * file is `pages[N - 1]`), [launcherColumns] x [launcherRows] the launcher grid now. [limits] gives the size range
     * of a widget provider in cells, or null when it is not installed.
     */
    fun plan(
        config: TileConfig,
        previous: List<PlacedTile>,
        pages: List<PageState>,
        launcherColumns: Int,
        launcherRows: Int,
        limits: (String) -> WidgetLimits?,
    ): TilePlan = try {
        Run(config, previous, pages, launcherColumns, launcherRows, limits).run()
    } catch (e: Exception) {
        TilePlan(emptyList(), listOf(TileStep.Refuse(0, "?", FAILED_REASON)))
    }

    private enum class State { PLANNED, UNCHANGED, KEPT, REFUSED }

    private class PrevEntry(val tile: PlacedTile) {
        var matched = false
    }

    /** One tile of the file. [prev] is the earlier placement it was matched with, if any. */
    private class Item(
        val page: Int,
        val spec: TileSpec,
        val provider: String?,
        val duplicate: Boolean,
        val pageProblem: String?,
    ) {
        var prev: PlacedTile? = null
        var state = State.PLANNED
        var reason: String? = null

        /** Where this tile ends up: its old cells when it stays put or is refused, else its new cells. */
        fun finalRect(): CellRect {
            val p = prev
            return if (state == State.KEPT && p != null) {
                CellRect(p.x, p.y, p.width, p.height)
            } else {
                CellRect(spec.x, spec.y, spec.width, spec.height)
            }
        }
    }

    private class Run(
        private val config: TileConfig,
        private val previous: List<PlacedTile>,
        private val pages: List<PageState>,
        private val launcherColumns: Int,
        private val launcherRows: Int,
        private val limits: (String) -> WidgetLimits?,
    ) {
        private var gridColumns = launcherColumns
        private var gridRows = launcherRows
        private val limitCache = HashMap<String, WidgetLimits?>()

        fun run(): TilePlan {
            val filePages = config.pages.sortedBy { it.number }
            val first = filePages.firstOrNull()
            if (first != null) {
                gridColumns = first.columns
                gridRows = first.rows
            }
            val gridChanged = gridColumns != launcherColumns || gridRows != launcherRows

            val items = buildItems(filePages)
            val prevByKey = matchPrevious(items)
            for (item in items) judge(item)
            for (pageItems in items.groupBy { it.page }.values) resolveOverlaps(pageItems)

            val steps = ArrayList<TileStep>()
            if (gridChanged) steps.add(TileStep.SetGrid(gridColumns, gridRows))

            val removed = ArrayList<PlacedTile>()
            for (list in prevByKey.values) {
                for (entry in list) if (!entry.matched) removed.add(entry.tile)
            }
            removed.sortWith(compareBy<PlacedTile>({ it.page }, { it.y }, { it.x }))
            for (p in removed) steps.add(TileStep.Remove(p.page, p.provider, p.x, p.y))

            val planned = items.filter { it.state == State.PLANNED }
                .sortedWith(compareBy<Item>({ it.page }, { it.spec.y }, { it.spec.x }))
            for (item in planned) {
                val p = item.prev ?: continue
                val provider = item.provider ?: continue
                if (p.width == item.spec.width && p.height == item.spec.height) {
                    steps.add(TileStep.Move(item.page, provider, p.x, p.y, item.spec.x, item.spec.y))
                } else {
                    steps.add(TileStep.Resize(item.page, provider, item.spec, p.x, p.y))
                }
            }
            for (item in planned) {
                if (item.prev == null) steps.add(TileStep.Add(item.page, item.spec))
            }

            val refused = ArrayList<TileStep.Refuse>()
            for (item in items) {
                val reason = item.reason
                if (reason != null && (item.state == State.KEPT || item.state == State.REFUSED)) {
                    refused.add(TileStep.Refuse(item.page, item.spec.kind, reason))
                }
            }
            return TilePlan(
                steps = steps,
                refused = refused,
                gridFromColumns = if (gridChanged) launcherColumns else 0,
                gridFromRows = if (gridChanged) launcherRows else 0,
            )
        }

        private fun buildItems(filePages: List<TilePage>): List<Item> {
            val items = ArrayList<Item>()
            val seen = HashSet<Int>()
            for (fp in filePages) {
                val duplicate = !seen.add(fp.number)
                val problem = pageProblem(fp, duplicate)
                for (t in fp.tiles.sortedWith(READING_ORDER)) {
                    items.add(Item(fp.number, t, t.widgetProvider, duplicate, problem))
                }
            }
            return items
        }

        private fun pageProblem(fp: TilePage, duplicate: Boolean): String? = when {
            duplicate -> "page ${fp.number} is listed twice"
            fp.columns != gridColumns || fp.rows != gridRows -> GRID_MISMATCH
            fp.number < 1 || fp.number > pages.size + 1 -> "page ${fp.number} does not exist yet"
            else -> null
        }

        /** Pairs file tiles with earlier placements by page and provider, in reading order. Returns the earlier ones by key. */
        private fun matchPrevious(items: List<Item>): Map<Pair<Int, String>, List<PrevEntry>> {
            val byKey = LinkedHashMap<Pair<Int, String>, MutableList<PrevEntry>>()
            val sorted = previous.sortedWith(compareBy<PlacedTile>({ it.page }, { it.y }, { it.x }))
            for (p in sorted) byKey.getOrPut(Pair(p.page, p.provider)) { ArrayList() }.add(PrevEntry(p))

            val taken = HashMap<Pair<Int, String>, Int>()
            for (item in items) {
                val provider = item.provider ?: continue
                if (item.duplicate) continue
                val key = Pair(item.page, provider)
                val n = taken[key] ?: 0
                taken[key] = n + 1
                val list = byKey[key] ?: continue
                if (n < list.size) {
                    list[n].matched = true
                    item.prev = list[n].tile
                }
            }
            return byKey
        }

        /** Sets the state of [item]: unchanged, planned (the default), or refused with a reason. */
        private fun judge(item: Item) {
            val provider = item.provider
            var reason: String? = null
            if (provider == null) {
                reason = BUILT_IN
            } else if (item.pageProblem != null) {
                reason = item.pageProblem
            } else if (isUnchanged(item)) {
                item.state = State.UNCHANGED
            } else {
                reason = tileProblem(item, provider)
            }
            if (reason != null) {
                item.reason = reason
                item.state = if (item.prev != null) State.KEPT else State.REFUSED
            }
        }

        private fun isUnchanged(item: Item): Boolean {
            val p = item.prev ?: return false
            val s = item.spec
            return p.x == s.x && p.y == s.y && p.width == s.width && p.height == s.height
        }

        private fun tileProblem(item: Item, provider: String): String? {
            val s = item.spec
            if (s.width < 1 || s.height < 1 || s.x < 0 || s.y < 0 ||
                s.x.toLong() + s.width > gridColumns || s.y.toLong() + s.height > gridRows
            ) {
                return NO_FIT
            }
            val lim = limitsFor(provider) ?: return NOT_INSTALLED
            if (s.width < lim.minWidth || s.height < lim.minHeight) {
                return "needs at least ${lim.minWidth}x${lim.minHeight} cells"
            }
            if ((lim.maxWidth > 0 && s.width > lim.maxWidth) || (lim.maxHeight > 0 && s.height > lim.maxHeight)) {
                return "can be at most ${lim.maxWidth}x${lim.maxHeight} cells"
            }
            val state = if (item.page in 1..pages.size) pages.firstOrNull { it.number == item.page } else null
            if (state != null) {
                if (state.reservedTopRow && s.y == 0) return TOP_ROW
                val mine = CellRect(s.x, s.y, s.width, s.height)
                for (r in state.occupied) if (cellsOverlap(mine, r)) return OCCUPIED
            }
            return null
        }

        private fun limitsFor(provider: String): WidgetLimits? {
            if (limitCache.containsKey(provider)) return limitCache[provider]
            val found = limits(provider)
            limitCache[provider] = found
            return found
        }

        /**
         * Defensive: after everything above, no two tiles on a page may share a cell. A planned tile that collides with
         * a tile that stays (or with an earlier planned tile) is refused; if it had an earlier placement, that stays,
         * which can in turn collide with another planned tile, so this repeats until nothing collides.
         */
        private fun resolveOverlaps(pageItems: List<Item>) {
            while (true) {
                val live = pageItems.filter { it.state != State.REFUSED }
                var victim: Item? = null
                for (i in live.indices) {
                    for (j in i + 1 until live.size) {
                        val a = live[i]
                        val b = live[j]
                        if (a.state != State.PLANNED && b.state != State.PLANNED) continue
                        if (victim == null && cellsOverlap(a.finalRect(), b.finalRect())) {
                            victim = if (b.state == State.PLANNED) b else a
                        }
                    }
                    if (victim != null) break
                }
                val v = victim ?: return
                v.reason = OVERLAP
                v.state = if (v.prev != null) State.KEPT else State.REFUSED
            }
        }
    }
}
