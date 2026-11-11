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

The phone's xrnloops and the web app (served from `web/`) both read and write here, and xrnrule
on the phone and the laptop reads the same feed. Made to run on a small home server
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
                                "dayStartHour": 0, "epoch": E, "since": N}
                               -> {"seq", "rejected": {"habits": [...], "entries": [...]},
                                   "accepted": {"habits": [{"uuid", "seq"}],
                                                "entries": [{"habit", "date", "seq"}]}}
                               A rejected record lost to a newer one, which is returned.
                               An accepted one is stored at that seq.
                               With ifAbsent, records only fill gaps (a first sync).
                               dayStartHour (optional): the phone's "Extend the day past
                               midnight" setting, the hour its new day starts (0 or 3).
                               epoch and since (optional): the history the writer read and how
                               far. One this server no longer has gets 409 {"epoch"} and nothing
                               is written, so a write can't hide a restore (see Store.epoch).
    GET  /api/changes?since=N[&epoch=E] -> {"seq", "more", "habits": [...], "entries": [...], "dayStartHour", "epoch"}
                               &wait=S long-polls up to S seconds for something newer.
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
import gzip
import hashlib
import hmac
import ipaddress
import json
import math
import mimetypes
import os
import re
import secrets
import sqlite3
import sys
import threading
import time
import zlib
from datetime import date
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

YES_NO = 0
AT_LEAST = 0

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
    """The caller's address. Behind the local proxy (Funnel) that's the last X-Forwarded-For hop,
    the one the proxy added: anything before it came from the caller, who could make up a new
    address for every password guess. Only trusted from a loopback peer, for the same reason."""
    peer = handler.client_address[0]
    forwarded = handler.headers.get("X-Forwarded-For")
    if forwarded and peer in ("127.0.0.1", "::1"):
        return guesser(forwarded.split(",")[-1].strip())
    return guesser(peer)


def guesser(addr):
    """Who wrong logins are counted for: an IPv4 address, or an IPv6 address's /64. An IPv6
    line comes with at least a /64, so counted per address its owner had a new one for every
    guess and never reached the limit."""
    try:
        ip = ipaddress.ip_address(addr)
    except ValueError:
        return addr
    if ip.version == 4:
        return addr
    if ip.ipv4_mapped:
        return str(ip.ipv4_mapped)
    return f"{ipaddress.ip_address(int(ip) >> 64 << 64)}/64"


def from_another_site(headers):
    """Whether a request came from another site's page. A browser that was once given the password
    in its own sign-in box sends it again with a form any site posts here, and a text/plain form
    body can be JSON. The browser says where the page was in Sec-Fetch-Site; one too old for that
    still sends Origin with a POST, held against this host's name (or the proxy's X-Forwarded-Host).
    The apps and curl send neither. The extensions' pages are another origin, and may write."""
    origin = (headers.get("Origin") or "").strip().lower()
    if origin.startswith(("chrome-extension://", "moz-extension://")):
        return False
    site = (headers.get("Sec-Fetch-Site") or "").strip().lower()
    if site:
        return site in ("cross-site", "same-site")
    scheme, _, host = origin.partition("://")
    if scheme not in ("http", "https"):
        return False  # no Origin, or "null"
    ours = [headers.get("Host") or "", *(headers.get("X-Forwarded-Host") or "").split(",")]
    return host_key(host) not in {host_key(h) for h in ours if h.strip()}


def host_key(host):
    """A host as Origin and Host both write it: lower case, without the default port."""
    return host.strip().lower().removesuffix(":443").removesuffix(":80")


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


def reserve_guess(addr):
    """Counts a password from this address as wrong before it is checked; False if the address
    has used up its wrong logins. Counted afterwards, guesses sent at once all passed the check
    while the first were still in bcrypt. A right one clears the count (see Handler.login)."""
    now = time.monotonic()
    with fail_lock:
        if len(failures) > 10000:  # a flood from many addresses: forget the stale ones
            for a in [a for a, ts in failures.items() if now - ts[-1] >= FAIL_WINDOW_S]:
                del failures[a]
        recent = [t for t in failures.get(addr, ()) if now - t < FAIL_WINDOW_S]
        if len(recent) >= FAIL_LIMIT:
            failures[addr] = recent
            return False
        failures[addr] = recent + [now]
        return True


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
        checked, spare = False, None
        with open(args.users, encoding="utf-8") as f:
            for line in f:
                name, _, hashed = line.strip().partition(":")
                spare = hashed or spare
                if name == user and hashed:
                    checked = True
                    if bcrypt.checkpw(password.encode(), hashed.encode()):
                        auth_cache[key] = (user, time.time() + AUTH_CACHE_S, mtime)
                        return user
        if not checked and spare:
            # A name that isn't there takes as long as a wrong password for one that is, or
            # the time an answer takes says which names exist. The result is never used.
            bcrypt.checkpw(password.encode(), spare.encode())
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
    if tag is None:
        return None
    try:
        return user if hmac.compare_digest(session_mac(f"{who}.{expires}", tag), mac) else None
    except TypeError:  # a mac that isn't ASCII
        return None


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
            if not math.isfinite(value):  # it would go back out as JSON no browser can read
                raise ValueError(f"{key} must be a number")
        else:
            value = "" if value is None else str(value)
        out[key] = value
    if out["freqNum"] <= 0 or out["freqDen"] <= 0:
        raise ValueError("frequency must be positive")
    return out


