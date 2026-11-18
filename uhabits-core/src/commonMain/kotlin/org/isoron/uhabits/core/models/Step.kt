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
package org.isoron.uhabits.core.models

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * xrnloops (XRNLOOPS.md): a number habit's step, what the list's flick adds with + and takes off
 * with −. Loop keeps an amount as an entry, the amount ×1000, and so does all the arithmetic
 * here, so a step of 0.25 never drifts. The web app's loop.js has the same functions.
 */

/** The smallest step, one thousandth: the least Loop can store. */
const val MIN_STEP = 0.001

/** The biggest step the edit form takes. */
const val MAX_STEP = 100_000.0

/**
 * A step as the edit form saves it, to the thousandth, or null if it isn't one: not a number,
 * under [MIN_STEP] once rounded, or over [MAX_STEP].
 */
fun cleanStep(step: Double): Double? {
    if (!step.isFinite()) return null
    val entry = (step * 1000).roundToLong()
    if (entry < 1 || entry > (MAX_STEP * 1000).toLong()) return null
    return entry / 1000.0
}

/**
 * [step] as an entry: at least one thousandth and at most [MAX_STEP]. A step that isn't a
 * positive number (one a client wrote wrong) is 1, as it is for a habit that never had one.
 */
fun stepEntry(step: Double): Int {
    if (!step.isFinite() || step <= 0) return 1000
    return (step * 1000).roundToLong().coerceIn(1, (MAX_STEP * 1000).toLong()).toInt()
}

fun Habit.stepEntry(): Int = stepEntry(step)

/**
 * The entry a number day holding [value] gets from [steps] steps of [step] (an entry too): +1 is
 * the + key, -1 the − key. An empty or skipped day counts as 0, so + gives it one step and − gives
 * it 0; nothing goes below 0, or past what an entry holds. Loop keeps a skip as 3, the entry of
 * 0.003, so a sum that lands exactly there moves one thousandth further, the way it was going.
 */
fun steppedEntry(value: Int, step: Int, steps: Int): Int {
    val from = if (value < 0 || value == Entry.SKIP) 0L else value.toLong()
    val to = (from + step.toLong() * steps).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    return when {
        to != Entry.SKIP -> to
        steps > 0 -> Entry.SKIP + 1
        else -> Entry.SKIP - 1
    }
}

/**
 * The entry for an [amount] typed into a number day's sheet: the amount ×1000, rounded, and no
 * more than an entry holds; or null for one there's nothing to store for: not a number, or below
 * 0, which no amount is and which would read as a cleared day. 0.003 goes in as 0.004, since 3
 * is how Loop keeps a skip. The web's number sheet stores the same (`typedEntry` in loop.js).
 */
fun typedEntry(amount: Double): Int? {
    if (!amount.isFinite() || amount < 0) return null
    val entry = (amount * 1000).roundToInt()
    return if (entry == Entry.SKIP) Entry.SKIP + 1 else entry
}
