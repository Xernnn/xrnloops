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
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.utils.dp
import org.isoron.uhabits.utils.sres
import java.util.Date

/**
 * The line under a habit's name in the list that says what it does in xrnrule, by block name
 * (short, the column is narrow): a closed lock and "Social · 30 min" (what one unlock buys),
 * "Social · needs more" (this habit is done, the block wants another part), an open lock and
 * "Social · ready", "Social · till 14:30" or, for an unlock that's so much use rather than time,
 * "Social · 12 min left". Gone for a habit no block uses. While it shows,
 * the name keeps to one line, so the card stays the height of its checkmarks.
 */
class XrnruleLinkLine(context: Context, private val name: TextView) : TextView(context) {

    private var habitUuid: String? = null
    private var tint = 0
    private val onLinksChanged: () -> Unit = { render() }

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(sres.getColor(R.attr.contrast60))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        compoundDrawablePadding = dp(4f).toInt()
        visibility = View.GONE
    }

    fun bind(habit: Habit, color: Int) {
        habitUuid = habit.uuid
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

    private fun render() {
        val links = XrnruleLinks.instance?.forHabit(habitUuid).orEmpty()
        val link = mostUseful(links)
        if (link == null) {
            visibility = View.GONE
            name.maxLines = 2
            return
        }
        visibility = View.VISIBLE
        name.maxLines = 1
        val open = link.state == XrnruleLinksContract.STATE_OPEN || link.state == XrnruleLinksContract.STATE_READY
        val icon = context.getDrawable(if (open) R.drawable.xrn_ic_lock_open else R.drawable.xrn_ic_lock)!!.mutate()
        val size = dp(13f).toInt()
        icon.setBounds(0, 0, size, size)
        icon.setTint(tint)
        setCompoundDrawablesRelative(icon, null, null, null)
        val line = describe(link)
        text = if (links.size > 1) context.getString(R.string.xrnlink_more, line, links.size - 1) else line
    }

    private fun describe(link: XrnruleLink): String {
        val what = link.blockName.ifBlank { link.targets }
        return when (link.state) {
            XrnruleLinksContract.STATE_OPEN -> when {
                link.selfPaced -> context.getString(
                    R.string.xrnlink_left,
                    what,
                    minutes(((link.useLeftMs + 59_999) / 60_000).toInt())
                )
                link.openUntil > 0 -> context.getString(
                    R.string.xrnlink_open,
                    what,
                    DateFormat.getTimeFormat(context).format(Date(link.openUntil))
                )
                else -> context.getString(R.string.xrnlink_open_now, what)
            }
            XrnruleLinksContract.STATE_READY -> context.getString(R.string.xrnlink_ready, what)
            else -> if (link.habitDone) {
                context.getString(R.string.xrnlink_done_locked, what)
            } else {
                context.getString(R.string.xrnlink_locked, what, minutes(link.unlockMinutes))
            }
        }
    }

    private fun minutes(total: Int): String {
        val h = total / 60
        val m = total % 60
        return when {
            h == 0 -> context.getString(R.string.xrnlink_minutes, m)
            m == 0 -> context.getString(R.string.xrnlink_hours, h)
            else -> context.getString(R.string.xrnlink_hours_minutes, h, m)
        }
    }

    companion object {
        /**
         * Several blocks can want one habit. Show the one the habit still has to do something
         * for, then one waiting to be opened, then one that's open, then one where this habit
         * is done but the block wants more.
         */
        fun mostUseful(links: List<XrnruleLink>): XrnruleLink? = links.minByOrNull {
            when {
                it.state == XrnruleLinksContract.STATE_LOCKED && !it.habitDone -> 0
                it.state == XrnruleLinksContract.STATE_READY -> 1
                it.state == XrnruleLinksContract.STATE_OPEN -> 2
                else -> 3
            }
        }
    }
}
