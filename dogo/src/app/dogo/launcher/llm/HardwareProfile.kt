/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs

/**
 * Chooses the largest model a device can plausibly run.
 *
 * The bar is deliberately conservative. Asking for more RAM than the device has
 * gets the model memory-mapped into a process that then gets killed — which,
 * because this is the launcher, means the user is left with no home screen at
 * all. Answering with a smaller model is a much cheaper failure.
 */
object HardwareProfile {

    data class Recommendation(
        /** Best model that fits, or null when nothing fits. */
        val primary: ModelCatalog.ModelVariant?,
        /** True when [primary] is null and the user needs the cloud tier. */
        val cloudOnly: Boolean,
        val rationale: String,
        /** RAM the recommendation was computed from, so callers can re-check a
         *  user's own pick against the same numbers. */
        val ramGb: Int,
        /** Free storage the recommendation was computed from. */
        val freeStorageGb: Int,
    )

    /**
     * Whether [variant] can run on a device with [ramGb] of RAM and
     * [freeStorageGb] free.
     *
     * Public so that a model the user picked by hand is held to exactly the
     * same bar as one this class picked.
     */
    fun fits(variant: ModelCatalog.ModelVariant, ramGb: Int, freeStorageGb: Int): Boolean = variant.minRamGb <= ramGb &&
        ModelCatalog.requiredStorageBytes(variant) <= freeStorageGb.toLong() * ModelCatalog.BYTES_PER_GB

    /**
     * Picks a model for a device reporting [ramGb] of RAM and [freeStorageGb] free.
     *
     * [hasNpu] is accepted for API symmetry but deliberately does not influence
     * the result: we cannot reliably detect one, and letting an optimistic guess
     * skip the RAM ceiling is how the previous implementation ended up always
     * promoting to the largest model.
     */
    fun recommendModel(ramGb: Int, freeStorageGb: Int, hasNpu: Boolean = false): Recommendation {
        val fit = ModelCatalog.variants()
            .filter { fits(it, ramGb, freeStorageGb) }
            .maxByOrNull { it.sizeBytes }

        return when {
            fit != null -> Recommendation(
                primary = fit,
                cloudOnly = false,
                rationale = "This device has room for ${fit.displayName} " +
                    "(${"%.1f".format(fit.sizeGb)} GB).",
                ramGb = ramGb,
                freeStorageGb = freeStorageGb,
            )

            ramGb < ModelCatalog.variants().minOf { it.minRamGb } -> Recommendation(
                primary = null,
                cloudOnly = true,
                rationale = "This device has $ramGb GB of RAM, which is below the " +
                    "${ModelCatalog.variants().minOf { it.minRamGb }} GB needed to run a model " +
                    "on-device. Setup will use the cloud tier instead.",
                ramGb = ramGb,
                freeStorageGb = freeStorageGb,
            )

            else -> Recommendation(
                primary = null,
                cloudOnly = true,
                rationale = "This device needs about " +
                    "${ModelCatalog.variants().minOf { ModelCatalog.requiredStorageGb(it) }} GB of " +
                    "free storage to run a model on-device, but only $freeStorageGb GB is free. " +
                    "Free up space, or use the cloud tier.",
                ramGb = ramGb,
                freeStorageGb = freeStorageGb,
            )
        }
    }

    /** Samples the device this is running on. */
    fun fromDevice(context: Context): Recommendation {
        val memoryInfo = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.getMemoryInfo(memoryInfo)

        return recommendModel(
            ramGb = (memoryInfo.totalMem / ModelCatalog.BYTES_PER_GB).toInt(),
            freeStorageGb = freeStorageGb(),
            hasNpu = false,
        )
    }

    private fun freeStorageGb(): Int {
        val stat = StatFs(Environment.getDataDirectory().path)
        return (stat.availableBytes / ModelCatalog.BYTES_PER_GB).toInt()
    }
}
