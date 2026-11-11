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

package org.isoron.uhabits.xrnsync

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateHabitCommand
import org.isoron.uhabits.core.commands.DeleteHabitsCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.EntryList
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.NotificationTray
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two-way sync of every habit and entry with xrnloops-sync on the home server, so the web app
 * and this phone show the same thing (XRNLOOPS.md, "Sync").
 *
 * Local changes are found by diffing the habit list against a snapshot of what the server last
 * agreed to (`files/xrnsync-snapshot.json`), not by watching commands: that also catches a
 * backup import, which bypasses [CommandRunner]. Each goes out stamped with when it was made
 * ([XrnSyncEdits]), not when it is sent, so a change made offline loses to a later one made
 * elsewhere, and an import counts as old. Remote changes are applied directly on the one
 * sync thread, never queued behind it, so a diff can't run between "server sent X" and "X is
 * stored" and push the old value back. A record changed here since this sync read it is left
 * alone; it goes out on the next round.
 *
 * A change made here goes out within seconds, and a background job ([XrnSyncJob.schedulePush])
 * sends it even if Android ends this process first. Changes from elsewhere arrive at once while
 * one of this app's screens is open ([waitForChange]), and otherwise with the next sync.
 */
class XrnSync(
    context: Context,
    private val habitList: HabitList,
    private val modelFactory: ModelFactory,
    private val commandRunner: CommandRunner,
    private val preferences: Preferences,
    private val notificationTray: NotificationTray
) : CommandRunner.Listener {

    val settings = XrnSyncSettings(context)
    private val appContext = context.applicationContext
    private val snapshotFile = File(context.filesDir, "xrnsync-snapshot.json")
    private val edits = XrnSyncEdits(File(context.filesDir, "xrnsync-edits.txt"))

    /** Imports under way ([beginImport]): no round runs meanwhile. */
    private val imports = AtomicInteger()

    /**
     * Every habit this process has had in its database. One of these gone from it was deleted
     * here; one the snapshot has that never was here was lost with a database ([planPush]).
     */
    private val seenHere: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "xrnsync").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private var pending: ScheduledFuture<*>? = null
    private var lastResumeSyncMs = 0L

    /** How far the server's feed has been read, for [waitForChange]; -1 until known. */
    @Volatile
    private var feedSeq = -1L

    // The long-poll's backoff after a failure: see waitForChange and pollBackoffMs.
    @Volatile
    private var pollFailures = 0

    @Volatile
    private var pollHoldUntil = 0L

    @Volatile
    private var pollFailedWith = ""

    @Volatile
    private var screensOpen = 0
    private var watcher: Thread? = null

    /** Applied changes are announced as this, so widgets and lists refresh but we don't re-sync. */
    object SyncedFromServer : Command {
        override fun run() = Unit
    }

    fun start(app: Application) {
        instance = this
        for (habit in habitList) habit.uuid?.let(seenHere::add)
        commandRunner.addListener(this)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                screensOpen++
                watchWhileOpen()
                val now = System.currentTimeMillis()
                if (now - lastResumeSyncMs < RESUME_THROTTLE_MS) return
                lastResumeSyncMs = now
                requestSync(0)
            }

            override fun onActivityPaused(activity: Activity) {
                screensOpen = maxOf(0, screensOpen - 1)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        requestSync(START_DELAY_MS)
        if (settings.isConfigured) XrnSyncJob.schedule(appContext)
    }

    override fun onCommandFinished(command: Command) {
        if (command === SyncedFromServer) return
        val now = System.currentTimeMillis()
        // Even with sync off: a snapshot from before must not take them for a lost database.
        if (command is DeleteHabitsCommand) edits.noteDeleted(command.selected.mapNotNull { it.uuid }, now)
        if (!settings.isConfigured) return
        // When it was made, for its stamp: it may only get out hours from now.
        edits.noteChanged(XrnSyncEdits.changedBy(command, habitList), now)
        requestSync(DEBOUNCE_MS)
        // In case Android ends this process before then (it does, right after the app is left).
        XrnSyncJob.schedulePush(appContext)
    }

    /**
     * Waits up to [waitS] seconds for the server to have anything this phone hasn't pulled yet,
     * then syncs it in; true if something came. Blocks the watcher, never the sync thread, so a
     * tick here still goes out meanwhile.
     */
    private fun waitForChange(waitS: Int): Boolean {
        if (!settings.isConfigured) return false
        val key = credentialsKey()
        val holdMs = pollHoldUntil - SystemClock.elapsedRealtime()
        if (holdMs > 0 && key == pollFailedWith) {
            // A recent poll failed. The watcher polls again straight away, so sit out the wait
            // asked for instead of asking.
            try {
                Thread.sleep(minOf(holdMs, waitS * 1000L))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return false
        }
        return try {
            if (feedSeq < 0) feedSeq = Snapshot.load(snapshotFile).seq
            val page = XrnSyncApi(settings.url, settings.user, settings.password).pull(feedSeq, waitS)
            pollFailures = 0
            pollHoldUntil = 0
            val news = page.getJSONArray("habits").length() > 0 || page.getJSONArray("entries").length() > 0
            if (!news) feedSeq = maxOf(feedSeq, page.getLong("seq"))
            // A sync that failed brought nothing in, and the next poll would answer at once.
            news && syncBlocking(WATCH_SYNC_MS)
        } catch (e: Exception) {
            Log.w(TAG, "waiting for changes failed", e)
            pollFailures++
            pollFailedWith = key
            pollHoldUntil = SystemClock.elapsedRealtime() + pollBackoffMs(e, pollFailures)
            false
        }
    }

    /**
     * How long the long-poll stays away after its [failures]th failure in a row. A wrong password
     * won't fix itself, and the server counts each try towards locking this address out (then
     * answers 429 for 15 minutes), so those wait until the settings change (see [credentialsKey]);
     * anything else (no network, server down) backs off from 15 s to 5 minutes.
     */
    private fun pollBackoffMs(e: Exception, failures: Int): Long = when {
        e is XrnSyncApi.AuthException -> AUTH_RETRY_MS
        e is XrnSyncApi.ServerException && e.code == 429 -> LOCKED_OUT_RETRY_MS
        else -> minOf(WATCH_RETRY_MS shl minOf(failures - 1, 5), WATCH_RETRY_MAX_MS)
    }

    /** What a failed poll's hold applies to: new settings (a corrected password) end it at once. */
    private fun credentialsKey(): String =
        "${settings.url}\n${settings.user}\n${settings.password.hashCode()}"

    /** While one of our screens is open, ticks made in the web app show up here at once. */
    @Synchronized
    private fun watchWhileOpen() {
        if (!settings.isConfigured || watcher?.isAlive == true) return
        watcher = Thread({
            while (keepWatching()) {
                val start = SystemClock.elapsedRealtime()
                // Failures hold back inside waitForChange; this catches a server (or a proxy in
                // front of it) that answers at once instead of holding the poll open.
                if (!waitForChange(WATCH_S) && SystemClock.elapsedRealtime() - start < 2_000) {
                    Thread.sleep(WATCH_RETRY_MS)
                }
            }
        }, "xrnsync-watch").apply {
            isDaemon = true
            start()
        }
    }

    /** Locked with [watchWhileOpen], so a resume either sees the watcher gone or keeps it going. */
    @Synchronized
    private fun keepWatching(): Boolean {
        if (screensOpen == 0) watcher = null
        return screensOpen > 0
    }

    /** Syncs after [delayMs], replacing a sync already waiting (so a burst of ticks is one round). */
    @Synchronized
    fun requestSync(delayMs: Long) {
        if (!settings.isConfigured) return
        pending?.cancel(false)
        pending = executor.schedule({ runSync() }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** Syncs now and waits up to [timeoutMs]; true if it finished and worked. Off the main thread. */
    fun syncBlocking(timeoutMs: Long): Boolean {
        if (!settings.isConfigured) return false
        val future = executor.submit<Boolean> { runSync() }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            future.cancel(false) // still queued behind a slow round: don't let these pile up
            false
        }
    }

    /**
     * Before a backup is imported, off the main thread: holds off sync until [endImport], after a
     * round already under way has finished, so none of the import goes out as a change made now.
     * What it returns is for [endImport].
     */
    internal fun beginImport(): ImportBase {
        imports.incrementAndGet()
        try {
            executor.submit { }.get(IMPORT_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "importing while a sync is still under way", e)
        }
        return ImportBase(readLocal(), System.currentTimeMillis())
    }

    /** After the import: what it changed is old, and goes out filling gaps only ([stampFor]). */
    internal fun endImport(base: ImportBase) {
        try {
            if (settings.isConfigured) {
                // A change made here meanwhile was noted with its own time, and keeps it.
                val noted = edits.changes()
                val keys = importedKeys(base.local, readLocal()).filter { (noted[it] ?: -1) < base.started }
                edits.noteImported(keys)
            }
        } finally {
            imports.decrementAndGet()
            requestSync(0)
        }
    }

    internal class ImportBase(val local: LocalState, val started: Long)

    private fun runSync(): Boolean {
        // An import is under way: endImport syncs once it's over.
        if (imports.get() > 0) return false
        return try {
            val summary = sync()
            settings.recordAttempt(true, summary)
            true
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            val locked = e is XrnSyncApi.ServerException && e.code == 429
            val message = when {
                e is XrnSyncApi.AuthException -> "Wrong user name or password"
                locked -> "Locked out after wrong passwords; the server waits 15 minutes"
                e is XrnSyncApi.ServerException -> "The server answered ${e.code}"
                e is IOException -> "Can't reach the server (${e.message})"
                else -> "Failed: $e"
            }
            settings.recordAttempt(false, message, needsYou = e is XrnSyncApi.AuthException || locked)
            false
        }
    }

    // ---- the round ---------------------------------------------------------------------

    /** [started]: when the round read the phone's state ([before]). */
    private class Round(val before: LocalState, var snap: Snapshot, val started: Long) {
        var serverDayStartHour: Int? = null
        var applied = 0
        var refetchAll = false
        val touched = mutableSetOf<Habit>()
        var listChanged = false

        /** Entries read before their habit, which any edit to it moves later in the feed. */
        val early = mutableListOf<JSONObject>()

        /** Reminders that no longer have anything to ask: answered or deleted elsewhere. */
        val settled = mutableSetOf<Habit>()
    }

    private fun sync(): String {
        val api = XrnSyncApi(settings.url, settings.user, settings.password)
        val started = System.currentTimeMillis()
        val before = readLocal()
        val round = Round(before, agreed(), started)
        try {
            var restored = false
            var sent = try {
                push(api, round)
            } catch (e: XrnSyncApi.StaleEpochException) {
                restored = true // refused before anything was written
                0
            }
            if (restored || !pull(api, round)) {
                // The server went back or was wiped: it may have lost anything we've sent. What
                // we agreed on still says when each record was written, and what was deleted.
                Log.w(TAG, "the server's history went back: sending everything again")
                round.snap.startOver()
                sent += push(api, round)
                pull(api, round)
            }
            if (round.refetchAll) {
                round.snap.seq = 0
                pull(api, round)
            }
            if (!round.snap.initialized) {
                round.snap.initialized = true
                round.snap.save(snapshotFile)
            }
            feedSeq = round.snap.seq
            // Loop's "Extend the day past midnight": the web app and the laptop need it to agree on "today".
            val dayStart = preferences.midnightDelayHours
            if (round.serverDayStartHour != null && round.serverDayStartHour != dayStart) {
                api.push(JSONObject().put("dayStartHour", dayStart).put("habits", JSONArray()).put("entries", JSONArray()))
            }
            return "Sent $sent, received ${round.applied}"
        } finally {
            // Even if the round failed: the next one won't see these as new, so it's now or never.
            round.touched.forEach { it.recompute() }
            if (round.touched.isNotEmpty() || round.listChanged) {
                habitList.resort()
                main.post {
                    round.settled.forEach(notificationTray::cancel)
                    commandRunner.notifyListeners(SyncedFromServer)
                }
            }
        }
    }

    /**
     * The snapshot to diff against: blank if there's none or it can't be read, which makes this a
     * first sync, where only what was noted here keeps its stamp ([planPush]). Its cursor is one
     * server's and account's, so another one starts over; what it agreed to still says when each
     * record was written and what was deleted, so everything goes again with those stamps. A
     * habit it has that the database lost isn't taken for a deletion either: see [planPush].
     */
    private fun agreed(): Snapshot {
        val server = "${settings.url}\n${settings.user}"
        val snap = Snapshot.load(snapshotFile)
        if (snap.server != null && snap.server != server) {
            Log.w(TAG, "another server or account: sending everything again")
            snap.startOver()
        }
        snap.server = server
        return snap
    }

    private fun push(api: XrnSyncApi, round: Round): Int {
        val snap = round.snap
        val noted = edits.changes()
        val (habits, entries, lost) = planPush(round.before, snap, noted, System.currentTimeMillis(), edits.deletedHere(), seenHere)
        if (lost.isNotEmpty()) {
            // Not deleted here: the database lost them (HabitsApplication starts a new one when
            // it can't open the old). Sent as deletions they'd be gone from the web app and
            // xrnrule too; read the feed from the start instead, and they come back.
            Log.w(TAG, "${lost.size} habits missing from the database, not deleted here: fetching them again")
            for (uuid in lost) {
                snap.habits.remove(uuid)
                snap.entries.keys.removeAll { it.startsWith("$uuid|") }
            }
            round.refetchAll = true
        }
        // Noted before this round read the phone, yet the same as what was agreed: undone since.
        edits.forgetAllBut(habits.map { it.uuid }.toSet() + entries.map { it.key }, round.started)
        if (habits.isEmpty() && entries.isEmpty()) return 0.also { resent(snap) }

        // Habits go first, so the server (and the web app reading its feed) knows an entry's habit.
        val chunks = entries.chunked(PUSH_CHUNK).ifEmpty { listOf(emptyList()) }
        for ((i, chunk) in chunks.withIndex()) {
            val sentHabits = if (i == 0) habits else emptyList()
            val body = JSONObject()
                .put("habits", JSONArray(sentHabits.map { it.toJson() }))
                .put("entries", JSONArray(chunk.map { it.toJson() }))
            // The history this was made from: if the server has gone back since, it says so
            // (StaleEpochException) instead of taking it, which would hide that from every client.
            snap.epoch?.let { body.put("epoch", it).put("since", snap.seq) }
            val result = api.push(body)
            val rejected = result.getJSONObject("rejected")
            val lostHabits = rejected.getJSONArray("habits").objects().map { it.getString("uuid") }.toSet()
            val lostEntries = rejected.getJSONArray("entries").objects()
                .map { "${it.getString("habit")}|${it.getString("date")}" }.toSet()
            for (h in sentHabits) {
                if (h.uuid in lostHabits) continue
                if (h.deleted) {
                    snap.habits.remove(h.uuid)
                    snap.entries.keys.removeAll { it.startsWith("${h.uuid}|") }
                    snap.tombstones[h.uuid] = h.mtime
                } else {
                    snap.habits[h.uuid] = Stamped(h.data!!, h.mtime)
                    snap.tombstones.remove(h.uuid)
                }
            }
            for (e in chunk) if (e.key !in lostEntries) snap.entries[e.key] = Stamped(e.data, e.mtime)
            // What we lost to a newer write elsewhere comes back as the winner: take it.
            applyRemote(rejected.getJSONArray("habits"), rejected.getJSONArray("entries"), round)
            snap.save(snapshotFile)
            // Taken, or beaten by what is now here: either way it's settled.
            val sent = sentHabits.map { it.uuid } + chunk.map { it.key }
            edits.settle(sent.mapNotNull { key -> noted[key]?.let { key to it } }.toMap(), sentHabits.filter { it.deleted }.map { it.uuid })
        }
        resent(snap)
        return habits.size + entries.size
    }

    /** Everything went again after starting over: from now on only what changes goes. */
    private fun resent(snap: Snapshot) {
        if (!snap.resend) return
        snap.resend = false
        snap.save(snapshotFile)
    }

    /** Reads the feed to its end; false, with nothing read, if the server has started over. */
    private fun pull(api: XrnSyncApi, round: Round): Boolean {
        val snap = round.snap
        var safeSeq = snap.seq
        do {
            val page = api.pull(snap.seq, epoch = snap.epoch)
            val epoch = if (page.has("epoch")) page.getLong("epoch") else null
            if (epoch != null && snap.epoch != null && epoch != snap.epoch) return false
            snap.epoch = epoch ?: snap.epoch
            applyRemote(page.getJSONArray("habits"), page.getJSONArray("entries"), round)
            snap.seq = page.getLong("seq")
            if (page.has("dayStartHour")) round.serverDayStartHour = page.getInt("dayStartHour")
            // The saved cursor stays behind anything still waiting for its habit, so a failure
            // on a later page reads it again instead of losing it.
            if (round.early.isEmpty()) safeSeq = snap.seq
            if (page.optBoolean("more")) snap.save(snapshotFile, safeSeq)
        } while (page.optBoolean("more"))
        val early = round.early.toList()
        round.early.clear()
        applyRemote(JSONArray(), JSONArray(early), round)
        round.early.clear() // still no habit: it's gone, and its entries with it
        snap.save(snapshotFile)
        return true
    }

    private fun applyRemote(habits: JSONArray, entries: JSONArray, round: Round) {
        val (before, snap) = round.before to round.snap
        for (h in habits.objects()) {
            val uuid = h.getString("uuid")
            val mtime = h.getLong("mtime")
            val data = try {
                HabitData.fromJson(h.getJSONObject("data"))
            } catch (e: Exception) {
                Log.w(TAG, "skipping habit $uuid: $e")
                continue
            }
            val current = habitList.getByUUID(uuid)
            val currentData = current?.let(HabitData::of)
            if (currentData != before.habits[uuid]) continue // changed here meanwhile: ours goes out next
            if (h.optBoolean("deleted")) {
                if (current != null) {
                    DeleteHabitsCommand(habitList, listOf(current)).run()
                    round.settled += current
                    round.listChanged = true
                    round.applied++
                }
                before.habits.remove(uuid)
                snap.habits.remove(uuid)
                snap.entries.keys.removeAll { it.startsWith("$uuid|") }
                snap.tombstones[uuid] = mtime
                continue
            }
            if (current == null) {
                val model = modelFactory.buildHabit()
                data.applyTo(model)
                model.uuid = uuid
                CreateHabitCommand(modelFactory, habitList, model).run()
                seenHere += uuid
                // Brought back after it was deleted: its older entries are behind our cursor.
                if (snap.hadDays(uuid)) round.refetchAll = true
                round.listChanged = true
                round.applied++
            } else if (data.copy(position = currentData!!.position) != currentData) {
                // Position stays ours: Loop keeps positions dense, and the next push sends our order.
                val modified = modelFactory.buildHabit()
                modified.copyFrom(current)
                data.applyTo(modified)
                EditHabitCommand(habitList, current.id!!, modified).run()
                round.listChanged = true
                round.applied++
            }
            // What we just applied isn't a change "made here" if a newer version follows it.
            habitList.getByUUID(uuid)?.let { before.habits[uuid] = HabitData.of(it) }
            snap.habits[uuid] = Stamped(data, mtime)
            snap.tombstones.remove(uuid)
        }
        // Written per habit once they're all read: one commit each, however long the history.
        val incoming = mutableMapOf<EntryList, MutableList<Entry>>()
        for (e in entries.objects()) {
            val uuid = e.getString("habit")
            val date = e.getString("date")
            val key = "$uuid|$date"
            val habit = habitList.getByUUID(uuid)
            if (habit == null) {
                round.early += e
                continue
            }
            val day = parseDate(date) ?: continue
            val remote = EntryData(e.getInt("value"), e.optString("notes"))
            val stored = habit.originalEntries.get(day)
            val current = EntryData(stored.value, stored.notes)
            if (current != (before.entries[key] ?: EntryData.EMPTY)) continue // changed here meanwhile
            if (current != remote) {
                incoming.getOrPut(habit.originalEntries) { mutableListOf() } += Entry(day, remote.value, remote.notes)
                round.touched += habit
                if (day == getToday()) round.settled += habit
                round.applied++
                before.entries[key] = remote
            }
            snap.entries[key] = Stamped(remote, e.getLong("mtime"))
        }
        for ((list, batch) in incoming) list.addAll(batch)
    }

    // ---- local state -------------------------------------------------------------------

    internal class LocalState(val habits: MutableMap<String, HabitData>, val entries: MutableMap<String, EntryData>)

    private fun readLocal(): LocalState {
        val habits = mutableMapOf<String, HabitData>()
        val entries = mutableMapOf<String, EntryData>()
        for (habit in habitList) {
            val uuid = habit.uuid ?: continue
            seenHere += uuid
            habits[uuid] = HabitData.of(habit)
            for (entry in habit.originalEntries.getKnown()) {
                entries["$uuid|${entry.date.toCSVString()}"] = EntryData(entry.value, entry.notes)
            }
        }
        return LocalState(habits, entries)
    }

    private fun parseDate(text: String): LocalDate? {
        val parts = text.split("-").map { it.toIntOrNull() ?: return null }
        if (parts.size != 3) return null
        return LocalDate(parts[0], parts[1], parts[2])
    }

    companion object {
        /** Set once the app has started; the provider and the settings screen reach it here. */
        @Volatile
        var instance: XrnSync? = null
            private set

        /**
         * Runs [show] on the main thread once a sync has finished (or [REMINDER_SYNC_MS] has
         * passed), so a habit ticked in the web app since the last sync doesn't ring here; Loop
         * skips the reminder of a habit that's done. Without sync set up, runs it at once.
         */
        fun beforeReminder(receiver: BroadcastReceiver, habit: Habit, show: () -> Unit) {
            val sync = instance
            if (sync == null || !sync.settings.isConfigured) return show()
            val pending = receiver.goAsync()
            Thread({
                sync.syncBlocking(REMINDER_SYNC_MS)
                sync.main.post {
                    try {
                        // Deleted by that sync: the object we were handed doesn't know.
                        if (sync.habitList.getByUUID(habit.uuid) != null) show()
                    } catch (e: RuntimeException) {
                        Log.e(TAG, "couldn't show a reminder", e) // as ReminderReceiver does
                    } finally {
                        pending.finish()
                    }
                }
            }, "xrnsync-reminder").start()
        }

        private const val TAG = "XrnSync"
        private const val REMINDER_SYNC_MS = 8_000L
        private const val DEBOUNCE_MS = 3_000L
        private const val START_DELAY_MS = 2_000L
        private const val RESUME_THROTTLE_MS = 15_000L
        private const val PUSH_CHUNK = 2_000
        private const val WATCH_S = 25
        private const val WATCH_SYNC_MS = 10_000L
        private const val WATCH_RETRY_MS = 15_000L
        private const val WATCH_RETRY_MAX_MS = 5 * 60_000L
        private const val AUTH_RETRY_MS = 30 * 60_000L
        private const val LOCKED_OUT_RETRY_MS = 15 * 60_000L
        private const val IMPORT_WAIT_MS = 60_000L
    }
}

// ---- what a push sends -----------------------------------------------------------------

/** What an import brought in or changed, by [XrnSyncEdits]' keys. A new order alone isn't news. */
internal fun importedKeys(before: XrnSync.LocalState, after: XrnSync.LocalState): List<String> {
    val keys = mutableListOf<String>()
    for ((uuid, data) in after.habits) {
        if (before.habits[uuid]?.copy(position = data.position) != data) keys += uuid
    }
    for ((key, data) in after.entries) if (before.entries[key] != data) keys += key
    return keys
}

/** What [planPush] sends, and the habits it found [lost]. */
internal data class PushPlan(val habits: List<HabitRecord>, val entries: List<EntryRecord>, val lost: Set<String>)

/**
 * The records [local] has that differ from what the server agreed to ([snap]), each stamped by
 * [stampFor] from when it was changed here ([noted], from [XrnSyncEdits]).
 *
 * A habit the snapshot has and the phone doesn't is a deletion only if it was deleted here
 * ([deleted], noted with its time, or [seen] in this process's database). Otherwise the database
 * it was in is gone, a new empty one in its place: it is [lost], not deleted. Deleting the last
 * habits used to look like that, when the test was "an empty list under a full snapshot".
 *
 * After starting over ([Snapshot.resend]: a server restored from a backup, or another one) it is
 * everything: what didn't change goes with the stamp it was agreed with, and the habits deleted
 * once agreed go as deleted again, so a server that lost them learns them back and nothing older
 * wins. A blank snapshot (none yet, or unreadable) knows no stamps: what was noted here keeps its
 * own, and the rest, of no known age, only fills gaps ([UNKNOWN_AGE_MTIME]). That used to be the
 * whole first push ("ifAbsent"), so the server's older copy beat newer edits made here, and a
 * habit deleted here came back.
 */
internal fun planPush(
    local: XrnSync.LocalState,
    snap: Snapshot,
    noted: Map<String, Long>,
    now: Long,
    deleted: Map<String, Long>,
    seen: Set<String>
): PushPlan {
    fun stamp(key: String, previous: Long?): Long {
        val changedAt = noted[key] ?: deleted[key]
        if (changedAt == null && previous == null && !snap.initialized) return UNKNOWN_AGE_MTIME
        return stampFor(changedAt, previous, now)
    }

    val habits = mutableListOf<HabitRecord>()
    for ((uuid, data) in local.habits) {
        val old = snap.habits[uuid]
        if (old?.data == data) {
            if (snap.resend) habits += HabitRecord(uuid, data, old.mtime, deleted = false)
            continue
        }
        // Only the order moved (Loop renumbers whenever a habit is added or removed), so this
        // is no newer than what was agreed: an edit from elsewhere since then must beat it.
        val reordered = old != null && old.data.copy(position = data.position) == data
        habits += HabitRecord(uuid, data, if (reordered) old!!.mtime + 1 else stamp(uuid, old?.mtime), deleted = false)
    }
    val lost = mutableSetOf<String>()
    for ((uuid, old) in snap.habits) {
        if (uuid in local.habits) continue
        if (uuid in deleted || uuid in seen) {
            habits += HabitRecord(uuid, old.data, stamp(uuid, old.mtime), deleted = true)
        } else {
            lost += uuid
        }
    }
    if (snap.resend || !snap.initialized) {
        // Deleted here, but the snapshot has no record of it to send: the server may still have it.
        for (uuid in deleted.keys) {
            if (uuid !in local.habits && uuid !in snap.habits) habits += HabitRecord(uuid, null, stamp(uuid, null), deleted = true)
        }
    }
    if (snap.resend) {
        val sent = habits.map { it.uuid }.toSet()
        for ((uuid, mtime) in snap.tombstones) {
            if (uuid !in local.habits && uuid !in snap.habits && uuid !in sent) {
                habits += HabitRecord(uuid, null, mtime, deleted = true)
            }
        }
    }
    val entries = mutableListOf<EntryRecord>()
    for ((key, data) in local.entries) {
        val old = snap.entries[key]
        if ((old?.data ?: EntryData.EMPTY) != data) {
            entries += EntryRecord(key, data, stamp(key, old?.mtime))
        } else if (snap.resend && old != null) {
            entries += EntryRecord(key, data, old.mtime)
        }
    }
    for ((key, old) in snap.entries) {
        if (key in local.entries || key.substringBefore('|') !in local.habits) continue
        when {
            old.data != EntryData.EMPTY -> entries += EntryRecord(key, EntryData.EMPTY, stamp(key, old.mtime))
            // Cleared once that was agreed: a server back from a backup may have the old value.
            snap.resend -> entries += EntryRecord(key, EntryData.EMPTY, old.mtime)
        }
    }
    return PushPlan(habits, entries, lost)
}

/**
 * The mtime a record changed here goes out with, the server keeping the newest per record: when
 * the change was made ([changedAt]), or [now] for one nobody noted, as before; and always past
 * the version it was made on ([previous]), so a phone clock that runs behind can't lose a tick.
 * A record an import brought in ([XrnSyncEdits.FROM_BACKUP]) is older than anything the server
 * has, so it fills a gap but replaces nothing.
 */
internal fun stampFor(changedAt: Long?, previous: Long?, now: Long): Long = when (changedAt) {
    XrnSyncEdits.FROM_BACKUP -> BACKUP_MTIME
    null -> maxOf(now, (previous ?: 0) + 1)
    else -> maxOf(changedAt, (previous ?: 0) + 1)
}

/** The stamp of a record from a backup: older than any write the server can have. */
internal const val BACKUP_MTIME = 1L

/** The stamp of a record nothing says the age of: like one from a backup, it only fills a gap. */
internal const val UNKNOWN_AGE_MTIME = BACKUP_MTIME

// ---- records ---------------------------------------------------------------------------

data class Stamped<T>(val data: T, val mtime: Long)

data class EntryData(val value: Int, val notes: String) {
    companion object {
        val EMPTY = EntryData(Entry.UNKNOWN, "")
    }
}

data class ReminderData(val hour: Int, val minute: Int, val days: Int)

/** A habit's synced fields, in the server's terms (`server/xrnloops-sync.py`). */
data class HabitData(
    val name: String,
    val question: String,
    val description: String,
    val color: Int,
    val freqNum: Int,
    val freqDen: Int,
    val type: Int,
    val targetType: Int,
    val targetValue: Double,
    val unit: String,
    val archived: Boolean,
    val position: Int,
    val reminder: ReminderData?
) {
    fun applyTo(habit: Habit) {
        habit.name = name
        habit.question = question
        habit.description = description
        habit.color = PaletteColor(color.coerceIn(0, 19))
        habit.frequency = Frequency(freqNum, freqDen)
        habit.type = HabitType.fromInt(type)
        habit.targetType = NumericalHabitType.fromInt(targetType)
        habit.targetValue = targetValue
        habit.unit = unit
        habit.isArchived = archived
        habit.reminder = reminder?.let { Reminder(it.hour, it.minute, WeekdayList(it.days)) }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("question", question)
        .put("description", description)
        .put("color", color)
        .put("freqNum", freqNum)
        .put("freqDen", freqDen)
        .put("type", type)
        .put("targetType", targetType)
        .put("targetValue", targetValue)
        .put("unit", unit)
        .put("archived", archived)
        .put("position", position)
        .put(
            "reminder",
            reminder?.let { JSONObject().put("hour", it.hour).put("minute", it.minute).put("days", it.days) }
                ?: JSONObject.NULL
        )

    companion object {
        fun of(h: Habit) = HabitData(
            name = h.name,
            question = h.question,
            description = h.description,
            color = h.color.paletteIndex,
            freqNum = h.frequency.numerator,
            freqDen = h.frequency.denominator,
            type = h.type.value,
            targetType = h.targetType.value,
            targetValue = h.targetValue,
            unit = h.unit,
            archived = h.isArchived,
            position = h.position,
            reminder = h.reminder?.let { ReminderData(it.hour, it.minute, it.days.toInteger()) }
        )

        fun fromJson(o: JSONObject): HabitData {
            val num = o.optInt("freqNum", 1)
            val den = o.optInt("freqDen", 1)
            val type = o.optInt("type", HabitType.YES_NO.value)
            val targetType = o.optInt("targetType", NumericalHabitType.AT_LEAST.value)
            HabitType.fromInt(type) // throws on a value this Loop doesn't know
            NumericalHabitType.fromInt(targetType)
            require(num > 0 && den > 0) { "bad frequency $num/$den" }
            val r = o.optJSONObject("reminder")
            return HabitData(
                name = o.optString("name"),
                question = o.optString("question"),
                description = o.optString("description"),
                color = o.optInt("color", 8),
                // Loop stores "n times in n days" as every day; match it so it doesn't look changed.
                freqNum = if (num == den) 1 else num,
                freqDen = if (num == den) 1 else den,
                type = type,
                targetType = targetType,
                targetValue = o.optDouble("targetValue", 0.0),
                unit = o.optString("unit"),
                archived = o.optBoolean("archived"),
                position = o.optInt("position"),
                reminder = r?.let { ReminderData(it.optInt("hour"), it.optInt("minute"), it.optInt("days")) }
            )
        }
    }
}

/** [data] is null for a deletion the snapshot has no record of: the server keeps what it has. */
internal data class HabitRecord(val uuid: String, val data: HabitData?, val mtime: Long, val deleted: Boolean) {
    fun toJson(): JSONObject = JSONObject()
        .put("uuid", uuid)
        .put("mtime", mtime)
        .put("deleted", deleted)
        .put("data", data?.toJson() ?: JSONObject())
}

internal data class EntryRecord(val key: String, val data: EntryData, val mtime: Long) {
    fun toJson(): JSONObject = JSONObject()
        .put("habit", key.substringBefore('|'))
        .put("date", key.substringAfter('|'))
        .put("value", data.value)
        .put("notes", data.notes)
        .put("mtime", mtime)
}

/** What the server last agreed to, with each record's mtime; and how far its feed has been read. */
internal class Snapshot(
    var seq: Long,
    var initialized: Boolean,
    val habits: MutableMap<String, Stamped<HabitData>>,
    val entries: MutableMap<String, Stamped<EntryData>>,
    /** The url and user this was agreed with; null in a snapshot from before it was kept. */
    var server: String? = null,
    /** The server's history this cursor belongs to; a new one means it went back. */
    var epoch: Long? = null,
    /**
     * Habits deleted once that was agreed, with its mtime: what a server restored from a backup
     * has to hear again (none in a snapshot from before 2026-11-10).
     */
    val tombstones: MutableMap<String, Long> = mutableMapOf(),
    /** Started over ([startOver]): the next push sends everything, not just what changed. */
    var resend: Boolean = false
) {
    /**
     * For a server whose history went back, or another one: read its feed from the start, and
     * send everything again with the stamps agreed here (see [planPush]).
     */
    fun startOver() {
        seq = 0
        epoch = null
        resend = true
    }

    /**
     * Whether a habit the feed brings in, and the phone doesn't have, had days here once: it was
     * deleted here and that isn't agreed yet, or its deletion was agreed, here or elsewhere, and
     * its days went with it, off the phone and out of this snapshot. Either way the server still
     * has them, behind the cursor, and only reading the feed from the start brings them back.
     */
    fun hadDays(uuid: String) = uuid in habits || uuid in tombstones

    /** The file as last read or written: most rounds change nothing, and then don't touch it. */
    private var onDisk: String? = null

    fun save(file: File, cursor: Long = seq) {
        val h = JSONObject()
        for ((uuid, s) in habits) h.put(uuid, JSONObject().put("d", s.data.toJson()).put("m", s.mtime))
        val e = JSONObject()
        for ((key, s) in entries) e.put(key, JSONArray().put(s.data.value).put(s.data.notes).put(s.mtime))
        val t = JSONObject()
        for ((uuid, mtime) in tombstones) t.put(uuid, mtime)
        val json = JSONObject().put("seq", cursor).put("initialized", initialized).put("habits", h).put("entries", e)
            .put("server", server ?: JSONObject.NULL).put("epoch", epoch ?: JSONObject.NULL)
            .put("tombstones", t).put("resend", resend)
        val text = json.toString()
        if (text == onDisk) return
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) throw IOException("can't replace $file")
        onDisk = text
    }

    companion object {
        fun fresh(server: String?) = Snapshot(0, false, mutableMapOf(), mutableMapOf(), server)

        fun load(file: File): Snapshot {
            val empty = fresh(null)
            if (!file.exists()) return empty
            return try {
                val text = file.readText()
                val json = JSONObject(text)
                val habits = mutableMapOf<String, Stamped<HabitData>>()
                val h = json.getJSONObject("habits")
                for (uuid in h.keys()) {
                    val o = h.getJSONObject(uuid)
                    habits[uuid] = Stamped(HabitData.fromJson(o.getJSONObject("d")), o.getLong("m"))
                }
                val entries = mutableMapOf<String, Stamped<EntryData>>()
                val e = json.getJSONObject("entries")
                for (key in e.keys()) {
                    val a = e.getJSONArray(key)
                    entries[key] = Stamped(EntryData(a.getInt(0), a.getString(1)), a.getLong(2))
                }
                val tombstones = mutableMapOf<String, Long>()
                json.optJSONObject("tombstones")?.let { t -> for (uuid in t.keys()) tombstones[uuid] = t.getLong(uuid) }
                Snapshot(
                    json.getLong("seq"),
                    json.getBoolean("initialized"),
                    habits,
                    entries,
                    if (json.isNull("server")) null else json.optString("server"),
                    if (json.isNull("epoch")) null else json.optLong("epoch"),
                    tombstones,
                    json.optBoolean("resend")
                ).also { it.onDisk = text }
            } catch (e: Exception) {
                // An unreadable snapshot only costs a first-sync again ("fill gaps"), never data.
                Log.w("XrnSync", "snapshot unreadable, starting over", e)
                empty
            }
        }
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
