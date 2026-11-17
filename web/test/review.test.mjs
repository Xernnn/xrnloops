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
