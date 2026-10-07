package app.dogo.launcher.llm

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.RandomAccessFile
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Filesystem placement and download-state checks for model weights.
 *
 * Takes a [File] rather than a Context so the logic is testable without an
 * Android runtime (see dogo/BLOCKERS.md — Robolectric is unavailable).
 */
class ModelDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var modelsDir: File
    private lateinit var downloader: ModelDownloader

    private fun setUp() {
        modelsDir = tmp.newFolder("models")
        downloader = ModelDownloader(modelsDir)
    }

    @Test
    fun `target path sits inside the models directory`() {
        setUp()
        val variant = ModelCatalog.variants().first()

        val target = downloader.targetPath(variant)

        assertThat(target.parentFile).isEqualTo(modelsDir)
        assertThat(target.name).isEqualTo(variant.filename)
    }

    @Test
    fun `target path creates the models directory on demand`() {
        val nested = File(tmp.root, "does/not/exist/yet")
        val fresh = ModelDownloader(nested)

        val target = fresh.targetPath(ModelCatalog.variants().first())

        assertThat(target.parentFile?.exists()).isTrue()
    }

    @Test
    fun `a filename with path separators cannot escape the models directory`() {
        setUp()
        val sneaky = ModelCatalog.variants().first().copy(filename = "../../escaped.gguf")

        val target = downloader.targetPath(sneaky)

        assertThat(target.canonicalPath).startsWith(modelsDir.canonicalPath + File.separator)
    }

    @Test
    fun `nothing is reported as downloaded before a download starts`() {
        setUp()
        ModelCatalog.variants().forEach { variant ->
            assertThat(downloader.isDownloaded(variant)).isFalse()
        }
    }

    @Test
    fun `a truncated file is not reported as downloaded`() {
        setUp()
        val variant = ModelCatalog.variants().first()
        downloader.targetPath(variant).writeBytes(ByteArray(64))

        // The variant's real size is far larger than this stub, so this must NOT
        // count as downloaded: loading a truncated GGUF crashes at parse time.
        assertThat(downloader.isDownloaded(variant)).isFalse()
    }

    @Test
    fun `a complete file is reported as downloaded`() {
        setUp()
        val variant = ModelCatalog.variants().first()
        // Sparse file: reserves the right length without writing 3 GB to disk.
        RandomAccessFile(downloader.targetPath(variant), "rw").use { it.setLength(variant.sizeBytes) }

        assertThat(downloader.isDownloaded(variant)).isTrue()
    }

    @Test
    fun `an in-progress part file is not reported as downloaded`() {
        setUp()
        val variant = ModelCatalog.variants().first()
        downloader.partFile(variant).writeBytes(ByteArray(128))

        assertThat(downloader.isDownloaded(variant)).isFalse()
    }

    @Test
    fun `delete removes both the part file and any finished weights`() {
        setUp()
        val variant = ModelCatalog.variants().first()
        downloader.partFile(variant).writeBytes(ByteArray(128))
        downloader.targetPath(variant).writeBytes(ByteArray(64))

        downloader.delete(variant)

        assertThat(downloader.partFile(variant).exists()).isFalse()
        assertThat(downloader.targetPath(variant).exists()).isFalse()
    }

    @Test
    fun `installed models are reported in catalog order`() {
        setUp()
        val variants = ModelCatalog.variants()
        variants.forEach { variant ->
            RandomAccessFile(downloader.targetPath(variant), "rw").use { it.setLength(variant.sizeBytes) }
        }

        assertThat(downloader.installed()).containsExactlyElementsIn(variants).inOrder()
    }

    @Test
    fun `downloading size is capped at the model size`() {
        val progress = ModelDownloader.DownloadProgress(1_000, 500)

        assertThat(progress.fraction).isEqualTo(1f)
    }

    @Test
    fun `progress with an unknown total does not divide by zero`() {
        val progress = ModelDownloader.DownloadProgress(1_000, -1)

        assertThat(progress.fraction).isEqualTo(0f)
    }
}
