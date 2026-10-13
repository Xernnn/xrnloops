/*
 * Copyright (C) 2026 the xrnhabit authors
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

package org.isoron.uhabits.xrnhabit

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.xrnhabit.XrnhabitContract.COLUMN_ARCHIVED
import org.isoron.uhabits.xrnhabit.XrnhabitContract.COLUMN_COUNTABLE
import org.isoron.uhabits.xrnhabit.XrnhabitContract.COLUMN_DAILY
import org.isoron.uhabits.xrnhabit.XrnhabitContract.COLUMN_DONE
import org.isoron.uhabits.xrnhabit.XrnhabitContract.COLUMN_GOAL_MET
import org.isoron.uhabits.xrnhabit.XrnhabitContract.COLUMN_NAME
import org.isoron.uhabits.xrnhabit.XrnhabitContract.COLUMN_NUMERICAL
import org.isoron.uhabits.xrnhabit.XrnhabitContract.COLUMN_POSITION
import org.isoron.uhabits.xrnhabit.XrnhabitContract.COLUMN_UUID

/**
 * Read-only list of every habit with whether it is done on one date, for xrnhabit. Exported,
 * but guarded by the signature-level [XrnhabitContract.PERMISSION] in the manifest.
 *
 * Reads go through the app's own [org.isoron.uhabits.core.models.HabitList], whose iterator
 * hands out a copy and whose entry lookups are synchronized, so serving them on a binder
 * thread is safe.
 */
class XrnhabitProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (uri.pathSegments != listOf(XrnhabitContract.PATH_HABITS)) return null
        // Providers are published before Application.onCreate finishes; xrnhabit retries.
        if (!HabitsApplication.isComponentReady()) return null

        val date = uri.getQueryParameter(XrnhabitContract.PARAM_DATE)?.let(::parseDate) ?: getToday()
        val cursor = MatrixCursor(COLUMNS)
        for (habit in HabitsApplication.component.habitList) {
            cursor.addRow(
                arrayOf<Any?>(
                    habit.uuid,
                    habit.name,
                    habit.position,
                    if (habit.isArchived) 1 else 0,
                    if (habit.isNumerical) 1 else 0,
                    if (isCountable(habit)) 1 else 0,
                    if (isDone(habit, date)) 1 else 0,
                    if (isGoalMet(habit, date)) 1 else 0,
                    if (isDaily(habit)) 1 else 0
                )
            )
        }
        return cursor
    }

    /**
     * Done means the user really did it that day: a manual check for a yes/no habit (not
     * Loop's auto-filled check or a skip), or reaching the target of an "at least" habit.
     */
    private fun isDone(habit: Habit, date: LocalDate): Boolean {
        val value = habit.originalEntries.get(date).value
        if (value == Entry.UNKNOWN) return false
        return if (habit.isNumerical) {
            isCountable(habit) && value / 1000.0 >= habit.targetValue
        } else {
            value == Entry.YES_MANUAL
        }
    }

    /**
     * Done, or covered by the habit's goal: for a "3 times a week" habit Loop fills the other
     * days of a week whose goal is met with an automatic check, and those count here. For an
     * every-day habit this is the same as [isDone].
     */
    private fun isGoalMet(habit: Habit, date: LocalDate): Boolean {
        if (habit.isNumerical) return isDone(habit, date)
        val value = habit.computedEntries.get(date).value
        return value == Entry.YES_MANUAL || value == Entry.YES_AUTO
    }

    /** Loop only fills in goal days for yes/no habits that aren't every day. */
    private fun isDaily(habit: Habit): Boolean =
        habit.isNumerical || habit.frequency.numerator == habit.frequency.denominator

    /** Loop itself never treats an "at most" habit as completed, so it can't unlock anything. */
    private fun isCountable(habit: Habit): Boolean =
        !habit.isNumerical || habit.targetType == NumericalHabitType.AT_LEAST

    private fun parseDate(text: String): LocalDate? {
        val parts = text.split("-").map { it.toIntOrNull() ?: return null }
        if (parts.size != 3) return null
        return LocalDate(parts[0], parts[1], parts[2])
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.org.isoron.uhabits.habit"

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("read-only")

    companion object {
        private val COLUMNS = arrayOf(
            COLUMN_UUID,
            COLUMN_NAME,
            COLUMN_POSITION,
            COLUMN_ARCHIVED,
            COLUMN_NUMERICAL,
            COLUMN_COUNTABLE,
            COLUMN_DONE,
            COLUMN_GOAL_MET,
            COLUMN_DAILY
        )
    }
}
