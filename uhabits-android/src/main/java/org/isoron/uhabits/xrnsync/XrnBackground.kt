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

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/**
 * What lets sync and reminders run in the background on the owner's phone, as xrnrule's
 * OemAutostart and xrnimg's Battery row do it. HyperOS turns Autostart off at every install, and
 * with it off the boot receiver, the sync job and xrnrule's sync requests are refused without a
 * word. Android's battery optimisation files a seldom-opened app as rare and runs its jobs about
 * once a day.
 */
object XrnBackground {

    /**
     * Whether Autostart is on, or null where the phone won't say. Only Xiaomi's can be read: MIUI
     * and HyperOS keep it as an app op of their own (10008), which Android's API has no name for.
     */
    fun autostart(context: Context): Boolean? {
        if ("xiaomi" !in Build.MANUFACTURER.lowercase()) return null
        return runCatching {
            val check = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.java, Int::class.java, String::class.java)
            opAllows(check.invoke(context.getSystemService(AppOpsManager::class.java), MIUI_AUTOSTART_OP, Process.myUid(), context.packageName) as? Int)
        }.getOrNull()
    }

    /** An app op's mode as on or off; null for one that leaves it to something else. */
    fun opAllows(mode: Int?): Boolean? = when (mode) {
        AppOpsManager.MODE_ALLOWED -> true
        AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> false
        else -> null
    }

    /** HyperOS's Autostart list (Security); the app's own settings page on any other phone. */
    fun openAutostart(context: Context) {
        val miui = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        start(context, miui, details(context))
    }

    /** Whether Android leaves the app's background work alone (battery optimisation off for it). */
    fun batteryExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Android's own question, "let xrnloops always run in the background?"; the list of apps if it can't be asked. */
    @SuppressLint("BatteryLife") // installed by hand, never through Play, whose policy this check is
    fun askBattery(context: Context) {
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        start(context, ask, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun details(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private fun start(context: Context, vararg intents: Intent) {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // The next page.
            } catch (_: SecurityException) {
                // Not exported on this build.
            }
        }
    }

    private const val MIUI_AUTOSTART_OP = 10008
}
