/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ring

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// The ring home screen: apps sit on concentric dials anchored to one edge of
// the screen, and you spin them with your thumb. Plain Kotlin so the geometry
// can be unit tested off-device (see RingHomeTest).

/** Which hand holds the phone. The whole home screen mirrors. */
enum class HomeHand(val wireName: String, val title: String) {
    RIGHT("right", "Right hand"),
    LEFT("left", "Left hand"),
    ;

    companion object {
        fun fromWire(value: String?): HomeHand? = entries.firstOrNull { it.wireName == value?.trim()?.lowercase() }
    }
}

/**
 * One spinning dial: [count] slots spaced evenly around a full turn, offset by
 * [rotation]. The dial has detents, so letting go always settles a slot on the
 * angle the finger left it at.
 */
class Dial(val count: Int, stepOverride: Float? = null) {
    /**
     * Radians between slots. Home-screen dials pass the spacing their ring can
     * show (so a full ring fills the visible arc); drawers leave it out and get
     * the whole circle shared between the apps.
     */
    val step: Float = stepOverride ?: if (count <= 0) (2 * PI).toFloat() else (2 * PI / count).toFloat()

    /** Angle of slot [index] in radians, 0 = pointing right (+x). */
    fun angle(index: Int, rotation: Float): Float = index * step + rotation

    /** Where the finger's [angle] lands in slot space. */
    fun nearestIndex(angle: Float, rotation: Float): Int {
        if (count <= 0) return -1
        val rel = normalize(angle - rotation)
        val raw = (rel / step).roundToInt() % count
        return if (raw < 0) raw + count else raw
    }

    /** Rotation that puts the nearest slot exactly on [angle]. */
    fun snapAround(angle: Float, rotation: Float): Float {
        if (count <= 0) return rotation
        val rel = normalize(angle - rotation)
        val steps = (rel / step).roundToInt()
        return rotation + (rel - steps * step)
    }

    /**
     * Rotation after a flick of [rotation] at [velocity] (radians per second).
     * [detents] is how many slots the flick should coast past at most.
     */
    fun flingTarget(rotation: Float, velocity: Float, detents: Int = 6): Float {
        if (count <= 0) return rotation
        val slide = (velocity * 0.16f) / step
        val steps = slide.coerceIn(-detents.toFloat(), detents.toFloat()).roundToInt()
        return snap(rotation + steps * step)
    }

    fun snap(rotation: Float): Float = (rotation / step).roundToInt() * step

    companion object {
        /** Wraps an angle into (-PI, PI]. */
        fun normalize(angle: Float): Float {
            var a = angle % (2 * PI).toFloat()
            if (a > PI) a -= (2 * PI).toFloat()
            if (a <= -PI) a += (2 * PI).toFloat()
            return a
        }

        /** Shortest signed difference from [from] to [to], in radians. */
        fun delta(from: Float, to: Float): Float = normalize(to - from)
    }
}

/**
 * Where the rings of one screen go. All numbers are fractions of the screen
 * width, so a plan looks the same on a small phone and a tablet.
 */
data class RingPlan(
    /** Radius of each dial, as a fraction of the screen width. */
    val radii: List<Float>,
    /** How much of each circle is on screen, in radians. */
    val span: Float,
    /** Icon edge, as a fraction of the screen width. */
    val icon: Float,
    /** Ring centre, as fractions of the screen width and height. */
    val anchorX: Float,
    val anchorY: Float,
) {
    fun radiusOf(i: Int): Float = radii[i.coerceIn(radii.indices)]

    fun iconPx(width: Float): Float = icon * width

    /** How many slots [radius] holds before icons start to collide. */
    fun capacity(radius: Float, width: Float, spacing: Float = 1.28f): Int {
        val arc = span * radius * width
        val each = iconPx(width) * spacing
        return floor(arc / each).toInt().coerceAtLeast(2)
    }
}

