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

package org.isoron.uhabits.xrnsync

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateUtils
import android.widget.EditText
import android.widget.FrameLayout
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.isoron.uhabits.R
import kotlin.concurrent.thread

/**
 * Wires the "Sync with home server" category in Loop's settings screen to [XrnSyncSettings].
 * Loop is on androidx.preference 1.0, which has no summary providers or input types, so the
 * summaries are set by hand and the password gets its own dialog.
 */
object XrnSyncPreferences {

    fun attach(fragment: PreferenceFragmentCompat) {
        val sync = XrnSync.instance ?: return
        val settings = sync.settings
        for (key in listOf(XrnSyncSettings.KEY_URL, XrnSyncSettings.KEY_USER)) {
            val pref = fragment.findPreference(key) as? EditTextPreference ?: continue
            // Inflation already read Loop's own file; point at ours, then show what's really stored.
            pref.preferenceDataStore = settings.dataStore
            pref.text = settings.dataStore.getString(key, "")
            pref.summary = pref.text
            pref.setOnPreferenceChangeListener { p, value ->
                p.summary = value as? String
                changed(fragment, sync)
                true
            }
        }
        fragment.findPreference(XrnSyncSettings.KEY_PASSWORD)?.setOnPreferenceClickListener { pref ->
            val input = EditText(pref.context).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText(settings.password)
            }
            val pad = (20 * pref.context.resources.displayMetrics.density).toInt()
            val frame = FrameLayout(pref.context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            AlertDialog.Builder(pref.context)
                .setTitle(R.string.xrnsync_password)
                .setView(frame)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    settings.dataStore.putString(XrnSyncSettings.KEY_PASSWORD, input.text.toString())
                    changed(fragment, sync)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        fragment.findPreference(XrnSyncSettings.KEY_SYNC_NOW)?.setOnPreferenceClickListener { pref ->
            if (!settings.isConfigured) return@setOnPreferenceClickListener true
            pref.summary = pref.context.getString(R.string.xrnsync_running)
            pref.isEnabled = false
            thread(name = "xrnsync-now") {
                sync.syncBlocking(60_000)
                Handler(Looper.getMainLooper()).post {
                    pref.isEnabled = true
                    refresh(fragment)
                }
            }
            true
        }
        refresh(fragment)
    }

    private fun changed(fragment: PreferenceFragmentCompat, sync: XrnSync) {
        sync.requestSync(500)
        refresh(fragment)
    }

    private fun refresh(fragment: PreferenceFragmentCompat) {
        val settings = XrnSync.instance?.settings ?: return
        val password = fragment.findPreference(XrnSyncSettings.KEY_PASSWORD)
        password?.setSummary(if (settings.password.isEmpty()) R.string.xrnsync_password_unset else R.string.xrnsync_password_set)
        val pref = fragment.findPreference(XrnSyncSettings.KEY_SYNC_NOW) ?: return
        val context = pref.context
        val last = settings.lastSync
        pref.summary = when {
            !settings.isConfigured -> context.getString(R.string.xrnsync_not_configured)
            last == null -> context.getString(R.string.xrnsync_never)
            else -> context.getString(
                R.string.xrnsync_last,
                DateUtils.getRelativeTimeSpanString(last.atMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                last.message
            )
        }
    }
}
