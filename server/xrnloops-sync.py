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
                               &wait=S long-polls up to S seconds for something newer; one
                               whose caller hangs up first ends there, unanswered.
    POST /api/session          with Basic auth: sets the web app's login cookie (below); a
                               device token can't
    DELETE /api/session        signs the browser out, and ends its cookie on every server (below)
    GET  /health               200 "ok", no login (for monitoring)
    GET  /, /app.js, ...       the web app (static files, no login; the data needs one)

The phone sends Basic auth with every request, or a device token, `Authorization: Bearer
xrndev_...`: a login of its own for one device and the services it names, which the server's
owner makes and revokes in device-tokens.json beside the users file (see DeviceTokens). A token
for other services only is answered 403. The web app signs in once and then carries an
HttpOnly cookie, `xrn_session`, so the password never sits where a page's script could read it:
the host is shared with other apps, and a script bug in any of them could read the pages'
storage. The cookie counts only with an `X-Xrn-Client` header, which another site's page can't
send. It's signed with `session.key`, made next to the users file on first start, which
xrnrule-sync shares (its status page is on the same host), so either sign-in works for both. A
new password ends every session; so does deleting session.key. Signing out ends that one cookie:
its SHA-256 goes on `signed-out.json` beside session.key until it would have run out, a list that
xrnhub, xrnrule-sync and xrninsta (which take the same cookie) also write to and refuse.

    habit:  {"uuid", "mtime", "deleted", "seq", "data": {"name", "question", "description",
             "color", "freqNum", "freqDen", "type", "targetType", "targetValue", "step", "unit",
             "archived", "position", "reminder": null | {"hour", "minute", "days"},
             "icon", "timer"}}
    entry:  {"habit", "date": "YYYY-MM-DD", "value", "notes", "mtime", "seq"}

`icon` and `timer` are xrnrule's: an icon name, and whether a numerical habit counts minutes
with its focus timer. `step` (2026-11-15) is what xrnloops' number flick adds with + and takes
off with −, in the habit's unit; more than 0, and 1 when a write never gave one. A habit stored
before it comes back without it until it is next written, and clients read that as 1; a write
that leaves it out (xrnrule, an older xrnloops) keeps the stored one, as for any field. Writes
may add "baseSeq" to any record (see above).

Entry values are Loop's: UNKNOWN=-1, NO=0, YES_AUTO=1, YES_MANUAL=2, SKIP=3; numerical habits
store the amount x1000.

