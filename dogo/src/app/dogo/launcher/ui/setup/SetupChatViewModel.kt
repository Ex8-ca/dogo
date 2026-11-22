/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.dogo.launcher.agent.Action
import app.dogo.launcher.agent.ActionExecutor
import app.dogo.launcher.agent.AgentOptions
import app.dogo.launcher.agent.backend.AgentBackend
import app.dogo.launcher.agent.backend.AgentBackends
import app.dogo.launcher.agent.backend.ChatTurn
import app.dogo.launcher.agent.backend.OnDeviceBackend
import app.dogo.launcher.llm.HardwareProfile
import app.dogo.launcher.llm.ModelCatalog
import app.dogo.launcher.llm.ModelRunner
import app.dogo.launcher.ui.options.OptionsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the setup chat: sends the conversation to the chosen agent, parses
 * any actions it proposes, and holds the transcript.
 *
 * Nothing is applied here. Proposed actions are shown to the user to approve,
 * and they are checked against the same [AgentOptions] gates whichever agent
 * proposed them.
 */
class SetupChatViewModel(
    application: Application,
) : AndroidViewModel(application) {

    data class Message(
        val text: String,
        val fromUser: Boolean,
        val proposedActions: List<Action> = emptyList(),
    )

    data class UiState(
        val messages: List<Message> = emptyList(),
        val busy: Boolean = false,
        val modelReady: Boolean = false,
        val modelName: String? = null,
        val status: String? = null,
        val options: AgentOptions = AgentOptions(),
    )

    private val turns = mutableListOf<ChatTurn>()
    private val runner = ModelRunner.forContext(application)
    private val optionsStore = OptionsStore(application)
    private var backend: AgentBackend? = null
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        reloadOptions()
    }

    /**
     * Re-reads the options and switches agent if they changed. The chat screen
     * calls this when it resumes, so a change made in Dogo Options applies on
     * return without restarting the launcher.
     */
    fun reloadOptions() {
        if (_uiState.value.busy) return
        val options = optionsStore.load()
        _uiState.update { it.copy(options = options) }

        if (options.isOnDevice) {
            backend = OnDeviceBackend(runner)
            // If a model was already picked and downloaded, load it so the
            // chat is usable the moment it opens.
            val variant = options.resolveModel(HardwareProfile.fromDevice(getApplication()))
            if (variant != null && runner.loadedVariant?.id != variant.id && runner.isAvailable(variant)) {
                loadModel(variant)
            } else {
                refreshOnDeviceReadiness()
            }
        } else {
            // A resident local model holds gigabytes the launcher can't spare.
            runner.close()
            val agent = options.resolvedAgent()
            val problem = agent.problems().firstOrNull()
            backend = if (problem == null) AgentBackends.remote(agent) else null
            _uiState.update {
                it.copy(
                    modelReady = backend != null,
                    modelName = backend?.label,
                    status = problem?.let { p ->
                        "${agent.preset.displayName} isn't set up yet. ${p.message}"
                    },
                )
            }
        }
    }

    private fun refreshOnDeviceReadiness() {
        _uiState.update {
            it.copy(
                modelReady = runner.isReady(),
                modelName = runner.loadedVariant?.displayName,
                status = if (runner.isReady()) {
                    null
                } else {
                    "No model loaded. Open Dogo Options to download one, or choose another agent."
                },
            )
        }
    }

    /** Loads [variant] so replies can be generated on-device. */
    fun loadModel(variant: ModelCatalog.ModelVariant) {
        _uiState.update { it.copy(busy = true, status = "Loading ${variant.displayName}…") }
        viewModelScope.launch {
            runner.load(variant)
                .onSuccess { _uiState.update { it.copy(busy = false, status = null) } }
                .onFailure { e ->
                    _uiState.update { it.copy(busy = false, status = "Could not load the model: ${e.message}") }
                }
            refreshOnDeviceReadiness()
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _uiState.value.busy) return
        val active = backend
        if (active == null || !_uiState.value.modelReady) {
            _uiState.update {
                it.copy(status = it.status ?: "No agent is ready. Open Dogo Options to choose one.")
            }
            return
        }

        _uiState.update { state ->
            state.copy(
                messages = state.messages + Message(trimmed, fromUser = true),
                busy = true,
                status = null,
            )
        }
        turns += ChatTurn(ChatTurn.Role.USER, trimmed)

        viewModelScope.launch {
            active.reply(SetupChatAgent.systemPrompt, turns.toList())
                .onSuccess { reply ->
                    turns += ChatTurn(ChatTurn.Role.ASSISTANT, reply)
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages + Message(
                                reply,
                                fromUser = false,
                                proposedActions = ActionExecutor.parseActions(reply),
                            ),
                            busy = false,
                        )
                    }
                }
                .onFailure { e ->
                    // Keep what the user typed on screen, but take the turn out
                    // of the history so a retry doesn't send it twice.
                    turns.removeAt(turns.lastIndex)
                    _uiState.update {
                        it.copy(busy = false, status = "The agent couldn't reply: ${e.message}")
                    }
                }
        }
    }

    fun dismissStatus() {
        _uiState.update { it.copy(status = null) }
    }

    override fun onCleared() {
        super.onCleared()
        runner.close()
    }
}
