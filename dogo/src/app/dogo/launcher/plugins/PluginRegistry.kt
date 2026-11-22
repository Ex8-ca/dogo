/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.plugins

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/**
 * Finds installed Dogo plugins.
 *
 * A plugin is an ordinary APK, installable from F-Droid or a Dogo repo, with:
 *  - `assets/dogo-plugin.json` (a [PluginManifest]), and
 *  - every entry point a component that answers [PluginManifest.ACTION_PLUGIN].
 *
 * Nothing found here is trusted: a plugin is only used after the user enables
 * it in Dogo Options, having seen its capabilities. [PluginStore] keeps those
 * choices, keyed to the plugin's version so an update that asks for more
 * capabilities must be approved again.
 */
class PluginRegistry(context: Context) {
    private val context = context.applicationContext

    data class Installed(
        val packageName: String,
        val manifest: PluginManifest,
        val label: String,
    )

    data class Broken(val packageName: String, val reason: String)

    data class Scan(val plugins: List<Installed>, val broken: List<Broken>)

    fun scan(): Scan {
        val pm = context.packageManager
        val intent = Intent(PluginManifest.ACTION_PLUGIN)
        val components: List<ResolveInfo> = buildList {
            addAll(pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0)))
            addAll(pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0)))
            addAll(pm.queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(0)))
        }
        val byPackage = components.groupBy { info ->
            (info.serviceInfo ?: info.activityInfo).packageName
        }
        val plugins = mutableListOf<Installed>()
        val broken = mutableListOf<Broken>()
        byPackage.forEach { (pkg, infos) ->
            if (pkg == context.packageName) return@forEach
            val manifestJson = runCatching {
                pm.getResourcesForApplication(pkg).assets.open(PluginManifest.ASSET_NAME).bufferedReader().use { it.readText() }
            }.getOrElse {
                broken += Broken(pkg, "No assets/${PluginManifest.ASSET_NAME}.")
                return@forEach
            }
            PluginManifest.parse(manifestJson).fold(
                onSuccess = { manifest ->
                    val declared = infos.map { (it.serviceInfo ?: it.activityInfo).name }.toSet()
                    val missing = manifest.entryPoints.values.map { qualify(pkg, it) }.filterNot { it in declared }
                    if (missing.isNotEmpty()) {
                        broken += Broken(pkg, "Entry points not exported for ${PluginManifest.ACTION_PLUGIN}: ${missing.joinToString()}")
                    } else {
                        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString() }
                            .getOrDefault(manifest.name)
                        plugins += Installed(pkg, manifest, label)
                    }
                },
                onFailure = { broken += Broken(pkg, it.message ?: "Invalid manifest.") },
            )
        }
        return Scan(plugins.sortedBy { it.manifest.name.lowercase() }, broken)
    }

    companion object {
        /** ".Foo" and "Foo" are relative to the package, like in an Android manifest. */
        fun qualify(pkg: String, component: String): String = when {
            component.startsWith(".") -> pkg + component
            !component.contains('.') -> "$pkg.$component"
            else -> component
        }
    }
}

/** Which plugins the user enabled, and the capabilities they approved. */
class PluginStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("dogo_plugins", Context.MODE_PRIVATE)

    /** Enabled only if the approval matches this exact version's capabilities. */
    fun isEnabled(plugin: PluginRegistry.Installed): Boolean {
        val stored = prefs.getString(plugin.manifest.id, null) ?: return false
        return stored == approvalKey(plugin)
    }

    fun setEnabled(plugin: PluginRegistry.Installed, enabled: Boolean) {
        prefs.edit().apply {
            if (enabled) putString(plugin.manifest.id, approvalKey(plugin)) else remove(plugin.manifest.id)
        }.apply()
    }

    /** True when it was enabled before but has since asked for something new. */
    fun needsReapproval(plugin: PluginRegistry.Installed): Boolean {
        val stored = prefs.getString(plugin.manifest.id, null) ?: return false
        return stored != approvalKey(plugin)
    }

    companion object {
        fun approvalKey(plugin: PluginRegistry.Installed): String = plugin.packageName + "|" + plugin.manifest.capabilities.map { it.wire }.sorted().joinToString(",") +
            "|" + plugin.manifest.actions.map { it.name }.sorted().joinToString(",")
    }
}
