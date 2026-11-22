/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ring

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.util.Themes
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws the Dogo Ring and follows the finger. Opened by [DogoRing.open] while
 * the finger that summoned it is still down; the workspace forwards that
 * finger's events through [onFingerEvent]. Releasing without moving leaves the
 * ring open for taps.
 */
@SuppressLint("ViewConstructor")
class DogoRingView internal constructor(
    private val launcher: Launcher,
    root: RingItem.Group,
    private val style: RingStyle,
    private val icons: Map<String, Bitmap>,
    private val offsetX: Float,
    private val offsetY: Float,
    private val onCommand: (RingCommand, Float, Float) -> Unit,
) : AbstractFloatingView(launcher, null) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val outer = dp(126f)
    private val hub = dp(38f)
    private val iconSize = dp(40f)
    private val pop = dp(10f)
    private val labelRoom = dp(26f)

    // Fire and Electric bring their own colours; the others follow your theme.
    private val accent = when (style) {
        RingStyle.FIRE -> 0xFFFF7A1A.toInt()
        RingStyle.ELECTRIC -> 0xFF2FD8FF.toInt()
        else -> Themes.getColorAccent(launcher)
    }
    private val accentLight = ColorUtils.blendARGB(accent, Color.WHITE, 0.45f)

    private inner class Level(val group: RingItem.Group, val cx: Float, val cy: Float) {
        val geometry = RingGeometry(group.children.size.coerceAtLeast(1), hub, outer)
        var progress = 0f
        val animator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OPEN_MS
            interpolator = OvershootInterpolator(1.3f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
        }
    }

    private val levels = ArrayList<Level>()
    private val level get() = levels.last()

    private var selected = RingGeometry.HUB
    private var selectedSince = 0L
    private var sticky = false
    private var armed = false
    private var startX = 0f
    private var startY = 0f
    private var appear = 0f
    private var closing = false

    private data class TrailPoint(val x: Float, val y: Float, val t: Long)
    private val trail = ArrayDeque<TrailPoint>()

    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val born: Long, val life: Long, val size: Float, val white: Boolean, val ember: Boolean = false)
    private val particles = ArrayList<Particle>()
    private val random = Random(SystemClock.uptimeMillis())

    // Electric: the current lightning, redrawn a few times a second so it crackles.
    private val bolts = ArrayList<Bolt>()
    private val strikes = ArrayList<Pair<Float, Float>>()
    private var boltsAt = 0L
    private var boltsFor = Int.MIN_VALUE

    private var sweepAngle = -90f
    private var lastFrame = 0L
    private val blips = List(6) { Triple(random.nextFloat() * 360f, 0.55f + random.nextFloat() * 0.35f, random.nextFloat()) }

    private val dwell = Runnable { if (selected >= 0 && currentItem(selected) is RingItem.Group) enterGroup(selected) }

    // ---- paints ---------------------------------------------------------

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bloom = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = android.graphics.BlurMaskFilter(dp(9f), android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val ember = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(11f)
        setShadowLayer(dp(3f), 0f, dp(1f), Color.argb(200, 0, 0, 0))
    }
    private val hubLabel = TextPaint(label).apply {
        textSize = dp(13f)
        isFakeBoldText = true
    }
    private val wedge = Path()
    private val tmpRect = RectF()

    private val commandIcons: Map<RingCommand, Drawable?> = mapOf(
        RingCommand.APP_DRAWER to drawable(R.drawable.ic_apps),
        RingCommand.HOME_OPTIONS to drawable(R.drawable.ic_home_screen),
        RingCommand.DOGO_OPTIONS to drawable(R.drawable.ic_setting),
        RingCommand.TALK to drawable(R.drawable.ic_mic_flat),
    )

    private fun drawable(id: Int): Drawable? = runCatching { ContextCompat.getDrawable(launcher, id)?.mutate()?.apply { setTint(Color.WHITE) } }.getOrNull()

    init {
        setWillNotDraw(false)
        mIsOpen = true
        levels += Level(root, 0f, 0f)
        updateDescription()
    }

    /** Screen readers (and UI tests) hear what's on the open ring. */
    private fun updateDescription() {
        contentDescription = "Dogo Ring, ${level.group.label}: " + level.group.children.joinToString { it.label }
    }

    // ---- opening and closing --------------------------------------------

    internal fun start(rawX: Float, rawY: Float) {
        val x = rawX - offsetX
        val y = rawY - offsetY
        val (cx, cy) = clamp(x, y)
        levels[0] = Level(levels[0].group, cx, cy)
        startX = x
        startY = y
        level.animator.start()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OPEN_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                appear = it.animatedValue as Float
                invalidate()
            }
        }.start()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
        burst(cx, cy, 14, outer * 0.9f)
    }

    private fun clamp(x: Float, y: Float): Pair<Float, Float> {
        val insets = launcher.deviceProfile.insets
        val w = launcher.dragLayer.width.toFloat()
        val h = launcher.dragLayer.height.toFloat()
        // Stay above the dock so its icons never sit under the ring's labels.
        val hotseat = launcher.hotseat
        val loc = IntArray(2)
        val bottom = if (hotseat != null && hotseat.isShown && hotseat.height > 0) {
            hotseat.getLocationOnScreen(loc)
            (loc[1] - offsetY).coerceAtMost(h - insets.bottom)
        } else {
            h - insets.bottom
        }
        return RingGeometry.clampCenter(x, y, outer + pop + labelRoom, insets.left.toFloat(), insets.top.toFloat(), w - insets.right, bottom)
    }

    override fun handleClose(animate: Boolean) {
        removeCallbacks(dwell)
        if (!animate) {
            removeFromParent()
            return
        }
        closing = true
        ValueAnimator.ofFloat(appear, 0f).apply {
            duration = CLOSE_MS
            addUpdateListener {
                appear = it.animatedValue as Float
                invalidate()
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = removeFromParent()
                },
            )
        }.start()
    }

    private fun removeFromParent() {
        (parent as? android.view.ViewGroup)?.removeView(this)
    }

    override fun isOfType(type: Int): Boolean = type and TYPE_OPTIONS_POPUP != 0

    // ---- touch ------------------------------------------------------------

    /** Events from the finger that opened the ring (raw screen coordinates). */
    fun onFingerEvent(ev: MotionEvent) {
        handle(ev.actionMasked, ev.rawX - offsetX, ev.rawY - offsetY)
    }

    // Once the summoning finger lifts, every new touch belongs to the ring.
    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean = isOpen && !closing && ev.actionMasked == MotionEvent.ACTION_DOWN

    override fun onControllerTouchEvent(ev: MotionEvent): Boolean {
        handle(ev.actionMasked, ev.rawX - offsetX, ev.rawY - offsetY)
        return true
    }

    private fun handle(action: Int, x: Float, y: Float) {
        if (closing) return
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                startX = x
                startY = y
                armed = true // a tap in sticky mode selects straight away
                track(x, y)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!armed && hypot(x - startX, y - startY) > dp(12f)) armed = true
                track(x, y)
            }

            MotionEvent.ACTION_UP -> release(x, y)

            MotionEvent.ACTION_CANCEL -> close(true)
        }
    }

    private fun reach(): Float = if (sticky) outer + pop + labelRoom else Float.POSITIVE_INFINITY

    private fun track(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        trail.addLast(TrailPoint(x, y, now))
        while (trail.size > 24) trail.removeFirst()
        val l = level
        val hit = if (armed) l.geometry.hit(x - l.cx, y - l.cy, reach()) else RingGeometry.HUB
        if (hit != selected) {
            selected = hit
            selectedSince = now
            removeCallbacks(dwell)
            if (hit >= 0) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                val (px, py) = l.geometry.position(hit, outer)
                burst(l.cx + px, l.cy + py, 5, dp(70f))
                if (currentItem(hit) is RingItem.Group && !sticky) postDelayed(dwell, DWELL_MS)
            }
        }
        // Flicking past the rim of a group opens it at once.
        if (!sticky && hit >= 0 && currentItem(hit) is RingItem.Group && hypot(x - l.cx, y - l.cy) > outer + pop) {
            enterGroup(hit)
        }
        invalidate()
    }

    private fun release(x: Float, y: Float) {
        removeCallbacks(dwell)
        track(x, y)
        val index = selected
        when {
            index >= 0 -> activate(index)

            index == RingGeometry.NONE -> close(true)

            // Released in the hub.
            !sticky && !armed -> {
                sticky = true
                invalidate()
            }

            levels.size > 1 -> goBack()

            // Tapping the light in the middle starts talking, like Hermes Vessel's orb.
            sticky -> talk()

            else -> {
                sticky = true
                invalidate()
            }
        }
        trail.clear()
    }

    private fun talk() {
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        burst(level.cx, level.cy, 18, outer * 0.8f)
        close(true)
        onCommand(RingCommand.TALK, level.cx, level.cy)
    }

    private fun currentItem(index: Int): RingItem? = level.group.children.getOrNull(index)

    private fun activate(index: Int) {
        when (val item = currentItem(index) ?: return) {
            is RingItem.Group -> {
                enterGroup(index)
                sticky = true
            }

            is RingItem.App -> launch(item, index)

            is RingItem.Command -> {
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                val (px, py) = level.geometry.position(index)
                val at = level.cx + px to level.cy + py
                close(item.command != RingCommand.HOME_OPTIONS)
                onCommand(item.command, at.first, at.second)
            }
        }
    }

    private fun enterGroup(index: Int) {
        removeCallbacks(dwell)
        val parent = level
        val group = parent.group.children[index] as RingItem.Group
        val (px, py) = parent.geometry.position(index)
        val (cx, cy) = clamp(parent.cx + px, parent.cy + py)
        levels += Level(group, cx, cy)
        level.animator.start()
        selected = RingGeometry.HUB
        selectedSince = SystemClock.uptimeMillis()
        // The finger is now in the new hub; it must move to pick something.
        if (trail.isNotEmpty()) {
            startX = trail.last().x
            startY = trail.last().y
        }
        armed = sticky
        trail.clear()
        updateDescription()
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        burst(cx, cy, 10, outer * 0.7f)
        invalidate()
    }

    private fun goBack() {
        levels.removeAt(levels.lastIndex)
        updateDescription()
        selected = RingGeometry.HUB
        level.animator.start()
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        invalidate()
    }

    private fun launch(item: RingItem.App, index: Int) {
        val apps = launcher.getSystemService(LauncherApps::class.java)
        val user = Process.myUserHandle()
        val activity = apps.getActivityList(item.pkg, user).firstOrNull()
        if (activity == null) {
            close(true)
            return
        }
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        val (px, py) = level.geometry.position(index)
        val ix = (level.cx + px - iconSize / 2).toInt()
        val iy = (level.cy + py - iconSize / 2).toInt()
        val size = iconSize.toInt()
        val bounds = Rect(ix + offsetX.toInt(), iy + offsetY.toInt(), ix + offsetX.toInt() + size, iy + offsetY.toInt() + size)
        val options = ActivityOptions.makeScaleUpAnimation(this, ix, iy, size, size).toBundle()
        runCatching { apps.startMainActivity(activity.componentName, user, bounds, options) }
        burst(level.cx + px, level.cy + py, 16, dp(140f))
        close(true)
    }

    // ---- effects ------------------------------------------------------------

    private fun burst(x: Float, y: Float, count: Int, speed: Float) {
        if (style == RingStyle.MINIMAL || style == RingStyle.ELECTRIC) return
        if (style == RingStyle.FIRE) {
            // Picking a slice throws a spray of coals off its edge of the ring.
            val now = SystemClock.uptimeMillis()
            val ang = atan2(y - level.cy, x - level.cx)
            repeat(count + 2) { ember(level.cx + cos(ang) * (outer + dp(6f)), level.cy + sin(ang) * (outer + dp(6f)), now, 0.5f, cos(ang), sin(ang)) }
            return
        }
        val now = SystemClock.uptimeMillis()
        repeat(count) {
            val a = random.nextFloat() * 2 * Math.PI
            val v = speed * (0.4f + random.nextFloat() * 0.6f)
            particles += Particle(
                x,
                y,
                (cos(a) * v).toFloat(),
                (sin(a) * v).toFloat(),
                now,
                380L + random.nextLong(320L),
                dp(1.5f + random.nextFloat() * 2.5f),
                random.nextFloat() < 0.35f,
            )
        }
        while (particles.size > 160) particles.removeAt(0)
    }

    /** Fire: a hot coal that drifts upward and sways as it cools. */
    private fun ember(x: Float, y: Float, now: Long, lift: Float = 1f, outX: Float = 0f, outY: Float = 0f) {
        particles += Particle(
            x + (random.nextFloat() - 0.5f) * dp(10f),
            y + (random.nextFloat() - 0.5f) * dp(10f),
            (random.nextFloat() - 0.5f) * dp(40f) + outX * dp(140f) * (0.5f + random.nextFloat()),
            -dp(50f + random.nextFloat() * 110f) * lift + outY * dp(140f) * (0.5f + random.nextFloat()),
            now,
            600L + random.nextLong(700L),
            dp(1.2f + random.nextFloat() * random.nextFloat() * 3.6f),
            random.nextFloat() < 0.3f,
            ember = true,
        )
        while (particles.size > 160) particles.removeAt(0)
    }

    // ---- drawing ------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else min(0.05f, (now - lastFrame) / 1000f)
        lastFrame = now

        // A soft spotlight: dark around the ring, clear at the screen edges' far corners.
        val focus = levels.last()
        fill.shader = RadialGradient(
            focus.cx,
            focus.cy,
            max(width, height) * 0.75f,
            intArrayOf(Color.argb((215 * appear).toInt(), 0, 0, 0), Color.argb((175 * appear).toInt(), 0, 0, 0)),
            floatArrayOf(0.25f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null

        if (style == RingStyle.FIRE && !closing) {
            val last = trail.lastOrNull()
            if (last != null && now - last.t < 120L && hypot(last.x - focus.cx, last.y - focus.cy) > outer) repeat(2) { ember(last.x, last.y, now) }
            // Coals keep lifting off the whole rim, more from the top where the heat rises.
            if (random.nextFloat() < 0.7f) {
                val ang = (random.nextFloat() * 2 - 1) * Math.PI * (if (random.nextFloat() < 0.6f) 0.5 else 1.0) - Math.PI / 2
                ember(focus.cx + (cos(ang) * (outer + dp(3f))).toFloat(), focus.cy + (sin(ang) * (outer + dp(3f))).toFloat(), now, 0.8f)
            }
        }

        // Only the open ring is drawn; its hub says "Back" to return.
        drawLevel(canvas, levels.last(), appear, true, dt)
        drawTrail(canvas, now)
        drawParticles(canvas, now, dt)

        val animating = true // the light in the middle always breathes
        if (animating && isAttachedToWindow) postInvalidateOnAnimation()
    }

    private fun drawLevel(canvas: Canvas, l: Level, alpha: Float, top: Boolean, dt: Float) {
        val g = l.geometry
        val p = l.progress
        val a = (alpha * min(1f, p * 1.6f)).coerceIn(0f, 1f)
        if (a <= 0.01f) return
        val scale = 0.55f + 0.45f * p
        canvas.save()
        canvas.translate(l.cx, l.cy)
        canvas.scale(scale, scale)

        val sel = if (top) selected else RingGeometry.HUB
        when (style) {
            RingStyle.GLOW -> drawGlowBody(canvas, g, sel, a)
            RingStyle.RADAR -> drawRadarBody(canvas, g, sel, a, dt, top)
            RingStyle.FIRE -> drawFireBody(canvas, g, sel, a)
            RingStyle.ELECTRIC -> drawElectricBody(canvas, g, sel, a)
            RingStyle.MINIMAL -> drawMinimalBody(canvas, g, sel, a)
        }
        // Lightning runs under the icons and labels, so they stay readable.
        if (style == RingStyle.ELECTRIC && top) drawBolts(canvas, g, sel, a)
        l.group.children.forEachIndexed { i, item -> drawItem(canvas, g, i, item, i == sel, a) }
        drawHub(canvas, l, sel, a)
        canvas.restore()
    }

    private fun alphaOf(color: Int, a: Float, base: Int = Color.alpha(color)) = ColorUtils.setAlphaComponent(color, (base * a).toInt().coerceIn(0, 255))

    private fun wedgePath(g: RingGeometry, index: Int, inner: Float, outerR: Float): Path {
        wedge.reset()
        val start = g.startAngle(index) + 0.8f
        val sweep = g.sweep - 1.6f
        tmpRect.set(-outerR, -outerR, outerR, outerR)
        wedge.arcTo(tmpRect, start, sweep, true)
        tmpRect.set(-inner, -inner, inner, inner)
        wedge.arcTo(tmpRect, start + sweep, -sweep, false)
        wedge.close()
        return wedge
    }

    private fun drawDividers(canvas: Canvas, g: RingGeometry, color: Int, width: Float) {
        if (g.count < 2) return
        stroke.clearShadowLayer()
        stroke.color = color
        stroke.strokeWidth = width
        for (i in 0 until g.count) {
            val rad = Math.toRadians(g.startAngle(i).toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            canvas.drawLine(c * (hub + dp(4f)), s * (hub + dp(4f)), c * (outer - dp(4f)), s * (outer - dp(4f)), stroke)
        }
    }

    private fun drawGlowBody(canvas: Canvas, g: RingGeometry, sel: Int, a: Float) {
        fill.shader = null
        fill.clearShadowLayer()
        fill.color = Color.argb((215 * a).toInt(), 6, 8, 18)
        canvas.drawCircle(0f, 0f, outer + dp(2f), fill)

        if (sel >= 0) {
            val path = wedgePath(g, sel, hub + dp(3f), outer + pop)
            fill.shader = RadialGradient(0f, 0f, outer + pop, intArrayOf(alphaOf(accent, a, 20), alphaOf(accent, a, 170)), floatArrayOf(0.3f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(path, fill)
            fill.shader = null
            tmpRect.set(-(outer + pop), -(outer + pop), outer + pop, outer + pop)
            bloom.color = alphaOf(accent, a, 255)
            bloom.strokeWidth = dp(9f)
            canvas.drawArc(tmpRect, g.startAngle(sel) + 1f, g.sweep - 2f, false, bloom)
            stroke.color = alphaOf(accentLight, a, 255)
            stroke.strokeWidth = dp(3f)
            canvas.drawArc(tmpRect, g.startAngle(sel) + 1f, g.sweep - 2f, false, stroke)
        }

        drawDividers(canvas, g, alphaOf(accent, a, 80), dp(1f))

        bloom.color = alphaOf(accent, a, 200)
        bloom.strokeWidth = dp(7f)
        canvas.drawCircle(0f, 0f, outer, bloom)
        stroke.color = alphaOf(accentLight, a, 255)
        stroke.strokeWidth = dp(2.5f)
        canvas.drawCircle(0f, 0f, outer, stroke)
        stroke.strokeWidth = dp(1f)
        stroke.color = alphaOf(accent, a, 110)
        canvas.drawCircle(0f, 0f, outer - dp(7f), stroke)
    }

    private fun drawFireBody(canvas: Canvas, g: RingGeometry, sel: Int, a: Float) {
        fill.shader = null
        fill.clearShadowLayer()
        fill.color = Color.argb((215 * a).toInt(), 20, 9, 4)
        canvas.drawCircle(0f, 0f, outer + dp(2f), fill)
        // Heat rising off the inside of the rim.
        fill.shader = RadialGradient(0f, 0f, outer, intArrayOf(Color.TRANSPARENT, alphaOf(accent, a, 70)), floatArrayOf(0.62f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(0f, 0f, outer, fill)
        fill.shader = null

        if (sel >= 0) {
            val path = wedgePath(g, sel, hub + dp(3f), outer + pop)
            fill.shader = RadialGradient(
                0f,
                0f,
                outer + pop,
                intArrayOf(alphaOf(0xFFB3260A.toInt(), a, 170), alphaOf(accent, a, 235), alphaOf(0xFFFFC542.toInt(), a, 255)),
                floatArrayOf(0.3f, 0.8f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(path, fill)
            fill.shader = null
            tmpRect.set(-(outer + pop), -(outer + pop), outer + pop, outer + pop)
            bloom.color = alphaOf(0xFFFFB02E.toInt(), a, 255)
            bloom.strokeWidth = dp(10f)
            canvas.drawArc(tmpRect, g.startAngle(sel) + 1f, g.sweep - 2f, false, bloom)
        }

        drawDividers(canvas, g, alphaOf(accent, a, 120), dp(1.5f))
        // A solid, flickering ring of flame colour.
        val flicker = 0.85f + random.nextFloat() * 0.15f
        bloom.color = alphaOf(accent, a * flicker, 230)
        bloom.strokeWidth = dp(10f)
        canvas.drawCircle(0f, 0f, outer, bloom)
        stroke.color = alphaOf(accent, a, 255)
        stroke.strokeWidth = dp(4f)
        canvas.drawCircle(0f, 0f, outer, stroke)
        stroke.color = alphaOf(0xFFFFD37A.toInt(), a, 200)
        stroke.strokeWidth = dp(1.2f)
        canvas.drawCircle(0f, 0f, outer - dp(1f), stroke)
    }

    private fun drawElectricBody(canvas: Canvas, g: RingGeometry, sel: Int, a: Float) {
        fill.shader = null
        fill.clearShadowLayer()
        fill.color = Color.argb((220 * a).toInt(), 2, 10, 20)
        canvas.drawCircle(0f, 0f, outer + dp(2f), fill)

        if (sel >= 0) {
            val path = wedgePath(g, sel, hub + dp(3f), outer + pop)
            fill.shader = RadialGradient(0f, 0f, outer + pop, intArrayOf(alphaOf(accent, a, 25), alphaOf(accent, a, 120)), floatArrayOf(0.3f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(path, fill)
            fill.shader = null
        }

        // Glowing circuit lines between the slices.
        val buzz = 0.8f + random.nextFloat() * 0.2f
        val n = g.count
        if (n > 1) {
            bloom.color = alphaOf(accent, a * buzz, 170)
            bloom.strokeWidth = dp(4f)
            stroke.color = alphaOf(accentLight, a, 200)
            stroke.strokeWidth = dp(1f)
            for (i in 0 until n) {
                val rad = Math.toRadians(g.startAngle(i).toDouble())
                val c = cos(rad).toFloat()
                val s = sin(rad).toFloat()
                canvas.drawLine(c * (hub + dp(4f)), s * (hub + dp(4f)), c * (outer - dp(7f)), s * (outer - dp(7f)), bloom)
                canvas.drawLine(c * (hub + dp(4f)), s * (hub + dp(4f)), c * (outer - dp(7f)), s * (outer - dp(7f)), stroke)
            }
        }
        // Neon double outline.
        for (r in floatArrayOf(outer, outer - dp(7f))) {
            bloom.color = alphaOf(accent, a * buzz, 220)
            bloom.strokeWidth = dp(8f)
            canvas.drawCircle(0f, 0f, r, bloom)
            stroke.color = alphaOf(ColorUtils.blendARGB(accent, Color.WHITE, 0.6f), a, 255)
            stroke.strokeWidth = dp(1.8f)
            canvas.drawCircle(0f, 0f, r, stroke)
        }
        if (sel >= 0) {
            tmpRect.set(-(outer + pop), -(outer + pop), outer + pop, outer + pop)
            stroke.color = alphaOf(Color.WHITE, a, 255)
            stroke.strokeWidth = dp(2.5f)
            canvas.drawArc(tmpRect, g.startAngle(sel) + 1f, g.sweep - 2f, false, stroke)
        }
    }

    /** A lightning stroke: points along its path and how thick it is where it starts. */
    private class Bolt(val xs: FloatArray, val ys: FloatArray, val width: Float)

    /**
     * Lightning from (x0, y0) to (x1, y1): fine jitter around a clear direction (midpoint
     * displacement), plus thin twigs that branch off and die out.
     */
    private fun strike(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, out: MutableList<Bolt>, depth: Int = 0) {
        val len = hypot(x1 - x0, y1 - y0)
        if (len < dp(4f)) return
        var xs = floatArrayOf(x0, x1)
        var ys = floatArrayOf(y0, y1)
        var rough = len * 0.16f
        repeat(5) {
            val n = xs.size
            val nx = FloatArray(n * 2 - 1)
            val ny = FloatArray(n * 2 - 1)
            for (k in 0 until n - 1) {
                nx[k * 2] = xs[k]
                ny[k * 2] = ys[k]
                val dx = xs[k + 1] - xs[k]
                val dy = ys[k + 1] - ys[k]
                val d = max(1f, hypot(dx, dy))
                val off = (random.nextFloat() - 0.5f) * 2f * rough
                nx[k * 2 + 1] = (xs[k] + xs[k + 1]) / 2f - dy / d * off
                ny[k * 2 + 1] = (ys[k] + ys[k + 1]) / 2f + dx / d * off
            }
            nx[nx.size - 1] = xs[n - 1]
            ny[ny.size - 1] = ys[n - 1]
            xs = nx
            ys = ny
            rough *= 0.52f
        }
        out += Bolt(xs, ys, width)
        if (depth >= 2) return
        // Twigs: short side branches, thinner, angled off the main direction.
        val twigs = if (depth == 0) 3 + random.nextInt(3) else random.nextInt(2)
        val dir = atan2(y1 - y0, x1 - x0)
        repeat(twigs) {
            val k = (xs.size * (0.15f + random.nextFloat() * 0.65f)).toInt()
            val ang = dir + (if (random.nextBoolean()) 1f else -1f) * (0.4f + random.nextFloat() * 0.6f)
            val tl = len * (0.12f + random.nextFloat() * 0.22f)
            strike(xs[k], ys[k], xs[k] + cos(ang) * tl, ys[k] + sin(ang) * tl, width * 0.45f, out, depth + 1)
        }
    }

    private fun drawBolts(canvas: Canvas, g: RingGeometry, sel: Int, a: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - boltsAt > 70L || sel != boltsFor) {
            boltsAt = now
            boltsFor = sel
            bolts.clear()
            strikes.clear()
            if (sel >= 0) {
                val (x, y) = g.position(sel)
                val ty = y - dp(6f)
                val trad = atan2(ty, x)
                // The icon is drawn over the end, so the bolt seems to strike it.
                strike(cos(trad) * hub, sin(trad) * hub, x, ty, dp(5.5f), bolts)
                strikes += x to ty
                // A second, thinner arc crackles past the icon out to the rim of the slice.
                if (random.nextFloat() < 0.8f) {
                    val side = trad + (if (random.nextBoolean()) 1f else -1f) * Math.toRadians(g.sweep * 0.3).toFloat()
                    val rim = outer - dp(8f)
                    strike(cos(trad) * hub, sin(trad) * hub, cos(side) * rim, sin(side) * rim, dp(3.2f), bolts)
                    strikes += cos(side) * rim to sin(side) * rim
                }
            } else if (random.nextFloat() < 0.75f) {
                // Idle: short tendrils crawl out of the hub.
                repeat(2 + random.nextInt(2)) {
                    val ang = random.nextFloat() * 2f * Math.PI.toFloat()
                    val len = hub + (outer - hub) * (0.14f + random.nextFloat() * 0.2f)
                    strike(cos(ang) * hub, sin(ang) * hub, cos(ang) * len, sin(ang) * len, dp(2f), bolts, 1)
                }
            }
        }
        if (bolts.isEmpty()) return
        val flash = 0.7f + random.nextFloat() * 0.3f
        val body = alphaOf(ColorUtils.blendARGB(accent, Color.WHITE, 0.3f), a * flash, 255)
        val core = alphaOf(Color.WHITE, a * flash, 255)
        stroke.strokeCap = Paint.Cap.ROUND
        for (b in bolts) {
            val n = b.xs.size
            // Glow along the whole stroke.
            wedge.reset()
            wedge.moveTo(b.xs[0], b.ys[0])
            for (k in 1 until n) wedge.lineTo(b.xs[k], b.ys[k])
            bloom.color = alphaOf(accent, a * flash, 255)
            bloom.strokeWidth = max(dp(6f), b.width * 3.2f)
            canvas.drawPath(wedge, bloom)
            // Body and white core, tapering toward the tip.
            for (k in 0 until n - 1) {
                val taper = 1f - 0.65f * k / (n - 1f)
                stroke.color = body
                stroke.strokeWidth = b.width * taper
                canvas.drawLine(b.xs[k], b.ys[k], b.xs[k + 1], b.ys[k + 1], stroke)
                stroke.color = core
                stroke.strokeWidth = b.width * taper * 0.45f
                canvas.drawLine(b.xs[k], b.ys[k], b.xs[k + 1], b.ys[k + 1], stroke)
            }
        }
        stroke.strokeCap = Paint.Cap.BUTT
        // A flash where each bolt lands.
        for ((sx, sy) in strikes) {
            val r = dp(16f) * (0.8f + random.nextFloat() * 0.4f)
            fill.shader = RadialGradient(sx, sy, r, intArrayOf(alphaOf(Color.WHITE, a * flash, 230), alphaOf(accent, a * flash, 150), Color.TRANSPARENT), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(sx, sy, r, fill)
        }
        fill.shader = null
    }

    private fun drawRadarBody(canvas: Canvas, g: RingGeometry, sel: Int, a: Float, dt: Float, top: Boolean) {
        fill.shader = null
        fill.clearShadowLayer()
        fill.color = Color.argb((200 * a).toInt(), 4, 8, 16)
        canvas.drawCircle(0f, 0f, outer, fill)
        fill.color = alphaOf(accent, a, 70)
        canvas.drawCircle(0f, 0f, outer, fill)

        // Scope grid.
        fill.color = Color.argb((40 * a).toInt(), 255, 255, 255)
        val step = dp(14f)
        var gx = -outer
        while (gx <= outer) {
            var gy = -outer
            while (gy <= outer) {
                if (gx * gx + gy * gy < (outer - dp(6f)) * (outer - dp(6f))) canvas.drawCircle(gx, gy, dp(1f), fill)
                gy += step
            }
            gx += step
        }

        // The beam chases the selection, or idles round.
        if (top) {
            if (sel >= 0) {
                val target = g.centerAngle(sel) + g.sweep / 2f
                var diff = ((target - sweepAngle) % 360f + 540f) % 360f - 180f
                sweepAngle += diff * min(1f, dt * 14f)
                diff = abs(diff)
            } else {
                sweepAngle = (sweepAngle + 140f * dt) % 360f
            }
        }
        fill.shader = SweepGradient(
            0f,
            0f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((150 * a).toInt(), 255, 255, 255)),
            floatArrayOf(0f, 0.86f, 1f),
        ).apply { setLocalMatrix(Matrix().apply { setRotate(sweepAngle) }) }
        canvas.drawCircle(0f, 0f, outer - dp(2f), fill)
        fill.shader = null
        val rad = Math.toRadians(sweepAngle.toDouble())
        stroke.color = Color.argb((230 * a).toInt(), 255, 255, 255)
        stroke.strokeWidth = dp(1.5f)
        stroke.setShadowLayer(dp(6f), 0f, 0f, Color.argb((200 * a).toInt(), 255, 255, 255))
        canvas.drawLine(0f, 0f, cos(rad).toFloat() * outer, sin(rad).toFloat() * outer, stroke)
        stroke.clearShadowLayer()

        // Blips light up as the beam passes.
        blips.forEach { (angle, r, _) ->
            val behind = ((sweepAngle - angle) % 360f + 360f) % 360f
            val glow = (1f - behind / 200f).coerceIn(0f, 1f)
            if (glow > 0f) {
                val br = Math.toRadians(angle.toDouble())
                fill.color = Color.argb((220 * a * glow).toInt(), 255, 255, 255)
                canvas.drawCircle(cos(br).toFloat() * outer * r, sin(br).toFloat() * outer * r, dp(2.5f), fill)
            }
        }

        if (sel >= 0) {
            fill.color = alphaOf(accentLight, a, 120)
            canvas.drawPath(wedgePath(g, sel, hub + dp(3f), outer + pop), fill)
            tmpRect.set(-(outer + pop), -(outer + pop), outer + pop, outer + pop)
            bloom.color = Color.argb((230 * a).toInt(), 255, 255, 255)
            bloom.strokeWidth = dp(7f)
            canvas.drawArc(tmpRect, g.startAngle(sel) + 1f, g.sweep - 2f, false, bloom)
            stroke.color = Color.argb((255 * a).toInt(), 255, 255, 255)
            stroke.strokeWidth = dp(2.5f)
            canvas.drawArc(tmpRect, g.startAngle(sel) + 1f, g.sweep - 2f, false, stroke)
        }

        drawDividers(canvas, g, Color.argb((60 * a).toInt(), 255, 255, 255), dp(0.75f))

        stroke.color = Color.argb((170 * a).toInt(), 255, 255, 255)
        stroke.strokeWidth = dp(1.5f)
        canvas.drawCircle(0f, 0f, outer, stroke)
        for (t in 0 until 72) {
            val tr = Math.toRadians(t * 5.0)
            val len = if (t % 6 == 0) dp(8f) else dp(4f)
            val c = cos(tr).toFloat()
            val s = sin(tr).toFloat()
            stroke.strokeWidth = if (t % 6 == 0) dp(1.5f) else dp(1f)
            canvas.drawLine(c * outer, s * outer, c * (outer - len), s * (outer - len), stroke)
        }
    }

    private fun drawMinimalBody(canvas: Canvas, g: RingGeometry, sel: Int, a: Float) {
        fill.shader = null
        fill.clearShadowLayer()
        fill.color = Color.argb((228 * a).toInt(), 30, 30, 34)
        canvas.drawCircle(0f, 0f, outer, fill)
        if (sel >= 0) {
            fill.color = Color.argb((255 * a).toInt(), 72, 72, 78)
            canvas.drawPath(wedgePath(g, sel, hub + dp(2f), outer + pop), fill)
        }
        drawDividers(canvas, g, Color.argb((45 * a).toInt(), 255, 255, 255), dp(1f))
    }

    private fun drawItem(canvas: Canvas, g: RingGeometry, index: Int, item: RingItem, isSelected: Boolean, a: Float) {
        val (x0, y0) = g.position(index)
        val push = if (isSelected) pop * 0.6f else 0f
        val rad = Math.toRadians(g.centerAngle(index).toDouble())
        val x = x0 + cos(rad).toFloat() * push
        val y = y0 + sin(rad).toFloat() * push - dp(6f)
        val size = iconSize * (if (isSelected) 1.18f else 1f)
        val half = size / 2f
        tmpRect.set(x - half, y - half, x + half, y + half)
        bitmapPaint.alpha = (255 * a).toInt()

        when (item) {
            is RingItem.App -> {
                val bmp = icons[item.pkg]
                if (bmp != null) {
                    canvas.drawBitmap(bmp, null, tmpRect, bitmapPaint)
                } else {
                    fill.shader = null
                    fill.color = alphaOf(accent, a, 200)
                    canvas.drawCircle(x, y, half * 0.9f, fill)
                }
            }

            is RingItem.Group -> {
                fill.shader = null
                fill.clearShadowLayer()
                fill.color = Color.argb((70 * a).toInt(), 255, 255, 255)
                canvas.drawRoundRect(tmpRect, size * 0.28f, size * 0.28f, fill)
                val apps = item.children.filterIsInstance<RingItem.App>().take(4)
                val cell = size * 0.38f
                val gap = size * 0.06f
                apps.forEachIndexed { i, app ->
                    val cxi = x + if (i % 2 == 0) -(cell / 2 + gap / 2) else (cell / 2 + gap / 2)
                    val cyi = y + if (i < 2) -(cell / 2 + gap / 2) else (cell / 2 + gap / 2)
                    icons[app.pkg]?.let { canvas.drawBitmap(it, null, RectF(cxi - cell / 2, cyi - cell / 2, cxi + cell / 2, cyi + cell / 2), bitmapPaint) }
                }
                if (apps.isEmpty()) {
                    fill.color = alphaOf(accent, a, 220)
                    canvas.drawRoundRect(tmpRect, size * 0.28f, size * 0.28f, fill)
                    drawSparkle(canvas, x, y, half * 0.55f, Color.argb((255 * a).toInt(), 255, 255, 255))
                }
            }

            is RingItem.Command -> {
                fill.shader = null
                fill.color = alphaOf(accent, a, if (isSelected) 230 else 150)
                fill.setShadowLayer(if (style == RingStyle.MINIMAL) 0f else dp(8f), 0f, 0f, alphaOf(accent, a, 200))
                canvas.drawCircle(x, y, half * 0.92f, fill)
                fill.clearShadowLayer()
                val d = commandIcons[item.command]
                if (d != null) {
                    val inset = (size * 0.27f).toInt()
                    d.setBounds((x - half).toInt() + inset, (y - half).toInt() + inset, (x + half).toInt() - inset, (y + half).toInt() - inset)
                    d.alpha = (255 * a).toInt()
                    d.draw(canvas)
                } else {
                    drawSparkle(canvas, x, y, half * 0.55f, Color.argb((255 * a).toInt(), 255, 255, 255))
                }
            }
        }

        label.alpha = (255 * a).toInt()
        label.textSize = if (isSelected) dp(12.5f) else dp(11f)
        label.isFakeBoldText = isSelected
        val chord = (2 * g.iconRadius * sin(Math.toRadians(g.sweep / 2.0))).toFloat()
        val width = chord.coerceIn(dp(64f), dp(104f))
        val text = TextUtils.ellipsize(item.label, label, width, TextUtils.TruncateAt.END).toString()
        canvas.drawText(text, x, y + half + dp(13f), label)
    }

    private fun drawSparkle(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        val path = Path()
        val w = r * 0.28f
        path.moveTo(x, y - r)
        path.quadTo(x + w * 0.3f, y - w * 0.3f, x + r, y)
        path.quadTo(x + w * 0.3f, y + w * 0.3f, x, y + r)
        path.quadTo(x - w * 0.3f, y + w * 0.3f, x - r, y)
        path.quadTo(x - w * 0.3f, y - w * 0.3f, x, y - r)
        path.close()
        fill.shader = null
        fill.color = color
        canvas.drawPath(path, fill)
    }

    private fun drawHub(canvas: Canvas, l: Level, sel: Int, a: Float) {
        fill.shader = null
        stroke.clearShadowLayer()
        when (style) {
            RingStyle.GLOW, RingStyle.FIRE, RingStyle.ELECTRIC -> {
                fill.color = Color.argb((210 * a).toInt(), 8, 10, 20)
                canvas.drawCircle(0f, 0f, hub, fill)
                bloom.color = alphaOf(accent, a, 180)
                bloom.strokeWidth = dp(5f)
                canvas.drawCircle(0f, 0f, hub, bloom)
                stroke.color = alphaOf(accentLight, a, 255)
                stroke.strokeWidth = dp(2f)
                canvas.drawCircle(0f, 0f, hub, stroke)
            }

            RingStyle.RADAR -> {
                fill.color = alphaOf(ColorUtils.blendARGB(accent, Color.BLACK, 0.45f), a, 235)
                canvas.drawCircle(0f, 0f, hub, fill)
            }

            RingStyle.MINIMAL -> {
                fill.color = Color.argb((255 * a).toInt(), 16, 16, 18)
                canvas.drawCircle(0f, 0f, hub, fill)
            }
        }
        val isRoot = l === levels.first()
        if (isRoot) {
            drawLight(canvas, a)
            return
        }
        val title = when {
            sel == RingGeometry.HUB && !isRoot && l === level && sticky -> "Back"
            else -> l.group.label
        }
        hubLabel.alpha = (255 * a).toInt()
        if (isRoot && style != RingStyle.MINIMAL) {
            drawSparkle(canvas, 0f, -dp(9f), dp(7f), alphaOf(accentLight, a, 255))
            canvas.drawText(title, 0f, dp(14f), hubLabel)
        } else {
            canvas.drawText(TextUtils.ellipsize(title, hubLabel, hub * 1.8f, TextUtils.TruncateAt.END).toString(), 0f, dp(5f), hubLabel)
        }
    }

    /** The breathing light in the centre (the same light as the call screen's orb). */
    // The accent pushed to a saturated mid tone, so the light has colour even on pastel themes.
    private val vivid: Int = FloatArray(3).let { hsl ->
        ColorUtils.colorToHSL(accent, hsl)
        hsl[1] = max(hsl[1], 0.75f)
        hsl[2] = hsl[2].coerceIn(0.52f, 0.62f)
        ColorUtils.HSLToColor(hsl)
    }

    private fun drawLight(canvas: Canvas, a: Float) {
        val accent = vivid
        val t = (SystemClock.uptimeMillis() % 4200L) / 4200f
        val breath = (1f - cos(t * 2 * Math.PI).toFloat()) / 2f
        val r = hub * (0.70f + 0.05f * breath)
        val deep = ColorUtils.blendARGB(accent, Color.BLACK, 0.55f)
        // Light spilling into the dark disc.
        fill.shader = RadialGradient(0f, 0f, hub * 1.35f, intArrayOf(alphaOf(accent, a, 200), alphaOf(accent, a, 90), Color.TRANSPARENT), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(0f, 0f, hub * 1.35f, fill)
        // Lit from inside: near-white core, accent body, darker refractive edge.
        fill.shader = RadialGradient(
            -r * 0.16f,
            -r * 0.20f,
            r * 1.2f,
            intArrayOf(
                alphaOf(Color.WHITE, a, 255),
                alphaOf(ColorUtils.blendARGB(accent, Color.WHITE, 0.45f), a, 255),
                alphaOf(accent, a, 255),
                alphaOf(ColorUtils.blendARGB(accent, deep, 0.6f), a, 255),
            ),
            floatArrayOf(0f, 0.32f, 0.72f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(0f, 0f, r, fill)
        // Light drifting inside the glass.
        val drift = (SystemClock.uptimeMillis() % 14000L) / 14000f * 2 * Math.PI
        for (i in 0 until 2) {
            val ang = drift * (1 + i * 0.41) + i * 2.09
            val px = (cos(ang) * r * 0.3).toFloat()
            val py = (sin(ang * 1.17) * r * 0.24).toFloat()
            fill.shader = RadialGradient(px, py, r * 0.7f, intArrayOf(Color.argb((90 * a).toInt(), 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(px, py, r * 0.7f, fill)
        }
        fill.shader = null
        stroke.clearShadowLayer()
        // Soft window sheen on the upper left, as on the call screen's orb.
        fill.shader = RadialGradient(-r * 0.34f, -r * 0.42f, r * 0.5f, intArrayOf(Color.argb((150 * a).toInt(), 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(-r * 0.34f, -r * 0.42f, r * 0.5f, fill)
        fill.shader = null
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = Color.argb((210 * a).toInt(), 255, 255, 255)
        stroke.strokeWidth = r * 0.07f
        tmpRect.set(-r * 0.74f, -r * 0.74f, r * 0.74f, r * 0.74f)
        canvas.drawArc(tmpRect, 205f, 55f, false, stroke)
        // Light refracted back up through the bottom of the glass: a soft crescent, no hard edges.
        fill.shader = RadialGradient(0f, r * 0.35f, r * 0.9f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, alphaOf(ColorUtils.blendARGB(accent, Color.WHITE, 0.5f), a, 90)), floatArrayOf(0f, 0.78f, 1f), Shader.TileMode.CLAMP)
        canvas.save()
        canvas.clipPath(android.graphics.Path().apply { addCircle(0f, 0f, r, android.graphics.Path.Direction.CW) })
        canvas.drawCircle(0f, r * 0.35f, r * 0.9f, fill)
        canvas.restore()
        fill.shader = null
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.color = Color.argb((40 * a).toInt(), 255, 255, 255)
        stroke.strokeWidth = dp(1f)
        canvas.drawCircle(0f, 0f, r * 1.24f, stroke)
        val mic = commandIcons[RingCommand.TALK]
        if (mic != null) {
            fill.shader = RadialGradient(0f, if (sticky) -r * 0.16f else 0f, r * 0.55f, intArrayOf(alphaOf(ColorUtils.blendARGB(accent, Color.BLACK, 0.5f), a, 110), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(0f, if (sticky) -r * 0.16f else 0f, r * 0.55f, fill)
            fill.shader = null
            val s = (r * 0.42f).toInt()
            val dy = if (sticky) -(r * 0.16f).toInt() else 0
            mic.setTint(Color.WHITE)
            mic.setBounds(-s, -s + dy, s, s + dy)
            mic.alpha = (255 * a).toInt()
            mic.draw(canvas)
        }
        if (sticky) {
            val old = hubLabel.color
            hubLabel.color = Color.WHITE
            hubLabel.textSize = dp(8.5f)
            hubLabel.letterSpacing = 0.12f
            hubLabel.setShadowLayer(dp(2f), 0f, 0f, ColorUtils.blendARGB(accent, Color.BLACK, 0.6f))
            hubLabel.alpha = (255 * a).toInt()
            canvas.drawText("TALK", 0f, r * 0.62f, hubLabel)
            hubLabel.color = old
            hubLabel.textSize = dp(13f)
            hubLabel.letterSpacing = 0f
            hubLabel.setShadowLayer(dp(3f), 0f, dp(1f), Color.argb(200, 0, 0, 0))
        }
    }

    private fun drawTrail(canvas: Canvas, now: Long) {
        while (trail.isNotEmpty() && now - trail.first().t > TRAIL_MS) trail.removeFirst()
        if (style == RingStyle.MINIMAL || trail.isEmpty()) return
        fill.shader = null
        trail.forEach { pt ->
            val life = 1f - (now - pt.t) / TRAIL_MS.toFloat()
            fill.color = alphaOf(accent, appear * life, 200)
            fill.setShadowLayer(dp(10f), 0f, 0f, alphaOf(accent, appear * life, 255))
            canvas.drawCircle(pt.x, pt.y, dp(7f) * life, fill)
        }
        fill.clearShadowLayer()
        val last = trail.last()
        fill.color = Color.argb((230 * appear).toInt(), 255, 255, 255)
        canvas.drawCircle(last.x, last.y, dp(3f), fill)
    }

    private fun drawParticles(canvas: Canvas, now: Long, dt: Float) {
        if (particles.isEmpty()) return
        val it = particles.iterator()
        fill.shader = null
        while (it.hasNext()) {
            val p = it.next()
            val age = (now - p.born) / p.life.toFloat()
            if (age >= 1f) {
                it.remove()
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            val life = 1f - age
            val color = if (p.white) Color.WHITE else accentLight
            if (p.ember) {
                // A spark: white-hot when it leaves, cooling through orange to deep red,
                // drawn as a short streak along its path and added onto the light below.
                p.vx += (random.nextFloat() - 0.5f) * dp(160f) * dt
                p.vy -= dp(30f) * dt
                val hot = when {
                    age < 0.1f -> ColorUtils.blendARGB(0xFFFFF4C2.toInt(), 0xFFFF9A1F.toInt(), age / 0.1f)
                    age < 0.5f -> ColorUtils.blendARGB(0xFFFF9A1F.toInt(), 0xFFF0480C.toInt(), (age - 0.1f) / 0.4f)
                    else -> ColorUtils.blendARGB(0xFFF0480C.toInt(), 0xFF8A1606.toInt(), (age - 0.5f) / 0.5f)
                }
                val flick = 0.6f + random.nextFloat() * 0.4f
                val w = max(dp(0.8f), p.size * (0.3f + 0.7f * life))
                val speed = max(1f, hypot(p.vx, p.vy))
                val tail = min(dp(6f), speed * 0.03f)
                // Soft glow, then a hot point with only a short tail.
                fill.color = alphaOf(hot, appear * life * flick, 70)
                canvas.drawCircle(p.x, p.y, w * 2.2f, fill)
                ember.strokeWidth = w
                ember.color = alphaOf(hot, appear * life * flick, 255)
                canvas.drawLine(p.x, p.y, p.x - p.vx / speed * tail, p.y - p.vy / speed * tail, ember)
            } else if (style == RingStyle.GLOW && p.size > dp(3f)) {
                drawSparkle(canvas, p.x, p.y, p.size * 1.6f * life, alphaOf(color, appear * life, 255))
            } else {
                fill.color = alphaOf(color, appear * life, 255)
                canvas.drawCircle(p.x, p.y, max(dp(0.8f), p.size * life), fill)
            }
        }
    }

    private companion object {
        const val OPEN_MS = 220L
        const val CLOSE_MS = 140L
        const val DWELL_MS = 300L
        const val TRAIL_MS = 600L
    }
}
