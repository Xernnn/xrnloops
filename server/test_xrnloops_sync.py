# Copyright (C) 2026 the xrnrule authors
#
# This file is part of Loop Habit Tracker (the xrnloops fork). GPLv3 or later; see
# xrnloops-sync.py for the notice.
"""Tests for xrnloops-sync.py: Loop's rules (checked against uhabits-core's EntryListTest), the
last-writer-wins store, and the HTTP layer.

    python3 -m unittest server/test_xrnloops_sync.py     (from the repo root)
"""
import base64
import concurrent.futures
import hashlib
import gzip
import hashlib
import http.client
import importlib.util
import json
import os
import socket
import tempfile
import threading
import time
import unittest
import unittest.mock
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("xrnloops_sync", os.path.join(HERE, "xrnloops-sync.py"))
srv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(srv)

NO, YES_MANUAL, SKIP = 0, 2, 3  # as Entry has them in uhabits-core

class StoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = srv.Store(os.path.join(self.tmp.name, "loop.sqlite"))

    def tearDown(self):
        self.store.db.close()
        self.tmp.cleanup()

    def entry(self, value, mtime, d="2026-10-16", notes=""):
        return {"habit": "h1", "date": d, "value": value, "notes": notes, "mtime": mtime}

    def test_last_writer_wins_per_entry(self):
        self.store.apply({"entries": [self.entry(YES_MANUAL, 100)]})
        result = self.store.apply({"entries": [self.entry(NO, 50)]})  # older: loses
        self.assertEqual(YES_MANUAL, result["rejected"]["entries"][0]["value"])
        self.store.apply({"entries": [self.entry(NO, 150)]})
        changes = self.store.changes(0)
        self.assertEqual([NO], [e["value"] for e in changes["entries"]])

    def test_tie_is_decided_the_same_everywhere(self):
        self.store.apply({"entries": [self.entry(NO, 100)]})
        self.store.apply({"entries": [self.entry(YES_MANUAL, 100)]})
        self.assertEqual(YES_MANUAL, self.store.changes(0)["entries"][0]["value"])

    def test_if_absent_only_fills_gaps(self):
        self.store.apply({"entries": [self.entry(YES_MANUAL, 100)]})
        result = self.store.apply({"ifAbsent": True, "entries": [self.entry(NO, 999), self.entry(SKIP, 1, "2026-10-15")]})
        self.assertEqual(1, len(result["rejected"]["entries"]))
        values = {e["date"]: e["value"] for e in self.store.changes(0)["entries"]}
        self.assertEqual({"2026-10-16": YES_MANUAL, "2026-10-15": SKIP}, values)

    def test_changes_since_and_paging(self):
        self.store.apply({"habits": [{"uuid": "h1", "mtime": 1, "data": {"name": "Plank"}}]})
        first = self.store.changes(0)
        self.store.apply({"entries": [self.entry(YES_MANUAL, 2)]})
        later = self.store.changes(first["seq"])
        self.assertEqual([], later["habits"])
        self.assertEqual(1, len(later["entries"]))
        old_page = srv.PAGE_ROWS
        srv.PAGE_ROWS = 2
        try:
            self.store.apply({"entries": [self.entry(YES_MANUAL, 3, f"2026-09-{d:02d}") for d in range(1, 6)]})
            seen, since, more = 0, 0, True
            while more:
                page = self.store.changes(since)
                seen += len(page["habits"]) + len(page["entries"])
                since, more = page["seq"], page["more"]
            self.assertEqual(7, seen)
        finally:
            srv.PAGE_ROWS = old_page

    def test_a_write_from_the_stored_version_wins_whatever_the_clock(self):
        first = self.store.apply({"entries": [self.entry(5000, 100)]})["accepted"]["entries"][0]["seq"]
        # Made from the stored version: taken, though its clock is behind.
        result = self.store.apply({"entries": [{**self.entry(7000, 50), "baseSeq": first}]})
        self.assertEqual([], result["rejected"]["entries"])
        second = result["accepted"]["entries"][0]["seq"]
        self.assertGreater(second, first)
        # Made from the old version: refused, though its clock is ahead; the newer one comes back.
        result = self.store.apply({"entries": [{**self.entry(6000, 999), "baseSeq": first}]})
        self.assertEqual([], result["accepted"]["entries"])
        self.assertEqual({"value": 7000, "seq": second},
                         {k: result["rejected"]["entries"][0][k] for k in ("value", "seq")})
        self.assertEqual(second, self.store.changes(0)["entries"][0]["seq"])

    def test_a_client_past_the_seq_gets_everyone_a_new_epoch(self):
        self.store.apply({"entries": [self.entry(YES_MANUAL, 1)]})
        first = self.store.changes(0)
        epoch = first["epoch"]
        self.assertEqual(epoch, self.store.changes(first["seq"], epoch=epoch)["epoch"])  # nothing went back
        # Restored from a backup: a client has read further than the server has written.
        answer = self.store.changes(first["seq"] + 5, wait_s=30, epoch=epoch)  # answers at once
        self.assertNotEqual(epoch, answer["epoch"])
        # Another client, behind the server's seq, still hears of it; the new epoch stays put.
        self.assertEqual(answer["epoch"], self.store.changes(0, epoch=epoch)["epoch"])
        self.assertEqual(answer["epoch"], self.store.changes(first["seq"] + 5, epoch=epoch)["epoch"])
        self.assertEqual(answer["epoch"], self.store.changes(0)["epoch"])

    def test_a_write_cant_hide_a_restore(self):
        self.store.apply({"entries": [self.entry(YES_MANUAL, 1, f"2026-10-{d:02d}") for d in range(1, 6)]})
        page = self.store.changes(0)
        epoch, read = page["epoch"], page["seq"] + 3  # the client read 3 changes the backup lacks
        seq = self.store.seq()
        # Enough records to move seq past what the client read, had they gone in.
        many = [self.entry(NO, 9, f"2026-09-{d:02d}") for d in range(1, 11)]
        with self.assertRaises(srv.StaleEpoch) as refused:
            self.store.apply({"epoch": epoch, "since": read, "entries": many})
        self.assertNotEqual(epoch, refused.exception.epoch)
        self.assertEqual(seq, self.store.seq())  # nothing written
        # Every other client hears of it too, and a write from the new history goes in.
        self.assertEqual(refused.exception.epoch, self.store.changes(0, epoch=epoch)["epoch"])
        self.store.apply({"epoch": refused.exception.epoch, "since": 0, "entries": many})
        self.assertEqual(seq + len(many), self.store.seq())

    def test_a_write_from_the_current_history_goes_in(self):
        page = self.store.changes(0)
        result = self.store.apply({"epoch": page["epoch"], "since": page["seq"], "entries": [self.entry(YES_MANUAL, 1)]})
        self.assertEqual(1, len(result["accepted"]["entries"]))
        for bad in ({"epoch": "1"}, {"epoch": page["epoch"], "since": True}):
            with self.assertRaises(ValueError):
                self.store.apply({**bad, "entries": []})

    def test_a_write_that_fails_half_way_leaves_nothing(self):
        with self.assertRaises(ValueError):
            self.store.apply({"entries": [self.entry(YES_MANUAL, 1), {"habit": "h1", "date": "not a day", "value": 2, "mtime": 1}]})
        self.store.apply({"entries": [self.entry(NO, 5, "2026-10-01")]})
        self.assertEqual(["2026-10-01"], [e["date"] for e in self.store.changes(0)["entries"]])

    def test_a_habit_write_keeps_the_fields_it_leaves_out(self):
        self.store.apply({"habits": [{"uuid": "h1", "mtime": 1, "data": {"name": "Read", "icon": "book", "timer": True}}]})
        # xrnloops doesn't know icon and timer: an edit from it keeps them.
        self.store.apply({"habits": [{"uuid": "h1", "mtime": 2, "data": {"name": "Read more"}}]})
        data = self.store.changes(0)["habits"][0]["data"]
        self.assertEqual(("Read more", "book", True), (data["name"], data["icon"], data["timer"]))
        result = self.store.apply({"habits": [{"uuid": "h1", "mtime": 3, "baseSeq": 0, "data": {"name": "Stale"}}]})
        self.assertEqual("Read more", result["rejected"]["habits"][0]["data"]["name"])

    def test_bad_base_seq_is_refused(self):
        with self.assertRaises(ValueError):
            self.store.apply({"entries": [{**self.entry(1, 1), "baseSeq": "3"}]})

    def test_identical_write_does_not_bump_seq(self):
        self.store.apply({"entries": [self.entry(YES_MANUAL, 100)]})
        seq = self.store.seq()
        self.store.apply({"entries": [self.entry(YES_MANUAL, 200)]})
        self.assertEqual(seq, self.store.seq())

    def test_a_deleted_habit_stays_in_the_feed_marked_deleted(self):
        self.store.apply({"habits": [{"uuid": "h1", "mtime": 1, "data": {"name": "Plank"}},
                                     {"uuid": "h2", "mtime": 1, "data": {"name": "Read"}}]})
        self.store.apply({"habits": [{"uuid": "h2", "mtime": 2, "deleted": True, "data": {"name": "Read"}}]})
        deleted = {h["uuid"]: h["deleted"] for h in self.store.changes(0)["habits"]}
        self.assertEqual({"h1": False, "h2": True}, deleted)

    def test_long_poll_wakes_on_write(self):
        seq = self.store.seq()
        threading.Timer(0.2, lambda: self.store.apply({"entries": [self.entry(YES_MANUAL, 1)]})).start()
        start = time.monotonic()
        result = self.store.changes(seq, wait_s=5)
        self.assertLess(time.monotonic() - start, 3)
        self.assertEqual(1, len(result["entries"]))

    def test_bad_habit_data_is_refused(self):
        with self.assertRaises(ValueError):
            self.store.apply({"habits": [{"uuid": "h1", "mtime": 1, "data": {"freqNum": 0}}]})

    def test_day_start_hour_is_kept_and_wakes_long_polls(self):
        self.assertEqual(0, self.store.changes(0)["dayStartHour"])
        seq = self.store.seq()
        threading.Timer(0.2, lambda: self.store.apply({"dayStartHour": 3})).start()
        start = time.monotonic()
        page = self.store.changes(seq, wait_s=5)
        self.assertLess(time.monotonic() - start, 3)
        self.assertEqual(3, page["dayStartHour"])
        self.assertEqual(3, self.store.changes(0)["dayStartHour"])
        seq = self.store.seq()
        self.store.apply({"dayStartHour": 3, "entries": []})  # unchanged: no new seq
        self.assertEqual(seq, self.store.seq())
        for bad in (24, -1, "3", True):
            with self.assertRaises(ValueError):
                self.store.apply({"dayStartHour": bad})


class HttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import bcrypt

        cls.tmp = tempfile.TemporaryDirectory()
        users = os.path.join(cls.tmp.name, "htpasswd")
        with open(users, "w") as f:
            f.write("sonn:" + bcrypt.hashpw(b"secret", bcrypt.gensalt(4)).decode() + "\n")
        web = os.path.join(cls.tmp.name, "web")
        os.makedirs(web)
        with open(os.path.join(web, "index.html"), "w") as f:
            f.write("<!doctype html>hi")
        srv.args = type("Args", (), {"data": os.path.join(cls.tmp.name, "data"), "users": users, "web": web})()
        srv.FAIL_DELAY_S = 0
        srv.session_key = srv.load_session_key()
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), srv.Handler)
        cls.base = f"http://127.0.0.1:{cls.httpd.server_address[1]}"
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        for s in srv.stores.values():
            s.db.close()
        srv.stores.clear()
        cls.tmp.cleanup()

    def request(self, path, body=None, password="secret", headers=None):
        req = urllib.request.Request(self.base + path, method="POST" if body is not None else "GET",
                                     data=json.dumps(body).encode() if body is not None else None)
        req.add_header("Authorization", "Basic " + base64.b64encode(f"sonn:{password}".encode()).decode())
        req.add_header("Content-Type", "application/json")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read().decode(), r.headers

    def test_health_and_static_need_no_login(self):
        with urllib.request.urlopen(self.base + "/xrnloops/health") as r:
            self.assertEqual("ok", r.read().decode())
        with urllib.request.urlopen(self.base + "/") as r:
            self.assertIn("hi", r.read().decode())
        with self.assertRaises(urllib.error.HTTPError) as e:
            urllib.request.urlopen(self.base + "/../users")
        self.assertEqual(404, e.exception.code)

    def test_wrong_password_and_web_client_challenge(self):
        with self.assertRaises(urllib.error.HTTPError) as e:
            self.request("/api/changes?since=0", password="nope")
        self.assertEqual(401, e.exception.code)
        self.assertIn("Basic", e.exception.headers.get("WWW-Authenticate"))
        with self.assertRaises(urllib.error.HTTPError) as e:
            self.request("/api/changes?since=0", password="nope", headers={"X-Xrn-Client": "web"})
        self.assertIsNone(e.exception.headers.get("WWW-Authenticate"))

    def test_round_trip(self):
        body = {"habits": [{"uuid": "abc", "mtime": 1, "data": {"name": "Plank"}}],
                "entries": [{"habit": "abc", "date": "2026-10-16", "value": YES_MANUAL, "mtime": 1}]}
        status, text, _ = self.request("/xrnloops/api/changes", body)
        self.assertEqual(200, status)
        _, text, _ = self.request("/api/changes?since=0")
        feed = json.loads(text)
        self.assertEqual(["abc"], [h["uuid"] for h in feed["habits"]])
        self.assertEqual([("2026-10-16", YES_MANUAL)], [(e["date"], e["value"]) for e in feed["entries"]])

    def status_of(self, path, **kw):
        try:
            return self.request(path, **kw)[0]
        except urllib.error.HTTPError as e:
            return e.code

    def test_wrong_passwords_lock_out_that_address_only(self):
        guesser = {"X-Forwarded-For": "203.0.113.9"}  # as Funnel's proxy passes it on
        for _ in range(srv.FAIL_LIMIT):
            self.assertEqual(401, self.status_of("/api/changes?since=0", password="nope", headers=guesser))
        # Locked out now, even with the right password; everyone else isn't.
        self.assertEqual(429, self.status_of("/api/changes?since=0", headers=guesser))
        self.assertEqual(200, self.status_of("/api/changes?since=0", headers={"X-Forwarded-For": "203.0.113.10"}))

    def test_a_made_up_first_hop_doesnt_dodge_the_lockout(self):
        for i in range(srv.FAIL_LIMIT):
            forwarded = {"X-Forwarded-For": f"198.51.100.{i}, 203.0.113.20"}  # what a proxy appends to
            self.assertEqual(401, self.status_of("/api/changes?since=0", password="nope", headers=forwarded))
        forwarded = {"X-Forwarded-For": "198.51.100.200, 203.0.113.20"}
        self.assertEqual(429, self.status_of("/api/changes?since=0", headers=forwarded))

    def test_an_ipv6_guesser_is_its_64_not_each_address_in_it(self):
        for i in range(srv.FAIL_LIMIT):  # a new address of its own /64 for every guess
            guesser = {"X-Forwarded-For": f"2001:db8:1:2::{i + 1:x}"}
            self.assertEqual(401, self.status_of("/api/changes?since=0", password="nope", headers=guesser))
        self.assertEqual(429, self.status_of("/api/changes?since=0", headers={"X-Forwarded-For": "2001:db8:1:2:aaaa::1"}))
        self.assertEqual(200, self.status_of("/api/changes?since=0", headers={"X-Forwarded-For": "2001:db8:1:3::1"}))
        self.assertEqual("203.0.113.40", srv.guesser("::ffff:203.0.113.40"))
        self.assertEqual("not an address", srv.guesser("not an address"))

    def test_a_name_that_isnt_there_costs_a_check_like_one_that_is(self):
        import bcrypt

        def checks(login):
            header = "Basic " + base64.b64encode(login.encode()).decode()
            with unittest.mock.patch.object(bcrypt, "checkpw", wraps=bcrypt.checkpw) as checkpw:
                self.assertIsNone(srv.check_login(header))
            return checkpw.call_count
        self.assertEqual(1, checks("sonn:nope"))
        self.assertEqual(1, checks("nobody:nope"))  # none: the answer came sooner, and said so
        self.assertIsNone(srv.check_login("Basic " + base64.b64encode(b"nobody:secret").decode()))

    def test_a_request_with_no_password_isnt_held(self):
        # The hold after a wrong password slows a guesser down. One that sent no password guessed
        # nothing, and each held a thread for the hold all the same.
        def timed(addr, **headers):
            start = time.monotonic()
            status = self.raw("GET", "/api/changes?since=0", headers={"X-Forwarded-For": addr, **headers})[0]
            return status, time.monotonic() - start
        srv.failures["203.0.113.61"] = [time.monotonic()] * srv.FAIL_LIMIT  # locked out
        with unittest.mock.patch.object(srv, "FAIL_DELAY_S", 0.5):
            for addr, status in (("203.0.113.60", 401), ("203.0.113.61", 429)):
                got, took = timed(addr)
                self.assertEqual(status, got)
                self.assertLess(took, 0.4, addr)
                got, took = timed(addr, Authorization=self.basic("nope"))
                self.assertEqual(status, got)
                self.assertGreaterEqual(took, 0.5, addr)

    def test_guesses_sent_at_once_still_stop_at_the_limit(self):
        def slow_wrong(header):  # bcrypt takes a while: every guess is in flight at once
            time.sleep(0.3)
            return None
        guesser = {"X-Forwarded-For": "203.0.113.30"}
        limit, n = 3, 12  # a limit the test server's listen backlog lets every guess reach at once
        start = threading.Barrier(n)
        def guess(_):
            start.wait()
            return self.status_of("/api/changes?since=0", password="nope", headers=guesser)
        with unittest.mock.patch.object(srv, "check_login", slow_wrong), unittest.mock.patch.object(srv, "FAIL_LIMIT", limit):
            with concurrent.futures.ThreadPoolExecutor(n) as pool:
                statuses = list(pool.map(guess, range(n)))
        self.assertEqual(limit, statuses.count(401))
        self.assertEqual(n - limit, statuses.count(429))

    def test_numbers_that_arent(self):
        self.assertEqual(400, self.status_of("/api/changes?since=0&wait=nan"))
        self.assertEqual(400, self.status_of("/api/changes?since=0&wait=inf"))
        for bad in (b'{"habits":[{"uuid":"n","mtime":1,"data":{"targetValue":NaN}}],"entries":[]}',
                    b'{"habits":[{"uuid":"n","mtime":1,"data":{"targetValue":1e999}}],"entries":[]}',
                    b'{"habits":[{"uuid":"n","mtime":1e999,"data":{}}],"entries":[]}',
                    b'{"habits":[],"entries":[{"habit":"n","date":"2026-10-16","value":1180591620717411303424,"mtime":1}]}'):
            token = base64.b64encode(b"sonn:secret").decode()
            status, _ = self.raw("POST", "/api/changes", bad, {"Authorization": f"Basic {token}"})
            self.assertEqual(400, status, bad)

    def test_a_write_from_a_lost_history_gets_409(self):
        _, text, _ = self.request("/api/changes?since=0")
        feed = json.loads(text)
        body = json.dumps({"epoch": feed["epoch"], "since": feed["seq"] + 100, "entries": []}).encode()
        status, res = self.raw("POST", "/api/changes", body, {"Authorization": self.basic(), "Content-Type": "application/json"})
        self.assertEqual(409, status)
        _, text, _ = self.request("/api/changes?since=0")
        self.assertNotEqual(feed["epoch"], json.loads(text)["epoch"])

    def test_numbers_too_big_for_the_database(self):
        self.assertEqual(400, self.status_of("/api/changes?since=" + "9" * 30))

    def fetch(self, path, **headers):
        """Unlike request(): the raw status and headers, for what the browser's cache sees."""
        req = urllib.request.Request(f"{self.base}{path}", headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, r.headers, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.headers, e.read()

    def web_file(self, name, data):
        path = os.path.join(srv.args.web, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)
        self.addCleanup(lambda: os.path.exists(path) and os.remove(path))
        return path

    def test_a_file_that_has_not_changed_costs_a_304(self):
        self.web_file("app.js", b"// the app")
        status, headers, body = self.fetch("/app.js")
        self.assertEqual(200, status)
        self.assertEqual("no-cache", headers["Cache-Control"])
        self.assertGreater(len(body), 0)
        again = self.fetch("/app.js", **{"If-None-Match": headers["ETag"]})
        self.assertEqual((304, b""), (again[0], again[2]))
        self.assertEqual(headers["ETag"], again[1]["ETag"])
        self.assertIsNone(again[1]["Content-Length"])  # it would describe the file, not this answer
        self.assertEqual(200, self.fetch("/app.js", **{"If-None-Match": '"stale"'})[0])

    def test_a_deploy_changes_the_tag(self):
        self.web_file("app.js", b"// the app")
        tag = self.fetch("/app.js")[1]["ETag"]
        self.web_file("app.js", b"// the next release")
        status, headers, _ = self.fetch("/app.js", **{"If-None-Match": tag})
        self.assertEqual(200, status)
        self.assertNotEqual(tag, headers["ETag"])

    def test_fonts_are_kept_and_not_packed_again(self):
        self.web_file("fonts/a.woff2", os.urandom(4096))
        status, headers, _ = self.fetch("/fonts/a.woff2", **{"Accept-Encoding": "gzip"})
        self.assertEqual(200, status)
        self.assertIn("max-age", headers["Cache-Control"])
        self.assertIsNone(headers["Content-Encoding"])

    def test_the_data_is_never_kept(self):
        token = base64.b64encode(b"sonn:secret").decode()
        status, headers, _ = self.fetch("/api/changes?since=0", Authorization=f"Basic {token}")
        self.assertEqual((200, "no-store"), (status, headers["Cache-Control"]))
        self.assertIsNone(headers["ETag"])

    def test_the_policy_follows_index_html(self):
        page = os.path.join(srv.args.web, "index.html")
        with open(page) as f:
            original = f.read()
        def restore():
            with open(page, "w") as f:
                f.write(original)
        self.addCleanup(restore)
        for script in ("first()", "second()"):
            with open(page, "w") as f:
                f.write(f"<script>{script}</script>")
            digest = base64.b64encode(hashlib.sha256(script.encode()).digest()).decode()
            with urllib.request.urlopen(self.base + "/") as r:
                self.assertIn(digest, r.headers["Content-Security-Policy"])

    def test_head_answers_like_get_with_no_body(self):
        status, res = self.raw("HEAD", "/health")
        self.assertEqual(200, status)
        self.assertEqual("2", res.headers["Content-Length"])

    def test_a_null_string_field_is_empty(self):
        self.assertEqual("", srv.clean_habit_data({"name": "N", "unit": None})["unit"])

    def test_a_cookie_with_a_non_ascii_mac_is_just_refused(self):
        self.assertIsNone(srv.check_session(f"{srv.SESSION_COOKIE}=c29ubg.9999999999.\u00e9\u00e9"))

    def test_an_unparseable_request_gets_an_answer(self):
        with socket.create_connection(("127.0.0.1", self.httpd.server_address[1]), timeout=10) as s:
            s.sendall(b"GARBAGE\r\n\r\n")
            self.assertIn(b"400", s.recv(200))

    def test_a_connection_that_goes_quiet_is_let_go(self):
        # With no timeout, a client that stopped half way through its request held a thread for good.
        self.assertGreater(srv.Handler.timeout, srv.MAX_WAIT_S)  # and longer than any long poll
        with unittest.mock.patch.object(srv.Handler, "timeout", 0.3):
            with socket.create_connection(("127.0.0.1", self.httpd.server_address[1]), timeout=10) as s:
                s.sendall(b"GET /api/changes?since=0 HTTP/1.1\r\nHost: x\r\n")  # and then nothing
                start = time.monotonic()
                self.assertEqual(b"", s.recv(100))
                self.assertLess(time.monotonic() - start, 5)

    def raw(self, method, path, body=b"", headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_address[1], timeout=10)
        conn.request(method, path, body=body, headers=headers or {})
        res = conn.getresponse()
        res.read()
        conn.close()
        return res.status, res

    def basic(self, password="secret"):
        return "Basic " + base64.b64encode(f"sonn:{password}".encode()).decode()

    def test_the_web_app_signs_in_with_a_cookie_scripts_cannot_read(self):
        status, res = self.raw("POST", "/xrnloops/api/session", headers={"Authorization": self.basic(), "X-Xrn-Client": "web"})
        self.assertEqual(200, status)
        set_cookie = res.getheader("Set-Cookie")
        for flag in ("HttpOnly", "Secure", "SameSite=Strict", "Path=/"):
            self.assertIn(flag, set_cookie)
        cookie = set_cookie.split(";")[0]
        web = {"Cookie": cookie, "X-Xrn-Client": "web"}
        self.assertEqual(200, self.raw("GET", "/api/changes?since=0", headers=web)[0])
        # Without the header another site's page can't add, the cookie is worth nothing.
        self.assertEqual(401, self.raw("GET", "/api/changes?since=0", headers={"Cookie": cookie})[0])
        # Nor does a forged, tampered or missing one, and a cookie can't mint a new one.
        who, expires, mac = cookie.partition("=")[2].split(".")
        for bad in (f"{who}.{int(expires) + 1}.{mac}", f"{who}.{expires}.{'0' * 64}", "junk", ""):
            self.assertEqual(401, self.raw("GET", "/api/changes?since=0", headers={"Cookie": f"{srv.SESSION_COOKIE}={bad}", "X-Xrn-Client": "web"})[0])
        self.assertEqual(401, self.raw("POST", "/api/session", headers=web)[0])
        self.assertEqual(401, self.raw("POST", "/api/session", headers={"Authorization": self.basic("nope"), "X-Xrn-Client": "web"})[0])
        status, res = self.raw("DELETE", "/api/session", headers=web)
        self.assertEqual(200, status)
        self.assertIn("Max-Age=0", res.getheader("Set-Cookie"))

    def test_a_page_on_another_site_cant_write_with_the_browsers_password(self):
        # A browser sends the password it was once given with a form another site posts here,
        # and a text/plain form body can be JSON.
        forged = json.dumps({"habits": [{"uuid": "forged", "mtime": 1, "data": {"name": "Junk"}}]}).encode()
        def post(path, body, headers):
            headers = {"Authorization": self.basic(), "Content-Type": "text/plain", **headers}
            return self.raw("POST", path, body, headers)[0]
        for elsewhere in ({"Sec-Fetch-Site": "cross-site", "Origin": "https://evil.example"},
                          {"Sec-Fetch-Site": "same-site", "Origin": "https://other.homeserver.example"},
                          {"Origin": "https://evil.example"}):  # a browser from before Sec-Fetch-Site
            self.assertEqual(403, post("/api/changes", forged, elsewhere), elsewhere)
            self.assertEqual(403, post("/api/session", b"", elsewhere), elsewhere)
        self.assertNotIn("forged", self.request("/api/changes?since=0")[1])
        # Reads, this host's own pages (behind the proxy too), the extensions and the apps still do.
        self.assertEqual(200, self.raw("GET", "/api/changes?since=0", headers={
            "Authorization": self.basic(), "Sec-Fetch-Site": "cross-site"})[0])
        empty = json.dumps({"entries": []}).encode()
        for ours in ({"Sec-Fetch-Site": "same-origin", "Origin": "https://homeserver.example"},
                     {"Origin": f"http://127.0.0.1:{self.httpd.server_address[1]}"},
                     {"Origin": "https://HomeServer.example", "X-Forwarded-Host": "homeserver.example:443"},
                     {"Sec-Fetch-Site": "cross-site", "Origin": "chrome-extension://abcdefghijklmnop"},
                     {"Sec-Fetch-Site": "cross-site", "Origin": "moz-extension://8c2f5e7a-0000-4000-8000-000000000000"},
                     {"Sec-Fetch-Site": "none"},
                     {}):
            self.assertEqual(200, post("/api/changes", empty, ours), ours)

    def test_pages_cannot_be_framed_or_sniffed(self):
        _, res = self.raw("GET", "/")
        self.assertEqual("DENY", res.getheader("X-Frame-Options"))
        self.assertEqual("nosniff", res.getheader("X-Content-Type-Options"))
        self.assertEqual("no-referrer", res.getheader("Referrer-Policy"))

    def test_the_content_policy_allows_index_html_inline_scripts_by_hash(self):
        with tempfile.TemporaryDirectory() as web:
            with open(os.path.join(web, "index.html"), "w") as f:
                f.write("<head><script>go()</script><script type=module src=app.js></script></head>")
            policy = srv.content_policy(web)
        digest = base64.b64encode(hashlib.sha256(b"go()").digest()).decode()
        self.assertIn(f"script-src 'self' 'sha256-{digest}';", policy)
        self.assertIn("frame-ancestors 'none'", policy)

    def test_gzip_bodies_are_unpacked_only_so_far(self):
        headers = {"Authorization": self.basic(), "Content-Type": "application/json", "Content-Encoding": "gzip"}
        old, srv.MAX_BODY = srv.MAX_BODY, 64 * 1024
        try:
            bomb = gzip.compress(b'{"habits": [], "pad": "' + b"0" * (srv.MAX_BODY * 4) + b'"}')
            self.assertEqual(400, self.raw("POST", "/api/changes", bomb, headers)[0])
            self.assertEqual(400, self.raw("POST", "/api/changes", b"not gzip", headers)[0])
            fine = gzip.compress(json.dumps({"habits": [], "entries": []}).encode())
            self.assertEqual(200, self.raw("POST", "/api/changes", fine, headers)[0])
        finally:
            srv.MAX_BODY = old


if __name__ == "__main__":
    unittest.main()
