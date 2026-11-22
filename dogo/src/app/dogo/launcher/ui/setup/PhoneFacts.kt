/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.setup

import android.content.Context
import android.content.Intent
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.widget.LauncherAppWidgetProviderInfo
import com.android.launcher3.widget.WidgetManagerHelper

/**
 * What the agent is told about the home screen, so it can add widgets and
 * restyle the layout without guessing. Each lookup fails soft: a missing
 * fact means a shorter prompt, never a broken chat.
 */
object PhoneFacts {

    /** "package/class = Label (4x2)" for every widget the phone offers. */
    fun widgets(context: Context): List<String> = runCatching {
        val pm = context.packageManager
        WidgetManagerHelper(context).getAllProviders(null)
            .filter { it.provider.packageName != context.packageName }
            .map { info ->
                val size = runCatching { LauncherAppWidgetProviderInfo.fromProviderInfo(context, info).let { "${it.spanX}x${it.spanY}" } }.getOrDefault("?")
                val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString() }.getOrDefault(info.provider.packageName)
                val label = info.loadLabel(pm)?.toString().orEmpty().ifBlank { app }
                "${info.provider.packageName}/${info.provider.className} = $app: $label ($size)"
            }
            .sorted()
    }.getOrDefault(emptyList())

    fun home(context: Context): List<String> = runCatching {
        val idp = InvariantDeviceProfile.INSTANCE.get(context)
        val prefs = PreferenceManager.getInstance(context)
        val prefs2 = PreferenceManager2.getInstance(context)
        listOf(
            "Grid: ${idp.numColumns} columns x ${idp.numRows} rows; dock has ${idp.numShownHotseatIcons} slots.",
            "Icon size ${(prefs2.homeIconSizeFactor.firstCached() * 100).toInt()}%, labels " +
                (if (prefs2.showIconLabelsOnHomeScreen.firstCached()) "on" else "off") +
                ", shape ${prefs2.iconShape.firstCached()}.",
            "Dock " + (if (prefs2.isHotseatEnabled.firstCached()) "shown" else "hidden") +
                ", search bar ${prefs2.hotseatMode.firstCached()}, date line " +
                (if (prefs2.enableSmartspace.firstCached()) "on" else "off") + ".",
            "Icon pack: ${prefs.iconPackPackage.get().ifEmpty { "system icons" }}.",
        ) + placedWidgets(context)
    }.getOrDefault(emptyList())

    /** Widgets already on the home screen, so the agent doesn't add them twice. */
    private fun placedWidgets(context: Context): List<String> = runCatching {
        val model = com.android.launcher3.LauncherAppState.getInstance(context).model
        if (!model.isModelLoaded()) return emptyList()
        val placed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val done = java.util.concurrent.CountDownLatch(1)
        model.enqueueModelUpdateTask { _, data, _ ->
            synchronized(data) {
                data.itemsIdMap.forEach {
                    if (it is com.android.launcher3.model.data.LauncherAppWidgetInfo && it.container == com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP) {
                        placed += "${it.providerName.flattenToShortString()} (${it.spanX}x${it.spanY})"
                    }
                }
            }
            done.countDown()
        }
        // Called off the main thread; the model thread answers in milliseconds.
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        if (placed.isEmpty()) emptyList() else listOf("Widgets already on the home screen: ${placed.joinToString()}.")
    }.getOrDefault(emptyList())

    private val ICON_PACK_INTENTS = listOf(
        Intent("com.novalauncher.THEME"),
        Intent("org.adw.launcher.icons.ACTION_PICK_ICON"),
        Intent("com.dlto.atom.launcher.THEME"),
        Intent("android.intent.action.MAIN").addCategory("com.anddoes.launcher.THEME"),
    )

    fun iconPacks(context: Context): List<SetupChatAgent.InstalledApp> = runCatching {
        val pm = context.packageManager
        ICON_PACK_INTENTS.flatMap { pm.queryIntentActivities(it, 0) }
            .distinctBy { it.activityInfo.packageName }
            .map { SetupChatAgent.InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
    }.getOrDefault(emptyList())
}
