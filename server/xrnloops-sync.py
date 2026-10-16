#!/usr/bin/env python3
# Copyright (C) 2026 the xrnhabit authors
#
# This file is part of Loop Habit Tracker (the xrnloops fork).
#
# Loop Habit Tracker is free software: you can redistribute it and/or modify it under the terms
# of the GNU General Public License as published by the Free Software Foundation, either version
# 3 of the License, or (at your option) any later version. It is distributed WITHOUT ANY
# WARRANTY; see the GNU General Public License for more details: <http://www.gnu.org/licenses/>.
"""xrnloops sync server: habits and their daily entries, behind HTTP Basic auth.

The phone's xrnloops and the web app (served from `web/`) both read and write here; the
laptop's xrnhabit asks `/api/status` which habits are done. Made to run on a small home server
next to Radicale, published under a path (e.g. /xrnloops) by a reverse proxy such as Tailscale
Funnel. Standard library only, plus `bcrypt` to read an htpasswd file (Radicale's works as-is).

Records are habits (by uuid) and entries (by habit uuid + day). Each carries the writer's
`mtime` (wall-clock ms) and the last write wins per record. Every accepted write takes the next
`seq`, so a client asks for "everything after the seq I last saw".

    POST /api/changes          {"habits": [...], "entries": [...], "ifAbsent": false}
                               -> {"seq", "rejected": {"habits": [...], "entries": [...]}}
                               A rejected record lost to a newer one, which is returned.
                               With ifAbsent, records only fill gaps (a first sync).
    GET  /api/changes?since=N  -> {"seq", "more", "habits": [...], "entries": [...]}
                               &wait=S long-polls up to S seconds for something newer.
    GET  /api/status?date=YYYY-MM-DD
                               -> per habit: done / goal_met / daily / countable, with the
                               meaning of XrnhabitProvider in the Android app.
    GET  /health               200 "ok", no login (for monitoring)
    GET  /, /app.js, ...       the web app (static files, no login; the data needs one)

    habit:  {"uuid", "mtime", "deleted", "data": {"name", "question", "description", "color",
             "freqNum", "freqDen", "type", "targetType", "targetValue", "unit", "archived",
             "position", "reminder": null | {"hour", "minute", "days"}}}
    entry:  {"habit", "date": "YYYY-MM-DD", "value", "notes", "mtime"}

Entry values are Loop's: UNKNOWN=-1, NO=0, YES_AUTO=1, YES_MANUAL=2, SKIP=3; numerical habits
store the amount x1000.

    python3 xrnloops-sync.py --data ~/xrnloops-sync/data --users ~/.config/radicale/users \
        --web ~/xrnloops-sync/web
"""
import argparse
import base64
import calendar
import gzip
import hashlib
import json
import mimetypes
import os
import sqlite3
import sys
import threading
import time
from datetime import date, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

MAX_BODY = 32 * 1024 * 1024
FAIL_DELAY_S = 2  # after a wrong password, as Radicale does
AUTH_CACHE_S = 600  # a bcrypt check costs ~0.3s on a phone; don't pay it per request
MAX_WAIT_S = 55
PAGE_ROWS = 5000
PREFIX = "/xrnloops"

UNKNOWN, NO, YES_AUTO, YES_MANUAL, SKIP = -1, 0, 1, 2, 3
YES_NO, NUMERICAL = 0, 1
AT_LEAST, AT_MOST = 0, 1

args = None
auth_cache = {}  # sha256(user:password) -> (user, expires, users-file mtime)
stores = {}
stores_lock = threading.Lock()


# ---- login ---------------------------------------------------------------------------------

def users_mtime():
    try:
        return os.stat(args.users).st_mtime
    except OSError:
        return 0


def check_login(header):
    """The user name for a valid `Authorization: Basic ...`, else None."""
    if not header or not header.startswith("Basic "):
        return None
    try:
        user, _, password = base64.b64decode(header[6:]).decode("utf-8").partition(":")
    except Exception:
        return None
    key = hashlib.sha256(f"{user}:{password}".encode()).hexdigest()
    mtime = users_mtime()
    hit = auth_cache.get(key)
    if hit and hit[1] > time.time() and hit[2] == mtime:
        return hit[0]
    import bcrypt  # only needed once someone logs in, so the tests can run without it

    try:
        with open(args.users, encoding="utf-8") as f:
            for line in f:
                name, _, hashed = line.strip().partition(":")
                if name == user and hashed and bcrypt.checkpw(password.encode(), hashed.encode()):
                    auth_cache[key] = (user, time.time() + AUTH_CACHE_S, mtime)
                    return user
    except (OSError, ValueError):
        pass
    return None


