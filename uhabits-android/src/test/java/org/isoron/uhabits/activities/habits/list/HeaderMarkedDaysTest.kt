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

package org.isoron.uhabits.activities.habits.list

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import androidx.fragment.app.FragmentActivity
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.resetToday
import org.isoron.platform.time.setToday
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.habits.list.views.HeaderView
import org.isoron.uhabits.core.io.StandardLogging
import org.isoron.uhabits.core.preferences.MemoryStorage
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.utils.MidnightTimer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * xrnloops: the list's day header marks the 1st and the 15th, and only their number
 * (XRNLOOPS.md). Drawn with the app's own themes and fonts (Robolectric's native graphics); the
 * PNGs go to -Pshots=<dir>, or build/shots, for a look without a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h873dp-xxhdpi", application = Application::class)
class HeaderMarkedDaysTest {

    private val days = 8

    @After
    fun resetDay() = resetToday()

    @Test
    fun theFifteenthAndTheFirstWearTheMarker_andNoOtherDay() {
        // Newest on the right: 11 12 13 14 15 16 17 18, then 27 28 29 30 1 2 3 4.
        assertEquals(listOf(4), markedColumns(draw("mid-dark", R.style.XrnAppThemeDark, LocalDate(2026, 11, 18))))
        assertEquals(listOf(4), markedColumns(draw("first-dark", R.style.XrnAppThemeDark, LocalDate(2026, 12, 4))))
        assertEquals(emptyList<Int>(), markedColumns(draw("none-dark", R.style.XrnAppThemeDark, LocalDate(2026, 11, 28))))
    }

    @Test
    fun todayOnAMarkedDayWearsItToo_inEveryTheme() {
        for ((name, theme) in listOf("light" to R.style.XrnAppTheme, "dark" to R.style.XrnAppThemeDark, "black" to R.style.XrnAppThemeDark_PureBlack)) {
            assertEquals(name, listOf(7), markedColumns(draw("today-$name", theme, LocalDate(2026, 11, 15))))
        }
    }

    @Test
    fun onlyTheNumberIsMarked_notTheWeekdayAboveIt() {
        val shot = draw("mid-dark", R.style.XrnAppThemeDark, LocalDate(2026, 11, 18))
        val rows = (0 until shot.bitmap.height).filter { y -> columnRange(shot, 4).any { x -> isAccent(shot, x, y) } }
        // The weekday's letters end above the header's middle; the marker starts below it.
        assertEquals(true, rows.isNotEmpty() && rows.first() > shot.bitmap.height / 2)
    }

    private class Shot(val bitmap: Bitmap, val accent: Int, val cell: Float, val right: Float)

    private fun draw(name: String, theme: Int, today: LocalDate): Shot {
        setToday(today)
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(theme)
        val activity = controller.setup().get()
        val prefs = Preferences(MemoryStorage()).apply { isCheckmarkSequenceReversed = true } // newest on the right
        val header = HeaderView(activity, prefs, MidnightTimer(StandardLogging(), prefs)).apply { buttonCount = days }
        val width = activity.resources.displayMetrics.widthPixels
        header.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        header.layout(0, 0, width, header.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, header.measuredHeight, Bitmap.Config.ARGB_8888)
        header.draw(Canvas(bitmap))
        val dir = File(System.getProperty("xrnloops.shots") ?: "build/shots").apply { mkdirs() }
        File(dir, "header-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val accent = TypedValue().also { activity.theme.resolveAttribute(R.attr.colorAccent, it, true) }.data
        val density = activity.resources.displayMetrics.density
        val shot = Shot(bitmap, accent, activity.resources.getDimension(R.dimen.checkmarkWidth), width - 8 * density)
        controller.pause().stop().destroy()
        return shot
    }

    /** The pixels of column [index], counted from the left of the day columns. */
    private fun columnRange(shot: Shot, index: Int): IntRange {
        val left = shot.right - (days - index) * shot.cell
        return left.toInt() until (left + shot.cell).toInt()
    }

    /** The number drawn in the accent itself, which only a marked day's is. */
    private fun isAccent(shot: Shot, x: Int, y: Int): Boolean {
        val pixel = shot.bitmap.getPixel(x, y)
        return Color.red(pixel) == Color.red(shot.accent) && Color.green(pixel) == Color.green(shot.accent) &&
            Color.blue(pixel) == Color.blue(shot.accent)
    }

    private fun markedColumns(shot: Shot): List<Int> = (0 until days).filter { index ->
        columnRange(shot, index).any { x -> (0 until shot.bitmap.height).any { y -> isAccent(shot, x, y) } }
    }
}
