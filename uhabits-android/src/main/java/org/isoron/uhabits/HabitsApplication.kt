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

package org.isoron.uhabits

import android.app.Application
import android.content.Context
import org.isoron.platform.time.computeToday
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.database.UnsupportedDatabaseVersionException
import org.isoron.uhabits.core.reminders.ReminderScheduler
import org.isoron.uhabits.core.ui.NotificationTray
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.isoron.uhabits.inject.create
import org.isoron.uhabits.utils.DatabaseUtils
import org.isoron.uhabits.widgets.WidgetUpdater
import org.isoron.uhabits.xrnrule.TodayRefresher
import org.isoron.uhabits.xrnrule.XrnruleLinks
import org.isoron.uhabits.xrnrule.XrnruleNotifier
import org.isoron.uhabits.xrnsync.XrnSync
import java.io.File

/**
 * The Android application for Loop Habit Tracker.
 */
class HabitsApplication : Application() {

    private lateinit var context: Context
    private lateinit var widgetUpdater: WidgetUpdater
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var notificationTray: NotificationTray
    private lateinit var xrnruleNotifier: XrnruleNotifier

    override fun onCreate() {
        super.onCreate()
        context = this

        if (isTestMode()) {
            val db = DatabaseUtils.getDatabaseFile(context)
            if (db.exists()) db.delete()
        }

        try {
            DatabaseUtils.initializeDatabase(context)
        } catch (e: UnsupportedDatabaseVersionException) {
            val db = DatabaseUtils.getDatabaseFile(context)
            db.renameTo(File(db.absolutePath + ".invalid"))
            DatabaseUtils.initializeDatabase(context)
        }

        val db = DatabaseUtils.getDatabaseFile(this)
        HabitsApplication.component = HabitsApplicationComponent::class.create(
            appContext = context,
            dbFile = db
        )

        val prefs = component.preferences
        prefs.lastAppVersion = BuildConfig.VERSION_CODE

        setToday(computeToday(component.preferences.midnightDelayHours, 0))

        val habitList = component.habitList
        for (h in habitList) h.recompute()

        widgetUpdater = component.widgetUpdater.apply {
            startListening()
            scheduleStartDayWidgetUpdate()
        }

        reminderScheduler = component.reminderScheduler
        reminderScheduler.startListening()

        notificationTray = component.notificationTray
        notificationTray.startListening()

        xrnruleNotifier = XrnruleNotifier(context, component.commandRunner)
        XrnruleLinks(context).start(this)
        xrnruleNotifier.startListening()
        registerActivityLifecycleCallbacks(TodayRefresher(component.midnightTimer))
        XrnSync(context, habitList, component.modelFactory, component.commandRunner, component.preferences).start(this)
        ready = true

        val taskRunner = component.taskRunner
        taskRunner.execute {
            reminderScheduler.scheduleAll()
            widgetUpdater.updateWidgets()
        }
    }

    override fun onTerminate() {
        reminderScheduler.stopListening()
        widgetUpdater.stopListening()
        notificationTray.stopListening()
        xrnruleNotifier.stopListening()
        super.onTerminate()
    }

    val component: HabitsApplicationComponent
        get() = HabitsApplication.component

    companion object {
        lateinit var component: HabitsApplicationComponent

        @Volatile
        private var ready = false

        /**
         * False until [onCreate] has finished: content providers are called earlier, and
         * [component] is set before every habit has been loaded.
         */
        fun isComponentReady(): Boolean = ready

        fun isTestMode(): Boolean {
            return try {
                Class.forName("org.isoron.uhabits.BaseAndroidTest")
                true
            } catch (e: ClassNotFoundException) {
                false
            }
        }
    }
}
