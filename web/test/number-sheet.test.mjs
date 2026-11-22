// A number day's sheet (app.js, numberSheet; the phone's NoteSheet): its own pad, − and + by the
// habit's step, the quick keys, and what it stores by Save or by closing it any other way: an
// entry the phone holds and reads back the same, the amount ×1000 as an Int, never below 0, and
// never 3, Loop's skip. A keyboard types on the pad too.
import test from "node:test";
import assert from "node:assert/strict";
import { DAY, openApp, store } from "./app.mjs";
import { fresh } from "./dom.mjs";

/** Run, at least 5 km a day, by `step`, with `entries` on DAY. */
const open = ({ entries = [], step = 1, targetValue = 5, targetType = 0 } = {}) => openApp(
  [{ uuid: "run", data: { name: "Run", type: 1, targetType, targetValue, unit: "km", freqNum: 1, freqDen: 1, step } }],
  entries.map((value) => ({ habit: "run", date: DAY, value, notes: "" })),
);

const click = (node) => node.dispatchEvent(new Event("click"));
const text = (node) => node.children.map((c) => (typeof c === "string" ? c : text(c))).join("");

/** Opens the day's sheet: its keys, chips, − and +, Save, and ways to leave it or type on it. */
function sheet(app, habit) {
  const find = fresh();
  app.numberSheet(habit, DAY);
  const panel = find("div", (n) => /\bsheet\b/.test(n.class));
  const pad = (key) => find("button", (n) => n["data-key"] === key);
  const self = {
    panel,
    amount: find("span", (n) => /^amount/.test(n.class)),
    goal: find("span", (n) => /^goal/.test(n.class)),
    fill: find("div", (n) => /^fill/.test(n.class)),
    progress: find("div", (n) => n.class === "progress"),
    chip: (label) => find("button", (n) => /^chip/.test(n.class) && text(n).endsWith(label)),
    round: (sign) => find("button", (n) => /^round/.test(n.class) && text(n).startsWith(sign)),
    pad,
    tap(keys) {
      for (const k of keys) click(pad(k === "<" ? "back" : k));
      return self;
    },
    key(...keys) {
      for (const key of keys) panel.dispatchEvent(Object.assign(new Event("keydown", { cancelable: true }), { key }));
      return self;
    },
    save: () => click(find("button", (n) => /\bsave\b/.test(n.class))),
    dismiss: () => find("div", (n) => n.class === "scrim").dispatchEvent(new Event("pointerdown")),
  };
  return self;
}

const press = (node) => node.dispatchEvent(Object.assign(new Event("pointerdown"), { button: 0 }));
const lift = (node) => {
  node.dispatchEvent(new Event("pointerup"));
  click(node);
};

test.beforeEach(() => store.clear());

for (const how of ["save", "dismiss"]) {
  test(`an amount keyed in is stored ×1000 (${how})`, async () => {
    const { app, habit, sent } = await open();
    sheet(app, habit("run")).tap("2.5")[how]();
    assert.equal(sent("run").value, 2500);
  });

  test(`0.003, which Loop would read as a skip, is stored as 0.004 (${how})`, async () => {
    const { app, habit, sent } = await open();
    sheet(app, habit("run")).tap("0.003")[how]();
    assert.equal(sent("run").value, 4);
  });

  test(`the most an entry holds goes in, and no digit past it (${how})`, async () => {
    const { app, habit, sent } = await open();
    const s = sheet(app, habit("run")).tap("21474830");
    assert.equal(s.amount.textContent, "2147483");
    s.tap(".6479")[how]();
    assert.equal(sent("run").value, 2147483647);
  });

  test(`+ and − are kept as typing is (${how})`, async () => {
    const { app, habit, sent } = await open({ entries: [3000] });
    const s = sheet(app, habit("run"));
    click(s.round("+"));
    s[how]();
    assert.equal(sent("run").value, 4000);
  });
}

test("only an amount gets in: one separator, three decimals, no minus or letters", async () => {
  const { app, habit, sent } = await open();
  const s = sheet(app, habit("run")).key("-", "e", "1", ".", ",", "2", "3", "4", "5");
  assert.equal(s.amount.textContent, "1.234");
  s.save();
  assert.equal(sent("run").value, 1234);
});

test("an empty or skipped day shows a dash, not a 0", async () => {
  for (const entries of [[], [3]]) {
    store.clear();
    const { app, habit } = await open({ entries });
    const s = sheet(app, habit("run"));
    assert.equal(s.amount.textContent, "–");
    assert.match(s.amount.class, /\bnone\b/);
  }
});

test("the first key replaces the amount the sheet opened with, and ⌫ takes one off", async () => {
  const { app, habit, sent } = await open({ entries: [4500] });
  const s = sheet(app, habit("run"));
  assert.equal(s.amount.textContent, "4.5");
  s.tap("6");
  assert.equal(s.amount.textContent, "6");
  s.tap("2<<7").save();
  assert.equal(sent("run").value, 7000);
});

test("an amount nobody changed writes nothing, by Save or on the way out", async () => {
  for (const how of ["save", "dismiss"]) {
    store.clear();
    const { app, habit, sent } = await open({ entries: [4000] });
    sheet(app, habit("run"))[how]();
    assert.equal(sent("run"), undefined, how);
  }
  store.clear();
  const { app, habit, sent } = await open({ entries: [4000] });
  const s = sheet(app, habit("run"));
  click(s.round("+"));
  click(s.round("−"));
  s.save();
  assert.equal(sent("run"), undefined, "back where it opened");
});

