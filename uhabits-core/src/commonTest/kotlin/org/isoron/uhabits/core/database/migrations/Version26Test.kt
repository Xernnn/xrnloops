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

package org.isoron.uhabits.core.database.migrations

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.format
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.query
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.database.HabitRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** xrnloops: 26 adds the number flick's step; every habit there was before reads as 1. */
class Version26Test : BaseUnitTest() {

    @Test
    fun testMigrateTo26GivesEveryHabitAStepOfOne() = runTest {
        val db = openDatabaseResource("/databases/022.db")
        db.migrateTo(26) { v ->
            fileOpener.openResourceFile("migrations/${format("%02d.sql", v)}").lines().joinToString("\n")
        }
        val steps = mutableListOf<Double>()
        db.query("select step from Habits") { steps.add(it.getReal(0)) }
        assertTrue(steps.isNotEmpty())
        assertEquals(List(steps.size) { 1.0 }, steps)
        assertEquals(steps, HabitRepository(db).findAll().map { it.step })
        db.close()
    }
}
