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

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
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
import org.isoron.uhabits.core.models.AmountPad
import org.isoron.uhabits.core.models.Entry.Companion.NO
import org.isoron.uhabits.core.models.Entry.Companion.SKIP
import org.isoron.uhabits.core.models.Entry.Companion.UNKNOWN
import org.isoron.uhabits.core.models.Entry.Companion.YES_MANUAL
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.dayProgress
import org.isoron.uhabits.core.models.dayTargetEntry
import org.isoron.uhabits.core.models.meetsDayTarget
import org.isoron.uhabits.core.models.stepEntry
import org.isoron.uhabits.databinding.XrnnoteSheetBinding
import org.isoron.uhabits.utils.InterfaceUtils
import org.isoron.uhabits.utils.StyledResources
import org.isoron.uhabits.utils.dismissCurrentAndShow
import org.isoron.uhabits.xrnlook.XrnFonts
import org.isoron.uhabits.xrntap.NumberFlick
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * A habit's day, in a sheet from the bottom. Replaces Loop's checkmark popup and number box
 * everywhere they showed (the list, the habit page's calendar, a reminder's edit). A yes/no day
 * gets Done, Not done, Skip and Clear. A number day gets its amount between − and +, a bar
 * towards the day's target, quick keys for the target, skip and clear, a pad of its own (no
 * text field, so the phone's keyboard never comes up) and Save. Both work in entry values, where
 * an amount is stored ×1000, and the web app's number sheet has the same parts.
 *
 * Named for the day's note, which it had until 2026-11-15. It sets the value only: the day keeps
 * its note ([org.isoron.uhabits.core.commands.CreateRepetitionCommand]). Picking a value saves
 * it. Closing a number sheet any way but Save keeps an amount that was keyed in, so it is never
 * lost; only Save or Clear empties a day, never a box left blank on the way out, and an amount
 * nobody changed writes nothing, so it can't put back one sync changed meanwhile ([AmountPad]).
 */
class NoteSheet : BottomSheetDialogFragment() {

    private var onSave: ((Int) -> Unit)? = null
    private lateinit var view: XrnnoteSheetBinding
    private var saved = false
    private var originalValue = UNKNOWN
    private var color = 0
    private var pad: AmountPad? = null

    override fun getTheme() = R.style.XrnNoteSheet

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Brought back after the activity was rebuilt: what to save to is gone with it.
        if (onSave == null) dismissAllowingStateLoss()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        view = XrnnoteSheetBinding.inflate(inflater, container, false)
        val args = requireArguments()
        color = args.getInt(ARG_COLOR)
        originalValue = args.getInt(ARG_VALUE)

        view.title.text = args.getString(ARG_NAME).orEmpty()
        view.title.setTextColor(color)
        view.title.visibility = if (view.title.text.isBlank()) View.GONE else View.VISIBLE
        view.date.text = describe(LocalDate(args.getInt(ARG_DAYS)))

        if (isNumber) {
            bindNumber()
        } else {
            val faint = res.getColor(R.attr.contrast60)
            addValue(YES_MANUAL, R.string.fa_check, R.string.xrnnote_done, color, color)
            addValue(NO, R.string.fa_times, R.string.xrnnote_not_done, faint, color)
            addValue(SKIP, R.string.fa_skipped, R.string.xrnnote_skip, color, color)
            addValue(UNKNOWN, R.string.fa_question, R.string.xrnnote_clear, faint, color)
        }

