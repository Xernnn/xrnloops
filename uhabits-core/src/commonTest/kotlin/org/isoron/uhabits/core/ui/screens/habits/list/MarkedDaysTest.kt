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

package org.isoron.uhabits.core.ui.screens.habits.list

import org.isoron.platform.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkedDaysTest {
    @Test
    fun theFirstAndTheFifteenthOfEveryMonthAreMarked() {
        for (month in 1..12) {
            val marked = (1..28).filter { LocalDate(2026, month, it).isMarkedDay() }
            assertEquals(listOf(1, 15), marked, "month $month")
        }
    }

    @Test
    fun noOtherDayIsMarked() {
        val others = listOf(
            LocalDate(2026, 1, 31), LocalDate(2026, 2, 28), LocalDate(2028, 2, 29),
            LocalDate(2026, 11, 14), LocalDate(2026, 11, 16), LocalDate(2026, 11, 30), LocalDate(2026, 12, 2)
        )
        assertEquals(emptyList(), others.filter { it.isMarkedDay() })
    }
}
