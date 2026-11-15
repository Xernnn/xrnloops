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

package org.isoron.uhabits.core.ui.screens.habits.show.views

import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.callbacks.CheckMarkDialogCallback
import org.isoron.uhabits.core.ui.callbacks.NumberPickerCallback
import org.isoron.uhabits.core.ui.views.LightTheme
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryCardPresenterTest : BaseUnitTest() {
    private val prefs: Preferences = mock()
    private val screen: HistoryCardPresenter.Screen = mock()
    private lateinit var yesNo: Habit
    private lateinit var number: Habit

    @BeforeTest
    override fun setUp() {
        super.setUp()
        yesNo = fixtures.createShortHabit()
        number = fixtures.createNumericalHabit()
        habitList.add(yesNo)
        habitList.add(number)
        every { prefs.isShortToggleEnabled } returns false
        every { screen.showFeedback() } returns Unit
    }

    private fun presenter(habit: Habit) = HistoryCardPresenter(commandRunner, habit, habitList, prefs, screen)

    private fun openDaySheet(date: LocalDate): CheckMarkDialogCallback {
        var callback: CheckMarkDialogCallback? = null
        every {
            screen.showCheckmarkPopup(any(), any(), any(), any(), any())
        } calls { args ->
            callback = args.arg<CheckMarkDialogCallback>(4)
            Unit
        }
        presenter(yesNo).onDateShortPress(date)
        return callback!!
    }

    private fun openNumberSheet(date: LocalDate): NumberPickerCallback {
        var callback: NumberPickerCallback? = null
        every {
            screen.showNumberPopup(any(), any(), any(), any())
        } calls { args ->
            callback = args.arg<NumberPickerCallback>(3)
            Unit
        }
        presenter(number).onDateShortPress(date)
        return callback!!
    }

    private fun syncChanges(habit: Habit, date: LocalDate, value: Int, notes: String = "") {
        habit.originalEntries.add(Entry(date, value, notes))
        habit.recompute()
    }

    // xrnloops: the sheet has no note since 2026-11-15; what it writes keeps the day's, even one
    // sync wrote while it was open (XRNLOOPS.md).
    @Test
    fun testAPickedValueKeepsTheDaysNote() {
        val today = getToday()
        syncChanges(yesNo, today, Entry.YES_MANUAL, "first")
        val sheet = openDaySheet(today)
        syncChanges(yesNo, today, Entry.SKIP, "written meanwhile")
        sheet.onValuePicked(Entry.NO)
        assertEquals(Entry(today, Entry.NO, "written meanwhile"), yesNo.originalEntries.get(today))
    }

    @Test
    fun testAPickedValueStillWins() {
        val today = getToday()
        val sheet = openDaySheet(today)
        syncChanges(yesNo, today, Entry.SKIP)
        sheet.onValuePicked(Entry.NO)
        assertEquals(Entry.NO, yesNo.computedEntries.get(today).value)
    }

    @Test
    fun testANumberKeepsTheDaysNote() {
        val today = getToday()
        syncChanges(number, today, 3000, "first")
        val sheet = openNumberSheet(today)
        syncChanges(number, today, 7000, "written meanwhile")
        sheet.onNumberPicked(8.5)
        assertEquals(Entry(today, 8500, "written meanwhile"), number.originalEntries.get(today))
    }

    @Test
    fun testANumberPickedStillWins() {
        val today = getToday()
        val shown = number.computedEntries.get(today).value / 1000.0
        val sheet = openNumberSheet(today)
        syncChanges(number, today, 7000)
        sheet.onNumberPicked(shown + 1.5)
        assertEquals(((shown + 1.5) * 1000).toInt(), number.computedEntries.get(today).value)
    }

    @Test
    fun testATapThatTogglesKeepsTheDaysNote() {
        every { prefs.isShortToggleEnabled } returns true
        every { prefs.isSkipEnabled } returns false
        every { prefs.areQuestionMarksEnabled } returns false
        val today = getToday()
        syncChanges(yesNo, today, Entry.YES_MANUAL, "kept")
        presenter(yesNo).onDateShortPress(today)
        assertEquals(Entry(today, Entry.NO, "kept"), yesNo.originalEntries.get(today))
    }

    @Test
    fun testTheCalendarShowsNoDotForANote() {
        syncChanges(yesNo, getToday(), Entry.YES_MANUAL, "kept")
        val state = HistoryCardPresenter.buildState(yesNo, DayOfWeek.SUNDAY, LightTheme())
        assertTrue(state.notesIndicators.none { it })
    }
}