Habits that tick themselves (optional). With a file at --auto (default
~/.config/xrnloops/auto.json), the server asks a personal hub (xrnhub's read-only /api/v1) every
two minutes whether a habit's day already shows what the habit stands for, and ticks it:

    {"user": "alex", "hub": "http://127.0.0.1:5238", "token": "xrnhub_...",
     "rules": [{"habit": "Weigh in", "from": "weight", "missed": "no"},
               {"habit": "Plan the day", "from": "calendar", "hours": 8},
               {"habit": "Log spending", "from": "money"},
               {"habit": "Pantry", "from": "inventory"}]}

`habit` is a yes/no habit's name or uuid. `from` is what ticks it: `weight` (a weighing that
day), `calendar` (at least `hours` of timed events that day, overlaps counted once, and only the
time that has passed unless the rule adds `"planned": true`), `money` (a
transaction entered that day, not one a repeating bill made) or `inventory` (anything done to an
item that day). A day is the habit day, from dayStartHour to the next. A tick is an ordinary
entry (YES_MANUAL), written over "not entered" or "no", never over a tick or a skip, and it is
never taken back. Only today is written, while it is today: a day left unmet stays as it is,
and a day that is over is never touched, whatever arrives later. One exception, for a rule that
says `"missed": "no"`: as its day ends, a day with nothing entered is settled once, "no" if it
was missed (a tick if it was done in the day's last minutes), and never looked at again, so
whatever you make of that day afterwards stays. See auto_round.

    python3 xrnloops-sync.py --data ~/xrnloops-sync/data --users ~/.config/xrn-sync/users \
        --web ~/xrnloops-sync/web
"""
import argparse
import base64
import fcntl
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
import select
import socket
import sqlite3
import sys
import threading
import time
import urllib.request
import zlib
from datetime import date, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

MAX_BODY = 32 * 1024 * 1024
# How many lists and objects deep a request body may go. The clients' own nest a handful; json.loads
# raises RecursionError past a few thousand (tens of thousands on Python 3.14), which no except
# here caught, and str() of a value that deep is as costly as it is long.
MAX_DEPTH = 64
FAIL_DELAY_S = 2  # after a wrong password, as Radicale does
AUTH_CACHE_S = 600  # a bcrypt check costs ~0.3s on a phone; don't pay it per request
MAX_WAIT_S = 55
GONE_CHECK_S = 1  # how often a long-poll looks whether its caller is still there
FAIL_LIMIT = 10  # wrong logins from one address within FAIL_WINDOW_S, then 429 until they age out
FAIL_WINDOW_S = 15 * 60
PAGE_ROWS = 5000
PREFIX = "/xrnloops"
SESSION_COOKIE = "xrn_session"
SESSION_S = 30 * 86400

YES_NO = 0
AT_LEAST = 0
UNKNOWN, NO, YES_AUTO, YES_MANUAL, SKIP = -1, 0, 1, 2, 3  # an entry's value, as Loop has them

AUTO_EVERY_S = 120  # how often the habits that tick themselves are looked at
AUTO_TIMEOUT_S = 20

args = None
auth_cache = {}  # sha256(user:password) -> (user, expires, users-file mtime)
fail_lock = threading.Lock()
fail_settled = threading.Condition(fail_lock)  # a login being checked has been settled
failures = {}  # client address -> (monotonic time, user name tried) of its recent wrong logins
checking = {}  # client address -> how many of its logins are being checked right now
stores = {}
stores_lock = threading.Lock()
session_key = b""  # signs the web app's login cookies; see load_session_key()
devices = None  # DeviceTokens, made on first use: see device_tokens()
signed_out_lock = threading.Lock()
signed_out_seen = None  # the signed-out.json (path, inode, mtime, size) signed_out_list was read from
signed_out_list = {}  # sha256 of a signed-out cookie -> when it would have run out; see signed_out()

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


def recent_failures(addr, now):
    """This address's wrong logins within FAIL_WINDOW_S; older ones are forgotten. Under fail_lock."""
    recent = [f for f in failures.get(addr, ()) if now - f[0] < FAIL_WINDOW_S]
    if recent:
        failures[addr] = recent
    else:
        failures.pop(addr, None)
    return recent


def locked_out(addr):
    """Whether this address has used up its wrong logins for now."""
    with fail_lock:
        return len(recent_failures(addr, time.monotonic())) >= FAIL_LIMIT


def reserve_guess(addr):
    """Waits for a place to check a password from this address in; False once the address has
    used up its wrong logins. A login being checked holds a place until settle_guess: counted only
    afterwards, guesses sent at once all passed the check while the first were still in bcrypt.
    It isn't a wrong login, though: one that finds no place waits for it to be settled rather than
    being turned away, so two clients signing in at once never lock each other out."""
    with fail_settled:
        now = time.monotonic()
        if len(failures) > 10000:  # a flood from many addresses: forget the stale ones
            for a in [a for a, fs in failures.items() if now - fs[-1][0] >= FAIL_WINDOW_S]:
                del failures[a]
        while True:
            wrong = len(recent_failures(addr, time.monotonic()))
            if wrong >= FAIL_LIMIT:
                return False
            if wrong + checking.get(addr, 0) < FAIL_LIMIT:
                checking[addr] = checking.get(addr, 0) + 1
                return True
            fail_settled.wait(1)


def settle_guess(addr, name, right):
    """A login reserve_guess let through has been checked. A wrong one counts against the address,
    under the name it tried. A right one clears that name's mistakes from this address, and no one
    else's: a client logging in mustn't give a guesser behind the same address its guesses back.
    None, a password already found right (cached_login) or a check that broke, settles nothing."""
    with fail_settled:
        left = checking.get(addr, 1) - 1
        if left > 0:
            checking[addr] = left
        else:
            checking.pop(addr, None)
        if right is False:
            failures.setdefault(addr, []).append((time.monotonic(), name[:64]))
        elif right:
            kept = [f for f in failures.get(addr, ()) if f[1] != name[:64]]
            if kept:
                failures[addr] = kept
            else:
                failures.pop(addr, None)
        fail_settled.notify_all()


def basic_login(header):
    """(user, password) from `Authorization: Basic ...`, or None."""
    if not header or not header.startswith("Basic "):
        return None
    try:
        user, _, password = base64.b64decode(header[6:]).decode("utf-8").partition(":")
    except Exception:
        return None
    return user, password


def cached_login(header):
    """The user name for a password already found right (the auth cache), else None: no check."""
    login = basic_login(header)
    if not login:
        return None
    hit = auth_cache.get(hashlib.sha256(f"{login[0]}:{login[1]}".encode()).hexdigest())
    return hit[0] if hit and hit[1] > time.time() and hit[2] == users_mtime() else None


def check_login(header):
    """The user name for a valid `Authorization: Basic ...`, else None."""
    login = basic_login(header)
    if not login:
        return None
    user, password = login
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


def signed_out_path():
    """The cookies signed out on any server that shares the users file and session.key (xrnhub,
    xrnrule-sync and xrninsta too): {sha256 of the cookie: when it would have run out}, beside them."""
    return os.path.join(os.path.dirname(args.users), "signed-out.json")


def read_signed_out(path):
    """signed-out.json's list: {} with no file, None (and a line in the log) for one that can't be read."""
    try:
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
        if not isinstance(doc, dict):
            raise ValueError("not a JSON object")
    except FileNotFoundError:
        return {}
    except (OSError, ValueError, RecursionError) as e:  # ValueError: also bad UTF-8
        sys.stderr.write(f"{path} can't be read, so the sign-outs read before it hold: {e}\n")
        return None
    return {k: v for k, v in doc.items() if isinstance(k, str) and isinstance(v, (int, float))}


def signed_out():
    """The list as the file has it now, read again only when it has changed, so a sign-out on
    another server counts here from the next request. A file that can't be read keeps the last list."""
    global signed_out_seen, signed_out_list
    path = signed_out_path()
    try:
        st = os.stat(path)
        now = (path, st.st_ino, st.st_mtime_ns, st.st_size)
    except FileNotFoundError:
        now = (path, None)
    except OSError as e:
        now = (path, "unreadable", e.errno)
    with signed_out_lock:
        if now != signed_out_seen:
            signed_out_seen = now
            listed = {} if now[1] is None else read_signed_out(path)
            if listed is not None:
                signed_out_list = listed
        return signed_out_list


def sign_out(token):
    """Puts a cookie on the list, dropping what has run out. Under an flock on signed-out.json.lock,
    with the file read again inside it, so two servers signing out at once both keep theirs; written
    whole to a new file that then takes the old one's place."""
    global signed_out_seen, signed_out_list
    path = signed_out_path()
    lock = os.open(f"{path}.lock", os.O_RDWR | os.O_CREAT, 0o600)
    try:
        fcntl.flock(lock, fcntl.LOCK_EX)
        listed = read_signed_out(path)
        if listed is None:
            listed = dict(signed_out_list)
        listed[hashlib.sha256(token.encode()).hexdigest()] = int(token.split(".")[1])
        now = time.time()
        kept = {k: v for k, v in listed.items() if v >= now}  # run out: refused anyway
        tmp = f"{path}.{os.getpid()}.{threading.get_ident()}.tmp"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(kept, f, separators=(",", ":"))
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
        st = os.stat(path)
        with signed_out_lock:
            signed_out_seen, signed_out_list = (path, st.st_ino, st.st_mtime_ns, st.st_size), kept
    finally:
        os.close(lock)  # and with it the flock


def make_session(user):
    """A login cookie's value for [user]: who, until when, and a signature over both."""
    who = base64.urlsafe_b64encode(user.encode()).decode().rstrip("=")
    tag = password_tag(user)
    expires = int(time.time()) + SESSION_S
    # A cookie is made from the name, the second it runs out and the password alone, so one made in
    # the second the same one was signed out (here or on another server) would be that cookie. It
    # runs out a second later instead, which keeps the format every server reads.
    listed = signed_out()
    while True:
        body = f"{who}.{expires}"
        token = f"{body}.{session_mac(body, tag)}"
        if hashlib.sha256(token.encode()).hexdigest() not in listed:
            return token
        expires += 1


def session_token(cookie_header):
    """The login cookie's value in a Cookie header, or ""."""
    token = ""
    for part in (cookie_header or "").split(";"):
        name, _, value = part.strip().partition("=")
        if name == SESSION_COOKIE:
            token = value
    return token


def check_session(cookie_header):
    """The user a request's login cookie names, if it's ours, unexpired, not signed out and the
    password unchanged."""
    token = session_token(cookie_header)
    try:
        who, expires, mac = token.split(".")
        user = base64.urlsafe_b64decode(who + "=" * (-len(who) % 4)).decode("utf-8")
        if int(expires) < time.time():
            return None
    except ValueError:  # also a bad base64 or UTF-8
        return None
    if hashlib.sha256(token.encode()).hexdigest() in signed_out():
        return None
    tag = password_tag(user)
    if tag is None:
        return None
    try:
        return user if hmac.compare_digest(session_mac(f"{who}.{expires}", tag), mac) else None
    except TypeError:  # a mac that isn't ASCII
        return None


def end_session(cookie_header):
    """Signs out the session a request's cookie carries, on every server that takes it, until it
    would have run out anyway. The user it was, or None when it was no session of ours."""
    user = check_session(cookie_header)
    if not user:
        return None
    try:
        sign_out(session_token(cookie_header))
    except OSError as e:
        sys.stderr.write(f"sign-out not kept: {e!r}\n")
    return user


def session_cookie(value, max_age):
    # Path=/: xrnrule's status page (/xrnrule) is signed in by this too.
    return f"{SESSION_COOKIE}={value}; Path=/; Max-Age={max_age}; HttpOnly; Secure; SameSite=Strict"


# ---- device logins ----------------------------------------------------------------------------
# The same block is in every xrn sync server that reads the shared users file.

DEVICE_TOKEN = re.compile(r"xrndev_([0-9a-f]{8})_([A-Za-z0-9_-]{43})")
DEVICE_USER = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
DEVICE_HASH = re.compile(r"[0-9a-f]{64}")
DEVICE_SEEN_S = 600  # when a device was last here is written down at most this often
DEVICE_SPARE = "0" * 64  # what a token whose id isn't listed is held against, so it takes as long


class DeviceTokens:
    """Device logins: `Authorization: Bearer xrndev_<id>_<secret>`, one per device, each for the
    services it names. The server's owner makes and revokes them in device-tokens.json, beside the
    users file, which no server writes:

        {"tokens": [{"id": "<8 hex>", "user": "alex", "device": "laptop",
                     "services": ["xrnloops", "xrnrule"], "sha256": "<SHA-256 of the secret, hex>",
                     "created": <unix seconds>}]}

    Only the secret's SHA-256 is kept, so a copy of the file signs no one in; the secret is 32
    random bytes, which no guessing reaches, so no bcrypt is paid per request. The file is read
    again whenever it (or the users file) changes, so a revoked token is refused from its next
    request on. One that can't be read lets no token in; the password still works. A token signs
    in as its user only while the users file lists them, and only to the services it names. When
    each was last used here goes to device-seen/<service>.json beside it, at most every
    DEVICE_SEEN_S, never on every request."""

    def __init__(self, users_path, service):
        folder = os.path.dirname(os.path.abspath(users_path))
        self.path = os.path.join(folder, "device-tokens.json")
        self.seen_path = os.path.join(folder, "device-seen", f"{service}.json")
        self.users_path = users_path
        self.service = service
        self.lock = threading.Lock()
        self.stamps = None  # the two files' (inode, mtime, size) [tokens] was read from
        self.tokens = {}  # id -> entry, of users the users file lists
        self.seen = None  # id -> unix seconds, as device-seen/<service>.json has them

    @staticmethod
    def stamp(path):
        try:
            st = os.stat(path)
            return st.st_ino, st.st_mtime_ns, st.st_size
        except FileNotFoundError:
            return None
        except OSError as e:
            return "unreadable", e.errno

    def current(self):
        """The tokens as the files say now, read again only when one of them has changed."""
        stamps = (self.stamp(self.path), self.stamp(self.users_path))
        with self.lock:
            if stamps == self.stamps:
                return self.tokens
        tokens = {}
        if stamps[0] is not None:
            try:
                with open(self.path, encoding="utf-8") as f:
                    doc = json.load(f)
                listed = doc.get("tokens") if isinstance(doc, dict) else None
                if not isinstance(listed, list):
                    raise ValueError("not a list of tokens")
                users = set()
                with open(self.users_path, encoding="utf-8") as f:
                    for line in f:
                        name, _, hashed = line.strip().partition(":")
                        if hashed:
                            users.add(name)
                for t in listed:
                    if not isinstance(t, dict):
                        continue
                    token_id, user, services, digest = t.get("id"), t.get("user"), t.get("services"), t.get("sha256")
                    if (isinstance(token_id, str) and isinstance(digest, str) and DEVICE_HASH.fullmatch(digest)
                            and isinstance(user, str) and DEVICE_USER.fullmatch(user) and user in users
                            and isinstance(services, list)):
                        tokens[token_id] = {"user": user, "sha256": digest,
                                            "services": {s for s in services if isinstance(s, str)}}
            except (OSError, ValueError, RecursionError) as e:  # ValueError: also bad UTF-8
                sys.stderr.write(f"{self.path} can't be read, so no device token signs in: {e}\n")
                tokens = {}
        with self.lock:
            self.stamps, self.tokens = stamps, tokens
        return tokens

    def check(self, header):
        """(user, False) for a device token that signs in here; (None, True) for a right one that
        is for other services only; (None, False) for anything else: no device token, or a wrong
        or revoked one."""
        if not header or not header.startswith("Bearer "):
            return None, False
        m = DEVICE_TOKEN.fullmatch(header[7:].strip())
        if not m:
            return None, False
        token_id, secret = m.groups()
        entry = self.current().get(token_id)
        right = hmac.compare_digest(hashlib.sha256(secret.encode()).hexdigest(),
                                    entry["sha256"] if entry else DEVICE_SPARE)
        if not (entry and right):
            return None, False
        if self.service not in entry["services"]:
            return None, True
        self.note(token_id)
        return entry["user"], False

    def note(self, token_id):
        """Writes down that [token_id] was here now, if it last was DEVICE_SEEN_S ago or more."""
        now = int(time.time())
        with self.lock:
            if self.seen is None:
                try:
                    with open(self.seen_path, encoding="utf-8") as f:
                        doc = json.load(f)
                    self.seen = {k: v for k, v in doc.items() if isinstance(v, int)} if isinstance(doc, dict) else {}
                except (OSError, ValueError, RecursionError):
                    self.seen = {}
            if now - self.seen.get(token_id, 0) < DEVICE_SEEN_S:
                return
            # Revoked ones go; the file holds no more than the tokens there are.
            self.seen = {k: v for k, v in self.seen.items() if k in self.tokens}
            self.seen[token_id] = now
            try:
                os.makedirs(os.path.dirname(self.seen_path), mode=0o700, exist_ok=True)
                tmp = f"{self.seen_path}.{os.getpid()}.tmp"
                fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                with os.fdopen(fd, "w", encoding="utf-8") as f:
                    json.dump(self.seen, f, separators=(",", ":"))
                    f.flush()
                    os.fsync(f.fileno())
                os.replace(tmp, self.seen_path)
            except OSError as e:  # the login counts all the same
                sys.stderr.write(f"{self.seen_path} not written: {e}\n")


def device_tokens():
    """The device logins beside the users file in use."""
    global devices
    if devices is None or devices.users_path != args.users:
        devices = DeviceTokens(args.users, "xrnloops")
    return devices


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
    "position": 0, "reminder": None, "icon": "", "timer": False, "step": 1.0,
}