test("a box left blank stores nothing on the way out, and Save empties the day", async () => {
  const { app, habit, sent } = await open({ entries: [4000] });
  sheet(app, habit("run")).tap("<<").dismiss();
  assert.equal(sent("run"), undefined);
  sheet(app, habit("run")).tap("<").save();
  assert.equal(sent("run").value, -1);
});

test("Save with no amount leaves a skip the phone made while the sheet was open", async () => {
  const { app, habit, sent, fromServer } = await open({ entries: [4000] });
  const s = sheet(app, habit("run")).tap("<");
  fromServer("run", 3);
  s.save();
  assert.equal(sent("run").value, 3);
});

test("+ and − go by the habit's step, never below 0, and step over the skip's 0.003", async () => {
  let { app, habit, sent } = await open({ step: 0.5 });
  let s = sheet(app, habit("run"));
  assert.deepEqual([text(s.round("+")), text(s.round("−"))], ["+0.5", "−0.5"]);
  click(s.round("+"));
  click(s.round("+"));
  click(s.round("+"));
  assert.equal(s.amount.textContent, "1.5");
  s.save();
  assert.equal(sent("run").value, 1500);

  store.clear();
  ({ app, habit, sent } = await open({ step: 0.001, entries: [2] }));
  s = sheet(app, habit("run"));
  click(s.round("+"));
  s.save();
  assert.equal(sent("run").value, 4);
});

test("a quick 0 is two taps: − on an empty day, or 0 on the pad, then Save", async () => {
  for (const way of ["minus", "pad"]) {
    store.clear();
    const { app, habit, sent } = await open();
    const s = sheet(app, habit("run"));
    if (way === "minus") click(s.round("−"));
    else s.tap("0");
    s.save();
    assert.equal(sent("run").value, 0, way);
  }
});

test("a step of 1 shows a bare + and −", async () => {
  const { app, habit } = await open();
  const s = sheet(app, habit("run"));
  assert.equal(text(s.round("+")), "+");
  assert.equal(text(s.round("−")), "−");
});

test("held, + repeats until it is let go, and the click that ends it adds nothing", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout", "setInterval"] });
  const { app, habit, sent } = await open();
  const s = sheet(app, habit("run"));
  const plus = s.round("+");
  press(plus);
  for (const ms of [400, 90, 90, 90]) t.mock.timers.tick(ms);
  lift(plus);
  t.mock.timers.tick(1000);
  assert.equal(s.amount.textContent, "4");
  s.save();
  assert.equal(sent("run").value, 4000);
});

test("held, ⌫ empties the box", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout", "setInterval"] });
  const { app, habit, sent } = await open({ entries: [12500] });
  const s = sheet(app, habit("run"));
  press(s.pad("back"));
  t.mock.timers.tick(500);
  lift(s.pad("back"));
  assert.equal(s.amount.textContent, "–");
  s.save();
  assert.equal(sent("run").value, -1);
});

test("the quick keys save at once: the day's target, skip and clear", async () => {
  for (const [label, value] of [["5", 5000], ["Skip", 3], ["Clear", -1]]) {
    store.clear();
    const { app, habit, sent } = await open({ entries: [2000] });
    click(sheet(app, habit("run")).chip(label));
    assert.equal(sent("run").value, value, label);
  }
});

test("the target key enters the day's share rounded towards meeting it", async () => {
  const { app, habit, sent } = await open();
  habit("run").data.freqDen = 7; // 5 a week: 0.714… a day
  const s = sheet(app, habit("run"));
  click(s.chip("0.715"));
  assert.equal(sent("run").value, 715);
});

test("the quick key the day is set to is picked out; Clear never is", async () => {
  const { app, habit } = await open({ entries: [3] });
  const s = sheet(app, habit("run"));
  assert.match(s.chip("Skip").class, /\bon\b/);
  assert.doesNotMatch(s.chip("Clear").class, /\bon\b/);
});

test("no target: no target key and no bar", async () => {
  const { app, habit } = await open({ targetValue: 0 });
  const s = sheet(app, habit("run"));
  assert.equal(s.progress, undefined);
  assert.equal(s.chip("0"), undefined);
  assert.ok(s.chip("Skip"));
});

test("the bar fills towards the day's target, and the figure takes the habit's colour once it's met", async () => {
  const { app, habit } = await open({ entries: [2000] });
  const s = sheet(app, habit("run"));
  assert.equal(text(s.goal), "/ 5");
  assert.equal(s.fill.style.width, "40%");
  assert.doesNotMatch(s.goal.class, /\bmet\b/);
  s.tap("6");
  assert.equal(s.fill.style.width, "100%");
  assert.match(s.goal.class, /\bmet\b/);
});

test("at most: past the target the bar isn't met", async () => {
  const { app, habit } = await open({ targetType: 1, targetValue: 2, entries: [1000] });
  const s = sheet(app, habit("run"));
  assert.match(s.goal.class, /\bmet\b/);
  s.tap("3");
  assert.doesNotMatch(s.goal.class, /\bmet\b/);
  assert.match(s.fill.class, /\bover\b/);
});

test("a keyboard: digits, a comma, Backspace, Delete, ↑ ↓ and Enter", async () => {
  const { app, habit, sent } = await open({ entries: [1000] });
  const s = sheet(app, habit("run")).key("2", ",", "7", "5", "Backspace");
  assert.equal(s.amount.textContent, "2.7");
  s.key("ArrowUp", "ArrowUp", "ArrowDown");
  assert.equal(s.amount.textContent, "3.7");
  s.key("Delete");
  assert.equal(s.amount.textContent, "–");
  s.key("9", "Enter");
  assert.equal(sent("run").value, 9000);
});
