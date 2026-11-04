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

import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateUtils
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.material.snackbar.Snackbar
import org.isoron.uhabits.R
import org.isoron.uhabits.xrnlook.XrnSnackbar

/**
 * The cloud in the habit list's toolbar, there only while sync is failing
 * ([XrnSyncSettings.isFailing]). A tap says what went wrong and when, with Retry, or Settings
 * when it's the password. It watches the sync settings, so it comes and goes while the list
 * is open.
 */
class XrnSyncTrouble(private val activity: AppCompatActivity) {
    private val settings = XrnSyncSettings(activity)
    private val prefs = activity.getSharedPreferences(XrnSyncSettings.FILE, Context.MODE_PRIVATE)
    private var shown: Boolean? = null

    // A field: SharedPreferences holds its listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    init {
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        prefs.registerOnSharedPreferenceChangeListener(listener)
                        refresh()
                    }
                    Lifecycle.Event.ON_STOP -> prefs.unregisterOnSharedPreferenceChangeListener(listener)
                    else -> Unit
                }
            }
        )
    }

    /** From the menu's set-up; [allowed] is false while something else (search) owns the bar. */
    fun bind(item: MenuItem?, allowed: Boolean) {
        val failing = settings.isFailing
        shown = failing
        item ?: return
        item.isVisible = failing && allowed
        item.icon?.mutate()?.setTint(0xFFF59E0B.toInt()) // amber, in every theme
    }

    fun explain(openSettings: () -> Unit) {
        val last = settings.lastSync ?: return
        val now = System.currentTimeMillis()
        val ago = if (now - last.atMs < DateUtils.MINUTE_IN_MILLIS) {
            activity.getString(R.string.xrnsync_trouble_just_now)
        } else {
            DateUtils.getRelativeTimeSpanString(last.atMs, now, DateUtils.MINUTE_IN_MILLIS)
        }
        val text = activity.getString(R.string.xrnsync_trouble_message, ago, last.message)
        val bar = XrnSnackbar.make(activity, text, Snackbar.LENGTH_LONG, R.drawable.xrn_ic_cloud_off) ?: return
        if (last.needsYou) {
            bar.setAction(R.string.xrnsync_trouble_settings) { openSettings() }
        } else {
            bar.setAction(R.string.xrnsync_trouble_retry) { XrnSync.instance?.requestSync(0) }
        }
        bar.show()
    }

    private fun refresh() {
        if (settings.isFailing != shown) activity.invalidateOptionsMenu()
    }
}
