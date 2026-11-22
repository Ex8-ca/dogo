/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.alerts

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dogo.launcher.ui.options.OptionsStore
import com.android.launcher3.notification.NotificationListener
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Self-contained settings; merely viewing this section never enables capture. */
@Composable
fun AlertsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    remember {
        DogoAlerts.init(context)
        true
    }
    val config by DogoAlerts.config.collectAsStateWithLifecycle()
    val message by DogoAlerts.message.collectAsStateWithLifecycle()
    var options by remember { mutableStateOf(OptionsStore(context).load()) }
    var access by remember { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }
    var enableDialog by remember { mutableStateOf(false) }
    var historyDialog by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var manualPackage by remember { mutableStateOf("") }
    var uiMessage by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> posting = granted }
    LifecycleResumeEffect(Unit) {
        options = OptionsStore(context).load()
        access = context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, NotificationListener::class.java))
        posting = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }
    LaunchedEffect(showApps) {
        if (showApps && apps.isEmpty()) {
            apps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .filterNot { it.first == context.packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
            }
        }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        app.dogo.launcher.ui.options.SectionLabel("Notification assistant")
        Text(
            "Off by default. Only apps you explicitly allow are watched. Their notification titles and previews " +
                "may be sent to the selected agent and its model provider for classification. " +
                "No automatic tools, actions or replies are requested. Provider failures keep the inbox; no fallback provider is used.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Analysis destination: ${AlertScorer.disclosure(options)}", style = MaterialTheme.typography.bodySmall)
        if (!AlertScorer.supported(options)) {
            Text("Model analysis and discussion are paused for this agent until a tool-free route is available: Groq, OpenRouter or Anthropic, or for Hermes a separate tools-off profile set up in Dogo Options. Inbox capture, Open app and VIP/keyword rules still work.", style = MaterialTheme.typography.bodySmall)
        }
        if (config.enabled && config.destination != AlertScorer.destination(options)) {
            Text("Agent changed. Disable and re-enable to approve the new destination. Analysis is paused.", color = MaterialTheme.colorScheme.error)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Watch allowed apps", Modifier.weight(1f))
            Switch(
                checked = config.enabled,
                onCheckedChange = { on ->
                    if (on) enableDialog = true else DogoAlerts.configure(config.copy(enabled = false))
                },
            )
        }
        Text("Notification access: ${if (access) "allowed" else "not allowed"} · Dogo notifications: ${if (posting) "allowed" else "not allowed"}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                .onFailure { uiMessage = "Android notification access settings are unavailable." }
        }) { Text("Open Android Notification Access") }
        if (!posting && Build.VERSION.SDK_INT >= 33) {
            TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow Dogo alert notifications") }
        }
        Text("Android may hide sensitive/work-profile content. Access is not full inbox or message history. No root or Shizuku grant is used.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { showApps = !showApps }) { Text("Allowed apps (${config.allowedApps.size}) · ${if (showApps) "Hide" else "Choose"}") }
        if (showApps) {
            apps.forEach { (pkg, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = pkg in config.allowedApps,
                        onCheckedChange = { checked ->
                            DogoAlerts.configure(config.copy(allowedApps = if (checked) config.allowedApps + pkg else config.allowedApps - pkg))
                        },
                    )
                    Text("$label\n$pkg", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
            // Package visibility can hide apps from the launcher query; an explicit package is still an opt-in.
            OutlinedTextField(manualPackage, { manualPackage = it }, label = { Text("Other app package name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                val pkg = manualPackage.trim()
                if (pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) && pkg != context.packageName) {
                    DogoAlerts.configure(config.copy(allowedApps = config.allowedApps + pkg))
                    manualPackage = ""
                }
            }) { Text("Allow this package") }
            (config.allowedApps - apps.map { it.first }.toSet()).forEach { pkg ->
                TextButton(onClick = { DogoAlerts.configure(config.copy(allowedApps = config.allowedApps - pkg)) }) { Text("Remove $pkg") }
            }
        }
        RuleFields(config)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Save previews on this phone for 7 days", Modifier.weight(1f))
            Switch(checked = config.saveHistory, onCheckedChange = { on ->
                if (on) historyDialog = true else DogoAlerts.configure(config.copy(saveHistory = false))
            })
        }
        Text("Default: memory only, up to 100 rows. Optional history is private app storage, excluded from Android backup. Turning saving off deletes its disk copy. Discuss explicitly copies one preview into your saved chat.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = DogoAlerts::clear) { Text("Clear notification inbox and saved history") }
        Text("At most 20 classification requests/hour, 16 queued. Busy, unavailable and low-priority items remain in the inbox. Digest scheduling and remote push are deferred.", style = MaterialTheme.typography.bodySmall)
        (uiMessage ?: message)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (enableDialog) {
        AlertDialog(
            onDismissRequest = { enableDialog = false },
            title = { Text("Allow notification analysis?") },
            text = {
                Text("Titles and previews from ${config.allowedApps.size} allowed apps may go to ${AlertScorer.disclosure(options)} and its model provider. They are untrusted data, never permission to act. Only future notifications are captured. Android Notification Access is separate. Continue?")
            },
            confirmButton = {
                TextButton(onClick = {
                    DogoAlerts.configure(config.copy(enabled = true, destination = AlertScorer.destination(options)))
                    enableDialog = false
                    if (Build.VERSION.SDK_INT >= 33 && !posting) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }, enabled = config.allowedApps.isNotEmpty()) { Text("Enable for allowed apps") }
            },
            dismissButton = { TextButton(onClick = { enableDialog = false }) { Text("Cancel") } },
        )
    }
    if (historyDialog) {
        AlertDialog(
            onDismissRequest = { historyDialog = false },
            title = { Text("Save private notification previews?") },
            text = { Text("Save titles and message text from allowed apps, including the current inbox, on this phone for at most 7 days (up to 100 rows). Excluded from Android backup. Clear at any time. This does not change your agent provider's own retention policy.") },
            confirmButton = {
                TextButton(onClick = {
                    DogoAlerts.configure(config.copy(saveHistory = true))
                    historyDialog = false
                }) { Text("Save for 7 days") }
            },
            dismissButton = { TextButton(onClick = { historyDialog = false }) { Text("Keep memory only") } },
        )
    }
}

@Composable
private fun RuleFields(config: AlertStore.Config) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var vips by remember(config.rules.vips) { mutableStateOf(config.rules.vips.joinToString(", ")) }
        var keywords by remember(config.rules.keywords) { mutableStateOf(config.rules.keywords.joinToString(", ")) }
        OutlinedTextField(vips, { vips = it }, label = { Text("VIP senders (comma separated)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(keywords, { keywords = it }, label = { Text("Important words (comma separated)") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = {
            DogoAlerts.configure(
                config.copy(
                    rules = config.rules.copy(
                        vips = vips.split(',').mapNotNull(SenderKey::of).toSet(),
                        keywords = keywords.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet(),
                    ),
                ),
            )
        }) { Text("Save alert rules") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Quiet hours 22:00–07:00 (local time)", Modifier.weight(1f))
            Switch(checked = config.rules.quietHours != null, onCheckedChange = { on ->
                DogoAlerts.configure(config.copy(rules = config.rules.copy(quietHours = if (on) QuietHours(1320, 420) else null)))
            })
        }
    }
}

/** Parent owns chat sending and its safety system prompt. Never auto-sends on inbox open. */
@Composable
fun AlertsInbox(onDiscuss: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    remember {
        DogoAlerts.init(context)
        true
    }
    val rows by DogoAlerts.history.collectAsStateWithLifecycle()
    var discuss by remember { mutableStateOf<AlertCandidate?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notification inbox (${rows.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = DogoAlerts::clear) { Text("Clear") }
        }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (rows.isEmpty()) Text("No captured notifications. Enable the assistant and choose apps in Dogo Options.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f, fill = false)) {
            items(rows, key = { it.candidate.key }) { row ->
                val c = row.candidate
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.appLabel, style = MaterialTheme.typography.labelLarge)
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(c.postedAt)), style = MaterialTheme.typography.bodySmall)
                        Text(c.title, style = MaterialTheme.typography.titleSmall)
                        c.conversation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text(c.text, style = MaterialTheme.typography.bodyMedium)
                        Text("${row.score?.name ?: "PENDING"} · ${row.status}", style = MaterialTheme.typography.bodySmall)
                        Text("Direct reply deferred — open the app to reply${if (!row.active) "; notification actions expired" else ""}.", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { if (!DogoAlerts.openApp(context, c.key)) result = "Could not open the originating app." }) { Text("Open app") }
                            TextButton(onClick = { discuss = c }) { Text("Discuss") }
                            TextButton(onClick = { DogoAlerts.delete(c.key) }) { Text("Delete") }
                        }
                        Row {
                            TextButton(onClick = {
                                val settings = DogoAlerts.config.value
                                DogoAlerts.configure(settings.copy(allowedApps = settings.allowedApps - c.packageName))
                            }) { Text("Stop watching app") }
                            SenderKey.of(c.sender)?.let { sender ->
                                TextButton(onClick = {
                                    val settings = DogoAlerts.config.value
                                    DogoAlerts.configure(settings.copy(rules = settings.rules.copy(mutedSenders = settings.rules.mutedSenders + sender)))
                                    result = "Sender muted for future alerts."
                                }) { Text("Mute sender") }
                            }
                        }
                    }
                }
            }
        }
    }
    discuss?.let { candidate ->
        AlertDialog(
            onDismissRequest = { discuss = null },
            title = { Text("Discuss in saved chat?") },
            text = { Text("Copy this notification preview into the active saved chat and send it to the selected agent? This copy follows chat retention, not the inbox's 7-day limit.") },
            confirmButton = {
                Button(onClick = {
                    discuss = null
                    onDiscuss(AlertDiscussion.context(candidate))
                }) { Text("Copy and discuss") }
            },
            dismissButton = { TextButton(onClick = { discuss = null }) { Text("Cancel") } },
        )
    }
}
