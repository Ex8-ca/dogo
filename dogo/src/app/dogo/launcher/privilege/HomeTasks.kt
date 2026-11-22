/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.privilege

import android.appwidget.AppWidgetProviderInfo
import android.os.Process
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherModel
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.Workspace
import com.android.launcher3.model.AllAppsList
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.model.ModelTaskController
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.util.GridOccupancy
import com.android.launcher3.widget.LauncherAppWidgetProviderInfo
import com.android.launcher3.widget.LauncherWidgetHolder
import com.android.launcher3.widget.WidgetManagerHelper
import com.android.launcher3.widget.util.WidgetSizes

/** How [AddWidgetTask] went. */
data class WidgetPlacement(
    val label: String,
    val page: Int,
    val columns: Int,
    val rows: Int,
    val bound: Boolean,
    val needsSetup: Boolean,
    val alreadyThere: Boolean = false,
)

/**
 * Puts [provider]'s widget on the home screen, written straight to the
 * launcher database (like [AddFolderTask]) so it survives a reload.
 *
 * If Android won't let Dogo bind widgets yet, the widget is still placed but
 * shows "tap to set up"; one tap brings up Android's own "allow Dogo to
 * create widgets?" prompt.
 */
class AddWidgetTask(
    private val provider: AppWidgetProviderInfo,
    private val columns: Int?,
    private val rows: Int?,
    private val page: Int?,
    private val onDone: (Result<WidgetPlacement>) -> Unit,
) : LauncherModel.ModelUpdateTask {

    override fun execute(taskController: ModelTaskController, dataModel: BgDataModel, apps: AllAppsList) {
        onDone(
            runCatching {
                val context = taskController.context
                val idp = InvariantDeviceProfile.INSTANCE.get(context)
                val info = LauncherAppWidgetProviderInfo.fromProviderInfo(context, provider)
                val spanX = (columns ?: info.spanX).coerceIn(info.minSpanX.coerceAtLeast(1), idp.numColumns)
                val spanY = (rows ?: info.spanY).coerceIn(info.minSpanY.coerceAtLeast(1), idp.numRows)

                val holder = LauncherWidgetHolder.newInstance(context)
                val widgetId = try {
                    holder.allocateAppWidgetId()
                } finally {
                    holder.destroy()
                }
                val options = WidgetSizes.getWidgetSizeOptions(context, info.provider, spanX, spanY)
                val bound = WidgetManagerHelper(context).bindAppWidgetIdIfAllowed(widgetId, info, options)

                val item = LauncherAppWidgetInfo(widgetId, info.provider).apply {
                    this.spanX = spanX
                    this.spanY = spanY
                    minSpanX = info.minSpanX
                    minSpanY = info.minSpanY
                    user = Process.myUserHandle()
                    restoreStatus = when {
                        // Tapping it starts Android's bind prompt with this id.
                        !bound -> LauncherAppWidgetInfo.FLAG_ID_NOT_VALID or LauncherAppWidgetInfo.FLAG_ID_ALLOCATED

                        // Bound, but the app wants to ask a question first (which city, which calendar).
                        info.configure != null -> LauncherAppWidgetInfo.FLAG_UI_NOT_READY

                        else -> LauncherAppWidgetInfo.RESTORE_COMPLETED
                    }
                }

                // Asking twice (a re-sent photo, a repeated request) shouldn't stack copies.
                val existing = synchronized(dataModel) {
                    dataModel.itemsIdMap.firstOrNull { it is LauncherAppWidgetInfo && it.providerName == info.provider && it.container == Favorites.CONTAINER_DESKTOP }
                }
                if (existing != null) {
                    LauncherWidgetHolder.newInstance(context).let {
                        it.deleteAppWidgetId(widgetId)
                        it.destroy()
                    }
                    val screens = dataModel.itemsIdMap.collectWorkspaceScreens(context)
                    return@runCatching WidgetPlacement(info.getLabel()?.toString().orEmpty(), screens.indexOf(existing.screenId).coerceAtLeast(0), existing.spanX, existing.spanY, bound = true, needsSetup = false, alreadyThere = true)
                }

                val placed: Pair<Int, IntArray>
                synchronized(dataModel) {
                    placed = HomeGrid.findSpace(context, dataModel, taskController, idp, spanX, spanY, page)
                    taskController.getModelWriter().addItemToDatabase(item, Favorites.CONTAINER_DESKTOP, placed.second[0], placed.second[1], placed.second[2])
                }
                taskController.scheduleCallbackTask { it.bindItemsAdded(listOf<ItemInfo>(item)) }
                WidgetPlacement(
                    label = info.getLabel()?.toString().orEmpty(),
                    page = placed.first,
                    columns = spanX,
                    rows = spanY,
                    bound = bound,
                    needsSetup = !bound || info.configure != null,
                )
            },
        )
    }
}

