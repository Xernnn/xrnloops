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
package org.isoron.uhabits.core.models

import kotlin.math.roundToInt

/**
 * xrnloops: the entry (an amount ×1000, as Loop stores it) that a "the day's target" key enters
 * for a day's [share] of a number habit's target: the least amount that meets an "at least"
 * target, the most that an "at most" one allows. A share is seldom a whole thousandth (5 a week
 * is 0.714285… a day), so rounding it can miss: 714 isn't done by [Habit.isCompletedToday], nor
 * by xrnrule, which counts the share rounded up (715). The web app's `dayTargetEntry` matches.
 */
fun dayTargetEntry(share: Double, type: NumericalHabitType): Int {
    if (!share.isFinite() || share <= 0) return 0
    var entry = (share * 1000).roundToInt()
    if (type == NumericalHabitType.AT_LEAST) {
        while (entry < Int.MAX_VALUE && entry / 1000.0 < share) entry++
    } else {
        while (entry > 0 && entry / 1000.0 > share) entry--
    }
    return entry
}

/** xrnloops: [dayTargetEntry] for this habit's own target, its share of one day. */
fun Habit.dayTargetEntry(): Int = dayTargetEntry(targetValue / frequency.denominator, targetType)
