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
import androidx.core.content.edit
import androidx.preference.PreferenceDataStore

/**
 * Where xrnloops-sync lives and how to log in, plus how the last attempt went. Kept in its own
 * preferences file rather than Loop's, which [org.isoron.uhabits.HabitsBackupAgent] copies to
 * the cloud backup: the password must stay on the phone.
 */
class XrnSyncSettings(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val url: String get() = prefs.getString(KEY_URL, "").orEmpty().trim().trimEnd('/')
    val user: String get() = prefs.getString(KEY_USER, "").orEmpty().trim()
    val password: String get() = prefs.getString(KEY_PASSWORD, "").orEmpty()

    val isConfigured: Boolean get() = url.startsWith("http") && user.isNotEmpty() && password.isNotEmpty()

    /** The settings screen edits our keys through this, so they land in [FILE]. */
    val dataStore = object : PreferenceDataStore() {
        override fun putString(key: String, value: String?) {
            prefs.edit { putString(key, value) }
        }

        override fun getString(key: String, defValue: String?): String? = prefs.getString(key, defValue)
    }

    data class LastSync(val atMs: Long, val ok: Boolean, val message: String)

    val lastSync: LastSync?
        get() {
            val at = prefs.getLong(KEY_LAST_AT, 0)
            if (at == 0L) return null
            return LastSync(at, prefs.getBoolean(KEY_LAST_OK, false), prefs.getString(KEY_LAST_MESSAGE, "").orEmpty())
        }

    fun recordAttempt(ok: Boolean, message: String) {
        prefs.edit {
            putLong(KEY_LAST_AT, System.currentTimeMillis())
            putBoolean(KEY_LAST_OK, ok)
            putString(KEY_LAST_MESSAGE, message)
        }
    }

    companion object {
        const val FILE = "xrnsync"
        const val KEY_URL = "xrnsync_url"
        const val KEY_USER = "xrnsync_user"
        const val KEY_PASSWORD = "xrnsync_password"
        const val KEY_SYNC_NOW = "xrnsync_now"
        private const val KEY_LAST_AT = "last_at"
        private const val KEY_LAST_OK = "last_ok"
        private const val KEY_LAST_MESSAGE = "last_message"
    }
}
