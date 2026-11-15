// A day's note (app.js, writeEntry): nothing here shows or writes one since 2026-11-15, and every
// write keeps the one stored, even one that came from the server after the keys opened.
import test from "node:test";
import assert from "node:assert/strict";
import { DAY, openApp, store } from "./app.mjs";

const PREFS = "xrnloops.prefs";

/** The web app with a yes/no habit and a number one, each with a note on DAY. */
const open = () => openApp(
  [
    { uuid: "read", data: { name: "Read", type: 0, freqNum: 1, freqDen: 1 } },
    { uuid: "run", data: { name: "Run", type: 1, targetType: 0, targetValue: 5, unit: "km", freqNum: 1, freqDen: 1 } },
  ],
  [
    { habit: "read", date: DAY, value: 0, notes: "tired" },
    { habit: "run", date: DAY, value: 3000, notes: "knee" },
  ],
);

test.beforeEach(() => store.clear());

test("a flick on a yes/no day keeps its note", async () => {
  const { app, habit, sent } = await open();
  app.checkChoices(habit("read"), DAY).up.run();
  assert.deepEqual(sent("read"), { value: 2, notes: "tired" });
});

test("a click on a day keeps its note", async () => {
  const { app, habit, sent } = await open();
  app.tapDay(habit("read"), DAY);
  assert.deepEqual(sent("read"), { value: 2, notes: "tired" });
});

test("a number day's keys keep its note", async () => {
  const { app, habit, sent } = await open();
  app.numberChoices(habit("run"), DAY).left.run();
  assert.deepEqual(sent("run"), { value: 3, notes: "knee" });
});

test("the note kept is the one stored when the key is picked, not when the keys opened", async () => {
  const { app, habit, sent, fromServer } = await open();
  const keys = app.checkChoices(habit("read"), DAY);
  fromServer("read", 0, "written on the phone meanwhile");
  keys.left.run();
  assert.deepEqual(sent("read"), { value: 3, notes: "written on the phone meanwhile" });
});

test("the yes/no keys have no note key", async () => {
  const { app, habit } = await open();
  assert.equal(app.checkChoices(habit("read"), DAY).right, undefined);
});

test("a browser set to open the day's note ticks now, and keeps that", async () => {
  store.set(PREFS, JSON.stringify({ tap: "menu", theme: "dark" }));
  const { app } = await open();
  assert.equal(app.prefs.tap, "tick");
  assert.deepEqual(JSON.parse(store.get(PREFS)), { tap: "tick", todayRight: true, theme: "dark" });
});
