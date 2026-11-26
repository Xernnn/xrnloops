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

package org.isoron.uhabits.xrnsync

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateUtils
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
                // Afterwards: this runs before the new value is saved, and `changed` reads it.
                fragment.view?.post { if (fragment.isAdded) changed(fragment, sync) }
                true
            }
        }
        fragment.findPreference(XrnSyncSettings.KEY_PASSWORD)?.setOnPreferenceClickListener { pref ->
            // The app's Material dialog, not android.app.AlertDialog: on HyperOS the framework one
            // came out as a narrow white box whose field was invisible and never took focus. The
            // eye button lets you check what you typed before saving.
            val input = TextInputEditText(pref.context).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText(settings.password)
            }
            val field = TextInputLayout(pref.context).apply {
                endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            val pad = (20 * pref.context.resources.displayMetrics.density).toInt()
            val frame = FrameLayout(pref.context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(field, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            MaterialAlertDialogBuilder(pref.context)
                .setTitle(R.string.xrnsync_password)
                .setView(frame)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    settings.dataStore.putString(XrnSyncSettings.KEY_PASSWORD, input.text.toString())
                    changed(fragment, sync)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
                .window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            input.requestFocus()
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
        fragment.findPreference(KEY_AUTOSTART)?.setOnPreferenceClickListener { pref ->
            XrnBackground.openAutostart(pref.context)
            true
        }
        fragment.findPreference(KEY_BATTERY)?.setOnPreferenceClickListener { pref ->
            XrnBackground.askBattery(pref.context)
            true
        }
        refresh(fragment)
    }

    /** Back on the settings screen (from the Autostart or Battery page): show what they say now. */
    fun resumed(fragment: PreferenceFragmentCompat) = refresh(fragment)

    private const val KEY_AUTOSTART = "xrnsync_autostart"
    private const val KEY_BATTERY = "xrnsync_battery"

    private fun changed(fragment: PreferenceFragmentCompat, sync: XrnSync) {
        sync.requestSync(500)
        if (sync.settings.isConfigured) XrnSyncJob.schedule(fragment.requireContext())
        refresh(fragment)
    }

    private fun refresh(fragment: PreferenceFragmentCompat) {
        fragment.findPreference(KEY_AUTOSTART)?.let { pref ->
            // Only Xiaomi's can be read; elsewhere the row isn't shown.
            val on = XrnBackground.autostart(pref.context)
            pref.isVisible = on != null
            pref.setSummary(if (on == true) R.string.xrnsync_on else R.string.xrnsync_off)
        }
        fragment.findPreference(KEY_BATTERY)?.let { pref ->
            pref.setSummary(if (XrnBackground.batteryExempt(pref.context)) R.string.xrnsync_unrestricted else R.string.xrnsync_restricted)
        }
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