/**
 * Replaces the dock's apps. The old dock icons are only removed from the dock;
 * the apps stay installed and in the drawer.
 */
class SetDockTask(
    private val appsToPlace: List<AppInfo>,
    private val onDone: (Result<Int>) -> Unit,
) : LauncherModel.ModelUpdateTask {

    override fun execute(taskController: ModelTaskController, dataModel: BgDataModel, apps: AllAppsList) {
        onDone(
            runCatching {
                val context = taskController.context
                val slots = InvariantDeviceProfile.INSTANCE.get(context).numShownHotseatIcons
                val items = appsToPlace.take(slots).mapIndexed { i, app ->
                    app.makeWorkspaceItem(context).apply {
                        container = Favorites.CONTAINER_HOTSEAT
                        screenId = i
                        cellX = i
                        cellY = 0
                    }
                }
                check(items.isNotEmpty()) { "No apps to put in the dock." }
                taskController.deleteAndBindComponentsRemoved({ it != null && it.container == Favorites.CONTAINER_HOTSEAT }, "Dogo: new dock")
                taskController.getModelWriter().addItemsToDatabase(items.map { it as ItemInfo })
                taskController.scheduleCallbackTask { it.bindItemsAdded(items.map { item -> item as ItemInfo }) }
                items.size
            },
        )
    }
}

/** Finding room on the home screen pages. */
internal object HomeGrid {

    /**
     * Returns (page index, [screenId, cellX, cellY]) for a spanX × spanY item,
     * preferring [page], then the first page with room, then a new page. The
     * top row of the first page is left for "At a glance" when it is on.
     */
    fun findSpace(
        context: android.content.Context,
        dataModel: BgDataModel,
        taskController: ModelTaskController,
        idp: InvariantDeviceProfile,
        spanX: Int,
        spanY: Int,
        page: Int?,
    ): Pair<Int, IntArray> {
        val screens = dataModel.itemsIdMap.collectWorkspaceScreens(context)
        val glance = PreferenceManager2.getInstance(context).enableSmartspace.firstCached()
        val order = buildList {
            if (page != null && page in 0 until screens.size()) add(page)
            for (i in 0 until screens.size()) if (i != page) add(i)
        }
        for (index in order) {
            val screenId = screens.get(index)
            val grid = GridOccupancy(idp.numColumns, idp.numRows)
            dataModel.itemsIdMap.forEach { if (it.container == Favorites.CONTAINER_DESKTOP && it.screenId == screenId) grid.markCells(it, true) }
            if (glance && screenId == Workspace.FIRST_SCREEN_ID) grid.markCells(0, 0, idp.numColumns, 1, true)
            val xy = IntArray(2)
            if (grid.findVacantCell(xy, spanX, spanY)) return index to intArrayOf(screenId, xy[0], xy[1])
        }
        val newScreen = taskController.model.modelDbController.getNewScreenId()
        return screens.size() to intArrayOf(newScreen, 0, 0)
    }
}
