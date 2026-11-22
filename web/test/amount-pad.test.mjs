// The number sheet's pad (loop.js, AmountPad), with the same cases as the phone's
// (uhabits-core AmountPadTest), so the two keep the same rules.
import test from "node:test";
import assert from "node:assert/strict";
import { AT_LEAST, AT_MOST, AmountPad, SKIP, UNKNOWN, dayProgress, meetsDayTarget } from "../loop.js";

const type = (pad, keys) => [...keys].map((k) => (k === "." ? pad.separator() : k === "<" ? pad.backspace() : pad.digit(Number(k))));

test("an empty or skipped day opens with no amount; an amount to the thousandth", () => {
  assert.equal(new AmountPad(UNKNOWN).text, "");
  assert.equal(new AmountPad(SKIP).text, "");
  assert.equal(new AmountPad(0).text, "0");
  assert.deepEqual([2500, 715, 1500000, 4, 2147483647].map((e) => new AmountPad(e).text), ["2.5", "0.715", "1500", "0.004", "2147483.647"]);
});

test("the first key replaces the opening amount; after ⌫ keys add on", () => {
  const pad = new AmountPad(3000);
  type(pad, "52");
  assert.equal(pad.text, "52");
  const back = new AmountPad(2500);
  type(back, "<7");
  assert.equal(back.text, "2.7");
  const point = new AmountPad(3000);
  type(point, ".5");
  assert.equal(point.text, "0.5");
});

test("only an amount gets in", () => {
  const pad = new AmountPad(UNKNOWN);
  assert.deepEqual(type(pad, "1..2345"), [true, true, false, true, true, true, false]);
  assert.equal(pad.text, "1.234");
  const zeros = new AmountPad(UNKNOWN);
  type(zeros, "007");
  assert.equal(zeros.text, "7");
  const max = new AmountPad(UNKNOWN);
  type(max, "2147483");
  assert.equal(max.digit(0), false);
  type(max, ".647");
  assert.equal(max.entry, 2147483647);
  const skip = new AmountPad(UNKNOWN);
  type(skip, "0.003");
  assert.equal(skip.entry, SKIP + 1);
});

test("+ and − go by the step from what's shown, never below 0, and the next key starts over", () => {
  const pad = new AmountPad(UNKNOWN);
  pad.step(1000, -1);
  assert.equal(pad.text, "0");
  const quarter = new AmountPad(SKIP);
  for (let i = 0; i < 4; i++) quarter.step(250, 1);
  assert.equal(quarter.entry, 1000);
  const tiny = new AmountPad(2);
  tiny.step(1, 1);
  assert.equal(tiny.entry, SKIP + 1);
  const typed = new AmountPad(3000);
  type(typed, "6");
  typed.step(1000, 1);
  assert.equal(typed.text, "7");
  type(typed, "9");
  assert.equal(typed.text, "9");
});

test("what Save and leaving write", () => {
  const same = new AmountPad(3000);
  same.step(1000, 1);
  same.step(1000, -1);
  assert.equal(same.toSave(), null);
  assert.equal(same.toKeep(), null);
  const cleared = new AmountPad(3000);
  cleared.clear();
  assert.equal(cleared.toSave(), UNKNOWN);
  assert.equal(cleared.toSave(SKIP), SKIP);
  assert.equal(cleared.toKeep(), null);
  const typed = new AmountPad(UNKNOWN);
  type(typed, "4");
  assert.equal(typed.toSave(), 4000);
  assert.equal(typed.toKeep(), 4000);
});

test("the bar and whether the target is met", () => {
  assert.equal(dayProgress(3000, 0), null);
  assert.equal(dayProgress(null, 8000), 0);
  assert.equal(dayProgress(SKIP, 8000), 0);
  assert.equal(dayProgress(3000, 8000), 0.375);
  assert.equal(dayProgress(12000, 8000), 1);
  assert.equal(meetsDayTarget(714, 715, AT_LEAST), false);
  assert.equal(meetsDayTarget(715, 715, AT_LEAST), true);
  assert.equal(meetsDayTarget(2000, 2000, AT_MOST), true);
  assert.equal(meetsDayTarget(2001, 2000, AT_MOST), false);
  assert.equal(meetsDayTarget(null, 2000, AT_MOST), false);
});
