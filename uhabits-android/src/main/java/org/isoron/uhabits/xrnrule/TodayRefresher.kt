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
import android.os.Bundle
import org.isoron.uhabits.core.utils.MidnightTimer

/**
 * Moves Loop's "today" forward whenever one of its screens comes back. Upstream only recomputes
 * it at process start and at midnight while the habit list is on screen, so a process that
 * outlives midnight in the background (xrnrule's reads keep it warm) shows yesterday as the
 * first column, and a tick there lands on yesterday's date.
 */
class TodayRefresher(private val midnightTimer: MidnightTimer) : Application.ActivityLifecycleCallbacks {

    override fun onActivityResumed(activity: Activity) {
        midnightTimer.refreshIfDayChanged()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
