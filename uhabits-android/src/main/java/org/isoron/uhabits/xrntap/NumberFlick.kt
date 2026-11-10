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
import org.isoron.uhabits.activities.habits.list.views.NumberButtonView
import org.isoron.uhabits.activities.habits.list.views.toShortString
import org.isoron.uhabits.core.models.Entry.Companion.SKIP
import org.isoron.uhabits.core.models.Entry.Companion.UNKNOWN
import org.isoron.uhabits.core.models.NumericalHabitType.AT_LEAST
import org.isoron.uhabits.core.models.dayTargetEntry
import org.isoron.uhabits.utils.getFontAwesome
import org.isoron.uhabits.utils.sres
import org.isoron.uhabits.xrnlook.XrnFonts

/**
 * The flick menu on a number habit's day, laid out like the yes/no one ([CheckmarkFlick]): up
 * the target ("8"), down 0, left – (skip), right Loop's number dialog for any other amount and
 * the note, and letting go in the middle clears the day to ?. A tap still opens the dialog, as
 * in Loop. Keys whose amount meets the target wear the habit's colour.
 */
object NumberFlick {

    fun gesture(button: NumberButtonView) = FlickGesture(button, { button.color }) { choices(button) }

    private fun choices(button: NumberButtonView): FlickChoices = with(button) {
        val fa = getFontAwesome()
        val faint = sres.getColor(R.attr.contrast60)
        val bold = XrnFonts.bold(context)
        fun meets(amount: Double) = if (targetType == AT_LEAST) amount >= threshold else amount <= threshold
        fun amount(entry: Int): FlickChoice { // as Loop stores it, the amount ×1000
            val label = (entry / 1000.0).toShortString()
            return FlickChoice(
                tint = if (meets(entry / 1000.0)) color else faint,
                glyph = label,
                typeface = bold,
                glyphSp = if (label.length > 3) 14f else 18f
            ) { set(this, entry) }
        }
        FlickChoices(
            center = FlickChoice(faint, resources.getString(R.string.fa_question), fa, 17f) { set(this, UNKNOWN) },
            // The share rounded could fall short of it (5 a week is 0.714… a day, and 714 isn't done).
            up = amount(dayTargetEntry(threshold, targetType)),
            right = FlickChoice(tint = faint, icon = context.getDrawable(R.drawable.xrn_ic_dialpad)?.mutate()) { onEdit() },
            down = amount(0),
            left = FlickChoice(color, resources.getString(R.string.fa_skipped), fa) { set(this, SKIP) }
        )
    }

    private fun set(button: NumberButtonView, stored: Int) = with(button) {
        value = stored / 1000.0
        onToggle(stored, notes)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        Unit
    }
}
