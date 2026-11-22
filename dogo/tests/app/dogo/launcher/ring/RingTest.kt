/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ring

import app.dogo.launcher.agent.Action
import app.dogo.launcher.agent.ActionExecutor
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RingTest {

    // ---- geometry -------------------------------------------------------------

    @Test
    fun `slice zero is straight up and slices go clockwise`() {
        val g = RingGeometry(4, hubRadius = 10f, outerRadius = 100f)
        assertThat(g.hit(0f, -50f)).isEqualTo(0) // up
        assertThat(g.hit(50f, 0f)).isEqualTo(1) // right
        assertThat(g.hit(0f, 50f)).isEqualTo(2) // down
        assertThat(g.hit(-50f, 0f)).isEqualTo(3) // left
    }

    @Test
    fun `hub, slice borders and reach`() {
        val g = RingGeometry(4, 10f, 100f)
        assertThat(g.hit(3f, 3f)).isEqualTo(RingGeometry.HUB)
        // 44 degrees right of up is still slice 0, 46 is slice 1.
        fun at(deg: Double) = Math.toRadians(deg - 90).let { g.hit((kotlin.math.cos(it) * 50).toFloat(), (kotlin.math.sin(it) * 50).toFloat()) }
        assertThat(at(44.0)).isEqualTo(0)
        assertThat(at(46.0)).isEqualTo(1)
        assertThat(at(-44.0)).isEqualTo(0)
        assertThat(at(-46.0)).isEqualTo(3)
        // Flicks past the rim still pick a slice unless reach is limited.
        assertThat(g.hit(0f, -400f)).isEqualTo(0)
        assertThat(g.hit(0f, -400f, reach = 120f)).isEqualTo(RingGeometry.NONE)
    }

    @Test
    fun `icon positions sit between hub and rim`() {
        val g = RingGeometry(6, 40f, 120f)
        val (x, y) = g.position(0)
        assertThat(x).isWithin(0.01f).of(0f)
        assertThat(y).isWithin(0.01f).of(-80f)
        repeat(6) { i ->
            val (px, py) = g.position(i)
            assertThat(g.hit(px, py)).isEqualTo(i)
        }
    }

    @Test
    fun `ring centre is pulled inside the screen`() {
        val (x, y) = RingGeometry.clampCenter(5f, 2000f, 150f, 0f, 100f, 1440f, 2800f)
        assertThat(x).isEqualTo(150f)
        assertThat(y).isEqualTo(2000f)
        val (x2, y2) = RingGeometry.clampCenter(1430f, 120f, 150f, 0f, 100f, 1440f, 2800f)
        assertThat(x2).isEqualTo(1290f)
        assertThat(y2).isEqualTo(250f)
        // Too small to fit: centred.
        assertThat(RingGeometry.clampCenter(10f, 10f, 150f, 0f, 0f, 200f, 200f)).isEqualTo(100f to 100f)
    }

    // ---- building -------------------------------------------------------------

    private val phone = listOf(
        RingBuilder.AppEntry("com.android.dialer", "Phone"),
        RingBuilder.AppEntry("com.android.messaging", "Messaging"),
        RingBuilder.AppEntry("com.android.contacts", "Contacts"),
        RingBuilder.AppEntry("org.lineageos.jelly", "Browser"),
        RingBuilder.AppEntry("org.lineageos.aperture", "Camera"),
        RingBuilder.AppEntry("org.lineageos.twelve", "Twelve", category = 1),
        RingBuilder.AppEntry("com.android.calculator2", "Calculator"),
        RingBuilder.AppEntry("com.termux", "Termux"),
        RingBuilder.AppEntry("com.android.settings", "Settings"),
        RingBuilder.AppEntry("org.fdroid.fdroid", "F-Droid"),
        RingBuilder.AppEntry("com.example.mystery", "Mystery"),
        RingBuilder.AppEntry("app.dogo.launcher.debug", "Dogo"),
    )

    @Test
    fun `apps are grouped by what they are for, Dogo left out`() {
        val groups = RingBuilder.automaticGroups(phone).associate { it.name to it.apps }
        assertThat(groups["People"]).containsExactly("com.android.contacts", "com.android.messaging", "com.android.dialer")
        assertThat(groups["Web"]).containsExactly("org.lineageos.jelly")
        assertThat(groups["Media"]).containsExactly("org.lineageos.aperture", "org.lineageos.twelve")
        assertThat(groups["Tools"]).containsExactly("com.android.calculator2", "com.termux")
        assertThat(groups["System"]).containsExactly("org.fdroid.fdroid", "com.android.settings")
        assertThat(groups["More"]).containsExactly("com.example.mystery")
        assertThat(groups.values.flatten()).doesNotContain("app.dogo.launcher.debug")
    }

    @Test
    fun `root ring ends with the Dogo slice, single-app groups become the app`() {
        val root = RingBuilder.build(RingBuilder.automaticGroups(phone), phone)
        val labels = root.children.map { it.label }
        assertThat(labels.last()).isEqualTo(RingBuilder.DOGO)
        assertThat(labels).containsAtLeast("People", "Media", "Tools", "System").inOrder()
        assertThat(root.children.first { it.label == "Browser" }).isInstanceOf(RingItem.App::class.java)
        val dogo = root.children.last() as RingItem.Group
        assertThat(dogo.children.filterIsInstance<RingItem.Command>().map { it.command }).containsAtLeast(RingCommand.TALK, RingCommand.ASK_DOGO)
    }

    @Test
    fun `big groups overflow into nested More slices`() {
        val many = (1..30).map { RingBuilder.AppEntry("com.example.app$it", "App $it") }
        val root = RingBuilder.build(listOf(RingGroup("All", many.map { it.pkg })), many)
        val all = root.children.first() as RingItem.Group
        assertThat(all.children.size).isAtMost(RingBuilder.MAX_SLICES)
        fun count(item: RingItem): Int = when (item) {
            is RingItem.Group -> item.children.sumOf { count(it) }
            else -> 1
        }
        assertThat(count(all)).isEqualTo(30)
        assertThat(all.children.last().label).isEqualTo(RingBuilder.MORE)
    }

    @Test
    fun `uninstalled packages and empty groups are skipped`() {
        val root = RingBuilder.build(listOf(RingGroup("Gone", listOf("com.not.here")), RingGroup("Calc", listOf("com.android.calculator2", "com.nope"))), phone)
        assertThat(root.children.map { it.label }).containsExactly("Calculator", RingBuilder.DOGO).inOrder()
    }

    // ---- config and the agent action -------------------------------------------

    @Test
    fun `config round-trips and survives garbage`() {
        val config = RingConfig(enabled = false, style = RingStyle.RADAR, holdMs = 200, groups = listOf(RingGroup("Work", listOf("a.b"))))
        assertThat(RingConfig.fromJson(config.toJson())).isEqualTo(config)
        assertThat(RingConfig.fromJson("{nope")).isEqualTo(RingConfig())
        assertThat(RingConfig.fromJson("""{"holdMs": 99999}""").holdMs).isEqualTo(RingConfig.HOLD_RANGE.last)
        assertThat(RingConfig.fromJson("""{"style": "sparkly"}""").style).isEqualTo(RingStyle.GLOW)
        assertThat(RingConfig.fromJson("""{"style": "Fire"}""").style).isEqualTo(RingStyle.FIRE)
        assertThat(RingStyle.fromWire(" electric ")).isEqualTo(RingStyle.ELECTRIC)
        assertThat(RingConfig().isAutomatic).isTrue()
    }

    @Test
    fun `the agent can arrange the ring`() {
        val action = ActionExecutor.parseAction(
            """{"action":"arrange_ring","params":{"groups":[{"name":"Talk","apps":["com.android.dialer","com.android.dialer",""]},{"name":"","apps":["x.y"]}],"style":"radar"}}""",
        )
        assertThat(action).isEqualTo(Action.ArrangeRing(listOf(RingGroup("Talk", listOf("com.android.dialer"))), "radar"))
        assertThat(ActionExecutor.parseAction(ActionExecutor.toJson(action))).isEqualTo(action)
        // Nothing to change is not an action.
        assertThat(ActionExecutor.parseAction("""{"action":"arrange_ring","params":{}}""")).isEqualTo(Action.Unknown)
    }
}
