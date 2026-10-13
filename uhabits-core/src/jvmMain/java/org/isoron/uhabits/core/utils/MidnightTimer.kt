/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
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
package org.isoron.uhabits.core.utils

import me.tatarka.inject.annotations.Inject
import org.isoron.platform.time.DateUtils
import org.isoron.platform.time.computeToday
import org.isoron.platform.time.getToday
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.AppScope
import org.isoron.uhabits.core.io.Logging
import org.isoron.uhabits.core.preferences.Preferences
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * A class that emits events when a new day starts.
 */
@AppScope
@Inject
open class MidnightTimer(
    logging: Logging,
    private val preferences: Preferences
) {
    private val listeners = mutableListOf<MidnightListener>()
    private lateinit var executor: ScheduledExecutorService
    private val logger = logging.getLogger("MidnightTimer")

    @Synchronized
    open fun addListener(listener: MidnightListener) {
        this.listeners.add(listener)
    }

    @Synchronized
    open fun onPause(): MutableList<Runnable>? {
        logger.info("Pausing timer")
        return executor.shutdownNow()
    }

    @Synchronized
    open fun onResume(
        delayOffsetInMillis: Long = DateUtils.SECOND_LENGTH,
        testExecutor: ScheduledExecutorService? = null
    ) {
        executor = testExecutor ?: Executors.newSingleThreadScheduledExecutor()
        val initialDelay = DateUtils.millisecondsUntilTomorrowWithOffset(preferences.midnightDelayHours, 0) + delayOffsetInMillis
        logger.info("Scheduling refresh for $initialDelay ms from now")
        executor.scheduleAtFixedRate(
            { notifyListeners() },
            initialDelay,
            DateUtils.DAY_LENGTH,
            TimeUnit.MILLISECONDS
        )
    }

    @Synchronized
    open fun removeListener(listener: MidnightListener) = this.listeners.remove(listener)

    /**
     * xrnloops: catches up on a new day that started while no timer was running. The timer only
     * runs while the habit list is on screen, so a process that stays alive in the background
     * past midnight otherwise keeps showing (and checking) yesterday as today.
     */
    @Synchronized
    open fun refreshIfDayChanged() {
        if (computeToday(preferences.midnightDelayHours, 0) != getToday()) notifyListeners()
    }

    @Synchronized
    private fun notifyListeners() {
        logger.info("Midnight refresh")
        setToday(computeToday(preferences.midnightDelayHours, 0))
        for (l in listeners) {
            l.atMidnight()
        }
    }

    fun interface MidnightListener {
        fun atMidnight()
    }
}
