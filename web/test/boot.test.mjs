// Opening the page and signing in while the server can't be reached (app.js, boot, signIn): the
// page tries again, as its live updates do, and a session the server gave isn't forgotten.
import test from "node:test";
import assert from "node:assert/strict";
import { store } from "./app.mjs";
import { FakeNode } from "./dom.mjs";

const root = new FakeNode("main");
root.replaceChildren = (...children) => void (root.children = children);
document.getElementById = (id) => (id === "app" ? root : null);

/** The server: it answers a sign-in, and the feed once `up` is true; anything else waits. */
const server = { up: false };
globalThis.fetch = (url) => {
  const u = String(url);
  if (u.endsWith("api/session")) return Promise.resolve({ ok: true, status: 200 });
  if (u.includes("api/changes?since=0") && !u.includes("wait=")) {
    if (!server.up) return Promise.reject(new TypeError("fetch failed"));
    const page = { seq: 1, more: false, habits: [], entries: [], epoch: 7, dayStartHour: 0 };
    return Promise.resolve({ ok: true, status: 200, json: async () => page });
  }
  return new Promise(() => {});
};

const settle = async () => {
  for (let i = 0; i < 20; i++) await new Promise(setImmediate);
};

let copies = 0;
const openPage = () => import(`../app.js?boot=${++copies}`);

test.beforeEach((t) => {
  store.clear();
  server.up = false;
  t.mock.timers.enable({ apis: ["setTimeout"] });
});

test("a page opened while the server can't be reached tries again, and loads once it can", async (t) => {
  store.set("xrn.signedIn", "true");
  const app = await openPage();
  await settle();
  assert.equal(app.state.loaded, false);
  server.up = true;
  t.mock.timers.tick(5000);
  await settle();
  assert.equal(app.state.loaded, true);
});

test("a server that can't be reached right after signing in doesn't forget the session", async (t) => {
  const app = await openPage();
  app.signIn("someone", "secret");
  await settle();
  assert.equal(app.state.signedIn, true);
  assert.equal(store.get("xrn.signedIn"), "true");
  server.up = true;
  t.mock.timers.tick(5000);
  await settle();
  assert.equal(app.state.loaded, true);
});
