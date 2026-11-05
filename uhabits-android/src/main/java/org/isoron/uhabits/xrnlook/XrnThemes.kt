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

package org.isoron.uhabits.xrnlook

import android.content.Context
import android.graphics.Typeface
import org.isoron.platform.gui.Color
import org.isoron.uhabits.R
import org.isoron.uhabits.core.ui.views.DarkTheme
import org.isoron.uhabits.core.ui.views.LightTheme

/*
 * The xrn family look for the charts and grids Loop draws itself (uhabits-core's Theme), in
 * step with res/values/xrn_app_theme.xml: whatever a chart paints as its card must be that
 * theme's cardBgColor, or each chart shows as a box on its card. Habit colours stay Loop's.
 */

class XrnLightTheme : LightTheme() {
    override val appBackgroundColor = Color(0xF2F2F4)
    override val cardBackgroundColor = Color(0xFFFFFF)
    override val headerBackgroundColor = Color(0xF2F2F4)
    override val headerBorderColor = Color(0xE3E3E8)
    override val headerTextColor = Color(0x6B6B73)
    override val highContrastTextColor = Color(0x1B1B1E)
    override val lowContrastTextColor = Color(0xE3E3E8)
    override val mediumContrastTextColor = Color(0x6B6B73)

    /** Loop's dark grey, which is also the list's toolbar in the light theme: xrn zinc instead. */
    override fun color(paletteIndex: Int): Color =
        if (paletteIndex == 17) Color(0x27272A) else super.color(paletteIndex)
}

class XrnDarkTheme : DarkTheme() {
    override val appBackgroundColor = Color(0x1F1F23)
    override val cardBackgroundColor = Color(0x2B2B30)
    override val headerBackgroundColor = Color(0x1F1F23)
    override val headerBorderColor = Color(0x37373C)
    override val headerTextColor = Color(0xAAAAAC)
    override val highContrastTextColor = Color(0xF4F4F5)
    override val lowContrastTextColor = Color(0x45454B)
    override val mediumContrastTextColor = Color(0xAAAAAC)
}

/** MiSans for text Loop paints on a canvas (charts, the list's day header). */
object XrnFonts {
    fun regular(context: Context): Typeface = context.resources.getFont(R.font.xrn_misans_regular)
    fun bold(context: Context): Typeface = context.resources.getFont(R.font.xrn_misans_semibold)
}
