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
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.callbacks.CheckMarkDialogCallback
import org.isoron.uhabits.core.ui.callbacks.NumberPickerCallback
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun openNoteSheet(date: LocalDate): CheckMarkDialogCallback {
        var callback: CheckMarkDialogCallback? = null
        every {
            screen.showCheckmarkPopup(any(), any(), any(), any(), any(), any())
        } calls { args ->
            callback = args.arg<CheckMarkDialogCallback>(5)
            Unit
        }
        presenter(yesNo).onDateShortPress(date)
        return callback!!
    }

    private fun openNumberSheet(date: LocalDate): NumberPickerCallback {
        var callback: NumberPickerCallback? = null
        every {
            screen.showNumberPopup(any(), any(), any(), any(), any())
        } calls { args ->
            callback = args.arg<NumberPickerCallback>(4)
            Unit
        }
        presenter(number).onDateShortPress(date)
        return callback!!
    }

    private fun syncChanges(habit: Habit, date: LocalDate, value: Int) {
        habit.originalEntries.add(Entry(date, value))
        habit.recompute()
    }

    @Test
    fun testANoteAloneKeepsWhatSyncChanged() {
        val today = getToday()
        val shown = yesNo.computedEntries.get(today).value
        val sheet = openNoteSheet(today)
        syncChanges(yesNo, today, Entry.SKIP)
        sheet.onNotesSaved(shown, "written meanwhile")
        val entry = yesNo.computedEntries.get(today)
        assertEquals(Entry.SKIP, entry.value)
        assertEquals("written meanwhile", entry.notes)
    }

    @Test
    fun testAPickedValueStillWins() {
        val today = getToday()
        val sheet = openNoteSheet(today)
        syncChanges(yesNo, today, Entry.SKIP)
        sheet.onNotesSaved(Entry.NO, "")
        assertEquals(Entry.NO, yesNo.computedEntries.get(today).value)
    }

    @Test
    fun testANumberNoteAloneKeepsWhatSyncChanged() {
        val today = getToday()
        val shown = number.computedEntries.get(today).value / 1000.0
        val sheet = openNumberSheet(today)
        syncChanges(number, today, 7000)
        sheet.onNumberPicked(shown, "written meanwhile")
        val entry = number.computedEntries.get(today)
        assertEquals(7000, entry.value)
        assertEquals("written meanwhile", entry.notes)
    }

    @Test
    fun testANumberPickedStillWins() {
        val today = getToday()
        val shown = number.computedEntries.get(today).value / 1000.0
        val sheet = openNumberSheet(today)
        syncChanges(number, today, 7000)
        sheet.onNumberPicked(shown + 1.5, "")
        assertEquals(((shown + 1.5) * 1000).toInt(), number.computedEntries.get(today).value)
    }
}
