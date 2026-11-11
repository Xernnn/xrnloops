// The web app's own rules (loop.js), under Node with no build step: node --test web/test
import test from "node:test";
import assert from "node:assert/strict";
import { Worker } from "node:worker_threads";

import { AT_LEAST, AT_MOST, dayTargetEntry } from "../loop.js";

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