# ---- storage -------------------------------------------------------------------------------

SCHEMA = """
CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS habits(
    uuid TEXT PRIMARY KEY, data TEXT NOT NULL, mtime INTEGER NOT NULL,
    deleted INTEGER NOT NULL DEFAULT 0, seq INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS entries(
    habit TEXT NOT NULL, date TEXT NOT NULL, value INTEGER NOT NULL, notes TEXT NOT NULL DEFAULT '',
    mtime INTEGER NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY(habit, date));
CREATE INDEX IF NOT EXISTS habits_seq ON habits(seq);
CREATE INDEX IF NOT EXISTS entries_seq ON entries(seq);
"""

HABIT_FIELDS = {
    "name": "", "question": "", "description": "", "color": 8, "freqNum": 1, "freqDen": 1,
    "type": YES_NO, "targetType": AT_LEAST, "targetValue": 0.0, "unit": "", "archived": False,
    "position": 0, "reminder": None,
}


def clean_habit_data(data):
    """Known fields only, with defaults, so a client can't store arbitrary blobs."""
    if not isinstance(data, dict):
        raise ValueError("habit data must be an object")
    out = {}
    for key, default in HABIT_FIELDS.items():
        value = data.get(key, default)
        if key == "reminder":
            if value is not None:
                if not isinstance(value, dict):
                    raise ValueError("reminder must be an object or null")
                value = {k: int(value.get(k, 0)) for k in ("hour", "minute", "days")}
        elif isinstance(default, bool):
            value = bool(value)
        elif isinstance(default, int):
            value = int(value)
        elif isinstance(default, float):
            value = float(value)
        else:
            value = str(value)
        out[key] = value
    if out["freqNum"] <= 0 or out["freqDen"] <= 0:
        raise ValueError("frequency must be positive")
    return out


def parse_day(text):
    y, m, d = (int(p) for p in str(text).split("-"))
    return date(y, m, d)


