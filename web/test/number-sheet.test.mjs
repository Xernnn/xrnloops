// A number day's sheet (app.js, numberSheet; the phone's NoteSheet): what it stores for an amount
// typed into it, by Save or by closing it any other way, is an entry the phone holds and reads
// back the same: the amount ×1000 as an Int, never below 0, and never 3, Loop's skip.
import test from "node:test";
import assert from "node:assert/strict";
import { DAY, openApp, store } from "./app.mjs";
import { fresh } from "./dom.mjs";

const open = () => openApp(
  [{ uuid: "run", data: { name: "Run", type: 1, targetType: 0, targetValue: 5, unit: "km", freqNum: 1, freqDen: 1 } }],
  [],
);

/** Opens the day's sheet and types `text` into its amount. */
function typeInto(app, habit, text) {
  const find = fresh();
  app.numberSheet(habit, DAY);
  find("input").value = text;
  return {
    save: () => find("form").dispatchEvent(new Event("submit", { cancelable: true })),
    dismiss: () => find("div", (n) => n.class === "scrim").dispatchEvent(new Event("pointerdown")),
  };
}

test.beforeEach(() => store.clear());

for (const how of ["save", "dismiss"]) {
  test(`an amount typed is stored ×1000 (${how})`, async () => {
    const { app, habit, sent } = await open();
    typeInto(app, habit("run"), "2.5")[how]();
    assert.equal(sent("run").value, 2500);
  });

  test(`an amount below 0 is not stored (${how})`, async () => {
    const { app, habit, sent } = await open();
    typeInto(app, habit("run"), "-5")[how]();
    assert.equal(sent("run"), undefined);
  });

  test(`0.003, which Loop would read as a skip, is stored as 0.004 (${how})`, async () => {
    const { app, habit, sent } = await open();
    typeInto(app, habit("run"), "0.003")[how]();
    assert.equal(sent("run").value, 4);
  });

  test(`an amount past what the phone's Int holds stops at the biggest one (${how})`, async () => {
    const { app, habit, sent } = await open();
    typeInto(app, habit("run"), "3000000")[how]();
    assert.equal(sent("run").value, 2147483647);
  });
}

test("a box left blank still stores nothing on the way out, and Save still clears", async () => {
  const { app, habit, sent } = await open();
  typeInto(app, habit("run"), "").dismiss();
  assert.equal(sent("run"), undefined);
  app.ingest({ habits: [], entries: [{ habit: "run", date: DAY, value: 4000, notes: "", mtime: 2 }] });
  typeInto(app, habit("run"), "").save();
  assert.equal(sent("run").value, -1);
});
