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

package org.isoron.uhabits.activities.habits.edit

import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder

/**
 * xrnloops: what the habit form sets, and the habit its Save writes (XRNLOOPS.md). The rest comes
 * from the habit as it is stored when Save is pressed: its place in the list, whether it's
 * archived, its uuid, and its own notes (`description`). The form has had no box for those since
 * 2026-11-15, but they stay in the database and in sync, so Save must carry them, never blank them.
 */
data class HabitForm(
    val type: HabitType,
    val name: String,
    val question: String,
    val color: PaletteColor,
    val frequency: Frequency,
    val reminder: Reminder?,
    val targetValue: Double = 0.0,
    val targetType: NumericalHabitType = NumericalHabitType.AT_LEAST,
    val unit: String = "",
    val step: Double = 1.0
) {
    /** The habit to save: [stored] (null for a new habit) with what this form sets. */
    fun habit(factory: ModelFactory, stored: Habit?): Habit {
        val habit = factory.buildHabit()
        if (stored != null) habit.copyFrom(stored)
        habit.name = name
        habit.question = question
        habit.color = color
        habit.reminder = reminder
        habit.frequency = frequency
        if (type == HabitType.NUMERICAL) {
            habit.targetValue = targetValue
            habit.targetType = targetType
            habit.unit = unit
            habit.step = step
        }
        habit.type = type
        return habit
    }
}
