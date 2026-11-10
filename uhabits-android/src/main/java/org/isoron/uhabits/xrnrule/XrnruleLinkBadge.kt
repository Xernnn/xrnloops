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
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.FragmentActivity
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.utils.dp
import org.isoron.uhabits.utils.getFontAwesome
import org.isoron.uhabits.utils.sres

/**
 * A habit's lock on its card in the list, straight after the name, when a block in xrnrule uses
 * the habit; nothing otherwise. It's drawn like the row's own marks, with no background and no
 * words: the glyph a day's ✓ comes from, at its size, in its colour. It only shows how the most
 * useful block stands ([XrnruleLinks.ordered]). A tap brings up the words, [XrnruleLinkSheet]; the
 * rest of the card still opens the habit.
 */
class XrnruleLinkBadge(context: Context) : AppCompatTextView(context) {

    private var habit: Habit? = null
    private var tint = 0
    private val onLinksChanged: () -> Unit = { render() }

    init {
        visibility = View.GONE
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPaddingRelative(dp(GAP_DP).toInt(), 0, 0, 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, GLYPH_SP)
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
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(dp(WIDTH_DP).toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP).toInt(), MeasureSpec.EXACTLY)
        )
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
        background = RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(tint, RIPPLE_ALPHA)),
            null,
            null
        ).apply { radius = dp(RIPPLE_DP).toInt() }
        contentDescription = "${link.blockName.ifBlank { link.targets }}: ${XrnruleLinkSheet.status(context, link)}"
        setOnClickListener {
            (context as? FragmentActivity)?.let { XrnruleLinkSheet.show(it, habit, tint) }
        }
    }

    companion object {
        /** A day's ✓ is this size (CheckmarkButtonView). */
        private const val GLYPH_SP = 14f

        /** From the end of the name to the glyph. */
        private const val GAP_DP = 7f

        /** A fair target for a thumb; the glyph sits at its start, close to the name. */
        private const val WIDTH_DP = 36f
        private const val HEIGHT_DP = 40f
        private const val RIPPLE_DP = 18f
        private const val RIPPLE_ALPHA = 0x40

        /**
         * The glyph for [link], in [color], the habit's: closed while the block waits on this
         * habit, a bolt when an unlock is earned and only has to be opened, open while it's open.
         * Once the habit has done its part and the block wants more, it's closed and in the grey
         * of an empty day, as there's nothing left to do here.
         */
        fun paint(view: TextView, link: XrnruleLink, color: Int) {
            val ready = link.state == XrnruleLinksContract.STATE_READY
            val open = link.state == XrnruleLinksContract.STATE_OPEN
            view.typeface = view.getFontAwesome()
            view.setText(
                when {
                    ready -> R.string.xrnlink_glyph_ready
                    open -> R.string.xrnlink_glyph_open
                    else -> R.string.xrnlink_glyph_locked
                }
            )
            val faint = !ready && !open && link.habitDone
            view.setTextColor(if (faint) view.sres.getColor(R.attr.contrast40) else color)
        }
    }
}
