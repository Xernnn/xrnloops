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

import android.app.Activity
import org.isoron.uhabits.R
import org.isoron.uhabits.xrnlook.XrnSnackbar

/**
 * The banner on the screen in front after a tick did something in xrnrule: "Earned 30 min of
 * Social", "Social is open till 14:30", "Counted towards Social · it needs more". Its Open
 * button starts xrnrule on that block, as a tap on the habit's link line does.
 */
object XrnruleBanner {
    private const val DURATION_MS = 5_000

    fun show(activity: Activity, news: List<XrnruleEarned>) {
        val first = news.firstOrNull() ?: return
        var text = describe(activity, first)
        if (news.size > 1) text = activity.getString(R.string.xrnlink_banner_more, text, news.size - 1)
        val icon = if (first.kind == XrnruleEarned.Kind.COUNTED) R.drawable.xrn_ic_lock else R.drawable.xrn_ic_lock_open
        val bar = XrnSnackbar.make(activity, text, DURATION_MS, icon) ?: return
        bar.setAction(R.string.xrnlink_banner_action) { XrnruleLinks.openBlock(activity, first.link.blockId) }
        bar.show()
    }

    private fun describe(activity: Activity, news: XrnruleEarned): String {
        val link = news.link
        val what = link.blockName.ifBlank { link.targets }
        return when (news.kind) {
            XrnruleEarned.Kind.EARNED -> activity.getString(
                R.string.xrnlink_banner_earned,
                XrnruleLinkSheet.minutes(activity, link.unlockMinutes),
                what
            )
            XrnruleEarned.Kind.OPEN -> when {
                link.selfPaced -> activity.getString(
                    R.string.xrnlink_banner_open_left,
                    what,
                    XrnruleLinkSheet.minutes(activity, ((link.useLeftMs + 59_999) / 60_000).toInt())
                )
                link.openUntil > 0 -> activity.getString(
                    R.string.xrnlink_banner_open_till,
                    what,
                    XrnruleLinkSheet.clock(link.openUntil)
                )
                else -> activity.getString(R.string.xrnlink_banner_open, what)
            }
            XrnruleEarned.Kind.COUNTED -> activity.getString(R.string.xrnlink_banner_counted, what)
        }
    }
}
