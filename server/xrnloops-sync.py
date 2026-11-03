#!/usr/bin/env python3
# Copyright (C) 2026 the xrnrule authors
#
# This file is part of Loop Habit Tracker (the xrnloops fork).
#
# Loop Habit Tracker is free software: you can redistribute it and/or modify it under the terms
# of the GNU General Public License as published by the Free Software Foundation, either version
# 3 of the License, or (at your option) any later version. It is distributed WITHOUT ANY
# WARRANTY; see the GNU General Public License for more details: <http://www.gnu.org/licenses/>.
"""xrnloops sync server: habits and their daily entries, behind HTTP Basic auth.

The phone's xrnloops and the web app (served from `web/`) both read and write here; the
laptop's xrnrule asks `/api/status` which habits are done. Made to run on a small home server
next to Radicale, published under a path (e.g. /xrnloops) by a reverse proxy such as Tailscale
Funnel. Standard library only, plus `bcrypt` to read an htpasswd file (Radicale's works as-is).

Records are habits (by uuid) and entries (by habit uuid + day). Each carries the writer's
`mtime` (wall-clock ms) and the last write wins per record. Every accepted write takes the next
`seq`, so a client asks for "everything after the seq I last saw". Every record it gets back
carries its `seq`; a write that names the version it was made from (`baseSeq`) is taken only if
that is still the stored version, whatever the clocks say, and is otherwise rejected with the
newer one. xrnrule writes this way, so an amount it adds on top of an old value gets added again
to the new one instead of replacing it. A habit write keeps the stored value of every field it
leaves out, so an older client (xrnloops) can't wipe fields it doesn't know (`icon`, `timer`).

    POST /api/changes          {"habits": [...], "entries": [...], "ifAbsent": false,
                                "dayStartHour": 0}
                               -> {"seq", "rejected": {"habits": [...], "entries": [...]},
                                   "accepted": {"habits": [{"uuid", "seq"}],
                                                "entries": [{"habit", "date", "seq"}]}}
                               A rejected record lost to a newer one, which is returned.
                               An accepted one is stored at that seq.
                               With ifAbsent, records only fill gaps (a first sync).
                               dayStartHour (optional): the phone's "Extend the day past
                               midnight" setting, the hour its new day starts (0 or 3).
    GET  /api/changes?since=N[&epoch=E] -> {"seq", "more", "habits": [...], "entries": [...], "dayStartHour", "epoch"}
                               &wait=S long-polls up to S seconds for something newer.
    GET  /api/status?date=YYYY-MM-DD
                               -> per habit: done / goal_met / daily / countable, with the
                               meaning of XrnruleProvider in the Android app; and
                               dayStartHour, so the laptop asks for Loop's day, not the date.
    POST /api/session          with Basic auth: sets the web app's login cookie (below)
    DELETE /api/session        signs the browser out
    GET  /health               200 "ok", no login (for monitoring)
    GET  /, /app.js, ...       the web app (static files, no login; the data needs one)

The phone sends Basic auth with every request. The web app signs in once and then carries an
HttpOnly cookie, `xrn_session`, so the password never sits where a page's script could read it:
the host is shared with other apps, and a script bug in any of them could read the pages'
storage. The cookie counts only with an `X-Xrn-Client` header, which another site's page can't
send. It's signed with `session.key`, made next to the users file on first start, which
xrnrule-sync shares (its status page is on the same host), so either sign-in works for both. A
new password ends every session; so does deleting session.key.

    habit:  {"uuid", "mtime", "deleted", "seq", "data": {"name", "question", "description",
             "color", "freqNum", "freqDen", "type", "targetType", "targetValue", "unit",
             "archived", "position", "reminder": null | {"hour", "minute", "days"},
             "icon", "timer"}}
    entry:  {"habit", "date": "YYYY-MM-DD", "value", "notes", "mtime", "seq"}

`icon` and `timer` are xrnrule's: an icon name, and whether a numerical habit counts minutes
with its focus timer. Writes may add "baseSeq" to any record (see above).

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
import hmac
import json
import mimetypes
import os
import re
import secrets
import sqlite3
import sys
import threading
import time
import zlib
from datetime import date, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

MAX_BODY = 32 * 1024 * 1024
FAIL_DELAY_S = 2  # after a wrong password, as Radicale does
AUTH_CACHE_S = 600  # a bcrypt check costs ~0.3s on a phone; don't pay it per request
MAX_WAIT_S = 55
FAIL_LIMIT = 10  # wrong logins from one address within FAIL_WINDOW_S, then 429 until they age out
FAIL_WINDOW_S = 15 * 60
PAGE_ROWS = 5000
PREFIX = "/xrnloops"
SESSION_COOKIE = "xrn_session"
SESSION_S = 30 * 86400

UNKNOWN, NO, YES_AUTO, YES_MANUAL, SKIP = -1, 0, 1, 2, 3
YES_NO, NUMERICAL = 0, 1
AT_LEAST, AT_MOST = 0, 1

args = None
auth_cache = {}  # sha256(user:password) -> (user, expires, users-file mtime)
fail_lock = threading.Lock()
failures = {}  # client address -> monotonic times of its recent wrong logins
stores = {}
stores_lock = threading.Lock()
session_key = b""  # signs the web app's login cookies; see load_session_key()

SECURITY_HEADERS = {  # on every answer; main() adds the web app's Content-Security-Policy
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
}


def content_policy(web):
    """The web app's CSP: its own files only, plus index.html's inline scripts by hash."""
    hashes = []
    try:
        with open(os.path.join(web, "index.html"), encoding="utf-8") as f:
            for script in re.findall(r"<script>(.*?)</script>", f.read(), re.S):
                digest = base64.b64encode(hashlib.sha256(script.encode()).digest()).decode()
                hashes.append(f" 'sha256-{digest}'")
    except OSError:
        pass
    return ("default-src 'self'; script-src 'self'" + "".join(hashes) + "; style-src 'self'; img-src 'self' data:; "
            "object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")


# ---- login ---------------------------------------------------------------------------------

def users_mtime():
    try:
        return os.stat(args.users).st_mtime
    except OSError:
        return 0


def client_address(handler):
    """The caller's address. Behind the local proxy (Funnel) that's the first X-Forwarded-For hop;
    the header is only trusted from a loopback peer, since anyone else could make it up."""
    peer = handler.client_address[0]
    forwarded = handler.headers.get("X-Forwarded-For")
    if forwarded and peer in ("127.0.0.1", "::1"):
        return forwarded.split(",")[0].strip()
    return peer


def locked_out(addr):
    """Whether this address has used up its wrong logins for now."""
    now = time.monotonic()
    with fail_lock:
        recent = [t for t in failures.get(addr, ()) if now - t < FAIL_WINDOW_S]
        if recent:
            failures[addr] = recent
        else:
            failures.pop(addr, None)
        return len(recent) >= FAIL_LIMIT


def note_failure(addr):
    now = time.monotonic()
    with fail_lock:
        if len(failures) > 10000:  # a flood from many addresses: forget the stale ones
            for a in [a for a, ts in failures.items() if now - ts[-1] >= FAIL_WINDOW_S]:
                del failures[a]
        failures.setdefault(addr, []).append(now)


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


def load_session_key():
    """The key that signs login cookies: random, made on first start next to the users file, so
    every server reading that file (xrnrule-sync too) takes the others' cookies."""
    path = os.path.join(os.path.dirname(args.users), "session.key")
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        pass
    else:
        with os.fdopen(fd, "wb") as f:
            f.write(os.urandom(32))
    for _ in range(50):  # the other server may be writing it this very moment
        with open(path, "rb") as f:
            key = f.read()
        if len(key) >= 32:
            return key
        time.sleep(0.1)
    raise SystemExit(f"{path} is too short to sign with")