def nested_within(value, limit):
    """Whether no list or object in [value] sits more than [limit] deep, the outermost being 1.
    Walked with a list of its own, not by recursion, so any depth is measured without a stack."""
    todo = [(value, 1)] if isinstance(value, (dict, list)) else []
    while todo:
        value, depth = todo.pop()
        if depth > limit:
            return False
        for v in value.values() if isinstance(value, dict) else value:
            if isinstance(v, (dict, list)):
                todo.append((v, depth + 1))
    return True


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
    if out["step"] <= 0:
        raise ValueError("step must be more than 0")
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

    def changes(self, since, wait_s=0, epoch=None, gone=None):
        """The page after [since], held up to [wait_s] for one to come. None, without one, once
        [gone] says the caller has stopped waiting for it."""
        with self.lock:
            current = self._current_epoch(epoch, since)
            if epoch is not None and epoch != current:
                wait_s = 0  # that client starts over: tell it now
            deadline = time.monotonic() + min(max(wait_s, 0), MAX_WAIT_S)
            while self.seq() <= since:
                left = deadline - time.monotonic()
                if left <= 0:
                    break
                self.changed.wait(min(left, GONE_CHECK_S) if gone else left)
                if gone and gone():
                    return None
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


    def habits(self):
        """Every habit that isn't deleted: uuid -> its data."""
        with self.lock:
            return {r[0]: json.loads(r[1]) for r in self.db.execute("SELECT uuid, data FROM habits WHERE deleted=0")}

    def auto_closed(self):
        """The last habit day auto_round settled as it ended (a date's ordinal), or None before the first."""
        with self.lock:
            row = self.db.execute("SELECT value FROM meta WHERE key='auto_closed'").fetchone()
            return row[0] if row else None

    def set_auto_closed(self, ordinal):
        with self.lock:
            self.db.execute("INSERT INTO meta(key, value) VALUES('auto_closed', ?) "
                            "ON CONFLICT(key) DO UPDATE SET value=excluded.value", (ordinal,))
            self.db.commit()

    def entry(self, habit, day):
        """The stored entry of [habit] on [day], or None."""
        with self.lock:
            row = self.db.execute("SELECT 1 FROM entries WHERE habit=? AND date=?", (habit, day)).fetchone()
            return self._entry_row(habit, day) if row else None


