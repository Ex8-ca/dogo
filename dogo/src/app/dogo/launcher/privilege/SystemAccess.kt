/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.privilege

import android.content.Context
import android.content.pm.PackageManager
import app.dogo.launcher.plugins.PluginRegistry
import app.dogo.launcher.plugins.PluginStore

/**
 * Reports and requests the access Dogo has, across every [Tier].
 *
 * The single place that knows about Shizuku and root, so nothing else in Dogo
 * has to care which one is in use.
 */
class SystemAccess(context: Context) {
    private val context = context.applicationContext
    private val shizuku = ShizukuShellRunner()
    private val root = RootShellRunner()
    private val plugins = PluginRegistry(context)
    private val pluginStore = PluginStore(context)

    /** Installed plugins the user has enabled (at their current version). */
    fun enabledPlugins(): List<PluginRegistry.Installed> = plugins.scan().plugins.filter(pluginStore::isEnabled)

    fun hasPermission(permission: String): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun isShizukuInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(ShizukuShellRunner.PACKAGE, PackageManager.PackageInfoFlags.of(0))
    }.isSuccess

    suspend fun availability(): PrivilegePlanner.Availability = PrivilegePlanner.Availability(
        grantedPermissions = setOf(ActionRequirements.WRITE_SECURE_SETTINGS).filter(::hasPermission).toSet(),
        shizukuReady = shizuku.isReady(),
        rootReady = root.isReady(),
        enabledPlugins = enabledPlugins().map { it.manifest.id }.toSet(),
    )

    fun runner(tier: Tier): ShellRunner = when (tier) {
        Tier.SHIZUKU -> shizuku
        Tier.ROOT -> root
        else -> error("$tier has no shell")
    }

    suspend fun statuses(): List<TierStatus> {
        val adbGranted = hasPermission(ActionRequirements.WRITE_SECURE_SETTINGS)
        val shizukuStatus = when {
            !isShizukuInstalled() -> "Not installed. Get Shizuku from F-Droid or GitHub."
            !shizuku.isRunning() -> "Installed but not running. Open Shizuku and start it."
            !shizuku.hasPermission() -> "Running. Tap to allow Dogo."
            else -> "Ready."
        }
        val rootStatus = if (root.isReady()) "Ready." else "Not granted. Tap to ask your root manager."
        return listOf(
            TierStatus(Tier.NONE, true, "Always available."),
            TierStatus(
                Tier.ADB_GRANT,
                adbGranted,
                if (adbGranted) {
                    "Granted: Dogo can change animation speed."
                } else {
                    "Not granted. From a computer: " + ActionRequirements.adbGrantCommand(context.packageName)
                },
            ),
            TierStatus(Tier.SHIZUKU, shizuku.isReady(), shizukuStatus),
            TierStatus(Tier.ROOT, root.isReady(), rootStatus),
            TierStatus(Tier.SYSTEM, false, "Comes with a future Dogo build of LineageOS."),
        )
    }

    /** Asks for [tier]; returns whether it is now available. */
    suspend fun request(tier: Tier): Boolean = when (tier) {
        Tier.SHIZUKU -> shizuku.requestAccess()
        Tier.ROOT -> root.requestAccess()
        else -> false
    }
}
