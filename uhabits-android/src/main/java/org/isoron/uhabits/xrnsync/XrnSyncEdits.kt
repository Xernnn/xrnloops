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

import org.isoron.uhabits.core.commands.ArchiveHabitsCommand
import org.isoron.uhabits.core.commands.ChangeHabitColorCommand
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CreateHabitCommand
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.commands.DeleteHabitsCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.commands.UnarchiveHabitsCommand
import org.isoron.uhabits.core.models.HabitList
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * When each record was changed here, so its push carries that time rather than the time it
 * finally got out: a tick made offline at 08:00 has to lose to a web edit made at 12:00 when the
 * phone reconnects at 18:00 (XRNLOOPS.md, "Sync"). A change can wait hours for a network and
 * outlive the process, so each one is appended to [file] as it is noted; what the server has
 * taken is forgotten again, and the file rewritten without it. Keys are [XrnSync]'s: a habit's
 * uuid, or an entry's "uuid|yyyy-mm-dd".
 */
internal class XrnSyncEdits(private val file: File?) {
    private val changed = HashMap<String, Long>()

    init {
        load()
    }

    /** [keys] were changed here at [time] (wall-clock ms). */
    @Synchronized
    fun noteChanged(keys: Collection<String>, time: Long) {
        if (keys.isEmpty()) return
        for (key in keys) changed[key] = time
        append(keys.map { "$KIND_CHANGED\t$it\t$time" })
    }

    /** [keys] came in from a backup: old, of no known age, so they only fill gaps on the server. */
    @Synchronized
    fun noteImported(keys: Collection<String>) = noteChanged(keys, FROM_BACKUP)

    /** Every noted change: when it was made, or [FROM_BACKUP]. */
    @Synchronized
    fun changes(): Map<String, Long> = HashMap(changed)

    /**
     * The server has settled these (taken them, or answered with a newer version that is now
     * here): forget each, unless it was changed again after the time it was sent with.
     */
    @Synchronized
    fun settle(sent: Map<String, Long>) {
        var forgot = false
        for ((key, time) in sent) {
            if (changed[key] == time) {
                changed.remove(key)
                forgot = true
            }
        }
        if (forgot) rewrite()
    }

    /**
     * Forgets the changes noted before [time] that no longer differ from what the server has
     * ([pending] are those that still do): undone before they went out, say a tick taken back.
     */
    @Synchronized
    fun forgetAllBut(pending: Set<String>, time: Long) {
        val stale = changed.filter { (key, at) -> key !in pending && at < time }.keys
        if (stale.isEmpty()) return
        changed.keys.removeAll(stale)
        rewrite()
    }

    private fun load() {
        val text = try {
            file?.takeIf { it.exists() }?.readText() ?: return
        } catch (e: IOException) {
            return // what it held goes out stamped "now", as before there was one
        }
        for (line in text.lines()) {
            val parts = line.split('\t')
            val time = parts.getOrNull(2)?.toLongOrNull() ?: continue // a line cut short by a crash
            if (parts[0] == KIND_CHANGED) changed[parts[1]] = time
        }
        // Cut short: the next line appended would run on from it and be lost too.
        if (text.isNotEmpty() && !text.endsWith("\n")) rewrite()
    }

    private fun append(lines: List<String>) {
        val file = file ?: return
        try {
            FileOutputStream(file, true).use { out ->
                out.write(lines.joinToString("\n", postfix = "\n").toByteArray())
            }
        } catch (e: IOException) {
            // Still in memory: it goes out right this time unless the process ends first.
        }
    }

    private fun rewrite() {
        val file = file ?: return
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(changed.entries.joinToString("") { (key, time) -> "$KIND_CHANGED\t$key\t$time\n" })
            if (!tmp.renameTo(file)) throw IOException("can't replace $file")
        } catch (e: IOException) {
            // The old file keeps settled changes too; their stamps are still the right ones.
        }
    }

    companion object {
        /** Noted for a record brought in by an import: see [noteImported]. */
        const val FROM_BACKUP = 0L
        private const val KIND_CHANGED = "c"

        /**
         * The records [command] changed, by key, for one of Loop's commands; nothing for one it
         * doesn't know, whose changes go out stamped with the time they're sent, as before.
         */
        fun changedBy(command: Command, habitList: HabitList): List<String> = when (command) {
            is CreateRepetitionCommand -> listOfNotNull(command.habit.uuid?.let { "$it|${command.date.toCSVString()}" })
            is EditHabitCommand -> listOfNotNull(habitList.getById(command.habitId)?.uuid ?: command.modified.uuid)
            is CreateHabitCommand -> listOfNotNull(command.model.uuid)
            is DeleteHabitsCommand -> command.selected.mapNotNull { it.uuid }
            is ArchiveHabitsCommand -> command.selected.mapNotNull { it.uuid }
            is UnarchiveHabitsCommand -> command.selected.mapNotNull { it.uuid }
            is ChangeHabitColorCommand -> command.selected.mapNotNull { it.uuid }
            else -> emptyList()
        }
    }
}
