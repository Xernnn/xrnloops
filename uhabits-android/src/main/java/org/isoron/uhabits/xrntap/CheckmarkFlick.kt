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

import android.view.HapticFeedbackConstants
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.habits.list.views.CheckmarkButtonView
import org.isoron.uhabits.core.models.Entry.Companion.NO
import org.isoron.uhabits.core.models.Entry.Companion.SKIP
import org.isoron.uhabits.core.models.Entry.Companion.UNKNOWN
import org.isoron.uhabits.core.models.Entry.Companion.YES_MANUAL
import org.isoron.uhabits.utils.getFontAwesome
import org.isoron.uhabits.utils.sres

/**
 * The habit list's yes/no days: what a tap does ([XrnTap]) and the flick menu a short hold opens.
 * Always in the same places, so a flick becomes a habit: up ✓, down ✗, left – (skip), and
 * letting go in the middle clears the day back to ?. Right has no key since the day's note went
 * (2026-11-15). A hold let go in the middle at once is still a tap ([FlickGesture.QUICK_MS]).
 */
object CheckmarkFlick {

    fun tap(button: CheckmarkButtonView): Unit = with(button) {
        when (XrnTap.action(context)) {
            XrnTap.Action.CYCLE -> performToggle()
            XrnTap.Action.TICK -> set(this, XrnTap.tickValue(value, preferences.areQuestionMarksEnabled))
        }
    }

    fun gesture(button: CheckmarkButtonView) = FlickGesture(button, { button.color }) { choices(button) }

    private fun choices(button: CheckmarkButtonView): FlickChoices = with(button) {
        val fa = getFontAwesome()
        val faint = sres.getColor(R.attr.contrast60)
        fun key(glyph: Int, tint: Int, newValue: Int, size: Float = 20f) = FlickChoice(
            tint = tint,
            glyph = resources.getString(glyph),
            typeface = fa,
            glyphSp = size
        ) { set(this, newValue) }
        FlickChoices(
            center = key(R.string.fa_question, faint, UNKNOWN, 17f),
            up = key(R.string.fa_check, color, YES_MANUAL),
            down = key(R.string.fa_times, faint, NO),
            left = key(R.string.fa_skipped, color, SKIP)
        )
    }

    /** Like Loop's [CheckmarkButtonView.performToggle], to a value picked by hand. */
    private fun set(button: CheckmarkButtonView, newValue: Int) = with(button) {
        value = newValue
        onToggle(newValue)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        Unit
    }
}
