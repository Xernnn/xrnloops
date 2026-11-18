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

import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.Entry.Companion.SKIP
import org.isoron.uhabits.core.models.Entry.Companion.UNKNOWN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** xrnloops: the number flick's + and − ([steppedEntry]) and the step they go by. */
class StepTest : BaseUnitTest() {

    @Test
    fun plusOnAnEmptyOrSkippedDayGivesOneStep() {
        assertEquals(1000, steppedEntry(UNKNOWN, 1000, 1))
        assertEquals(250, steppedEntry(SKIP, 250, 1))
        assertEquals(1000, steppedEntry(0, 1000, 1))
    }

    @Test
    fun minusNeverGoesBelowZero() {
        assertEquals(0, steppedEntry(UNKNOWN, 1000, -1)) // an empty day gets a 0
        assertEquals(0, steppedEntry(SKIP, 1000, -1))
        assertEquals(0, steppedEntry(500, 1000, -1))
        assertEquals(0, steppedEntry(0, 1000, -1))
        assertEquals(1500, steppedEntry(2500, 1000, -1))
    }

    @Test
    fun aQuarterAtATimeDoesNotDrift() {
        var value = UNKNOWN
        repeat(4) { value = steppedEntry(value, stepEntry(0.25), 1) }
        assertEquals(1000, value)
        repeat(3) { value = steppedEntry(value, stepEntry(0.25), -1) }
        assertEquals(250, value)
        var tenths = UNKNOWN
        repeat(10) { tenths = steppedEntry(tenths, stepEntry(0.1), 1) }
        assertEquals(1000, tenths) // 0.1 ten times as doubles is 0.9999999999999999
    }

    @Test
    fun aStepNeverLandsOnTheEntryLoopKeepsASkipAs() {
        assertEquals(SKIP + 1, steppedEntry(2, 1, 1))
        assertEquals(SKIP - 1, steppedEntry(4, 1, -1))
        assertEquals(SKIP + 1, steppedEntry(1, 2, 1))
    }

    @Test
    fun theTopIsWhatAnEntryHolds() {
        assertEquals(Int.MAX_VALUE, steppedEntry(Int.MAX_VALUE - 10, stepEntry(MAX_STEP), 1))
    }

    @Test
    fun aStepIsAPositiveNumberToTheThousandth() {
        assertEquals(1000, stepEntry(1.0))
        assertEquals(250, stepEntry(0.25))
        assertEquals(1, stepEntry(0.0004)) // rounds to nothing: the least there is
        assertEquals(1000, stepEntry(0.0))
        assertEquals(1000, stepEntry(-2.0))
        assertEquals(1000, stepEntry(Double.NaN))
        assertEquals(100_000_000, stepEntry(1e12))
    }

    @Test
    fun theFormTakesAStepFromAThousandthToItsLimit() {
        assertEquals(0.25, cleanStep(0.25))
        assertEquals(0.001, cleanStep(0.001))
        assertEquals(1.235, cleanStep(1.2345))
        assertEquals(MAX_STEP, cleanStep(MAX_STEP))
        assertNull(cleanStep(0.0))
        assertNull(cleanStep(0.0004))
        assertNull(cleanStep(-1.0))
        assertNull(cleanStep(MAX_STEP + 1))
        assertNull(cleanStep(Double.NaN))
        assertNull(cleanStep(Double.POSITIVE_INFINITY))
    }

    @Test
    fun anAmountTypedIsStoredAsTheWebStoresIt() {
        assertEquals(2500, typedEntry(2.5))
        assertEquals(0, typedEntry(0.0))
        assertEquals(SKIP + 1, typedEntry(0.003)) // not a skip
        assertEquals(Int.MAX_VALUE, typedEntry(3_000_000.0)) // as much as an entry holds
        assertNull(typedEntry(-5.0)) // a keyboard that types a minus: no amount, not a cleared day
        assertNull(typedEntry(Double.NaN))
        assertNull(typedEntry(Double.POSITIVE_INFINITY))
    }

    @Test
    fun aHabitIsMadeWithAStepOfOneAndAnEditCarriesItsStep() {
        val habit = modelFactory.buildHabit()
        assertEquals(1.0, habit.step)
        val edited = modelFactory.buildHabit()
        edited.step = 0.5
        habit.copyFrom(edited)
        assertEquals(0.5, habit.step)
        assertEquals(500, habit.stepEntry())
    }
}
