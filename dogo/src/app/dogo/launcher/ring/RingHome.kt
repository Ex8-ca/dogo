/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ring

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.util.firstBlocking
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.statemanager.StateManager
import com.android.launcher3.views.BaseDragLayer
import org.json.JSONObject

/**
 * Puts the ring home screen on top of the workspace and takes it away again.
 *
 * The workspace is not replaced: it is kept alive but invisible, so widgets,
 * shortcuts and the database stay intact and turning the ring home off is
 * instant and lossless.
 */
object RingHome {
    private const val PREFS = "dogo_ring_home"
    private const val KEY_USAGE = "usage"

    private var view: RingHomeView? = null
    private var owner: Launcher? = null
    private var stateListener: StateManager.StateListener<LauncherState>? = null
    private var appsListener: AllAppsStore.OnUpdateListener? = null

    fun isEnabled(context: Context): Boolean = config(context).home

    internal fun config(context: Context): RingConfig = DogoRing.config(context)

    private const val TAG = "RingHome"

    /** Called from Launcher.setupViews(); does nothing unless the setting is on. */
    fun attachIfEnabled(launcher: Launcher) {
        val cfg = config(launcher)
        android.util.Log.i(TAG, "attachIfEnabled home=${cfg.home} hand=${cfg.hand} apps=${launcher.appsView.appsStore.apps.size}")
        if (view?.parent != null && owner === launcher) {
            sync(launcher)
            return
        }
        detach(owner)
        val config = config(launcher)
        if (!config.home) return
        val (home, all, icons) = installed(launcher)
        android.util.Log.i(TAG, "installed ${all.size} apps, ${icons.size} icons")
        val v = RingHomeView(launcher)
        v.setData(home, all, icons, config.hand)
        val dragLayer = launcher.dragLayer
        val lp = BaseDragLayer.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        lp.ignoreInsets = true
        // Right above the workspace and below everything that can cover it
        // (hotseat, scrim, all-apps), so the drawer slides over the rings.
        val workspaceIndex = (0 until dragLayer.childCount).indexOfFirst { dragLayer.getChildAt(it) === launcher.workspace }
        val at = if (workspaceIndex >= 0) workspaceIndex + 1 else dragLayer.childCount
        dragLayer.addView(v, at, lp)
        view = v
        owner = launcher
        applyWorkspaceHidden(launcher)

        val listener = object : StateManager.StateListener<LauncherState> {
            override fun onStateTransitionStart(toState: LauncherState) {
                applyWorkspaceHidden(launcher)
            }

            override fun onStateTransitionComplete(finalState: LauncherState) {
                applyWorkspaceHidden(launcher)
            }
        }
        launcher.stateManager.addStateListener(listener)
        stateListener = listener
        // The launcher's app list is still empty while setupViews() runs; fill
        // the rings the moment the model has it (and on every later change).
        val appListener = AllAppsStore.OnUpdateListener { sync(launcher) }
        launcher.appsView.appsStore.addUpdateListener(appListener)
        appsListener = appListener
        launcher.dragLayer.postDelayed({ runCatching { applyWorkspaceHidden(launcher) } }, 500)
        android.util.Log.i(TAG, "attached at index $at of ${dragLayer.childCount}")
        // The standard drawer would otherwise be reachable behind the rings.
        v.announceForAccessibility("Ring home on")
    }

    /** Re-reads the setting and the app list. Call from onResume. */
    fun sync(launcher: Launcher) {
        val config = config(launcher)
        if (!config.home) {
            detach(launcher)
            return
        }
        val v = view
        if (v == null || owner !== launcher || v.parent == null) {
            attachIfEnabled(launcher)
            return
        }
        v.setHand(config.hand)
        val (home, all, icons) = installed(launcher)
        android.util.Log.i(TAG, "sync hand=${config.hand} apps=${all.size} icons=${icons.size}")
        v.refreshApps(home, all, icons)
        applyWorkspaceHidden(launcher)
    }

    fun detach(launcher: Launcher?) {
        val v = view ?: return
        val l = launcher ?: owner
        stateListener?.let { l?.stateManager?.removeStateListener(it) }
        stateListener = null
        appsListener?.let { l?.appsView?.appsStore?.removeUpdateListener(it) }
        appsListener = null
        (v.parent as? ViewGroup)?.removeView(v)
        view = null
        owner = null
        if (l != null) restoreWorkspace(l)
    }

    /** Back closes the drawer first, so Back never leaves the launcher by accident. */
    fun onBack(launcher: Launcher): Boolean = view?.closeDrawer() ?: false

    fun showDrawer() {
        view?.showDrawer()
    }

    /** Records a launch so the most used apps get the inner (easiest) ring. */
    fun bump(context: Context, pkg: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val usage = runCatching { JSONObject(prefs.getString(KEY_USAGE, "{}") ?: "{}") }.getOrDefault(JSONObject())
        usage.put(pkg, usage.optInt(pkg, 0) + 1)
        prefs.edit().putString(KEY_USAGE, usage.toString()).apply()
    }

    fun usage(context: Context): Map<String, Int> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USAGE, null) ?: return emptyMap()
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optInt(it, 0) }
    }

    /**
     * Installed apps in the order the home screen wants them: most launched
     * first, then alphabetical, so the inner ring is the one you reach for.
     */
    internal fun installed(launcher: Launcher): Triple<List<HomeApp>, List<HomeApp>, Map<String, Bitmap>> {
        val hidden = runCatching { PreferenceManager2.getInstance(launcher).hiddenApps.get().firstBlocking() }.getOrDefault(emptySet())
        val pm = launcher.packageManager
        val icons = HashMap<String, Bitmap>()
        val entries = launcher.appsView.appsStore.apps
            .filter { it.componentName != null && it.toComponentKey().toString() !in hidden }
            .distinctBy { it.componentName!!.packageName }
            .mapNotNull { app ->
                val pkg = app.componentName!!.packageName
                app.bitmap?.icon?.let { icons[pkg] = it }
                val label = app.title?.toString()?.takeIf { it.isNotBlank() } ?: runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
                }.getOrDefault(pkg)
                HomeApp(pkg, label)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        val usage = usage(launcher)
        val home = entries.sortedWith(
            compareByDescending<HomeApp> { usage[it.pkg] ?: 0 }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
        )
        return Triple(home, entries, icons)
    }

    // ---- workspace visibility ----------------------------------------------

    private fun applyWorkspaceHidden(launcher: Launcher) {
        listOfNotNull(launcher.workspace, launcher.findViewById<View?>(R.id.hotseat), launcher.findViewById<View?>(R.id.page_indicator))
            .forEach {
                it.visibility = View.INVISIBLE
                it.alpha = 0f
            }
    }

    private fun restoreWorkspace(launcher: Launcher) {
        listOfNotNull(launcher.workspace, launcher.findViewById<View?>(R.id.hotseat), launcher.findViewById<View?>(R.id.page_indicator))
            .forEach {
                it.visibility = View.VISIBLE
                it.alpha = 1f
            }
    }
}
