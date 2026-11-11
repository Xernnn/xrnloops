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

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.commands.DeleteHabitsCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What a push sends ([planPush]): a change stamped with when it was made here, not when it was
 * sent, and a habit gone from the phone sent as deleted only if it was deleted here.
 */
class XrnSyncPushTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val hour = 3_600_000L
    private val at8 = 8 * hour
    private val at12 = 12 * hour
    private val at18 = 18 * hour

    private val read = habit("Read")
    private val day = "h1|2026-11-10"

    private fun habit(name: String, position: Int = 0) =
        HabitData(name, "", "", 8, 1, 1, 0, 0, 0.0, "", false, position, null)

    private fun local(habits: Map<String, HabitData> = mapOf("h1" to read), entries: Map<String, EntryData> = emptyMap()) =
        XrnSync.LocalState(habits.toMutableMap(), entries.toMutableMap())

    private fun snapshot(habits: Map<String, Stamped<HabitData>> = mapOf("h1" to Stamped(read, 1L)), entries: Map<String, Stamped<EntryData>> = emptyMap()) =
        Snapshot(10, true, habits.toMutableMap(), entries.toMutableMap(), "server", 1L)

    private val tick = EntryData(Entry.YES_MANUAL, "")

    @Test
    fun anOfflineTickLosesToALaterEditElsewhere() {
        // Ticked offline at 08:00 over the agreed day, sent at 18:00; the web changed it at 12:00.
        val agreed = snapshot(entries = mapOf(day to Stamped(EntryData(Entry.NO, ""), 7 * hour)))
        val (_, entries) = planPush(local(entries = mapOf(day to tick)), agreed, mapOf(day to at8), at18, emptyMap(), emptySet())
        assertEquals(at8, entries.single().mtime)
        assertTrue("the server keeps the newer write", entries.single().mtime < at12)
    }

    @Test
    fun aChangeNobodyNotedGoesOutAsBefore() {
        val (_, entries) = planPush(local(entries = mapOf(day to tick)), snapshot(), emptyMap(), at18, emptyMap(), emptySet())
        assertEquals(at18, entries.single().mtime)
    }

    @Test
    fun aChangeStillBeatsTheVersionItWasMadeOn() {
        // The agreed version came from a clock running ahead of this phone's.
        val agreed = snapshot(entries = mapOf(day to Stamped(EntryData(Entry.NO, ""), 9 * hour)))
        val (_, entries) = planPush(local(entries = mapOf(day to tick)), agreed, mapOf(day to at8), at18, emptyMap(), emptySet())
        assertEquals(9 * hour + 1, entries.single().mtime)
        assertEquals(9 * hour + 1, stampFor(null, 9 * hour, at8))
    }

    @Test
    fun anImportOnlyFillsGaps() {
        val agreed = snapshot(entries = mapOf(day to Stamped(EntryData(Entry.NO, ""), 7 * hour)))
        val noted = mapOf(day to XrnSyncEdits.FROM_BACKUP, "h1" to XrnSyncEdits.FROM_BACKUP)
        val (habits, entries) = planPush(local(mapOf("h1" to habit("Old name")), mapOf(day to tick)), agreed, noted, at18, emptyMap(), emptySet())
        assertEquals(listOf(BACKUP_MTIME), entries.map { it.mtime })
        assertEquals(listOf(BACKUP_MTIME), habits.map { it.mtime })
        assertTrue(BACKUP_MTIME < 7 * hour)
    }

    @Test
    fun aHabitBroughtBackAfterItsDeletionWasAgreedHasDaysToFetchAgain() {
        // Deleted on the web and taken off this phone, days and all. Then a browser that was
        // offline sends an edit made after that, and the habit is back in the feed.
        val agreed = snapshot(habits = emptyMap())
        agreed.tombstones["h1"] = at8
        assertTrue(agreed.hadDays("h1"))
        assertTrue("deleted here, which the server refused", snapshot().hadDays("h1"))
        assertFalse("new to this phone", agreed.hadDays("h9"))
    }

    @Test
    fun aDeletionCarriesItsTime() {
        val (habits, _) = planPush(local(habits = emptyMap()), snapshot(), mapOf("h1" to at8), at18, mapOf("h1" to at8), emptySet())
        assertEquals(listOf(Triple("h1", true, at8)), habits.map { Triple(it.uuid, it.deleted, it.mtime) })
    }

    @Test
    fun aNewOrderAloneStaysJustPastTheAgreedVersion() {
        val (habits, _) = planPush(local(mapOf("h1" to habit("Read", position = 3))), snapshot(), mapOf("h1" to at8), at18, emptyMap(), emptySet())
        assertEquals(2L, habits.single().mtime)
    }

    @Test
    fun deletingTheLastHabitsDeletesThem() {
        val agreed = snapshot(mapOf("h1" to Stamped(read, 1L), "h2" to Stamped(habit("Walk"), 1L)))
        val plan = planPush(local(habits = emptyMap()), agreed, mapOf("h1" to at8, "h2" to at8), at18, mapOf("h1" to at8), setOf("h2"))
        assertEquals(setOf("h1", "h2"), plan.habits.filter { it.deleted }.map { it.uuid }.toSet())
        assertEquals(emptySet<String>(), plan.lost)
    }

    @Test
    fun habitsTheDatabaseLostAreNotDeletions() {
        // A new, empty database: a habit made in it since doesn't make the others deletions.
        val agreed = snapshot(entries = mapOf(day to Stamped(tick, 1L)))
        val plan = planPush(local(mapOf("h9" to habit("New"))), agreed, emptyMap(), at18, emptyMap(), emptySet())
        assertEquals(listOf("h9" to false), plan.habits.map { it.uuid to it.deleted })
        assertEquals(emptyList<EntryRecord>(), plan.entries)
        assertEquals(setOf("h1"), plan.lost)
    }

    @Test
    fun afterARestoreEverythingGoesAgainWithItsOwnStamp() {
        val agreed = snapshot(
            mapOf("h1" to Stamped(read, 5 * hour), "h2" to Stamped(habit("Walk"), 6 * hour)),
            mapOf(day to Stamped(tick, 7 * hour), "h1|2026-11-09" to Stamped(EntryData.EMPTY, 4 * hour))
        )
        agreed.tombstones["h3"] = 3 * hour
        agreed.startOver()
        assertEquals(0L to null, agreed.seq to agreed.epoch)
        val walkFar = habit("Walk far")
        val plan = planPush(local(mapOf("h1" to read, "h2" to walkFar), mapOf(day to tick)), agreed, mapOf("h2" to at8), at18, emptyMap(), emptySet())
        // Unchanged: the stamp it was agreed with, so the restored server's older copy loses and
        // a newer one made elsewhere since still wins. Changed here: when it was changed.
        assertEquals(
            setOf(Triple("h1", false, 5 * hour), Triple("h2", false, at8), Triple("h3", true, 3 * hour)),
            plan.habits.map { Triple(it.uuid, it.deleted, it.mtime) }.toSet()
        )
        assertEquals(walkFar, plan.habits.single { it.uuid == "h2" }.data)
        assertEquals(setOf(day to 7 * hour, "h1|2026-11-09" to 4 * hour), plan.entries.map { it.key to it.mtime }.toSet())
    }

    @Test
    fun withoutASnapshotWhatWasNotedHereKeepsItsStamp() {
        // The snapshot was unreadable: nothing says how old an unnoted record is, so it only
        // fills a gap, but the tick made here at 08:00 and the deletion at 09:00 keep theirs.
        val blank = Snapshot.fresh("server")
        val here = local(entries = mapOf(day to tick, "h1|2026-01-01" to tick))
        val plan = planPush(here, blank, mapOf(day to at8), at18, mapOf("h7" to 9 * hour), emptySet())
        assertEquals(setOf(day to at8, "h1|2026-01-01" to UNKNOWN_AGE_MTIME), plan.entries.map { it.key to it.mtime }.toSet())
        assertEquals(
            setOf(Triple("h1", false, UNKNOWN_AGE_MTIME), Triple("h7", true, 9 * hour)),
            plan.habits.map { Triple(it.uuid, it.deleted, it.mtime) }.toSet()
        )
        assertEquals(null, plan.habits.single { it.uuid == "h7" }.data)
    }

    @Test
    fun deletionsAreNotedUntilTheServerHasThem() {
        val file = tmp.newFile("edits.txt")
        XrnSyncEdits(file).noteDeleted(listOf("h1", "h2"), at8)
        val edits = XrnSyncEdits(file) // the next process
        assertEquals(mapOf("h1" to at8, "h2" to at8), edits.deletedHere())
        edits.settle(emptyMap(), tombstones = listOf("h1"))
        assertEquals(setOf("h2"), XrnSyncEdits(file).deletedHere().keys)
    }

    @Test
    fun editsOutliveTheProcessUntilSettled() {
        val file = tmp.newFile("edits.txt")
        XrnSyncEdits(file).apply {
            noteChanged(listOf(day, "h2"), at8)
            noteImported(listOf("h3|2016-01-01"))
        }
        file.appendText("c\th4|2026") // the process ended halfway through a line
        val edits = XrnSyncEdits(file) // the next one
        assertEquals(mapOf(day to at8, "h2" to at8, "h3|2016-01-01" to XrnSyncEdits.FROM_BACKUP), edits.changes())

        edits.noteChanged(listOf("h2"), at12) // changed again after it was sent at 08:00
        assertEquals(at12, XrnSyncEdits(file).changes()["h2"])
        edits.settle(mapOf(day to at8, "h2" to at8))
        assertEquals(mapOf("h2" to at12, "h3|2016-01-01" to XrnSyncEdits.FROM_BACKUP), XrnSyncEdits(file).changes())

        // Noted before the round began and no longer different: undone. Noted since: kept.
        edits.noteChanged(listOf("h5"), at18)
        edits.forgetAllBut(setOf("h2"), at12 + 1)
        assertEquals(mapOf("h2" to at12, "h5" to at18), XrnSyncEdits(file).changes())
    }

    @Test
    fun theCommandsSayWhatTheyChanged() {
        val factory = MemoryModelFactory()
        val list = factory.buildHabitList()
        val h = factory.buildHabit().also { list.add(it) }
        val date = LocalDate(2026, 11, 10)
        assertEquals(listOf("${h.uuid}|2026-11-10"), XrnSyncEdits.changedBy(CreateRepetitionCommand(list, h, date, Entry.YES_MANUAL, ""), list))
        assertEquals(listOf(h.uuid), XrnSyncEdits.changedBy(EditHabitCommand(list, h.id!!, factory.buildHabit().also { it.copyFrom(h) }), list))
        assertEquals(listOf(h.uuid), XrnSyncEdits.changedBy(DeleteHabitsCommand(list, listOf(h)), list))
    }

    @Test
    fun anImportsChangesButNotItsNewOrder() {
        val before = local(mapOf("h1" to read, "h2" to habit("Walk")), mapOf(day to tick))
        val after = local(
            mapOf("h1" to habit("Read", position = 5), "h2" to habit("Walk far"), "h3" to habit("New")),
            mapOf(day to tick, "h1|2016-01-01" to tick)
        )
        assertEquals(setOf("h2", "h3", "h1|2016-01-01"), importedKeys(before, after).toSet())
    }
}
