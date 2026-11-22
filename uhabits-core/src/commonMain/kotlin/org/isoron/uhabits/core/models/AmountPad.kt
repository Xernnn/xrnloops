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

/**
 * xrnloops (XRNLOOPS.md): the amount in a number day's sheet while it is keyed in on the sheet's
 * own pad, and what the sheet writes when it closes. [opened] is the day's entry as the sheet
 * opened on it (an amount ×1000, [Entry.UNKNOWN] for an empty day, [Entry.SKIP] for a skipped
 * one). The web app's number sheet has the same rules (`AmountPad` in loop.js).
 *
 * The amount is [text], with "." for the separator whatever the locale shows: "" is no amount,
 * as an empty or skipped day opens. Only an amount gets in: digits, one separator, at most three
 * decimals (an entry keeps the thousandth), and no more than an entry holds. The first key after
 * the sheet opens, or after + or −, starts a new amount, as a box with its text selected did.
 */
class AmountPad(val opened: Int) {

    var text: String = shown(opened)
        private set

    /** What the sheet opened with: an amount nobody changed writes nothing. */
    val openedText: String = text

    /** The next digit or separator replaces what's shown rather than adding to it. */
    private var fresh = true

    /** What [text] stands for as an entry ([typedEntry]), or null for no amount. */
    val entry: Int? get() = if (text.isEmpty()) null else typedEntry(text.toDouble())

    val changed: Boolean get() = text != openedText

    /** [digit] (0 to 9) on the end; false when it can't go there and nothing changed. */
    fun digit(digit: Int): Boolean {
        require(digit in 0..9)
        val from = if (fresh) "" else text
        val decimals = from.substringAfter('.', "")
        if ('.' in from && decimals.length >= DECIMALS) return false
        val next = if (from == "0") "$digit" else "$from$digit"
        if (next.toDouble() > MAX_AMOUNT) return false
        fresh = false
        text = next
        return true
    }

    /** The decimal separator; a box with nothing in it gets "0." */
    fun separator(): Boolean {
        val from = if (fresh) "" else text
        if ('.' in from) return false
        fresh = false
        text = from.ifEmpty { "0" } + "."
        return true
    }

    /** ⌫: the last character off. */
    fun backspace(): Boolean {
        fresh = false
        if (text.isEmpty()) return false
        text = text.dropLast(1)
        return true
    }

    /** ⌫ held: no amount. */
    fun clear(): Boolean {
        fresh = false
        if (text.isEmpty()) return false
        text = ""
        return true
    }

    /**
     * + (1) or − (-1) by the habit's [step] (an entry), from what the sheet shows, as the list's
     * flick goes ([steppedEntry]): no amount counts as 0, − never goes below 0, and 0.003, which
     * is how Loop keeps a skip, is stepped over.
     */
    fun step(step: Int, steps: Int) {
        text = shown(steppedEntry(entry ?: Entry.UNKNOWN, step, steps))
        fresh = true
    }

    /**
     * What Save writes, or null for nothing: an amount nobody changed writes nothing, so it can't
     * put back one sync changed meanwhile, and no amount empties the day without undoing a skip
     * ([now] is the day as it is when Save is pressed, when the screen knows it).
     */
    fun toSave(now: Int = opened): Int? = when {
        !changed -> null
        text.isEmpty() -> if (now == Entry.SKIP) Entry.SKIP else Entry.UNKNOWN
        else -> entry
    }

    /**
     * What leaving the sheet any way but Save writes: an amount that was keyed in is kept, so it
     * is never lost, and nothing else. Only Save or Clear empties a day.
     */
    fun toKeep(): Int? = if (changed && text.isNotEmpty()) entry else null

    companion object {
        /** Kept to the thousandth, as an entry is the amount ×1000. */
        const val DECIMALS = 3

        /** The most an entry holds, as an amount. */
        const val MAX_AMOUNT = Int.MAX_VALUE / 1000.0

        /**
         * An entry as the pad shows it, to the thousandth with "." for the separator: "2.5",
         * "0.715", "8"; "" for an empty or skipped day, which has no amount.
         */
        fun shown(entry: Int): String {
            if (entry < 0 || entry == Entry.SKIP) return ""
            val whole = entry / 1000
            val part = entry % 1000
            if (part == 0) return "$whole"
            return "$whole." + "$part".padStart(DECIMALS, '0').trimEnd('0')
        }
    }
}

/**
 * xrnloops: how far a day holding [entry] is towards its share of the target ([target], from
 * [dayTargetEntry]), from 0 to 1, for the number sheet's bar; null when there's no target to show.
 * An empty or skipped day is at 0.
 */
fun dayProgress(entry: Int?, target: Int): Float? {
    if (target <= 0) return null
    if (entry == null || entry < 0 || entry == Entry.SKIP) return 0f
    return (entry.toDouble() / target).coerceAtMost(1.0).toFloat()
}

/**
 * xrnloops: whether a day holding [entry] meets its share of the target ([target], from
 * [dayTargetEntry]), as the list colours its amount: reached for "at least" (which is "completed
 * today"), not passed for "at most". No amount, a skip or no target never does.
 */
fun meetsDayTarget(entry: Int?, target: Int, type: NumericalHabitType): Boolean {
    if (target <= 0 || entry == null || entry < 0 || entry == Entry.SKIP) return false
    return if (type == NumericalHabitType.AT_MOST) entry <= target else entry >= target
}
