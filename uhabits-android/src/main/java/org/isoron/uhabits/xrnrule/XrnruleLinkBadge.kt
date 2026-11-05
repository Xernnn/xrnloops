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

package org.isoron.uhabits.xrnrule

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.FragmentActivity
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.utils.dp
import org.isoron.uhabits.utils.sres

/**
 * A habit's lock on its card in the list, after the name, when a block in xrnrule uses the habit;
 * nothing otherwise. No words, so the card stays as clean as any other; it only shows how the most
 * useful block stands ([XrnruleLinks.ordered]). A tap brings up the words, [XrnruleLinkSheet]; the
 * rest of the card still opens the habit.
 */
class XrnruleLinkBadge(context: Context) : AppCompatImageView(context) {

    private var habit: Habit? = null
    private var tint = 0
    private val onLinksChanged: () -> Unit = { render() }

    init {
        visibility = View.GONE
    }

    fun bind(habit: Habit, color: Int) {
        this.habit = habit
        tint = color
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        XrnruleLinks.instance?.addListener(onLinksChanged)
        render()
    }

    override fun onDetachedFromWindow() {
        XrnruleLinks.instance?.removeListener(onLinksChanged)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = dp(SIDE_DP).toInt()
        setMeasuredDimension(side, side)
    }

    private fun render() {
        val habit = habit
        val link = XrnruleLinks.ordered(XrnruleLinks.instance?.forHabit(habit?.uuid).orEmpty()).firstOrNull()
        if (habit == null || link == null) {
            setOnClickListener(null)
            isClickable = false
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        paint(this, link)
        contentDescription = "${link.blockName.ifBlank { link.targets }}: ${XrnruleLinkSheet.status(context, link)}"
        setOnClickListener {
            (context as? FragmentActivity)?.let { XrnruleLinkSheet.show(it, habit, tint) }
        }
    }

    companion object {
        /** The whole view, a fair target for a thumb; the circle inside it is the part that shows. */
        private const val SIDE_DP = 40f
        private const val CIRCLE_DP = 24f
        private const val GLYPH_DP = 13f
        private const val RIPPLE_ALPHA = 0x40
        private const val ON_READY = 0xFF18181B.toInt()

        /**
         * The lock for [link], in a [SIDE_DP] square, in xrnrule's colours for how a block stands,
         * so it reads the same in both apps and never looks like part of the habit: red while the
         * block waits on this habit, grey once the habit has done its part and the block wants
         * more, green while it's open, and a solid amber bolt when an unlock is earned and only
         * has to be opened, the one thing here worth a look from across the list.
         */
        fun paint(view: ImageView, link: XrnruleLink) {
            val res = view.sres
            val icon: Int
            val ink: Int
            val fill: Int
            when {
                link.state == XrnruleLinksContract.STATE_READY -> {
                    icon = R.drawable.xrn_ic_bolt
                    ink = ON_READY
                    fill = res.getColor(R.attr.xrnReady)
                }
                link.state == XrnruleLinksContract.STATE_OPEN -> {
                    icon = R.drawable.xrn_ic_lock_open
                    ink = res.getColor(R.attr.xrnOpen)
                    fill = res.getColor(R.attr.xrnOpenSoft)
                }
                link.habitDone -> {
                    icon = R.drawable.xrn_ic_lock
                    ink = res.getColor(R.attr.contrast60)
                    fill = res.getColor(R.attr.contrast20)
                }
                else -> {
                    icon = R.drawable.xrn_ic_lock
                    ink = res.getColor(R.attr.xrnLocked)
                    fill = res.getColor(R.attr.xrnLockedSoft)
                }
            }
            view.setImageResource(icon)
            view.imageTintList = ColorStateList.valueOf(ink)
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = view.dp((SIDE_DP - GLYPH_DP) / 2).toInt()
            view.setPadding(pad, pad, pad, pad)

            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
            }
            view.background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(ink, RIPPLE_ALPHA)),
                InsetDrawable(circle, view.dp((SIDE_DP - CIRCLE_DP) / 2).toInt()),
                null
            )
        }
    }
}
