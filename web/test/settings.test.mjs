// Settings (app.js, settingsPanel): a panel in place of the old menu, with every setting it had,
// each kept where and how this browser kept it before (localStorage "xrnloops.prefs").
import test from "node:test";
import assert from "node:assert/strict";
import { openApp, store } from "./app.mjs";
import { fresh, made } from "./dom.mjs";

const PREFS = "xrnloops.prefs";
const prefs = () => JSON.parse(store.get(PREFS) ?? "{}");
const click = (node) => node.dispatchEvent(new Event("click"));
const text = (node) => node.children.filter((c) => typeof c === "string").join("");

test.beforeEach(() => store.clear());

test("each setting is kept under the key and in the shape the menu kept it", async () => {
  const { app } = await openApp([], []);
  const find = fresh();
  app.settingsPanel();
  click(find("button", (n) => text(n) === "Cycles ✓ ✗ ?"));
  click(find("button", (n) => n.role === "switch"));
  click(find("button", (n) => text(n) === "Black"));
  assert.deepEqual(prefs(), { tap: "cycle", todayRight: false, theme: "black" });
  assert.deepEqual([app.prefs.tap, app.prefs.todayRight, app.prefs.theme], ["cycle", false, "black"]);
});

test("the panel shows what this browser has kept", async () => {
  store.set(PREFS, JSON.stringify({ tap: "cycle", todayRight: false, theme: "dark" }));
  const { app } = await openApp([], []);
  fresh();
  app.settingsPanel();
  const checked = made.filter((n) => n.tagName === "button" && n["aria-checked"] === "true").map(text);
  assert.deepEqual(checked, ["Cycles ✓ ✗ ?", "Dark"]);
  assert.equal(made.find((n) => n.role === "switch")["aria-checked"], "false");
});

test("a switch flicked twice puts today back on the right", async () => {
  const { app } = await openApp([], []);
  const find = fresh();
  app.settingsPanel();
  const toggle = find("button", (n) => n.role === "switch");
  click(toggle);
  click(toggle);
  assert.equal(prefs().todayRight, true);
  assert.equal(toggle["aria-checked"], "true");
});
