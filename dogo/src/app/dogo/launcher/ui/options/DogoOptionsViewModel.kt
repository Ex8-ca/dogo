package app.dogo.launcher.ui.options

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.dogo.launcher.agent.AgentOptions
import app.dogo.launcher.agent.AgentOptions.ActionType
import app.dogo.launcher.llm.HardwareProfile
import app.dogo.launcher.llm.ModelCatalog
import app.dogo.launcher.llm.ModelDownloader
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the Dogo options screen: which model to use, where it runs, and what
 * the agent is allowed to change.
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

    data class UiState(
        val options: AgentOptions = AgentOptions(),
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

    private val _uiState = MutableStateFlow(UiState(options = store.load()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        val recommendation = HardwareProfile.fromDevice(application)
        _uiState.update { it.copy(recommendation = recommendation) }
        refresh()
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

    fun setRuntime(runtime: AgentOptions.Runtime) = updateOptions { it.copy(runtime = runtime) }

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
    }
}
