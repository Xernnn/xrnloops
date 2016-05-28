/*
 * Copyright (C) 2016 Álinson Santos Xavier <isoron@gmail.com>
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

package org.isoron.uhabits.ui.habits.edit;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.isoron.uhabits.R;
import org.isoron.uhabits.commands.Command;
import org.isoron.uhabits.commands.CommandRunner;
import org.isoron.uhabits.commands.EditHabitCommand;
import org.isoron.uhabits.models.Habit;

public class EditHabitDialogFragment extends BaseDialogFragment
{
    public static EditHabitDialogFragment newInstance(long habitId)
    {
        EditHabitDialogFragment frag = new EditHabitDialogFragment();
        Bundle args = new Bundle();
        args.putLong("habitId", habitId);
        frag.setArguments(args);
        return frag;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState)
    {
        View view = super.onCreateView(inflater, container, savedInstanceState);
        getDialog().setTitle(R.string.edit_habit);
        initializeHabits();
        restoreSavedInstance(savedInstanceState);
        helper.populateForm(modifiedHabit);
        return view;
    }

    private void initializeHabits()
    {
        Long habitId = (Long) getArguments().get("habitId");
        if(habitId == null) throw new IllegalArgumentException("habitId must be specified");

        originalHabit = Habit.get(habitId);
        modifiedHabit = new Habit(originalHabit);
    }

    protected void saveHabit()
    {
        Command command = new EditHabitCommand(originalHabit, modifiedHabit);
        CommandRunner.getInstance().execute(command, originalHabit.getId());
    }
}
