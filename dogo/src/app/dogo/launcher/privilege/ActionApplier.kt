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
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Process
import android.provider.Settings
import androidx.core.graphics.toColorInt
import app.dogo.launcher.agent.Action
import app.dogo.launcher.agent.HomeStyle
import app.dogo.launcher.agent.WidgetSize
import app.dogo.launcher.plugins.PluginActionRunner
import app.dogo.launcher.ring.DogoRing
import app.dogo.launcher.ring.RingStyle
import app.dogo.launcher.tiles.TileParser
import app.dogo.launcher.tiles.TileStore
import app.lawnchair.hotseat.HotseatMode
import app.lawnchair.icons.shape.IconShape
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.theme.color.ColorOption
import app.lawnchair.ui.preferences.components.ThemeChoice
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherAppState
import com.android.launcher3.model.ItemInstallQueue
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.widget.WidgetManagerHelper
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
            fun parse(c: String) = runCatching { c.trim().toColorInt() }.getOrElse { error("\"$c\" isn't a colour like #1E1E1E.") }
            val top = parse(action.color)
            val bottom = action.color2?.let(::parse)
            withContext(Dispatchers.IO) {
                val metrics = context.resources.displayMetrics
                val bitmap = if (bottom == null) {
                    Bitmap.createBitmap(WALLPAPER_SIZE, WALLPAPER_SIZE, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(top) }
                } else {
                    // A gradient needs the screen's shape, or Android stretches it unevenly.
                    val w = metrics.widthPixels / 4
                    val h = metrics.heightPixels / 4
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        val paint = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP) }
                        Canvas(it).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
                    }
                }
                WallpaperManager.getInstance(context).setBitmap(bitmap)
            }
            if (bottom == null) "Wallpaper set to ${action.color}." else "Wallpaper set to a ${action.color} to ${action.color2} gradient."
        }

        is Action.AddWidget -> addWidget(action)

        is Action.StyleHome -> styleHome(action.style.clamped())

        is Action.SetDock -> setDock(action)

        is Action.WriteTiles -> writeTiles(action)

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
            RingStyle.fromWire(it) ?: error("Unknown ring style \"$it\". Use glow, radar, fire, electric or minimal.")
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

    /** The widget [spec] names: "package/class", "package/.Class", or a package (its first widget). */
    private fun findWidget(spec: String): android.appwidget.AppWidgetProviderInfo {
        val all = WidgetManagerHelper(context).getAllProviders(null)
        val s = spec.trim()
        val pkg = s.substringBefore('/')
        val cls = s.substringAfter('/', "").let { if (it.startsWith(".")) pkg + it else it }
        return all.firstOrNull { cls.isNotEmpty() && it.provider.packageName == pkg && it.provider.className == cls }
            ?: all.firstOrNull { it.provider.packageName == pkg && cls.isNotEmpty() && it.provider.className.endsWith(cls.substringAfterLast('.')) }
            ?: all.firstOrNull { cls.isEmpty() && it.provider.packageName == pkg }
            ?: error(
                "No widget called \"$spec\" on this phone." +
                    if (all.none { it.provider.packageName == pkg }) " ${labelFor(pkg)} has no widgets." else "",
            )
    }

    private suspend fun addWidget(action: Action.AddWidget): String {
        val provider = findWidget(action.provider)
        val size = WidgetSize.parse(action.size)
        val model = LauncherAppState.getInstance(context).model
        check(model.isModelLoaded()) { NEEDS_HOME_SCREEN_WIDGET }
        val done = CompletableDeferred<Result<WidgetPlacement>>()
        withContext(Dispatchers.Main) {
            model.enqueueModelUpdateTask(AddWidgetTask(provider, size?.columns, size?.rows, action.page) { done.complete(it) })
        }
        val placed = (withTimeoutOrNull(FOLDER_TIMEOUT_MS) { done.await() } ?: error(NEEDS_HOME_SCREEN_WIDGET)).getOrThrow()
        if (placed.alreadyThere) return "The ${placed.label} widget is already on page ${placed.page + 1}."
        return buildString {
            append("Added the ${placed.label} widget (${placed.columns}×${placed.rows}) to page ${placed.page + 1}.")
            if (!placed.bound) {
                append(" Tap it once and allow Dogo to create widgets; after that, widgets go straight on.")
            } else if (placed.needsSetup) {
                append(" Tap it to finish setting it up.")
            }
        }
    }

    /**
     * Applies a tile file. The text is parsed and checked line by line; lines that fail are
     * reported, the rest still apply. Only widgets Dogo placed for tiles earlier are ever moved,
     * resized or removed. If the grid must change, that is done first and the plan is made again
     * against the reloaded home screen.
     */
    private suspend fun writeTiles(action: Action.WriteTiles): String {
        val config = TileParser.parse(action.text)
        check(!config.isEmpty) {
            "That tile file has no usable page." + config.issues.firstOrNull()?.let { " ${it.message}" }.orEmpty()
        }
        val model = LauncherAppState.getInstance(context).model
        check(model.isModelLoaded()) { NEEDS_HOME_SCREEN_WIDGET }
        val store = TileStore(context)
        val state = store.load()
        val providers = WidgetManagerHelper(context).getAllProviders(null)

        suspend fun runOnce(allowGrid: Boolean): TileApplyResult {
            val done = CompletableDeferred<Result<TileApplyResult>>()
            withContext(Dispatchers.Main) {
                model.enqueueModelUpdateTask(ApplyTilesTask(config, state.widgetIds, providers, allowGrid) { done.complete(it) })
            }
            return (withTimeoutOrNull(FOLDER_TIMEOUT_MS) { done.await() } ?: error(NEEDS_HOME_SCREEN_WIDGET)).getOrThrow()
        }

        var result = runOnce(allowGrid = false)
        result.needsGrid?.let { (columns, rows) ->
            styleHome(HomeStyle(columns = columns, rows = rows))
            // Changing the grid reloads the home screen; wait for it before planning again.
            check(
                withTimeoutOrNull(GRID_RELOAD_MS) {
                    while (!model.isModelLoaded()) kotlinx.coroutines.delay(250)
                    true
                } == true,
            ) {
                "The home screen did not finish reloading after the grid changed. Open Dogo's home screen and try again."
            }
            result = runOnce(allowGrid = true)
        }
        store.save(TileStore.State(result.widgetIds, action.text, state.file))

        return buildString {
            append("Tiles: ${result.added} added, ${result.moved} moved or resized, ${result.removed} removed")
            if (result.refused > 0) append(", ${result.refused} not placed")
            append('.')
            if (result.needsSetup > 0) append(" ${result.needsSetup} widget${if (result.needsSetup == 1) " needs" else "s need"} a tap on the home screen to finish setup.")
            val notes = result.lines.filter { it.startsWith("Not placed") || it.startsWith("Grid") }
            if (notes.isNotEmpty()) append(' ').append(notes.joinToString(" "))
            if (config.issues.isNotEmpty()) append(" Lines ignored: ${config.issues.take(3).joinToString("; ") { it.message }}.")
        }
    }

    private suspend fun setDock(action: Action.SetDock): String {
        val user = Process.myUserHandle()
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val missing = mutableListOf<String>()
        val apps = action.apps.mapNotNull { pkg ->
            launcherApps.getActivityList(pkg, user).firstOrNull()?.let { AppInfo(context, it, user) }.also { if (it == null) missing += pkg }
        }
        check(apps.isNotEmpty()) { "None of those apps are installed: ${action.apps.joinToString()}." }
        val model = LauncherAppState.getInstance(context).model
        check(model.isModelLoaded()) { NEEDS_HOME_SCREEN }
        val slots = InvariantDeviceProfile.INSTANCE.get(context).numShownHotseatIcons
        val done = CompletableDeferred<Result<Int>>()
        withContext(Dispatchers.Main) { model.enqueueModelUpdateTask(SetDockTask(apps) { done.complete(it) }) }
        val n = (withTimeoutOrNull(FOLDER_TIMEOUT_MS) { done.await() } ?: error(NEEDS_HOME_SCREEN)).getOrThrow()
        return buildString {
            append("The dock now has ${apps.take(n).joinToString { it.title.toString() }}.")
            if (apps.size > slots) append(" The dock has $slots slots; the rest were left out.")
            if (missing.isNotEmpty()) append(" Not installed: ${missing.joinToString()}.")
        }
    }

    private suspend fun styleHome(style: HomeStyle): String {
        require(!style.isEmpty) { "Nothing to change." }
        val prefs = PreferenceManager.getInstance(context)
        val prefs2 = PreferenceManager2.getInstance(context)
        style.iconPack?.let { pack ->
            if (pack.isNotEmpty()) {
                runCatching { context.packageManager.getPackageInfo(pack, 0) }.getOrElse { error("The icon pack $pack isn't installed.") }
            }
        }
        withContext(Dispatchers.Main) {
            if (style.columns != null || style.rows != null || style.dockColumns != null) {
                prefs.batchEdit {
                    style.columns?.let { prefs.workspaceColumns.set(it) }
                    style.rows?.let { prefs.workspaceRows.set(it) }
                    style.dockColumns?.let { prefs.hotseatColumns.set(it) }
                }
                InvariantDeviceProfile.INSTANCE.get(context).onPreferencesChanged(context)
            }
            style.iconPack?.let { prefs.iconPackPackage.set(it) }
        }
        style.iconSize?.let { prefs2.homeIconSizeFactor.set(it) }
        style.labels?.let { prefs2.showIconLabelsOnHomeScreen.set(it) }
        style.iconShape?.let { name -> IconShape.fromString(name, context)?.let { prefs2.iconShape.set(it) } }
        style.dock?.let { prefs2.isHotseatEnabled.set(it) }
        style.searchBar?.let { on -> prefs2.hotseatMode.set(if (on) HotseatMode.fromString("lawnchair") else HotseatMode.fromString("disabled")) }
        style.glance?.let { prefs2.enableSmartspace.set(it) }
        style.accent?.let { prefs2.accentColor.set(ColorOption.CustomColor(it.toColorInt())) }
        style.notificationCounts?.let { prefs2.showNotificationCount.set(it) }
        return "Home screen updated: ${style.describe()}."
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
        const val GRID_RELOAD_MS = 20_000L
        const val NEEDS_HOME_SCREEN_WIDGET =
            "Widgets go on Dogo's home screen. Set Dogo as your home app (or open it once), then try again."
        const val NEEDS_HOME_SCREEN =
            "Folders are made on Dogo's home screen. Set Dogo as your home app (or open it once), then try again."
    }
}
