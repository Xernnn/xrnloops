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
        paint(this, link, tint)
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
        private const val FILL_ALPHA = 0x24
        private const val RIPPLE_ALPHA = 0x40

        /**
         * The lock for [link], in a [SIDE_DP] square: closed in the habit's colour while the block
         * waits on this habit, faint once the habit has done its part and the block wants more,
         * open once it's open, and solid when an unlock is earned and only has to be opened.
         */
        fun paint(view: ImageView, link: XrnruleLink, tint: Int) {
            val ready = link.state == XrnruleLinksContract.STATE_READY
            val open = ready || link.state == XrnruleLinksContract.STATE_OPEN
            val waiting = link.state == XrnruleLinksContract.STATE_LOCKED && link.habitDone
            val ink = if (waiting) view.sres.getColor(R.attr.contrast60) else tint
            val onTint = if (ColorUtils.calculateLuminance(tint) > 0.45) 0xFF18181B.toInt() else 0xFFFFFFFF.toInt()

            view.setImageResource(if (open) R.drawable.xrn_ic_lock_open else R.drawable.xrn_ic_lock)
            view.imageTintList = ColorStateList.valueOf(if (ready) onTint else ink)
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = view.dp((SIDE_DP - GLYPH_DP) / 2).toInt()
            view.setPadding(pad, pad, pad, pad)

            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (ready) tint else ColorUtils.setAlphaComponent(ink, FILL_ALPHA))
            }
            view.background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(ink, RIPPLE_ALPHA)),
                InsetDrawable(circle, view.dp((SIDE_DP - CIRCLE_DP) / 2).toInt()),
                null
            )
        }
    }
}
