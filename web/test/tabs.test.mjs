// The queue of unsent writes that this browser's tabs share in localStorage (app.js, "pending").
// app.js runs here on stand-ins for what it uses of a browser at start. A GET is never answered,
// so a tab stays where it is before it draws anything, and a POST is its queue going out.
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
let posted = [];
globalThis.fetch = async (url, { method, body }) => {
  if (method !== "POST") return new Promise(() => {});
  posted.push(JSON.parse(body));
  return { status: 200, ok: true, json: async () => ({ rejected: { habits: [], entries: [] } }) };
};

const PENDING = "xrnloops.pending", OWNER = "xrnloops.pendingOwner";
const tick = (habit, mtime) => ({ habit, date: "2026-11-11", value: 2, notes: "", mtime });
const queue = (...entries) => ({ habits: [], entries });

let opened = 0;
/** A tab of the web app, signed in as sonn, opened on what localStorage holds by then. */
async function openTab() {
  store.set("xrn.signedIn", "true");
  if (!store.has(OWNER)) store.set(OWNER, JSON.stringify("sonn"));
  const win = (globalThis.window = new EventTarget());
  await import(`../app.js?tab=${++opened}`);
  return {
    /** Another tab saved `value` under `key`: localStorage has it, and this tab hears of it. */
    hears(key, value) {
      store.set(key, JSON.stringify(value));
      win.dispatchEvent(Object.assign(new Event("storage"), { key, newValue: JSON.stringify(value) }));
    },
    /** What this tab sends once it's online. */
    async sends() {
      posted = [];
      win.dispatchEvent(new Event("online"));
      await new Promise((resolve) => setTimeout(resolve, 10));
      return posted.flatMap((body) => body.entries.map((e) => e.habit));
    },
  };
}

test.beforeEach(() => store.clear());

test("a tick made here as the other tab made one still goes out, and stays saved", async () => {
  store.set(PENDING, JSON.stringify(queue(tick("walk", 5))));
  const tab = await openTab();
  // The other tab ticked Read before it heard of Walk, and saved its queue without it.
  tab.hears(PENDING, queue(tick("read", 6)));
  assert.deepEqual(JSON.parse(store.get(PENDING)).entries.map((e) => e.habit).sort(), ["read", "walk"]);
  assert.deepEqual((await tab.sends()).sort(), ["read", "walk"]);
});

test("what the other tab sent doesn't go out again from here", async () => {
  store.set(PENDING, JSON.stringify(queue(tick("walk", 5))));
  const tab = await openTab();
  tab.hears(PENDING, queue(tick("walk", 5), tick("read", 6)));
  tab.hears(PENDING, queue()); // the other tab sent both
  assert.deepEqual(await tab.sends(), []);
  assert.deepEqual(JSON.parse(store.get(PENDING)), queue());
});

test("a record goes by its newer write, whichever tab made it", async () => {
  store.set(PENDING, JSON.stringify(queue(tick("walk", 5), tick("read", 9))));
  const tab = await openTab();
  tab.hears(PENDING, queue({ ...tick("walk", 7), value: 0 }, tick("read", 8)));
  await tab.sends();
  const sent = posted[0].entries.sort((a, b) => a.habit.localeCompare(b.habit));
  assert.deepEqual(sent.map((e) => [e.habit, e.mtime, e.value]), [["read", 9, 2], ["walk", 7, 0]]);
});

test("signed in as someone else in the other tab, what was queued here for sonn is dropped", async () => {
  store.set(PENDING, JSON.stringify(queue(tick("walk", 5))));
  const tab = await openTab();
  tab.hears(OWNER, "other");
  tab.hears(PENDING, queue());
  assert.deepEqual(await tab.sends(), []);
  assert.deepEqual(JSON.parse(store.get(PENDING)), queue());
});
