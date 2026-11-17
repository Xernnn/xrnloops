// MiSans Latin 4.007's Bold and Heavy draw an ampersand where "@" should be, in every format they
// come in; the web app sets habit names at 800 (the habit's page, the number sheet). Every weight
// must take its "@" from a file that draws one.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const css = readFileSync(new URL("../style.css", import.meta.url), "utf8");
const faces = [...css.matchAll(/@font-face\s*{([^}]*)}/g)].map(([, body]) => ({
  family: /font-family:\s*"([^"]+)"/.exec(body)[1],
  file: /url\("([^"]+)"\)/.exec(body)[1],
  weight: Number(/font-weight:\s*(\d+)/.exec(body)[1]),
  ranges: (/unicode-range:\s*([^;]+)/.exec(body)?.[1] ?? "U+0-10FFFF").split(",").map((r) => {
    const [lo, hi = lo] = r.trim().slice(2).split("-");
    return [parseInt(lo, 16), parseInt(hi, 16)];
  }),
}));
const BROKEN = ["fonts/MiSansLatin-Bold.woff2", "fonts/MiSansLatin-Heavy.woff2"];
const drawing = (weight, ch) => faces.filter((f) => f.family === "MiSans" && f.weight === weight
  && f.ranges.some(([lo, hi]) => lo <= ch.codePointAt(0) && ch.codePointAt(0) <= hi)).map((f) => f.file);

test("no weight of MiSans draws its @ with the Bold's or the Heavy's", () => {
  for (const weight of new Set(faces.filter((f) => f.family === "MiSans").map((f) => f.weight))) {
    const files = drawing(weight, "@");
    assert.equal(files.length, 1, `${weight}: ${files}`);
    assert.ok(!BROKEN.includes(files[0]), `${weight} draws @ with ${files[0]}`);
  }
});

test("everything else at 800 and 900 is still the Bold's and the Heavy's", () => {
  assert.deepEqual(drawing(800, "A"), ["fonts/MiSansLatin-Bold.woff2"]);
  assert.deepEqual(drawing(900, "&"), ["fonts/MiSansLatin-Heavy.woff2"]);
});
