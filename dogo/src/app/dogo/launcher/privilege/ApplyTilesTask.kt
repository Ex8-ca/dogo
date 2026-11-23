/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.privilege

import android.appwidget.AppWidgetProviderInfo
import android.os.Process
import app.dogo.launcher.tiles.CellRect
import app.dogo.launcher.tiles.PageState
import app.dogo.launcher.tiles.PlacedTile
import app.dogo.launcher.tiles.TileConfig
import app.dogo.launcher.tiles.TilePlanner
import app.dogo.launcher.tiles.TileSpec
import app.dogo.launcher.tiles.TileStep
import app.dogo.launcher.tiles.WidgetLimits
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherModel
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.Workspace
import com.android.launcher3.model.AllAppsList
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.model.ModelTaskController
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.widget.LauncherAppWidgetProviderInfo
import com.android.launcher3.widget.LauncherWidgetHolder
import com.android.launcher3.widget.WidgetManagerHelper
import com.android.launcher3.widget.util.WidgetSizes

/** What [ApplyTilesTask] did, for the chat and for [app.dogo.launcher.tiles.TileStore]. */
data class TileApplyResult(
    val lines: List<String>,
    val added: Int,
    val moved: Int,
    val removed: Int,
    val refused: Int,
    val needsSetup: Int,
    val widgetIds: Set<Int>,
    /** Set instead of doing anything when the launcher grid has to change first. */
    val needsGrid: Pair<Int, Int>? = null,
)

/**
 * Turns a tile file into real widgets on the home screen. It reads the launcher's pages, asks
 * [TilePlanner] what to do, then writes the changes straight to the launcher database (like
 * [AddWidgetTask]) so they survive a reload.
 *
 * Only widgets whose ids are in [placedIds] are ever moved, resized or removed; the user's own
 * icons, folders and widgets are treated as occupied cells and never touched. When the grid has
 * to change first and [allowGrid] is false, nothing is changed and [TileApplyResult.needsGrid]
 * says what the grid should become.
 */