def password_tag(user):
    """A fingerprint of [user]'s password hash, so a new password ends the old sessions."""
    try:
        with open(args.users, encoding="utf-8") as f:
            for line in f:
                name, _, hashed = line.strip().partition(":")
                if name == user and hashed:
                    return hashlib.sha256(hashed.encode()).hexdigest()
    except OSError:
        pass
    return None


def session_mac(body, tag):
    return hmac.new(session_key, f"{body}|{tag}".encode(), hashlib.sha256).hexdigest()


def make_session(user):
    """A login cookie's value for [user]: who, until when, and a signature over both."""
    who = base64.urlsafe_b64encode(user.encode()).decode().rstrip("=")
    body = f"{who}.{int(time.time()) + SESSION_S}"
    return f"{body}.{session_mac(body, password_tag(user))}"


def check_session(cookie_header):
    """The user a request's login cookie names, if it's ours, unexpired and the password unchanged."""
    token = ""
    for part in (cookie_header or "").split(";"):
        name, _, value = part.strip().partition("=")
        if name == SESSION_COOKIE:
            token = value
    try:
        who, expires, mac = token.split(".")
        user = base64.urlsafe_b64decode(who + "=" * (-len(who) % 4)).decode("utf-8")
        if int(expires) < time.time():
            return None
    except ValueError:  # also a bad base64 or UTF-8
        return None
    tag = password_tag(user)
    if tag is None or not hmac.compare_digest(session_mac(f"{who}.{expires}", tag), mac):
        return None
    return user


