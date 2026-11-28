/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ring

import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import com.android.launcher3.Launcher
import com.android.launcher3.util.Themes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One installed app, as the home screen needs it. */
internal data class HomeApp(val pkg: String, val label: String)

/**
 * The ring home screen. Apps sit on concentric dials anchored to one edge of
 * the screen; drag a dial and it spins, let go and it settles with an app under
 * your thumb. The hub (on the edge, level with your thumb) opens the drawer,
 * which is the same idea with every app on it.
 *
 * Drawn on a plain canvas rather than with the workspace grid: the point is
 * that the home screen is not a grid. See [RingHome] for attaching it.
 */
class RingHomeView internal constructor(
    private val launcher: Launcher,
) : View(launcher) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var hand = HomeHand.RIGHT
    private var mode = Mode.HOME
    private var iconRunnable: Runnable? = null

    private enum class Mode { HOME, DRAWER }

    private val accent = Themes.getColorAccent(launcher)
    private val accentSoft = ColorUtils.setAlphaComponent(accent, 90)

    // ---- data ---------------------------------------------------------------

    private var homeApps: List<HomeApp> = emptyList()
    private var allApps: List<HomeApp> = emptyList()
    private var icons: Map<String, Bitmap> = emptyMap()

    private inner class Ring(
        val apps: List<HomeApp>,
        val radiusFrac: Float,
        val span: Float,
        val iconFrac: Float,
        step: Float? = null,
    ) {
        val dial = Dial(apps.size, step)
        var rotation = 0f
        var velocity = 0f
        var animator: ValueAnimator? = null
        var radius = 0f
        var iconSize = 0f
        var entering = 0f

        fun angleOf(index: Int) = dial.angle(index, rotation)

        fun place() {
            val w = width.toFloat()
            radius = radiusFrac * w
            iconSize = iconFrac * w
        }

        fun settle() {
            animator?.cancel()
            val from = rotation
            val to = dial.snap(rotation)
            if (abs(to - from) < 0.0005f) return
            animator = ValueAnimator.ofFloat(from, to).apply {
                duration = 220
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener {
                    rotation = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun fling(angle: Float) {
            animator?.cancel()
            val from = rotation
            val to = dial.flingTarget(rotation, velocity)
            val virtual = dial.snapAround(angle, to)
            animator = ValueAnimator.ofFloat(from, virtual).apply {
                duration = 380
                interpolator = DecelerateInterpolator(1.9f)
                addUpdateListener {
                    rotation = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    private var homeRings: List<Ring> = emptyList()
    private var drawerRings: List<Ring> = emptyList()

    // ---- layout -------------------------------------------------------------

    private var anchorX = 0f
    private var anchorY = 0f
    private var hubRadius = 0f
    private var hubPressed = false
    private var appear = 0f
    private var drawerMix = 0f

    // ---- gesture ------------------------------------------------------------

    private var dragging: Ring? = null
    private var dragAngle = 0f
    private var dragAt = 0L
    private var pressed: Pair<Ring, Int>? = null
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var ring: DogoRingView? = null

    private val hold = Runnable {
        if (moved || pressed != null || mode != Mode.HOME) return@Runnable
        val opened = DogoRing.open(launcher, downX + offsetX, downY + offsetY)
        if (opened != null) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            ring = opened
            opened.start(downX + offsetX, downY + offsetY)
        } else {
            launcher.showDefaultOptions(downX, downY)
        }
    }

    // ---- paints -------------------------------------------------------------

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(11f)
        setShadowLayer(dp(4f), 0f, dp(1f), Color.argb(210, 0, 0, 0))
    }
    private val clock = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(46f)
        setShadowLayer(dp(10f), 0f, dp(2f), Color.argb(120, 0, 0, 0))
    }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(Color.WHITE, 190)
        textSize = dp(12f)
        letterSpacing = 0.02f
    }
    private val tiny = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(Color.WHITE, 150)
        textSize = dp(10f)
        letterSpacing = 0.08f
    }
    private val tmpRect = RectF()
    private val arcRect = RectF()

    private var offsetX = 0f
    private var offsetY = 0f

    private val timeFormat = SimpleDateFormat("H:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    private var battery = -1
    private var batteryAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, msToNextMinute())
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = false
        contentDescription = "Dogo ring home"
    }

    // ---- public surface -----------------------------------------------------

    internal fun setData(home: List<HomeApp>, all: List<HomeApp>, icons: Map<String, Bitmap>, hand: HomeHand) {
        this.homeApps = home
        this.allApps = all
        this.icons = icons
        this.hand = hand
        this.mode = Mode.HOME
        rebuild()
    }

    internal fun setHand(newHand: HomeHand) {
        if (hand == newHand) return
        hand = newHand
        rebuild()
    }

    internal fun showDrawer() {
        if (mode == Mode.DRAWER) return
        mode = Mode.DRAWER
        rebuild()
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    internal fun closeDrawer(): Boolean {
        if (mode != Mode.DRAWER) return false
        mode = Mode.HOME
        rebuild()
        isFocusable = false
        isFocusableInTouchMode = false
        clearFocus()
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        return true
    }

    /** Back (and Escape, for a keyboard) closes the drawer. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (mode == Mode.DRAWER && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
            closeDrawer()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    internal fun isDrawerOpen() = mode == Mode.DRAWER

    internal fun refreshApps(home: List<HomeApp>, all: List<HomeApp>, icons: Map<String, Bitmap>) {
        homeApps = home
        allApps = all
        this.icons = icons
        rebuild()
    }

    // ---- construction -------------------------------------------------------

    private fun rebuild() {
        if (width == 0 || height == 0) return
        val w = width.toFloat()
        val plan = if (mode == Mode.HOME) RingHomePlan.home(hand) else RingHomePlan.drawer(hand, allApps.size)
        anchorX = plan.anchorX * w
        anchorY = plan.anchorY * height
        hubRadius = min(w, height.toFloat()) * 0.085f

        val apps = if (mode == Mode.HOME) homeApps else allApps
        val counts = if (mode == Mode.HOME) {
            RingHomePlan.homeSlots(plan, w, apps.size)
        } else {
            RingHomePlan.slots(plan, apps.size)
        }
        var at = 0
        val rings = ArrayList<Ring>()
        counts.forEachIndexed { i, n ->
            if (n <= 0) return@forEachIndexed
            val slice = apps.subList(at, min(at + n, apps.size))
            at += n
            val step = if (mode == Mode.HOME) RingHomePlan.homeStep(plan, w, i) else null
            val ring = Ring(slice, plan.radiusOf(i), plan.span, plan.icon, step)
            ring.place()
            // The first slot starts at one end of the visible arc, so a full
            // ring fills the window exactly.
            val facing = if (hand == HomeHand.LEFT) 0f else Math.PI.toFloat()
            val used = ring.dial.step * ring.apps.size
            ring.rotation = facing - ring.span / 2f + (ring.span - used) / 2f + ring.dial.step / 2f
            rings += ring
        }
        if (mode == Mode.HOME) homeRings = rings else drawerRings = rings
        updateDescription()
        invalidate()
    }

    private val activeRings: List<Ring> get() = if (mode == Mode.HOME) homeRings else drawerRings

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val location = IntArray(2)
        getLocationOnScreen(location)
        offsetX = location[0].toFloat()
        offsetY = location[1].toFloat()
        rebuild()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshBattery()
        post(ticker)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            addUpdateListener {
                appear = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        homeRings.forEach { it.animator?.cancel() }
        drawerRings.forEach { it.animator?.cancel() }
        super.onDetachedFromWindow()
    }

    // ---- drawing ------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f) return

        drawBackdrop(canvas, w, h)
        val rings = activeRings
        val mix = if (mode == Mode.DRAWER) 1f else 0f
        rings.forEachIndexed { i, r -> drawRing(canvas, r, i, appear * (1f - (1f - 0.25f) * mix)) }
        drawHub(canvas)
        if (mode == Mode.HOME) drawClock(canvas, w, h)
        drawPressedLabel(canvas)
    }

    /** A glass panel that hugs the anchored edge, and a soft vignette. */
    private fun drawBackdrop(canvas: Canvas, w: Float, h: Float) {
        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        glow.shader = RadialGradient(
            anchorX, anchorY, w * 0.95f,
            intArrayOf(
                ColorUtils.setAlphaComponent(accent, if (mode == Mode.DRAWER) 26 else 34),
                ColorUtils.setAlphaComponent(Color.BLACK, 40),
                ColorUtils.setAlphaComponent(Color.BLACK, 0),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, glow)

        val scrim = Paint(Paint.ANTI_ALIAS_FLAG)
        val leftEdge = hand == HomeHand.LEFT
        scrim.shader = LinearGradient(
            if (leftEdge) 0f else w, 0f, if (leftEdge) w * 0.45f else w * 0.55f, 0f,
            ColorUtils.setAlphaComponent(Color.BLACK, if (mode == Mode.DRAWER) 120 else 70),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, scrim)
    }

    private fun drawRing(canvas: Canvas, ring: Ring, index: Int, alpha: Float) {
        val facing = if (hand == HomeHand.LEFT) 0f else Math.PI.toFloat()
        val startDeg = Math.toDegrees((facing - ring.span / 2f).toDouble()).toFloat()
        val sweepDeg = Math.toDegrees(ring.span.toDouble()).toFloat()

        // The track the dial slides along.
        fill.shader = null
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = dp(1f)
        fill.color = ColorUtils.setAlphaComponent(Color.WHITE, (22 * alpha).toInt())
        arcRect.set(anchorX - ring.radius, anchorY - ring.radius, anchorX + ring.radius, anchorY + ring.radius)
        canvas.drawArc(arcRect, startDeg, sweepDeg, false, fill)

        ring.apps.forEachIndexed { i, app ->
            val a = ring.angleOf(i)
            if (!RingHomePlan.inWindow(a, hand == HomeHand.LEFT, ring.span)) return@forEachIndexed
            val edge = abs(Dial.normalize(a - facing))
            val fade = ((ring.span / 2f - edge) / 0.22f).coerceIn(0f, 1f)
            val (x, y) = RingHomePlan.position(anchorX, anchorY, ring.radius, a)
            val isPressed = pressed?.let { it.first === ring && it.second == i } == true
            val scale = if (isPressed) 1.16f else 1f
            val size = ring.iconSize * scale
            val half = size / 2f
            tmpRect.set(x - half, y - half, x + half, y + half)

            // A dim disc under each icon keeps it readable on busy wallpapers.
            fill.style = Paint.Style.FILL
            fill.color = ColorUtils.setAlphaComponent(Color.BLACK, (46 * alpha * fade).toInt())
            canvas.drawCircle(x, y, half * 1.02f, fill)

            val bmp = icons[app.pkg]
            bitmapPaint.alpha = (255 * alpha * fade).toInt().coerceIn(0, 255)
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, tmpRect, bitmapPaint)
            } else {
                fill.color = ColorUtils.setAlphaComponent(accent, (200 * alpha * fade).toInt())
                canvas.drawCircle(x, y, half * 0.86f, fill)
            }
            bitmapPaint.alpha = 255

            if (isPressed) {
                stroke.strokeWidth = dp(1.5f)
                stroke.color = ColorUtils.setAlphaComponent(accentSoft, (220 * alpha).toInt())
                canvas.drawCircle(x, y, half * 1.28f, stroke)
            }
        }
    }

    private fun drawHub(canvas: Canvas) {
        val r = hubRadius * (if (mode == Mode.DRAWER) 0.94f else 1f) * (0.94f + 0.06f * appear)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        glow.shader = RadialGradient(
            anchorX, anchorY, r * 2.4f,
            intArrayOf(ColorUtils.setAlphaComponent(accent, 110), ColorUtils.setAlphaComponent(accent, 26), ColorUtils.setAlphaComponent(accent, 0)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(anchorX, anchorY, r * 2.1f, glow)

        fill.shader = RadialGradient(
            anchorX - r * 0.35f, anchorY - r * 0.45f, r * 1.7f,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, if (hubPressed) 96 else 64),
                ColorUtils.setAlphaComponent(Color.WHITE, 26),
                ColorUtils.setAlphaComponent(Color.BLACK, 70),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        fill.style = Paint.Style.FILL
        canvas.drawCircle(anchorX, anchorY, r, fill)

        stroke.strokeWidth = dp(1.4f)
        stroke.color = ColorUtils.setAlphaComponent(Color.WHITE, 140)
        canvas.drawCircle(anchorX, anchorY, r, stroke)

        // Nine dots: the drawer. Becomes a cross while the drawer is open.
        val dot = r * 0.09f
        fill.shader = null
        fill.color = ColorUtils.setAlphaComponent(Color.WHITE, 230)
        if (mode == Mode.DRAWER) {
            stroke.strokeWidth = dp(2f)
            stroke.color = ColorUtils.setAlphaComponent(Color.WHITE, 230)
            val arm = r * 0.34f
            canvas.drawLine(anchorX - arm, anchorY - arm, anchorX + arm, anchorY + arm, stroke)
            canvas.drawLine(anchorX - arm, anchorY + arm, anchorX + arm, anchorY - arm, stroke)
        } else {
            val gap = r * 0.30f
            for (gy in -1..1) for (gx in -1..1) {
                canvas.drawCircle(anchorX + gx * gap, anchorY + gy * gap, dot, fill)
            }
        }
    }

    private fun drawClock(canvas: Canvas, w: Float, h: Float) {
        val mirror = hand == HomeHand.RIGHT
        val x = if (mirror) w * 0.075f else w * 0.925f
        val align = if (mirror) Paint.Align.LEFT else Paint.Align.RIGHT
        clock.textAlign = align
        small.textAlign = align
        tiny.textAlign = align
        clock.alpha = (255 * appear).toInt()
        small.alpha = (255 * appear).toInt()
        tiny.alpha = (255 * appear).toInt()

        val top = h * 0.115f
        // A soft dark pool behind the clock so it reads over a bright wallpaper.
        val pool = Paint(Paint.ANTI_ALIAS_FLAG)
        pool.shader = RadialGradient(
            x + if (mirror) w * 0.10f else -w * 0.10f, top + dp(6f), w * 0.42f,
            intArrayOf(ColorUtils.setAlphaComponent(Color.BLACK, 96), ColorUtils.setAlphaComponent(Color.BLACK, 0)),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, top + dp(70f), pool)
        canvas.drawText(timeFormat.format(Date()), x, top, clock)
        canvas.drawText(dateFormat.format(Date()), x, top + dp(26f), small)
        if (battery >= 0) {
            canvas.drawText("$battery%", x, top + dp(44f), tiny)
        }
    }

    private fun drawPressedLabel(canvas: Canvas) {
        val p = pressed ?: return
        val ring = p.first
        val i = p.second
        val app = ring.apps.getOrNull(i) ?: return
        val (x, y) = RingHomePlan.position(anchorX, anchorY, ring.radius, ring.angleOf(i))
        label.alpha = 255
        canvas.drawText(app.label, x, y + ring.iconSize * 0.92f, label)
    }

    private fun updateDescription() {
        val names = activeRings.flatMap { it.apps }.take(24).joinToString { it.label }
        contentDescription = if (mode == Mode.HOME) "Dogo ring home: $names" else "Dogo app drawer: $names"
    }

    // ---- touch --------------------------------------------------------------

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = SystemClock.uptimeMillis()
                downX = x
                downY = y
                moved = false
                pressed = hitIcon(x, y)
                hubPressed = hypot(x - anchorX, y - anchorY) < hubRadius
                if (pressed != null) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                dragging = if (pressed != null || hubPressed) null else hitRing(x, y)
                dragAngle = angleOf(x, y)
                dragAt = SystemClock.uptimeMillis()
                dragging?.let { it.animator?.cancel(); it.velocity = 0f }
                postDelayed(hold, holdMs())
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (hypot(x - downX, y - downY) > dp(10f)) {
                    if (!moved) {
                        moved = true
                        removeCallbacks(hold)
                        if (pressed != null) {
                            pressed = null
                            dragging = hitRing(x, y)
                            dragAngle = angleOf(x, y)
                        }
                    }
                    val d = dragging
                    if (d != null) {
                        val now = SystemClock.uptimeMillis()
                        val angle = angleOf(x, y)
                        val delta = Dial.delta(dragAngle, angle)
                        d.rotation += delta
                        val dt = max(now - dragAt, 1L)
                        d.velocity = 0.7f * d.velocity + 0.3f * (delta / (dt / 1000f))
                        dragAngle = angle
                        dragAt = now
                        invalidate()
                    }
                }
                ring?.onFingerEvent(ev)
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                // In the drawer, a swipe from empty space towards the anchored
                // edge closes it (spinning a dial is angular, so it never fires).
                val outer = activeRings.maxOfOrNull { it.radius } ?: 0f
                val startedOutside = hypot(downX - anchorX, downY - anchorY) > outer * 1.12f
                if (mode == Mode.DRAWER && dragging == null && pressed == null && startedOutside &&
                    hypot(x - downX, y - downY) > dp(70f)
                ) {
                    val outward = if (hand == HomeHand.RIGHT) x - downX else downX - x
                    if (outward > 0) {
                        closeDrawer()
                        return true
                    }
                }
                val forwarded = ring
                if (forwarded != null) {
                    forwarded.onFingerEvent(ev)
                    ring = null
                    pressed = null
                    dragging = null
                    hubPressed = false
                    invalidate()
                    return true
                }
                val tapped = pressed
                val d = dragging
                if (tapped != null && !moved) {
                    launch(tapped.first, tapped.second)
                } else if (hubPressed && !moved) {
                    if (mode == Mode.DRAWER) closeDrawer() else showDrawer()
                } else if (mode == Mode.DRAWER && !moved) {
                    closeDrawer()
                } else if (d != null) {
                    val angle = angleOf(x, y)
                    if (abs(d.velocity) > 1.2f) {
                        d.fling(angle)
                    } else {
                        // Land the slot nearest the thumb on the thumb.
                        val from = d.rotation
                        val to = d.dial.snapAround(angle, from)
                        d.animator?.cancel()
                        d.animator = ValueAnimator.ofFloat(from, to).apply {
                            duration = 180
                            interpolator = DecelerateInterpolator(1.5f)
                            addUpdateListener {
                                d.rotation = it.animatedValue as Float
                                invalidate()
                            }
                            start()
                        }
                    }
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                pressed = null
                dragging = null
                hubPressed = false
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                ring?.onFingerEvent(ev)
                ring = null
                pressed = null
                dragging = null
                hubPressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    /** The ring menu opens on a hold that is long enough not to fight a spin. */
    private fun holdMs(): Long = (DogoRing.config(launcher).takeIf { it.enabled }?.holdMs ?: 420L).coerceAtLeast(340L)

    private fun angleOf(x: Float, y: Float): Float = kotlin.math.atan2(y - anchorY, x - anchorX)

    private fun hitIcon(x: Float, y: Float): Pair<Ring, Int>? {
        activeRings.forEach { ring ->
            val band = hypot(x - anchorX, y - anchorY)
            if (abs(band - ring.radius) > ring.iconSize * 0.7f) return@forEach
            ring.apps.forEachIndexed { i, _ ->
                val (ix, iy) = RingHomePlan.position(anchorX, anchorY, ring.radius, ring.angleOf(i))
                if (hypot(x - ix, y - iy) < ring.iconSize * 0.62f) return ring to i
            }
        }
        return null
    }

    /** The dial whose annulus the finger is on, nearest ring first. */
    private fun hitRing(x: Float, y: Float): Ring? {
        val band = hypot(x - anchorX, y - anchorY)
        val angle = angleOf(x, y)
        return activeRings
            .filter { RingHomePlan.inWindow(angle, hand == HomeHand.LEFT, it.span) }
            .minByOrNull { abs(it.radius - band) }
            ?.takeIf { abs(it.radius - band) < it.iconSize * 1.5f }
    }

    private fun launch(ring: Ring, index: Int) {
        val app = ring.apps.getOrNull(index) ?: return
        val apps = launcher.getSystemService(LauncherApps::class.java)
        val user = Process.myUserHandle()
        val activity = apps?.getActivityList(app.pkg, user)?.firstOrNull() ?: return
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        val (x, y) = RingHomePlan.position(anchorX, anchorY, ring.radius, ring.angleOf(index))
        val size = ring.iconSize.toInt()
        val bounds = Rect(
            (x - size / 2f + offsetX).toInt(),
            (y - size / 2f + offsetY).toInt(),
            (x + size / 2f + offsetX).toInt(),
            (y + size / 2f + offsetY).toInt(),
        )
        val options = ActivityOptions.makeScaleUpAnimation(this, bounds.left, bounds.top, size, size).toBundle()
        runCatching { apps?.startMainActivity(activity.componentName, user, bounds, options) }
        RingHome.bump(launcher, app.pkg)
    }

    // ---- housekeeping -------------------------------------------------------

    private fun refreshBattery() {
        val now = SystemClock.uptimeMillis()
        if (now - batteryAt < 30_000L) return
        batteryAt = now
        battery = runCatching {
            val intent = launcher.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level < 0 || scale <= 0) -1 else (level * 100 / scale)
        }.getOrDefault(-1)
    }

    private fun msToNextMinute(): Long {
        val now = System.currentTimeMillis()
        return 60_000L - (now % 60_000L) + 40L
    }
}
