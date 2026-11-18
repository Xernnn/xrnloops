// The weekly review's figures (app.js, weekScore): days before a habit's first one aren't days it
// missed. A habit has no creation date; its history starts at its first entry, as Loop's score does.
import test from "node:test";
import assert from "node:assert/strict";
import * as L from "../loop.js";
import { openApp, store } from "./app.mjs";

const YES = 2;
const day = (s) => L.dayNum(s);

test.beforeEach(() => store.clear());

test("a habit begun on Thursday has met every day it had, not 4 of 7", async () => {
  const { app, habit } = await openApp(
    [{ uuid: "read", data: { name: "Read", type: 0, freqNum: 1, freqDen: 1 } }],
    ["2026-11-12", "2026-11-13", "2026-11-14", "2026-11-15"].map((date) => ({ habit: "read", date, value: YES, notes: "" })),
  );
  const week = app.weekScore([habit("read")], day("2026-11-09"), day("2026-11-15"));
  assert.deepEqual([week.met, week.possible], [4, 4]);
  // The week before it began counts for nothing, not as a week of misses.
  assert.deepEqual(app.weekScore([habit("read")], day("2026-11-02"), day("2026-11-08")).possible, 0);
});

test("a day missed after the first still counts as missed", async () => {
  const { app, habit } = await openApp(
    [{ uuid: "read", data: { name: "Read", type: 0, freqNum: 1, freqDen: 1 } }],
    ["2026-11-12", "2026-11-14"].map((date) => ({ habit: "read", date, value: YES, notes: "" })),
  );
  const week = app.weekScore([habit("read")], day("2026-11-09"), day("2026-11-15"));
  assert.deepEqual([week.met, week.possible], [2, 4]);
});

test("a number habit begins at its first amount too", async () => {
  const { app, habit } = await openApp(
    [{ uuid: "run", data: { name: "Run", type: 1, targetType: 0, targetValue: 5, unit: "km", freqNum: 1, freqDen: 1 } }],
    [{ habit: "run", date: "2026-11-14", value: 6000, notes: "" }],
  );
  const week = app.weekScore([habit("run")], day("2026-11-09"), day("2026-11-15"));
  assert.deepEqual([week.met, week.possible], [1, 2]);
});

// ---- the week as the review draws it (app.js, weekReview) ------------------------------------

const NO = 0, SKIP = 3;
const MON = "2026-11-09";
const week = (n) => L.dayStr(day(MON) + n);
const daily = (uuid, name, position, extra = {}) => ({ uuid, data: { name, type: 0, freqNum: 1, freqDen: 1, position, ...extra } });
const entry = (habit, n, value) => ({ habit, date: week(n), value, notes: "" });

test("each habit's days, its share done, each day's share, the perfect days, best and worst", async () => {
  const { app, habit } = await openApp(
    [daily("read", "Read", 0), daily("walk", "Walk", 1)],
    [
      ...[0, 1, 2, 3, 4, 5, 6].map((n) => entry("read", n, YES)),
      entry("walk", 0, YES), entry("walk", 1, YES), entry("walk", 2, NO), entry("walk", 3, SKIP), entry("walk", 4, YES),
    ],
  );
  const wk = app.weekReview([habit("read"), habit("walk")], day(MON), day(week(6)));
  const [read, walk] = wk.rows;
  assert.deepEqual(walk.cells, ["met", "met", "no", "skip", "met", "missed", "missed"]);
  // A skip counts neither for nor against: 3 done of 6.
  assert.deepEqual([read.done, read.possible, read.rate], [7, 7, 1]);
  assert.deepEqual([walk.done, walk.possible, walk.rate], [3, 6, 0.5]);
  assert.deepEqual(wk.perDay.map((p) => p.rate), [1, 1, 0.5, 1, 1, 0.5, 0.5]);
  assert.deepEqual([wk.met, wk.possible, wk.perfect, wk.daysSoFar], [10, 13, 4, 7]);
  assert.equal(wk.best.habit.uuid, "read");
  assert.equal(wk.worst.habit.uuid, "walk");
  assert.deepEqual([read.streak, walk.streak], [7, 0]);
  assert.equal(wk.longest.habit.uuid, "read");
});

