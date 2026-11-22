/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.options

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.dogo.launcher.agent.AgentOptions
import app.dogo.launcher.agent.AgentOptions.ActionType
import app.dogo.launcher.agent.backend.AgentBackends
import app.dogo.launcher.agent.backend.AgentConfig
import app.dogo.launcher.agent.backend.AgentPreset
import app.dogo.launcher.agent.backend.ChatTurn
import app.dogo.launcher.agent.backend.ModelLister
import app.dogo.launcher.agent.backend.Termux
import app.dogo.launcher.llm.HardwareProfile
import app.dogo.launcher.llm.ModelCatalog
import app.dogo.launcher.llm.ModelDownloader
import app.dogo.launcher.plugins.PluginRegistry
import app.dogo.launcher.plugins.PluginStore
import app.dogo.launcher.privilege.SystemAccess
import app.dogo.launcher.privilege.Tier
import app.dogo.launcher.privilege.TierStatus
import app.dogo.launcher.ring.DogoRing
import app.dogo.launcher.ring.RingConfig
import app.dogo.launcher.themes.OmarchyThemeSource
import app.dogo.launcher.voice.VoiceConfig
import app.dogo.launcher.voice.VoiceStore
import app.dogo.launcher.voice.VoiceTokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the Dogo options screen: which agent answers, which on-device model it
 * uses, and what the agent is allowed to change.
 *
 * Persistence is deliberately simple — the options are a handful of scalars,
 * and a launcher must not fail to start because its settings file is corrupt.
 */
