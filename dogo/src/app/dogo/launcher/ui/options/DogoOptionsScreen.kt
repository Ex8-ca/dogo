/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.options

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dogo.launcher.agent.AgentOptions
import app.dogo.launcher.agent.backend.AgentCatalog
import app.dogo.launcher.agent.backend.AgentConfig
import app.dogo.launcher.agent.backend.AgentPreset
import app.dogo.launcher.agent.backend.KeyRequirement
import app.dogo.launcher.agent.backend.ResolvedAgent
import app.dogo.launcher.agent.backend.Termux
import app.dogo.launcher.agent.backend.TermuxCli
import app.dogo.launcher.alerts.AlertsSection
import app.dogo.launcher.llm.ModelCatalog
import app.dogo.launcher.plugins.PluginRegistry
import app.dogo.launcher.privilege.Tier
import app.dogo.launcher.privilege.TierStatus
import app.dogo.launcher.ring.RingConfig
import app.dogo.launcher.ring.RingStyle
import app.dogo.launcher.themes.OmarchyThemeSource
import app.dogo.launcher.themes.WallpaperThemeSection
import app.dogo.launcher.voice.RealtimeProtocol
import app.dogo.launcher.voice.VoiceConfig

/**
 * Lets the user choose which agent Dogo talks to, which on-device model it
 * uses, and what the agent is allowed to change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DogoOptionsScreen(
    modifier: Modifier = Modifier,
    viewModel: DogoOptionsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(viewModel) {
        viewModel.refreshAccess()
        viewModel.refreshPlugins()
        onPauseOrDispose { }
    }

    val termuxPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshAccess()
    }
    viewModel.onRequestTermuxPermission = { termuxPermission.launch(Termux.PERMISSION) }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Dogo Options") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.recommendation?.let { rec ->
                RecommendationCard(
                    rationale = rec.rationale,
                    ramGb = rec.ramGb,
                    freeStorageGb = rec.freeStorageGb,
                )
            }

            state.issues.forEach { issue ->
                IssueCard(issue)
            }

            GroupHeader("Agent", "Who Dogo talks to, and how hard it thinks.", first = true)

            val noLocalModel = state.recommendation?.primary == null
            AgentCatalog.all.forEach { preset ->
                AgentRow(
                    preset = preset,
                    selected = state.options.agentId == preset.id,
                    onSelect = { viewModel.selectAgent(preset) },
                    note = if (preset == AgentCatalog.ON_DEVICE && noLocalModel) "not available on this phone" else null,
                )
            }

            if (state.options.agent == AgentCatalog.TERMUX) {
                TermuxAgentCard(
                    selectedCli = state.options.resolvedAgent().model,
                    termux = state.termux,
                    connection = state.connection,
                    onPick = { id -> viewModel.updateAgentConfig { it.copy(model = id) } },
                    onRequestPermission = viewModel::requestTermuxPermission,
                    onTest = viewModel::testConnection,
                )
            } else if (!state.options.isOnDevice) {
                RemoteAgentCard(
                    preset = state.options.agent,
                    config = state.options.configFor(state.options.agent),
                    resolved = state.options.resolvedAgent(),
                    connection = state.connection,
                    onEdit = viewModel::updateAgentConfig,
                    onTest = viewModel::testConnection,
                )
            }

            if (state.options.agent == AgentCatalog.HERMES) {
                SafeRouteCard(
                    config = state.options.configFor(AgentCatalog.HERMES_SAFE),
                    check = state.safeRoute,
                    onEdit = viewModel::updateSafeRoute,
                    onTest = viewModel::testSafeRoute,
                )
            }

            if (state.options.isOnDevice) {
                SectionLabel("Model")
                Text(
                    "Automatically follow this phone" +
                        if (state.options.modelId == null) " (currently selected)" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setModel(null) }
                        .padding(vertical = 8.dp),
                )
                state.models.forEach { row ->
                    ModelCard(
                        row = row,
                        selected = state.options.modelId == row.variant.id,
                        onSelect = { viewModel.setModel(row.variant) },
                        onDownload = { viewModel.download(row.variant) },
                        onDelete = { viewModel.delete(row.variant) },
                    )
                }
            }

            SectionLabel("Thinking level")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val levels = AgentOptions.ThinkingLevel.entries
                levels.forEachIndexed { index, level ->
                    SegmentedButton(
                        selected = state.options.thinkingLevel == level,
                        onClick = { viewModel.setThinkingLevel(level) },
                        shape = SegmentedButtonDefaults.itemShape(index, levels.size),
                    ) {
                        Text(level.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }
            Text(
                state.options.thinkingLevel.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GroupHeader("You and voice", "Your name, Dogo's personality, and talking to Dogo out loud.")

            SectionLabel("About you")
            app.dogo.launcher.profile.AssistantProfileSection()

            SectionLabel("Voice")
            VoiceSection(
                config = state.voice,
                check = state.voiceCheck,
                onChange = viewModel::updateVoice,
                onUseHermes = viewModel::useHermesForVoice,
                onTest = viewModel::testVoice,
            )

            GroupHeader("Look and feel", "The ring, colours and themes.")

            SectionLabel("Dogo Ring")
            RingSection(
                config = state.ring,
                onChange = viewModel::updateRing,
            )

            WallpaperThemeSection()

            SectionLabel("Omarchy themes")
            ThemesSection(
                state = state.themes,
                onLoad = viewModel::loadOfficialThemes,
                onApply = viewModel::applyTheme,
                onApplyCommunity = viewModel::applyCommunityTheme,
                onWallpaperToggle = viewModel::setThemeWallpaper,
            )

            GroupHeader("Notifications", "Off until you turn it on. Only apps you pick are watched.")

            AlertsSection()

            GroupHeader("What Dogo can do", "You decide what Dogo may change, and whether it asks first.")

            SectionLabel("Approval")
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Confirm every action", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Dogo shows each change as a card and waits for your tap. Turn this off only for changes you trust.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.options.requireConfirmation,
                    onCheckedChange = viewModel::setRequireConfirmation,
                )
            }

            SectionLabel("Changes Dogo may make")
            AgentOptions.ActionType.entries.forEach { type ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(type.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            type.blurb,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = type in state.options.allowedActions,
                        onCheckedChange = { viewModel.toggleAction(type) },
                    )
                }
            }

            SectionLabel("System access")
            Text(
                "Some changes reach beyond the launcher. Dogo uses the least access " +
                    "that works, and only for changes you approve.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.access.filter { it.tier != Tier.NONE }.forEach { status ->
                AccessRow(status, onRequest = { viewModel.requestAccess(status.tier) })
            }

            GroupHeader("Plugins", "Extra actions from plugins you have installed.")

            PluginsSection(
                plugins = state.plugins,
                broken = state.brokenPlugins,
                onToggle = viewModel::setPluginEnabled,
            )
        }
    }
}

@Composable
private fun GroupHeader(title: String, subtitle: String, first: Boolean = false) {
    Column(
        Modifier.fillMaxWidth().padding(top = if (first) 4.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!first) HorizontalDivider()
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = if (first) 0.dp else 8.dp),
        )
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.secondary,
        modifier = modifier.padding(top = 16.dp),
    )
}

@Composable
private fun RecommendationCard(rationale: String, ramGb: Int, freeStorageGb: Int) {
    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("This phone", style = MaterialTheme.typography.labelLarge)
            Text(
                "$ramGb GB RAM · $freeStorageGb GB free",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                rationale,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun IssueCard(issue: AgentOptions.Issue) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Text(issue.message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ModelCard(
    row: DogoOptionsViewModel.ModelRow,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val variant = row.variant
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(variant.displayName, style = MaterialTheme.typography.bodyLarge)
                        if (row.recommended) {
                            Text(
                                "  Recommended",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        buildString {
                            append("%.1f GB".format(variant.sizeGb))
                            append(" · needs ")
                            append(variant.minRamGb)
                            append(" GB RAM")
                            if (!row.fits) append(" · won't fit on this phone")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.fits) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                when {
                    row.downloading -> CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )

                    row.downloaded -> IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, "Delete model")
                    }

                    else -> IconButton(onClick = onDownload) {
                        Icon(Icons.Default.Download, "Download model")
                    }
                }
            }
            if (row.downloading) {
                LinearProgressIndicator(
                    progress = { row.progress },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
                Text(
                    "${(row.progress * 100).toInt()}% downloaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AgentRow(preset: AgentPreset, selected: Boolean, onSelect: () -> Unit, note: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(preset.displayName, style = MaterialTheme.typography.bodyLarge)
                if (preset.leavesDevice) {
                    Text(
                        "  off-phone",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                note?.let {
                    Text(
                        "  $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                preset.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SafeRouteCard(
    config: AgentConfig,
    check: DogoOptionsViewModel.ConnectionCheck,
    onEdit: ((AgentConfig) -> AgentConfig) -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showKey by remember { mutableStateOf(false) }
    Card(modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hermes with no tools (for notifications and screens)", style = MaterialTheme.typography.labelLarge)
            Text(
                "Notification and screen text can't be trusted, so it never goes to your normal Hermes, which has tools. " +
                    "Set up a second Hermes profile with every tool turned off, then enter its own address and key here. " +
                    "Dogo asks the server what tools it has and refuses the route unless the answer is none.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = config.baseUrl.orEmpty(),
                onValueChange = { value -> onEdit { it.copy(baseUrl = value) } },
                label = { Text("Address of the tools-off profile") },
                placeholder = { Text("https://your-host/p/dogosafe/v1") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = config.apiKey.orEmpty(),
                onValueChange = { value -> onEdit { it.copy(apiKey = value.trim()) } },
                label = { Text("Its own API key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onTest, enabled = check !is DogoOptionsViewModel.ConnectionCheck.Running) { Text("Test route") }
                if (check is DogoOptionsViewModel.ConnectionCheck.Running) {
                    CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
                }
            }
            when (check) {
                is DogoOptionsViewModel.ConnectionCheck.Ok -> Text(check.reply, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                is DogoOptionsViewModel.ConnectionCheck.Failed -> Text(check.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
        }
    }
}

@Composable
private fun RemoteAgentCard(
    preset: AgentPreset,
    config: AgentConfig,
    resolved: ResolvedAgent,
    connection: DogoOptionsViewModel.ConnectionCheck,
    onEdit: ((AgentConfig) -> AgentConfig) -> Unit,
    onTest: () -> Unit,
) {
    var showKey by remember(preset.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${preset.displayName} connection", style = MaterialTheme.typography.labelLarge)

            OutlinedTextField(
                value = config.baseUrl ?: preset.defaultBaseUrl,
                onValueChange = { value -> onEdit { it.copy(baseUrl = value) } },
                label = { Text("Address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = config.model ?: preset.defaultModel,
                onValueChange = { value -> onEdit { it.copy(model = value) } },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (preset.key != KeyRequirement.NONE) {
                OutlinedTextField(
                    value = config.apiKey.orEmpty(),
                    onValueChange = { value -> onEdit { it.copy(apiKey = value) } },
                    label = {
                        Text(if (preset.key == KeyRequirement.REQUIRED) "API key" else "API key (optional)")
                    },
                    supportingText = preset.keyHint.takeIf { it.isNotEmpty() }?.let { hint -> { Text(hint) } },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                "Requests go to ${resolved.baseUrl.ifBlank { "(no address yet)" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onTest,
                    enabled = connection !is DogoOptionsViewModel.ConnectionCheck.Running,
                ) { Text("Test connection") }
                if (connection is DogoOptionsViewModel.ConnectionCheck.Running) {
                    CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
                }
            }

            when (connection) {
                is DogoOptionsViewModel.ConnectionCheck.Ok -> {
                    Text(
                        "Connected. The agent replied: \"${connection.reply}\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (connection.models.isNotEmpty()) {
                        Text("Models on this server (tap to use):", style = MaterialTheme.typography.bodySmall)
                        connection.models.take(MAX_MODEL_CHOICES).forEach { id ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onEdit { it.copy(model = id) } }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (id == resolved.model) {
                                    Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                                }
                                Text(
                                    id,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 6.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                is DogoOptionsViewModel.ConnectionCheck.Failed -> Text(
                    connection.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> Unit
            }
        }
    }
}

private const val MAX_MODEL_CHOICES = 25

@Composable
private fun AccessRow(status: TierStatus, onRequest: () -> Unit) {
    val requestable = !status.available && (status.tier == Tier.SHIZUKU || status.tier == Tier.ROOT)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (requestable) Modifier.clickable(onClick = onRequest) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(status.tier.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                status.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            status.available -> Icon(Icons.Default.Check, "Available", tint = MaterialTheme.colorScheme.primary)
            requestable -> TextButton(onClick = onRequest) { Text("Allow") }
        }
    }
}

@Composable
private fun ThemesSection(
    state: DogoOptionsViewModel.ThemesState,
    onLoad: () -> Unit,
    onApply: (OmarchyThemeSource.ThemeRef) -> Unit,
    onApplyCommunity: (String) -> Unit,
    onWallpaperToggle: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var community by remember { mutableStateOf("") }
        Text(
            "Use an Omarchy desktop theme on Dogo: its light/dark mode, accent colour and wallpaper.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Also set the wallpaper", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = state.setWallpaper, onCheckedChange = onWallpaperToggle)
        }
        if (state.official.isEmpty()) {
            Button(onClick = onLoad, enabled = !state.loading) {
                Text(if (state.loading) "Loading…" else "Browse official themes")
            }
        }
        state.official.forEach { ref ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = state.applying == null) { onApply(ref) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(ref.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (state.applying == ref.name) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
        OutlinedTextField(
            value = community,
            onValueChange = { community = it },
            label = { Text("Community theme (owner/repo)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                TextButton(onClick = { onApplyCommunity(community) }, enabled = community.isNotBlank() && state.applying == null) {
                    Text("Apply")
                }
            },
        )
        state.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PluginsSection(
    plugins: List<DogoOptionsViewModel.PluginRow>,
    broken: List<PluginRegistry.Broken>,
    onToggle: (PluginRegistry.Installed, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (plugins.isEmpty() && broken.isEmpty()) {
            Text(
                "No plugins installed. Plugins are apps with a dogo-plugin.json; see docs/plugins.md.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        plugins.forEach { row ->
            val m = row.plugin.manifest
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${m.name} ${m.version}", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${m.author.ifBlank { row.plugin.packageName }} · ${m.license}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = row.enabled, onCheckedChange = { onToggle(row.plugin, it) })
                    }
                    if (m.description.isNotBlank()) Text(m.description, style = MaterialTheme.typography.bodySmall)
                    if (m.capabilities.isNotEmpty()) {
                        Text(
                            "Can: " + m.capabilities.joinToString { it.title.lowercase() },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (m.actions.isNotEmpty()) {
                        Text(
                            "Actions: " + m.actions.joinToString { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (row.needsReapproval) {
                        Text(
                            "Updated and now asks for something new. Switch it on again to approve.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        broken.forEach {
            Text(
                "${it.packageName}: ${it.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun TermuxAgentCard(
    selectedCli: String,
    termux: DogoOptionsViewModel.TermuxState,
    connection: DogoOptionsViewModel.ConnectionCheck,
    onPick: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onTest: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CLI agent in Termux", style = MaterialTheme.typography.labelLarge)
            when {
                !termux.installed -> Text(
                    "Termux isn't installed. Get it from F-Droid, then come back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                !termux.permitted -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Dogo needs permission to run commands in Termux.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRequestPermission) { Text("Allow") }
                }

                else -> Text(
                    "In Termux, make sure ~/.termux/termux.properties has allow-external-apps = true.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TermuxCli.all.forEach { cli ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onPick(cli.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selectedCli == cli.id, onClick = { onPick(cli.id) })
                    Column(Modifier.weight(1f)) {
                        Text(cli.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Install: ${cli.installHint}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onTest,
                    enabled = termux.installed && termux.permitted && connection !is DogoOptionsViewModel.ConnectionCheck.Running,
                ) { Text("Test connection") }
                if (connection is DogoOptionsViewModel.ConnectionCheck.Running) {
                    CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
                }
            }
            when (connection) {
                is DogoOptionsViewModel.ConnectionCheck.Ok -> Text(
                    "Connected. The agent replied: \"${connection.reply}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                is DogoOptionsViewModel.ConnectionCheck.Failed -> Text(
                    connection.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> Unit
            }
        }
    }
}

@Composable
private fun RingSection(config: RingConfig, onChange: ((RingConfig) -> RingConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Touch and hold to open the ring", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "A radial menu opens under your thumb. Slide to an app and let go. " +
                        "Long-press options (wallpaper, widgets) are under Dogo > Home.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = config.enabled, onCheckedChange = { on -> onChange { it.copy(enabled = on) } })
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            RingStyle.entries.forEachIndexed { i, style ->
                SegmentedButton(
                    selected = config.style == style,
                    onClick = { onChange { it.copy(style = style) } },
                    shape = SegmentedButtonDefaults.itemShape(i, RingStyle.entries.size),
                    enabled = config.enabled,
                    icon = {},
                ) { Text(style.title, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (config.isAutomatic) {
                    "Apps are grouped automatically. Ask Dogo to arrange them your way."
                } else {
                    "Your groups: " + config.groups.orEmpty().joinToString { it.name }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (!config.isAutomatic) {
                TextButton(onClick = { onChange { it.copy(groups = null) } }) { Text("Reset") }
            }
        }
    }
}

@Composable
private fun VoiceSection(
    config: VoiceConfig,
    check: DogoOptionsViewModel.ConnectionCheck,
    onChange: ((VoiceConfig) -> VoiceConfig) -> Unit,
    onUseHermes: () -> Unit,
    onTest: () -> Unit,
) {
    var showKey by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Talk to Dogo in real time with OpenAI's Realtime voice. Tap the mic in Dogo Setup. " +
                "What you say goes to OpenAI; changes still wait for your Apply.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = config.source == VoiceConfig.Source.TOKEN_SERVER,
                onClick = { onChange { it.copy(source = VoiceConfig.Source.TOKEN_SERVER) } },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Key on my computer") }
            SegmentedButton(
                selected = config.source == VoiceConfig.Source.API_KEY,
                onClick = { onChange { it.copy(source = VoiceConfig.Source.API_KEY) } },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Key on this phone") }
        }
        if (config.source == VoiceConfig.Source.TOKEN_SERVER) {
            Text(
                "Recommended. Your OpenAI key stays on your computer; the phone gets a key that expires in 10 minutes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onUseHermes) { Text("Use my Hermes server") }
            OutlinedTextField(
                value = config.tokenUrl,
                onValueChange = { v -> onChange { it.copy(tokenUrl = v.trim()) } },
                label = { Text("Token service") },
                placeholder = { Text("https://my-pc.ts.net:10000/dogo/realtime/token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = config.serverKey,
                onValueChange = { v -> onChange { it.copy(serverKey = v.trim()) } },
                label = { Text("Token service key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = config.apiKey,
                onValueChange = { v -> onChange { it.copy(apiKey = v.trim()) } },
                label = { Text("OpenAI API key") },
                placeholder = { Text("sk-...") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Stored encrypted on this phone. Anyone with the phone unlocked could use it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("Speaking voice", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RealtimeProtocol.VOICES.forEach { v ->
                androidx.compose.material3.FilterChip(
                    selected = config.voice == v,
                    onClick = { onChange { it.copy(voice = v) } },
                    label = { Text(v.replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onTest, enabled = check !is DogoOptionsViewModel.ConnectionCheck.Running) { Text("Test voice key") }
            if (check is DogoOptionsViewModel.ConnectionCheck.Running) {
                CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
            }
        }
        when (check) {
            is DogoOptionsViewModel.ConnectionCheck.Ok -> Text(check.reply, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            is DogoOptionsViewModel.ConnectionCheck.Failed -> Text(check.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
    }
}