        // Edge to edge: keep the last row above the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(view.root) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottomPadding + bottom)
            insets
        }
        return view.root
    }

    private val bottomPadding by lazy { dp(16) }

    private val isNumber get() = requireArguments().getBoolean(ARG_NUMBER)

    private val res by lazy { StyledResources(requireContext()) }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        // A keyboard plugged in types on the pad too.
        if (isNumber) dialog.setOnKeyListener { _, keyCode, event -> onKey(keyCode, event) }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (saved) return
        pad?.toKeep()?.let(::save)
    }

    private fun save(value: Int) {
        saved = true
        onSave?.invoke(value)
    }

    // ---- a number day ------------------------------------------------------------------------

    private val unit get() = requireArguments().getString(ARG_UNIT).orEmpty()

    /** The day's target as an entry stores it (×1000); 0 for none. */
    private val target get() = requireArguments().getInt(ARG_TARGET)

    private val targetType get() =
        if (requireArguments().getBoolean(ARG_AT_MOST)) NumericalHabitType.AT_MOST else NumericalHabitType.AT_LEAST

    /** The amount, − and +, the bar towards the target, the quick keys, the pad and Save. */
    private fun bindNumber() {
        val number = view.number
        val pad = AmountPad(originalValue)
        this.pad = pad
        val step = requireArguments().getInt(ARG_STEP, 1000)
        view.values.visibility = View.GONE
        number.root.visibility = View.VISIBLE
        number.unit.text = unit
        number.unit.visibility = if (unit.isBlank()) View.GONE else View.VISIBLE
        stepButton(number.minus, step, -1)
        stepButton(number.plus, step, 1)

        number.progress.visibility = if (target > 0) View.VISIBLE else View.GONE
        number.target.text = getString(R.string.xrnnote_of_target, shown(target))
        number.bar.track = ColorUtils.setAlphaComponent(color, 0x38)

        if (target > 0) {
            val spoken = getString(R.string.xrnnote_target, "${shown(target)} $unit".trim())
            addChip(target, shown(target), R.drawable.xrn_ic_flag, spoken)
        }
        addChip(SKIP, getString(R.string.xrnnote_skip))
        addChip(UNKNOWN, getString(R.string.xrnnote_clear))
        addPad()

        number.save.backgroundTintList = ColorStateList.valueOf(color)
        number.save.setTextColor(onAccent(color))
        number.save.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(onAccent(color), 0x33))
        number.save.setOnClickListener {
            pad.toSave()?.let(::save)
            dismiss()
        }
        showAmount()
    }

    /** What the pad holds, in the hero, the bar and the figure beside it. */
    private fun showAmount() {
        val pad = pad ?: return
        val number = view.number
        val empty = pad.text.isEmpty()
        val text = if (empty) getString(R.string.xrnnote_no_amount) else pad.text.replace('.', separator)
        number.amount.text = text
        number.amount.setTextColor(res.getColor(if (empty) R.attr.contrast40 else R.attr.contrast100))
        // The dash for no amount is drawn light, so it reads as a gap and never as a figure.
        number.amount.typeface = if (empty) XrnFonts.regular(requireContext()) else XrnFonts.bold(requireContext())
        number.amount.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            when {
                text.length <= 3 -> 56f
                text.length <= 5 -> 48f
                text.length <= 7 -> 40f
                else -> 32f
            }
        )
        number.hero.contentDescription =
            if (empty) getString(R.string.xrnnote_no_amount_spoken) else "$text $unit".trim()
        stackUnit(text)

        val progress = dayProgress(pad.entry, target) ?: return
        val met = meetsDayTarget(pad.entry, target, targetType)
        // "At most": the bar is how much of the day's allowance is used, and past it isn't met.
        val over = targetType == NumericalHabitType.AT_MOST && !met && !empty
        number.bar.fraction = progress
        number.bar.color = if (over) res.getColor(R.attr.contrast60) else color
        number.target.setTextColor(if (met) color else res.getColor(R.attr.contrast60))
        number.target.typeface = if (met) XrnFonts.bold(requireContext()) else XrnFonts.regular(requireContext())
        number.progress.contentDescription =
            getString(R.string.xrnnote_progress, if (empty) "0" else text, "${shown(target)} $unit".trim())
    }

    /** The unit goes under the amount when the two don't fit side by side, as on the web. */
    private fun stackUnit(text: String) {
        val number = view.number
        val hero = number.hero
        // Before the first layout, the room the sheet will give it: its width less the padding and − +.
        val width = hero.width.takeIf { it > 0 }
            ?: (minOf(resources.displayMetrics.widthPixels, dp(SHEET_MAX_DP)) - dp(40) - dp(112))
        val room = width - hero.paddingStart - hero.paddingEnd
        val needed = number.amount.paint.measureText(text) + dp(8) + number.unit.paint.measureText(unit)
        val stacked = unit.isNotBlank() && needed > room
        hero.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        (number.unit.layoutParams as ViewGroup.MarginLayoutParams).marginStart = if (stacked) 0 else dp(8)
        number.unit.requestLayout()
    }

    /** An entry as the sheet shows it: to the thousandth, with the locale's separator. */
    private fun shown(entry: Int) = AmountPad.shown(entry).replace('.', separator)

    private val separator get() = DecimalFormatSymbols.getInstance().decimalSeparator

    /** − or +, round, in the habit's colour; held, it repeats. */
    private fun stepButton(button: TextView, step: Int, steps: Int) {
        val label = NumberFlick.stepLabel(step)
        val sign = if (steps > 0) "+" else "−"
        button.text = if (step == 1000) sign else "$sign$label"
        button.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            when {
                step == 1000 -> 30f
                label.length <= 2 -> 18f
                label.length <= 4 -> 14f
                else -> 11f
            }
        )
        button.setTextColor(color)
        button.background = surface(ColorUtils.setAlphaComponent(color, 0x2E), null, oval = true, ripple = color)
        button.contentDescription = getString(
            if (steps > 0) R.string.xrnnote_add else R.string.xrnnote_take_off,
            "$label $unit".trim()
        )
        holdToRepeat(button) {
            pad?.step(step, steps)
            showAmount()
        }
    }

    /**
     * A tap runs [action] once (as does TalkBack's, or a keyboard's); held, it runs again every
     * [REPEAT_MS] after [HOLD_MS], with a tick each time, until the finger lifts or leaves.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun holdToRepeat(button: View, action: () -> Unit) {
        button.isClickable = true
        button.isFocusable = true
        button.setOnClickListener {
            action()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        var repeated = false
        val repeat = object : Runnable {
            override fun run() {
                repeated = true
                action()
                button.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                button.postDelayed(this, REPEAT_MS)
            }
        }
        fun stop(v: View) {
            v.removeCallbacks(repeat)
            v.isPressed = false
        }
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    repeated = false
                    v.drawableHotspotChanged(event.x, event.y)
                    v.isPressed = true
                    v.postDelayed(repeat, HOLD_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val inside = event.x in 0f..v.width.toFloat() && event.y in 0f..v.height.toFloat()
                    if (!inside) stop(v)
                }
                MotionEvent.ACTION_UP -> {
                    val tapped = v.isPressed && !repeated
                    stop(v)
                    if (tapped) v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> stop(v)
            }
            true
        }
    }

    /** One of the quick keys, all one width: the day's target, Skip, Clear. Each saves at once. */
    private fun addChip(value: Int, label: String, icon: Int? = null, description: String? = null) {
        val context = requireContext()
        // A day with nothing on it has nothing to pick out: "Clear" isn't what it's set to.
        val selected = value == originalValue && value != UNKNOWN
        val ink = when {
            selected -> onAccent(color)
            value == UNKNOWN -> res.getColor(R.attr.contrast60)
            else -> res.getColor(R.attr.contrast100)
        }
        val chip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            background = if (selected) {
                surface(color, null, radius = dp(24).toFloat(), ripple = onAccent(color))
            } else {
                surface(res.getColor(R.attr.cardBgColor), res.getColor(R.attr.xrnRowEdge), radius = dp(24).toFloat())
            }
            isClickable = true
            isFocusable = true
            contentDescription = description ?: label
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                save(value)
                dismiss()
            }
        }
        if (icon != null) {
            chip.addView(
                ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(if (selected) onAccent(color) else color)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) }
            )
        }
        chip.addView(
            TextView(context).apply {
                text = label
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                typeface = XrnFonts.bold(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(ink)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        )
        view.number.chips.addView(
            chip,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = if (view.number.chips.childCount == 0) 0 else dp(8)
            }
        )
    }

    /** 1 to 9, the separator, 0 and ⌫, in rows of three. */
    private fun addPad() {
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", SEPARATOR, "0", BACKSPACE)
        for (row in keys.chunked(3)) {
            val line = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            for (key in row) {
                line.addView(
                    padKey(key),
                    LinearLayout.LayoutParams(0, dp(KEY_DP), 1f).apply {
                        marginStart = if (line.childCount == 0) 0 else dp(8)
                    }
                )
            }
            view.number.pad.addView(
                line,
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = if (view.number.pad.childCount == 0) 0 else dp(8)
                }
            )
        }
    }

    private fun padKey(key: String): View {
        val context = requireContext()
        val ink = res.getColor(R.attr.contrast100)
        val face = surface(res.getColor(R.attr.cardBgColor), res.getColor(R.attr.xrnRowEdge), radius = dp(16).toFloat())
        val view: View = if (key == BACKSPACE) {
            ImageView(context).apply {
                setImageResource(R.drawable.xrn_ic_backspace)
                imageTintList = ColorStateList.valueOf(ink)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = getString(R.string.xrnnote_delete)
                setOnClickListener { press(it, pad?.backspace() == true) }
                // Held, ⌫ empties the box.
                setOnLongClickListener {
                    if (pad?.clear() == true) showAmount()
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    true
                }
            }
        } else {
            TextView(context).apply {
                text = if (key == SEPARATOR) "$separator" else key
                gravity = Gravity.CENTER
                includeFontPadding = false
                // A separator is a speck at the digits' size.
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (key == SEPARATOR) 34f else 26f)
                if (key == SEPARATOR) typeface = XrnFonts.bold(context)
                setTextColor(ink)
                if (key == SEPARATOR) {
                    contentDescription = getString(R.string.xrnnote_decimal)
                    setOnClickListener { press(it, pad?.separator() == true) }
                } else {
                    setOnClickListener { press(it, pad?.digit(key.toInt()) == true) }
                }
            }
        }
        view.background = face
        view.isClickable = true
        view.isFocusable = true
        return view
    }

    /** A key's tick, or the phone's "no" for one that couldn't go in (a fourth decimal, say). */
    private fun press(key: View, accepted: Boolean) {
        if (accepted) {
            key.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showAmount()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            key.performHapticFeedback(HapticFeedbackConstants.REJECT)
        }
    }

    /** A keyboard's digits, separator, ⌫ and Enter, on the pad. */
    private fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        val pad = pad ?: return false
        val digit = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        val separatorKey = keyCode in SEPARATOR_KEYS
        val enter = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (digit < 0 && !separatorKey && !enter && keyCode != KeyEvent.KEYCODE_DEL) return false
        // Enter saves, unless a quick key or Save has the focus (moved there with Tab or the
        // arrows): then it presses that one. A typed key gives the focus to the first key there
        // is, so − or + having it says nothing.
        val focused = dialog?.currentFocus
        val pressesFocused = focused != null && (focused.parent == view.number.chips || focused == view.number.save)
        if (enter && pressesFocused) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when {
            digit >= 0 -> pad.digit(digit)
            separatorKey -> pad.separator()
            enter -> view.number.save.performClick()
            else -> pad.backspace()
        }
        showAmount()
        return true
    }

    // ---- shared --------------------------------------------------------------------------------

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    /** A key's face: [fill] with an optional hairline [edge], and a ripple clipped to its shape. */
    private fun surface(
        fill: Int,
        edge: Int?,
        radius: Float = 0f,
        oval: Boolean = false,
        ripple: Int? = null
    ): Drawable {
        fun shape(color: Int) = GradientDrawable().apply {
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = radius
            setColor(color)
        }
        val face = shape(fill).apply { if (edge != null) setStroke(dp(1), edge) }
        val wash = ColorUtils.setAlphaComponent(ripple ?: res.getColor(R.attr.contrast100), 0x29)
        return RippleDrawable(ColorStateList.valueOf(wash), face, shape(Color.WHITE))
    }

    private fun tile(selected: Boolean, accent: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = 16 * resources.displayMetrics.density
            setColor(if (selected) accent else res.getColor(R.attr.cardBgColor))
            if (!selected) setStroke(dp(1), res.getColor(R.attr.xrnRowEdge))
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
        private const val ARG_NUMBER = "number"
        private const val ARG_UNIT = "unit"
        private const val ARG_TARGET = "target" // the day's target as an entry stores it (×1000)
        private const val ARG_AT_MOST = "atMost"
        private const val ARG_STEP = "step" // the habit's step, an entry too

        private const val SEPARATOR = "."
        private const val BACKSPACE = "⌫"
        private val SEPARATOR_KEYS = setOf(
            KeyEvent.KEYCODE_PERIOD,
            KeyEvent.KEYCODE_COMMA,
            KeyEvent.KEYCODE_NUMPAD_DOT,
            KeyEvent.KEYCODE_NUMPAD_COMMA
        )
        private const val KEY_DP = 58
        private const val SHEET_MAX_DP = 640 // a bottom sheet's widest, on a tablet
        private const val HOLD_MS = 400L
        private const val REPEAT_MS = 90L

        fun show(
            activity: FragmentActivity,
            habitName: String,
            date: LocalDate,
            color: Int,
            value: Int,
            onSave: (Int) -> Unit
        ) = open(activity, habitName, date, color, value, Bundle.EMPTY, onSave)

        private fun open(
            activity: FragmentActivity,
            habitName: String,
            date: LocalDate,
            color: Int,
            value: Int,
            extras: Bundle,
            onSave: (Int) -> Unit
        ) {
            val sheet = NoteSheet()
            sheet.onSave = onSave
            sheet.arguments = Bundle().apply {
                putString(ARG_NAME, habitName)
                putInt(ARG_DAYS, date.daysSince2000)
                putInt(ARG_COLOR, color)
                putInt(ARG_VALUE, value)
                putAll(extras)
            }
            sheet.dismissCurrentAndShow(activity.supportFragmentManager, "xrnNoteSheet")
        }

        /** A number day. [value] and what [onSave] gets are amounts, as Loop's popup had them. */
        fun showNumber(
            activity: FragmentActivity,
            habit: Habit,
            date: LocalDate,
            color: Int,
            value: Double,
            onSave: (Double) -> Unit
        ) {
            val extras = Bundle().apply {
                putBoolean(ARG_NUMBER, true)
                putString(ARG_UNIT, habit.unit)
                // Rounded, the day's share could fall short of it: 5 a week is 0.714… a day.
                putInt(ARG_TARGET, habit.dayTargetEntry())
                putBoolean(ARG_AT_MOST, habit.targetType == NumericalHabitType.AT_MOST)
                putInt(ARG_STEP, habit.stepEntry())
            }
            open(activity, habit.name, date, color, (value * 1000).roundToInt(), extras) { onSave(it / 1000.0) }
        }
    }
}
