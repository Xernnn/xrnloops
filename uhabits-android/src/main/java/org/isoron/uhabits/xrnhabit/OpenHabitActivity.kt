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

import android.app.Activity
import android.os.Bundle
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.intents.IntentFactory

/**
 * Opens one habit's page for xrnhabit, found by its uuid (the id xrnhabit links to). Exported,
 * but guarded by the signature-level [XrnhabitContract.PERMISSION] in the manifest. Falls back
 * to the habit list when the habit is gone.
 */
class OpenHabitActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as HabitsApplication
        val habit = intent.getStringExtra(XrnhabitContract.EXTRA_HABIT_UUID)
            ?.let { app.component.habitList.getByUUID(it) }
        val target = if (habit != null) {
            IntentFactory().startShowHabitActivity(this, habit)
        } else {
            packageManager.getLaunchIntentForPackage(packageName)
        }
        target?.let(::startActivity)
        finish()
    }
}
