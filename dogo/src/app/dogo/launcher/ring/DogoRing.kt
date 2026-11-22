/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ring

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.view.MotionEvent
import android.view.ViewGroup
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.util.firstBlocking
import com.android.launcher3.BuildConfig
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.views.BaseDragLayer

/** Opens the Dogo Ring and stores its settings. */
object DogoRing {
    private const val PREFS = "dogo_ring"
    private const val KEY = "config"

    /** The ring is a Dogo feature; the upstream Lawnchair channels never see it. */
    val isDogoChannel: Boolean get() = BuildConfig.FLAVOR_channel == "dogo"

    fun config(context: Context): RingConfig = RingConfig.fromJson(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(context: Context, config: RingConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, config.toJson()).apply()
    }

    fun update(context: Context, change: (RingConfig) -> RingConfig) = save(context, change(config(context)))

    /** Hold time if the ring should open on this touch, or null if it is off. */
    fun holdMsOrNull(context: Context): Long? = if (!isDogoChannel) null else config(context).takeIf { it.enabled }?.holdMs

    /** Installed apps as the drawer shows them (hidden apps left out), with icons. */
    internal fun installedApps(launcher: Launcher): Pair<List<RingBuilder.AppEntry>, Map<String, Bitmap>> {
        val hidden = runCatching { PreferenceManager2.getInstance(launcher).hiddenApps.get().firstBlocking() }.getOrDefault(emptySet())
        val pm = launcher.packageManager
        val icons = HashMap<String, Bitmap>()
        val entries = launcher.appsView.appsStore.apps
            .filter { it.componentName != null && it.toComponentKey().toString() !in hidden }
            .distinctBy { it.componentName!!.packageName }
            .map { app ->
                val pkg = app.componentName!!.packageName
                app.bitmap?.icon?.let { icons[pkg] = it }
                val category = runCatching { pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).category }.getOrDefault(-1)
                RingBuilder.AppEntry(pkg, app.title?.toString() ?: pkg, category)
            }
        return entries to icons
    }

    /**
     * Opens the ring under the finger at ([rawX], [rawY]). The caller keeps
     * feeding that finger's events to the returned view.
     */
    fun open(launcher: Launcher, rawX: Float, rawY: Float): DogoRingView? {
        val config = config(launcher)
        val (apps, icons) = installedApps(launcher)
        if (apps.isEmpty()) return null
        val groups = config.groups ?: RingBuilder.automaticGroups(apps, launcher.packageName.substringBefore(".debug"))
        val root = RingBuilder.build(groups, apps)

        val dragLayer = launcher.dragLayer
        val location = IntArray(2)
        dragLayer.getLocationOnScreen(location)
        val view = DogoRingView(launcher, root, config.style, icons, location[0].toFloat(), location[1].toFloat()) { command, x, y ->
            runCommand(launcher, command, x, y)
        }
        val lp = BaseDragLayer.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        lp.ignoreInsets = true
        dragLayer.addView(view, lp)
        view.start(rawX, rawY)
        return view
    }

    private fun runCommand(launcher: Launcher, command: RingCommand, x: Float, y: Float) {
        when (command) {
            RingCommand.ASK_DOGO -> openSettingsRoute(launcher, "dogo-setup")
            RingCommand.DOGO_OPTIONS -> openSettingsRoute(launcher, "dogo-options")
            RingCommand.APP_DRAWER -> launcher.stateManager.goToState(LauncherState.ALL_APPS)
            RingCommand.HOME_OPTIONS -> launcher.showDefaultOptions(x, y)
        }
    }

    private fun openSettingsRoute(context: Context, route: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("lawnchair://settings/$route"))
            .setComponent(ComponentName(context, "app.lawnchair.ui.preferences.PreferenceActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** A synthetic CANCEL so the workspace stops scrolling once the ring owns the finger. */
    fun cancelEvent(): MotionEvent {
        val now = android.os.SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
    }
}
