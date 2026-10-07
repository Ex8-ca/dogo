package app.dogo.launcher.llm

import android.content.Context
import de.kherud.llama.InferenceParameters
import de.kherud.llama.LlamaModel
import de.kherud.llama.ModelParameters
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Runs on-device text generation with llama.cpp.
 *
 * Weights are GGUF files downloaded by [ModelDownloader]; see [ModelCatalog]
 * for why. Loading maps the model into this process, so exactly one variant
 * may be resident at a time and [close] matters — leaking a model holds several
 * gigabytes of the launcher's memory budget.
 *
 * All generation is blocking; it is moved off the main thread by [generate] and
 * [generateTokens]. Loading in [load] is deliberately *not* dispatched, because
 * callers want to see the failure inline.
 */
class ModelRunner(private val modelsDir: File) {

    companion object {
        /** Context window in tokens. Setup conversations are short. */
        const val DEFAULT_CONTEXT_TOKENS = 4_096

        /** Upper bound on generated tokens for a single reply. */
        const val DEFAULT_MAX_TOKENS = 512

        private const val MODELS_SUBDIR = "models"
        private const val DEFAULT_TEMPERATURE = 0.7f
        private const val THREADS = 4
        private const val GPU_LAYERS = 0

        fun forContext(context: Context): ModelRunner = ModelRunner(File(context.filesDir, MODELS_SUBDIR))
    }

    private var model: LlamaModel? = null
    private var loaded: ModelCatalog.ModelVariant? = null

    /** The model currently loaded, if any. */
    val loadedVariant: ModelCatalog.ModelVariant?
        get() = loaded

    fun isReady(): Boolean = model != null

    /** Resolves the weights for [variant] without loading them. */
    fun modelFile(variant: ModelCatalog.ModelVariant): File = File(modelsDir, File(variant.filename).name)

    fun isAvailable(variant: ModelCatalog.ModelVariant): Boolean = modelFile(variant).let { it.exists() && it.length() >= variant.sizeBytes }

    /**
     * Loads [variant] into memory, replacing any already-loaded model.
     *
     * Returns failure rather than throwing so callers can show the reason.
     */
    fun load(variant: ModelCatalog.ModelVariant): Result<Unit> {
        val file = modelFile(variant)
        if (!isAvailable(variant)) {
            return Result.failure(IllegalStateException("Model ${variant.id} is not downloaded"))
        }

        return try {
            // Release the previous model before mapping a new one, otherwise the
            // two coexist and the process can be OOM-killed.
            close()

            val parameters = ModelParameters()
                .setModel(file.absolutePath)
                .setCtxSize(DEFAULT_CONTEXT_TOKENS)
                .setThreads(THREADS)
                .setThreadsBatch(THREADS)
                .setGpuLayers(GPU_LAYERS)

            model = LlamaModel(parameters)
            loaded = variant
            Result.success(Unit)
        } catch (e: Throwable) {
            model = null
            loaded = null
            Result.failure(e)
        }
    }

    /** Releases the model and its mapped weights. Safe to call repeatedly. */
    fun close() {
        model?.close()
        model = null
        loaded = null
    }

    /** Generates a complete reply, blocking until finished. */
    fun generate(prompt: String): Result<String> = try {
        Result.success(requireModel().complete(parametersFor(prompt)))
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /**
     * Streams generated tokens.
     *
     * Emits deltas, so a consumer should append them rather than replace.
     */
    fun generateTokens(prompt: String): Flow<String> = flow {
        val active = requireModel()
        val iterable = active.generate(parametersFor(prompt))
        for (output in iterable) {
            emit(output.text)
        }
    }.flowOn(Dispatchers.IO)

    private fun requireModel(): LlamaModel = model ?: throw IllegalStateException("No model loaded; call load() first")

    private fun parametersFor(prompt: String) = InferenceParameters(prompt)
        .setNPredict(DEFAULT_MAX_TOKENS)
        .setTemperature(DEFAULT_TEMPERATURE)
}
