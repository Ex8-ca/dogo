/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.privilege

import android.app.WallpaperManager
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.provider.Settings
import androidx.core.graphics.toColorInt
import app.dogo.launcher.agent.Action
import app.dogo.launcher.plugins.PluginActionRunner
import app.dogo.launcher.ring.DogoRing
import app.dogo.launcher.ring.RingStyle
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.ui.preferences.components.ThemeChoice
import com.android.launcher3.LauncherAppState
import com.android.launcher3.model.ItemInstallQueue
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.util.ComponentKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Carries out an approved [Action].
 *
 * Every action goes through [PrivilegePlanner] first, so the route is the
 * least powerful one that works, and a blocked action comes back as a
 * failure that says how to unblock it rather than silently doing nothing.
 */
class ActionApplier(
    context: Context,
    private val access: SystemAccess = SystemAccess(context),
) {
    private val context = context.applicationContext

    data class Outcome(val action: Action, val ok: Boolean, val message: String, val tier: Tier?)

    suspend fun apply(action: Action): Outcome {
        val plan = PrivilegePlanner.plan(action, access.availability(), context.packageName)
        return try {
            when (plan) {
                is PrivilegePlanner.Plan.Blocked -> Outcome(action, false, plan.hint, null)

                PrivilegePlanner.Plan.InLauncher -> {
                    val message = applyInLauncher(action)
                    Outcome(action, true, message, Tier.NONE)
                }

                is PrivilegePlanner.Plan.ViaPlugin -> {
                    action as Action.Plugin
                    val plugin = access.enabledPlugins().first { it.manifest.id == plan.pluginId }
                    val message = PluginActionRunner(context).run(plugin, action.name, JSONObject(action.paramsJson)).getOrThrow()
                    Outcome(action, true, message, Tier.NONE)
                }

                is PrivilegePlanner.Plan.WithPermission -> {
                    val message = applyWithPermission(action)
                    Outcome(action, true, message, Tier.ADB_GRANT)
                }

                is PrivilegePlanner.Plan.ViaShell -> {
                    access.runner(plan.tier).run(plan.command).getOrThrow()
                    Outcome(action, true, "Done (via ${plan.tier.title}).", plan.tier)
                }
            }
        } catch (e: Exception) {
            Outcome(action, false, e.message ?: "Couldn't apply this change.", null)
        }
    }

    private suspend fun applyInLauncher(action: Action): String = when (action) {
        is Action.SetTheme -> {
            val choice = when (action.theme.trim().lowercase()) {
                "dark", "black", "night" -> ThemeChoice.DARK
                "light", "white", "day" -> ThemeChoice.LIGHT
                "system", "auto", "default" -> ThemeChoice.SYSTEM
                else -> error("Unknown theme \"${action.theme}\". Use light, dark or system.")
            }
            withContext(Dispatchers.Main) { PreferenceManager.getInstance(context).launcherTheme.set(choice) }
            "Dogo's theme is now $choice."
        }

        is Action.HideApp -> {
            val keys = componentKeysFor(action.pkg)
            val prefs = PreferenceManager2.getInstance(context)
            val hidden = prefs.hiddenApps.get().first()
            prefs.hiddenApps.set(hidden + keys.map { it.toString() })
            "Hid ${labelFor(action.pkg)} from the app drawer."
        }

        is Action.AddToHome -> {
            componentKeysFor(action.pkg) // validates the package
            // The queue is persisted and flushed whenever Dogo's home screen
            // runs, so this survives Dogo not being open right now.
            ItemInstallQueue.INSTANCE.get(context).queueItem(action.pkg, Process.myUserHandle())
            if (LauncherAppState.getInstance(context).model.isModelLoaded()) {
                "Added ${labelFor(action.pkg)} to the home screen."
            } else {
                "Queued ${labelFor(action.pkg)}; it appears next time Dogo's home screen opens."
            }
        }

        is Action.CreateFolder -> createFolder(action)

        is Action.ArrangeRing -> arrangeRing(action)

        is Action.SetWallpaper -> {
            val color = runCatching { action.color.trim().toColorInt() }
                .getOrElse { error("\"${action.color}\" isn't a colour like #1E1E1E.") }
            withContext(Dispatchers.IO) {
                val bitmap = Bitmap.createBitmap(WALLPAPER_SIZE, WALLPAPER_SIZE, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(color)
                WallpaperManager.getInstance(context).setBitmap(bitmap)
            }
            "Wallpaper set to ${action.color}."
        }

        else -> error("Not a launcher action")
    }

    private fun applyWithPermission(action: Action): String = when (action) {
        is Action.SetAnimationScale -> {
            val scale = ActionRequirements.clampAnimationScale(action.scale)
            ActionRequirements.ANIMATION_SCALE_KEYS.forEach {
                check(Settings.Global.putFloat(context.contentResolver, it, scale)) { "Android refused to change $it." }
            }
            "Animations set to ${ActionRequirements.formatScale(scale)}x."
        }

        else -> error("No permission route for this action")
    }

    private suspend fun createFolder(action: Action.CreateFolder): String {
        require(action.name.isNotBlank()) { "The folder needs a name." }
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val user = Process.myUserHandle()
        val missing = mutableListOf<String>()
        val folder = FolderInfo().apply { title = action.name }
        action.apps.forEach { pkg ->
            val activity = launcherApps.getActivityList(pkg, user).firstOrNull()
            if (activity == null) {
                missing += pkg
            } else {
                folder.add(AppInfo(context, activity, user).makeWorkspaceItem(context))
            }
        }
        check(folder.getContents().isNotEmpty()) {
            "None of those apps are installed: ${action.apps.joinToString()}."
        }
        val model = LauncherAppState.getInstance(context).model
        // The model only loads once Dogo's home screen has run. Before that the
        // task would be dropped without a word, so say so instead.
        check(model.isModelLoaded()) { NEEDS_HOME_SCREEN }
        val done = CompletableDeferred<Result<Int>>()
        withContext(Dispatchers.Main) {
            model.enqueueModelUpdateTask(AddFolderTask(folder) { done.complete(it) })
        }
        (withTimeoutOrNull(FOLDER_TIMEOUT_MS) { done.await() } ?: error(NEEDS_HOME_SCREEN)).getOrThrow()
        val added = folder.getContents().size
        return "Created folder \"${action.name}\" with $added app${if (added == 1) "" else "s"}." +
            if (missing.isNotEmpty()) " Not installed: ${missing.joinToString()}." else ""
    }

    private fun arrangeRing(action: Action.ArrangeRing): String {
        val style = action.style?.let {
            RingStyle.fromWire(it) ?: error("Unknown ring style \"$it\". Use glow, radar or minimal.")
        }
        val user = Process.myUserHandle()
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val missing = mutableListOf<String>()
        val groups = action.groups.map { g ->
            g.copy(
                apps = g.apps.filter { pkg ->
                    launcherApps.getActivityList(pkg, user).isNotEmpty().also { if (!it) missing += pkg }
                },
            )
        }.filter { it.apps.isNotEmpty() }
        check(action.groups.isEmpty() || groups.isNotEmpty()) { "None of those apps are installed." }
        DogoRing.update(context) { old ->
            old.copy(
                enabled = true,
                style = style ?: old.style,
                groups = if (action.groups.isEmpty()) old.groups else groups,
            )
        }
        return buildString {
            if (groups.isNotEmpty()) append("Arranged the ring into ${groups.size} groups: ${groups.joinToString { it.name }}.")
            if (style != null) append(if (isEmpty()) "" else " ").append("Ring style is now ${style.title}.")
            if (missing.isNotEmpty()) append(" Not installed: ${missing.joinToString()}.")
            append(" Touch and hold the home screen to try it.")
        }
    }

    private fun componentKeysFor(pkg: String): List<ComponentKey> {
        val user = Process.myUserHandle()
        val activities = context.getSystemService(LauncherApps::class.java).getActivityList(pkg, user)
        check(activities.isNotEmpty()) { "$pkg isn't installed (or has no launcher icon)." }
        return activities.map { ComponentKey(it.componentName, user) }
    }

    private fun labelFor(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
    }.getOrDefault(pkg)

    private companion object {
        const val WALLPAPER_SIZE = 64
        const val FOLDER_TIMEOUT_MS = 10_000L
        const val NEEDS_HOME_SCREEN =
            "Folders are made on Dogo's home screen. Set Dogo as your home app (or open it once), then try again."
    }
}
