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
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.isoron.uhabits.R
import org.isoron.uhabits.utils.StyledResources

/**
 * Settings as xrnrule groups them: each section's rows on one rounded card under its label, with
 * hairlines between them. Painted behind the rows, so Loop's preferences stay as they are. A
 * section label is the only row that is a bare TextView (layout/preference_category_custom.xml).
 */
class XrnSettingsCards(context: Context) : RecyclerView.ItemDecoration() {

    private val density = context.resources.displayMetrics.density
    private val side = (SIDE_DP * density).toInt()
    private val inset = HAIRLINE_INSET_DP * density
    private val radius = RADIUS_DP * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val rect = RectF()

    init {
        val res = StyledResources(context)
        fill.color = res.getColor(R.attr.cardBgColor)
        edge.color = res.getColor(R.attr.xrnRowEdge)
    }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.left = side
        outRect.right = side
        // Here, not in the layout: Preference's setSingleLine on every bind drops textAllCaps.
        (view as? TextView)?.isAllCaps = true
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val rows = parent.adapter?.itemCount ?: return
        var first = -1
        for (i in 0..parent.childCount) {
            val child = parent.getChildAt(i)
            if (child != null && child !is TextView) {
                if (first < 0) first = i
                continue
            }
            if (first >= 0) drawCard(c, parent, first, i - 1, rows)
            first = -1
        }
    }

    /** The card behind children [from]..[to], which runs on past the screen if the section does. */
    private fun drawCard(c: Canvas, parent: RecyclerView, from: Int, to: Int, rows: Int) {
        val top = parent.getChildAt(from)
        val bottom = parent.getChildAt(to)
        val isCut = from == 0 && parent.getChildAdapterPosition(top) > 0
        val runsOn = to == parent.childCount - 1 && parent.getChildAdapterPosition(bottom) < rows - 1
        rect.set(
            side.toFloat(),
            top.top - if (isCut) radius * 2 else 0f,
            (parent.width - side).toFloat(),
            bottom.bottom + if (runsOn) radius * 2 else 0f
        )
        c.drawRoundRect(rect, radius, radius, fill)
        c.drawRoundRect(rect, radius, radius, edge)
        for (i in from + 1..to) {
            val y = parent.getChildAt(i).top.toFloat()
            c.drawLine(rect.left + inset, y, rect.right, y, edge)
        }
    }

    private companion object {
        const val SIDE_DP = 12f
        const val RADIUS_DP = 20f
        const val HAIRLINE_INSET_DP = 16f
    }
}
