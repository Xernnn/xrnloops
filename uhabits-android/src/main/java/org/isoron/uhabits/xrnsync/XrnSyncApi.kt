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

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The calls xrnloops-sync answers (see `server/xrnloops-sync.py`). Blocking; call off the main thread. */
class XrnSyncApi(private val baseUrl: String, user: String, password: String) {
    private val auth = "Basic " + Base64.encodeToString("$user:$password".toByteArray(), Base64.NO_WRAP)

    class AuthException : IOException("the server refused the user name or password")

    /** Changes after [since]; with [waitS], the server holds the answer until there are some (long-poll). */
    fun pull(since: Long, waitS: Int = 0): JSONObject =
        request("GET", "/api/changes?since=$since" + if (waitS > 0) "&wait=$waitS" else "", null, waitS * 1000)

    fun push(body: JSONObject): JSONObject = request("POST", "/api/changes", body)

    private fun request(method: String, path: String, body: JSONObject?, waitMs: Int = 0): JSONObject {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS + waitMs
            conn.setRequestProperty("Authorization", auth)
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) throw AuthException()
            if (code !in 200..299) {
                val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("server answered $code ${text.take(200)}")
            }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TIMEOUT_MS = 20_000
    }
}
