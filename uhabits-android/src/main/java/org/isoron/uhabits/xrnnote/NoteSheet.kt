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
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.method.DigitsKeyListener
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
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
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.databinding.XrnnoteSheetBinding
import org.isoron.uhabits.utils.InterfaceUtils
import org.isoron.uhabits.utils.StyledResources
import org.isoron.uhabits.utils.dismissCurrentAndShow
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * A habit's day: its value and its note, in a sheet from the bottom. Replaces Loop's checkmark
 * popup and number box everywhere they showed (the list, the habit page's calendar, a reminder's
 * edit). A number day gets the amount and quick keys in place of the four value buttons; both
 * work in entry values, where an amount is stored ×1000, and the web app's sheet has the same keys.
 *
 * As in Loop's popups, picking a value saves it with the note as it stands. Closing the sheet any
 * other way keeps what was typed, a new amount or a changed note, so typing is never lost; only
 * Save or Clear empties a day, never a box left blank on the way out.
 */
class NoteSheet : BottomSheetDialogFragment() {

    private var onSave: ((Int, String) -> Unit)? = null
    private lateinit var view: XrnnoteSheetBinding
    private var saved = false
    private var originalValue = UNKNOWN
    private var originalNote = ""
    private var originalAmount = ""

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
        if (isNumber) {
            bindNumber(color)
        } else {
            addValue(YES_MANUAL, R.string.fa_check, R.string.xrnnote_done, color, color)
            addValue(NO, R.string.fa_times, R.string.xrnnote_not_done, faint, color)
            addValue(SKIP, R.string.fa_skipped, R.string.xrnnote_skip, color, color)
            addValue(UNKNOWN, R.string.fa_question, R.string.xrnnote_clear, faint, color)
        }

        view.save.setOnClickListener {
            when {
                isNumber -> save(amount())
                note() != originalNote -> save(originalValue)
            }
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

    private val isNumber get() = requireArguments().getBoolean(ARG_NUMBER)

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        val focused = when {
            requireArguments().getBoolean(ARG_FOCUS) -> view.note.requestFocus().also {
                view.note.setSelection(view.note.length())
            }
            isNumber -> view.amount.requestFocus().also { view.amount.selectAll() }
            else -> false
        }
        if (focused) {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (saved || !::view.isInitialized) return
        when {
            amountTyped() -> save(amount())
            note() != originalNote -> save(originalValue)
        }
    }

    private fun amountTyped(): Boolean {
        val text = view.amount.text?.toString().orEmpty()
        return isNumber && text.isNotEmpty() && text != originalAmount
    }

    private fun note() = view.note.text?.toString()?.trim().orEmpty()

    private fun save(value: Int) {
        saved = true
        onSave?.invoke(value, note())
    }

    /** The amount, its unit and the quick keys: the day's target, 0, skip and clear. */
    private fun bindNumber(color: Int) {
        val args = requireArguments()
        val unit = args.getString(ARG_UNIT).orEmpty()
        val target = args.getDouble(ARG_TARGET)
        view.amountRow.visibility = View.VISIBLE
        view.amountLayout.setBoxStrokeColorStateList(ColorStateList.valueOf(color))
        view.unit.text = unit
        view.note.minLines = 2
        // A skip or an empty day has no amount: blank, as on the web, so Save doesn't enter a 0.
        if (originalValue >= 0 && originalValue != SKIP) {
            view.amount.setText(AMOUNT.format(originalValue / 1000.0))
        }
        originalAmount = view.amount.text?.toString().orEmpty()
        fixDecimalSeparator()
        view.amount.setOnEditorActionListener { _, _, _ -> view.save.performClick() }

        val res = StyledResources(requireContext())
        val strong = res.getColor(R.attr.contrast100)
        val faint = res.getColor(R.attr.contrast60)
        val targetLabel = "${AMOUNT.format(target)} $unit".trim()
        if (target > 0) addKey((target * 1000).roundToInt(), targetLabel, color, color, 1f)
        addKey(0, "0", strong, color, 0f)
        addKey(SKIP, getString(R.string.xrnnote_skip), color, color, 0f)
        addKey(UNKNOWN, getString(R.string.xrnnote_clear), faint, color, 0f)
    }

    /** What's in the box, in entry values. Nothing in it means no amount, not "undo the skip". */
    private fun amount(): Int {
        val text = view.amount.text?.toString().orEmpty()
        if (text.isEmpty()) return if (originalValue == SKIP) SKIP else UNKNOWN
        return try {
            (NumberFormat.getInstance().parse(text)!!.toDouble() * 1000).roundToInt()
        } catch (e: ParseException) {
            originalValue
        }
    }

    /** As Loop's number box does: the keyboard's separator has to be the one we parse. */
    private fun fixDecimalSeparator() {
        // https://stackoverflow.com/a/34256139
        val separator = DecimalFormatSymbols.getInstance().decimalSeparator
        view.amount.keyListener = DigitsKeyListener.getInstance("0123456789$separator")
        // https://github.com/flutter/flutter/issues/61175
        val keyboard = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ).orEmpty()
        if (keyboard.contains("swiftkey") || keyboard.contains("samsung")) {
            view.amount.inputType = EditorInfo.TYPE_CLASS_TEXT
        }
    }

