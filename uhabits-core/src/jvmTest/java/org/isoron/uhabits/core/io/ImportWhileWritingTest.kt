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
package org.isoron.uhabits.core.io

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.OneConnection
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * xrnloops: an import goes on while another thread writes days, on a database that, like
 * Android's, has one connection and lends it to a transaction's thread until it ends
 * ([OneConnection]). The import used to run inside one transaction (ImportDataTask) while it took
 * the habit list's and repositories' locks, which the other thread holds while it waits for the
 * connection: a tick during an import, or sync applying the server's changes, deadlocked.
 */
class ImportWhileWritingTest : BaseUnitTest() {

    @Test
    fun anImportGoesOnWhileDaysAreWritten() = runTest {
        val db = OneConnection(TestDatabaseHelper.createEmptyDatabase())
        val factory = SQLModelFactory(db)
        val habits = factory.buildHabitList()
        val walk = factory.buildHabit().also {
            it.name = "Walk"
            habits.add(it)
        }
        val file = copyResourceToTempFile("loop.db")
        val importer = LoopDBImporter(habits, factory, databaseOpener(), commandRunner, StandardLogging(), fileOpener)

        val importing = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>()
        val writer = thread {
            var day = LocalDate(2020, 1, 1)
            try {
                while (importing.get()) {
                    // The list's lock and the repository's, then the connection: as every thread.
                    walk.originalEntries.add(Entry(day, Entry.YES_MANUAL))
                    day = day.plus(1)
                }
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        try {
            importer.importHabitsFromFile(file) // as ImportDataTask runs it
        } finally {
            importing.set(false)
            writer.join(10_000)
        }
        assertNull(failure.get())
        assertEquals(10, habits.size())
    }
}
