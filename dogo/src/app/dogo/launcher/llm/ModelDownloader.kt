package app.dogo.launcher.llm

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Downloads model weights from HuggingFace into app-private storage.
 *
 * Takes a [File] rather than a Context so the placement and state logic can be
 * unit tested without an Android runtime (see `dogo/BLOCKERS.md` — Robolectric
 * is currently unavailable). Use [forContext] from Android code.
 */
class ModelDownloader(private val modelsDir: File) {

    companion object {
        private const val MODELS_SUBDIR = "models"
        private const val PART_SUFFIX = ".part"
        private const val BUFFER_SIZE = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val USER_AGENT = "DogoPhoneLauncher"

        /** Bytes below which a completed file is treated as truncated. */
        private const val MIN_PLAUSIBLE_BYTES = 512L * 1024 * 1024

        /** How often download() emits an update. */
        const val PROGRESS_INTERVAL_BYTES = 4L * 1024 * 1024

        fun forContext(context: Context): ModelDownloader = ModelDownloader(File(context.filesDir, MODELS_SUBDIR))
    }

    data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long) {
        /** 0..1, clamped. Zero when the server did not report a total. */
        val fraction: Float
            get() = if (totalBytes <= 0) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    /** Final location the weights move to once fully downloaded. */
    fun targetPath(variant: ModelCatalog.ModelVariant): File {
        if (!modelsDir.exists()) modelsDir.mkdirs()
        // Reduce to a bare filename: a variant carrying separators must not be
        // able to write outside the models directory.
        return File(modelsDir, File(variant.filename).name)
    }

    /** In-progress download for [variant], resumable across app restarts. */
    fun partFile(variant: ModelCatalog.ModelVariant): File = File(targetPath(variant).path + PART_SUFFIX)

    /**
     * True only for a file that is present *and* the full advertised length.
     *
     * A short file means an interrupted download, and loading it would crash
     * the launcher rather than fail cleanly.
     */
    fun isDownloaded(variant: ModelCatalog.ModelVariant): Boolean {
        val file = targetPath(variant)
        return file.exists() &&
            file.length() >= maxOf(variant.sizeBytes, MIN_PLAUSIBLE_BYTES)
    }

    /** Removes both the partial and any finished download for [variant]. */
    fun delete(variant: ModelCatalog.ModelVariant) {
        partFile(variant).delete()
        targetPath(variant).delete()
    }

    /** Fully downloaded models, in catalog order. */
    fun installed(): List<ModelCatalog.ModelVariant> = ModelCatalog.variants().filter { isDownloaded(it) }

    /**
     * Downloads [variant], resuming a previous attempt if one exists.
     *
     * Emits progress roughly every [PROGRESS_INTERVAL_BYTES] so the UI stays
     * responsive without flooding it with thousands of updates over a multi-GB
     * transfer.
     */
    fun download(variant: ModelCatalog.ModelVariant): Flow<DownloadProgress> = flow {
        val target = targetPath(variant)
        val part = partFile(variant)

        if (isDownloaded(variant)) {
            emit(DownloadProgress(variant.sizeBytes, variant.sizeBytes))
            return@flow
        }

        var alreadyHave = if (part.exists()) part.length() else 0L
        val connection = open(variant.downloadUrl, alreadyHave)

        try {
            if (alreadyHave > 0 && connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                // Server ignored our Range header; a blind append would corrupt
                // the file, so start again.
                connection.disconnect()
                part.delete()
                alreadyHave = 0L
            }

            val total = resolveTotalBytes(connection, variant, alreadyHave)
            emit(DownloadProgress(alreadyHave, total))

            connection.inputStream.use { input ->
                part.outputStream().let { output ->
                    if (alreadyHave > 0) output.channel.position(alreadyHave)
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = alreadyHave
                    var lastEmit = alreadyHave

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        if (downloaded - lastEmit >= PROGRESS_INTERVAL_BYTES) {
                            emit(DownloadProgress(downloaded, total))
                            lastEmit = downloaded
                        }
                    }
                }
            }

            // Promote only once the bytes are actually all here.
            if (part.length() < variant.sizeBytes) {
                throw IOException(
                    "Incomplete download for ${variant.id}: " +
                        "got ${part.length()} of ${variant.sizeBytes} bytes",
                )
            }
            if (!part.renameTo(target)) {
                throw IOException("Could not move ${part.path} to ${target.path}")
            }

            emit(DownloadProgress(variant.sizeBytes, variant.sizeBytes))
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun open(url: String, resumeFrom: Long): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", USER_AGENT)
        if (resumeFrom > 0) {
            connection.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        connection.connect()
        return connection
    }

    private fun resolveTotalBytes(
        connection: HttpURLConnection,
        variant: ModelCatalog.ModelVariant,
        resumeFrom: Long,
    ): Long {
        val declared = connection.contentLengthLong
        // For a 206, contentLengthLong is the length of the *remaining* bytes.
        return if (declared > 0) resumeFrom + declared else variant.sizeBytes
    }
}
