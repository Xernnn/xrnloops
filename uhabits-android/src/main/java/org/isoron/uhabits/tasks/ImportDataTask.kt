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
package org.isoron.uhabits.tasks

import android.util.Log
import org.isoron.platform.io.UserFile
import org.isoron.uhabits.core.io.GenericImporter
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.xrnsync.XrnSync

class ImportDataTask(
    private val importer: GenericImporter,
    private val file: UserFile,
    private val listener: Listener
) : Task {
    private var result = 0
    override suspend fun doInBackground() {
        // xrnloops: sync waits for the import, then sends what it changed as old (XRNLOOPS.md).
        val sync = XrnSync.instance
        val base = sync?.beginImport()
        try {
            importAll()
        } finally {
            if (base != null) sync.endImport(base)
        }
    }

    /**
     * xrnloops: not inside a transaction. Android hands its one connection to the thread that
     * began one until it ends, and the import takes the habit list's and the repositories' locks
     * as it goes, which any other thread holds while it waits for that connection: sync applying
     * the server's changes, a tick, a screen loading a habit's days. Each importer writes a
     * habit's days in one go instead (EntryList.addAll), so an import stays quick.
     */
    private suspend fun importAll() {
        try {
            if (importer.canHandle(file)) {
                importer.importHabitsFromFile(file)
                result = SUCCESS
            } else {
                result = NOT_RECOGNIZED
            }
        } catch (e: Exception) {
            result = FAILED
            Log.e("ImportDataTask", "Import failed", e)
        }
    }

    override fun onPostExecute() {
        listener.onImportDataFinished(result)
    }

    fun interface Listener {
        fun onImportDataFinished(result: Int)
    }

    companion object {
        const val FAILED = 3
        const val NOT_RECOGNIZED = 2
        const val SUCCESS = 1
    }
}
