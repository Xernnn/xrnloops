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
package org.isoron.uhabits.core.database

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.OneConnection
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.querySingle
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * xrnloops: a habit made while another thread writes days gets its own row's id, on a database
 * whose one connection every thread shares, as Android's does ([OneConnection]).
 */
class NewHabitIdTest {

    @Test
    fun aNewHabitGetsItsOwnIdWhileDaysAreWritten() = runTest {
        val db = OneConnection(TestDatabaseHelper.createEmptyDatabase())
        val factory = SQLModelFactory(db)
        val habits = factory.buildHabitList()
        val walk = factory.buildHabit().also { habits.add(it) }
        val writing = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>()
        val writer = thread {
            var day = LocalDate(2020, 1, 1)
            try {
                while (writing.get()) {
                    walk.originalEntries.add(Entry(day, Entry.YES_MANUAL))
                    day = day.plus(1)
                }
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        val made = try {
            (1..300).map { n -> factory.buildHabit().also { it.name = "Habit $n" }.also(habits::add) }
        } finally {
            writing.set(false)
            writer.join(10_000)
        }
        assertNull(failure.get())
        for (habit in made) {
            val uuid = db.querySingle("SELECT uuid FROM Habits WHERE id = ?", habit.id.toString()) { it.getText(0) }
            assertEquals(habit.uuid, uuid, habit.name)
        }
    }
}