test("the days before a habit's first aren't drawn or counted, nor the days still to come", async () => {
  const { app, habit } = await openApp([daily("read", "Read", 0)], [2, 3, 4].map((n) => entry("read", n, YES)));
  const wk = app.weekReview([habit("read")], day(MON), day(week(5)));
  assert.deepEqual(wk.rows[0].cells, [null, null, "met", "met", "met", "missed", null]);
  assert.deepEqual([wk.rows[0].done, wk.rows[0].possible], [3, 4]);
  assert.deepEqual(wk.perDay.map((p) => p.rate), [null, null, 1, 1, 1, 0, null]);
  assert.equal(wk.daysSoFar, 6);
});

test("on today, a streak that ran to yesterday still runs; a day missed before today ends it", async () => {
  const { app, habit } = await openApp([daily("read", "Read", 0)], [2, 3, 4].map((n) => entry("read", n, YES)));
  // Saturday is today, not done yet: Wednesday to Friday still counts, as the list's streak does.
  assert.equal(app.weekReview([habit("read")], day(MON), day(week(5))).rows[0].streak, 3);
  // A week looked back on from later: Saturday was missed, so nothing ran on Sunday.
  assert.equal(app.weekReview([habit("read")], day(MON), day(week(6)) + 7).rows[0].streak, 0);
});

test("the streak is counted up to the week's last day, not past it", async () => {
  const { app, habit } = await openApp([daily("read", "Read", 0)], Array.from({ length: 14 }, (_, n) => entry("read", n, YES)));
  assert.equal(app.weekReview([habit("read")], day(MON), day(week(13))).rows[0].streak, 7);
});

test("no worst when every habit did as well as the best", async () => {
  const { app, habit } = await openApp(
    [daily("read", "Read", 0), daily("walk", "Walk", 1)],
    [entry("read", 0, YES), entry("walk", 0, YES)],
  );
  const wk = app.weekReview([habit("read"), habit("walk")], day(MON), day(week(0)));
  assert.equal(wk.best.habit.uuid, "read");
  assert.equal(wk.worst, null);
});

test("a number habit's day is done when its amount meets the target, at most as at least", async () => {
  const { app, habit } = await openApp(
    [daily("screen", "Screen", 0, { type: 1, targetType: 1, targetValue: 2, unit: "h" })],
    [entry("screen", 0, 1500), entry("screen", 1, 3000), entry("screen", 2, SKIP)],
  );
  const wk = app.weekReview([habit("screen")], day(MON), day(week(3)));
  assert.deepEqual(wk.rows[0].cells.slice(0, 4), ["met", "no", "skip", "missed"]);
  assert.deepEqual([wk.rows[0].done, wk.rows[0].possible], [1, 3]);
});

// ---- xrnrule's side of the week (app.js, blocksWeek) -----------------------------------------

/** A moment on a day of the week from MON, at a local hour (the review's days are local days). */
const at = (n, hour = 12) => {
  const [y, m, d] = week(n).split("-").map(Number);
  return new Date(y, m - 1, d, hour).getTime();
};

