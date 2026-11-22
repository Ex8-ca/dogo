/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.privilege

import app.dogo.launcher.agent.Action

/**
 * What each action needs, in plain data so it can be unit tested.
 *
 * [Need.Shell] actions are written as the exact command Android ships, so the
 * same command runs whether it goes through Shizuku or root, and the user can
 * be shown precisely what will run.
 */
object ActionRequirements {

    sealed interface Need {
        /** Runs inside Dogo with no special access. */
        data object Launcher : Need

        /**
         * Needs [permission]. A one-time `adb pm grant` is enough; otherwise
         * the same change can go through a shell as [fallback].
         */
        data class Permission(val permission: String, val fallback: List<String>) : Need

        /** Handled by an enabled plugin, with the capabilities it was granted. */
        data class Plugin(val pluginId: String) : Need

        /** Needs a privileged shell (Shizuku, root, or system). */
        data class Shell(val command: List<String>) : Need
    }

    const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    /** The three global animation scales Settings > Developer options exposes. */
    val ANIMATION_SCALE_KEYS = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")

    /** Developer options only offers these; anything else is clamped to the nearest. */
    private val ANIMATION_SCALES = listOf(0f, 0.5f, 1f, 1.5f, 2f, 5f, 10f)

    fun clampAnimationScale(scale: Float): Float = ANIMATION_SCALES.minBy { kotlin.math.abs(it - scale) }

    fun needFor(action: Action): Need? = when (action) {
        is Action.SetTheme,
        is Action.AddToHome,
        is Action.HideApp,
        is Action.CreateFolder,
        is Action.SetWallpaper,
        is Action.ArrangeRing,
        -> Need.Launcher

        is Action.SetAnimationScale -> {
            val scale = formatScale(clampAnimationScale(action.scale))
            Need.Permission(
                WRITE_SECURE_SETTINGS,
                fallback = listOf("sh", "-c", ANIMATION_SCALE_KEYS.joinToString(" && ") { "settings put global $it $scale" }),
            )
        }

        is Action.SetSystemDarkMode -> Need.Shell(listOf("cmd", "uimode", "night", if (action.enabled) "yes" else "no"))

        is Action.Plugin -> Need.Plugin(action.pluginId)

        Action.Unknown -> null
    }

    /** "0.5" not "0.5000", "1" not "1.0": exactly what Settings writes. */
    fun formatScale(scale: Float): String = if (scale == scale.toInt().toFloat()) scale.toInt().toString() else scale.toString()

    /** The lowest tier that can satisfy [need]. */
    fun minimumTier(need: Need): Tier = when (need) {
        Need.Launcher, is Need.Plugin -> Tier.NONE
        is Need.Permission -> Tier.ADB_GRANT
        is Need.Shell -> Tier.SHIZUKU
    }

    /** The command to give the user so they can grant [permission] from a computer. */
    fun adbGrantCommand(packageName: String, permission: String = WRITE_SECURE_SETTINGS): String = "adb shell pm grant $packageName $permission"
}