class StaleEpoch(Exception):
    """A write made from a history this server no longer has: it was restored from a backup."""

    def __init__(self, epoch):
        super().__init__("the server's history went back")
        self.epoch = epoch


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
        epoch = body.get("epoch")
        if epoch is not None:
            since = body.get("since", 0)
            for value in (epoch, since):
                if isinstance(value, bool) or not isinstance(value, int):
                    raise ValueError("epoch and since must be numbers")
            # Before anything is written: one write would move seq past what the writer has read,
            # and then neither it nor any other client could tell the history went back.
            current = self._current_epoch(epoch, since)
            if epoch != current:
                raise StaleEpoch(current)
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

    def _current_epoch(self, epoch, since):
        """The epoch, once a client that read [since] in [epoch] has been heard (under the lock)."""
        current = self.epoch()
        if epoch is not None and epoch == current and since > self.seq():
            # The client has read further than this server ever wrote, so its history went
            # back. A new epoch makes every client, not only this one, read it all again.
            current = self._new_epoch()
            sys.stderr.write(f"since={since} is past seq={self.seq()}: restored from a backup? new epoch\n")
        return current

    def changes(self, since, wait_s=0, epoch=None):
        with self.lock:
            current = self._current_epoch(epoch, since)
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


def store_for(user):
    with stores_lock:
        if user not in stores:
            stores[user] = Store(os.path.join(args.data, "users", user, "loop.sqlite"))
        return stores[user]


# ---- HTTP ----------------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    server_version = "xrnloops-sync/1"
    timeout = 120  # seconds of silence before a connection is dropped, so a stalled client can't hold a thread

    def log_message(self, fmt, *a):
        headers = getattr(self, "headers", None)  # none yet on a request line that didn't parse
        who = headers.get("X-Forwarded-For", self.client_address[0]) if headers else "-"
        sys.stderr.write(f"{who} {fmt % a}\n")

    def parse_request(self):
        if not super().parse_request():
            return False
        # Before the login, which a page elsewhere can have the browser pass for it.
        if self.command not in ("GET", "HEAD", "OPTIONS") and from_another_site(self.headers):
            self.send(403, "refused: sent from another site's page")
            return False
        return True

    def route(self):
        path, _, query = self.path.partition("?")
        if path == PREFIX or path.startswith(PREFIX + "/"):
            path = path[len(PREFIX):] or "/"
        return path, {k: v[-1] for k, v in parse_qs(query).items()}

    def send(self, code, body=b"", ctype="text/plain; charset=utf-8", headers=None):
        if isinstance(body, str):
            body = body.encode()
        headers = {"Cache-Control": "no-store", **(headers or {})}
        worth_packing = len(body) > 1024 and not ctype.startswith("font/")  # woff2 is packed already
        if worth_packing and "gzip" in (self.headers.get("Accept-Encoding") or ""):
            body = gzip.compress(body, 6)
            headers["Content-Encoding"] = "gzip"
        self.send_response(code)
        for k, v in SECURITY_HEADERS.items():
            self.send_header(k, v)
        if code != 304:
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
        for k, v in headers.items():
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
        password = self.headers.get("Authorization")
        if not (reserve_guess(addr) if password else not locked_out(addr)):
            if password:
                time.sleep(FAIL_DELAY_S)
            self.send(429, "too many wrong passwords; try again later", headers={"Retry-After": str(FAIL_WINDOW_S)})
            return None
        user = check_login(password)
        if not user:
            if password:  # a request with none guessed nothing, and needn't hold a thread for it
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
                if not math.isfinite(wait):
                    raise ValueError("wait must be a number")
                epoch = int(query["epoch"]) if "epoch" in query else None
                return self.send_json(store.changes(since, wait, epoch))
        except (KeyError, ValueError, OverflowError) as e:
            return self.send(400, f"bad request: {e}")
        self.send(404, "not found")

    do_HEAD = do_GET

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
        except StaleEpoch as e:
            return self.send_json({"epoch": e.epoch}, 409)
        except (KeyError, ValueError, TypeError, OverflowError) as e:
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
        if name == "index.html":  # read here each time, so its scripts' hashes are too
            SECURITY_HEADERS["Content-Security-Policy"] = content_policy(args.web)
        ctype = mimetypes.guess_type(file)[0] or "application/octet-stream"
        if file.endswith(".webmanifest"):
            ctype = "application/manifest+json"
        if ctype.startswith("text/") or ctype in ("application/javascript", "application/json", "image/svg+xml"):
            ctype += "; charset=utf-8"
        # A font never changes under one name. Anything else may with a deploy, so the browser
        # asks each time, which costs a 304 instead of the file.
        tag = '"' + hashlib.sha256(data).hexdigest()[:32] + '"'
        cache = "public, max-age=2592000" if name.startswith("fonts/") else "no-cache"
        headers = {"ETag": tag, "Cache-Control": cache, "Vary": "Accept-Encoding"}
        if tag in (self.headers.get("If-None-Match") or ""):
            return self.send(304, headers=headers)
        self.send(200, data, ctype, headers)


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
    mimetypes.add_type("font/woff2", ".woff2")
    SECURITY_HEADERS["Content-Security-Policy"] = content_policy(args.web)
    session_key = load_session_key()
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    srv.daemon_threads = True
    sys.stderr.write(f"xrnloops-sync on http://{args.host}:{args.port}, data in {args.data}\n")
    srv.serve_forever()


if __name__ == "__main__":
    main()