object RingHomePlan {
    /** Icons visible at once on each home ring is kept low; the rest spin in. */
    fun home(hand: HomeHand): RingPlan {
        val anchorX = if (hand == HomeHand.RIGHT) 0.865f else 0.135f
        return RingPlan(
            radii = listOf(0.36f, 0.55f, 0.74f),
            span = Math.toRadians(150.0).toFloat(),
            icon = 0.125f,
            anchorX = anchorX,
            anchorY = 0.52f,
        )
    }

    /** The app drawer: a wider fan that fits every installed app. */
    fun drawer(hand: HomeHand, appCount: Int): RingPlan {
        val anchorX = if (hand == HomeHand.RIGHT) 0.865f else 0.135f
        var rings = 4
        while (rings < 8 && appCount > 0) {
            val radii = drawerRadii(rings)
            // Equal spacing on every ring: share the total arc out by radius.
            val total = radii.sum()
            val tightest = radii.min()
            val onIt = ceil(appCount * tightest / total).toInt()
            val spacing = (2 * PI * tightest) / onIt
            if (spacing >= 0.118f * 1.12f) break
            rings++
        }
        return RingPlan(
            radii = drawerRadii(rings),
            span = Math.toRadians(168.0).toFloat(),
            icon = 0.104f,
            anchorX = anchorX,
            anchorY = 0.5f,
        )
    }

    /** Evenly spaced out to 0.78 of the width, so the outermost icons stay on screen. */
    private fun drawerRadii(rings: Int): List<Float> = List(rings) { i -> 0.26f + i * (0.52f / (rings - 1).coerceAtLeast(1)) }

    /**
     * Splits [count] apps across [plan]'s dials, proportional to each ring's
     * length so the gaps look even. Returns one slot count per ring.
     */
    fun slots(plan: RingPlan, count: Int): List<Int> {
        if (count <= 0 || plan.radii.isEmpty()) return plan.radii.map { 0 }
        val totalR = plan.radii.sum()
        val raw = plan.radii.map { count * it / totalR }
        val out = raw.map { it.toInt() }.toMutableList()
        // Hand the rounding remainder to the rings that lost the most to it.
        val order = raw.indices.sortedByDescending { raw[it] - out[it] }
        var left = count - out.sum()
        var k = 0
        while (left > 0 && order.isNotEmpty()) {
            out[order[k % order.size]]++
            left--
            k++
        }
        return out
    }

    /**
     * Distance from the ring centre to [dx],[dy] measured along the screen, and
     * the angle. Kept here so the view and the tests agree on the mapping.
     */
    fun polar(dx: Float, dy: Float): Pair<Float, Float> = kotlin.math.hypot(dx, dy) to kotlin.math.atan2(dy, dx)

    /**
     * True when [angle] is inside the visible window of a ring whose window is
     * centred on the screen side ([mirror] = true means the rings open towards
     * +x, i.e. a left-edge anchor).
     */
    fun inWindow(angle: Float, mirror: Boolean, span: Float): Boolean {
        val facing = if (mirror) 0f else PI.toFloat()
        val off = abs(Dial.normalize(angle - facing))
        return off <= span / 2f
    }

    /** Screen position of a slot. */
    fun position(cx: Float, cy: Float, radius: Float, angle: Float): Pair<Float, Float> =
        (cx + radius * cos(angle)) to (cy + radius * sin(angle))

    /** Which hand a tap on [x] is nearer to, so a tap on the far edge mirrors the layout. */
    fun sideOf(x: Float, width: Float): HomeHand = if (x > width / 2f) HomeHand.RIGHT else HomeHand.LEFT

    /**
     * Slot counts a home screen wants: spread the apps over the rings in
     * proportion to their length (so all three look used), but never past the
     * point where icons would touch.
     */
    fun homeSlots(plan: RingPlan, width: Float, count: Int): List<Int> {
        // Fill the inner ring first: it is the one nearest the thumb.
        var left = count
        return plan.radii.map { r ->
            val take = min(plan.capacity(r, width, spacing = 1.34f), max(left, 0))
            left -= take
            take
        }
    }

    /** Spacing a home ring uses: its slots fill the visible arc. */
    fun homeStep(plan: RingPlan, width: Float, ring: Int): Float =
        plan.span / plan.capacity(plan.radiusOf(ring), width, spacing = 1.34f).toFloat()
}
