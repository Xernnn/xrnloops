/*
 * Copyright (C) 2026 the xrnhabit authors
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
import android.util.Log
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateHabitCommand
import org.isoron.uhabits.core.commands.DeleteHabitsCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Two-way sync of every habit and entry with xrnloops-sync on the home server, so the web app
 * and this phone show the same thing (XRNLOOPS.md, "Sync").
 *
 * Local changes are found by diffing the habit list against a snapshot of what the server last
 * agreed to (`files/xrnsync-snapshot.json`), not by watching commands: that also catches a
 * backup import, which bypasses [CommandRunner]. Remote changes are applied directly on the one
 * sync thread, never queued behind it, so a diff can't run between "server sent X" and "X is
 * stored" and push the old value back. A record changed here since this sync read it is left
 * alone; it goes out on the next round.
 */
class XrnSync(
    context: Context,
    private val habitList: HabitList,
    private val modelFactory: ModelFactory,
    private val commandRunner: CommandRunner
) : CommandRunner.Listener {

    val settings = XrnSyncSettings(context)
    private val appContext = context.applicationContext
    private val snapshotFile = File(context.filesDir, "xrnsync-snapshot.json")
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "xrnsync").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private var pending: ScheduledFuture<*>? = null
    private var lastResumeSyncMs = 0L

    /** Applied changes are announced as this, so widgets and lists refresh but we don't re-sync. */
    object SyncedFromServer : Command {
        override fun run() = Unit
    }

    fun start(app: Application) {
        instance = this
        commandRunner.addListener(this)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                val now = System.currentTimeMillis()
                if (now - lastResumeSyncMs < RESUME_THROTTLE_MS) return
                lastResumeSyncMs = now
                requestSync(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        requestSync(START_DELAY_MS)
        if (settings.isConfigured) XrnSyncJob.schedule(appContext)
    }

    override fun onCommandFinished(command: Command) {
        if (command !== SyncedFromServer) requestSync(DEBOUNCE_MS)
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
            false
        }
    }

    private fun runSync(): Boolean = try {
        val summary = sync()
        settings.recordAttempt(true, summary)
        true
    } catch (e: Exception) {
        Log.w(TAG, "sync failed", e)
        val message = when (e) {
            is XrnSyncApi.AuthException -> "Wrong user name or password"
            is IOException -> "Can't reach the server (${e.message})"
            else -> "Failed: $e"
        }
        settings.recordAttempt(false, message)
        false
    }

    // ---- the round ---------------------------------------------------------------------

    private class Round(val before: LocalState, val snap: Snapshot) {
        var applied = 0
        var refetchAll = false
        val touched = mutableSetOf<Habit>()
        var listChanged = false
    }

    private fun sync(): String {
        val api = XrnSyncApi(settings.url, settings.user, settings.password)
        val snap = Snapshot.load(snapshotFile)
        val round = Round(readLocal(), snap)
        val sent = push(api, round)
        pull(api, round)
        if (round.refetchAll) {
            snap.seq = 0
            pull(api, round)
        }
        snap.initialized = true
        snap.save(snapshotFile)
        round.touched.forEach { it.recompute() }
        if (round.touched.isNotEmpty() || round.listChanged) {
            habitList.resort()
            main.post { commandRunner.notifyListeners(SyncedFromServer) }
        }
        return "Sent $sent, received ${round.applied}"
    }

    private fun push(api: XrnSyncApi, round: Round): Int {
        val (before, snap) = round.before to round.snap
        val now = System.currentTimeMillis()
        fun stamp(previous: Long?) = maxOf(now, (previous ?: 0) + 1) // our write beats what it replaces

        val habits = mutableListOf<HabitRecord>()
        for ((uuid, data) in before.habits) {
            val old = snap.habits[uuid]
            if (old?.data != data) habits += HabitRecord(uuid, data, stamp(old?.mtime), deleted = false)
        }
        for ((uuid, old) in snap.habits) {
            if (uuid !in before.habits) habits += HabitRecord(uuid, old.data, stamp(old.mtime), deleted = true)
        }
        val entries = mutableListOf<EntryRecord>()
        for ((key, data) in before.entries) {
            val old = snap.entries[key]
            if ((old?.data ?: EntryData.EMPTY) != data) entries += EntryRecord(key, data, stamp(old?.mtime))
        }
        for ((key, old) in snap.entries) {
            val uuid = key.substringBefore('|')
            if (key !in before.entries && uuid in before.habits && old.data != EntryData.EMPTY) {
                entries += EntryRecord(key, EntryData.EMPTY, stamp(old.mtime))
            }
        }
        if (habits.isEmpty() && entries.isEmpty()) return 0

        // Habits go first, so the server (and the web app reading its feed) knows an entry's habit.
        val chunks = entries.chunked(PUSH_CHUNK).ifEmpty { listOf(emptyList()) }
        for ((i, chunk) in chunks.withIndex()) {
            val sentHabits = if (i == 0) habits else emptyList()
            val body = JSONObject()
                .put("ifAbsent", !snap.initialized)
                .put("habits", JSONArray(sentHabits.map { it.toJson() }))
                .put("entries", JSONArray(chunk.map { it.toJson() }))
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
                } else {
                    snap.habits[h.uuid] = Stamped(h.data, h.mtime)
                }
            }
            for (e in chunk) if (e.key !in lostEntries) snap.entries[e.key] = Stamped(e.data, e.mtime)
            // What we lost to a newer write elsewhere comes back as the winner: take it.
            applyRemote(rejected.getJSONArray("habits"), rejected.getJSONArray("entries"), round)
            snap.save(snapshotFile)
        }
        return habits.size + entries.size
    }

    private fun pull(api: XrnSyncApi, round: Round) {
        val snap = round.snap
        do {
            val page = api.pull(snap.seq)
            applyRemote(page.getJSONArray("habits"), page.getJSONArray("entries"), round)
            snap.seq = page.getLong("seq")
            snap.save(snapshotFile)
        } while (page.optBoolean("more"))
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
                    round.listChanged = true
                    round.applied++
                }
                snap.habits.remove(uuid)
                snap.entries.keys.removeAll { it.startsWith("$uuid|") }
                continue
            }
            if (current == null) {
                val model = modelFactory.buildHabit()
                data.applyTo(model)
                model.uuid = uuid
                CreateHabitCommand(modelFactory, habitList, model).run()
                // Brought back after we deleted it: its older entries are behind our cursor.
                if (uuid in snap.habits) round.refetchAll = true
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
            snap.habits[uuid] = Stamped(data, mtime)
        }
        for (e in entries.objects()) {
            val uuid = e.getString("habit")
            val date = e.getString("date")
            val key = "$uuid|$date"
            val habit = habitList.getByUUID(uuid) ?: continue
            val day = parseDate(date) ?: continue
            val remote = EntryData(e.getInt("value"), e.optString("notes"))
            val stored = habit.originalEntries.get(day)
            val current = EntryData(stored.value, stored.notes)
            if (current != (before.entries[key] ?: EntryData.EMPTY)) continue // changed here meanwhile
            if (current != remote) {
                habit.originalEntries.add(Entry(day, remote.value, remote.notes))
                round.touched += habit
                round.applied++
            }
            snap.entries[key] = Stamped(remote, e.getLong("mtime"))
        }
    }

    // ---- local state -------------------------------------------------------------------

    private class LocalState(val habits: Map<String, HabitData>, val entries: Map<String, EntryData>)

    private fun readLocal(): LocalState {
        val habits = mutableMapOf<String, HabitData>()
        val entries = mutableMapOf<String, EntryData>()
        for (habit in habitList) {
            val uuid = habit.uuid ?: continue
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
        fun beforeReminder(receiver: BroadcastReceiver, show: () -> Unit) {
            val sync = instance
            if (sync == null || !sync.settings.isConfigured) return show()
            val pending = receiver.goAsync()
            Thread({
                sync.syncBlocking(REMINDER_SYNC_MS)
                sync.main.post {
                    try {
                        show()
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
    }
}

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

private data class HabitRecord(val uuid: String, val data: HabitData, val mtime: Long, val deleted: Boolean) {
    fun toJson(): JSONObject = JSONObject()
        .put("uuid", uuid)
        .put("mtime", mtime)
        .put("deleted", deleted)
        .put("data", data.toJson())
}

private data class EntryRecord(val key: String, val data: EntryData, val mtime: Long) {
    fun toJson(): JSONObject = JSONObject()
        .put("habit", key.substringBefore('|'))
        .put("date", key.substringAfter('|'))
        .put("value", data.value)
        .put("notes", data.notes)
        .put("mtime", mtime)
}

/** What the server last agreed to, with each record's mtime; and how far its feed has been read. */
private class Snapshot(
    var seq: Long,
    var initialized: Boolean,
    val habits: MutableMap<String, Stamped<HabitData>>,
    val entries: MutableMap<String, Stamped<EntryData>>
) {
    fun save(file: File) {
        val h = JSONObject()
        for ((uuid, s) in habits) h.put(uuid, JSONObject().put("d", s.data.toJson()).put("m", s.mtime))
        val e = JSONObject()
        for ((key, s) in entries) e.put(key, JSONArray().put(s.data.value).put(s.data.notes).put(s.mtime))
        val json = JSONObject().put("seq", seq).put("initialized", initialized).put("habits", h).put("entries", e)
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) throw IOException("can't replace $file")
    }

    companion object {
        fun load(file: File): Snapshot {
            val empty = Snapshot(0, false, mutableMapOf(), mutableMapOf())
            if (!file.exists()) return empty
            return try {
                val json = JSONObject(file.readText())
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
                Snapshot(json.getLong("seq"), json.getBoolean("initialized"), habits, entries)
            } catch (e: Exception) {
                // An unreadable snapshot only costs a first-sync again ("fill gaps"), never data.
                Log.w("XrnSync", "snapshot unreadable, starting over", e)
                empty
            }
        }
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
