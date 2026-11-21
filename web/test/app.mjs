// app.js under node, on stand-ins for what it uses of a browser, as in tabs.test.mjs. Nothing is
// drawn and the server never answers; what it would get is read from the queue of unsent writes.
export const store = new Map();
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
globalThis.requestAnimationFrame = () => 0;
globalThis.fetch = () => new Promise(() => {});
globalThis.window = new EventTarget();

export const DAY = "2026-11-11";

let opened = 0;
/**
 * A fresh copy of the web app, signed in, holding `habits` and `entries` as if the server had
 * sent them. `sent(uuid)` is what would go to the server for DAY; `fromServer` brings in a newer
 * version of that day.
 */
export async function openApp(habits, entries) {
  store.set("xrn.signedIn", "true");
  store.set("xrnloops.pendingOwner", JSON.stringify("alex"));
  const app = await import(`../app.js?copy=${++opened}`);
  app.ingest({ habits: habits.map((h) => ({ mtime: 1, deleted: false, ...h })), entries: entries.map((e) => ({ mtime: 1, ...e })) });
  return {
    app,
    habit: (uuid) => app.state.habits.get(uuid),
    sent(uuid) {
      const e = JSON.parse(store.get("xrnloops.pending") ?? "{}").entries?.find((x) => x.habit === uuid && x.date === DAY);
      return e && { value: e.value, notes: e.notes };
    },
    fromServer: (uuid, value, notes = "") => app.ingest({ habits: [], entries: [{ habit: uuid, date: DAY, value, notes, mtime: 2 }] }),
  };
}
