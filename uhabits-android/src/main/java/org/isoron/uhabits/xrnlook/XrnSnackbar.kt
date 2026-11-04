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

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.snackbar.Snackbar
import org.isoron.uhabits.R
import org.isoron.uhabits.utils.StyledResources
import org.isoron.uhabits.utils.dp

/** Our banners at the bottom of a screen: dark zinc with xrn green in every theme, like xrnrule's. */
object XrnSnackbar {

    /** Null on a screen that can't show one (no Material theme). [icon] is tinted green. */
    fun make(activity: Activity, text: CharSequence, durationMs: Int, icon: Int? = null): Snackbar? {
        val root = activity.findViewById<View>(android.R.id.content) ?: return null
        val bar = try {
            Snackbar.make(root, text, durationMs)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val dark = ColorUtils.calculateLuminance(StyledResources(activity).getColor(R.attr.cardBgColor)) < 0.5
        val green = ContextCompat.getColor(activity, R.color.xrn_green)
        bar.setBackgroundTint(if (dark) 0xFF3F3F46.toInt() else 0xFF27272A.toInt())
        bar.setTextColor(0xFFF4F4F5.toInt())
        bar.setActionTextColor(green)
        // Sentence case, like the rest of the xrn look.
        bar.view.findViewById<Button>(com.google.android.material.R.id.snackbar_action)?.isAllCaps = false
        if (icon != null) {
            bar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.apply {
                val drawable = AppCompatResources.getDrawable(activity, icon)?.mutate()
                val size = dp(18f).toInt()
                drawable?.setBounds(0, 0, size, size)
                drawable?.setTint(green)
                setCompoundDrawablesRelative(drawable, null, null, null)
                compoundDrawablePadding = dp(10f).toInt()
            }
        }
        return bar
    }
}
