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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What a sync round costs when nothing changed: a round runs after every tick, on every resume
 * and every half hour, and each one parsed the whole snapshot file and built its text again.
 */
class XrnSyncSnapshotTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val run = HabitData("Run", "", "", 8, 1, 1, 1, 0, 5.0, "km", false, 0, null, step = 0.25)

    /** A few years of a daily habit, as saved. */
    private fun saved(): File {
        val file = tmp.newFile("xrnsync-snapshot.json")
        val entries = (0 until 1000).associate { "h1|day$it" to Stamped(EntryData(it * 10, ""), it.toLong()) }
        Snapshot(40, true, mutableMapOf("h1" to Stamped(run, 5L)), entries.toMutableMap(), "server", 7L).save(file)
        return file
    }

    @Test
    fun aSnapshotNothingChangedInIsntBuiltAgain() {
        val file = saved()
        val written = file.readText()
        val snap = Snapshot.load(file)
        repeat(3) { snap.save(file) }
        assertEquals(0, snap.serialisations)
        assertEquals(written, file.readText())
        // Nor once it has been saved itself.
        snap.entries["h1|day1"] = Stamped(EntryData(2, ""), 99L)
        repeat(3) { snap.save(file) }
        assertEquals(1, snap.serialisations)
    }

    @Test
    fun everyChangeIsSaved() {
        val changes = listOf<(Snapshot) -> Unit>(
            { it.entries["h1|day1"] = Stamped(EntryData(2, ""), 99L) },
            { it.entries["h1|day1"] = Stamped(EntryData(10, ""), 99L) }, // a new stamp only
            { it.entries["h1|day1"] = Stamped(EntryData(10, "a note"), 1L) },
            { it.entries["h1|new"] = Stamped(EntryData(2, ""), 1L) },
            { it.entries.keys.removeAll { key -> key.startsWith("h1|") } },
            { it.habits["h1"] = Stamped(run.copy(name = "Run far"), 5L) },
            { it.habits["h1"] = Stamped(run, 6L) },
            { it.habits.remove("h1") },
            { it.tombstones["h2"] = 3L },
            { it.seq = 41 },
            { it.initialized = false },
            { it.server = "another" },
            { it.epoch = 8L },
            { it.resend = true }
        )
        for ((i, change) in changes.withIndex()) {
            val file = File(tmp.root, "snap$i.json")
            saved().copyTo(file)
            tmp.root.resolve("xrnsync-snapshot.json").delete()
            val snap = Snapshot.load(file)
            change(snap)
            snap.save(file)
            assertEquals("change $i", 1, snap.serialisations)
            val back = Snapshot.load(file)
            assertEquals("change $i", snap.entries, back.entries)
            assertEquals("change $i", snap.habits, back.habits)
            assertEquals("change $i", snap.tombstones, back.tombstones)
            val header = { s: Snapshot -> listOf(s.seq, s.initialized, s.server, s.epoch, s.resend) }
            assertEquals("change $i", header(snap), header(back))
        }
    }

    @Test
    fun aCursorKeptBehindIsSaved() {
        // A pull of several pages saves a cursor behind an entry still waiting for its habit.
        val file = saved()
        val snap = Snapshot.load(file)
        snap.save(file, cursor = 30)
        assertEquals(30, Snapshot.load(file).seq)
        snap.save(file)
        assertEquals(40, Snapshot.load(file).seq)
        assertEquals(2, snap.serialisations)
    }

    @Test
    fun aRoundTakesTheSnapshotTheLastOneKeptWithoutReadingTheFile() {
        val file = saved()
        val kept = KeptSnapshot(file)
        val first = kept.take()
        assertEquals(40, first.seq)
        first.seq = 45
        first.save(file)
        kept.keep(first)
        // Changed underneath: a round that kept its snapshot doesn't read the file again.
        Snapshot(99, true, mutableMapOf(), mutableMapOf(), "server", 7L).save(file)
        assertSame(first, kept.take())
        // Taken, and a round that failed keeps nothing back: the next one reads the file.
        assertEquals(99, kept.take().seq)
    }
}