class Store:
    """One user's habits. All access goes through `lock`; `changed` wakes long-polls."""

    def __init__(self, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.executescript(SCHEMA)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.lock = threading.Lock()
        self.changed = threading.Condition(self.lock)

    def seq(self):
        row = self.db.execute("SELECT value FROM meta WHERE key='seq'").fetchone()
        return row[0] if row else 0

    def _next_seq(self):
        seq = self.seq() + 1
        self.db.execute("INSERT INTO meta(key, value) VALUES('seq', ?) "
                        "ON CONFLICT(key) DO UPDATE SET value=excluded.value", (seq,))
        return seq

    @staticmethod
    def _wins(incoming_mtime, incoming_key, stored_mtime, stored_key, if_absent):
        """Last writer wins; an exact tie goes to the larger content so every server agrees."""
        if if_absent:
            return False
        if incoming_mtime != stored_mtime:
            return incoming_mtime > stored_mtime
        return incoming_key > stored_key

    def apply(self, body):
        if_absent = bool(body.get("ifAbsent"))
        rejected_habits, rejected_entries = [], []
        with self.lock:
            for h in body.get("habits") or []:
                uuid = str(h["uuid"])
                data = json.dumps(clean_habit_data(h.get("data") or {}), sort_keys=True, ensure_ascii=False)
                deleted = 1 if h.get("deleted") else 0
                mtime = int(h["mtime"])
                row = self.db.execute("SELECT data, mtime, deleted FROM habits WHERE uuid=?", (uuid,)).fetchone()
                if row is None:
                    self.db.execute("INSERT INTO habits(uuid, data, mtime, deleted, seq) VALUES(?,?,?,?,?)",
                                    (uuid, data, mtime, deleted, self._next_seq()))
                elif (row[0], row[2]) == (data, deleted):
                    if mtime > row[1]:
                        self.db.execute("UPDATE habits SET mtime=? WHERE uuid=?", (mtime, uuid))
                elif self._wins(mtime, f"{deleted}{data}", row[1], f"{row[2]}{row[0]}", if_absent):
                    self.db.execute("UPDATE habits SET data=?, mtime=?, deleted=?, seq=? WHERE uuid=?",
                                    (data, mtime, deleted, self._next_seq(), uuid))
                else:
                    rejected_habits.append(self._habit_row(uuid))
            for e in body.get("entries") or []:
                habit = str(e["habit"])
                day = parse_day(e["date"]).isoformat()
                value = int(e["value"])
                notes = str(e.get("notes") or "")
                mtime = int(e["mtime"])
                row = self.db.execute("SELECT value, notes, mtime FROM entries WHERE habit=? AND date=?",
                                      (habit, day)).fetchone()
                if row is None:
                    self.db.execute("INSERT INTO entries(habit, date, value, notes, mtime, seq) VALUES(?,?,?,?,?,?)",
                                    (habit, day, value, notes, mtime, self._next_seq()))
                elif (row[0], row[1]) == (value, notes):
                    if mtime > row[2]:
                        self.db.execute("UPDATE entries SET mtime=? WHERE habit=? AND date=?", (mtime, habit, day))
                elif self._wins(mtime, f"{value}:{notes}", row[2], f"{row[0]}:{row[1]}", if_absent):
                    self.db.execute("UPDATE entries SET value=?, notes=?, mtime=?, seq=? WHERE habit=? AND date=?",
                                    (value, notes, mtime, self._next_seq(), habit, day))
                else:
                    rejected_entries.append(self._entry_row(habit, day))
            self.db.commit()
            self.changed.notify_all()
            return {"seq": self.seq(), "rejected": {"habits": rejected_habits, "entries": rejected_entries}}

    def _habit_row(self, uuid):
        row = self.db.execute("SELECT uuid, data, mtime, deleted FROM habits WHERE uuid=?", (uuid,)).fetchone()
        return {"uuid": row[0], "data": json.loads(row[1]), "mtime": row[2], "deleted": bool(row[3])}

    def _entry_row(self, habit, day):
        row = self.db.execute("SELECT habit, date, value, notes, mtime FROM entries WHERE habit=? AND date=?",
                              (habit, day)).fetchone()
        return {"habit": row[0], "date": row[1], "value": row[2], "notes": row[3], "mtime": row[4]}

    def changes(self, since, wait_s=0):
        with self.lock:
            deadline = time.monotonic() + min(max(wait_s, 0), MAX_WAIT_S)
            while self.seq() <= since:
                left = deadline - time.monotonic()
                if left <= 0:
                    break
                self.changed.wait(left)
            latest = self.seq()
            # Page by seq across both tables: stop at the PAGE_ROWS-th change after `since`.
            cut = self.db.execute(
                "SELECT seq FROM (SELECT seq FROM habits WHERE seq>? UNION ALL SELECT seq FROM entries WHERE seq>?) "
                "ORDER BY seq LIMIT 1 OFFSET ?", (since, since, PAGE_ROWS - 1)).fetchone()
            upto = cut[0] if cut else latest
            habits = [
                {"uuid": r[0], "data": json.loads(r[1]), "mtime": r[2], "deleted": bool(r[3])}
                for r in self.db.execute(
                    "SELECT uuid, data, mtime, deleted FROM habits WHERE seq>? AND seq<=? ORDER BY seq", (since, upto))
            ]
            entries = [
                {"habit": r[0], "date": r[1], "value": r[2], "notes": r[3], "mtime": r[4]}
                for r in self.db.execute(
                    "SELECT habit, date, value, notes, mtime FROM entries WHERE seq>? AND seq<=? ORDER BY seq",
                    (since, upto))
            ]
            return {"seq": upto, "more": upto < latest, "habits": habits, "entries": entries}

    def status(self, day):
        with self.lock:
            rows = self.db.execute("SELECT uuid, data FROM habits WHERE deleted=0").fetchall()
            out = []
            for uuid, data in rows:
                habit = json.loads(data)
                entries = {
                    parse_day(d): v
                    for d, v in self.db.execute("SELECT date, value FROM entries WHERE habit=?", (uuid,))
                }
                out.append({"uuid": uuid, "name": habit["name"], "position": habit["position"],
                            "archived": habit["archived"], "numerical": habit["type"] == NUMERICAL,
                            **habit_status(habit, entries, day)})
            out.sort(key=lambda h: (h["position"], h["name"]))
            return {"date": day.isoformat(), "seq": self.seq(), "habits": out}


def store_for(user):
    with stores_lock:
        if user not in stores:
            stores[user] = Store(os.path.join(args.data, "users", user, "loop.sqlite"))
        return stores[user]


# ---- Loop's rules, ported from uhabits-core (EntryList, XrnhabitProvider) ---------------

def month_length(d):
    return calendar.monthrange(d.year, d.month)[1]


def normalized_frequency(num, den):
    return (1, 1) if num == den else (num, den)


def build_intervals(num, den, entries):
    """EntryList.buildIntervals. `entries` is newest first, as (date, value)."""
    filtered = [d for d, v in entries if v == YES_MANUAL]
    intervals = []
    for i in range(num - 1, len(filtered)):
        begin = filtered[i]
        center = filtered[i - num + 1]
        size = den
        if den in (30, 31):
            size = month_length(begin + timedelta(days=1)) if begin.day == month_length(begin) else month_length(begin)
        if (center - begin).days < size:
            intervals.append([begin, center, begin + timedelta(days=size - 1)])
    return intervals


def snap_intervals_together(intervals):
    """EntryList.snapIntervalsTogether, in place."""
    for i in range(1, len(intervals)):
        begin, center, end = intervals[i]
        nxt_begin = intervals[i - 1][0]
        gap_next_to_current = (end - nxt_begin).days
        gap_center_to_end = (end - center).days
        if gap_next_to_current >= 0:
            shift = min(gap_center_to_end, gap_next_to_current + 1)
            intervals[i] = [begin - timedelta(days=shift), center, end - timedelta(days=shift)]


def build_entries_from_intervals(original, intervals):
    """EntryList.buildEntriesFromInterval, as {date: value} with UNKNOWN days left in."""
    if not original:
        return {}
    days = [d for d, _ in original]
    lo = min(days + [iv[0] for iv in intervals])
    hi = max(days + [iv[2] for iv in intervals])
    result = {}
    current = lo
    while current <= hi:
        result[current] = UNKNOWN
        current += timedelta(days=1)
    for begin, _, end in intervals:
        current = begin
        while current <= end:
            result[current] = YES_AUTO
            current += timedelta(days=1)
    for d, v in original:
        result[d] = v if result[d] == UNKNOWN or v in (SKIP, YES_MANUAL) else YES_AUTO
    return result


def computed_entries(habit, entries):
    """Habit.computedEntries for a yes/no habit: what the user entered plus Loop's auto-checks."""
    if habit["type"] == NUMERICAL:
        return dict(entries)
    num, den = normalized_frequency(habit["freqNum"], habit["freqDen"])
    original = sorted(entries.items(), reverse=True)
    intervals = build_intervals(num, den, original)
    snap_intervals_together(intervals)
    return {d: v for d, v in build_entries_from_intervals(original, intervals).items() if v != UNKNOWN}


def habit_status(habit, entries, day):
    numerical = habit["type"] == NUMERICAL
    countable = not numerical or habit["targetType"] == AT_LEAST
    value = entries.get(day, UNKNOWN)
    if value == UNKNOWN:
        done = False
    elif numerical:
        done = countable and value / 1000.0 >= habit["targetValue"]
    else:
        done = value == YES_MANUAL
    if numerical:
        goal_met = done
    else:
        goal_met = computed_entries(habit, entries).get(day, UNKNOWN) in (YES_MANUAL, YES_AUTO)
    num, den = normalized_frequency(habit["freqNum"], habit["freqDen"])
    return {"countable": countable, "done": done, "goal_met": goal_met, "daily": numerical or num == den}


# ---- HTTP ----------------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    server_version = "xrnloops-sync/1"

    def log_message(self, fmt, *a):
        who = self.headers.get("X-Forwarded-For", self.client_address[0]) if self.headers else "-"
        sys.stderr.write(f"{who} {fmt % a}\n")

    def route(self):
        path, _, query = self.path.partition("?")
        if path == PREFIX or path.startswith(PREFIX + "/"):
            path = path[len(PREFIX):] or "/"
        return path, {k: v[-1] for k, v in parse_qs(query).items()}

    def send(self, code, body=b"", ctype="text/plain; charset=utf-8", headers=None):
        if isinstance(body, str):
            body = body.encode()
        if len(body) > 1024 and "gzip" in (self.headers.get("Accept-Encoding") or ""):
            body = gzip.compress(body, 6)
            headers = {**(headers or {}), "Content-Encoding": "gzip"}
        self.send_response(code)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def send_json(self, obj, code=200):
        self.send(code, json.dumps(obj, ensure_ascii=False, separators=(",", ":")), "application/json; charset=utf-8")

    def login(self):
        user = check_login(self.headers.get("Authorization"))
        if not user:
            time.sleep(FAIL_DELAY_S)
            # The web app logs in with its own form: a challenge would pop the browser's dialog.
            challenge = {} if self.headers.get("X-Xrn-Client") else {"WWW-Authenticate": 'Basic realm="xrnloops"'}
            self.send(401, "login required", headers=challenge)
        return user

    def read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            raise ValueError("body missing or too large")
        raw = self.rfile.read(length)
        if (self.headers.get("Content-Encoding") or "").lower() == "gzip":
            raw = gzip.decompress(raw)
        body = json.loads(raw)
        if not isinstance(body, dict):
            raise ValueError("body must be a JSON object")
        return body

    def do_GET(self):
        path, query = self.route()
        if path == "/health":
            return self.send(200, "ok")
        if not path.startswith("/api/"):
            return self.static(path)
        user = self.login()
        if not user:
            return
        store = store_for(user)
        try:
            if path == "/api/changes":
                since = int(query.get("since", 0))
                wait = float(query.get("wait", 0))
                return self.send_json(store.changes(since, wait))
            if path == "/api/status":
                day = parse_day(query["date"]) if "date" in query else date.today()
                return self.send_json(store.status(day))
        except (KeyError, ValueError) as e:
            return self.send(400, f"bad request: {e}")
        self.send(404, "not found")

    def do_POST(self):
        path, _ = self.route()
        user = self.login()
        if not user:
            return
        if path != "/api/changes":
            return self.send(404, "not found")
        try:
            body = self.read_json()
            result = store_for(user).apply(body)
        except (KeyError, ValueError, TypeError) as e:
            return self.send(400, f"bad request: {e}")
        except (OSError, sqlite3.Error) as e:
            sys.stderr.write(f"write failed: {e!r}\n")
            return self.send(500, "server error")
        self.send_json(result)

    def static(self, path):
        if path in ("", "/"):
            path = "/index.html"
        name = os.path.normpath(path.lstrip("/"))
        file = os.path.join(args.web, name)
        if name.startswith("..") or not os.path.isfile(file):
            return self.send(404, "not found")
        with open(file, "rb") as f:
            data = f.read()
        ctype = mimetypes.guess_type(file)[0] or "application/octet-stream"
        if file.endswith(".webmanifest"):
            ctype = "application/manifest+json"
        if ctype.startswith("text/") or ctype in ("application/javascript", "application/json", "image/svg+xml"):
            ctype += "; charset=utf-8"
        self.send(200, data, ctype)


def main():
    global args
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=5234)
    p.add_argument("--data", required=True, help="where the habit databases live")
    p.add_argument("--users", required=True, help="htpasswd file with bcrypt hashes")
    p.add_argument("--web", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "web"),
                   help="the web app's folder")
    args = p.parse_args()
    args.data = os.path.expanduser(args.data)
    args.users = os.path.expanduser(args.users)
    args.web = os.path.abspath(os.path.expanduser(args.web))
    mimetypes.add_type("text/javascript", ".js")
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    srv.daemon_threads = True
    sys.stderr.write(f"xrnloops-sync on http://{args.host}:{args.port}, data in {args.data}\n")
    srv.serve_forever()


if __name__ == "__main__":
    main()
