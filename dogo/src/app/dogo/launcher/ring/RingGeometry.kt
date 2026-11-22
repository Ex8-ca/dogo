/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ring

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Where the slices of a ring are and which one a finger is on. Angles are in
 * degrees, screen coordinates (y grows down), so -90 is straight up. Slice 0
 * is centred at the top and the rest follow clockwise.
 */
class RingGeometry(val count: Int, val hubRadius: Float, val outerRadius: Float) {
    init {
        require(count >= 1) { "A ring needs at least one slice" }
        require(outerRadius > hubRadius && hubRadius >= 0f)
    }

    val sweep: Float = 360f / count

    fun centerAngle(index: Int): Float = -90f + index * sweep

    fun startAngle(index: Int): Float = centerAngle(index) - sweep / 2f

    /** Offset from the ring's centre of slice [index]'s icon, at [radius]. */
    fun position(index: Int, radius: Float = iconRadius): Pair<Float, Float> {
        val a = Math.toRadians(centerAngle(index).toDouble())
        return (cos(a) * radius).toFloat() to (sin(a) * radius).toFloat()
    }

    val iconRadius: Float get() = (hubRadius + outerRadius) / 2f

    /**
     * The slice under a finger at ([dx], [dy]) from the centre. [HUB] inside
     * the hub, [NONE] further out than [reach] (flicks past the rim still
     * count, which is what makes muscle-memory launching work).
     */
    fun hit(dx: Float, dy: Float, reach: Float = Float.POSITIVE_INFINITY): Int {
        val distance = hypot(dx, dy)
        if (distance < hubRadius) return HUB
        if (distance > reach) return NONE
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val relative = ((angle + 90f + sweep / 2f) % 360f + 360f) % 360f
        return floor(relative / sweep).toInt().coerceIn(0, count - 1)
    }

    companion object {
        const val HUB = -1
        const val NONE = -2

        /**
         * Moves a ring centre so a ring of [extent] radius fits inside the
         * given bounds. If it can't fit, it is centred on that axis.
         */
        fun clampCenter(x: Float, y: Float, extent: Float, left: Float, top: Float, right: Float, bottom: Float): Pair<Float, Float> {
            fun axis(v: Float, lo: Float, hi: Float) = if (hi - lo < 2 * extent) (lo + hi) / 2f else v.coerceIn(lo + extent, hi - extent)
            return axis(x, left, right) to axis(y, top, bottom)
        }
    }
}
