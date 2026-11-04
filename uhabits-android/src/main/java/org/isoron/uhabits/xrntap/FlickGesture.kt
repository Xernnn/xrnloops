/*
 * Copyright (C) 2026 the xrnrule authors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.xrntap

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import org.isoron.uhabits.R
import org.isoron.uhabits.utils.StyledResources
import org.isoron.uhabits.utils.dp
import org.isoron.uhabits.utils.sp
import kotlin.math.abs
import kotlin.math.hypot

/** One key of the flick menu: a glyph in [typeface] (or an [icon]), and what picking it does. */
class FlickChoice(
    val tint: Int,
    val glyph: String? = null,
    val typeface: Typeface? = null,
    val glyphSp: Float = 20f,
    val icon: Drawable? = null,
    val run: () -> Unit
)

/** The middle key (letting go where the finger went down) and up to four around it. */
class FlickChoices(
    val center: FlickChoice,
    val up: FlickChoice? = null,
    val right: FlickChoice? = null,
    val down: FlickChoice? = null,
    val left: FlickChoice? = null
) {
    fun at(direction: Direction): FlickChoice? = when (direction) {
        Direction.CENTER -> center
        Direction.UP -> up
        Direction.RIGHT -> right
        Direction.DOWN -> down
        Direction.LEFT -> left
    }

    enum class Direction { CENTER, UP, RIGHT, DOWN, LEFT }
}

/**
 * A flick-keyboard gesture for one view, as on a Japanese phone keyboard: a tap is a click; held
 * for [HOLD_MS] (well under Android's long press) a cross of keys opens around it, and sliding the
 * finger towards one and letting go picks it. Letting go in the middle picks the middle key,
 * unless that comes within [QUICK_MS] of touching down: that was a slow tap, so it clicks.
 * Towards a side with no key, nothing. Moving before the menu opens is left to the list (a scroll).
 */