def store_for(user):
    with stores_lock:
        if user not in stores:
            stores[user] = Store(os.path.join(args.data, "users", user, "loop.sqlite"))
        return stores[user]


# ---- habits that tick themselves ------------------------------------------------------------

def auto_moment(text):
    """A time as the hub writes it ("2026-11-15T07:05:00+11:00", or the home inventory's
    "2026-11-15T08:00:00.000Z"), or None for a whole day or anything else."""
    try:
        moment = datetime.fromisoformat(str(text).replace("Z", "+00:00"))
    except ValueError:
        return None
    return moment if moment.tzinfo else None


def auto_window(day, start_hour):
    """The habit day [day] on this machine's clock: from its start hour to the next day's."""
    lo = datetime(day.year, day.month, day.day, start_hour).astimezone()
    after = day + timedelta(days=1)
    return lo, datetime(after.year, after.month, after.day, start_hour).astimezone()


def auto_weighed(answer, lo, hi, rule, now):
    return any(lo <= m < hi for m in (auto_moment(w.get("at")) for w in answer.get("weighings") or []) if m)


def auto_calendar(answer, lo, hi, rule, now):
    """Whether the day's timed events cover the rule's hours. Events that overlap count once,
    and one that runs past the day counts up to its edge; whole-day events aren't hours. Only
    time that has passed counts, so a day planned ahead ticks once it has been lived, unless the
    rule says "planned": true."""
    if answer.get("ready") is False:
        return None  # the hub is still reading the calendar: nothing is known yet
    if not rule.get("planned"):
        hi = min(hi, now)
    spans = []
    for event in answer.get("events") or []:
        start, end = auto_moment(event.get("start")), auto_moment(event.get("end"))
        if event.get("allDay") or not start or not end:
            continue
        start, end = max(start, lo), min(end, hi)
        if start < end:
            spans.append((start, end))
    covered, upto = timedelta(), None
    for start, end in sorted(spans):
        if upto is None or start > upto:
            covered, upto = covered + (end - start), end
        elif end > upto:
            covered, upto = covered + (end - upto), end
    hours = rule.get("hours", 8)
    if isinstance(hours, bool) or not isinstance(hours, (int, float)) or hours <= 0:
        raise ValueError("hours must be a number above 0")
    return covered >= timedelta(hours=hours)


