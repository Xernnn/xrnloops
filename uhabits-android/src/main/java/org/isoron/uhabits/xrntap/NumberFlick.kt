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
import org.isoron.uhabits.core.models.Entry.Companion.SKIP
import org.isoron.uhabits.core.models.Entry.Companion.UNKNOWN
import org.isoron.uhabits.core.models.NumericalHabitType.AT_LEAST
import org.isoron.uhabits.core.models.steppedEntry
import org.isoron.uhabits.utils.getFontAwesome
import org.isoron.uhabits.utils.sres
import org.isoron.uhabits.xrnlook.XrnFonts
import java.text.DecimalFormat
import kotlin.math.roundToInt

/**
 * The flick menu on a number habit's day, laid out like the yes/no one ([CheckmarkFlick]): up
 * adds the habit's step ("+1", "+0.25"), down takes one off ("−1", never below 0), left – (skip),
 * right the day's sheet for any other amount, and letting go in the middle clears the day to ?.
 * A tap still opens the sheet, as Loop's dialog opened. A key whose result meets the target
 * wears the habit's colour. The keys go from the day as it is stored when one is picked
 * ([org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior.onStep]); what they show
 * comes from the day as the list draws it.
 *
 * Until 2026-11-15 up entered the day's target and down 0; the day's sheet still has both.
 */
object NumberFlick {

    fun gesture(button: NumberButtonView) = FlickGesture(button, { button.color }) { choices(button) }

    private fun choices(button: NumberButtonView): FlickChoices = with(button) {
        val fa = getFontAwesome()
        val faint = sres.getColor(R.attr.contrast60)
        val bold = XrnFonts.bold(context)
        val shown = (value * 1000).roundToInt() // as Loop stores it: ×1000, -1 empty, 3 a skip
        fun meets(entry: Int) = if (targetType == AT_LEAST) entry / 1000.0 >= threshold else entry / 1000.0 <= threshold
        fun stepKey(steps: Int): FlickChoice {
            val label = (if (steps > 0) "+" else "\u2212") + stepLabel(step)
            return FlickChoice(
                tint = if (meets(steppedEntry(shown, step, steps))) color else faint,
                glyph = label,
                typeface = bold,
                glyphSp = when {
                    label.length <= 3 -> 18f
                    label.length <= 5 -> 14f
                    else -> 11f
                }
            ) {
                this.value = steppedEntry(shown, step, steps) / 1000.0 // drawn at once; the list catches up
                onStep(steps)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
        FlickChoices(
            center = FlickChoice(faint, resources.getString(R.string.fa_question), fa, 17f) { set(this, UNKNOWN) },
            up = stepKey(1),
            right = FlickChoice(tint = faint, icon = context.getDrawable(R.drawable.xrn_ic_dialpad)?.mutate()) { onEdit() },
            down = stepKey(-1),
            left = FlickChoice(color, resources.getString(R.string.fa_skipped), fa) { set(this, SKIP) }
        )
    }

    /** A step (an entry, ×1000) as its keys show it: to the thousandth, "1", "0.25", "1500". */
    fun stepLabel(step: Int): String = DecimalFormat("#.###").format(step / 1000.0)

    private fun set(button: NumberButtonView, stored: Int) = with(button) {
        value = stored / 1000.0
        onToggle(stored)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        Unit
    }
}
