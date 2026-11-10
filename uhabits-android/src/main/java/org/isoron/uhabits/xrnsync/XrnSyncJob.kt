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

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * A sync about every half hour in the background, whenever there's a network, so the list, the
 * widgets and the reminders here catch up with ticks made in the web app while nobody opens
 * this app. Android may stretch the period, in Doze especially; reminders sync on their own
 * ([XrnSync.beforeReminder]).
 *
 * The same job, once ([schedulePush]), sends a change made here even when Android ends the
 * process before the in-app sync gets to it: leaving the app right after a tick is enough on
 * some phones. If the in-app sync did send it, this round just finds nothing new.
 */
class XrnSyncJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        // The process was started for this job, so HabitsApplication.onCreate has run.
        val sync = XrnSync.instance ?: return false
        if (!sync.settings.isConfigured) return false
        Thread({
            val ok = sync.syncBlocking(TIMEOUT_MS)
            jobFinished(params, !ok)
        }, "xrnsync-job").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 0x78726e // "xrn"; nothing else in this app uses JobScheduler
        private const val PUSH_JOB_ID = JOB_ID + 1
        private const val PERIOD_MS = 30 * 60 * 1000L
        private const val TIMEOUT_MS = 60_000L

        /** After the in-app sync's own wait, so that one usually gets there first. */
        private const val PUSH_DELAY_MS = 5_000L

        /**
         * Schedules the job unless it already is. Scheduling again would restart its period, and
         * this runs at every start of the app. It runs inside `Application.onCreate`, so a
         * refusal (a missing permission, a vendor's limits) is logged, never thrown: the app
         * must start without background sync rather than not at all.
         */
        fun schedule(context: Context) {
            try {
                val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
                if (scheduler.getPendingJob(JOB_ID) != null) return
                val job = JobInfo.Builder(JOB_ID, ComponentName(context, XrnSyncJob::class.java))
                    .setPeriodic(PERIOD_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
                scheduler.schedule(job)
            } catch (e: RuntimeException) {
                Log.w("XrnSync", "background sync not scheduled", e)
            }
        }

        /**
         * One sync a few seconds from now, with a network, that outlives this process. Called
         * after every change here; scheduling again replaces the waiting one, so a burst of ticks
         * is one round. A failed round is retried with the job's backoff.
         */
        fun schedulePush(context: Context) {
            try {
                val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
                val job = JobInfo.Builder(PUSH_JOB_ID, ComponentName(context, XrnSyncJob::class.java))
                    .setMinimumLatency(PUSH_DELAY_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
                scheduler.schedule(job)
            } catch (e: RuntimeException) {
                Log.w("XrnSync", "upload job not scheduled", e)
            }
        }
    }
}