def auto_spent(answer, lo, hi, rule, now):
    # A bill a repeating one made was entered by the app, not by you.
    return any(lo <= m < hi for m in (auto_moment(t.get("at")) for t in answer.get("transactions") or []
                                      if not t.get("repeating")) if m)


def auto_stocked(answer, lo, hi, rule, now):
    return any(lo <= m < hi for item in answer.get("items") or []
               for m in (auto_moment(h.get("date")) for h in item.get("history") or []) if m)


# What a rule's "from" names: the hub's path, whether it takes a range of days, and the test of
# one day in its answer (True, False, or None when the hub can't say yet).
AUTO_SOURCES = {
    "weight": ("/api/v1/health/weight", True, auto_weighed),
    "calendar": ("/api/v1/calendar", True, auto_calendar),
    "money": ("/api/v1/money/transactions", True, auto_spent),
    "inventory": ("/api/v1/inventory", False, auto_stocked),
}


def auto_name(text):
    """A habit's name as rules match it: an emoji with or without its variation selector is the same name."""
    return str(text).replace("\ufe0f", "").replace("\ufe0e", "").strip()


def auto_habit(habits, wanted):
    """The uuid of the yes/no habit a rule names (by uuid, or by name: one in use before an archived one)."""
    if wanted in habits:
        return wanted if habits[wanted].get("type", YES_NO) == YES_NO else None
    named = [u for u, data in habits.items()
             if auto_name(data.get("name", "")) == auto_name(wanted) and data.get("type", YES_NO) == YES_NO]
    named.sort(key=lambda u: (bool(habits[u].get("archived")), habits[u].get("position", 0), u))
    return named[0] if named else None