class DogoOptionsViewModel(
    application: Application,
) : AndroidViewModel(application) {

    data class ModelRow(
        val variant: ModelCatalog.ModelVariant,
        val downloaded: Boolean,
        val recommended: Boolean,
        val fits: Boolean,
        val downloading: Boolean = false,
        val progress: Float = 0f,
    )

    /** Result of the last "Test connection" for the selected agent. */
    sealed interface ConnectionCheck {
        data object Idle : ConnectionCheck
        data object Running : ConnectionCheck
        data class Ok(val reply: String, val models: List<String>) : ConnectionCheck
        data class Failed(val message: String) : ConnectionCheck
    }

    data class TermuxState(val installed: Boolean = false, val permitted: Boolean = false)

    data class PluginRow(val plugin: PluginRegistry.Installed, val enabled: Boolean, val needsReapproval: Boolean)

    data class ThemesState(
        val loading: Boolean = false,
        val official: List<OmarchyThemeSource.ThemeRef> = emptyList(),
        val applying: String? = null,
        val message: String? = null,
        val isError: Boolean = false,
        val setWallpaper: Boolean = true,
    )

    data class UiState(
        val options: AgentOptions = AgentOptions(),
        val connection: ConnectionCheck = ConnectionCheck.Idle,
        val access: List<TierStatus> = emptyList(),
        val plugins: List<PluginRow> = emptyList(),
        val brokenPlugins: List<PluginRegistry.Broken> = emptyList(),
        val themes: ThemesState = ThemesState(),
        val termux: TermuxState = TermuxState(),
        val ring: RingConfig = RingConfig(),
        val voice: VoiceConfig = VoiceConfig(),
        val voiceCheck: ConnectionCheck = ConnectionCheck.Idle,
        val recommendation: HardwareProfile.Recommendation? = null,
        val models: List<ModelRow> = emptyList(),
        val activeDownload: String? = null,
    ) {
        val issues: List<AgentOptions.Issue>
            get() = recommendation?.let { options.validate(it) }.orEmpty()
    }

    private val downloader = ModelDownloader.forContext(application)
    private val store = OptionsStore(application)
    private var downloadJob: Job? = null
    private var connectionJob: Job? = null
    private val systemAccess = SystemAccess(application)
    private val pluginRegistry = PluginRegistry(application)
    private val pluginStore = PluginStore(application)
    private val themeSource = OmarchyThemeSource()

    // Declared before init: refreshAccess() in init reads it.
    private val voiceStore = VoiceStore(application)
    private val _uiState = MutableStateFlow(UiState(options = store.load()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        val recommendation = HardwareProfile.fromDevice(application)
        _uiState.update { it.copy(recommendation = recommendation) }
        refresh()
        refreshAccess()
        refreshPlugins()
    }

    fun refreshPlugins() {
        viewModelScope.launch {
            val scan = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pluginRegistry.scan() }
            _uiState.update { state ->
                state.copy(
                    plugins = scan.plugins.map { PluginRow(it, pluginStore.isEnabled(it), pluginStore.needsReapproval(it)) },
                    brokenPlugins = scan.broken,
                )
            }
        }
    }

    fun setPluginEnabled(plugin: PluginRegistry.Installed, enabled: Boolean) {
        pluginStore.setEnabled(plugin, enabled)
        refreshPlugins()
    }

    fun loadOfficialThemes() {
        if (_uiState.value.themes.loading) return
        _uiState.update { it.copy(themes = it.themes.copy(loading = true, message = null)) }
        viewModelScope.launch {
            themeSource.listOfficial().fold(
                onSuccess = { refs -> _uiState.update { it.copy(themes = it.themes.copy(loading = false, official = refs)) } },
                onFailure = { e ->
                    _uiState.update { it.copy(themes = it.themes.copy(loading = false, message = e.message, isError = true)) }
                },
            )
        }
    }

    fun setThemeWallpaper(enabled: Boolean) {
        _uiState.update { it.copy(themes = it.themes.copy(setWallpaper = enabled)) }
    }

    fun applyTheme(ref: OmarchyThemeSource.ThemeRef) = applyThemeRef(ref)

    /** Accepts `owner/repo` or a GitHub URL for a community theme. */
    fun applyCommunityTheme(input: String) {
        val ref = themeSource.parseCommunityRef(input)
        if (ref == null) {
            _uiState.update {
                it.copy(themes = it.themes.copy(message = "Enter a GitHub repo like owner/omarchy-xyz-theme.", isError = true))
            }
            return
        }
        applyThemeRef(ref)
    }

    private fun applyThemeRef(ref: OmarchyThemeSource.ThemeRef) {
        if (_uiState.value.themes.applying != null) return
        _uiState.update { it.copy(themes = it.themes.copy(applying = ref.name, message = null)) }
        val withWallpaper = _uiState.value.themes.setWallpaper
        viewModelScope.launch {
            val result = runCatching {
                val loaded = themeSource.load(ref).getOrThrow()
                themeSource.apply(getApplication(), loaded, withWallpaper)
            }
            _uiState.update {
                it.copy(
                    themes = it.themes.copy(
                        applying = null,
                        message = result.getOrNull() ?: result.exceptionOrNull()?.message,
                        isError = result.isFailure,
                    ),
                )
            }
        }
    }

    /**
     * RUN_COMMAND is a runtime permission Termux defines, so it has to be
     * requested from an Activity; the screen does that and calls back here.
     */
    var onRequestTermuxPermission: (() -> Unit)? = null

    fun updateVoice(change: (VoiceConfig) -> VoiceConfig) {
        val next = change(_uiState.value.voice)
        voiceStore.save(next)
        _uiState.update { it.copy(voice = next, voiceCheck = ConnectionCheck.Idle) }
    }

    /** Fills in the token service next to the Hermes address, with the Hermes key. */
    fun useHermesForVoice() {
        val hermes = _uiState.value.options.agentConfigs[app.dogo.launcher.agent.backend.AgentCatalog.HERMES.id]
        val url = hermes?.baseUrl?.let { VoiceConfig.tokenUrlNextTo(it) }
        val key = hermes?.apiKey
        if (url == null || key.isNullOrBlank()) {
            _uiState.update { it.copy(voiceCheck = ConnectionCheck.Failed("Set up Hermes Agent first (https address and key), or enter the token service by hand.")) }
            return
        }
        updateVoice { it.copy(source = VoiceConfig.Source.TOKEN_SERVER, tokenUrl = url, serverKey = key) }
    }

    fun testVoice() {
        _uiState.update { it.copy(voiceCheck = ConnectionCheck.Running) }
        viewModelScope.launch {
            VoiceTokens.fetch(_uiState.value.voice)
                .onSuccess { token ->
                    val note = if (token.startsWith("ek_")) "Got a 10-minute voice key. Ready to talk." else "Key saved. It's checked when you start talking."
                    _uiState.update { it.copy(voiceCheck = ConnectionCheck.Ok(note, emptyList())) }
                }
                .onFailure { e -> _uiState.update { it.copy(voiceCheck = ConnectionCheck.Failed(e.message ?: "Couldn't get a voice key.")) } }
        }
    }

    fun updateRing(change: (RingConfig) -> RingConfig) {
        val app = getApplication<Application>()
        DogoRing.update(app, change)
        _uiState.update { it.copy(ring = DogoRing.config(app)) }
    }

    fun requestTermuxPermission() {
        onRequestTermuxPermission?.invoke()
    }

    /** Re-reads which access tiers are available. Cheap; call on resume. */
    fun refreshAccess() {
        val app = getApplication<Application>()
        _uiState.update {
            it.copy(
                termux = TermuxState(Termux.isInstalled(app), Termux.hasPermission(app)),
                ring = DogoRing.config(app),
                voice = voiceStore.load(),
            )
        }
        viewModelScope.launch {
            val statuses = systemAccess.statuses()
            _uiState.update { it.copy(access = statuses) }
        }
    }

    /** Asks Shizuku or the root manager for access, then refreshes. */
    fun requestAccess(tier: Tier) {
        viewModelScope.launch {
            systemAccess.request(tier)
            refreshAccess()
        }
    }

    private fun refresh() {
        val recommendation = _uiState.value.recommendation ?: return
        val downloadingId = _uiState.value.activeDownload
        _uiState.update { state ->
            state.copy(
                models = ModelCatalog.variants().map { variant ->
                    ModelRow(
                        variant = variant,
                        downloaded = downloader.isDownloaded(variant),
                        recommended = recommendation.primary?.id == variant.id,
                        fits = HardwareProfile.fits(
                            variant,
                            recommendation.ramGb,
                            recommendation.freeStorageGb,
                        ),
                    )
                },
                activeDownload = downloadingId,
            )
        }
    }

    private fun updateOptions(transform: (AgentOptions) -> AgentOptions) {
        val updated = transform(_uiState.value.options)
        store.save(updated)
        _uiState.update { it.copy(options = updated) }
    }

    fun selectAgent(preset: AgentPreset) {
        connectionJob?.cancel()
        updateOptions { it.copy(agentId = preset.id) }
        _uiState.update { it.copy(connection = ConnectionCheck.Idle) }
    }

    /** Saves edits to the selected agent's address, model or key. */
    fun updateAgentConfig(transform: (AgentConfig) -> AgentConfig) {
        updateOptions { options -> options.withConfig(options.agent, transform(options.configFor(options.agent))) }
        if (_uiState.value.connection !is ConnectionCheck.Running) {
            _uiState.update { it.copy(connection = ConnectionCheck.Idle) }
        }
    }

    /**
     * Sends one real request to the selected agent, and lists its models when
     * the server supports that, so a broken address or key shows up here
     * rather than halfway through setup.
     */
    fun testConnection() {
        val agent = _uiState.value.options.resolvedAgent()
        val problem = agent.problems().firstOrNull()
        if (problem != null) {
            _uiState.update { it.copy(connection = ConnectionCheck.Failed(problem.message)) }
            return
        }
        val backend = AgentBackends.create(getApplication(), agent) ?: return
        connectionJob?.cancel()
        _uiState.update { it.copy(connection = ConnectionCheck.Running) }
        connectionJob = viewModelScope.launch {
            val models = (backend as? ModelLister)?.listModels()?.getOrNull().orEmpty()
            val check = backend.reply(
                system = "You are being tested by the Dogo launcher. Reply with exactly: Dogo connected",
                turns = listOf(ChatTurn(ChatTurn.Role.USER, "Connection test. Reply with: Dogo connected")),
            ).fold(
                onSuccess = { ConnectionCheck.Ok(it.trim().take(120), models) },
                onFailure = { e ->
                    val hint = if (models.isNotEmpty()) " Available models: ${models.take(8).joinToString()}" else ""
                    ConnectionCheck.Failed((e.message ?: "The agent didn't answer.") + hint)
                },
            )
            _uiState.update { it.copy(connection = check) }
        }
    }

    fun setThinkingLevel(level: AgentOptions.ThinkingLevel) = updateOptions { it.copy(thinkingLevel = level) }

    fun setRequireConfirmation(required: Boolean) = updateOptions { it.copy(requireConfirmation = required) }

    fun toggleAction(type: ActionType) = updateOptions { current ->
        val updated = current.allowedActions.toMutableSet()
        if (!updated.add(type)) updated.remove(type)
        current.copy(allowedActions = updated)
    }

    /** Passing null clears the pin and returns to following the hardware. */
    fun setModel(variant: ModelCatalog.ModelVariant?) {
        updateOptions { it.copy(modelId = variant?.id) }
    }

    fun download(variant: ModelCatalog.ModelVariant) {
        if (_uiState.value.activeDownload != null) return
        _uiState.update { state ->
            state.copy(
                activeDownload = variant.id,
                models = state.models.map {
                    if (it.variant.id == variant.id) it.copy(downloading = true, progress = 0f) else it
                },
            )
        }
        downloadJob = viewModelScope.launch {
            try {
                downloader.download(variant).collect { progress ->
                    _uiState.update { state ->
                        state.copy(
                            models = state.models.map {
                                if (it.variant.id == variant.id) {
                                    it.copy(downloading = true, progress = progress.fraction)
                                } else {
                                    it
                                }
                            },
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(activeDownload = null) }
            }
            _uiState.update { it.copy(activeDownload = null) }
            refresh()
        }
    }

    fun delete(variant: ModelCatalog.ModelVariant) {
        downloader.delete(variant)
        refresh()
    }

    override fun onCleared() {
        super.onCleared()
        downloadJob?.cancel()
        connectionJob?.cancel()
    }
}
