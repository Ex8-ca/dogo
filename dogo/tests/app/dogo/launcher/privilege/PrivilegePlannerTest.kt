/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.privilege

import app.dogo.launcher.agent.Action
import app.dogo.launcher.privilege.ActionRequirements.Need
import app.dogo.launcher.privilege.PrivilegePlanner.Availability
import app.dogo.launcher.privilege.PrivilegePlanner.Plan
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PrivilegePlannerTest {

    private val pkg = "app.dogo.launcher"
    private val nothing = Availability()
    private val shizuku = Availability(shizukuReady = true)
    private val root = Availability(rootReady = true)
    private val both = Availability(shizukuReady = true, rootReady = true)
    private val granted = Availability(grantedPermissions = setOf(ActionRequirements.WRITE_SECURE_SETTINGS))

    private val launcherActions = listOf(
        Action.SetTheme("dark"),
        Action.AddToHome("org.fdroid.fdroid"),
        Action.HideApp("com.android.calculator2"),
        Action.CreateFolder("Chat", listOf("a")),
        Action.SetWallpaper("#000000"),
    )

    @Test
    fun `launcher actions need nothing, whatever is available`() {
        for (action in launcherActions) {
            for (a in listOf(nothing, shizuku, root, both, granted)) {
                assertThat(PrivilegePlanner.plan(action, a, pkg)).isEqualTo(Plan.InLauncher)
            }
        }
    }

    @Test
    fun `Dogo's theme and the phone's dark mode are different actions with different needs`() {
        assertThat(ActionRequirements.needFor(Action.SetTheme("dark"))).isEqualTo(Need.Launcher)
        assertThat(ActionRequirements.needFor(Action.SetSystemDarkMode(true)))
            .isEqualTo(Need.Shell(listOf("cmd", "uimode", "night", "yes")))
        assertThat(ActionRequirements.needFor(Action.SetSystemDarkMode(false)))
            .isEqualTo(Need.Shell(listOf("cmd", "uimode", "night", "no")))
    }

    @Test
    fun `system dark mode is blocked without a shell and says how to unblock`() {
        val plan = PrivilegePlanner.plan(Action.SetSystemDarkMode(true), nothing, pkg)
        assertThat(plan).isInstanceOf(Plan.Blocked::class.java)
        plan as Plan.Blocked
        assertThat(plan.needs).isEqualTo(Tier.SHIZUKU)
        assertThat(plan.hint).contains("Shizuku or root")
    }

    @Test
    fun `an adb grant alone does not unlock system dark mode`() {
        assertThat(PrivilegePlanner.plan(Action.SetSystemDarkMode(true), granted, pkg)).isInstanceOf(Plan.Blocked::class.java)
    }

    @Test
    fun `prefers Shizuku over root when both are ready`() {
        // Least power that works.
        val plan = PrivilegePlanner.plan(Action.SetSystemDarkMode(true), both, pkg) as Plan.ViaShell
        assertThat(plan.tier).isEqualTo(Tier.SHIZUKU)
        assertThat((PrivilegePlanner.plan(Action.SetSystemDarkMode(true), root, pkg) as Plan.ViaShell).tier)
            .isEqualTo(Tier.ROOT)
    }

    @Test
    fun `animation speed uses a granted permission before any shell`() {
        val withEverything = both.copy(grantedPermissions = granted.grantedPermissions)
        assertThat(PrivilegePlanner.plan(Action.SetAnimationScale(0.5f), withEverything, pkg))
            .isEqualTo(Plan.WithPermission(ActionRequirements.WRITE_SECURE_SETTINGS))
    }

    @Test
    fun `animation speed falls back to the shell with all three scales`() {
        val plan = PrivilegePlanner.plan(Action.SetAnimationScale(0.5f), root, pkg) as Plan.ViaShell
        assertThat(plan.tier).isEqualTo(Tier.ROOT)
        assertThat(plan.command.first()).isEqualTo("sh")
        val script = plan.command.last()
        ActionRequirements.ANIMATION_SCALE_KEYS.forEach { assertThat(script).contains("settings put global $it 0.5") }
    }

    @Test
    fun `animation speed without access gives the exact adb command`() {
        val plan = PrivilegePlanner.plan(Action.SetAnimationScale(0.5f), nothing, pkg) as Plan.Blocked
        assertThat(plan.needs).isEqualTo(Tier.ADB_GRANT)
        assertThat(plan.hint).contains("adb shell pm grant app.dogo.launcher android.permission.WRITE_SECURE_SETTINGS")
    }

    @Test
    fun `animation scales snap to what Developer options offers`() {
        assertThat(ActionRequirements.clampAnimationScale(0.4f)).isEqualTo(0.5f)
        assertThat(ActionRequirements.clampAnimationScale(-3f)).isEqualTo(0f)
        assertThat(ActionRequirements.clampAnimationScale(100f)).isEqualTo(10f)
        assertThat(ActionRequirements.formatScale(1f)).isEqualTo("1")
        assertThat(ActionRequirements.formatScale(0.5f)).isEqualTo("0.5")
    }

    @Test
    fun `unknown actions are blocked`() {
        assertThat(PrivilegePlanner.plan(Action.Unknown, both, pkg)).isInstanceOf(Plan.Blocked::class.java)
    }

    @Test
    fun `shell arguments are quoted safely`() {
        assertThat(listOf("cmd", "uimode", "night", "yes").toShellLine()).isEqualTo("cmd uimode night yes")
        assertThat(listOf("echo", "it's; rm -rf /").toShellLine()).isEqualTo("echo 'it'\\''s; rm -rf /'")
        assertThat(listOf("x", "").toShellLine()).isEqualTo("x ''")
    }

    @Test
    fun `minimum tiers are ordered`() {
        assertThat(ActionRequirements.minimumTier(Need.Launcher)).isEqualTo(Tier.NONE)
        assertThat(ActionRequirements.minimumTier(Need.Permission("p", emptyList()))).isEqualTo(Tier.ADB_GRANT)
        assertThat(ActionRequirements.minimumTier(Need.Shell(emptyList()))).isEqualTo(Tier.SHIZUKU)
        assertThat(Tier.entries).containsExactly(Tier.NONE, Tier.ADB_GRANT, Tier.SHIZUKU, Tier.ROOT, Tier.SYSTEM).inOrder()
    }
}
