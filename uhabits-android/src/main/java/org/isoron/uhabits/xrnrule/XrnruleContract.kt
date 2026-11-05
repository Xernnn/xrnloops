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

/**
 * The interface between this Loop build and xrnrule, a separate app that blocks other apps
 * until a habit is done. xrnrule keeps a copy of these constants
 * (`com.xern.xrnrule.app.loop.LoopContract`); change both together.
 *
 * - Push: [ACTION_HABITS_CHANGED], sent to [XRNRULE_PACKAGE] after every command
 *   ([XrnruleNotifier]). It carries no data.
 * - Ask: [METHOD_SYNC] has this app sync with the home server now ([XrnruleProvider]). xrnrule
 *   then reads the habits from that server, never from here.
 *
 * Both directions are guarded by [PERMISSION], a `signature` permission that both apps declare,
 * so only apps signed with the same key can ask for a sync or send the change signal.
 */
object XrnruleContract {
    const val XRNRULE_PACKAGE = "com.xern.xrnrule"
    const val PERMISSION = "com.xern.xrnrule.permission.HABITS"
    const val ACTION_HABITS_CHANGED = "com.xern.xrnrule.action.HABITS_CHANGED"

    /**
     * `ContentResolver.call(authority, METHOD_SYNC, null, null)`: syncs with the home server (a
     * few seconds at most). The result has [KEY_CONFIGURED] (sync is set up here) and [KEY_SYNCED]
     * (it worked).
     */
    const val METHOD_SYNC = "sync"
    const val KEY_CONFIGURED = "configured"
    const val KEY_SYNCED = "synced"
}