class FlickGesture(
    private val view: View,
    private val accent: () -> Int,
    private val choices: () -> FlickChoices
) {
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val flickPx = view.dp(18f)
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var menu: Overlay? = null
    private val open = Runnable { show() }

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downAt = event.eventTime
                moved = false
                view.isPressed = true
                view.postDelayed(open, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                val shown = menu
                if (shown != null) {
                    shown.select(direction(dx, dy))
                } else if (!moved && hypot(dx, dy) > slop) {
                    moved = true
                    view.removeCallbacks(open)
                    view.isPressed = false
                }
            }
            MotionEvent.ACTION_UP -> {
                view.removeCallbacks(open)
                view.isPressed = false
                val shown = menu
                if (shown != null) {
                    val slowTap = shown.atCenter() && event.eventTime - downAt < QUICK_MS
                    val picked = shown.picked()
                    dismiss()
                    if (slowTap) view.performClick() else picked?.run?.invoke()
                } else if (!moved) {
                    view.performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(open)
                view.isPressed = false
                dismiss()
            }
        }
        return true
    }

    private fun direction(dx: Float, dy: Float) = when {
        hypot(dx, dy) < flickPx -> FlickChoices.Direction.CENTER
        abs(dx) > abs(dy) -> if (dx > 0) FlickChoices.Direction.RIGHT else FlickChoices.Direction.LEFT
        else -> if (dy > 0) FlickChoices.Direction.DOWN else FlickChoices.Direction.UP
    }

    private fun show() {
        if (!view.isAttachedToWindow || menu != null) return
        // From here the finger belongs to the menu, not to the list's scrolling.
        view.parent?.requestDisallowInterceptTouchEvent(true)
        menu = Overlay(view, choices(), accent()).also { it.show() }
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun dismiss() {
        menu?.dismiss()
        menu = null
    }

    /** The cross, drawn over the whole window so it can reach past the row and the list. */
    private class Overlay(
        private val view: View,
        private val choices: FlickChoices,
        private val accent: Int
    ) : Drawable(), View.OnAttachStateChangeListener {

        private val root = view.rootView as ViewGroup
        private val res = StyledResources(view.context)
        private val tile = view.dp(52f)
        private val gap = view.dp(6f)
        private val radius = view.dp(14f)
        private val keyColor = res.getColor(R.attr.cardBgColor)
        private val edgeColor = res.getColor(R.attr.contrast20)
        private val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.45) 0xFF18181B.toInt() else 0xFFFFFFFF.toInt()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            setShadowLayer(view.dp(8f), 0f, view.dp(2f), 0x55000000)
        }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = view.dp(1f)
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val rect = RectF()
        private var cx = 0f
        private var cy = 0f
        private var selected = FlickChoices.Direction.CENTER
        private var shown = 0f
        private val appear = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 110
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidateSelf()
            }
        }

        fun show() {
            val at = IntArray(2)
            val rootAt = IntArray(2)
            view.getLocationInWindow(at)
            root.getLocationInWindow(rootAt)
            // Centred on the day, moved in from the window's edges so every key is on screen.
            val reach = 1.5f * tile + gap + view.dp(8f)
            cx = (at[0] - rootAt[0] + view.width / 2f).coerceIn(reach, root.width - reach)
            cy = (at[1] - rootAt[1] + view.height / 2f).coerceIn(reach, root.height - reach)
            setBounds(0, 0, root.width, root.height)
            root.overlay.add(this)
            view.addOnAttachStateChangeListener(this)
            appear.start()
        }

        fun dismiss() {
            appear.cancel()
            view.removeOnAttachStateChangeListener(this)
            root.overlay.remove(this)
        }

        fun select(direction: FlickChoices.Direction) {
            if (direction == selected) return
            selected = direction
            if (choices.at(direction) != null) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidateSelf()
        }

        fun picked(): FlickChoice? = choices.at(selected)

        fun atCenter() = selected == FlickChoices.Direction.CENTER

        override fun onViewAttachedToWindow(v: View) = Unit

        override fun onViewDetachedFromWindow(v: View) = dismiss()

        override fun draw(canvas: Canvas) {
            val scale = 0.85f + 0.15f * shown
            val alpha = (255 * shown).toInt()
            canvas.save()
            canvas.scale(scale, scale, cx, cy)
            val step = tile + gap
            for (direction in FlickChoices.Direction.values()) {
                val choice = choices.at(direction) ?: continue
                val (ox, oy) = when (direction) {
                    FlickChoices.Direction.CENTER -> 0f to 0f
                    FlickChoices.Direction.UP -> 0f to -step
                    FlickChoices.Direction.RIGHT -> step to 0f
                    FlickChoices.Direction.DOWN -> 0f to step
                    FlickChoices.Direction.LEFT -> -step to 0f
                }
                rect.set(cx + ox - tile / 2, cy + oy - tile / 2, cx + ox + tile / 2, cy + oy + tile / 2)
                val isSelected = direction == selected
                fill.color = if (isSelected) accent else keyColor
                fill.alpha = alpha
                canvas.drawRoundRect(rect, radius, radius, fill)
                edge.color = edgeColor
                edge.alpha = alpha
                canvas.drawRoundRect(rect, radius, radius, edge)
                drawLabel(canvas, choice, if (isSelected) onAccent else choice.tint, alpha)
            }
            canvas.restore()
        }

        private fun drawLabel(canvas: Canvas, choice: FlickChoice, color: Int, alpha: Int) {
            val icon = choice.icon
            if (icon != null) {
                val half = (view.dp(24f) / 2).toInt()
                icon.setBounds(
                    rect.centerX().toInt() - half,
                    rect.centerY().toInt() - half,
                    rect.centerX().toInt() + half,
                    rect.centerY().toInt() + half
                )
                icon.setTint(color)
                icon.alpha = alpha
                icon.draw(canvas)
                return
            }
            val glyph = choice.glyph ?: return
            text.typeface = choice.typeface
            text.textSize = view.sp(choice.glyphSp)
            text.color = color
            text.alpha = alpha
            val baseline = rect.centerY() - (text.descent() + text.ascent()) / 2
            canvas.drawText(glyph, rect.centerX(), baseline, text)
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        /** How long a finger rests before the menu opens. Android's long press is 400 ms or more. */
        const val HOLD_MS = 170L

        /** Let go in the middle sooner than this after touching down, and it was a (slow) tap. */
        const val QUICK_MS = 300L
    }
}
