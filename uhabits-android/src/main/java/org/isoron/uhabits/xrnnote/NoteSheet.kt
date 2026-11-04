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

import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Entry.Companion.NO
import org.isoron.uhabits.core.models.Entry.Companion.SKIP
import org.isoron.uhabits.core.models.Entry.Companion.UNKNOWN
import org.isoron.uhabits.core.models.Entry.Companion.YES_MANUAL
import org.isoron.uhabits.databinding.XrnnoteSheetBinding
import org.isoron.uhabits.utils.InterfaceUtils
import org.isoron.uhabits.utils.StyledResources
import org.isoron.uhabits.utils.dismissCurrentAndShow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A yes/no habit's day: its value and its note, in a sheet from the bottom. Replaces Loop's
 * checkmark popup everywhere it showed (the list, the habit page's calendar, a reminder's edit).
 *
 * As in Loop's popup, picking a value saves it with the note as it stands, and closing the sheet
 * any other way keeps a changed note (with the day's value as it was), so typing is never lost.
 */
class NoteSheet : BottomSheetDialogFragment() {

    private var onSave: ((Int, String) -> Unit)? = null
    private lateinit var view: XrnnoteSheetBinding
    private var saved = false
    private var originalValue = UNKNOWN
    private var originalNote = ""

    override fun getTheme() = R.style.XrnNoteSheet

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Brought back after the activity was rebuilt: what to save to is gone with it.
        if (onSave == null) dismissAllowingStateLoss()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        view = XrnnoteSheetBinding.inflate(inflater, container, false)
        val args = requireArguments()
        val color = args.getInt(ARG_COLOR)
        originalValue = args.getInt(ARG_VALUE)
        originalNote = args.getString(ARG_NOTE).orEmpty()

        view.title.text = args.getString(ARG_NAME).orEmpty()
        view.title.setTextColor(color)
        view.title.visibility = if (view.title.text.isBlank()) View.GONE else View.VISIBLE
        view.date.text = describe(LocalDate(args.getInt(ARG_DAYS)))
        view.note.setText(originalNote)
        view.noteLayout.setBoxStrokeColorStateList(ColorStateList.valueOf(color))

        val res = StyledResources(requireContext())
        val faint = res.getColor(R.attr.contrast60)
        addValue(YES_MANUAL, R.string.fa_check, R.string.xrnnote_done, color, color)
        addValue(NO, R.string.fa_times, R.string.xrnnote_not_done, faint, color)
        addValue(SKIP, R.string.fa_skipped, R.string.xrnnote_skip, color, color)
        addValue(UNKNOWN, R.string.fa_question, R.string.xrnnote_clear, faint, color)

        view.save.setOnClickListener {
            if (note() != originalNote) save(originalValue)
            dismiss()
        }
        // Edge to edge: keep the button above the navigation bar. The keyboard is the window's
        // (adjustResize in the theme); counting it here too would make room for it twice.
        ViewCompat.setOnApplyWindowInsetsListener(view.root) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottomPadding + bottom)
            insets
        }
        return view.root
    }

    private val bottomPadding by lazy { (16 * resources.displayMetrics.density).toInt() }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        if (requireArguments().getBoolean(ARG_FOCUS) && view.note.requestFocus()) {
            view.note.setSelection(view.note.length())
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!saved && ::view.isInitialized && note() != originalNote) save(originalValue)
    }

    private fun note() = view.note.text?.toString()?.trim().orEmpty()

    private fun save(value: Int) {
        saved = true
        onSave?.invoke(value, note())
    }

    /** One of the four value buttons: a glyph in Loop's icon font over a word. */
    private fun addValue(value: Int, glyph: Int, label: Int, tint: Int, accent: Int) {
        val context = requireContext()
        val res = StyledResources(context)
        val selected = value == originalValue
        val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.45) 0xFF18181B.toInt() else 0xFFFFFFFF.toInt()
        val surface = ColorUtils.blendARGB(res.getColor(R.attr.cardBgColor), res.getColor(R.attr.contrast100), 0.07f)
        val dp = resources.displayMetrics.density
        val button = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = (68 * dp).toInt()
            isClickable = true
            isFocusable = true
            contentDescription = getString(label)
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(if (selected) accent else surface)
            }
            foreground = context.getDrawable(
                TypedValue().also { context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId
            )
            setOnClickListener {
                save(value)
                dismiss()
            }
        }
        button.addView(
            TextView(context).apply {
                text = getString(glyph)
                typeface = InterfaceUtils.getFontAwesome(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (value == UNKNOWN) 18f else 20f)
                setTextColor(if (selected) onAccent else tint)
                gravity = Gravity.CENTER
            }
        )
        button.addView(
            TextView(context).apply {
                text = getString(label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(if (selected) onAccent else res.getColor(R.attr.contrast80))
                gravity = Gravity.CENTER
                setPadding(0, (4 * dp).toInt(), 0, 0)
            }
        )
        view.values.addView(
            button,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                val margin = (4 * dp).toInt()
                setMargins(margin, 0, margin, 0)
            }
        )
    }

    /** "Today · Wednesday 4 November", or just the date for another day. */
    private fun describe(date: LocalDate): String {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
        val text = SimpleDateFormat(pattern, Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(date.unixTime))
        val today = getToday()
        return when (date.daysSince2000) {
            today.daysSince2000 -> getString(R.string.xrnnote_today, text)
            today.daysSince2000 - 1 -> getString(R.string.xrnnote_yesterday, text)
            else -> text
        }
    }

    companion object {
        private const val ARG_NAME = "name"
        private const val ARG_DAYS = "days"
        private const val ARG_COLOR = "color"
        private const val ARG_VALUE = "value"
        private const val ARG_NOTE = "note"
        private const val ARG_FOCUS = "focus"

        /** Set by the flick menu's note key: the next sheet opens with the keyboard up. */
        @Volatile
        private var focusNext = false

        fun focusNoteNextTime() {
            focusNext = true
        }

        fun show(
            activity: FragmentActivity,
            habitName: String,
            date: LocalDate,
            color: Int,
            value: Int,
            note: String,
            onSave: (Int, String) -> Unit
        ) {
            val sheet = NoteSheet()
            sheet.onSave = onSave
            sheet.arguments = Bundle().apply {
                putString(ARG_NAME, habitName)
                putInt(ARG_DAYS, date.daysSince2000)
                putInt(ARG_COLOR, color)
                putInt(ARG_VALUE, value)
                putString(ARG_NOTE, note)
                putBoolean(ARG_FOCUS, focusNext)
            }
            focusNext = false
            sheet.dismissCurrentAndShow(activity.supportFragmentManager, "xrnNoteSheet")
        }
    }
}
