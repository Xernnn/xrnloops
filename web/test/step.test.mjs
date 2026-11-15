// The number flick's + and − (app.js, numberChoices; loop.js, steppedEntry), by the habit's step
// (2026-11-15), as on the phone (NumberFlick, uhabits-core StepTest).
import test from "node:test";
import assert from "node:assert/strict";
import { DAY, openApp, store } from "./app.mjs";

/** Run, at least 5 km a day, with `step` (none: a habit synced from before the step). */
const open = (step, entries = [{ habit: "run", date: DAY, value: 4500, notes: "knee" }]) => openApp(
  [{ uuid: "run", data: { name: "Run", type: 1, targetType: 0, targetValue: 5, unit: "km", freqNum: 1, freqDen: 1, ...(step == null ? {} : { step }) } }],
  entries,
);

test.beforeEach(() => store.clear());

test("up adds the step and down takes it off, and each key says how much", async () => {
  const { app, habit, sent } = await open(0.25);
  const keys = app.numberChoices(habit("run"), DAY);
  assert.deepEqual([keys.up.text, keys.down.text], ["+0.25", "−0.25"]);
  keys.up.run();
  assert.deepEqual(sent("run"), { value: 4750, notes: "knee" });
  app.numberChoices(habit("run"), DAY).down.run();
  app.numberChoices(habit("run"), DAY).down.run();
  assert.deepEqual(sent("run"), { value: 4250, notes: "knee" });
});

test("a habit with no step goes by 1", async () => {
  const { app, habit, sent } = await open(null);
  const keys = app.numberChoices(habit("run"), DAY);
  assert.deepEqual([keys.up.text, keys.down.text], ["+1", "−1"]);
  keys.up.run();
  assert.equal(sent("run").value, 5500);
});

test("a key that reaches the target wears the habit's colour", async () => {
  const { app, habit } = await open(0.5);
  const keys = app.numberChoices(habit("run"), DAY); // 4.5 km of 5
  assert.equal(keys.up.tint, "var(--c)");
  assert.equal(keys.down.tint, "var(--muted)");
});

test("+ and − go from the day as it is when picked, not as it was when the keys opened", async () => {
  const { app, habit, sent, fromServer } = await open(1);
  const keys = app.numberChoices(habit("run"), DAY);
  fromServer("run", 8000, "from the phone");
  keys.down.run();
  assert.deepEqual(sent("run"), { value: 7000, notes: "from the phone" });
});

test("+ on an empty or skipped day gives one step, and − on one gives 0", async () => {
  for (const value of [null, 3]) {
    const entries = value == null ? [] : [{ habit: "run", date: DAY, value, notes: "" }];
    store.clear();
    let { app, habit, sent } = await open(2, entries);
    app.numberChoices(habit("run"), DAY).up.run();
    assert.equal(sent("run").value, 2000, `${value}`);
    store.clear();
    ({ app, habit, sent } = await open(2, entries));
    app.numberChoices(habit("run"), DAY).down.run();
    assert.equal(sent("run").value, 0, `${value}`);
  }
});
