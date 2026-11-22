/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.tiles

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TileWriterTest {

    private val kinds = setOf("clock", "weather", "battery", "notes")

    private fun parse(text: String) = TileParser.parse(text, kinds)

    private fun noIssues(config: TileConfig) = config.copy(issues = emptyList())

    private fun overlap(a: IntArray, b: IntArray): Boolean = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

    // ---- write ----

    @Test
    fun `write gives a header, a general block and sorted pages with tiles in reading order`() {
        val config = TileConfig(
            pages = listOf(
                TilePage(2, 3, 3, listOf(TileSpec("notes", 1, 1, 1, 1), TileSpec("clock", 1, 1, 0, 0))),
                TilePage(1, 4, 8, listOf(TileSpec("weather", 4, 3, 0, 2), TileSpec("clock", 4, 2, 0, 0))),
            ),
            style = TileStyle(gaps = 12, rounding = 20, border = TileColour.Rgba(255, 136, 0, 255), borderSize = 3, blur = true),
            issues = emptyList(),
        )

        val text = TileWriter.write(config)

        val expected = """
            # Dogo tiles: one page per line, tiles are kind WxH+column+row.
            general {
                gaps = 12
                rounding = 20
                border = rgb(ff8800)
                border_size = 3
                blur = on
            }
            page = 1, grid 4x8, clock 4x2+0+0, weather 4x3+0+2
            page = 2, grid 3x3, clock 1x1+0+0, notes 1x1+1+1
        """.trimIndent() + "\n"
        assertThat(text).isEqualTo(expected)
        assertThat(text).startsWith("#")
    }

    @Test
    fun `write of the default style and no pages parses back to an empty config`() {
        val text = TileWriter.write(TileConfig(emptyList(), TileStyle(), emptyList()))

        val back = parse(text)

        assertThat(back.issues).isEmpty()
        assertThat(back.isEmpty).isTrue()
        assertThat(back.style).isEqualTo(TileStyle())
    }

    @Test
    fun `colours are written without a hash and parse back`() {
        val colours = listOf(
            TileColour.Accent,
            TileColour.None,
            TileColour.Rgba(0, 0, 0, 255),
            TileColour.Rgba(1, 2, 3, 255),
            TileColour.Rgba(255, 255, 255, 0),
            TileColour.Rgba(171, 205, 239, 18),
        )
        for (colour in colours) {
            val config = TileConfig(emptyList(), TileStyle(border = colour), emptyList())

            val text = TileWriter.write(config)
            val back = parse(text)

            assertThat(text).doesNotContain("border = #")
            assertThat(text.lines().filter { !it.startsWith("#") }.joinToString("\n")).doesNotContain("#")
            assertThat(back.issues).isEmpty()
            assertThat(back.style.border).isEqualTo(colour)
        }
        assertThat(TileWriter.write(TileConfig(emptyList(), TileStyle(border = TileColour.Rgba(171, 205, 239, 18)), emptyList())))
            .contains("border = rgba(abcdef12)")
    }

    @Test
    fun `parse of write of parse is the same config and write output has zero issues`() {
        val sources = listOf(
            "",
            "# only a comment",
            "general { gaps = 3 }\npage = 2, grid 3x3, notes 1x1+2+2, clock 2x2+0+0\npage = 1, grid 4x8, weather 4x3+0+2, clock 4x2+0+0",
            "general {\n gaps = 99\n rounding = 5\n border = RGBA(AABBCC80)\n blur = on\n}\npage = 1, grid 4x4, clock 5x5+0+0, battery 2x2+0+0, notes 2x2+1+1, bogus 1x1+3+3",
            "page = 1, grid 10x10, widget:com.example.cal/.Cal 10x4+0+0, widget:org.a/Outer\$Inner 4x4+0+4, clock 1x1+9+9",
            "page = 1, grid 4x4, clock 2x2+0+0\npage = 1, grid 3x3, notes 1x1+0+0\npage = 5, grid 3x3",
            "garbage ]]] {{{ page = = ,,,",
        )
        for (source in sources) {
            val first = parse(source)

            val text = TileWriter.write(first)
            val second = parse(text)

            assertThat(second.issues).isEmpty()
            assertThat(noIssues(second)).isEqualTo(noIssues(first))
            // Writing again is stable.
            assertThat(TileWriter.write(second)).isEqualTo(text)
        }
    }

    @Test
    fun `a config built in code with unsorted tiles still writes parseable text`() {
        val config = TileConfig(
            listOf(TilePage(1, 3, 3, listOf(TileSpec("notes", 1, 1, 2, 2), TileSpec("clock", 2, 1, 0, 0), TileSpec("weather", 1, 1, 0, 1)))),
            TileStyle(),
            emptyList(),
        )

        val back = parse(TileWriter.write(config))

        assertThat(back.issues).isEmpty()
        assertThat(back.page(1)!!.tiles.map { it.kind }).containsExactly("clock", "weather", "notes").inOrder()
    }

    // ---- describe ----

    @Test
    fun `describe is empty for equal configs and ignores issues and tile order`() {
        val a = parse("general { gaps = 8 }\npage = 1, grid 4x4, clock 2x2+0+0, notes 1x1+3+3, bogus 1x1+0+3")
        val b = parse("page = 1, grid 4x4, notes 1x1+3+3, clock 2x2+0+0\ngeneral { gaps = 8 }")

        assertThat(TileWriter.describe(a, b)).isEmpty()
        assertThat(TileWriter.describe(a, a)).isEmpty()
        assertThat(TileWriter.describe(null, parse(""))).isEmpty()
    }

    @Test
    fun `describe reports added and removed tiles on a page and a gaps change`() {
        val old = parse("general { gaps = 8 }\npage = 1, grid 4x8, weather 4x3+0+2, notes 4x1+0+7")
        val new = parse("general { gaps = 12 }\npage = 1, grid 4x8, clock 4x2+0+0, notes 4x1+0+7")

        val lines = TileWriter.describe(old, new)

        assertThat(lines).containsExactly("Gaps 8 -> 12", "Page 1: adds clock (4x2), removes weather").inOrder()
    }

    @Test
    fun `describe covers every style key`() {
        val old = parse("")
        val new = parse("general { gaps = 0  rounding = 4  border = none  border_size = 5  blur = on }")

        assertThat(TileWriter.describe(old, new)).containsExactly(
            "Gaps 8 -> 0",
            "Rounding 18 -> 4",
            "Border accent -> none",
            "Border size 2 -> 5",
            "Blur off -> on",
        ).inOrder()
        assertThat(TileWriter.describe(parse("general { border = rgb(ff0000) }"), parse("general { border = accent }")))
            .containsExactly("Border rgb(ff0000) -> accent")
    }

    @Test
    fun `describe treats null as an empty starting point`() {
        val new = parse("page = 1, grid 4x4, clock 2x2+0+0, weather 2x2+2+0")

        val lines = TileWriter.describe(null, new)

        assertThat(lines).containsExactly("Page 1: new page with clock (2x2), weather (2x2)")
    }

    @Test
    fun `describe reports new removed and resized pages and moved tiles`() {
        val old = parse("page = 1, grid 4x4, clock 2x2+0+0\npage = 2, grid 3x3, notes 1x1+0+0\npage = 4, grid 3x3, battery 1x1+0+0")
        val new = parse("page = 1, grid 4x4, clock 2x2+2+2\npage = 2, grid 4x4, notes 1x1+0+0\npage = 3, grid 3x3")

        val lines = TileWriter.describe(old, new)

        assertThat(lines).containsExactly(
            "Page 1: moves tiles",
            "Page 2: grid 3x3 -> 4x4",
            "Page 3: new empty page",
            "Page 4: removed",
        ).inOrder()
    }

    @Test
    fun `describe tells a resized tile as one add and one remove`() {
        val old = parse("page = 1, grid 4x4, clock 2x2+0+0")
        val new = parse("page = 1, grid 4x4, clock 4x2+0+0")

        assertThat(TileWriter.describe(old, new)).containsExactly("Page 1: adds clock (4x2), removes clock")
    }

    @Test
    fun `describe names a widget by its class and keeps it short`() {
        val old = parse("")
        val new = parse("page = 1, grid 4x4, widget:com.example.calendar/com.example.calendar.AgendaWidget 4x2+0+0")

        val lines = TileWriter.describe(old, new)

        assertThat(lines).containsExactly("Page 1: new page with widget AgendaWidget (4x2)")
    }

    @Test
    fun `describe returns at most 12 lines`() {
        val manyPages = (1..8).joinToString("\n") { "page = $it, grid 3x3, clock 1x1+0+0" }
        val new = parse("general { gaps = 1  rounding = 1  border = none  border_size = 1  blur = on }\n$manyPages")

        val lines = TileWriter.describe(null, new)

        // 5 style lines + 8 page lines = 13 changes, so the list is cut to 12 with a note.
        assertThat(lines).hasSize(12)
        assertThat(lines.last()).isEqualTo("...and 2 more changes")
        assertThat(lines.first()).isEqualTo("Gaps 8 -> 1")
    }

    @Test
    fun `describe lines never echo long kind names`() {
        val long = "widget:com.example.verylongpackagename/com.example.VeryVeryVeryVeryLongClassNameHere"
        val lines = TileWriter.describe(null, parse("page = 1, grid 4x4, $long 2x2+0+0"))

        assertThat(lines).hasSize(1)
        assertThat(lines[0]).doesNotContain("verylongpackagename")
        assertThat(lines[0].length).isLessThan(80)
    }

    // ---- geometry ----

    private fun fullGrid(columns: Int, rows: Int): TilePage {
        val tiles = ArrayList<TileSpec>()
        for (y in 0 until rows) for (x in 0 until columns) tiles.add(TileSpec("notes", 1, 1, x, y))
        return TilePage(1, columns, rows, tiles)
    }

    @Test
    fun `a tile covering the whole grid fills the area edge to edge`() {
        val page = TilePage(1, 4, 8, listOf(TileSpec("clock", 4, 8, 0, 0)))

        val rect = TileGeometry.cellRect(page, page.tiles[0], 1080, 1920, 16)

        assertThat(rect.asList()).containsExactly(0, 0, 1080, 1920).inOrder()
    }

    @Test
    fun `cells divide the area with gaps between them and none at the outer edge`() {
        val page = fullGrid(4, 2)

        val first = TileGeometry.cellRect(page, TileSpec("notes", 1, 1, 0, 0), 1000, 500, 20)
        val second = TileGeometry.cellRect(page, TileSpec("notes", 1, 1, 1, 0), 1000, 500, 20)
        val last = TileGeometry.cellRect(page, TileSpec("notes", 1, 1, 3, 1), 1000, 500, 20)

        // Width: (1000 - 3*20) / 4 = 235. Height: (500 - 20) / 2 = 240.
        assertThat(first.asList()).containsExactly(0, 0, 235, 240).inOrder()
        assertThat(second.asList()).containsExactly(255, 0, 490, 240).inOrder()
        assertThat(last.asList()).containsExactly(765, 260, 1000, 500).inOrder()
    }

    @Test
    fun `the last column and row absorb the rounding remainder`() {
        val page = fullGrid(3, 3)

        val first = TileGeometry.cellRect(page, TileSpec("notes", 1, 1, 0, 0), 100, 100, 0)
        val last = TileGeometry.cellRect(page, TileSpec("notes", 1, 1, 2, 2), 100, 100, 0)

        assertThat(first.asList()).containsExactly(0, 0, 33, 33).inOrder()
        assertThat(last.asList()).containsExactly(66, 66, 100, 100).inOrder()
    }

    @Test
    fun `adjacent tiles never overlap and the grid fills the area for many sizes`() {
        val areas = listOf(1080 to 1920, 1079 to 1913, 720 to 1280, 401 to 799, 100 to 100, 33 to 47)
        val grids = listOf(1 to 1, 2 to 2, 4 to 8, 3 to 7, 8 to 16, 5 to 3)
        val gaps = listOf(0, 1, 7, 16, 31)
        for ((width, height) in areas) {
            for ((columns, rows) in grids) {
                for (gap in gaps) {
                    val page = fullGrid(columns, rows)
                    val rects = page.tiles.map { TileGeometry.cellRect(page, it, width, height, gap) }

                    for (i in rects.indices) {
                        val r = rects[i]
                        assertThat(r[0]).isAtLeast(0)
                        assertThat(r[1]).isAtLeast(0)
                        assertThat(r[2]).isAtMost(width)
                        assertThat(r[3]).isAtMost(height)
                        assertThat(r[2]).isGreaterThan(r[0])
                        assertThat(r[3]).isGreaterThan(r[1])
                        for (j in i + 1 until rects.size) {
                            assertThat(overlap(r, rects[j])).isFalse()
                        }
                    }
                    assertThat(rects.minOf { it[0] }).isEqualTo(0)
                    assertThat(rects.minOf { it[1] }).isEqualTo(0)
                    assertThat(rects.maxOf { it[2] }).isEqualTo(width)
                    assertThat(rects.maxOf { it[3] }).isEqualTo(height)
                }
            }
        }
    }

    @Test
    fun `neighbouring cells in a row are exactly one gap apart`() {
        val page = fullGrid(4, 1)
        val rects = page.tiles.map { TileGeometry.cellRect(page, it, 1000, 300, 12) }

        for (i in 0 until rects.size - 1) {
            assertThat(rects[i + 1][0] - rects[i][2]).isEqualTo(12)
        }
    }

    @Test
    fun `a multi-cell tile spans its cells and the gaps between them and does not touch a neighbour`() {
        val page = TilePage(1, 4, 4, listOf(TileSpec("clock", 2, 2, 0, 0), TileSpec("weather", 2, 2, 2, 0), TileSpec("notes", 4, 2, 0, 2)))

        val clock = TileGeometry.cellRect(page, page.tiles[0], 400, 400, 10)
        val weather = TileGeometry.cellRect(page, page.tiles[1], 400, 400, 10)
        val notes = TileGeometry.cellRect(page, page.tiles[2], 400, 400, 10)

        // Cell = (400 - 30) / 4 = 92, so a 2-cell tile is 92 + 10 + 92 = 194.
        assertThat(clock.asList()).containsExactly(0, 0, 194, 194).inOrder()
        assertThat(weather.asList()).containsExactly(204, 0, 400, 194).inOrder()
        assertThat(notes.asList()).containsExactly(0, 204, 400, 400).inOrder()
        assertThat(overlap(clock, weather)).isFalse()
        assertThat(overlap(clock, notes)).isFalse()
        assertThat(overlap(weather, notes)).isFalse()
    }

    @Test
    fun `an oversized gap or tiny area never throws and still stays inside the area`() {
        val page = fullGrid(8, 16)
        for ((w, h, gap) in listOf(Triple(10, 10, 32), Triple(0, 0, 8), Triple(-5, -5, -3), Triple(1, 1, 100))) {
            for (tile in page.tiles) {
                val r = TileGeometry.cellRect(page, tile, w, h, gap)

                assertThat(r).hasLength(4)
                assertThat(r[0]).isAtLeast(0)
                assertThat(r[2]).isAtMost(maxOf(w, 0))
                assertThat(r[3]).isAtMost(maxOf(h, 0))
            }
        }
    }
}
