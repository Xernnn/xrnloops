# Copyright (C) 2026 the xrnhabit authors
#
# This file is part of Loop Habit Tracker (the xrnloops fork). GPLv3 or later; see
# xrnloops-sync.py for the notice.
"""Tests for xrnloops-sync.py: Loop's rules (checked against uhabits-core's EntryListTest), the
last-writer-wins store, and the HTTP layer.

    python3 -m unittest server/test_xrnloops_sync.py     (from the repo root)
"""
import base64
import importlib.util
import json
import os
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from datetime import date, timedelta
from http.server import ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("xrnloops_sync", os.path.join(HERE, "xrnloops-sync.py"))
srv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(srv)

UNKNOWN, NO, YES_AUTO, YES_MANUAL, SKIP = srv.UNKNOWN, srv.NO, srv.YES_AUTO, srv.YES_MANUAL, srv.SKIP
TODAY = date(2015, 1, 25)


def day(offset):
    return TODAY - timedelta(days=offset)


def habit(**kw):
    return srv.clean_habit_data({"name": "Plank", **kw})


class LoopRulesTest(unittest.TestCase):
    """The cases from uhabits-core's EntryListTest, so the port can't drift from Loop."""

    def test_compute_boolean(self):
        entries = {day(4): YES_MANUAL, day(9): YES_MANUAL, day(10): YES_MANUAL}
        computed = srv.computed_entries(habit(freqNum=1, freqDen=3), entries)
        expected = {
            day(2): YES_AUTO, day(3): YES_AUTO, day(4): YES_MANUAL, day(7): YES_AUTO, day(8): YES_AUTO,
            day(9): YES_MANUAL, day(10): YES_MANUAL, day(11): YES_AUTO, day(12): YES_AUTO,
        }
        self.assertEqual(expected, computed)
        self.assertEqual({}, srv.computed_entries(habit(freqNum=1, freqDen=3), {}))

    def test_compute_numerical_keeps_entries(self):
        entries = {day(4): 100, day(9): 200, day(10): 300}
        self.assertEqual(entries, srv.computed_entries(habit(type=srv.NUMERICAL), entries))

    def test_add_from_interval(self):
        original = [(day(1), YES_MANUAL), (day(2), NO), (day(4), NO), (day(5), YES_MANUAL),
                    (day(10), YES_MANUAL), (day(11), NO)]
        intervals = [[day(2), day(2), day(1)], [day(6), day(5), day(4)], [day(10), day(8), day(8)]]
        expected = {
            day(1): YES_MANUAL, day(2): YES_AUTO, day(3): UNKNOWN, day(4): YES_AUTO, day(5): YES_MANUAL,
            day(6): YES_AUTO, day(7): UNKNOWN, day(8): YES_AUTO, day(9): YES_AUTO, day(10): YES_MANUAL,
            day(11): NO,
        }
        self.assertEqual(expected, srv.build_entries_from_intervals(original, intervals))

    def test_snap_intervals_together(self):
        original = [[day(8), day(8), day(2)], [day(12), day(12), day(6)],
                    [day(20), day(20), day(14)], [day(27), day(27), day(21)]]
        srv.snap_intervals_together(original)
        self.assertEqual([[day(8), day(8), day(2)], [day(15), day(12), day(9)],
                          [day(22), day(20), day(16)], [day(29), day(27), day(23)]], original)
        original = [[day(6), day(4), day(0)], [day(11), day(8), day(5)]]
        srv.snap_intervals_together(original)
        self.assertEqual([[day(6), day(4), day(0)], [day(13), day(8), day(7)]], original)

    def test_build_intervals(self):
        entries = [(day(8), YES_MANUAL), (day(18), YES_MANUAL), (day(23), YES_MANUAL)]
        self.assertEqual([[day(8), day(8), day(2)], [day(18), day(18), day(12)], [day(23), day(23), day(17)]],
                         srv.build_intervals(1, 7, entries))
        self.assertEqual([[day(8), day(8), day(8)], [day(18), day(18), day(18)], [day(23), day(23), day(23)]],
                         srv.build_intervals(1, 1, entries))
        entries = [(day(8), YES_MANUAL), (day(15), YES_MANUAL), (day(18), YES_MANUAL),
                   (day(22), YES_MANUAL), (day(23), YES_MANUAL)]
        self.assertEqual([[day(18), day(15), day(12)], [day(22), day(18), day(16)], [day(23), day(22), day(17)]],
                         srv.build_intervals(2, 7, entries))
        entries = [(day(10), YES_MANUAL), (day(20), SKIP), (day(30), YES_MANUAL)]
        self.assertEqual([[day(10), day(10), day(8)], [day(30), day(30), day(28)]],
                         srv.build_intervals(1, 3, entries))

    def test_status_matches_provider(self):
        weekly = habit(freqNum=3, freqDen=7)
        entries = {day(1): YES_MANUAL, day(2): YES_MANUAL, day(3): YES_MANUAL}
        # Three ticks this week: the other days count as goal met (Loop's YES_AUTO), not done.
        s = srv.habit_status(weekly, entries, day(0))
        self.assertEqual((False, True, False), (s["done"], s["goal_met"], s["daily"]))
        s = srv.habit_status(weekly, entries, day(1))
        self.assertEqual((True, True), (s["done"], s["goal_met"]))
        # An auto-check or a skip is never "done".
        daily = habit()
        self.assertFalse(srv.habit_status(daily, {day(0): YES_AUTO}, day(0))["done"])
        self.assertFalse(srv.habit_status(daily, {day(0): SKIP}, day(0))["done"])
        self.assertTrue(srv.habit_status(daily, {day(0): YES_MANUAL}, day(0))["daily"])
        # Numerical: "at least" counts once the target is reached; "at most" never does.
        pushups = habit(type=srv.NUMERICAL, targetValue=20)
        self.assertTrue(srv.habit_status(pushups, {day(0): 20_000}, day(0))["done"])
        self.assertFalse(srv.habit_status(pushups, {day(0): 19_000}, day(0))["done"])
        sugar = habit(type=srv.NUMERICAL, targetType=srv.AT_MOST, targetValue=2)
        s = srv.habit_status(sugar, {day(0): 0}, day(0))
        self.assertEqual((False, False), (s["countable"], s["done"]))


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

    def test_identical_write_does_not_bump_seq(self):
        self.store.apply({"entries": [self.entry(YES_MANUAL, 100)]})
        seq = self.store.seq()
        self.store.apply({"entries": [self.entry(YES_MANUAL, 200)]})
        self.assertEqual(seq, self.store.seq())

    def test_deleted_habit_leaves_status(self):
        self.store.apply({"habits": [{"uuid": "h1", "mtime": 1, "data": {"name": "Plank"}},
                                     {"uuid": "h2", "mtime": 1, "data": {"name": "Read"}}]})
        self.store.apply({"habits": [{"uuid": "h2", "mtime": 2, "deleted": True, "data": {"name": "Read"}}]})
        names = [h["name"] for h in self.store.status(date(2026, 10, 16))["habits"]]
        self.assertEqual(["Plank"], names)

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
        _, text, _ = self.request("/api/status?date=2026-10-16")
        plank = json.loads(text)["habits"][0]
        self.assertEqual(("Plank", True, True), (plank["name"], plank["done"], plank["goal_met"]))
        _, text, _ = self.request("/api/changes?since=0")
        self.assertEqual(["abc"], [h["uuid"] for h in json.loads(text)["habits"]])


if __name__ == "__main__":
    unittest.main()
