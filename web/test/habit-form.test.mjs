// Saving the habit form (app.js, formHabit): what the form changed goes on top of the habit as
// stored when Save is pressed, so what the phone or xrnrule changed while the form was open
// (reminder, archived, order, icon, timer, a field the form has but left alone) isn't put back.
import test from "node:test";
import assert from "node:assert/strict";
import { openApp, store } from "./app.mjs";

const open = () => openApp(
  [
    { uuid: "read", data: { name: "Read", question: "", description: "", color: 8, type: 0, freqNum: 1, freqDen: 1, archived: false, position: 0, reminder: null, icon: "", timer: false } },
    { uuid: "run", data: { name: "Run", question: "", description: "", color: 8, type: 1, targetType: 0, targetValue: 5, unit: "km", step: 1, freqNum: 1, freqDen: 1, archived: false, position: 1, reminder: null, icon: "", timer: false } },
  ],
  [],
);

/** What the form holds when Save is pressed, as its FormData would give it. */
const form = (fields) => new Map(Object.entries(fields));

/** The habit as another device changed it while the form was open. */
const changedElsewhere = (app, uuid, fields) => {
  const stored = app.state.habits.get(uuid);
  app.ingest({ habits: [{ uuid, mtime: 2, deleted: false, data: { ...stored.data, ...fields } }], entries: [] });
};

const ELSEWHERE = { reminder: { hour: 8, minute: 30, days: 127 }, archived: true, position: 4, icon: "book", timer: true };

test.beforeEach(() => store.clear());

test("saving a yes/no habit keeps what changed elsewhere while the form was open", async () => {
  const { app, habit } = await open();
  const opened = { ...habit("read").data }; // as editView takes it
  changedElsewhere(app, "read", ELSEWHERE);
  const out = app.formHabit("read", opened, form({ name: "Read more", question: "", color: "8", kind: "daily" }));
  assert.equal(out.name, "Read more");
  for (const [k, v] of Object.entries(ELSEWHERE)) assert.deepEqual(out[k], v, k);
});

test("saving a number habit keeps what changed elsewhere while the form was open", async () => {
  const { app, habit } = await open();
  const opened = { ...habit("run").data };
  changedElsewhere(app, "run", ELSEWHERE);
  const out = app.formHabit("run", opened,
    form({ name: "Run", question: "", color: "8", targetType: "0", targetValue: "8", unit: "km", per: "1", step: "1" }));
  assert.equal(out.targetValue, 8);
  for (const [k, v] of Object.entries(ELSEWHERE)) assert.deepEqual(out[k], v, k);
});

test("a field the form has but nobody touched here keeps the change made elsewhere", async () => {
  const { app, habit } = await open();
  const opened = { ...habit("run").data };
  changedElsewhere(app, "run", { name: "Run far", targetValue: 10, freqNum: 3, freqDen: 7, step: 0.5 });
  const out = app.formHabit("run", opened,
    form({ name: "Run", question: "", color: "2", targetType: "0", targetValue: "5", unit: "km", per: "1", step: "1" }));
  assert.equal(out.color, 2); // changed here
  assert.deepEqual([out.name, out.targetValue, out.freqNum, out.freqDen, out.step], ["Run far", 10, 3, 7, 0.5]);
});

test("a field changed here wins over the change made elsewhere", async () => {
  const { app, habit } = await open();
  const opened = { ...habit("read").data };
  changedElsewhere(app, "read", { name: "Read on the phone", freqNum: 3, freqDen: 7 });
  const out = app.formHabit("read", opened, form({ name: "Read in the browser", question: "", color: "8", kind: "perWeek", num: "5" }));
  assert.deepEqual([out.name, out.freqNum, out.freqDen], ["Read in the browser", 5, 7]);
});

test("how often goes whole from wherever it changed, never half from each", async () => {
  const { app, habit } = await open();
  const opened = { ...habit("read").data }; // every day: 1 in 1
  changedElsewhere(app, "read", { freqNum: 3, freqDen: 7 });
  const out = app.formHabit("read", opened, form({ name: "Read", question: "", color: "8", kind: "everyN", den: "2" }));
  assert.deepEqual([out.freqNum, out.freqDen], [1, 2]); // not 3 in 2
});
