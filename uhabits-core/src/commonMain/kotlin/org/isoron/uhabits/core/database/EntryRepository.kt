package org.isoron.uhabits.core.database

import org.isoron.platform.Synchronized
import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.run

data class EntryData(
    var id: Long? = null,
    var habitId: Long? = null,
    var timestamp: Long = 0,
    var value: Int = 0,
    var notes: String = ""
)

/**
 * One instance serves every habit, and its statements hold state between bind and step, so each
 * method takes the repository's lock: xrnloops reads and writes entries from the sync thread and
 * the xrnrule provider's binder threads as well as Loop's own threads.
 */
class EntryRepository(private val db: Database) {
    private val findAllByHabitStmt by lazy {
        db.prepareStatement(
            "SELECT id, habit, timestamp, value, notes FROM Repetitions WHERE habit = ? ORDER BY timestamp DESC"
        )
    }

    private val insertStmt by lazy {
        db.prepareStatement(
            "INSERT INTO Repetitions(habit, timestamp, value, notes) VALUES (?, ?, ?, ?)"
        )
    }

    private val deleteByHabitAndTimestampStmt by lazy {
        db.prepareStatement("DELETE FROM Repetitions WHERE habit = ? AND timestamp = ?")
    }

    private val deleteByHabitStmt by lazy {
        db.prepareStatement("DELETE FROM Repetitions WHERE habit = ?")
    }

    @Synchronized
    fun findAllByHabitId(habitId: Long): List<EntryData> {
        findAllByHabitStmt.reset()
        findAllByHabitStmt.bindLong(1, habitId)
        val results = mutableListOf<EntryData>()
        while (findAllByHabitStmt.step() == StepResult.ROW) {
            results.add(
                EntryData(
                    id = findAllByHabitStmt.getLong(0),
                    habitId = findAllByHabitStmt.getLong(1),
                    timestamp = findAllByHabitStmt.getLong(2),
                    value = findAllByHabitStmt.getInt(3),
                    notes = findAllByHabitStmt.getTextOrNull(4) ?: ""
                )
            )
        }
        return results
    }

    @Synchronized
    fun insert(data: EntryData): Long {
        insertStmt.reset()
        insertStmt.bindLong(1, data.habitId!!)
        insertStmt.bindLong(2, data.timestamp)
        insertStmt.bindInt(3, data.value)
        insertStmt.bindText(4, data.notes)
        insertStmt.step()
        return db.queryLong("SELECT last_insert_rowid()")
    }

    @Synchronized
    fun deleteByHabitIdAndTimestamp(habitId: Long, timestamp: Long) {
        deleteByHabitAndTimestampStmt.reset()
        deleteByHabitAndTimestampStmt.bindLong(1, habitId)
        deleteByHabitAndTimestampStmt.bindLong(2, timestamp)
        deleteByHabitAndTimestampStmt.step()
    }

    @Synchronized
    fun deleteByHabitId(habitId: Long) {
        deleteByHabitStmt.reset()
        deleteByHabitStmt.bindLong(1, habitId)
        deleteByHabitStmt.step()
    }

    @Synchronized
    fun execSQL(sql: String) = db.run(sql)
}