def auto_round(store, rules, fetch, now):
    """One look at every rule: ticks today where it already shows what its habit stands for.

    [fetch] gets a hub path (with its query) as parsed JSON; [now] is this machine's time. Only
    the habit day [now] is in is written, while it lasts: a tick or a skip is left alone, and a
    met day that says "no" or nothing is ticked. A day that is over keeps what it says, whatever
    the hub knows of it since: the first version went back three days, and on its first half hour
    turned four days marked "no" by hand into ticks.

    The one write to a finished day is its closing, for rules that say "missed": "no". The first
    round of a new day settles the day that just ended, where nothing was entered: "no", or a
    tick if it was done after the last round saw it. It happens once: the store remembers the
    last day closed, so a day cleared or changed by hand afterwards is never settled again, and
    a late arrival changes nothing. Days before the first round here, and days that ended while
    this wasn't running (but for the latest), are not closed. A closing the hub couldn't answer
    is tried again on the next round.

    A source is asked once per day looked at, and only when a rule still has that day to settle.
    A write names the version it read (baseSeq), so a change that came in between wins. Returns
    what it wrote, and the problems it met, as text lines."""
    start_hour = store.day_start_hour()
    today = (now - timedelta(hours=start_hour)).date()
    ended = today - timedelta(days=1)
    closed = store.auto_closed()
    closing = closed is not None and closed < ended.toordinal()
    habits = store.habits()
    answers, wrote, problems, settled = {}, [], [], True
    for rule in rules:
        source = AUTO_SOURCES.get(rule.get("from"))
        uuid = auto_habit(habits, str(rule.get("habit", "")))
        if source is None or uuid is None:
            problems.append(f"no {'source' if source is None else 'yes/no habit'} for rule {rules.index(rule) + 1}")
            continue
        path, ranged, test = source
        for day in [today] + ([ended] if closing and rule.get("missed") == "no" else []):
            over = day != today
            stored = store.entry(uuid, day.isoformat())
            value = stored["value"] if stored else UNKNOWN
            if value in (YES_AUTO, YES_MANUAL, SKIP) or (over and value != UNKNOWN):
                continue
            asked = path + (f"?from={day.isoformat()}&to={(day + timedelta(days=1)).isoformat()}" if ranged else "")
            if asked not in answers:
                try:
                    answers[asked] = fetch(asked)
                except Exception as e:  # the hub is down or refuses: nothing is known
                    answers[asked] = None
                    problems.append(f"{rule.get('from')}: {e}")
            met = None
            if isinstance(answers[asked], dict):
                try:
                    met = test(answers[asked], *auto_window(day, start_hour), rule, now.astimezone())
                except (ValueError, TypeError, AttributeError) as e:
                    problems.append(f"{rule.get('from')}: {e}")
            if met is None:
                settled = settled and not over
                continue
            if not met and not over:
                continue
            new = YES_MANUAL if met else NO
            # Stamped at the start of the habit day (or just past what is stored), not now: a
            # hand entry for the day made on a device that was offline arrives later with an
            # earlier mtime, and it must still win, as "never over a tick or a skip" says.
            # baseSeq keeps this write from beating what the server already has.
            since = int(auto_window(day, start_hour)[0].timestamp() * 1000)
            entry = {"habit": uuid, "date": day.isoformat(), "value": new,
                     "notes": stored["notes"] if stored else "", "mtime": max(since, stored["mtime"] + 1 if stored else since)}
            if stored:
                entry["baseSeq"] = stored["seq"]
            if store.apply({"entries": [entry]})["accepted"]["entries"]:
                wrote.append(f"{rule.get('from')} {day.isoformat()} {'yes' if met else 'no'}")
    if closed is None or (closing and settled):
        store.set_auto_closed(ended.toordinal())
    return wrote, problems


def auto_config(path):
    """The rules file, or None when there is none. A wrong one raises ValueError."""
    try:
        with open(path, encoding="utf-8") as f:
            config = json.load(f)
    except FileNotFoundError:
        return None
    except (OSError, ValueError) as e:
        raise ValueError(f"{path}: {e}")
    if not isinstance(config, dict) or not isinstance(config.get("rules"), list) \
            or not all(isinstance(r, dict) for r in config["rules"]):
        raise ValueError(f"{path}: needs \"rules\", a list")
    for key in ("user", "hub", "token"):
        if not isinstance(config.get(key), str) or not config[key]:
            raise ValueError(f"{path}: needs \"{key}\"")
    if not re.fullmatch(r"https?://[^\s?#]+", config["hub"]):
        raise ValueError(f"{path}: \"hub\" must be an http address")
    return config


