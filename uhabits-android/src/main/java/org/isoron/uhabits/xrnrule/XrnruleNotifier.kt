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

import android.content.Context
import android.content.Intent
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateRepetitionCommand

/**
 * Tells xrnrule that habits changed. Every check — list screen, history card, widget,
 * notification action — runs as a [Command], so listening to the [CommandRunner] catches all
 * of them. The broadcast carries no data and only reaches holders of the signature permission.
 */
class XrnruleNotifier(
    private val context: Context,
    private val commandRunner: CommandRunner
) : CommandRunner.Listener {

    override fun onCommandFinished(command: Command) {
        // So the answer xrnrule sends back can say what this tick earned (XrnruleBanner).
        if (command is CreateRepetitionCommand) XrnruleLinks.instance?.noteEntered(command.habit.uuid.orEmpty())
        val intent = Intent(XrnruleContract.ACTION_HABITS_CHANGED)
            .setPackage(XrnruleContract.XRNRULE_PACKAGE)
        context.sendBroadcast(intent, XrnruleContract.PERMISSION)
    }

    fun startListening() {
        commandRunner.addListener(this)
    }

    fun stopListening() {
        commandRunner.removeListener(this)
    }
}
