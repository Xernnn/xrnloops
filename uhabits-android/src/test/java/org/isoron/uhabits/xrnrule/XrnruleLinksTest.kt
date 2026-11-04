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

import org.isoron.uhabits.xrnrule.XrnruleEarned.Kind
import org.isoron.uhabits.xrnrule.XrnruleLinksContract.STATE_LOCKED
import org.isoron.uhabits.xrnrule.XrnruleLinksContract.STATE_OPEN
import org.isoron.uhabits.xrnrule.XrnruleLinksContract.STATE_READY
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the banner after a tick says xrnrule did with it ([XrnruleLinks.earned]). */
class XrnruleLinksTest {
    private val now = 1_000_000L

    private fun link(habit: String, block: String, state: String, done: Boolean = false) = XrnruleLink(
        habitUuid = habit,
        blockId = block,
        blockName = block,
        targets = "",
        unlockMinutes = 30,
        state = state,
        openUntil = 0,
        habitDone = done,
        selfPaced = false,
        useLeftMs = 0
    )

    private fun map(vararg links: XrnruleLink) = links.groupBy { it.habitUuid }

    private fun earned(old: Map<String, List<XrnruleLink>>, fresh: Map<String, List<XrnruleLink>>, enteredAgoMs: Long = 1_000) =
        XrnruleLinks.earned(old, fresh, mapOf("read" to now - enteredAgoMs), now).map { it.link.blockId to it.kind }

    @Test
    fun earnedAfterATick() {
        val before = map(link("read", "social", STATE_LOCKED))
        val after = map(link("read", "social", STATE_READY, done = true))
        assertEquals(listOf("social" to Kind.EARNED), earned(before, after))
    }

    @Test
    fun openedStraightAway() {
        val before = map(link("read", "social", STATE_LOCKED))
        val after = map(link("read", "social", STATE_OPEN, done = true))
        assertEquals(listOf("social" to Kind.OPEN), earned(before, after))
    }

    @Test
    fun countedWhenTheBlockWantsMore() {
        val before = map(link("read", "social", STATE_LOCKED))
        val after = map(link("read", "social", STATE_LOCKED, done = true))
        assertEquals(listOf("social" to Kind.COUNTED), earned(before, after))
    }

    @Test
    fun nothingForAHabitNotEnteredHere() {
        val before = map(link("walk", "social", STATE_LOCKED))
        val after = map(link("walk", "social", STATE_READY, done = true))
        assertEquals(emptyList<Pair<String, Kind>>(), earned(before, after))
    }

    @Test
    fun nothingLongAfterTheTick() {
        val before = map(link("read", "social", STATE_LOCKED))
        val after = map(link("read", "social", STATE_READY, done = true))
        assertEquals(emptyList<Pair<String, Kind>>(), earned(before, after, XrnruleLinks.ENTERED_WINDOW_MS + 1))
    }

    @Test
    fun nothingForANewLinkOrOneThatWasntLocked() {
        val after = map(link("read", "social", STATE_READY, done = true), link("read", "video", STATE_OPEN, done = true))
        assertEquals(emptyList<Pair<String, Kind>>(), earned(map(), after))
        val before = map(link("read", "video", STATE_READY, done = true))
        assertEquals(emptyList<Pair<String, Kind>>(), earned(before, map(link("read", "video", STATE_OPEN, done = true))))
    }

    @Test
    fun earnedComesBeforeCountedAndEachBlockOnce() {
        val before = map(link("read", "reddit", STATE_LOCKED), link("read", "social", STATE_LOCKED))
        val after = map(link("read", "reddit", STATE_LOCKED, done = true), link("read", "social", STATE_READY, done = true))
        assertEquals(listOf("social" to Kind.EARNED, "reddit" to Kind.COUNTED), earned(before, after))
    }
}
