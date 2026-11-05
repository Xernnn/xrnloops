/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
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

import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.spy
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.exactly
import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.UserFile
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.callbacks.CheckMarkDialogCallback
import org.isoron.uhabits.core.ui.callbacks.NumberPickerCallback
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListHabitsBehaviorTest : BaseUnitTest() {
    private val dirFinder: ListHabitsBehavior.DirFinder = mock()

    private val prefs: Preferences = mock()
    private lateinit var behavior: ListHabitsBehavior

    private val screen: ListHabitsBehavior.Screen = mock()
    private lateinit var habit1: Habit
    private lateinit var habit2: Habit

    private var capturedPicker: NumberPickerCallback? = null

    private val bugReporter: ListHabitsBehavior.BugReporter = mock()

    @BeforeTest
    override fun setUp() {
        super.setUp()
        habit1 = fixtures.createShortHabit()
        habit2 = fixtures.createNumericalHabit()
        habitList.add(habit1)
        habitList.add(habit2)
        habitList = spy(habitList)
        behavior = ListHabitsBehavior(
            habitList,
            dirFinder,
            taskRunner,
            screen,
            commandRunner,
            prefs,
            bugReporter
        )
    }

    @Test
    fun testOnEdit() {
        val today = getToday()
        openNumberSheet(habit2, today)
        verify {
            screen.showNumberPopup(0.1, "", habit2, today, any())
        }
        capturedPicker!!.onNumberPicked(100.0, "")
        assertEquals(100000, habit2.computedEntries.get(today).value)
    }

    private fun openNumberSheet(habit: Habit, date: LocalDate): NumberPickerCallback {
        every {
            screen.showNumberPopup(any(), any(), any(), any(), any())
        } calls { args ->
            capturedPicker = args.arg<NumberPickerCallback>(4)
            Unit
        }
        behavior.onEdit(habit, date, 0f, 0f)
        return capturedPicker!!
    }

    @Test
    fun testOnEdit_aNumberNoteAloneKeepsWhatSyncChanged() {
        val today = getToday()
        val shown = habit2.computedEntries.get(today).value / 1000.0
        val sheet = openNumberSheet(habit2, today)
        habit2.originalEntries.add(Entry(today, 5000))
        habit2.recompute()
        sheet.onNumberPicked(shown, "written meanwhile")
        val entry = habit2.computedEntries.get(today)
        assertEquals(5000, entry.value)
        assertEquals("written meanwhile", entry.notes)
    }

    @Test
    fun testOnEdit_aNumberPickedStillWins() {
        val today = getToday()
        val sheet = openNumberSheet(habit2, today)
        habit2.originalEntries.add(Entry(today, 5000))
        habit2.recompute()
        sheet.onNumberPicked(7.0, "")
        assertEquals(7000, habit2.computedEntries.get(today).value)
    }

    @Test
    fun testOnEdit_aNumberReachingTheDaysShareCelebrates() {
        val today = getToday()
        habit2.frequency = Frequency.WEEKLY
        habit2.targetValue = 70.0
        habit2.targetType = NumericalHabitType.AT_LEAST
        openNumberSheet(habit2, today).onNumberPicked(9.0, "")
        verify(exactly(0)) { screen.showConfetti(any(), any(), any()) }
        openNumberSheet(habit2, today).onNumberPicked(10.0, "")
        verify(exactly(1)) { screen.showConfetti(any(), any(), any()) }
    }

    // xrnloops: sync can change a day while its note sheet is open (XRNLOOPS.md)
    private fun openNoteSheet(date: LocalDate): CheckMarkDialogCallback {
        var callback: CheckMarkDialogCallback? = null
        every {
            screen.showCheckmarkPopup(any(), any(), any(), any(), any(), any())
        } calls { args ->
            callback = args.arg<CheckMarkDialogCallback>(5)
            Unit
        }
        behavior.onEdit(habit1, date, 0f, 0f)
        return callback!!
    }

    private fun syncChanges(date: LocalDate, value: Int) {
        habit1.originalEntries.add(Entry(date, value))
        habit1.recompute()
    }

    @Test
    fun testOnEdit_aNoteAloneKeepsWhatSyncChanged() {
        val today = getToday()
        val shown = habit1.computedEntries.get(today).value
        val sheet = openNoteSheet(today)
        syncChanges(today, Entry.SKIP)
        sheet.onNotesSaved(shown, "written meanwhile")
        val entry = habit1.computedEntries.get(today)
        assertEquals(Entry.SKIP, entry.value)
        assertEquals("written meanwhile", entry.notes)
    }

    @Test
    fun testOnEdit_aPickedValueStillWins() {
        val today = getToday()
        val sheet = openNoteSheet(today)
        syncChanges(today, Entry.SKIP)
        sheet.onNotesSaved(Entry.NO, "")
        assertEquals(Entry.NO, habit1.computedEntries.get(today).value)
    }

    @Test
    fun testOnExportCSV() = runTest {
        val outputDir = createTempDir()
        every { dirFinder.getCSVOutputDir() } returns outputDir
        behavior.onExportCSV()
        taskRunner.await()
        verify { screen.showSendFileScreen(any()) }
        val files = outputDir.listFiles()
        assertEquals(1, files!!.size)
    }

    @Test
    fun testOnExportCSV_fail() = runTest {
        val mockDir: UserFile = mock()
        every { mockDir.resolve(any()) } throws RuntimeException("not writable")
        every { dirFinder.getCSVOutputDir() } returns mockDir
        behavior.onExportCSV()
        taskRunner.await()
        verify { screen.showMessage(ListHabitsBehavior.Message.COULD_NOT_EXPORT) }
    }

    @Test
    fun testOnHabitClick() {
        behavior.onClickHabit(habit1)
        verify { screen.showHabitScreen(habit1) }
    }

    @Test
    fun testOnHabitReorder() {
        val from = habit1
        val to = habit2
        behavior.onReorderHabit(from, to)
        verify { habitList.reorder(from, to) }
    }

    @Test
    fun testOnRepairDB() {
        behavior.onRepairDB()
        verify { habitList.repair() }
        verify { screen.showMessage(ListHabitsBehavior.Message.DATABASE_REPAIRED) }
    }

    @Test
    fun testOnSendBugReport() {
        every { bugReporter.getBugReport() } returns "hello"
        behavior.onSendBugReport()
        verify { bugReporter.dumpBugReportToFile() }
        verify { screen.showSendBugReportToDeveloperScreen("hello") }
        every { bugReporter.getBugReport() } throws RuntimeException()
        behavior.onSendBugReport()
        verify { screen.showMessage(ListHabitsBehavior.Message.COULD_NOT_GENERATE_BUG_REPORT) }
    }

    @Test
    fun testOnStartup_firstLaunch() {
        val today = getToday()
        every { prefs.isFirstRun } returns true
        behavior.onStartup()
        verify { prefs.isFirstRun = false }
        verify { prefs.updateLastHint(-1, today) }
        verify { screen.showIntroScreen() }
    }

    @Test
    fun testOnStartup_notFirstLaunch() {
        every { prefs.isFirstRun } returns false
        behavior.onStartup()
        verify { prefs.incrementLaunchCount() }
    }

    @Test
    fun testOnToggle() {
        assertTrue(habit1.isCompletedToday())
        behavior.onToggle(
            habit = habit1,
            date = getToday(),
            value = Entry.NO,
            notes = "",
            x = 0f,
            y = 0f
        )
        assertFalse(habit1.isCompletedToday())
    }
}
