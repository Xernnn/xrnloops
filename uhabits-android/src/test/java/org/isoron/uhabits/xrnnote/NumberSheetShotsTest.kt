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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.util.TypedValue
import android.view.View
import androidx.fragment.app.FragmentActivity
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.resetToday
import org.isoron.platform.time.setToday
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Entry.Companion.UNKNOWN
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.xrnlook.XrnDarkTheme
import org.isoron.uhabits.xrnlook.XrnLightTheme
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * xrnloops: the number sheet drawn with the app's own resources and fonts (Robolectric's native
 * graphics), for review without a phone: a made-up "Water" habit, 8 glasses a day, empty, below
 * its target and at it, in the light, dark and pure black themes, and the longest amount there
 * is with a long unit and a step of 0.25. -Pshots=<dir> picks where the
 * PNGs go; build/shots otherwise. The page behind is left plain, under the sheet's dim.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h873dp-xxhdpi", application = Application::class)
class NumberSheetShotsTest {

    private val today = LocalDate(2026, 11, 22)

    @Before
    fun fixToday() = setToday(today)

    @After
    fun resetDay() = resetToday()

    @Test
    fun drawsEveryCaseInEveryTheme() {
        val themes = listOf(
            Triple("light", R.style.XrnAppTheme, XrnLightTheme().color(PaletteColor(11)).toInt()),
            Triple("dark", R.style.XrnAppThemeDark, XrnDarkTheme().color(PaletteColor(11)).toInt()),
            Triple("black", R.style.XrnAppThemeDark_PureBlack, XrnDarkTheme().color(PaletteColor(11)).toInt())
        )
        val cases = listOf("empty" to UNKNOWN / 1000.0, "below" to 3.0, "met" to 8.0)
        for ((themeName, theme, color) in themes) {
            for ((case, value) in cases) shot("$case-$themeName", theme, color, value)
        }
        // The longest an amount gets, a long unit, a step that isn't 1, and "at most" gone past.
        val (_, dark, color) = themes[1]
        val reading = water().apply {
            name = "Screen time"
            unit = "minutes of scrolling"
            targetType = NumericalHabitType.AT_MOST
            targetValue = 45.0
            step = 0.25
        }
        shot("long-dark", dark, color, 2147483.647, reading)
    }

    private fun water() = MemoryModelFactory().buildHabit().apply {
        name = "Water"
        unit = "glasses"
        type = HabitType.NUMERICAL
        targetType = NumericalHabitType.AT_LEAST
        targetValue = 8.0
        step = 1.0
        color = PaletteColor(11)
    }

    private fun shot(name: String, theme: Int, color: Int, value: Double, habit: Habit = water()) {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(theme)
        val activity = controller.setup().get()
        NoteSheet.showNumber(activity, habit, today, color, value) { }
        shadowOf(Looper.getMainLooper()).idle()
        val sheet = activity.supportFragmentManager.findFragmentByTag("xrnNoteSheet") as NoteSheet
        val decor = sheet.requireDialog().window!!.decorView
        val metrics = activity.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()

        val page = TypedValue().also { activity.theme.resolveAttribute(R.attr.windowBackgroundColor, it, true) }.data
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(page)
        canvas.drawColor(0x52000000) // the dialog's dim, 0.32 of black
        decor.draw(canvas)
        val dir = File(System.getProperty("xrnloops.shots") ?: "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        sheet.dismissAllowingStateLoss()
        controller.pause().stop().destroy()
    }
}
