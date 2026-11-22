/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.privilege

import app.dogo.launcher.agent.Action
import app.dogo.launcher.privilege.ActionRequirements.Need

/**
 * Picks how each action will run, from what is available right now.
 *
 * Pure logic: availability comes in as data, so the choice can be tested
 * without a phone. Prefers the least powerful route that works, so Dogo never
 * reaches for root when a permission would do.
 */
object PrivilegePlanner {

    data class Availability(
        val grantedPermissions: Set<String> = emptySet(),
        val shizukuReady: Boolean = false,
        val rootReady: Boolean = false,
    ) {
        /** The shell routes usable now, least powerful first. */
        val shellTiers: List<Tier>
            get() = buildList {
                if (shizukuReady) add(Tier.SHIZUKU)
                if (rootReady) add(Tier.ROOT)
            }
    }

    sealed interface Plan {
        /** Apply inside the launcher. */
        data object InLauncher : Plan

        /** Apply with a permission Dogo already holds. */
        data class WithPermission(val permission: String) : Plan

        /** Apply by running [command] through [tier]. */
        data class ViaShell(val tier: Tier, val command: List<String>) : Plan

        /** Can't run yet; [hint] says what would unlock it. */
        data class Blocked(val needs: Tier, val hint: String) : Plan
    }

    fun plan(action: Action, availability: Availability, packageName: String): Plan {
        val need = ActionRequirements.needFor(action)
            ?: return Plan.Blocked(Tier.NONE, "Dogo doesn't know how to do this.")
        return when (need) {
            Need.Launcher -> Plan.InLauncher

            is Need.Permission -> when {
                need.permission in availability.grantedPermissions -> Plan.WithPermission(need.permission)

                availability.shellTiers.isNotEmpty() -> Plan.ViaShell(availability.shellTiers.first(), need.fallback)

                else -> Plan.Blocked(
                    Tier.ADB_GRANT,
                    "Needs a one-time permission. From a computer, run: " +
                        ActionRequirements.adbGrantCommand(packageName, need.permission) +
                        " (or set up Shizuku or root in Dogo Options).",
                )
            }

            is Need.Shell -> availability.shellTiers.firstOrNull()
                ?.let { Plan.ViaShell(it, need.command) }
                ?: Plan.Blocked(Tier.SHIZUKU, "Needs Shizuku or root. Set one up under Dogo Options > System access.")
        }
    }
}