test("what was blocked, day by day on each device; what was unlocked, earned and bought", async () => {
  const { app } = await openApp([], []);
  const blocks = { social: { name: "Social" }, video: { name: "Video" } };
  const events = app.distinctEvents([
    { d: "phone", t: "blocked", at: at(0, 9), target: "com.instagram.android", name: "Instagram" },
    { d: "phone", t: "blocked", at: at(0, 22), target: "com.instagram.android", name: "Instagram" },
    { d: "laptop", t: "blocked", at: at(0), target: "instagram.com", name: "instagram.com" },
    { d: "laptop", t: "blocked", at: at(2), target: "youtube.com", name: "youtube.com" },
    { d: "phone", t: "blocked", at: at(6, 23), target: "com.instagram.android", name: "Instagram" },
    // An unlock both devices saw end: one, with its minutes, on the day it ended.
    { d: "phone", t: "unlock", at: at(1), block: "social", ms: 30 * 60000, start: at(1) - 30 * 60000 },
    { d: "laptop", t: "unlock", at: at(1) + 5000, block: "social", ms: 30 * 60000, start: at(1) - 30 * 60000 },
    { d: "phone", t: "unlock", at: at(3), block: "gone", ms: 15 * 60000, start: at(3) - 15 * 60000 },
    { d: "phone", t: "unlock", at: at(4), block: "video", ms: 45 * 60000, start: at(4) - 45 * 60000 },
    { d: "phone", t: "habit", at: at(1) - 31 * 60000, block: "social" },
    { d: "phone", t: "bought", at: at(5), block: "video", ms: 10 * 60000 },
    { d: "laptop", t: "break", at: at(5), ms: 5 * 60000 },
    // The week before, for comparing; and a week after, which isn't this one's.
    { d: "phone", t: "blocked", at: at(-1), target: "x", name: "X" },
    { d: "phone", t: "unlock", at: at(-3), block: "video", ms: 20 * 60000, start: at(-3) - 20 * 60000 },
    { d: "phone", t: "blocked", at: at(7, 1), target: "x", name: "X" },
  ]);
  const wk = app.blocksWeek(events, blocks, day(MON));
  assert.deepEqual(wk.phone, [2, 0, 0, 0, 0, 0, 1]);
  assert.deepEqual(wk.laptop, [1, 0, 1, 0, 0, 0, 0]);
  assert.deepEqual([wk.blocked, wk.phoneTotal, wk.laptopTotal], [5, 3, 2]);
  assert.deepEqual(wk.unlocked, [0, 30, 0, 15, 45, 0, 0]);
  assert.equal(wk.unlockedTotal, 90);
  assert.deepEqual([wk.earned, wk.bought], [1, 10]);
  assert.deepEqual(wk.before, { blocked: 1, unlocked: 20, earned: 0 });
  assert.deepEqual(wk.top, [
    { name: "Instagram", phone: 3, laptop: 0, total: 3 },
    { name: "instagram.com", phone: 0, laptop: 1, total: 1 },
    { name: "youtube.com", phone: 0, laptop: 1, total: 1 },
  ]);
  assert.deepEqual(wk.byBlock, [{ name: "Video", min: 45 }, { name: "Social", min: 30 }, { name: "Deleted block", min: 15 }]);
});

test("a week with nothing logged is all zeros", async () => {
  const { app } = await openApp([], []);
  const wk = app.blocksWeek([], {}, day(MON));
  assert.deepEqual([wk.phone, wk.laptop, wk.unlocked], [Array(7).fill(0), Array(7).fill(0), Array(7).fill(0)]);
  assert.deepEqual([wk.blocked, wk.unlockedTotal, wk.earned, wk.top, wk.byBlock], [0, 0, 0, [], []]);
});

test("a week still going is compared with the same days of the week before", async () => {
  const { app } = await openApp([], []);
  const events = [
    { d: "phone", t: "blocked", at: at(-7), target: "x", name: "X" }, // the Monday before
    { d: "phone", t: "blocked", at: at(-5), target: "x", name: "X" }, // its Wednesday
    { d: "phone", t: "blocked", at: at(-1), target: "x", name: "X" }, // its Sunday
    { d: "phone", t: "habit", at: at(-6), block: "b" },
    { d: "phone", t: "blocked", at: at(0), target: "x", name: "X" },
  ];
  // On Tuesday: Monday and Tuesday of the week before, not all of it.
  assert.deepEqual(app.blocksWeek(events, {}, day(MON), 2).before, { blocked: 1, unlocked: 0, earned: 1 });
  assert.deepEqual(app.blocksWeek(events, {}, day(MON)).before, { blocked: 3, unlocked: 0, earned: 1 });
});
