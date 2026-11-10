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

import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** xrnloops: the amount a "the day's target" key enters always meets the target ([dayTargetEntry]). */
class DayTargetTest : BaseUnitTest() {

    private fun numberHabit(target: Double, den: Int, type: NumericalHabitType = NumericalHabitType.AT_LEAST) =
        modelFactory.buildHabit().apply {
            this.type = HabitType.NUMERICAL
            targetType = type
            targetValue = target
            frequency = Frequency(1, den)
        }

    private fun completedWith(habit: Habit, entry: Int): Boolean {
        habit.originalEntries.add(Entry(getToday(), entry))
        habit.recompute()
        return habit.isCompletedToday()
    }

    @Test
    fun theTargetKeyCompletesTheDay() {
        // The shares that aren't whole thousandths: rounded, each fell short and never completed.
        val cases = listOf(4.0 to 7, 5.0 to 7, 6.0 to 7, 12.0 to 7, 20.0 to 7, 10.0 to 30, 100.0 to 30, 8.0 to 1, 70.0 to 7, 2.5 to 1)
        for ((target, den) in cases) {
            val habit = numberHabit(target, den)
            val entry = habit.dayTargetEntry()
            assertTrue(completedWith(habit, entry), "$target in $den days: $entry")
            assertFalse(completedWith(habit, entry - 1), "$target in $den days: ${entry - 1} is enough too")
            // xrnrule's unit for one unlock: the share in thousandths, rounded up.
            val goal = (target * 1000).toLong()
            assertEquals((goal + den - 1) / den, entry.toLong(), "$target in $den days")
        }
        assertEquals(715, numberHabit(5.0, 7).dayTargetEntry())
    }

    @Test
    fun anAtMostTargetIsNeverExceeded() {
        for ((target, den) in listOf(5.0 to 7, 2.0 to 3, 10.0 to 30, 8.0 to 1)) {
            val share = target / den
            val entry = dayTargetEntry(share, NumericalHabitType.AT_MOST)
            assertTrue(entry / 1000.0 <= share, "$target in $den days: $entry")
            assertTrue((entry + 1) / 1000.0 > share, "$target in $den days: ${entry + 1} is allowed too")
        }
        assertEquals(666, dayTargetEntry(2.0 / 3, NumericalHabitType.AT_MOST)) // rounding gave 667, over it
    }

    @Test
    fun noTargetEntersNothing() {
        assertEquals(0, dayTargetEntry(0.0, NumericalHabitType.AT_LEAST))
        assertEquals(0, dayTargetEntry(Double.NaN, NumericalHabitType.AT_LEAST))
        assertEquals(0, dayTargetEntry(Double.POSITIVE_INFINITY, NumericalHabitType.AT_LEAST))
    }
}
