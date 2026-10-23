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
 * until a habit is done. xrnrule keeps an identical copy of these constants
 * (`com.xern.xrnrule.app.loop.LoopContract`); change both together.
 *
 * - Read: `content://<applicationId>.provider/habits?date=YYYY-MM-DD` ([XrnruleProvider]); the
 *   cursor's extras carry [KEY_DAY_START_HOUR].
 * - Wait: [METHOD_WATCH] answers when the home server has something new (a tick in the web app).
 * - Push: [ACTION_HABITS_CHANGED], sent to [XRNRULE_PACKAGE] after every command
 *   ([XrnruleNotifier]). It carries no data; xrnrule re-reads the provider.
 * - Open: [ACTION_OPEN_HABIT] with [EXTRA_HABIT_UUID] shows that habit's page
 *   ([OpenHabitActivity]), where today or yesterday can be ticked.
 *
 * Both directions are guarded by [PERMISSION], a `signature` permission that both apps declare,
 * so only apps signed with the same key can read habits or send the change signal.
 */
object XrnruleContract {
    const val XRNRULE_PACKAGE = "com.xern.xrnrule"
    const val PERMISSION = "com.xern.xrnrule.permission.HABITS"
    const val ACTION_HABITS_CHANGED = "com.xern.xrnrule.action.HABITS_CHANGED"
    const val ACTION_OPEN_HABIT = "com.xern.xrnrule.action.OPEN_HABIT"
    const val EXTRA_HABIT_UUID = "uuid"

    const val PATH_HABITS = "habits"
    const val PARAM_DATE = "date"

    /**
     * In the cursor's extras: the hour this app's day starts, 3 with "Extend the day past
     * midnight" on, else 0. Until then a tick lands on the day before, so xrnrule asks for that
     * date. It works the date out with its own clock, which ignores a hand-set phone clock.
     */
    const val KEY_DAY_START_HOUR = "day_start_hour"

    const val COLUMN_UUID = "uuid"
    const val COLUMN_NAME = "name"
    const val COLUMN_POSITION = "position"
    const val COLUMN_ARCHIVED = "archived"
    const val COLUMN_NUMERICAL = "numerical"

    /** 0 for an "at most" numerical habit, which Loop never counts as completed. */
    const val COLUMN_COUNTABLE = "countable"

    /** 1 when the habit is done on the requested date (see [XrnruleProvider.isDone]). */
    const val COLUMN_DONE = "done"

    /** 1 when it's done, or the day is covered by a met goal ("3 times a week"); see [XrnruleProvider.isGoalMet]. */
    const val COLUMN_GOAL_MET = "goal_met"

    /** 1 for an every-day habit (or a numeric one), where [COLUMN_GOAL_MET] equals [COLUMN_DONE]. */
    const val COLUMN_DAILY = "daily"

    /**
     * `ContentResolver.call(authority, METHOD_SYNC, null, null)`: syncs with the home server
     * first (a few seconds at most), so ticks made in the web app are in the next query. The
     * result has [KEY_CONFIGURED] (sync is set up here) and [KEY_SYNCED] (it worked).
     */
    const val METHOD_SYNC = "sync"
    const val KEY_CONFIGURED = "configured"
    const val KEY_SYNCED = "synced"

    /**
     * `ContentResolver.call(authority, METHOD_WATCH, null, null)`: waits up to ~25 s for the home
     * server to have something this phone hasn't pulled (a tick in the web app), syncs it in and
     * answers; [ACTION_HABITS_CHANGED] has gone out by then too. The result has [KEY_CONFIGURED]
     * and [KEY_CHANGED]. Without sync set up it answers at once. Blocking: call off the main thread.
     */
    const val METHOD_WATCH = "watch"
    const val KEY_CHANGED = "changed"
}