def auto_fetch(config):
    def fetch(path):
        request = urllib.request.Request(config["hub"].rstrip("/") + path,
                                         headers={"Authorization": "Bearer " + config["token"]})
        with urllib.request.urlopen(request, timeout=AUTO_TIMEOUT_S) as answer:
            return json.loads(answer.read(MAX_BODY))
    return fetch


def auto_loop(path):
    """Runs auto_round every AUTO_EVERY_S while the rules file is there. The log gets what was
    ticked, and a problem once, when it begins and when it ends, not every two minutes."""
    said = None
    while True:
        try:
            config = auto_config(path)
            if config is None:
                problems = []
            elif not os.path.exists(os.path.join(args.data, "users", config["user"], "loop.sqlite")):
                problems = [f"no habits kept for \"{config['user']}\""]
            else:
                wrote, problems = auto_round(store_for(config["user"]), config["rules"], auto_fetch(config), datetime.now())
                for line in wrote:
                    sys.stderr.write(f"auto: {line}\n")
        except Exception as e:  # nothing here may end the thread
            problems = [str(e)]
        trouble = "; ".join(sorted(set(problems)))
        if trouble != said:
            sys.stderr.write(f"auto: {trouble}\n" if trouble else "auto: fine again\n" if said else "")
            said = trouble
        time.sleep(AUTO_EVERY_S)


# ---- HTTP ----------------------------------------------------------------------------------

STATIC_SETTLE_NS = 2 * 10**9  # a file written this recently could change again within its time
static_files = {}  # path -> ((inode, mtime_ns, size), StaticFile)


class StaticFile:
    """One file of the web app, read once: its bytes, their ETag, and gzipped once it's asked for."""

    def __init__(self, data):
        self.data = data
        self.tag = '"' + hashlib.sha256(data).hexdigest()[:32] + '"'
        self._packed = None

    def packed(self):
        if self._packed is None:
            self._packed = gzip.compress(self.data, 6)
        return self._packed


def static_file(path):
    """The web app's file at [path], and whether it was read just now. Each request read, hashed
    and gzipped it again, a 304 too, which anyone can ask for without a login on a phone-class
    server. Read again once it changes (a deploy moves in new files); a file's time moves in clock
    ticks, so one written in the last moments may change again without it, and is read each time."""
    st = os.stat(path)
    key = (st.st_ino, st.st_mtime_ns, st.st_size)
    hit = static_files.get(path)
    if hit and hit[0] == key:
        return hit[1], False
    with open(path, "rb") as f:
        web_file = StaticFile(f.read())
    if time.time_ns() - st.st_mtime_ns >= STATIC_SETTLE_NS:
        static_files[path] = (key, web_file)
    return web_file, True


# The request line and X-Forwarded-For come from the caller. Written to the log as they are, a
# control character in them could rewrite the terminal the log is read in, or (a \r) start a line
# of its own for xrnhub's log reader. Escaped as http.server's own log_message, replaced here, does.
LOG_ESCAPES = str.maketrans({**{c: f"\\x{c:02x}" for c in (*range(0x20), *range(0x7F, 0xA0))}, "\\": "\\\\"})


