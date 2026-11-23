// The 1st and the 15th of the month are marked in the day headers, the list's and the week
// review's (app.js, listHeads and dayHeads; loop.js, markedDay), and only their number is: not the
// weekday above it. As on the phone (uhabits-core MarkedDaysTest).
import test from "node:test";
import assert from "node:assert/strict";
import "./dom.mjs";
import { openApp, store } from "./app.mjs";
import * as L from "../loop.js";

test.beforeEach(() => store.clear());

const day = (text) => L.dayNum(text);
const run = (from, count) => Array.from({ length: count }, (_, i) => day(from) + i);
const classOf = (node) => node.class ?? "";
/** The dates whose part [pick] of the head carries the mark. */
const marked = (heads, days, pick) => days.filter((_, i) => classOf(pick(heads[i])).split(" ").includes("mark")).map(L.dayStr);

test("the 1st and the 15th are the marked days, in every month", () => {
  for (let month = 1; month <= 12; month++) {
    const mm = String(month).padStart(2, "0");
    const days = run(`2026-${mm}-01`, 28);
    assert.deepEqual(days.filter(L.markedDay).map(L.dayStr), [`2026-${mm}-01`, `2026-${mm}-15`]);
  }
  for (const other of ["2026-01-31", "2028-02-29", "2026-11-14", "2026-11-16", "2026-11-30"]) assert.equal(L.markedDay(day(other)), false, other);
});

test("the list's head marks the number of the 15th and the 1st, and nothing else", async () => {
  const { app } = await openApp([], []);
  const days = run("2026-11-12", 22); // 12 Nov to 3 Dec
  const heads = app.listHeads(days, days.map(() => false), day("2026-12-03"), true);
  const part = (name) => (head) => head.children.find((c) => classOf(c).split(" ")[0] === name);
  assert.deepEqual(marked(heads, days, part("dn")), ["2026-11-15", "2026-12-01"]);
  assert.deepEqual(marked(heads, days, part("wd")), [], "the weekday is left as it is");
  assert.deepEqual(marked(heads, days, (head) => head), [], "and so is the column's head as a whole");
});

test("today on a marked day keeps both: today's head, the marked number", async () => {
  const { app } = await openApp([], []);
  const days = run("2026-11-13", 3);
  const today = app.listHeads(days, days.map(() => false), day("2026-11-15"), false).at(-1);
  assert.equal(classOf(today), "day today");
  assert.equal(classOf(today.children.at(-1)), "dn mark");
});

test("the week review's head marks them the same way", async () => {
  const { app } = await openApp([], []);
  const days = run("2026-11-09", 7); // Monday 9 to Sunday 15
  const heads = app.dayHeads(days, day("2026-11-12"));
  assert.deepEqual(marked(heads, days, (head) => head.children[1]), ["2026-11-15"]);
  assert.deepEqual(marked(heads, days, (head) => head.children[0]), []);
});
