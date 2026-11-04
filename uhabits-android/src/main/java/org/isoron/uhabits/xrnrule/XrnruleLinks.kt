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

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The other direction of the xrnrule link: which of our habits unlock which xrnrule block, and
 * whether that block is locked, ready or open. xrnrule keeps an identical copy of these
 * constants (`com.xern.xrnrule.app.loop.LinksContract`, `../xrnrule/app/.../loop/LoopLinks.kt`);
 * change both together.
 *
 * - Read: `content://com.xern.xrnrule.links/links`, one row per (habit, block) for every enabled
 *   habit block, guarded by [XrnruleContract.PERMISSION] like the rest of the link.
 * - Push: [ACTION_LINKS_CHANGED], sent to us when a row would change (a block opens, closes,
 *   gets a new habit). It carries no data; we read the provider again.
 */
object XrnruleLinksContract {
    const val AUTHORITY = "com.xern.xrnrule.links"
    const val PATH_LINKS = "links"
    const val ACTION_LINKS_CHANGED = "com.xern.xrnloops.action.LINKS_CHANGED"

    /** The habit's id: Loop's `uuid`, which xrnrule's habit list shares through the habit server. */
    const val COLUMN_HABIT_UUID = "habit_uuid"
    const val COLUMN_BLOCK_ID = "block_id"
    const val COLUMN_BLOCK_NAME = "block_name"

    /** What the block holds back, for a person: "Instagram + youtube.com", "Reddit + 2 more". */
    const val COLUMN_TARGETS = "targets"

    /** How long one unlock lasts. */
    const val COLUMN_UNLOCK_MINUTES = "unlock_minutes"

    /** [STATE_LOCKED], [STATE_READY] (earned, waiting to be opened) or [STATE_OPEN]. */
    const val COLUMN_STATE = "state"

    /** Wall-clock ms the open unlock ends; 0 unless [STATE_OPEN], and 0 for a self-paced one. */
    const val COLUMN_OPEN_UNTIL = "open_until"

    /** 1 when an unlock is so much use, not so much time: it ends when used up or at midnight. */
    const val COLUMN_SELF_PACED = "self_paced"

    /** Ms of use left in an open self-paced unlock; 0 otherwise. */
    const val COLUMN_USE_LEFT_MS = "use_left_ms"

    /** 1 when this habit's part of the block is done; the block may still need other parts. */
    const val COLUMN_HABIT_DONE = "habit_done"

    const val STATE_LOCKED = "locked"
    const val STATE_READY = "ready"
    const val STATE_OPEN = "open"
}

data class XrnruleLink(
    val habitUuid: String,
    val blockId: String,
    val blockName: String,
    val targets: String,
    val unlockMinutes: Int,
    val state: String,
    val openUntil: Long,
    val habitDone: Boolean,
    val selfPaced: Boolean,
    val useLeftMs: Long
)

/**
 * Keeps xrnrule's links in memory for the habit list. Read again when one of our screens comes
 * to the front and when xrnrule says something changed; listeners run on the main thread, and
 * only when the links really changed. With xrnrule missing, or a build without the provider,
 * there are simply no links.
 */
class XrnruleLinks(private val context: Context) {

    @Volatile
    private var byHabit: Map<String, List<XrnruleLink>> = emptyMap()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "xrnrule-links") }
    private val main = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    fun start(app: Application) {
        instance = this
        // Exported, but only xrnrule (same key, [XrnruleContract.PERMISSION]) can send it.
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(XrnruleLinksContract.ACTION_LINKS_CHANGED),
            XrnruleContract.PERMISSION,
            null,
            ContextCompat.RECEIVER_EXPORTED
        )
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = refresh()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        refresh()
    }

    /** The blocks [habitUuid] is part of; empty when none, or when xrnrule can't be read. */
    fun forHabit(habitUuid: String?): List<XrnruleLink> = habitUuid?.let { byHabit[it] }.orEmpty()

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    fun refresh() {
        executor.execute {
            val fresh = read()
            if (fresh == byHabit) return@execute
            byHabit = fresh
            main.post { listeners.forEach { it() } }
        }
    }

    private fun read(): Map<String, List<XrnruleLink>> {
        val uri = Uri.Builder().scheme("content").authority(XrnruleLinksContract.AUTHORITY)
            .appendPath(XrnruleLinksContract.PATH_LINKS).build()
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { links(it) }.orEmpty()
        } catch (e: Exception) {
            // Not installed (null above), a build signed with another key (SecurityException), or
            // xrnrule failing: no links is the safe answer.
            Log.w(TAG, "can't read xrnrule's links", e)
            emptyMap()
        }
    }

    private fun links(c: Cursor): Map<String, List<XrnruleLink>> {
        fun string(name: String) = c.getColumnIndex(name).let { if (it < 0) "" else c.getString(it).orEmpty() }
        fun long(name: String) = c.getColumnIndex(name).let { if (it < 0) 0L else c.getLong(it) }
        val links = mutableListOf<XrnruleLink>()
        while (c.moveToNext()) {
            val uuid = string(XrnruleLinksContract.COLUMN_HABIT_UUID)
            if (uuid.isEmpty()) continue
            links += XrnruleLink(
                habitUuid = uuid,
                blockId = string(XrnruleLinksContract.COLUMN_BLOCK_ID),
                blockName = string(XrnruleLinksContract.COLUMN_BLOCK_NAME),
                targets = string(XrnruleLinksContract.COLUMN_TARGETS),
                unlockMinutes = long(XrnruleLinksContract.COLUMN_UNLOCK_MINUTES).toInt(),
                state = string(XrnruleLinksContract.COLUMN_STATE),
                openUntil = long(XrnruleLinksContract.COLUMN_OPEN_UNTIL),
                habitDone = long(XrnruleLinksContract.COLUMN_HABIT_DONE) != 0L,
                selfPaced = long(XrnruleLinksContract.COLUMN_SELF_PACED) != 0L,
                useLeftMs = long(XrnruleLinksContract.COLUMN_USE_LEFT_MS)
            )
        }
        return links.groupBy { it.habitUuid }
    }

    companion object {
        private const val TAG = "XrnruleLinks"

        @Volatile
        var instance: XrnruleLinks? = null
            private set
    }
}
