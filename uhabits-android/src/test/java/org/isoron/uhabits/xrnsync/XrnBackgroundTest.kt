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

import android.app.AppOpsManager
import org.junit.Assert.assertEquals
import org.junit.Test

/** HyperOS's Autostart switch, read as xrnrule reads it. */
class XrnBackgroundTest {
    @Test
    fun autostartIsOnOnlyWhenTheOpAllowsIt() {
        assertEquals(true, XrnBackground.opAllows(AppOpsManager.MODE_ALLOWED))
        assertEquals(false, XrnBackground.opAllows(AppOpsManager.MODE_IGNORED)) // what every install sets it to
        assertEquals(false, XrnBackground.opAllows(AppOpsManager.MODE_ERRORED))
        assertEquals(null, XrnBackground.opAllows(AppOpsManager.MODE_DEFAULT))
        assertEquals(null, XrnBackground.opAllows(null))
    }
}
