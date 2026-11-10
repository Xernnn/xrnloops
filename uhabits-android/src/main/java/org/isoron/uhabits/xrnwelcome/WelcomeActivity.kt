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

package org.isoron.uhabits.xrnwelcome

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.format.DateUtils
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.habits.list.REQUEST_SETTINGS
import org.isoron.uhabits.activities.habits.list.RESULT_IMPORT_DATA
import org.isoron.uhabits.xrnrule.XrnruleContract
import org.isoron.uhabits.xrnsync.XrnSync
import org.isoron.uhabits.xrnsync.XrnSyncJob
import org.isoron.uhabits.xrnsync.XrnSyncSettings
import kotlin.concurrent.thread

/**
 * xrnloops' opening sequence, in place of Loop's IntroActivity: shown on the first run and
 * again from the habit list's menu ("Welcome & setup"). Four pages: the mark drawing itself,
 * the loop with xrnrule (blocked, done, open), bringing habits over (home server or a Loop
 * backup), and where the day ends plus reminders. The cards show live state, so opening it
 * again doubles as a check that everything is wired up.
 *
 * "Import file" can't run the import here (that needs the list screen's task plumbing), so it
 * hands [RESULT_IMPORT_DATA] back to the list, which opens the file picker as it does when
 * Settings asks. [open] therefore starts it for a result, with Settings' request code.
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var back: MaterialButton
    private lateinit var next: MaterialButton
    private lateinit var dots: LinearLayout
    private val pages = arrayOfNulls<View>(PAGES)
    private var fromMenu = false
    private var lastPage = 0
    private var syncing = false
    private var askedForNotifications = false

    private val component get() = (application as HabitsApplication).component

    private val notificationPermission =
        registerForActivityResult(RequestPermission()) { refresh(PAGE_DAY) }

    private val backToPrevious = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            pager.currentItem -= 1
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fromMenu = intent.getBooleanExtra(EXTRA_FROM_MENU, false)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.xrnwelcome_activity)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.xrnwelcomeRoot)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        pager = findViewById(R.id.xrnwelcomePager)
        back = findViewById(R.id.xrnwelcomeBack)
        next = findViewById(R.id.xrnwelcomeNext)
        dots = findViewById(R.id.xrnwelcomeDots)
        repeat(PAGES) { dots.addView(View(this)) }

        pager.offscreenPageLimit = PAGES - 1 // four small pages; keep them all, so refresh() can reach them
        pager.adapter = Pages()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position == PAGE_HELLO && lastPage != PAGE_HELLO) playMark()
                lastPage = position
                updateBar(position)
            }
        })
        back.setOnClickListener {
            if (pager.currentItem == PAGE_HELLO) finish() else pager.currentItem -= 1
        }
        next.setOnClickListener {
            if (pager.currentItem == PAGES - 1) finish() else pager.currentItem += 1
        }
        onBackPressedDispatcher.addCallback(this, backToPrevious)
        lastPage = pager.currentItem
        updateBar(pager.currentItem)
    }

    override fun onResume() {
        super.onResume()
        // Back from xrnrule or the notification settings: what they changed shows at once.
        for (page in 0 until PAGES) refresh(page)
    }

    // ---- the frame: dots and buttons ---------------------------------------------------

    private fun updateBar(position: Int) {
        backToPrevious.isEnabled = position > PAGE_HELLO
        back.setText(if (position == PAGE_HELLO) R.string.xrnwelcome_skip else R.string.xrnwelcome_back)
        next.setText(
            when {
                position < PAGES - 1 -> R.string.xrnwelcome_next
                fromMenu -> R.string.xrnwelcome_done
                else -> R.string.xrnwelcome_start
            }
        )
        TransitionManager.beginDelayedTransition(dots)
        val density = resources.displayMetrics.density
        for (i in 0 until PAGES) {
            val dot = dots.getChildAt(i)
            val active = i == position
            dot.layoutParams = LinearLayout.LayoutParams(
                ((if (active) 22 else 8) * density).toInt(),
                (8 * density).toInt()
            ).apply { marginStart = (3 * density).toInt(); marginEnd = marginStart }
            dot.background = GradientDrawable().apply {
                cornerRadius = 4 * density
                setColor(getColor(if (active) R.color.xrn_green else R.color.xrn_surface_high))
            }
        }
    }

    private fun playMark() {
        val mark = pages[PAGE_HELLO]?.findViewById<ImageView>(R.id.xrnwelcomeMark) ?: return
        val drawable = mark.drawable as? AnimatedVectorDrawable ?: return
        drawable.reset()
        mark.postDelayed({ drawable.start() }, MARK_DELAY_MS)
    }

    // ---- pages -------------------------------------------------------------------------

    private inner class Pages : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = PAGES
        override fun getItemViewType(position: Int) = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val page = build(viewType, parent)
            pages[viewType] = page
            if (viewType == PAGE_HELLO) playMark()
            return object : RecyclerView.ViewHolder(page) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            refresh(position)
        }
    }

    private fun build(page: Int, parent: ViewGroup): View {
        val inflater = LayoutInflater.from(this)
        if (page == PAGE_HELLO) return inflater.inflate(R.layout.xrnwelcome_page_hello, parent, false)
        val view = inflater.inflate(R.layout.xrnwelcome_page, parent, false)
        val content = view.findViewById<LinearLayout>(R.id.xrnwelcomePageContent)
        fun heading(@StringRes eyebrow: Int, @StringRes title: Int) {
            view.findViewById<TextView>(R.id.xrnwelcomePageEyebrow).setText(eyebrow)
            view.findViewById<TextView>(R.id.xrnwelcomePageTitle).setText(title)
        }
        fun footnote(@StringRes text: Int) = view.findViewById<TextView>(R.id.xrnwelcomePageFootnote).apply {
            setText(text)
            visibility = View.VISIBLE
        }
        fun card(tag: String, @DrawableRes icon: Int, @StringRes title: Int) {
            val card = inflater.inflate(R.layout.xrnwelcome_card, content, false)
            card.tag = tag
            card.findViewById<ImageView>(R.id.xrnwelcomeCardIcon).setImageResource(icon)
            card.findViewById<TextView>(R.id.xrnwelcomeCardTitle).setText(title)
            content.addView(card)
        }
        when (page) {
            PAGE_LOOP -> {
                heading(R.string.xrnwelcome_loop_eyebrow, R.string.xrnwelcome_loop_title)
                step(content, R.drawable.xrn_ic_lock, R.string.xrnwelcome_step_blocked, R.string.xrnwelcome_step_blocked_body, last = false)
                step(content, R.drawable.xrn_ic_check, R.string.xrnwelcome_step_done, R.string.xrnwelcome_step_done_body, last = false)
                step(content, R.drawable.xrn_ic_lock_open, R.string.xrnwelcome_step_open, R.string.xrnwelcome_step_open_body, last = true)
                card(CARD_XRNRULE, R.drawable.xrn_ic_check, R.string.xrnwelcome_xrnrule_found)
            }
            PAGE_HABITS -> {
                heading(R.string.xrnwelcome_habits_eyebrow, R.string.xrnwelcome_habits_title)
                card(CARD_SERVER, R.drawable.xrn_ic_cloud, R.string.xrnwelcome_server)
                card(CARD_BACKUP, R.drawable.xrn_ic_file, R.string.xrnwelcome_backup)
                footnote(R.string.xrnwelcome_habits_empty)
            }
            PAGE_DAY -> {
                heading(R.string.xrnwelcome_day_eyebrow, R.string.xrnwelcome_day_title)
                card(CARD_LATE, R.drawable.xrn_ic_moon, R.string.xrnwelcome_day_late)
                card(CARD_REMINDERS, R.drawable.xrn_ic_bell, R.string.xrnwelcome_reminders)
                if (!fromMenu) footnote(R.string.xrnwelcome_reopen_hint)
            }
        }
        return view
    }

    private fun step(content: LinearLayout, @DrawableRes icon: Int, @StringRes title: Int, @StringRes body: Int, last: Boolean) {
        val row = LayoutInflater.from(this).inflate(R.layout.xrnwelcome_step, content, false)
        row.findViewById<TextView>(R.id.xrnwelcomeStepTitle).setText(title)
        row.findViewById<TextView>(R.id.xrnwelcomeStepBody).setText(body)
        val image = row.findViewById<ImageView>(R.id.xrnwelcomeStepIcon)
        image.setImageResource(icon)
        if (last) {
            // The payoff gets the solid badge; nothing follows it.
            row.findViewById<View>(R.id.xrnwelcomeStepBadge).setBackgroundResource(R.drawable.xrnwelcome_badge_done)
            image.imageTintList = ContextCompat.getColorStateList(this, R.color.xrn_ink)
            row.findViewById<View>(R.id.xrnwelcomeStepRail).visibility = View.INVISIBLE
        }
        content.addView(row)
    }

    /** Puts the live state into [page]'s cards. Safe to call before the page exists. */
    private fun refresh(page: Int) {
        val view = pages[page] ?: return
        when (page) {
            PAGE_LOOP -> bindXrnrule(Card(view.findViewWithTag(CARD_XRNRULE)))
            PAGE_HABITS -> {
                bindServer(Card(view.findViewWithTag(CARD_SERVER)))
                Card(view.findViewWithTag(CARD_BACKUP)).apply {
                    body(getString(R.string.xrnwelcome_backup_body))
                    action(R.string.xrnwelcome_import) {
                        setResult(RESULT_IMPORT_DATA)
                        finish()
                    }
                }
            }
            PAGE_DAY -> {
                bindLateDay(Card(view.findViewWithTag(CARD_LATE)))
                bindReminders(Card(view.findViewWithTag(CARD_REMINDERS)))
            }
        }
    }

    // ---- cards -------------------------------------------------------------------------

    private inner class Card(val view: View) {
        private val badge: View = view.findViewById(R.id.xrnwelcomeCardBadge)
        private val icon: ImageView = view.findViewById(R.id.xrnwelcomeCardIcon)
        private val title: TextView = view.findViewById(R.id.xrnwelcomeCardTitle)
        private val body: TextView = view.findViewById(R.id.xrnwelcomeCardBody)
        private val button: MaterialButton = view.findViewById(R.id.xrnwelcomeCardAction)
        val switch: CompoundButton = view.findViewById(R.id.xrnwelcomeCardSwitch)

        /** A solid badge says "this is set up"; the tinted one, "not yet" or "nothing to set". */
        fun done(done: Boolean) {
            badge.setBackgroundResource(if (done) R.drawable.xrnwelcome_badge_done else R.drawable.xrnwelcome_badge)
            icon.imageTintList = ContextCompat.getColorStateList(this@WelcomeActivity, if (done) R.color.xrn_ink else R.color.xrn_green)
        }

        fun title(@StringRes text: Int) = title.setText(text)

        fun body(text: CharSequence, error: Boolean = false) {
            body.text = text
            body.setTextColor(getColor(if (error) R.color.xrn_error else R.color.xrn_muted))
        }

        fun action(@StringRes text: Int?, enabled: Boolean = true, onClick: () -> Unit = {}) {
            button.visibility = if (text == null) View.GONE else View.VISIBLE
            if (text == null) return
            button.setText(text)
            button.isEnabled = enabled
            button.setOnClickListener { onClick() }
        }
    }

    private fun bindXrnrule(card: Card) {
        val installed = try {
            packageManager.getPackageInfo(XrnruleContract.XRNRULE_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        card.done(installed)
        card.title(if (installed) R.string.xrnwelcome_xrnrule_found else R.string.xrnwelcome_xrnrule_missing)
        card.body(getString(if (installed) R.string.xrnwelcome_xrnrule_found_body else R.string.xrnwelcome_xrnrule_missing_body))
        val launch = packageManager.getLaunchIntentForPackage(XrnruleContract.XRNRULE_PACKAGE)
        card.action(if (launch != null) R.string.xrnwelcome_open_xrnrule else null) {
            launch?.let { startActivity(it) }
        }
    }

    private fun bindServer(card: Card) {
        val settings = XrnSync.instance?.settings
        if (settings == null) {
            card.done(false)
            card.body(getString(R.string.xrnwelcome_server_off))
            card.action(null)
            return
        }
        val last = settings.lastSync
        val ok = settings.isConfigured && last?.ok == true
        card.done(ok && !syncing)
        when {
            syncing -> card.body(getString(R.string.xrnwelcome_server_syncing))
            !settings.isConfigured -> card.body(getString(R.string.xrnwelcome_server_off))
            last == null -> card.body(getString(R.string.xrnsync_never))
            last.ok -> {
                val count = component.habitList.size()
                val habits = resources.getQuantityString(R.plurals.xrnwelcome_habit_count, count, count)
                val now = System.currentTimeMillis()
                val ago = if (now - last.atMs < DateUtils.MINUTE_IN_MILLIS) {
                    getString(R.string.xrnwelcome_just_now)
                } else {
                    DateUtils.getRelativeTimeSpanString(last.atMs, now, DateUtils.MINUTE_IN_MILLIS)
                }
                card.body(getString(R.string.xrnwelcome_server_ok, settings.user, habits, ago))
            }
            else -> card.body(last.message, error = true)
        }
        card.action(
            if (settings.isConfigured) R.string.xrnwelcome_change else R.string.xrnwelcome_sign_in,
            enabled = !syncing
        ) { showSignIn(settings) }
    }

    private fun bindLateDay(card: Card) {
        val preferences = component.preferences
        card.done(preferences.isMidnightDelayEnabled)
        card.body(getString(R.string.xrnwelcome_day_late_body))
        card.action(null)
        card.switch.visibility = View.VISIBLE
        card.switch.setOnCheckedChangeListener(null)
        card.switch.isChecked = preferences.isMidnightDelayEnabled
        card.switch.setOnCheckedChangeListener { _, checked ->
            preferences.isMidnightDelayEnabled = checked
            card.done(checked)
            // The web app and the laptop read "today" from the hour the phone sends.
            XrnSync.instance?.requestSync(500)
        }
        card.view.setOnClickListener { card.switch.toggle() }
    }

    private fun bindReminders(card: Card) {
        val allowed = NotificationManagerCompat.from(this).areNotificationsEnabled()
        card.done(allowed)
        card.body(getString(if (allowed) R.string.xrnwelcome_reminders_on else R.string.xrnwelcome_reminders_off))
        card.action(if (allowed) null else R.string.xrnwelcome_allow) {
            val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (canAsk && !askedForNotifications) {
                askedForNotifications = true
                notificationPermission.launch(POST_NOTIFICATIONS)
            } else {
                // Denied before (Android won't ask twice) or switched off by hand: only the
                // system screen can turn them back on.
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
        }
    }

    // ---- home server sign-in -----------------------------------------------------------

    /** Address, user and password in one dialog, then a first sync that brings every habit in. */
    private fun showSignIn(settings: XrnSyncSettings) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val font = ResourcesCompat.getFont(this, R.font.xrn_misans_regular)
        fun field(@StringRes hint: Int, value: String, type: Int): TextInputEditText {
            val layout = TextInputLayout(this)
            // Built from the layout's context, which carries its theme overlay; from the
            // activity's, the field keeps its own underline under the outline.
            val input = TextInputEditText(layout.context).apply {
                inputType = type
                typeface = font // a password type switches to monospace
                setText(value)
            }
            layout.apply {
                this.hint = getString(hint)
                if (type and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0) {
                    endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                }
                addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            val params = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
            form.addView(layout, params)
            return input
        }
        val text = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val url = field(R.string.xrnsync_url, settings.url, text or InputType.TYPE_TEXT_VARIATION_URI)
        val user = field(R.string.xrnsync_user, settings.user, text)
        val password = field(R.string.xrnsync_password, settings.password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        (form.getChildAt(0) as TextInputLayout).helperText = getString(R.string.xrnsync_url_hint)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.xrnwelcome_sign_in_title)
            .setView(FrameLayout(this).apply { addView(form) })
            .setPositiveButton(R.string.xrnwelcome_sign_in) { _, _ ->
                settings.dataStore.putString(XrnSyncSettings.KEY_URL, url.text.toString().trim())
                settings.dataStore.putString(XrnSyncSettings.KEY_USER, user.text.toString().trim())
                settings.dataStore.putString(XrnSyncSettings.KEY_PASSWORD, password.text.toString())
                syncNow()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        listOf(url, user, password).firstOrNull { it.text.isNullOrEmpty() }?.requestFocus()
    }

    private fun syncNow() {
        val sync = XrnSync.instance ?: return
        if (!sync.settings.isConfigured || syncing) {
            refresh(PAGE_HABITS)
            return
        }
        XrnSyncJob.schedule(this)
        syncing = true
        refresh(PAGE_HABITS)
        thread(name = "xrnwelcome-sync") {
            sync.syncBlocking(60_000)
            runOnUiThread {
                syncing = false
                if (!isDestroyed) refresh(PAGE_HABITS)
            }
        }
    }

    companion object {
        private const val PAGES = 4
        private const val PAGE_HELLO = 0
        private const val PAGE_LOOP = 1
        private const val PAGE_HABITS = 2
        private const val PAGE_DAY = 3
        private const val CARD_XRNRULE = "xrnrule"
        private const val CARD_SERVER = "server"
        private const val CARD_BACKUP = "backup"
        private const val CARD_LATE = "late"
        private const val CARD_REMINDERS = "reminders"
        private const val MARK_DELAY_MS = 250L
        private const val EXTRA_FROM_MENU = "from_menu"

        /**
         * Opens the welcome screen from the habit list, for a result: "Import file" comes back
         * as Settings' [RESULT_IMPORT_DATA]. [fromMenu] is false for the first run.
         */
        fun open(activity: Activity, fromMenu: Boolean) {
            val intent = Intent(activity, WelcomeActivity::class.java).putExtra(EXTRA_FROM_MENU, fromMenu)
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQUEST_SETTINGS)
        }
    }
}