class ApplyTilesTask(
    private val config: TileConfig,
    private val placedIds: Set<Int>,
    private val providers: List<AppWidgetProviderInfo>,
    private val allowGrid: Boolean,
    private val onDone: (Result<TileApplyResult>) -> Unit,
) : LauncherModel.ModelUpdateTask {

    override fun execute(taskController: ModelTaskController, dataModel: BgDataModel, apps: AllAppsList) {
        onDone(runCatching { run(taskController, dataModel) })
    }

    private fun resolve(spec: String): AppWidgetProviderInfo? {
        val pkg = spec.substringBefore('/')
        val cls = spec.substringAfter('/', "").let { if (it.startsWith(".")) pkg + it else it }
        if (cls.isEmpty()) return null
        return providers.firstOrNull { it.provider.packageName == pkg && it.provider.className == cls }
            ?: providers.firstOrNull { it.provider.packageName == pkg && it.provider.className.endsWith(cls.substringAfterLast('.')) }
    }

    private fun run(taskController: ModelTaskController, dataModel: BgDataModel): TileApplyResult {
        val context = taskController.context
        val idp = InvariantDeviceProfile.INSTANCE.get(context)
        val glance = PreferenceManager2.getInstance(context).enableSmartspace.firstCached()

        // The file may say `com.x/.Clock`; the launcher says `com.x/com.x.Clock`. Compare like with like.
        val canonical = config.copy(
            pages = config.pages.map { page ->
                page.copy(
                    tiles = page.tiles.map { tile ->
                        val info = tile.widgetProvider?.let(::resolve)
                        if (info != null) tile.copy(kind = TileSpec.WIDGET_PREFIX + info.provider.flattenToString()) else tile
                    },
                )
            },
        )

        val screens = dataModel.itemsIdMap.collectWorkspaceScreens(context)
        val items: List<ItemInfo> = synchronized(dataModel) { dataModel.itemsIdMap.toList() }
        val mine = items.filterIsInstance<LauncherAppWidgetInfo>()
            .filter { it.appWidgetId in placedIds && it.container == Favorites.CONTAINER_DESKTOP && screens.indexOf(it.screenId) >= 0 }
            .toMutableList()

        fun pageOf(item: ItemInfo) = screens.indexOf(item.screenId) + 1

        val previous = mine.map {
            PlacedTile(pageOf(it), it.providerName.flattenToString(), it.cellX, it.cellY, it.spanX, it.spanY)
        }
        val states = (0 until screens.size()).map { index ->
            val screenId = screens.get(index)
            // Everything on this page except the widgets Dogo placed for tiles counts as the user's.
            val occupied = items
                .filter { it.container == Favorites.CONTAINER_DESKTOP && it.screenId == screenId }
                .filterNot { it is LauncherAppWidgetInfo && it.appWidgetId in placedIds }
                .map { CellRect(it.cellX, it.cellY, it.spanX, it.spanY) }
            PageState(index + 1, idp.numColumns, idp.numRows, occupied, reservedTopRow = glance && screenId == Workspace.FIRST_SCREEN_ID)
        }
        val limits: (String) -> WidgetLimits? = { provider ->
            resolve(provider)?.let { info ->
                val li = LauncherAppWidgetProviderInfo.fromProviderInfo(context, info)
                WidgetLimits(li.minSpanX.coerceAtLeast(1), li.minSpanY.coerceAtLeast(1), li.maxSpanX, li.maxSpanY)
            }
        }

        val plan = TilePlanner.plan(canonical, previous, states, idp.numColumns, idp.numRows, limits)
        val grid = plan.steps.filterIsInstance<TileStep.SetGrid>().firstOrNull()
        if (grid != null && !allowGrid) {
            return TileApplyResult(plan.describe(), 0, 0, 0, plan.refused.size, 0, placedIds, needsGrid = grid.columns to grid.rows)
        }

        val writer = taskController.getModelWriter()
        val newScreens = HashMap<Int, Int>()
        fun screenFor(page: Int): Int = if (page - 1 in 0 until screens.size()) {
            screens.get(page - 1)
        } else {
            newScreens.getOrPut(page) { taskController.model.modelDbController.getNewScreenId() }
        }

        fun find(page: Int, provider: String, x: Int, y: Int) = mine.firstOrNull {
            pageOf(it) == page && it.providerName.flattenToString() == provider &&
                (x < 0 || it.cellX == x) && (y < 0 || it.cellY == y)
        }

        fun rebind(item: ItemInfo) = taskController.scheduleCallbackTask { callbacks ->
            callbacks.bindWorkspaceComponentsRemoved { it === item }
            callbacks.bindItemsAdded(listOf(item))
        }

        var added = 0
        var moved = 0
        var removed = 0
        var needsSetup = 0
        for (step in plan.steps) {
            when (step) {
                is TileStep.SetGrid, is TileStep.Refuse -> Unit

                is TileStep.Remove -> find(step.page, step.provider, step.x, step.y)?.let { item ->
                    LauncherWidgetHolder.newInstance(context).let {
                        it.deleteAppWidgetId(item.appWidgetId)
                        it.destroy()
                    }
                    taskController.deleteAndBindComponentsRemoved({ it === item }, "Dogo: tiles")
                    mine.remove(item)
                    removed++
                }

                is TileStep.Move -> find(step.page, step.provider, step.fromX, step.fromY)?.let { item ->
                    writer.modifyItemInDatabase(item, Favorites.CONTAINER_DESKTOP, screenFor(step.page), step.toX, step.toY, item.spanX, item.spanY)
                    rebind(item)
                    moved++
                }

                is TileStep.Resize -> find(step.page, step.provider, step.fromX, step.fromY)?.let { item ->
                    writer.modifyItemInDatabase(item, Favorites.CONTAINER_DESKTOP, screenFor(step.page), step.spec.x, step.spec.y, step.spec.width, step.spec.height)
                    rebind(item)
                    moved++
                }

                is TileStep.Add -> step.spec.widgetProvider?.let(::resolve)?.let { provider ->
                    val info = LauncherAppWidgetProviderInfo.fromProviderInfo(context, provider)
                    val holder = LauncherWidgetHolder.newInstance(context)
                    val widgetId = try {
                        holder.allocateAppWidgetId()
                    } finally {
                        holder.destroy()
                    }
                    val options = WidgetSizes.getWidgetSizeOptions(context, info.provider, step.spec.width, step.spec.height)
                    val bound = WidgetManagerHelper(context).bindAppWidgetIdIfAllowed(widgetId, info, options)
                    val item = LauncherAppWidgetInfo(widgetId, info.provider).apply {
                        spanX = step.spec.width
                        spanY = step.spec.height
                        minSpanX = info.minSpanX
                        minSpanY = info.minSpanY
                        user = Process.myUserHandle()
                        restoreStatus = when {
                            !bound -> LauncherAppWidgetInfo.FLAG_ID_NOT_VALID or LauncherAppWidgetInfo.FLAG_ID_ALLOCATED
                            info.configure != null -> LauncherAppWidgetInfo.FLAG_UI_NOT_READY
                            else -> LauncherAppWidgetInfo.RESTORE_COMPLETED
                        }
                    }
                    writer.addItemToDatabase(item, Favorites.CONTAINER_DESKTOP, screenFor(step.page), step.spec.x, step.spec.y)
                    taskController.scheduleCallbackTask { it.bindItemsAdded(listOf<ItemInfo>(item)) }
                    mine.add(item)
                    added++
                    if (!bound || info.configure != null) needsSetup++
                }
            }
        }
        return TileApplyResult(plan.describe(), added, moved, removed, plan.refused.size, needsSetup, mine.map { it.appWidgetId }.toSet())
    }
}
