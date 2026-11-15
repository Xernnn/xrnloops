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
package org.isoron.uhabits.core.commands

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CreateRepetitionCommandTest : BaseUnitTest() {
    private lateinit var command: CreateRepetitionCommand
    private lateinit var habit: Habit
    private lateinit var today: LocalDate

    @BeforeTest
    override fun setUp() {
        super.setUp()
        habit = fixtures.createShortHabit()
        habitList.add(habit)
        today = getToday()
        command = CreateRepetitionCommand(habitList, habit, today, 100, "")
    }

    @Test
    fun testExecute() {
        val entries = habit.originalEntries
        var entry = entries.get(today)
        assertEquals(Entry.YES_MANUAL, entry.value)
        command.run()
        entry = entries.get(today)
        assertEquals(100, entry.value.toLong())
    }

    // xrnloops: nothing in the app writes a note since 2026-11-15, and a new value keeps the
    // day's (XRNLOOPS.md): the one it has when the command runs, not when it was made.
    @Test
    fun testANewValueKeepsTheDaysNote() {
        val write = CreateRepetitionCommand(habitList, habit, today, Entry.NO)
        habit.originalEntries.add(Entry(today, Entry.YES_MANUAL, "written by sync meanwhile"))
        write.run()
        assertEquals(Entry(today, Entry.NO, "written by sync meanwhile"), habit.originalEntries.get(today))
    }

    @Test
    fun testANoteGivenStillGoesIn() {
        habit.originalEntries.add(Entry(today, Entry.YES_MANUAL, "old"))
        CreateRepetitionCommand(habitList, habit, today, Entry.NO, "").run()
        assertEquals("", habit.originalEntries.get(today).notes)
    }
}
