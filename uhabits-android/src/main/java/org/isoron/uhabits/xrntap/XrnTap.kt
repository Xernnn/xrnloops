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
 * Both choices act on the day, so Loop's switch, which the habit page's calendar still reads, is
 * kept on: a tap there toggles, and a long press opens the day's sheet. A third choice opened the
 * day's note until 2026-11-15; a phone that had it, or that never picked one, gets the default
 * ([resolve]) the first time this is read, at the app's start, and it's saved as picked.
 */
object XrnTap {
    const val KEY = "pref_xrn_tap"
    private const val LOOP_SHORT_TOGGLE = "pref_short_toggle"

    enum class Action(val key: String) {
        /** ✓, and a tap on a ✓ empties the day again. */
        TICK("tick"),

        /** Loop's toggle: ✓ → (skip) → ✗ → (?) → ✓. */
        CYCLE("cycle")
    }

    /**
     * The choice [stored] names. Anything else, none yet or "menu" (the day's note, gone since
     * 2026-11-15), follows Loop's switch: on, its toggle; off, as "menu" left it, a tick.
     */
    fun resolve(stored: String?, loopShortToggle: Boolean): Action =
        Action.values().firstOrNull { it.key == stored } ?: if (loopShortToggle) Action.CYCLE else Action.TICK

    fun action(context: Context): Action {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = prefs.getString(KEY, null)
        val loopSwitch = prefs.getBoolean(LOOP_SHORT_TOGGLE, false)
        val action = resolve(stored, loopSwitch)
        if (stored != action.key || !loopSwitch) {
            prefs.edit().putString(KEY, action.key).putBoolean(LOOP_SHORT_TOGGLE, true).apply()
        }
        return action
    }

    /** [Action.TICK]: ✓, or back to an empty day from a ✓ (a ? with question marks on). */
    fun tickValue(value: Int, areQuestionMarksEnabled: Boolean): Int = when (value) {
        YES_MANUAL -> if (areQuestionMarksEnabled) UNKNOWN else NO
        else -> YES_MANUAL
    }

    /** From Loop's SettingsFragment: shows the current choice. */
    fun attach(fragment: PreferenceFragmentCompat) {
        val pref = fragment.findPreference(KEY) as? ListPreference ?: return
        pref.value = action(fragment.requireContext()).key
    }
}
