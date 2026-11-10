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
package org.isoron.platform.io

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * xrnloops: Android's database as far as the threads go, for tests: one connection, which a
 * transaction keeps for the thread that began it until it ends, and any other statement takes
 * for its step. A thread that waits for it too long fails, so a deadlock fails a test instead of
 * hanging it.
 */
class OneConnection(private val db: Database) : Database {
    private val connection = ReentrantLock()

    private fun take() {
        check(connection.tryLock(5, TimeUnit.SECONDS)) { "no connection for 5 s: a deadlock" }
    }

    override fun prepareStatement(sql: String): PreparedStatement {
        val inner = db.prepareStatement(sql)
        val kind = sql.trimStart().uppercase()
        return object : PreparedStatement by inner {
            override fun step(): StepResult = when {
                kind.startsWith("BEGIN") -> {
                    take() // kept till COMMIT or ROLLBACK
                    inner.step()
                }
                kind.startsWith("COMMIT") || kind.startsWith("ROLLBACK") -> try {
                    inner.step()
                } finally {
                    connection.unlock()
                }
                else -> {
                    take()
                    try {
                        inner.step()
                    } finally {
                        connection.unlock()
                    }
                }
            }
        }
    }

    override fun close() = db.close()
}