    private fun addKey(value: Int, label: String, tint: Int, accent: Int, weight: Float) {
        val context = requireContext()
        val dp = resources.displayMetrics.density
        val selected = value == originalValue && value != UNKNOWN
        val key = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minimumWidth = (56 * dp).toInt()
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(if (selected) onAccent(accent) else tint)
            background = tile(selected, accent)
            pressable(this)
            setOnClickListener {
                save(value)
                dismiss()
            }
        }
        view.values.addView(
            key,
            LinearLayout.LayoutParams(
                if (weight > 0) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
                (44 * dp).toInt(),
                weight
            ).apply {
                val margin = (4 * dp).toInt()
                setMargins(margin, 0, margin, 0)
            }
        )
    }

    private fun tile(selected: Boolean, accent: Int): GradientDrawable {
        val res = StyledResources(requireContext())
        val dp = resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = 16 * dp
            setColor(if (selected) accent else res.getColor(R.attr.cardBgColor))
            if (!selected) setStroke((1 * dp).toInt(), res.getColor(R.attr.xrnRowEdge))
        }
    }

    private fun onAccent(accent: Int) =
        if (ColorUtils.calculateLuminance(accent) > 0.19) 0xFF18181B.toInt() else 0xFFFFFFFF.toInt()

    private fun pressable(target: View) {
        val context = requireContext()
        target.isClickable = true
        target.isFocusable = true
        target.foreground = context.getDrawable(
            TypedValue().also {
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            }.resourceId
        )
    }

    /** One of the four value buttons: a glyph in Loop's icon font over a word. */
    private fun addValue(value: Int, glyph: Int, label: Int, tint: Int, accent: Int) {
        val context = requireContext()
        val res = StyledResources(context)
        // A day with nothing on it has nothing to pick out: "Clear" isn't what it's set to.
        val selected = value == originalValue && value != UNKNOWN
        val onAccent = onAccent(accent)
        val dp = resources.displayMetrics.density
        val button = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = (68 * dp).toInt()
            contentDescription = getString(label)
            background = tile(selected, accent)
            pressable(this)
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
        private const val ARG_NUMBER = "number"
        private const val ARG_UNIT = "unit"
        private const val ARG_TARGET = "target"

        // Three places, as an amount is kept to the thousandth: fewer would change it on Save.
        private val AMOUNT get() = DecimalFormat("#.###")

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
        ) = open(activity, habitName, date, color, value, note, Bundle.EMPTY, onSave)

        private fun open(
            activity: FragmentActivity,
            habitName: String,
            date: LocalDate,
            color: Int,
            value: Int,
            note: String,
            extras: Bundle,
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
                putAll(extras)
            }
            focusNext = false
            sheet.dismissCurrentAndShow(activity.supportFragmentManager, "xrnNoteSheet")
        }

        /** A number day. [value] and what [onSave] gets are amounts, as Loop's popup had them. */
        fun showNumber(
            activity: FragmentActivity,
            habit: Habit,
            date: LocalDate,
            color: Int,
            value: Double,
            note: String,
            onSave: (Double, String) -> Unit
        ) {
            val stored = (value * 1000).roundToInt()
            val extras = Bundle().apply {
                putBoolean(ARG_NUMBER, true)
                putString(ARG_UNIT, habit.unit)
                putDouble(ARG_TARGET, habit.targetValue / habit.frequency.denominator)
            }
            open(activity, habit.name, date, color, stored, note, extras) { v, n ->
                onSave(if (v == stored) value else v / 1000.0, n)
            }
        }
    }
}
