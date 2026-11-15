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

import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.xrnsync.HabitData
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The habit form's Save (EditHabitActivity, HabitForm). The form has no Notes box since
 * 2026-11-15, and Save keeps the habit's own notes as they are stored when it's pressed.
 */
class HabitFormTest {
    private val factory = MemoryModelFactory()
    private val list = factory.buildHabitList()

    private fun stored(build: Habit.() -> Unit): Habit =
        factory.buildHabit().apply(build).also { list.add(it) }

    /** Save, as EditHabitActivity does it: the habit as stored now, with the form on top. */
    private fun save(habit: Habit, form: HabitForm) =
        EditHabitCommand(list, habit.id!!, form.habit(factory, list.getById(habit.id!!))).run()

    private val readForm = HabitForm(
        type = HabitType.YES_NO,
        name = "Read more",
        question = "Did you read today?",
        color = PaletteColor(3),
        frequency = Frequency.DAILY,
        reminder = null
    )

    @Test
    fun savingAnEditKeepsTheHabitsNotes() {
        val read = stored {
            name = "Read"
            description = "Ten pages before bed"
        }
        save(read, readForm)
        val saved = list.getById(read.id!!)!!
        assertEquals("Read more", saved.name)
        assertEquals("Did you read today?", saved.question)
        assertEquals("Ten pages before bed", saved.description)
        assertEquals("Ten pages before bed", HabitData.of(saved).description) // and so in sync
    }

    @Test
    fun savingANumberHabitKeepsItsNotes() {
        val run = stored {
            name = "Run"
            type = HabitType.NUMERICAL
            targetValue = 5.0
            unit = "km"
            description = "Slow on Mondays"
        }
        val form = HabitForm(
            type = HabitType.NUMERICAL,
            name = "Run",
            question = "How far did you run?",
            color = PaletteColor(5),
            frequency = Frequency.DAILY,
            reminder = null,
            targetValue = 8.0,
            targetType = NumericalHabitType.AT_LEAST,
            unit = "km",
            step = 0.5
        )
        save(run, form)
        val saved = list.getById(run.id!!)!!
        assertEquals(8.0, saved.targetValue, 0.0)
        assertEquals(0.5, saved.step, 0.0)
        assertEquals("Slow on Mondays", saved.description)
    }

    @Test
    fun theNotesKeptAreTheOnesStoredWhenSaveIsPressedNotWhenTheFormOpened() {
        val read = stored {
            name = "Read"
            description = "Ten pages before bed"
        }
        val form = readForm // what the form holds, typed while sync brings in new notes
        list.getById(read.id!!)!!.description = "Twenty pages, written on the web meanwhile"
        save(read, form)
        assertEquals("Twenty pages, written on the web meanwhile", list.getById(read.id!!)!!.description)
    }

    @Test
    fun aNewHabitHasNoNotes() {
        assertEquals("", readForm.habit(factory, null).description)
    }
}
