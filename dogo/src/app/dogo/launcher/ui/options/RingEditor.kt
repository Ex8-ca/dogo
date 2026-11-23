/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.options

import android.content.pm.LauncherApps
import android.os.Process
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.dogo.launcher.ring.DogoRing
import app.dogo.launcher.ring.RingBuilder
import app.dogo.launcher.ring.RingConfig
import app.dogo.launcher.ring.RingGroup
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.util.ComponentKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Unlike RingConfig's persistence reader, preserve unfinished (including blank) headings.
private val GroupDraftSaver = Saver<List<RingGroup>, ArrayList<String>>(
    save = { groups -> ArrayList(groups.map { JSONObject().put("name", it.name).put("apps", JSONArray(it.apps)).toString() }) },
    restore = { rows ->
        rows.map { row ->
            val json = JSONObject(row)
            val apps = json.getJSONArray("apps")
            RingGroup(json.getString("name"), (0 until apps.length()).map { apps.getString(it) })
        }
    },
)

/** Manual, local-only ring editing. Nothing is persisted until Save is tapped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RingEditor(onDismiss: () -> Unit, onSave: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var baselineJson by rememberSaveable { mutableStateOf(DogoRing.config(context).toJson()) }
    val baseline = remember(baselineJson) { RingConfig.fromJson(baselineJson) }
    var groups by rememberSaveable(stateSaver = GroupDraftSaver) { mutableStateOf(baseline.groups.orEmpty()) }
    var initialGroups by rememberSaveable(stateSaver = GroupDraftSaver) { mutableStateOf(baseline.groups.orEmpty()) }
    var initialized by rememberSaveable { mutableStateOf(baseline.groups != null) }
    var apps by remember { mutableStateOf<List<RingBuilder.AppEntry>?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var retry by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var picking by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(-1) }
    var removing by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(-1) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val dirty = groups != initialGroups
    val labels = remember(apps) { apps.orEmpty().associate { it.pkg to it.label } }

    LaunchedEffect(retry) {
        apps = null
        loadError = false
        try {
            val loaded = withContext(Dispatchers.IO) {
                val user = Process.myUserHandle()
                val hidden = PreferenceManager2.getInstance(context).hiddenApps.get().first()
                context.getSystemService(LauncherApps::class.java).getActivityList(null, user)
                    .filter { ComponentKey(it.componentName, it.user).toString() !in hidden }
                    .filter { it.componentName.packageName != context.packageName }
                    .map { RingBuilder.AppEntry(it.componentName.packageName, it.label.toString(), it.applicationInfo.category) }
                    .distinctBy { it.pkg }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            }
            apps = loaded
            if (!initialized) {
                groups = RingBuilder.effectiveGroups(baseline, loaded, context.packageName.substringBefore(".debug"))
                initialGroups = groups
                initialized = true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = true
        }
    }

    fun close() {
        if (dirty) confirmDiscard = true else onDismiss()
    }

    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                TopAppBar(
                    title = { Text("Edit ring") },
                    navigationIcon = { IconButton(onClick = { close() }) { Icon(Icons.Default.Close, contentDescription = "Close ring editor") } },
                )
            },
            bottomBar = {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { close() }) { Text("Cancel") }
                    Button(
                        enabled = initialized && apps != null,
                        onClick = {
                            submitted = true
                            saveError = null
                            if (groups.any { it.name.isBlank() }) return@Button
                            if (!dirty) {
                                // Nothing was edited: leave an automatic ring automatic.
                                onSave()
                                return@Button
                            }
                            if (DogoRing.config(context).groups != baseline.groups) {
                                conflict = true
                                return@Button
                            }
                            try {
                                DogoRing.update(context) { current -> current.copy(groups = groups.map { it.copy(name = it.name.trim(), apps = it.apps.distinct()) }) }
                                onSave()
                            } catch (e: Exception) {
                                saveError = "Couldn't save the ring. Your edits are still here; try again."
                            }
                        },
                    ) { Text("Save ring") }
                }
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text("Rename headings, then use + to pick apps and − to remove them. Changes stay here until you save.", style = MaterialTheme.typography.bodyMedium)
                }
                item {
                    Text("Dogo's Talk, Chat, All apps, Home and Options commands stay available. A group with one app opens that app directly; empty groups are hidden on the ring.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (apps == null) {
                    item {
                        if (loadError) {
                            Column {
                                Text("Couldn't load your apps. Your ring has not changed.")
                                TextButton(onClick = { retry++ }) { Text("Try again") }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(24.dp))
                                Text("Loading apps…", Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
                items(groups.indices.toList()) { index ->
                    val group = groups[index]
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = group.name,
                                    onValueChange = { name -> groups = groups.mapIndexed { i, g -> if (i == index) g.copy(name = name.take(RingConfig.MAX_NAME)) else g } },
                                    label = { Text("Group heading") },
                                    singleLine = true,
                                    isError = submitted && group.name.isBlank(),
                                    enabled = initialized,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(enabled = initialized, onClick = { removing = index }) { Icon(Icons.Default.Delete, contentDescription = "Remove group ${group.name}") }
                            }
                            if (submitted && group.name.isBlank()) Text("Give this group a heading.", color = MaterialTheme.colorScheme.error)
                            group.apps.forEach { pkg ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                        Text(labels[pkg] ?: pkg, style = MaterialTheme.typography.bodyMedium)
                                        if (apps != null && pkg !in labels) Text("Unavailable or hidden", style = MaterialTheme.typography.labelSmall)
                                    }
                                    IconButton(enabled = initialized, onClick = { groups = groups.mapIndexed { i, g -> if (i == index) g.copy(apps = g.apps - pkg) else g } }) {
                                        Icon(Icons.Default.Remove, contentDescription = "Remove ${labels[pkg] ?: pkg} from ${group.name}")
                                    }
                                }
                            }
                            if (group.apps.isEmpty()) Text("No apps yet", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { picking = index }, enabled = initialized && apps != null) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Text("Add app", Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
                item {
                    TextButton(enabled = initialized, onClick = { groups = groups + RingGroup("New group", emptyList()) }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("Add group", Modifier.padding(start = 8.dp))
                    }
                }
                saveError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
    if (picking in groups.indices) {
        RingAppPicker(
            group = groups[picking],
            apps = apps.orEmpty(),
            onDismiss = { picking = -1 },
            onAdd = { pkg ->
                groups = groups.mapIndexed { index, group -> if (index == picking) group.copy(apps = (group.apps + pkg).distinct()) else group }
            },
        )
    }
    if (removing in groups.indices) {
        AlertDialog(
            onDismissRequest = { removing = -1 },
            title = { Text("Remove ${groups[removing].name}?") },
            text = { Text("This removes the group from the ring, not the apps from your phone.") },
            confirmButton = {
                TextButton(onClick = {
                    groups = groups.filterIndexed { index, _ -> index != removing }
                    removing = -1
                }) { Text("Remove group") }
            },
            dismissButton = { TextButton(onClick = { removing = -1 }) { Text("Keep group") } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard ring edits?") },
            text = { Text("Your saved ring will stay as it was.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Discard edits") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
    if (conflict) {
        AlertDialog(
            onDismissRequest = { conflict = false },
            title = { Text("The ring changed while you were editing") },
            text = { Text("Your draft hasn't been saved. Keep editing, or reload the latest ring and discard this draft.") },
            confirmButton = {
                TextButton(onClick = {
                    baselineJson = DogoRing.config(context).toJson()
                    groups = RingBuilder.effectiveGroups(RingConfig.fromJson(baselineJson), apps.orEmpty(), context.packageName.substringBefore(".debug"))
                    initialGroups = groups
                    initialized = true
                    submitted = false
                    conflict = false
                }) { Text("Reload ring") }
            },
            dismissButton = { TextButton(onClick = { conflict = false }) { Text("Keep draft") } },
        )
    }
}

@Composable
private fun RingAppPicker(group: RingGroup, apps: List<RingBuilder.AppEntry>, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val candidates = apps.filter { it.pkg !in group.apps && (it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add apps to ${group.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, onValueChange = { query = it }, label = { Text("Search apps") }, singleLine = true)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    if (candidates.isEmpty()) item { Text("No more matching apps.", Modifier.padding(vertical = 16.dp)) }
                    items(candidates, key = { it.pkg }) { app ->
                        Row(Modifier.fillMaxWidth().clickable { onAdd(app.pkg) }, verticalAlignment = Alignment.CenterVertically) {
                            Text(app.label, Modifier.weight(1f).padding(vertical = 14.dp))
                            IconButton(onClick = { onAdd(app.pkg) }) { Icon(Icons.Default.Add, contentDescription = "Add ${app.label}") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
