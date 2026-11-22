/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.llm

/**
 * The set of on-device models Dogo can download and run.
 *
 * Weights are fetched at runtime as GGUF files and executed with llama.cpp
 * ([de.kherud.llama]), so nothing large is baked into the APK.
 *
 * Every entry here was verified against HuggingFace. Adding one is not a
 * copy-paste job — see [ModelCatalogTest], which pins the properties that have
 * to hold for the download path to work at all.
 */
object ModelCatalog {

    /** Licences we are willing to ship against. */
    enum class License(val spdxId: String, val fossEligible: Boolean) {
        /**
         * OSI-approved. F-Droid requires this, so anything not Apache-2.0 is
         * kept out of the catalog entirely rather than merely discouraged.
         */
        APACHE_2_0("Apache-2.0", true),

        /**
         * Google's custom Gemma licence: not OSI-approved, and the HuggingFace
         * repos return 401 until the user accepts Google's terms. Usable for a
         * personal build, never for an F-Droid release.
         */
        GEMMA("gemma", false),
    }

    data class ModelVariant(
        val id: String,
        val displayName: String,
        val hfRepo: String,
        val filename: String,
        /** Exact size in bytes, as reported by HuggingFace. */
        val sizeBytes: Long,
        /** Whole-device RAM needed before this model is worth attempting. */
        val minRamGb: Int,
        val license: License,
    ) {
        val downloadUrl: String
            get() = "https://huggingface.co/$hfRepo/resolve/main/$filename"

        val fossEligible: Boolean
            get() = license.fossEligible

        val sizeGb: Double
            get() = sizeBytes.toDouble() / BYTES_PER_GB
    }

    const val BYTES_PER_GB: Long = 1024L * 1024 * 1024

    /** Space kept free beyond the weights themselves, for scratch files. */
    const val STORAGE_HEADROOM_BYTES: Long = 512L * 1024 * 1024

    /**
     * All supported models, largest first.
     *
     * Ordered so the UI can present "best that fits" first without sorting.
     */
    fun variants(): List<ModelVariant> = listOf(
        ModelVariant(
            id = "gemma-4-e4b-q4",
            displayName = "Gemma 4 E4B (Q4)",
            hfRepo = "google/gemma-4-E4B-it-qat-q4_0-gguf",
            filename = "gemma-4-E4B_q4_0-it.gguf",
            sizeBytes = 5_154_941_280L,
            minRamGb = 12,
            license = License.APACHE_2_0,
        ),
        ModelVariant(
            id = "gemma-4-e2b-q4",
            displayName = "Gemma 4 E2B (Q4)",
            hfRepo = "google/gemma-4-E2B-it-qat-q4_0-gguf",
            filename = "gemma-4-E2B_q4_0-it.gguf",
            sizeBytes = 3_349_516_256L,
            minRamGb = 6,
            license = License.APACHE_2_0,
        ),
    )

    fun findById(id: String): ModelVariant? = variants().firstOrNull { it.id == id }

    /** Bytes of free storage needed to comfortably hold [variant]. */
    fun requiredStorageBytes(variant: ModelVariant): Long = variant.sizeBytes + STORAGE_HEADROOM_BYTES

    /** [requiredStorageBytes] rounded up to whole gigabytes, for device probes. */
    fun requiredStorageGb(variant: ModelVariant): Int = ((requiredStorageBytes(variant) + BYTES_PER_GB - 1) / BYTES_PER_GB).toInt()
}