class Handler(BaseHTTPRequestHandler):
    server_version = "xrnloops-sync/1"
    sys_version = ""  # the Server header named the Python version too
    timeout = 120  # seconds of silence before a connection is dropped, so a stalled client can't hold a thread

    def log_message(self, fmt, *a):
        headers = getattr(self, "headers", None)  # none yet on a request line that didn't parse
        who = headers.get("X-Forwarded-For", self.client_address[0]) if headers else "-"
        sys.stderr.write(f"{who} {fmt % a}".translate(LOG_ESCAPES) + "\n")

    def handle(self):
        # A caller hanging up, mid-request or before the answer is out, is the network, not a fault
        # here: the laptop's tailnet path drops, a phone loses its signal, a page is closed. One
        # line says so. socketserver printed some 30 lines of traceback for each, which xrnhub's
        # log page shows as an error.
        try:
            super().handle()
        except ConnectionError as e:
            self.close_connection = True
            self.log_message("connection dropped: %s", type(e).__name__)

    def caller_gone(self):
        """Whether the caller has closed its end, as Funnel does once the caller gives up on a
        long-poll: what it would have been told is then for no one."""
        try:
            readable, _, _ = select.select([self.connection], [], [], 0)
            return bool(readable) and not self.connection.recv(1, socket.MSG_PEEK)
        except (OSError, ValueError):
            return True

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

    def send(self, code, body=b"", ctype="text/plain; charset=utf-8", headers=None, packed=None):
        """[packed]: what gives [body] gzipped, if it has been already (StaticFile.packed)."""
        if isinstance(body, str):
            body = body.encode()
        headers = {"Cache-Control": "no-store", **(headers or {})}
        worth_packing = len(body) > 1024 and not ctype.startswith("font/")  # woff2 is packed already
        if worth_packing and "gzip" in (self.headers.get("Accept-Encoding") or ""):
            body = packed() if packed else gzip.compress(body, 6)
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
        right, elsewhere = None, False
        try:
            # A password already found right is a session, not a login, and settles nothing: the
            # phone sending it with every sync cleared the count for its whole address, so a
            # guesser behind the same one never ran out of guesses. Nor does a device token, which
            # is never a password; a wrong one counts as a wrong password does.
            user = cached_login(password)
            if not user:
                user = check_login(password)
                right = user is not None
            if not user:
                user, elsewhere = device_tokens().check(password)
                right = None if user or elsewhere else False
        finally:
            if password:
                settle_guess(addr, (basic_login(password) or ("",))[0], right)
        if elsewhere:
            # Its secret was right, so it guessed nothing; a 401 would say the token is wrong.
            self.send(403, "this device's login doesn't reach xrnloops")
            return None
        if not user:
            if password:  # a request with none guessed nothing, and needn't hold a thread for it
                time.sleep(FAIL_DELAY_S)
            # The web app logs in with its own form: a challenge would pop the browser's dialog.
            challenge = {} if self.headers.get("X-Xrn-Client") else {"WWW-Authenticate": 'Basic realm="xrnloops"'}
            self.send(401, "login required", headers=challenge)
            return None
        return user

    def read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            raise ValueError("body missing or too large")
        raw = self.rfile.read(length)
        if len(raw) < length:
            # The connection closed part way: what came is no document, so it isn't read as one.
            self.close_connection = True
            raise ValueError("the upload stopped short")
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
        try:
            body = json.loads(raw)
            deep = not nested_within(body, MAX_DEPTH)
        except RecursionError:  # past the parser's depth: not a ValueError, and it ended with no answer
            deep = True
        if deep:
            raise ValueError(f"body nested deeper than {MAX_DEPTH} levels")
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
                held = time.monotonic()
                page = store.changes(since, wait, epoch, gone=self.caller_gone if wait > 0 else None)
                if page is None:
                    self.close_connection = True
                    return self.log_message("caller left a long-poll after %.0fs", time.monotonic() - held)
                return self.send_json(page)
        except (KeyError, ValueError, OverflowError) as e:
            return self.send(400, f"bad request: {e}")
        self.send(404, "not found")

    do_HEAD = do_GET

    def do_POST(self):
        path, _ = self.route()
        if path == "/api/session":
            # Only a password signs in: a cookie can't be stretched into a new one, and a device
            # token can't become a cookie, which every page here would take for 30 days.
            if not (self.headers.get("Authorization") or "").startswith("Basic "):
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
        except ConnectionError:
            raise  # the caller hung up mid-upload, which isn't a failed write here: see handle()
        except (OSError, sqlite3.Error) as e:
            sys.stderr.write(f"write failed: {e!r}\n")
            return self.send(500, "server error")
        self.send_json(result)

    def do_DELETE(self):
        path, _ = self.route()
        if path != "/api/session":
            return self.send(404, "not found")
        # Emptying the browser's cookie alone left any copy of it signing in for its full 30 days.
        end_session(self.headers.get("Cookie"))
        self.send(200, '{"ok":true}', "application/json", {"Set-Cookie": session_cookie("", 0)})

    def static(self, path):
        if path in ("", "/"):
            path = "/index.html"
        name = os.path.normpath(path.lstrip("/"))
        file = os.path.join(args.web, name)
        if name.startswith("..") or not os.path.isfile(file):
            return self.send(404, "not found")
        web_file, read = static_file(file)
        if name == "index.html" and read:  # read again, so its scripts' hashes are too
            SECURITY_HEADERS["Content-Security-Policy"] = content_policy(args.web)
        ctype = mimetypes.guess_type(file)[0] or "application/octet-stream"
        if file.endswith(".webmanifest"):
            ctype = "application/manifest+json"
        if ctype.startswith("text/") or ctype in ("application/javascript", "application/json", "image/svg+xml"):
            ctype += "; charset=utf-8"
        # A font never changes under one name. Anything else may with a deploy, so the browser
        # asks each time, which costs a 304 instead of the file.
        cache = "public, max-age=2592000" if name.startswith("fonts/") else "no-cache"
        headers = {"ETag": web_file.tag, "Cache-Control": cache, "Vary": "Accept-Encoding"}
        if web_file.tag in (self.headers.get("If-None-Match") or ""):
            return self.send(304, headers=headers)
        self.send(200, web_file.data, ctype, headers, packed=web_file.packed)


def main():
    global args, session_key
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=5234)
    p.add_argument("--data", required=True, help="where the habit databases live")
    p.add_argument("--users", required=True, help="htpasswd file with bcrypt hashes")
    p.add_argument("--web", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "web"),
                   help="the web app's folder")
    p.add_argument("--auto", default="~/.config/xrnloops/auto.json",
                   help="rules for habits that tick themselves from a personal hub; none without the file")
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
    threading.Thread(target=auto_loop, args=(os.path.expanduser(args.auto),), daemon=True).start()
    sys.stderr.write(f"xrnloops-sync on http://{args.host}:{args.port}, data in {args.data}\n")
    srv.serve_forever()


if __name__ == "__main__":
    main()
