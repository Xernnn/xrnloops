// A habit's own notes (app.js, formHabit; Loop's "description"): nothing here shows or edits them
// since 2026-11-15, and saving the habit form keeps the ones stored, even ones that came from the
// phone after the form opened, as the phone's form does (EditHabitActivity, HabitFormTest).
import test from "node:test";
import assert from "node:assert/strict";
import { openApp, store } from "./app.mjs";

/** The web app with a yes/no habit and a number one, each with notes of its own. */
const open = () => openApp(
  [
    { uuid: "read", data: { name: "Read", question: "", description: "Ten pages before bed", color: 8, type: 0, freqNum: 1, freqDen: 1 } },
    { uuid: "run", data: { name: "Run", question: "", description: "Slow on Mondays", color: 8, type: 1, targetType: 0, targetValue: 5, unit: "km", step: 1, freqNum: 1, freqDen: 1 } },
  ],
  [],
);

/** What the form holds when Save is pressed, as its FormData would give it. */
const form = (fields) => new Map(Object.entries(fields));

test.beforeEach(() => store.clear());

test("saving a yes/no habit keeps its notes, and changes only what the form has", async () => {
  const { app, habit } = await open();
  const opened = { ...habit("read").data };
  const out = app.formHabit("read", opened, form({ name: "Read more", question: "Did you read?", color: "3", kind: "perWeek", num: "4" }));
  assert.deepEqual(out, { ...opened, name: "Read more", question: "Did you read?", color: 3, freqNum: 4, freqDen: 7 });
  assert.equal(out.description, "Ten pages before bed");
});

test("saving a number habit keeps its notes", async () => {
  const { app, habit } = await open();
  const out = app.formHabit("run", { ...habit("run").data },
    form({ name: "Run", question: "", color: "5", targetType: "0", targetValue: "8", unit: "km", per: "1", step: "0.5" }));
  assert.equal(out.targetValue, 8);
  assert.equal(out.step, 0.5);
  assert.equal(out.description, "Slow on Mondays");
});

test("the notes kept are the ones stored when Save is pressed, not when the form opened", async () => {
  const { app, habit } = await open();
  const opened = { ...habit("read").data }; // as editView takes it
  app.ingest({ habits: [{ uuid: "read", mtime: 2, deleted: false, data: { ...opened, description: "Twenty pages, from the phone" } }], entries: [] });
  const out = app.formHabit("read", opened, form({ name: "Read", question: "", color: "8", kind: "daily" }));
  assert.equal(out.description, "Twenty pages, from the phone");
});

test("a new habit has no notes", async () => {
  const { app } = await open();
  const defaults = { name: "", question: "", description: "", color: 8, freqNum: 1, freqDen: 1, type: 0 };
  const out = app.formHabit(undefined, defaults, form({ name: "Stretch", question: "", color: "2", kind: "daily" }));
  assert.equal(out.description, "");
});
