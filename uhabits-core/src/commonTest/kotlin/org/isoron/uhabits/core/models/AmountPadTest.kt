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

import org.isoron.uhabits.core.models.Entry.Companion.SKIP
import org.isoron.uhabits.core.models.Entry.Companion.UNKNOWN
import org.isoron.uhabits.core.models.NumericalHabitType.AT_LEAST
import org.isoron.uhabits.core.models.NumericalHabitType.AT_MOST
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** xrnloops: the number sheet's pad ([AmountPad]): typing, + and −, and what closing writes. */
class AmountPadTest {

    private fun AmountPad.type(keys: String) = keys.map {
        when (it) {
            '.' -> separator()
            '<' -> backspace()
            else -> digit(it.digitToInt())
        }
    }

    @Test
    fun anEmptyOrSkippedDayOpensWithNoAmount() {
        assertEquals("", AmountPad(UNKNOWN).text)
        assertEquals("", AmountPad(SKIP).text)
        assertNull(AmountPad(SKIP).entry)
        assertEquals("0", AmountPad(0).text)
    }

    @Test
    fun anAmountOpensToTheThousandth() {
        assertEquals("2.5", AmountPad(2500).text)
        assertEquals("0.715", AmountPad(715).text)
        assertEquals("1500", AmountPad(1_500_000).text)
        assertEquals("0.004", AmountPad(4).text)
        assertEquals("2147483.647", AmountPad(Int.MAX_VALUE).text)
    }

    @Test
    fun theFirstKeyReplacesTheAmountItOpenedWith() {
        val pad = AmountPad(3000)
        pad.type("5")
        assertEquals("5", pad.text)
        pad.type("2")
        assertEquals("52", pad.text)
        val separator = AmountPad(3000)
        separator.type(".5")
        assertEquals("0.5", separator.text)
    }

    @Test
    fun backspaceTakesOffOneCharacterAndThenTheNextKeyAddsOn() {
        val pad = AmountPad(2500)
        pad.type("<")
        assertEquals("2.", pad.text)
        pad.type("7")
        assertEquals("2.7", pad.text)
        pad.type("<<<")
        assertEquals("", pad.text)
        assertFalse(pad.backspace())
        assertNull(pad.entry)
    }

    @Test
    fun clearEmptiesTheBox() {
        val pad = AmountPad(2500)
        assertTrue(pad.clear())
        assertEquals("", pad.text)
        assertFalse(pad.clear())
        pad.type("4")
        assertEquals("4", pad.text)
    }

    @Test
    fun onlyAnAmountGetsIn() {
        val pad = AmountPad(UNKNOWN)
        assertEquals(listOf(true, true, false, true, true, true, false), pad.type("1..2345"))
        assertEquals("1.234", pad.text) // a second separator, and a fourth decimal, are refused
        assertEquals(1234, pad.entry)
        val zeros = AmountPad(UNKNOWN)
        zeros.type("007")
        assertEquals("7", zeros.text) // no leading zeros
        val point = AmountPad(UNKNOWN)
        point.type(".")
        assertEquals("0.", point.text)
        assertEquals(0, point.entry)
    }

    @Test
    fun noMoreThanAnEntryHolds() {
        val pad = AmountPad(UNKNOWN)
        pad.type("2147483")
        assertFalse(pad.digit(0)) // 21474830 is past the most an entry holds
        pad.type(".647")
        assertEquals(Int.MAX_VALUE, pad.entry)
        val over = AmountPad(UNKNOWN)
        over.type("2147483.6")
        assertFalse(over.digit(9))
        assertEquals("2147483.6", over.text)
    }

    @Test
    fun zeroPointZeroZeroThreeIsNotStoredAsASkip() {
        val pad = AmountPad(UNKNOWN)
        pad.type("0.003")
        assertEquals(SKIP + 1, pad.entry)
    }

