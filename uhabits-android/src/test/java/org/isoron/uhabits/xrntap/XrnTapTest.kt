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

package org.isoron.uhabits.xrntap

import org.isoron.uhabits.xrntap.XrnTap.Action.CYCLE
import org.isoron.uhabits.xrntap.XrnTap.Action.TICK
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Tap on a day" ([XrnTap.resolve]): the choice that opened the day's note is gone (2026-11-15). */
class XrnTapTest {
    @Test
    fun aPhoneThatOpenedTheDaysNoteTicksNow() {
        // Picking "menu" turned Loop's switch off (XrnTap.attach until 2026-11-15).
        assertEquals(TICK, XrnTap.resolve("menu", loopShortToggle = false))
    }

    @Test
    fun aChoiceMadeStays() {
        assertEquals(TICK, XrnTap.resolve("tick", loopShortToggle = false))
        assertEquals(CYCLE, XrnTap.resolve("cycle", loopShortToggle = false))
    }

    @Test
    fun noChoiceYetFollowsLoopsSwitchOrTicks() {
        assertEquals(CYCLE, XrnTap.resolve(null, loopShortToggle = true))
        assertEquals(TICK, XrnTap.resolve(null, loopShortToggle = false))
        assertEquals(TICK, XrnTap.resolve("something else", loopShortToggle = false))
    }
}
