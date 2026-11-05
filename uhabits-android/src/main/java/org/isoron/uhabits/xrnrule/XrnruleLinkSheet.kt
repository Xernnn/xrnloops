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

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.databinding.XrnlinkRowBinding
import org.isoron.uhabits.databinding.XrnlinkSheetBinding
import org.isoron.uhabits.utils.StyledResources
import org.isoron.uhabits.utils.dismissCurrentAndShow
import java.util.Date

/**
 * What a habit does in xrnrule, in words: every block that uses it, most useful first, each with
 * how it stands and what it holds back. Comes up from the lock on a card ([XrnruleLinkBadge]), and
 * only a tap on a block goes on to xrnrule ([XrnruleLinks.openBlock]). Follows the links while
 * it's up.
 */
class XrnruleLinkSheet : BottomSheetDialogFragment() {

    private lateinit var view: XrnlinkSheetBinding
    private val onLinksChanged: () -> Unit = { render() }

    override fun getTheme() = R.style.XrnNoteSheet

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        view = XrnlinkSheetBinding.inflate(inflater, container, false)
        val args = requireArguments()
        view.title.text = args.getString(ARG_NAME).orEmpty()
        view.title.setTextColor(args.getInt(ARG_COLOR))
        view.title.visibility = if (view.title.text.isBlank()) View.GONE else View.VISIBLE
        return view.root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        XrnruleLinks.instance?.addListener(onLinksChanged)
        render()
    }

    override fun onStop() {
        XrnruleLinks.instance?.removeListener(onLinksChanged)
        super.onStop()
    }

    private fun render() {
        val args = requireArguments()
        val links = XrnruleLinks.ordered(XrnruleLinks.instance?.forHabit(args.getString(ARG_HABIT)).orEmpty())
        if (links.isEmpty()) {
            dismissAllowingStateLoss()
            return
        }
        view.blocks.removeAllViews()
        links.forEach { addBlock(it, args.getInt(ARG_COLOR)) }
    }

    private fun addBlock(link: XrnruleLink, color: Int) {
        val context = requireContext()
        val res = StyledResources(context)
        val row = XrnlinkRowBinding.inflate(layoutInflater, view.blocks, true)
        XrnruleLinkBadge.paint(row.lock, link, color)
        // Without a name of its own, a block goes by what it holds back, and that isn't said twice.
        row.block.text = link.blockName.ifBlank { link.targets }
        row.status.text = status(context, link)
        row.targets.text = link.targets
        row.targets.visibility = if (link.blockName.isBlank() || link.targets.isBlank()) View.GONE else View.VISIBLE
        row.root.background = GradientDrawable().apply {
            cornerRadius = 16 * resources.displayMetrics.density
            setColor(ColorUtils.blendARGB(res.getColor(R.attr.cardBgColor), res.getColor(R.attr.contrast100), 0.07f))
        }
        row.root.clipToOutline = true
        row.root.setOnClickListener {
            XrnruleLinks.openBlock(context, link.blockId)
            dismiss()
        }
    }

    companion object {
        private const val ARG_HABIT = "habit"
        private const val ARG_NAME = "name"
        private const val ARG_COLOR = "color"

        fun show(activity: FragmentActivity, habit: Habit, color: Int) {
            val sheet = XrnruleLinkSheet()
            sheet.arguments = Bundle().apply {
                putString(ARG_HABIT, habit.uuid)
                putString(ARG_NAME, habit.name)
                putInt(ARG_COLOR, color)
            }
            sheet.dismissCurrentAndShow(activity.supportFragmentManager, "xrnLinkSheet")
        }

        /** "Locked · unlocks 30 min", "Earned · 30 min to open", "Open till 14:30", "Open · 12 min left". */
        fun status(context: Context, link: XrnruleLink): String = when (link.state) {
            XrnruleLinksContract.STATE_OPEN -> when {
                link.selfPaced -> context.getString(
                    R.string.xrnlink_left,
                    minutes(context, ((link.useLeftMs + 59_999) / 60_000).toInt())
                )
                link.openUntil > 0 -> context.getString(
                    R.string.xrnlink_open,
                    DateFormat.getTimeFormat(context).format(Date(link.openUntil))
                )
                else -> context.getString(R.string.xrnlink_open_now)
            }
            XrnruleLinksContract.STATE_READY -> context.getString(
                R.string.xrnlink_ready,
                minutes(context, link.unlockMinutes)
            )
            else -> if (link.habitDone) {
                context.getString(R.string.xrnlink_done_locked)
            } else {
                context.getString(R.string.xrnlink_locked, minutes(context, link.unlockMinutes))
            }
        }

        /** "30 min", "1 h", "1 h 30 min". */
        fun minutes(context: Context, total: Int): String {
            val h = total / 60
            val m = total % 60
            return when {
                h == 0 -> context.getString(R.string.xrnlink_minutes, m)
                m == 0 -> context.getString(R.string.xrnlink_hours, h)
                else -> context.getString(R.string.xrnlink_hours_minutes, h, m)
            }
        }
    }
}
