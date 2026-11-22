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
import app.dogo.launcher.llm.HardwareProfile
import app.dogo.launcher.llm.ModelCatalog
import app.dogo.launcher.llm.ModelDownloader
import app.dogo.launcher.privilege.SystemAccess
import app.dogo.launcher.privilege.Tier
import app.dogo.launcher.privilege.TierStatus
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

    data class UiState(
        val options: AgentOptions = AgentOptions(),
        val connection: ConnectionCheck = ConnectionCheck.Idle,
        val access: List<TierStatus> = emptyList(),
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

    private val _uiState = MutableStateFlow(UiState(options = store.load()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        val recommendation = HardwareProfile.fromDevice(application)
        _uiState.update { it.copy(recommendation = recommendation) }
        refresh()
        refreshAccess()
    }

    /** Re-reads which access tiers are available. Cheap; call on resume. */
    fun refreshAccess() {
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
        val backend = AgentBackends.remote(agent) ?: return
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