def session_cookie(value, max_age):
    # Path=/: xrnrule's status page (/xrnrule) is signed in by this too.
    return f"{SESSION_COOKIE}={value}; Path=/; Max-Age={max_age}; HttpOnly; Secure; SameSite=Strict"


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
    "position": 0, "reminder": None, "icon": "", "timer": False,
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

    def day_start_hour(self):
        row = self.db.execute("SELECT value FROM meta WHERE key='day_start_hour'").fetchone()
        return row[0] if row else 0

    def _next_seq(self):
        seq = self.seq() + 1
        self.db.execute("INSERT INTO meta(key, value) VALUES('seq', ?) "
                        "ON CONFLICT(key) DO UPDATE SET value=excluded.value", (seq,))
        return seq

    @staticmethod
    def _wins(incoming_mtime, incoming_key, stored_mtime, stored_key, if_absent, base_seq=None, stored_seq=None):
        """Last writer wins; an exact tie goes to the larger content so every server agrees.

        A write that names the version it was made from wins exactly when that version is still
        the stored one: it saw everything there, so it is newer whatever the clocks say."""
        if if_absent:
            return False
        if base_seq is not None:
            return base_seq == stored_seq
        if incoming_mtime != stored_mtime:
            return incoming_mtime > stored_mtime
        return incoming_key > stored_key

    @staticmethod
    def _base_seq(record):
        value = record.get("baseSeq")
        if value is None:
            return None
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError("baseSeq must be a number")
        return value

    def apply(self, body):
        with self.lock:
            try:
                return self._apply(body)
            except BaseException:
                # Nothing of a write that fails half way stays, or the next one would commit it.
                self.db.rollback()
                raise

    def _apply(self, body):
        """`apply` under the lock."""
        if_absent = bool(body.get("ifAbsent"))
        rejected_habits, rejected_entries = [], []
        accepted_habits, accepted_entries = [], []
        day_start = body.get("dayStartHour")
        if day_start is not None and (isinstance(day_start, bool) or not isinstance(day_start, int) or not 0 <= day_start <= 23):
            raise ValueError("dayStartHour must be an hour, 0 to 23")
        if day_start is not None and day_start != self.day_start_hour():
            self.db.execute("INSERT INTO meta(key, value) VALUES('day_start_hour', ?) "
                            "ON CONFLICT(key) DO UPDATE SET value=excluded.value", (day_start,))
            self._next_seq()  # wakes long-polls, so the web app and the laptop move their "today"
        for h in body.get("habits") or []:
            uuid = str(h["uuid"])
            base_seq = self._base_seq(h)
            incoming = h.get("data") or {}
            if not isinstance(incoming, dict):
                raise ValueError("habit data must be an object")
            deleted = 1 if h.get("deleted") else 0
            mtime = int(h["mtime"])
            row = self.db.execute("SELECT data, mtime, deleted, seq FROM habits WHERE uuid=?", (uuid,)).fetchone()
            # Fields the writer left out keep their stored values (see the module docstring).
            stored = json.loads(row[0]) if row is not None else {}
            data = json.dumps(clean_habit_data({**stored, **incoming}), sort_keys=True, ensure_ascii=False)
            if row is None:
                seq = self._next_seq()
                self.db.execute("INSERT INTO habits(uuid, data, mtime, deleted, seq) VALUES(?,?,?,?,?)",
                                (uuid, data, mtime, deleted, seq))
                accepted_habits.append({"uuid": uuid, "seq": seq})
            elif (row[0], row[2]) == (data, deleted):
                if mtime > row[1]:
                    self.db.execute("UPDATE habits SET mtime=? WHERE uuid=?", (mtime, uuid))
                accepted_habits.append({"uuid": uuid, "seq": row[3]})
            elif self._wins(mtime, f"{deleted}{data}", row[1], f"{row[2]}{row[0]}", if_absent, base_seq, row[3]):
                seq = self._next_seq()
                self.db.execute("UPDATE habits SET data=?, mtime=?, deleted=?, seq=? WHERE uuid=?",
                                (data, mtime, deleted, seq, uuid))
                accepted_habits.append({"uuid": uuid, "seq": seq})
            else:
                rejected_habits.append(self._habit_row(uuid))
        for e in body.get("entries") or []:
            habit = str(e["habit"])
            day = parse_day(e["date"]).isoformat()
            base_seq = self._base_seq(e)
            value = int(e["value"])
            notes = str(e.get("notes") or "")
            mtime = int(e["mtime"])
            row = self.db.execute("SELECT value, notes, mtime, seq FROM entries WHERE habit=? AND date=?",
                                  (habit, day)).fetchone()
            if row is None:
                seq = self._next_seq()
                self.db.execute("INSERT INTO entries(habit, date, value, notes, mtime, seq) VALUES(?,?,?,?,?,?)",
                                (habit, day, value, notes, mtime, seq))
                accepted_entries.append({"habit": habit, "date": day, "seq": seq})
            elif (row[0], row[1]) == (value, notes):
                if mtime > row[2]:
                    self.db.execute("UPDATE entries SET mtime=? WHERE habit=? AND date=?", (mtime, habit, day))
                accepted_entries.append({"habit": habit, "date": day, "seq": row[3]})
            elif self._wins(mtime, f"{value}:{notes}", row[2], f"{row[0]}:{row[1]}", if_absent, base_seq, row[3]):
                seq = self._next_seq()
                self.db.execute("UPDATE entries SET value=?, notes=?, mtime=?, seq=? WHERE habit=? AND date=?",
                                (value, notes, mtime, seq, habit, day))
                accepted_entries.append({"habit": habit, "date": day, "seq": seq})
            else:
                rejected_entries.append(self._entry_row(habit, day))
        self.db.commit()
        self.changed.notify_all()
        return {"seq": self.seq(), "rejected": {"habits": rejected_habits, "entries": rejected_entries},
                "accepted": {"habits": accepted_habits, "entries": accepted_entries}}

    def _habit_row(self, uuid):
        row = self.db.execute("SELECT uuid, data, mtime, deleted, seq FROM habits WHERE uuid=?", (uuid,)).fetchone()
        return {"uuid": row[0], "data": json.loads(row[1]), "mtime": row[2], "deleted": bool(row[3]), "seq": row[4]}

    def _entry_row(self, habit, day):
        row = self.db.execute("SELECT habit, date, value, notes, mtime, seq FROM entries WHERE habit=? AND date=?",
                              (habit, day)).fetchone()
        return {"habit": row[0], "date": row[1], "value": row[2], "notes": row[3], "mtime": row[4], "seq": row[5]}

    def epoch(self):
        """
        A number that changes when this server's history went back (restored from a backup): a
        client that knew another one reads everything again and sends back what was lost.
        """
        row = self.db.execute("SELECT value FROM meta WHERE key='epoch'").fetchone()
        return row[0] if row else self._new_epoch()

    def _new_epoch(self):
        epoch = secrets.randbits(53)  # exact in JavaScript too
        self.db.execute("INSERT INTO meta(key, value) VALUES('epoch', ?) "
                        "ON CONFLICT(key) DO UPDATE SET value=excluded.value", (epoch,))
        self.db.commit()
        return epoch

    def changes(self, since, wait_s=0, epoch=None):
        with self.lock:
            current = self.epoch()
            if epoch is not None and epoch == current and since > self.seq():
                # The client has read further than this server ever wrote, so its history went
                # back. A new epoch makes every client, not only this one, read it all again.
                current = self._new_epoch()
                sys.stderr.write(f"since={since} is past seq={self.seq()}: restored from a backup? new epoch\n")
            if epoch is not None and epoch != current:
                wait_s = 0  # that client starts over: tell it now
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
                {"uuid": r[0], "data": json.loads(r[1]), "mtime": r[2], "deleted": bool(r[3]), "seq": r[4]}
                for r in self.db.execute(
                    "SELECT uuid, data, mtime, deleted, seq FROM habits WHERE seq>? AND seq<=? ORDER BY seq",
                    (since, upto))
            ]
            entries = [
                {"habit": r[0], "date": r[1], "value": r[2], "notes": r[3], "mtime": r[4], "seq": r[5]}
                for r in self.db.execute(
                    "SELECT habit, date, value, notes, mtime, seq FROM entries WHERE seq>? AND seq<=? ORDER BY seq",
                    (since, upto))
            ]
            return {"seq": upto, "more": upto < latest, "habits": habits, "entries": entries,
                    "dayStartHour": self.day_start_hour(), "epoch": current}

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
            return {"date": day.isoformat(), "seq": self.seq(), "dayStartHour": self.day_start_hour(), "habits": out}


