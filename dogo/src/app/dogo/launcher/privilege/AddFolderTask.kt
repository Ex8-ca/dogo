/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.privilege

import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherModel
import com.android.launcher3.LauncherSettings
import com.android.launcher3.model.AllAppsList
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.model.ModelTaskController
import com.android.launcher3.model.WorkspaceItemSpaceFinder
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.util.IntArray

/**
 * Puts a new folder, with its apps, on the first free home-screen cell.
 *
 * Every item is written to the launcher database with the folder as its
 * container and a rank, so the folder survives the next model reload. (Lawnchair's
 * deck task only moves items in memory: after a reload the folder comes back
 * empty and Launcher3 deletes it.)
 */
class AddFolderTask(
    private val folder: FolderInfo,
    private val onDone: (Result<Int>) -> Unit,
) : LauncherModel.ModelUpdateTask {

    override fun execute(taskController: ModelTaskController, dataModel: BgDataModel, apps: AllAppsList) {
        val result = runCatching {
            val context = taskController.context
            val items = folder.getContents().filterIsInstance<WorkspaceItemInfo>()
            check(items.size >= 2) { "A folder needs at least two apps; Android shows one app as a plain icon." }

            val writer = taskController.getModelWriter()
            synchronized(dataModel) {
                val finder = WorkspaceItemSpaceFinder(
                    dataModel,
                    InvariantDeviceProfile.INSTANCE.get(context),
                    LauncherAppState.getInstance(context).model,
                )
                val (screenId, cellX, cellY) = finder.findSpaceForItem(
                    dataModel.itemsIdMap.collectWorkspaceScreens(context),
                    IntArray(),
                    ArrayList<ItemInfo>(),
                    folder.spanX,
                    folder.spanY,
                    context,
                ).let { Triple(it[0], it[1], it[2]) }

                // The folder first, so it has an id for its items to point at.
                writer.addItemToDatabase(folder, LauncherSettings.Favorites.CONTAINER_DESKTOP, screenId, cellX, cellY)
                items.forEachIndexed { rank, item ->
                    item.rank = rank
                    // In a folder, cellX/cellY are ignored on load; rank orders the items.
                    writer.addItemToDatabase(item, folder.id, 0, rank, 0)
                }
            }
            taskController.scheduleCallbackTask { it.bindItemsAdded(listOf<ItemInfo>(folder)) }
            items.size
        }
        onDone(result)
    }
}
