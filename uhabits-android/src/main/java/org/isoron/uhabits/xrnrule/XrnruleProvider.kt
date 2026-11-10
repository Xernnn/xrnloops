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

package org.isoron.uhabits.xrnrule

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.xrnsync.XrnSync

/**
 * Lets xrnrule ask for a sync ([XrnruleContract.METHOD_SYNC]). It serves no data: xrnrule reads
 * habits from the home server. It's a provider because Android starts this app to answer one,
 * which a broadcast to a stopped app wouldn't do. Exported, but guarded by the signature-level
 * [XrnruleContract.PERMISSION].
 */
class XrnruleProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /** Android doesn't apply the provider's read permission to `call`, so it's checked here. */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != XrnruleContract.METHOD_SYNC) return null
        val context = context ?: return null
        if (context.checkCallingPermission(XrnruleContract.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("needs ${XrnruleContract.PERMISSION}")
        }
        // Providers are published before Application.onCreate finishes.
        val sync = if (HabitsApplication.isComponentReady()) XrnSync.instance else null
        val configured = sync?.settings?.isConfigured == true
        return Bundle().apply {
            putBoolean(XrnruleContract.KEY_CONFIGURED, configured)
            putBoolean(XrnruleContract.KEY_SYNCED, configured && sync!!.syncBlocking(SYNC_TIMEOUT_MS))
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("read-only")

    private companion object {
        const val SYNC_TIMEOUT_MS = 5_000L
    }
}
