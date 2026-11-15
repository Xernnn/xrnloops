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
package org.isoron.uhabits.core.ui.screens.habits.list

import me.tatarka.inject.annotations.Inject
import org.isoron.platform.io.UserFile
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.models.Entry.Companion.YES_MANUAL
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType.AT_LEAST
import org.isoron.uhabits.core.models.NumericalHabitType.AT_MOST
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.ExportCSVTask
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.core.tasks.TaskRunner
import org.isoron.uhabits.core.ui.callbacks.CheckMarkDialogCallback
import org.isoron.uhabits.core.ui.callbacks.NumberPickerCallback
import kotlin.math.roundToInt

@Inject
open class ListHabitsBehavior(
    private val habitList: HabitList,
    private val dirFinder: DirFinder,
    private val taskRunner: TaskRunner,
    private val screen: Screen,
    private val commandRunner: CommandRunner,
    private val prefs: Preferences
) {
    open fun onClickHabit(h: Habit) {
        screen.showHabitScreen(h)
    }

    // xrnloops: the day's sheet sets a value only (XRNLOOPS.md); the note the day has stays.
    open fun onEdit(habit: Habit, date: LocalDate, x: Float, y: Float) {
        val entry = habit.computedEntries.get(date)
        if (habit.type == HabitType.NUMERICAL) {
            val oldValue = entry.value.toDouble() / 1000
            screen.showNumberPopup(oldValue, habit, date) { newValue: Double ->
                if (newValue != oldValue && meetsDayTarget(habit, newValue)) {
                    screen.showConfetti(habit.color, x, y)
                }
                val value = (newValue * 1000).roundToInt()
                commandRunner.run(CreateRepetitionCommand(habitList, habit, date, value))
            }
        } else {
            screen.showCheckmarkPopup(
                entry.value,
                habit.color,
                habit.name, // xrnloops: the sheet names the habit and the day (XRNLOOPS.md)
                date
            ) { newValue: Int ->
                if (newValue != entry.value && newValue == YES_MANUAL) screen.showConfetti(habit.color, x, y)
                commandRunner.run(CreateRepetitionCommand(habitList, habit, date, newValue))
            }
        }
    }

    /** xrnloops: the day's share of the target, as the list shows it. */
    private fun meetsDayTarget(habit: Habit, amount: Double): Boolean {
        val target = habit.targetValue / habit.frequency.denominator
        return (habit.targetType == AT_LEAST && amount >= target) ||
            (habit.targetType == AT_MOST && amount <= target)
    }

    open fun onExportCSV() {
        val selected = habitList.toList()
        val outputDir = dirFinder.getCSVOutputDir()
        taskRunner.execute(
            ExportCSVTask(habitList, selected, outputDir) { filename: String? ->
                if (filename != null) {
                    screen.showSendFileScreen(filename)
                } else {
                    screen.showMessage(Message.COULD_NOT_EXPORT)
                }
            }
        )
    }

    open fun onFirstRun() {
        prefs.isFirstRun = false
        prefs.updateLastHint(-1, getToday())
        screen.showIntroScreen()
    }

    open fun onReorderHabit(from: Habit, to: Habit) {
        taskRunner.execute { habitList.reorder(from, to) }
    }

    open fun onRepairDB() {
        taskRunner.execute(object : Task {
            override suspend fun doInBackground() {
                habitList.repair()
            }
            override fun onPostExecute() {
                screen.showMessage(Message.DATABASE_REPAIRED)
            }
        })
    }

    open fun onStartup() {
        prefs.incrementLaunchCount()
        if (prefs.isFirstRun) onFirstRun()
    }

    // xrnloops: no note from the list either; the day keeps its own (XRNLOOPS.md).
    open fun onToggle(habit: Habit, date: LocalDate, value: Int, x: Float, y: Float) {
        commandRunner.run(
            CreateRepetitionCommand(habitList, habit, date, value)
        )
        if (value == YES_MANUAL) screen.showConfetti(habit.color, x, y)
    }

    enum class Message {
        COULD_NOT_EXPORT,
        IMPORT_SUCCESSFUL,
        IMPORT_FAILED,
        DATABASE_REPAIRED,
        FILE_NOT_RECOGNIZED
    }

    interface DirFinder {
        fun getCSVOutputDir(): UserFile
    }

    interface Screen {
        fun showHabitScreen(h: Habit)
        fun showIntroScreen()
        fun showMessage(m: Message)
        fun showNumberPopup(
            value: Double,
            habit: Habit, // xrnloops
            date: LocalDate, // xrnloops
            callback: NumberPickerCallback
        )
        fun showCheckmarkPopup(
            selectedValue: Int,
            color: PaletteColor,
            habitName: String, // xrnloops
            date: LocalDate, // xrnloops
            callback: CheckMarkDialogCallback
        )
        fun showSendFileScreen(filename: String)
        fun showConfetti(color: PaletteColor, x: Float, y: Float)
    }
}
