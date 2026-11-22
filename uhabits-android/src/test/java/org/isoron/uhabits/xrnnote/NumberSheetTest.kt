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

package org.isoron.uhabits.xrnnote

import android.app.Application
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.resetToday
import org.isoron.platform.time.setToday
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Entry.Companion.SKIP
import org.isoron.uhabits.core.models.Entry.Companion.UNKNOWN
import org.isoron.uhabits.core.models.Entry.Companion.YES_MANUAL
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * xrnloops: the number sheet itself, driven as a finger would (NoteSheet): its pad, − and + by the
 * habit's step, the quick keys, Save and the other ways out, a keyboard, and the yes/no sheet the
 * class also draws. What it writes is [org.isoron.uhabits.core.models.AmountPad]'s, tested there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NumberSheetTest {

    private val today = LocalDate(2026, 11, 22)
    private val saved = mutableListOf<Double>()
    private lateinit var activity: FragmentActivity
    private lateinit var sheet: NoteSheet

    @Before
    fun fixToday() = setToday(today)

    @After
    fun resetDay() = resetToday()

    /** Water, 8 glasses a day, by [step], with [value] on the day (an amount, -0.001 for none). */
    private fun open(value: Double, step: Double = 1.0, target: Double = 8.0) {
        activity = Robolectric.buildActivity(FragmentActivity::class.java)
            .also { it.get().setTheme(R.style.XrnAppThemeDark) }
            .setup().get()
        val habit = MemoryModelFactory().buildHabit().apply {
            name = "Water"
            unit = "glasses"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = target
            this.step = step
        }
        NoteSheet.showNumber(activity, habit, today, 0xFF64B5F6.toInt(), value) { saved += it }
        idle()
        sheet = activity.supportFragmentManager.findFragmentByTag("xrnNoteSheet") as NoteSheet
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val root get() = sheet.requireDialog().window!!.decorView

    private fun <T : View> find(id: Int): T = root.findViewById(id)

    private fun ViewGroup.all(): List<View> = (0 until childCount).flatMap {
        val child = getChildAt(it)
        listOf(child) + ((child as? ViewGroup)?.all() ?: emptyList())
    }

    /** The pad's keys: digits, "." for the separator, "<" for ⌫. */
    private fun tap(keys: String) {
        val pad = find<ViewGroup>(R.id.pad).all()
        for (key in keys) {
            val view = when (key) {
                '<' -> pad.single { it.contentDescription == "Delete" }
                '.' -> pad.single { it.contentDescription == "Decimal point" }
                else -> pad.single { it is TextView && it.contentDescription == null && it.text == "$key" }
            }
            view.performClick()
        }
        idle()
    }

    private fun chips() = find<ViewGroup>(R.id.chips).all()

    private fun chip(description: String) = chips().single { it.contentDescription == description }

    private val amount get() = find<TextView>(R.id.amount).text.toString()

    private fun save() {
        find<View>(R.id.save).performClick()
        idle()
    }

    private fun leave() {
        sheet.requireDialog().cancel()
        idle()
    }

    @Test
    fun theSheetHasNoTextFieldSoThePhonesKeyboardNeverComesUp() {
        open(3.0)
        assertTrue((root as ViewGroup).all().none { it is EditText })
        assertEquals(12, find<ViewGroup>(R.id.pad).all().count { it !is ViewGroup })
    }

    @Test
    fun anEmptyOrSkippedDayShowsADashNotAZero() {
        open(UNKNOWN / 1000.0)
        assertEquals("–", amount)
        open(SKIP / 1000.0)
        assertEquals("–", amount)
        open(2.5)
        assertEquals("2.5", amount)
    }

    @Test
    fun saveWritesWhatThePadTyped() {
        open(UNKNOWN / 1000.0)
        tap("2.5")
        assertEquals("2.5", amount)
        save()
        assertEquals(listOf(2.5), saved)
    }

    @Test
    fun theFirstKeyReplacesTheAmountItOpenedWith() {
        open(3.0)
        tap("6")
        assertEquals("6", amount)
        tap("<7")
        save()
        assertEquals(listOf(7.0), saved)
    }

    @Test
    fun leavingAnyOtherWayKeepsAnAmountKeyedIn() {
        open(3.0)
        tap("12")
        leave()
        assertEquals(listOf(12.0), saved)
    }

    @Test
    fun anAmountNobodyChangedWritesNothing() {
        open(3.0)
        save()
        open(3.0)
        leave()
        open(3.0)
        find<View>(R.id.plus).performClick()
        find<View>(R.id.minus).performClick()
        save()
        assertEquals(emptyList<Double>(), saved)
    }

    @Test
    fun aBoxLeftBlankWritesNothingOnTheWayOutAndSaveEmptiesTheDay() {
        open(3.0)
        tap("<")
        assertEquals("–", amount)
        leave()
        assertEquals(emptyList<Double>(), saved)
        open(3.0)
        tap("<")
        save()
        assertEquals(listOf(UNKNOWN / 1000.0), saved)
    }

    @Test
    fun plusAndMinusGoByTheHabitsStepAndMinusGivesAQuickZero() {
        open(3.0, step = 0.5)
        assertEquals("+0.5", find<TextView>(R.id.plus).text)
        assertEquals("Add 0.5 glasses", find<View>(R.id.plus).contentDescription)
        find<View>(R.id.plus).performClick()
        find<View>(R.id.plus).performClick()
        assertEquals("4", amount)
        save()
        open(UNKNOWN / 1000.0)
        assertEquals("+", find<TextView>(R.id.plus).text)
        find<View>(R.id.minus).performClick()
        save()
        assertEquals(listOf(4.0, 0.0), saved)
    }

    @Test
    fun theQuickKeysSaveAtOnce() {
        open(3.0)
        chip("Target, 8 glasses").performClick()
        idle()
        open(3.0)
        chip("Skip").performClick()
        idle()
        open(3.0)
        chip("Clear").performClick()
        idle()
        assertEquals(listOf(8.0, SKIP / 1000.0, UNKNOWN / 1000.0), saved)
    }

    @Test
    fun noTargetMeansNoTargetKeyAndNoBar() {
        open(3.0, target = 0.0)
        assertEquals(View.GONE, find<View>(R.id.progress).visibility)
        assertEquals(listOf("Skip", "Clear"), chips().mapNotNull { it.contentDescription?.toString() })
    }

    @Test
    fun theFigureByTheBarSaysTheTarget() {
        open(3.0)
        assertEquals("/ 8", find<TextView>(R.id.target).text)
        assertEquals("3 of 8 glasses", find<View>(R.id.progress).contentDescription)
    }

    @Test
    fun aKeyboardTypesOnThePadAndEnterSaves() {
        open(3.0)
        val dialog = sheet.requireDialog()
        val keys = listOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_NUMPAD_5, KeyEvent.KEYCODE_ENTER)
        for (code in keys) {
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        idle()
        assertEquals(listOf(1.5), saved)
    }

    @Test
    fun theYesNoSheetKeepsItsFourValues() {
        activity = Robolectric.buildActivity(FragmentActivity::class.java)
            .also { it.get().setTheme(R.style.XrnAppThemeDark) }
            .setup().get()
        val values = mutableListOf<Int>()
        NoteSheet.show(activity, "Read", today, 0xFFB39DDB.toInt(), UNKNOWN) { values += it }
        idle()
        sheet = activity.supportFragmentManager.findFragmentByTag("xrnNoteSheet") as NoteSheet
        assertEquals(View.GONE, find<View>(R.id.number).visibility)
        val row = find<ViewGroup>(R.id.values)
        val buttons = (0 until row.childCount).map { row.getChildAt(it) }
        assertEquals(listOf("Done", "Not done", "Skip", "Clear"), buttons.map { it.contentDescription })
        buttons.first().performClick()
        idle()
        assertEquals(listOf(YES_MANUAL), values)
    }
}
