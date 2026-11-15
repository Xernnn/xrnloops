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

package org.isoron.uhabits.xrnsync

import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The number flick's step in sync (2026-11-15): it goes out and comes in, and is 1 when missing. */
class XrnSyncStepTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val run = HabitData("Run", "", "", 8, 1, 1, 1, 0, 5.0, "km", false, 0, null, step = 0.25)

    @Test
    fun aHabitGoesOutWithItsStepAndComesBackTheSame() {
        assertEquals(0.25, run.toJson().getDouble("step"), 0.0)
        assertEquals(run, HabitData.fromJson(run.toJson()))
    }

    @Test
    fun aHabitSyncedWithoutAStepHasOne() {
        val written = run.toJson().apply { remove("step") } // by a client from before, or a habit never edited since
        assertEquals(1.0, HabitData.fromJson(written).step, 0.0)
    }

    @Test
    fun aStepThatIsNoUseIsOne() {
        for (bad in listOf<Any>(0, -2.5, "lots", JSONObject.NULL)) {
            assertEquals("$bad", 1.0, HabitData.fromJson(run.toJson().put("step", bad)).step, 0.0)
        }
    }

    @Test
    fun theStepIsTheHabits() {
        val habit = MemoryModelFactory().buildHabit()
        run.applyTo(habit)
        assertEquals(0.25, habit.step, 0.0)
        assertEquals(run, HabitData.of(habit))
    }

    @Test
    fun aSnapshotFromBeforeTheStepReadsTheFeedAgainOnce() {
        val file = tmp.newFile("xrnsync-snapshot.json")
        Snapshot(40, true, mutableMapOf("h1" to Stamped(run, 5L)), mutableMapOf(), "server", 7L).save(file)
        assertEquals(40, Snapshot.load(file).seq)

        // As a version before the step saved it: the web app's step for Run went by unseen.
        val json = JSONObject(file.readText())
        json.getJSONObject("habits").getJSONObject("h1").getJSONObject("d").remove("step")
        file.writeText(json.toString())
        val old = Snapshot.load(file)
        assertEquals(0, old.seq)
        assertEquals(7L, old.epoch) // the same history, read from its start
        assertTrue(old.initialized)
        assertEquals(run.copy(step = 1.0), old.habits.getValue("h1").data)

        old.seq = 40 // read to the end, and saved: every habit has a step now
        old.save(file)
        assertEquals(40, Snapshot.load(file).seq)
    }
}
