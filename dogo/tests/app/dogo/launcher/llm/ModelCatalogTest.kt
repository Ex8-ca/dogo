package app.dogo.launcher.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the model catalog against the assumptions that broke the original
 * plan: guessed download URLs, guessed file sizes, and weights that turn out
 * to sit behind a licence click-through.
 *
 * Every URL and size here was checked against HuggingFace. The download URLs
 * must stay anonymously reachable — Dogo ships no model in the APK and has no
 * account system, so a 401 would make the model path unusable.
 */
class ModelCatalogTest {

    private val variants = ModelCatalog.variants()

    @Test
    fun `catalog is not empty`() {
        assertThat(variants).isNotEmpty()
    }

    @Test
    fun `ids are unique`() {
        assertThat(variants.map { it.id }.toSet()).hasSize(variants.size)
    }

    @Test
    fun `filenames are unique so downloads cannot collide`() {
        assertThat(variants.map { it.filename }.toSet()).hasSize(variants.size)
    }

    @Test
    fun `download urls point at google's official huggingface repos`() {
        variants.forEach { variant ->
            assertThat(variant.hfRepo).startsWith("google/")
            assertThat(variant.downloadUrl)
                .isEqualTo("https://huggingface.co/${variant.hfRepo}/resolve/main/${variant.filename}")
        }
    }

    @Test
    fun `download urls are https`() {
        variants.forEach { variant ->
            assertThat(variant.downloadUrl).startsWith("https://")
        }
    }

    @Test
    fun `files are gguf as required by the llama runtime`() {
        variants.forEach { variant ->
            assertThat(variant.filename).endsWith(".gguf")
        }
    }

    @Test
    fun `declared sizes are plausible for a q4 llama model`() {
        variants.forEach { variant ->
            assertThat(variant.sizeBytes).isGreaterThan(500L * 1024 * 1024)
        }
    }

    @Test
    fun `every model is redistributable under a permissive licence`() {
        // Dogo ships on F-Droid, which requires a FOSS licence. Gemma 3's custom
        // licence is not OSI-approved, and those repos additionally 401 without
        // accepting Google terms, so they must never re-enter this catalog.
        variants.forEach { variant ->
            assertThat(variant.license).isEqualTo(ModelCatalog.License.APACHE_2_0)
            assertThat(variant.fossEligible).isTrue()
        }
    }

    @Test
    fun `no catalog entry carries an access token in its url`() {
        variants.forEach { variant ->
            assertThat(variant.downloadUrl).doesNotContain("token=")
        }
    }

    @Test
    fun `lookup by id returns the requested variant`() {
        val target = variants.first()
        assertThat(ModelCatalog.findById(target.id)).isEqualTo(target)
    }

    @Test
    fun `lookup by unknown id returns null`() {
        assertThat(ModelCatalog.findById("gemma-4-e2b")).isNull()
        assertThat(ModelCatalog.findById("")).isNull()
    }

    @Test
    fun `ordering runs largest model first`() {
        val sizes = variants.map { it.sizeBytes }
        assertThat(sizes).isEqualTo(sizes.sortedDescending())
    }
}
