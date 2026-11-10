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

package org.isoron.uhabits.xrntap

import android.content.Context
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import org.isoron.uhabits.core.models.Entry.Companion.NO
import org.isoron.uhabits.core.models.Entry.Companion.UNKNOWN
import org.isoron.uhabits.core.models.Entry.Companion.YES_MANUAL

/**
 * What a tap on a day in the habit list does: Settings → "Tap on a day", in place of Loop's
 * "Toggle with short press" switch. A short hold opens the flick menu ([FlickGesture]) whatever
 * this says.
 *
 * Until it is picked, it follows Loop's switch, so nothing changes on its own. Picking it writes
 * that switch too, which the habit page's calendar still reads: a tap there toggles unless this is
 * [Action.MENU].
 */
object XrnTap {
    const val KEY = "pref_xrn_tap"
    private const val LOOP_SHORT_TOGGLE = "pref_short_toggle"

    enum class Action(val key: String) {
        /** ✓, and a tap on a ✓ empties the day again. */
        TICK("tick"),

        /** Loop's toggle: ✓ → (skip) → ✗ → (?) → ✓. */
        CYCLE("cycle"),

        /** The day's sheet ([org.isoron.uhabits.xrnnote.NoteSheet]): every value, and the note. */
        MENU("menu")
    }

    fun action(context: Context): Action {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = prefs.getString(KEY, null)
        return Action.values().firstOrNull { it.key == stored }
            ?: if (prefs.getBoolean(LOOP_SHORT_TOGGLE, false)) Action.CYCLE else Action.MENU
    }

    /** [Action.TICK]: ✓, or back to an empty day from a ✓ (a ? with question marks on). */
    fun tickValue(value: Int, areQuestionMarksEnabled: Boolean): Int = when (value) {
        YES_MANUAL -> if (areQuestionMarksEnabled) UNKNOWN else NO
        else -> YES_MANUAL
    }

    /** From Loop's SettingsFragment: shows the current choice and keeps Loop's switch in step. */
    fun attach(fragment: PreferenceFragmentCompat) {
        val pref = fragment.findPreference(KEY) as? ListPreference ?: return
        val context = fragment.requireContext()
        pref.value = action(context).key
        pref.setOnPreferenceChangeListener { _, value ->
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(LOOP_SHORT_TOGGLE, value != Action.MENU.key)
                .apply()
            true
        }
    }
}
