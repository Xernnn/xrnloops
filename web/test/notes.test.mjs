// A day's note (app.js, writeEntry): nothing here shows or writes one since 2026-11-15, and every
// write keeps the one stored, even one that came from the server after the keys opened. app.js
// runs here on stand-ins for what it uses of a browser, as in tabs.test.mjs; a GET is never
// answered, and what the server would get is read from the queue of unsent writes.
import test from "node:test";
import assert from "node:assert/strict";

const store = new Map();
globalThis.localStorage = {
  getItem: (key) => (store.has(key) ? store.get(key) : null),
  setItem: (key, value) => void store.set(key, String(value)),
  removeItem: (key) => void store.delete(key),
};
globalThis.location = { href: "https://home.test/xrnloops/", hash: "" };
const element = () => ({ dataset: {}, style: {}, setAttribute() {}, append() {}, addEventListener() {} });
globalThis.document = {
  documentElement: element(),
  head: element(),
  addEventListener() {},
  querySelectorAll: () => [],
  getElementById: () => null,
  createElement: element,
  createElementNS: element,
};
globalThis.matchMedia = () => ({ matches: false, addEventListener() {} });
globalThis.requestAnimationFrame = () => 0; // nothing is drawn
globalThis.fetch = () => new Promise(() => {}); // the server never answers
globalThis.window = new EventTarget();

const PENDING = "xrnloops.pending", PREFS = "xrnloops.prefs";
const DAY = "2026-11-11";

let opened = 0;
/** The web app, signed in, with a yes/no habit and a number one whose day has a note. */
async function openApp() {
  store.set("xrn.signedIn", "true");
  store.set("xrnloops.pendingOwner", JSON.stringify("sonn"));
  const app = await import(`../app.js?notes=${++opened}`);
  app.ingest({
    habits: [
      { uuid: "read", mtime: 1, deleted: false, data: { name: "Read", type: 0, freqNum: 1, freqDen: 1 } },
      { uuid: "run", mtime: 1, deleted: false, data: { name: "Run", type: 1, targetType: 0, targetValue: 5, unit: "km", freqNum: 1, freqDen: 1 } },
    ],
    entries: [
      { habit: "read", date: DAY, value: 0, notes: "tired", mtime: 1 },
      { habit: "run", date: DAY, value: 3000, notes: "knee", mtime: 1 },
    ],
  });
  const habit = (uuid) => app.state.habits.get(uuid);
  /** What goes to the server for that day: its value and note. */
  const sent = (uuid) => {
    const e = JSON.parse(store.get(PENDING)).entries.find((x) => x.habit === uuid && x.date === DAY);
    return e && { value: e.value, notes: e.notes };
  };
  /** The server sends a newer version of that day. */
  const fromServer = (uuid, value, notes) => app.ingest({ habits: [], entries: [{ habit: uuid, date: DAY, value, notes, mtime: 2 }] });
  return { app, habit, sent, fromServer };
}

test.beforeEach(() => store.clear());

test("a flick on a yes/no day keeps its note", async () => {
  const { app, habit, sent } = await openApp();
  app.checkChoices(habit("read"), DAY).up.run();
  assert.deepEqual(sent("read"), { value: 2, notes: "tired" });
});

test("a click on a day keeps its note", async () => {
  const { app, habit, sent } = await openApp();
  app.tapDay(habit("read"), DAY);
  assert.deepEqual(sent("read"), { value: 2, notes: "tired" });
});

test("a number day's keys keep its note", async () => {
  const { app, habit, sent } = await openApp();
  app.numberChoices(habit("run"), DAY).left.run();
  assert.deepEqual(sent("run"), { value: 3, notes: "knee" });
});

test("the note kept is the one stored when the key is picked, not when the keys opened", async () => {
  const { app, habit, sent, fromServer } = await openApp();
  const keys = app.checkChoices(habit("read"), DAY);
  fromServer("read", 0, "written on the phone meanwhile");
  keys.left.run();
  assert.deepEqual(sent("read"), { value: 3, notes: "written on the phone meanwhile" });
});

test("the yes/no keys have no note key", async () => {
  const { app, habit } = await openApp();
  assert.equal(app.checkChoices(habit("read"), DAY).right, undefined);
});

test("a browser set to open the day's note ticks now, and keeps that", async () => {
  store.set(PREFS, JSON.stringify({ tap: "menu", theme: "dark" }));
  const { app } = await openApp();
  assert.equal(app.prefs.tap, "tick");
  assert.deepEqual(JSON.parse(store.get(PREFS)), { tap: "tick", todayRight: true, theme: "dark" });
});