def store_for(user):
    with stores_lock:
        if user not in stores:
            stores[user] = Store(os.path.join(args.data, "users", user, "loop.sqlite"))
        return stores[user]


# ---- Loop's rules, ported from uhabits-core (EntryList, XrnruleProvider) ---------------

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
        for k, v in SECURITY_HEADERS.items():
            self.send_header(k, v)
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
        # The web app: its login cookie, with the header another site's page couldn't add.
        if not self.headers.get("Authorization") and self.headers.get("X-Xrn-Client"):
            user = check_session(self.headers.get("Cookie"))
            if not user:
                self.send(401, "login required")
            return user
        addr = client_address(self)
        if locked_out(addr):
            time.sleep(FAIL_DELAY_S)
            self.send(429, "too many wrong passwords; try again later", headers={"Retry-After": str(FAIL_WINDOW_S)})
            return None
        user = check_login(self.headers.get("Authorization"))
        if not user:
            if self.headers.get("Authorization"):
                note_failure(addr)
            time.sleep(FAIL_DELAY_S)
            # The web app logs in with its own form: a challenge would pop the browser's dialog.
            challenge = {} if self.headers.get("X-Xrn-Client") else {"WWW-Authenticate": 'Basic realm="xrnloops"'}
            self.send(401, "login required", headers=challenge)
            return None
        with fail_lock:
            failures.pop(addr, None)
        return user

    def read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            raise ValueError("body missing or too large")
        raw = self.rfile.read(length)
        if (self.headers.get("Content-Encoding") or "").lower() == "gzip":
            # Unpacked only up to MAX_BODY: a few megabytes of gzip can unpack to gigabytes.
            unpack = zlib.decompressobj(wbits=16 + zlib.MAX_WBITS)
            try:
                raw = unpack.decompress(raw, MAX_BODY + 1)
            except zlib.error:
                raise ValueError("bad gzip") from None
            if len(raw) > MAX_BODY:
                raise ValueError("body too large")
            if not unpack.eof:
                raise ValueError("bad gzip")
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
                epoch = int(query["epoch"]) if "epoch" in query else None
                return self.send_json(store.changes(since, wait, epoch))
            if path == "/api/status":
                day = parse_day(query["date"]) if "date" in query else date.today()
                return self.send_json(store.status(day))
        except (KeyError, ValueError) as e:
            return self.send(400, f"bad request: {e}")
        self.send(404, "not found")

    def do_POST(self):
        path, _ = self.route()
        if path == "/api/session":
            # Only a password signs in: a cookie can't be stretched into a new one.
            if not self.headers.get("Authorization"):
                return self.send(401, "login required")
            user = self.login()
            if user:
                self.send(200, '{"ok":true}', "application/json",
                          {"Set-Cookie": session_cookie(make_session(user), SESSION_S)})
            return
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

    def do_DELETE(self):
        path, _ = self.route()
        if path != "/api/session":
            return self.send(404, "not found")
        self.send(200, '{"ok":true}', "application/json", {"Set-Cookie": session_cookie("", 0)})

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
    global args, session_key
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
    SECURITY_HEADERS["Content-Security-Policy"] = content_policy(args.web)
    session_key = load_session_key()
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    srv.daemon_threads = True
    sys.stderr.write(f"xrnloops-sync on http://{args.host}:{args.port}, data in {args.data}\n")
    srv.serve_forever()


if __name__ == "__main__":
    main()
