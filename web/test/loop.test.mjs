// The web app's own rules (loop.js), under Node with no build step: node --test web/test
import test from "node:test";
import assert from "node:assert/strict";
import { Worker } from "node:worker_threads";

import { AT_LEAST, AT_MOST, MAX_STEP, SKIP, UNKNOWN, cleanStep, dayTargetEntry, formatStep, stepEntry, steppedEntry } from "../loop.js";

const habit = (targetValue, freqDen, targetType = AT_LEAST) => ({ data: { targetValue, freqDen, targetType } });

/** dayTargetEntry in a worker, which can be stopped if it never answers: it ran forever once. */
function dayTargetEntryWithin(h, ms = 5000) {
  const worker = new Worker(
    `const { parentPort, workerData } = require("node:worker_threads");
     import(workerData.loop).then((L) => parentPort.postMessage(L.dayTargetEntry(workerData.habit)));`,
    { eval: true, workerData: { loop: new URL("../loop.js", import.meta.url).href, habit: h } });
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      worker.terminate();
      reject(new Error(`no answer in ${ms} ms`));
    }, ms);
    worker.once("message", (value) => {
      clearTimeout(timer);
      worker.terminate();
      resolve(value);
    });
    worker.once("error", (e) => {
      clearTimeout(timer);
      reject(e);
    });
  });
}

test("a day's target is the least amount that meets it, or the most an at-most one allows", () => {
  // As DayTargetTest has them on the phone.
  assert.equal(dayTargetEntry(habit(5, 7)), 715);
  assert.equal(dayTargetEntry(habit(2, 3, AT_MOST)), 666);
  assert.equal(dayTargetEntry(habit(0, 1)), 0);
  assert.equal(dayTargetEntry(habit(NaN, 1)), 0);
  assert.equal(dayTargetEntry(habit(Infinity, 1)), 0);
});

test("a target too big for an entry is the biggest entry, as on the phone, and comes at once", async () => {
  // Past 2^53 a number's +1 is itself, so stepping up to the share never got there: the tab hung.
  assert.equal(await dayTargetEntryWithin(habit(1e20, 1)), 2147483647);
  assert.equal(await dayTargetEntryWithin(habit(9100000280246.709, 1)), 2147483647);
  assert.equal(await dayTargetEntryWithin(habit(1e20, 1, AT_MOST)), 2147483647);
  // An entry is an Int on the phone, and Kotlin's roundToInt stops there.
  assert.equal(dayTargetEntry(habit(1e7, 1)), 2147483647);
});

// The number flick's step (2026-11-15), as uhabits-core's StepTest has it on the phone.
const stepped = (step) => ({ data: { type: 1, step } });

test("+ and − count from 0 on an empty or skipped day, and never go below it", () => {
  assert.equal(steppedEntry(UNKNOWN, 1000, 1), 1000);
  assert.equal(steppedEntry(SKIP, 250, 1), 250);
  assert.equal(steppedEntry(UNKNOWN, 1000, -1), 0);
  assert.equal(steppedEntry(SKIP, 1000, -1), 0);
  assert.equal(steppedEntry(500, 1000, -1), 0);
  assert.equal(steppedEntry(2500, 1000, -1), 1500);
});

test("steps of a quarter or a tenth don't drift", () => {
  let value = UNKNOWN;
  for (let i = 0; i < 4; i++) value = steppedEntry(value, stepEntry(stepped(0.25)), 1);
  assert.equal(value, 1000);
  let tenths = UNKNOWN;
  for (let i = 0; i < 10; i++) tenths = steppedEntry(tenths, stepEntry(stepped(0.1)), 1);
  assert.equal(tenths, 1000);
});

test("a step never lands on 3, Loop's skip, nor past what an entry holds", () => {
  assert.equal(steppedEntry(2, 1, 1), 4);
  assert.equal(steppedEntry(4, 1, -1), 2);
  assert.equal(steppedEntry(2147483640, stepEntry(stepped(MAX_STEP)), 1), 2147483647);
});

test("a habit's step is a positive number to the thousandth, and 1 if it has none", () => {
  assert.equal(stepEntry(stepped(0.25)), 250);
  assert.equal(stepEntry(stepped(0.0004)), 1);
  for (const bad of [undefined, null, 0, -2, NaN, "2"]) assert.equal(stepEntry(stepped(bad)), 1000, String(bad));
  assert.equal(stepEntry(stepped(1e12)), 100000000);
});

test("the form takes a step from a thousandth to 100000", () => {
  assert.equal(cleanStep(0.25), 0.25);
  assert.equal(cleanStep(1.2345), 1.235);
  assert.equal(cleanStep(MAX_STEP), MAX_STEP);
  for (const bad of [0, 0.0004, -1, MAX_STEP + 1, NaN, Infinity]) assert.equal(cleanStep(bad), null, String(bad));
});

test("a step's keys say it to the thousandth", () => {
  assert.deepEqual([250, 1000, 1, 1500, 100000000].map(formatStep), ["0.25", "1", "0.001", "1.5", "100000"]);
});