    @Test
    fun plusAndMinusGoByTheStepAsTheFlickDoes() {
        val pad = AmountPad(UNKNOWN)
        pad.step(1000, 1)
        assertEquals("1", pad.text)
        pad.step(1000, 1)
        assertEquals("2", pad.text)
        pad.step(1000, -1)
        pad.step(1000, -1)
        pad.step(1000, -1)
        assertEquals("0", pad.text) // never below 0
        val skipped = AmountPad(SKIP)
        skipped.step(250, 1)
        assertEquals("0.25", skipped.text)
        val minus = AmountPad(SKIP)
        minus.step(1000, -1)
        assertEquals("0", minus.text) // − on an empty or skipped day is the quick 0
    }

    @Test
    fun aQuarterAtATimeDoesNotDriftAndASkipIsSteppedOver() {
        val pad = AmountPad(UNKNOWN)
        repeat(4) { pad.step(250, 1) }
        assertEquals(1000, pad.entry)
        val tenths = AmountPad(UNKNOWN)
        repeat(10) { tenths.step(100, 1) }
        assertEquals("1", tenths.text)
        val tiny = AmountPad(2)
        tiny.step(1, 1)
        assertEquals(SKIP + 1, tiny.entry)
    }

    @Test
    fun aStepGoesFromWhatWasTypedAndTheNextKeyStartsANewAmount() {
        val pad = AmountPad(3000)
        pad.type("6")
        pad.step(1000, 1)
        assertEquals("7", pad.text)
        pad.type("9")
        assertEquals("9", pad.text)
    }

    @Test
    fun anAmountNobodyChangedWritesNothing() {
        val pad = AmountPad(3000)
        assertNull(pad.toSave())
        assertNull(pad.toKeep())
        pad.step(1000, 1)
        pad.step(1000, -1)
        assertNull(pad.toSave()) // back where it opened
        pad.type("3")
        assertNull(pad.toSave())
        assertNull(AmountPad(UNKNOWN).toSave())
        assertNull(AmountPad(SKIP).toSave())
    }

    @Test
    fun saveWritesTheAmount() {
        val pad = AmountPad(3000)
        pad.type("2.5")
        assertEquals(2500, pad.toSave())
        val zero = AmountPad(UNKNOWN)
        zero.type("0")
        assertEquals(0, zero.toSave()) // a quick 0: two taps
        val minus = AmountPad(UNKNOWN)
        minus.step(1000, -1)
        assertEquals(0, minus.toSave()) // or − and Save
    }

    @Test
    fun saveWithNoAmountEmptiesTheDayWithoutUndoingASkip() {
        val pad = AmountPad(3000)
        pad.clear()
        assertEquals(UNKNOWN, pad.toSave())
        assertEquals(SKIP, pad.toSave(now = SKIP)) // sync skipped the day meanwhile
    }

    @Test
    fun leavingKeepsAnAmountKeyedInAndNeverBlanksADay() {
        val typed = AmountPad(UNKNOWN)
        typed.type("4")
        assertEquals(4000, typed.toKeep())
        val stepped = AmountPad(3000)
        stepped.step(1000, 1)
        assertEquals(4000, stepped.toKeep())
        val blank = AmountPad(3000)
        blank.clear()
        assertNull(blank.toKeep())
    }

    @Test
    fun theBarShowsTheShareOfTheDayTarget() {
        assertNull(dayProgress(3000, 0))
        assertEquals(0f, dayProgress(null, 8000))
        assertEquals(0f, dayProgress(SKIP, 8000))
        assertEquals(0.375f, dayProgress(3000, 8000))
        assertEquals(1f, dayProgress(12_000, 8000))
    }

    @Test
    fun theTargetIsMetAsTheListShowsIt() {
        val share = dayTargetEntry(5 / 7.0, AT_LEAST) // 0.715
        assertFalse(meetsDayTarget(714, share, AT_LEAST))
        assertTrue(meetsDayTarget(715, share, AT_LEAST))
        assertTrue(meetsDayTarget(2000, 2000, AT_MOST))
        assertFalse(meetsDayTarget(2001, 2000, AT_MOST))
        assertFalse(meetsDayTarget(null, 2000, AT_MOST))
        assertFalse(meetsDayTarget(SKIP, 2000, AT_MOST))
        assertFalse(meetsDayTarget(0, 0, AT_LEAST))
    }
}
