/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

/** Widgets, layout, dock and gradient wallpaper: parsing, limits and round trips. */
class HomeLayoutActionsTest {

    @Test
    fun `add_widget parses provider, size and page`() {
        val a = ActionExecutor.parseAction("""{"action":"add_widget","params":{"widget":"org.lineageos.twelve/.ui.widgets.NowPlayingAppWidgetProvider","size":"4x2","page":1}}""")
        assertThat(a).isEqualTo(Action.AddWidget("org.lineageos.twelve/.ui.widgets.NowPlayingAppWidgetProvider", "4x2", 1))
        assertThat(ActionExecutor.parseAction(ActionExecutor.toJson(a))).isEqualTo(a)
    }

    @Test
    fun `add_widget without a widget is unknown`() {
        assertThat(ActionExecutor.parseAction("""{"action":"add_widget","params":{"size":"4x2"}}""")).isEqualTo(Action.Unknown)
    }

    @Test
    fun `widget sizes read the ways people write them`() {
        assertThat(WidgetSize.parse("4x2")).isEqualTo(WidgetSize(4, 2))
        assertThat(WidgetSize.parse(" 2 × 2 ")).isEqualTo(WidgetSize(2, 2))
        assertThat(WidgetSize.parse("4 by 1")).isEqualTo(WidgetSize(4, 1))
        assertThat(WidgetSize.parse("big")).isNull()
        assertThat(WidgetSize.parse("40x2")).isNull()
    }

    @Test
    fun `style_home reads only the fields given`() {
        val a = ActionExecutor.parseAction("""{"action":"style_home","params":{"columns":5,"labels":"off","icon_size":"90%","accent":"#7AA2F7"}}""")
        val style = (a as Action.StyleHome).style
        assertThat(style).isEqualTo(HomeStyle(columns = 5, labels = false, iconSize = 0.9f, accent = "#7AA2F7"))
        assertThat(ActionExecutor.parseAction(ActionExecutor.toJson(a))).isEqualTo(a)
    }

    @Test
    fun `style_home with nothing in it is unknown`() {
        assertThat(ActionExecutor.parseAction("""{"action":"style_home","params":{"columns":"lots"}}""")).isEqualTo(Action.Unknown)
    }

    @Test
    fun `layout values are pulled into range and bad ones dropped`() {
        val s = HomeStyle(columns = 40, rows = 1, iconSize = 3f, iconShape = "Blob", accent = "blue").clamped()
        assertThat(s.columns).isEqualTo(10)
        assertThat(s.rows).isEqualTo(3)
        assertThat(s.iconSize).isEqualTo(1.5f)
        assertThat(s.iconShape).isNull()
        assertThat(s.accent).isNull()
        assertThat(HomeStyle(iconShape = " Squircle ").clamped().iconShape).isEqualTo("squircle")
    }

    @Test
    fun `layout change reads as plain words`() {
        assertThat(HomeStyle(columns = 4, rows = 5, labels = false, dock = true).describe())
            .isEqualTo("4 × 5 grid, hide labels, show the dock")
    }

    @Test
    fun `icon_pack empty means system icons, missing means leave it`() {
        assertThat(HomeStyle.fromJson(JSONObject("""{"icon_pack":""}""")).iconPack).isEqualTo("")
        assertThat(HomeStyle.fromJson(JSONObject("""{"labels":true}""")).iconPack).isNull()
    }

    @Test
    fun `set_dock keeps order and drops repeats`() {
        val a = ActionExecutor.parseAction("""{"action":"set_dock","params":{"apps":["a.phone","b.web","a.phone"," "]}}""")
        assertThat(a).isEqualTo(Action.SetDock(listOf("a.phone", "b.web")))
        assertThat(ActionExecutor.parseAction("""{"action":"set_dock","params":{"apps":[]}}""")).isEqualTo(Action.Unknown)
    }

    @Test
    fun `gradient wallpaper round trips and a flat one still works`() {
        val g = ActionExecutor.parseAction("""{"action":"set_wallpaper","params":{"color":"#1A1B26","color2":"#414868"}}""")
        assertThat(g).isEqualTo(Action.SetWallpaper("#1A1B26", "#414868"))
        assertThat(ActionExecutor.parseAction(ActionExecutor.toJson(g))).isEqualTo(g)
        assertThat(ActionExecutor.parseAction("""{"action":"set_wallpaper","params":{"color":"#000000"}}""")).isEqualTo(Action.SetWallpaper("#000000"))
    }

    @Test
    fun `new actions have switches and run inside the launcher`() {
        listOf(Action.AddWidget("x"), Action.StyleHome(HomeStyle(labels = true)), Action.SetDock(listOf("a"))).forEach {
            assertThat(AgentOptions.ActionType.of(it)).isNotNull()
            assertThat(app.dogo.launcher.privilege.ActionRequirements.needFor(it))
                .isEqualTo(app.dogo.launcher.privilege.ActionRequirements.Need.Launcher)
        }
    }

    @Test
    fun `a photo of a phone can be copied many changes at once`() {
        val reply = """
            Here's the look from your photo:
            {"action":"set_wallpaper","params":{"color":"#2E1A47","color2":"#C2185B"}}
            {"action":"style_home","params":{"columns":4,"rows":5,"labels":false,"icon_shape":"circle"}}
            {"action":"set_dock","params":{"apps":["com.android.dialer","org.mozilla.fennec_fdroid"]}}
            {"action":"add_widget","params":{"widget":"com.android.deskclock/com.android.alarmclock.DigitalAppWidgetProvider","size":"4x2","page":0}}
        """.trimIndent()
        val actions = ActionExecutor.parseActions(reply)
        assertThat(actions.map { it::class.simpleName }).containsExactly("SetWallpaper", "StyleHome", "SetDock", "AddWidget").inOrder()
    }
}
