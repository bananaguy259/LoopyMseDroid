package com.loopymse.droid.emulator

import android.content.Context
import android.graphics.*
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.loopymse.droid.util.PreferencesManager
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * VirtualGamepadView
 *
 * A fully custom View that renders and handles the Casio Loopy's complete
 * button layout as a transparent touch overlay on top of the emulator surface.
 *
 * Loopy button set:
 *   Left side:  D-Pad (Up/Down/Left/Right)
 *   Top-left:   L1 shoulder
 *   Top-right:  R1 shoulder
 *   Right side: A, B, C, D (diamond layout)
 *   Center:     Start
 *
 * Multi-touch: each pointer tracked independently.
 * Haptics: short vibration on press (optional, configurable).
 * Opacity: configurable 0–100%.
 * Auto-hide: when a physical controller is connected.
 */
class VirtualGamepadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ─── Loopy button codes (match Input::PadButton in input.h) ──────────────
    companion object {
        const val PAD_START = 0x0002
        const val PAD_L1    = 0x0004
        const val PAD_R1    = 0x0008
        const val PAD_A     = 0x0010
        const val PAD_D     = 0x0020
        const val PAD_C     = 0x0040
        const val PAD_B     = 0x0080
        const val PAD_UP    = 0x0100
        const val PAD_DOWN  = 0x0200
        const val PAD_LEFT  = 0x0400
        const val PAD_RIGHT = 0x0800
    }

    // ─── Tuneable layout constants (dp-independent; scaled in onSizeChanged) ─
    private var dpadRadius    = 0f  // Radius of the D-pad circle
    private var dpadCx        = 0f  // D-pad center X
    private var dpadCy        = 0f  // D-pad center Y
    private var btnRadius     = 0f  // Radius of each face button
    private var faceCx        = 0f  // Face button cluster center X
    private var faceCy        = 0f  // Face button cluster center Y
    private var faceSpread    = 0f  // Distance from center to each face button
    private var shoulderH     = 0f  // Shoulder button height
    private var shoulderW     = 0f  // Shoulder button width
    private var startR        = 0f  // Start button radius
    private var startCx       = 0f
    private var startCy       = 0f
    private var l1Rect        = RectF()
    private var r1Rect        = RectF()

    // ─── Paint objects ────────────────────────────────────────────────────────
    private val fillPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowPaint  = Paint(Paint.ANTI_ALIAS_FLAG)

    // ─── State ────────────────────────────────────────────────────────────────
    /** Which buttons are currently being held down */
    private val pressedButtons = mutableSetOf<Int>()
    /** Maps pointer ID -> set of buttons that pointer is responsible for */
    private val pointerButtonMap = mutableMapOf<Int, Set<Int>>()

    private var opacity: Float = 0.7f
    private var isVisible: Boolean = true

    // ─── Haptics ──────────────────────────────────────────────────────────────
    private val vibrator: Vibrator? by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
    private var hapticsEnabled = true

    init {
        // Transparent background — the game shows through
        setBackgroundColor(Color.TRANSPARENT)

        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.argb(80, 255, 255, 255)

        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 2f
        strokePaint.color = Color.argb(120, 255, 255, 255)

        textPaint.color = Color.argb(200, 255, 255, 255)
        textPaint.textAlign = Paint.Align.CENTER

        arrowPaint.style = Paint.Style.FILL
        arrowPaint.color = Color.argb(180, 255, 255, 255)
    }

    // ─── Public configuration ─────────────────────────────────────────────────

    fun configure(prefs: PreferencesManager) {
        opacity = prefs.gamepadOpacity / 100f
        isVisible = prefs.showGamepad
        hapticsEnabled = prefs.hapticsEnabled
        visibility = if (isVisible) VISIBLE else INVISIBLE
        invalidate()
    }

    fun setOpacity(percent: Int) {
        opacity = percent / 100f
        invalidate()
    }

    fun setGamepadVisible(visible: Boolean) {
        isVisible = visible
        visibility = if (visible) VISIBLE else INVISIBLE
    }

    // ─── Layout ───────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = min(w, h)

        // Scale everything relative to screen height for consistent feel on all phones
        dpadRadius    = side * 0.16f
        btnRadius     = side * 0.055f
        faceSpread    = side * 0.115f
        shoulderH     = side * 0.07f
        shoulderW     = side * 0.18f
        startR        = side * 0.038f

        val margin    = side * 0.06f

        // D-pad: left side, vertically centered with slight upward bias
        dpadCx = margin + dpadRadius
        dpadCy = h * 0.62f

        // Face buttons: right side, mirror of D-pad
        faceCx = w - margin - dpadRadius
        faceCy = dpadCy

        // Start: center-bottom
        startCx = w * 0.5f
        startCy = h * 0.88f

        // L1: top-left
        val l1X = dpadCx - shoulderW * 0.1f
        val l1Y = h * 0.10f
        l1Rect = RectF(l1X, l1Y, l1X + shoulderW, l1Y + shoulderH)

        // R1: top-right
        val r1X = faceCx + shoulderW * 0.1f - shoulderW
        val r1Y = h * 0.10f
        r1Rect = RectF(r1X, r1Y, r1X + shoulderW, r1Y + shoulderH)

        textPaint.textSize = btnRadius * 0.85f
    }

    // ─── Drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (!isVisible) return
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())

        val baseAlpha = (opacity * 255).toInt().coerceIn(0, 255)

        drawDpad(canvas, baseAlpha)
        drawFaceButtons(canvas, baseAlpha)
        drawShoulderButtons(canvas, baseAlpha)
        drawStartButton(canvas, baseAlpha)

        canvas.restore()
    }

    private fun drawDpad(canvas: Canvas, baseAlpha: Int) {
        // Outer ring
        strokePaint.alpha = (baseAlpha * 0.6f).toInt()
        canvas.drawCircle(dpadCx, dpadCy, dpadRadius, strokePaint)

        // Four directional zones with subtle fill + arrow indicator
        for ((button, angle) in listOf(
            PAD_UP to 270f, PAD_DOWN to 90f, PAD_LEFT to 180f, PAD_RIGHT to 0f
        )) {
            val pressed = button in pressedButtons
            val alpha = if (pressed) (baseAlpha * 1.4f).toInt().coerceAtMost(255) else (baseAlpha * 0.35f).toInt()
            fillPaint.alpha = alpha

            val path = dpadSectorPath(angle)
            canvas.drawPath(path, fillPaint)

            // Arrow
            drawArrow(canvas, button, baseAlpha)
        }

        // Center circle divider
        fillPaint.alpha = (baseAlpha * 0.5f).toInt()
        canvas.drawCircle(dpadCx, dpadCy, dpadRadius * 0.28f, fillPaint)
    }

    private fun dpadSectorPath(angleDeg: Float): Path {
        val path = Path()
        val inner = dpadRadius * 0.3f
        val outer = dpadRadius * 0.98f
        val halfArc = 42f
        path.addArc(
            dpadCx - outer, dpadCy - outer,
            dpadCx + outer, dpadCy + outer,
            angleDeg - halfArc, halfArc * 2
        )
        val startRad = Math.toRadians((angleDeg + halfArc).toDouble())
        val endRad   = Math.toRadians((angleDeg - halfArc).toDouble())
        path.lineTo(
            (dpadCx + inner * Math.cos(startRad)).toFloat(),
            (dpadCy + inner * Math.sin(startRad)).toFloat()
        )
        path.addArc(
            dpadCx - inner, dpadCy - inner,
            dpadCx + inner, dpadCy + inner,
            angleDeg + halfArc, -halfArc * 2
        )
        path.close()
        return path
    }

    private fun drawArrow(canvas: Canvas, button: Int, baseAlpha: Int) {
        arrowPaint.alpha = baseAlpha
        val dist   = dpadRadius * 0.68f
        val size   = dpadRadius * 0.14f
        val path   = Path()

        when (button) {
            PAD_UP -> {
                val cx = dpadCx; val cy = dpadCy - dist
                path.moveTo(cx, cy - size)
                path.lineTo(cx - size, cy + size * 0.6f)
                path.lineTo(cx + size, cy + size * 0.6f)
                path.close()
            }
            PAD_DOWN -> {
                val cx = dpadCx; val cy = dpadCy + dist
                path.moveTo(cx, cy + size)
                path.lineTo(cx - size, cy - size * 0.6f)
                path.lineTo(cx + size, cy - size * 0.6f)
                path.close()
            }
            PAD_LEFT -> {
                val cx = dpadCx - dist; val cy = dpadCy
                path.moveTo(cx - size, cy)
                path.lineTo(cx + size * 0.6f, cy - size)
                path.lineTo(cx + size * 0.6f, cy + size)
                path.close()
            }
            PAD_RIGHT -> {
                val cx = dpadCx + dist; val cy = dpadCy
                path.moveTo(cx + size, cy)
                path.lineTo(cx - size * 0.6f, cy - size)
                path.lineTo(cx - size * 0.6f, cy + size)
                path.close()
            }
        }
        canvas.drawPath(path, arrowPaint)
    }

    private fun drawFaceButtons(canvas: Canvas, baseAlpha: Int) {
        // Diamond layout: A=right, B=bottom, C=left, D=top
        val positions = mapOf(
            PAD_A to Pair(faceCx + faceSpread, faceCy),
            PAD_B to Pair(faceCx, faceCy + faceSpread),
            PAD_C to Pair(faceCx - faceSpread, faceCy),
            PAD_D to Pair(faceCx, faceCy - faceSpread)
        )
        val labels = mapOf(PAD_A to "A", PAD_B to "B", PAD_C to "C", PAD_D to "D")

        for ((button, pos) in positions) {
            val pressed = button in pressedButtons
            val fillAlpha = if (pressed) (baseAlpha * 1.4f).toInt().coerceAtMost(255)
                            else (baseAlpha * 0.4f).toInt()
            fillPaint.alpha = fillAlpha
            strokePaint.alpha = (baseAlpha * 0.7f).toInt()

            canvas.drawCircle(pos.first, pos.second, btnRadius, fillPaint)
            canvas.drawCircle(pos.first, pos.second, btnRadius, strokePaint)

            textPaint.alpha = baseAlpha
            canvas.drawText(
                labels[button] ?: "",
                pos.first,
                pos.second + textPaint.textSize * 0.38f,
                textPaint
            )
        }
    }

    private fun drawShoulderButtons(canvas: Canvas, baseAlpha: Int) {
        for ((button, rect) in listOf(PAD_L1 to l1Rect, PAD_R1 to r1Rect)) {
            val pressed = button in pressedButtons
            val fillAlpha = if (pressed) (baseAlpha * 1.4f).toInt().coerceAtMost(255)
                            else (baseAlpha * 0.4f).toInt()
            fillPaint.alpha = fillAlpha
            strokePaint.alpha = (baseAlpha * 0.7f).toInt()

            canvas.drawRoundRect(rect, 12f, 12f, fillPaint)
            canvas.drawRoundRect(rect, 12f, 12f, strokePaint)

            textPaint.alpha = baseAlpha
            canvas.drawText(
                if (button == PAD_L1) "L" else "R",
                rect.centerX(), rect.centerY() + textPaint.textSize * 0.38f,
                textPaint
            )
        }
    }

    private fun drawStartButton(canvas: Canvas, baseAlpha: Int) {
        val pressed = PAD_START in pressedButtons
        val fillAlpha = if (pressed) (baseAlpha * 1.4f).toInt().coerceAtMost(255)
                        else (baseAlpha * 0.4f).toInt()
        fillPaint.alpha = fillAlpha
        strokePaint.alpha = (baseAlpha * 0.7f).toInt()

        canvas.drawCircle(startCx, startCy, startR, fillPaint)
        canvas.drawCircle(startCx, startCy, startR, strokePaint)

        textPaint.textSize = startR * 0.65f
        textPaint.alpha = baseAlpha
        canvas.drawText("START", startCx, startCy + textPaint.textSize * 0.38f, textPaint)
        textPaint.textSize = btnRadius * 0.85f
    }

    // ─── Touch handling ───────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionIndex  = event.actionIndex
        val pointerId    = event.getPointerId(actionIndex)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                val buttons = buttonsAtPoint(x, y)
                if (buttons.isNotEmpty()) {
                    pointerButtonMap[pointerId] = buttons
                    buttons.forEach { btn ->
                        if (pressedButtons.add(btn)) {
                            EmulatorBridge.setButtonState(btn, true)
                            triggerHaptic()
                        }
                    }
                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // For each active pointer, re-evaluate which button it's over
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    val newButtons = buttonsAtPoint(x, y)
                    val oldButtons = pointerButtonMap[pid] ?: emptySet()

                    // Released buttons (pointer moved away)
                    (oldButtons - newButtons).forEach { btn ->
                        pressedButtons.remove(btn)
                        EmulatorBridge.setButtonState(btn, false)
                    }
                    // Newly pressed buttons (pointer moved onto)
                    (newButtons - oldButtons).forEach { btn ->
                        if (pressedButtons.add(btn)) {
                            EmulatorBridge.setButtonState(btn, true)
                            triggerHaptic()
                        }
                    }

                    if (newButtons.isEmpty()) {
                        pointerButtonMap.remove(pid)
                    } else {
                        pointerButtonMap[pid] = newButtons
                    }
                }
                invalidate()
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                val buttons = pointerButtonMap.remove(pointerId) ?: emptySet()
                buttons.forEach { btn ->
                    // Only release if no OTHER pointer is also holding this button
                    val stillHeld = pointerButtonMap.values.any { btn in it }
                    if (!stillHeld) {
                        pressedButtons.remove(btn)
                        EmulatorBridge.setButtonState(btn, false)
                    }
                }
                invalidate()
            }
        }

        return true
    }

    // ─── Hit detection ────────────────────────────────────────────────────────

    private fun buttonsAtPoint(x: Float, y: Float): Set<Int> {
        val result = mutableSetOf<Int>()

        // D-Pad: check if inside dpad circle first
        val dpadDist = dist(x, y, dpadCx, dpadCy)
        if (dpadDist <= dpadRadius) {
            val dx = x - dpadCx
            val dy = y - dpadCy
            val deadZone = dpadRadius * 0.25f

            if (dpadDist > deadZone) {
                // Primary axis: pick the dominant direction
                if (abs(dy) > abs(dx)) {
                    result.add(if (dy < 0) PAD_UP else PAD_DOWN)
                    // Diagonal: also add horizontal if close to 45°
                    if (abs(dx) > dpadRadius * 0.35f) {
                        result.add(if (dx < 0) PAD_LEFT else PAD_RIGHT)
                    }
                } else {
                    result.add(if (dx < 0) PAD_LEFT else PAD_RIGHT)
                    if (abs(dy) > dpadRadius * 0.35f) {
                        result.add(if (dy < 0) PAD_UP else PAD_DOWN)
                    }
                }
            }
        }

        // Face buttons: A (right), B (bottom), C (left), D (top)
        val facePositions = mapOf(
            PAD_A to Pair(faceCx + faceSpread, faceCy),
            PAD_B to Pair(faceCx, faceCy + faceSpread),
            PAD_C to Pair(faceCx - faceSpread, faceCy),
            PAD_D to Pair(faceCx, faceCy - faceSpread)
        )
        facePositions.forEach { (button, pos) ->
            if (dist(x, y, pos.first, pos.second) <= btnRadius * 1.2f) {
                result.add(button)
            }
        }

        // Shoulder buttons
        if (l1Rect.contains(x, y)) result.add(PAD_L1)
        if (r1Rect.contains(x, y)) result.add(PAD_R1)

        // Start button
        if (dist(x, y, startCx, startCy) <= startR * 1.3f) result.add(PAD_START)

        return result
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    // ─── Haptics ──────────────────────────────────────────────────────────────

    private fun triggerHaptic() {
        if (!hapticsEnabled) return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(18)
            }
        } catch (_: Exception) { /* vibration not available, silently skip */ }
    }

    /** Call this when a physical controller connects/disconnects */
    fun onPhysicalControllerChanged(connected: Boolean, showGamepadPref: Boolean) {
        val shouldShow = showGamepadPref && !connected
        setGamepadVisible(shouldShow)
    }
}
