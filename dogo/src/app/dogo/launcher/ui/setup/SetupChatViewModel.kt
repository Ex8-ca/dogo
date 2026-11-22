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
import app.dogo.launcher.llm.HardwareProfile
import app.dogo.launcher.llm.ModelRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the setup chat: turns user text into model replies, parses any actions
 * the model proposes, and holds the transcript.
 *
 * Nothing is applied here — proposed actions are surfaced to the UI for the
 * user to approve, which is the whole point of the confirmation default in
 * [AgentOptions].
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
    )

    private val history = mutableListOf<Pair<String, String>>()
    private val runner = ModelRunner.forContext(application)
    private val optionsStore = app.dogo.launcher.ui.options.OptionsStore(application)
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refreshModelState()
        // If the user already picked (and downloaded) a model in Dogo Options,
        // load it so the chat is usable the moment it is opened.
        autoLoadSelectedModel()
    }

    private fun autoLoadSelectedModel() {
        val options = optionsStore.load()
        if (options.runtime == app.dogo.launcher.agent.AgentOptions.Runtime.CLOUD) return
        val recommendation = app.dogo.launcher.llm.HardwareProfile.fromDevice(getApplication())
        val variant = options.resolveModel(recommendation) ?: return
        if (runner.isAvailable(variant)) {
            loadModel(variant)
        }
    }

    private fun refreshModelState() {
        _uiState.update {
            it.copy(
                modelReady = runner.isReady(),
                modelName = runner.loadedVariant?.displayName,
                status = if (runner.isReady()) null else "No model loaded. Open Dogo options to download one.",
            )
        }
    }

    /** Loads [variant] so replies can be generated on-device. */
    fun loadModel(variant: app.dogo.launcher.llm.ModelCatalog.ModelVariant) {
        _uiState.update { it.copy(busy = true, status = "Loading ${variant.displayName}…") }
        viewModelScope.launch {
            val result = runner.load(variant)
            result
                .onSuccess { _uiState.update { it.copy(busy = false, status = null) } }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(busy = false, status = "Could not load the model: ${e.message}")
                    }
                }
            refreshModelState()
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _uiState.value.busy) return
        if (!runner.isReady()) {
            _uiState.update {
                it.copy(status = "Load a model first — open Dogo options to download one.")
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
        history += "user" to trimmed

        viewModelScope.launch {
            val prompt = SetupChatAgent.buildPrompt(trimmed, history.toList())
            val result = runner.generate(prompt)

            result
                .onSuccess { reply ->
                    val actions = listOf(ActionExecutor.parseAction(reply))
                        .filter { it !is Action.Unknown }
                    history += "assistant" to reply
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages + Message(reply, fromUser = false, proposedActions = actions),
                            busy = false,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(busy = false, status = "The model could not reply: ${e.message}")
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
