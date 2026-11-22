/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.setup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Photos for the agent: upright, at most [MAX_SIDE] pixels, JPEG, base64. */
object PhotoCodec {
    /** Enough to read a phone's layout, small enough to send over mobile data. */
    const val MAX_SIDE = 1280
    private const val QUALITY = 82

    fun encode(photo: Bitmap): String {
        val scaled = scale(photo, MAX_SIDE)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun thumbnail(photo: Bitmap): Bitmap = scale(photo, 480)

    private fun scale(photo: Bitmap, side: Int): Bitmap {
        val longest = max(photo.width, photo.height)
        if (longest <= side) return photo
        val f = side.toFloat() / longest
        return Bitmap.createScaledBitmap(photo, (photo.width * f).toInt().coerceAtLeast(1), (photo.height * f).toInt().coerceAtLeast(1), true)
    }

    /** Reads [uri] the right way up, decoded small enough not to run out of memory. */
    fun load(context: Context, uri: Uri): Bitmap? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (rotation == 0f) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
    }.onFailure { android.util.Log.w("DogoPhoto", "Couldn't read $uri", it) }.getOrNull()
}
