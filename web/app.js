/*
 * Copyright (C) 2026 the xrnrule authors
 *
 * This file is part of Loop Habit Tracker (the xrnloops fork). GPLv3 or later; see loop.js.
 */

// The xrnloops web app: the phone's habit list on a big screen, a page per habit, an editor and
// a weekly review, in xrnrule's look. It talks to xrnloops-sync next to it (`api/...`): the
// whole history once, then a long-poll for changes, so a tick on the phone shows up here within
// a few seconds. Writes are queued in localStorage until the server has them. The login is a
// cookie the server sets (xrnloops-sync.py, "api/session"), which no script can read: the
// password is never kept.
//
// A day works as on the phone (XRNLOOPS.md, "The web app"): a click does what "Tap on a day"
// says; held for a moment, a cross of keys opens around it, and dragging towards one and letting
// go picks it (up ✓, down ✗, left skip, the middle ?). A right-click opens the same keys and
// leaves them open. A day's note is kept as it is: nothing here shows or writes one since
// 2026-11-15, and a write carries the stored one along (writeEntry). After each name, a lock for what the habit unlocks in xrnrule,
// read from xrnrule-sync's shared state (`../xrnrule/state`, the same login).

import * as L from "./loop.js";
import { ICONS, MARKS, UNITS_PER_EM } from "./icons.js";

const BASE = new URL(".", location.href);
const KEY_SIGNED_IN = "xrn.signedIn"; // a hint only: whether to try the cookie or show the form
const KEY_OLD_AUTH = "xrnloops.auth"; // the password, as this page kept it before the cookie
const KEY_PENDING = "xrnloops.pending";
const KEY_QUEUE_OWNER = "xrnloops.pendingOwner";
const KEY_DAY_START = "xrnloops.dayStartHour";
const KEY_PREFS = "xrnloops.prefs";
const TAP_CHOICES = ["tick", "cycle"];

/** This browser's settings: the phone's "Tap on a day" and "reverse order", and the theme. */
const prefs = { tap: "tick", todayRight: true, theme: "system", ...load(KEY_PREFS, {}) };
// "menu" opened the day's note until 2026-11-15: that browser ticks now, as a new one does.
if (!TAP_CHOICES.includes(prefs.tap)) {
  prefs.tap = "tick";
  save(KEY_PREFS, prefs);
}

const state = {
  signedIn: load(KEY_SIGNED_IN, false) === true,
  habits: new Map(), // uuid -> {uuid, data, mtime, deleted}
  entries: new Map(), // uuid -> Map(day string -> {value, notes, mtime})
  seq: 0,
  loaded: false,
  online: true,
  pending: load(KEY_PENDING, { habits: [], entries: [] }),
  showArchived: false,
  chartRange: 365,
  generation: 0,
  loginError: "",
  // Sync trouble, as the phone's amber cloud: failures in a row, since when, and why.
  sync: { failures: 0, since: 0, error: "" },
};

const summaries = new Map(); // uuid -> L.summarize(...), dropped when that habit changes
let summariesDay = null;
L.setDayStartHour(load(KEY_DAY_START, 0)); // the server confirms it with the first page

// ---- small helpers ----------------------------------------------------------------------

function load(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch {
    return fallback;
  }
}

function save(key, value) {
  try {
    if (value == null) localStorage.removeItem(key);
    else localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* private window: the session still works, it just isn't remembered */
  }
}

function h(tag, attrs = {}, ...children) {
  const el = tag === "svg" || attrs.svg ? document.createElementNS("http://www.w3.org/2000/svg", tag) : document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false || k === "svg") continue;
    if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "class") el.setAttribute("class", v);
    else if (k === "style" && typeof v === "object") {
      // Through the CSSOM, which the server's CSP allows (a style="" attribute it wouldn't).
      for (const [p, value] of Object.entries(v)) {
        if (value == null) continue;
        if (p.startsWith("--")) el.style.setProperty(p, value);
        else el.style[p] = value;
      }
    } else if (k in el && !(el instanceof SVGElement)) el[k] = v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const c of children.flat(Infinity)) if (c != null && c !== false) el.append(c instanceof Node ? c : String(c));
  return el;
}

const s = (tag, attrs = {}, ...children) => h(tag, { ...attrs, svg: true }, ...children);
const clamp = (x, lo, hi) => Math.min(Math.max(x, lo), hi);

class AuthError extends Error {}

const WRONG_LOGIN = "Wrong user name or password.";

/** The server went back to a backup since what this page read, and refused a write (HTTP 409). */
class StaleEpochError extends Error {}

/** The server refuses this address for a while after too many wrong passwords (HTTP 429). */
class LockedOutError extends Error {
  constructor() {
    super("Too many wrong passwords from here. Wait 15 minutes, then try again.");
  }
}

const weekdayShort = new Intl.DateTimeFormat(undefined, { weekday: "short", timeZone: "UTC" });
const dateLong = new Intl.DateTimeFormat(undefined, { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
const dateDay = new Intl.DateTimeFormat(undefined, { weekday: "long", day: "numeric", month: "long", timeZone: "UTC" });
const monthShort = new Intl.DateTimeFormat(undefined, { month: "short", timeZone: "UTC" });
const timeShort = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit" });
const dayShort = new Intl.DateTimeFormat(undefined, { weekday: "short" });

/** "19:41", or "Fri 00:30" for a time past today: how xrnrule writes one (XrnruleLinkSheet.clock). */
function clock(ms) {
  const at = new Date(ms);
  const time = timeShort.format(at);
  return at.toDateString() === new Date().toDateString() ? time : `${dayShort.format(at)} ${time}`;
}

/** An emoji-only name gets the phone's bigger glyphs. */
const isEmojiName = (name) => !!name && !/[\p{L}\p{N}]/u.test(name);

/** "Today · Wednesday 4 November", as the phone's day sheet says it. */
function describeDay(day) {
  const text = dateDay.format(L.dayDate(day));
  const today = L.todayNum();
  if (day === today) return `Today · ${text}`;
  if (day === today - 1) return `Yesterday · ${text}`;
  return text;
}

/** "30 min", "1 h", "1 h 30 min" (XrnruleLinkSheet.minutes). */
function minutes(total) {
  const hours = Math.floor(total / 60), m = total % 60;
  if (hours === 0) return `${m} min`;
  return m === 0 ? `${hours} h` : `${hours} h ${m} min`;
}

/** "3 minutes ago", "just now" (the phone's sync trouble message). */
function ago(ms) {
  const min = Math.round((Date.now() - ms) / 60000);
  if (min < 1) return "just now";
  if (min < 60) return `${min} minute${min === 1 ? "" : "s"} ago`;
  const hr = Math.round(min / 60);
  return `${hr} hour${hr === 1 ? "" : "s"} ago`;
}

// ---- theme and colours ------------------------------------------------------------------

const darkQuery = matchMedia("(prefers-color-scheme: dark)");
const THEME_BG = { light: "#f2f2f4", dark: "#1f1f23", black: "#000000" };

/** "light", "dark" (xrnrule's greys) or "black" (Loop's pure black, as on the phone). */
function themeName() {
  return ["light", "dark", "black"].includes(prefs.theme) ? prefs.theme : darkQuery.matches ? "dark" : "light";
}

function applyTheme() {
  const theme = themeName();
  document.documentElement.dataset.theme = theme;
  for (const m of document.querySelectorAll('meta[name="theme-color"]')) m.remove();
  document.head.append(h("meta", { name: "theme-color", content: THEME_BG[theme] }));
}

darkQuery.addEventListener("change", () => {
  applyTheme();
  render(true); // the page's colours are CSS; a redraw of the editor would lose what's typed in it
});

// The phone's habit colours: Loop's on the light theme (its dark grey made xrn zinc), and Loop's
// lighter set on the dark ones, pure black included (Themes.kt, xrnlook/XrnThemes.kt).
const PALETTE_LIGHT = L.PALETTE.map((c, i) => (i === 17 ? "#27272A" : i === 19 ? "#9E9E9E" : c));
const PALETTE_DARK = [
  "#EF9A9A", "#FFAB91", "#FFCC80", "#FFECB3", "#FFF59D", "#E6EE9C", "#C5E1A5", "#69F0AE",
  "#80CBC4", "#80DEEA", "#81D4FA", "#64B5F6", "#9FA8DA", "#B39DDB", "#CE93D8", "#F48FB1",
  "#BCAAA4", "#F5F5F5", "#E0E0E0", "#9E9E9E",
];

function palette() {
  return themeName() === "light" ? PALETTE_LIGHT : PALETTE_DARK;
}

function color(habit) {
  const p = palette();
  return p[habit.data.color] ?? p[8];
}

/** Text on a habit's colour: near-black on a light one, white on a dark one (as the phone). */
function onColor(hex) {
  const n = parseInt(hex.slice(1), 16);
  const lin = (c) => ((c /= 255) <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
  const lum = 0.2126 * lin(n >> 16) + 0.7152 * lin((n >> 8) & 255) + 0.0722 * lin(n & 255);
  return lum > 0.19 ? "#18181B" : "#FFFFFF";
}

/** The colour custom properties a habit's parts read: --c, and --on-c for text on it. */
function habitColors(habit) {
  const c = color(habit);
  return { "--c": habit.data.archived ? "var(--muted)" : c, "--on-c": onColor(c) };
}

// ---- glyphs -------------------------------------------------------------------------------

/** One of Loop's day glyphs (icons.js), sized in em so it follows the font size. */
function mark(name, cls = "") {
  const { d, box: [x, y, w, ht] } = MARKS[name];
  const pad = 28; // room for the outlined check's stroke
  const vw = w + 2 * pad, vh = ht + 2 * pad;
  return s("svg", {
    class: `mk ${cls}`.trim(), viewBox: `${x - pad} ${y - pad} ${vw} ${vh}`, "aria-hidden": "true",
    width: `${(vw / UNITS_PER_EM).toFixed(3)}em`, height: `${(vh / UNITS_PER_EM).toFixed(3)}em`,
  }, s("path", { d }));
}

function icon(name, cls = "icon") {
  return s("svg", { class: cls, viewBox: "0 0 24 24", "aria-hidden": "true" }, s("path", { d: ICONS[name] }));
}

/**
 * Line icons on a 24-unit grid, drawn as the family's other pages draw theirs (xrnhub's bar):
 * Lucide's shapes (ISC licence), stroked in the text colour.
 */
const STROKES = {
  settings: "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2zM15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z",
  plus: "M12 5v14M5 12h14",
  close: "M18 6 6 18M6 6l12 12",
  prev: "m15 18-6-6 6-6",
  next: "m9 18 6-6-6-6",
  phone: "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2zM12 18h.01",
  laptop: "M20 16V7a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v9m16 0H4m16 0 1.28 2.55a1 1 0 0 1-.9 1.45H3.62a1 1 0 0 1-.9-1.45L4 16",
  unlocked: "M7 11V7a5 5 0 0 1 9.9-1M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z",
  signout: "M9 20H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h3M15 16l4-4-4-4M19 12H9",
};

function stroke(name, cls = "") {
  return s("svg", { class: `stroke ${cls}`.trim(), viewBox: "0 0 24 24", "aria-hidden": "true" }, s("path", { d: STROKES[name] }));
}

/**
 * An xrn app's tile (XRNLOOPS.md, "The family's tiles"): ours, or xrnrule's where this page
 * names or links it. Below 24 px each takes the art drawn for 16 px, whose x stays readable.
 */
function tile(app, size) {
  const small = size < 24;
  const src = app === "xrnrule" ? (small ? "xrnrule-16.svg" : "xrnrule.svg") : small ? "favicon.svg" : "icon.svg";
  return h("img", { class: "tile", src, alt: "", width: size, height: size });
}

// ---- data ----------------------------------------------------------------------------------

async function api(path, body) {
  const res = await fetch(new URL(path, BASE), {
    method: body ? "POST" : "GET",
    headers: {
      "X-Xrn-Client": "web", // what makes the login cookie count
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    cache: "no-store",
  });
  if (res.status === 401) throw new AuthError();
  if (res.status === 429) throw new LockedOutError();
  if (res.status === 409) throw new StaleEpochError();
  if (!res.ok) throw new Error(`the server answered ${res.status}`);
  return res.json();
}

function entriesOf(uuid) {
  let map = state.entries.get(uuid);
  if (!map) state.entries.set(uuid, (map = new Map()));
  return map;
}

function pendingEntry(habit, date) {
  return state.pending.entries.find((e) => e.habit === habit && e.date === date);
}

/** Takes the server's records. A local write it hasn't seen yet stays on screen meanwhile. */
function ingest(page) {
  let dayMoved = false;
  if (typeof page.dayStartHour === "number" && page.dayStartHour !== L.dayStartHour()) {
    L.setDayStartHour(page.dayStartHour);
    save(KEY_DAY_START, page.dayStartHour);
    dayMoved = true;
  }
  for (const h of page.habits) {
    const mine = state.pending.habits.find((p) => p.uuid === h.uuid);
    if (mine && mine.mtime > h.mtime) continue;
    state.habits.set(h.uuid, h);
    summaries.delete(h.uuid);
  }
  for (const e of page.entries) {
    const mine = pendingEntry(e.habit, e.date);
    if (mine && mine.mtime > e.mtime) continue;
    entriesOf(e.habit).set(e.date, { value: e.value, notes: e.notes, mtime: e.mtime });
    summaries.delete(e.habit);
  }
  return dayMoved || page.habits.length + page.entries.length > 0;
}

async function loadAll(gen) {
  const habits = new Map(), entries = new Map();
  state.habits = habits;
  state.entries = entries;
  summaries.clear();
  let since = 0, more = true, epoch;
  while (more) {
    const page = await api(`api/changes?since=${since}`);
    if (gen !== state.generation) return;
    ingest(page);
    since = page.seq;
    more = page.more;
    epoch = page.epoch;
  }
  state.seq = since;
  state.epoch = epoch; // only now: stopped halfway, the next poll has to start over again
  state.loaded = true;
}

/** Sleeps that Retry (sync trouble) can cut short: sync's and the links watcher's. */
const sleepers = new Set();

function nap(ms) {
  return new Promise((resolve) => {
    const timer = setTimeout(done, ms);
    function done() {
      clearTimeout(timer);
      sleepers.delete(done);
      resolve();
    }
    sleepers.add(done);
  });
}

function syncFailed(e) {
  const sync = state.sync;
  if (!sync.failures) sync.since = Date.now();
  sync.failures++;
  sync.error = e instanceof TypeError ? "can't reach the home server" : e.message;
  setOnline(false);
}

function syncWorked() {
  state.sync.failures = 0;
  setOnline(true);
}

async function live(gen) {
  let day = L.todayNum();
  let failures = 0;
  while (gen === state.generation) {
    try {
      const epoch = state.epoch == null ? "" : `&epoch=${state.epoch}`;
      // Not held open while sync is failing: the first good answer has to clear the trouble.
      const wait = state.sync.failures ? 0 : 25;
      const page = await api(`api/changes?since=${state.seq}&wait=${wait}${epoch}`);
      if (gen !== state.generation) return;
      if (page.epoch !== state.epoch) { // restored from a backup: what's here is another history
        await loadAll(gen);
        render(true);
        continue;
      }
      const changed = ingest(page);
      state.seq = page.seq;
      failures = 0;
      syncWorked();
      // A new day moves every column along, even if nothing else changed.
      if (changed || L.todayNum() !== day) render(true);
      day = L.todayNum();
    } catch (e) {
      if (gen !== state.generation) return;
      if (e instanceof AuthError) return signOut("You were signed out. Sign in again.");
      if (e instanceof LockedOutError) return signOut(e.message);
      syncFailed(e);
      // 5 s, then doubling to a minute: a server that's down isn't asked every 5 s all night.
      await nap(Math.min(5000 * 2 ** failures, 60000));
      failures = Math.min(failures + 1, 4);
    }
  }
}

function setOnline(online) {
  const was = state.online;
  state.online = online;
  updateSyncBadge();
  if (online && !was) flush();
}

/** A write always wins over what it replaces, even if this computer's clock runs behind. */
function nextMtime(previous) {
  return Math.max(Date.now(), (previous ?? 0) + 1);
}

/**
 * A new value for a day. It keeps the day's note as stored now, which may have come from the
 * server since a sheet opened: nothing here writes a note since 2026-11-15, nor blanks one.
 */
function writeEntry(uuid, date, value) {
  const old = entriesOf(uuid).get(date);
  const notes = old?.notes ?? "";
  const rec = { habit: uuid, date, value, notes, mtime: nextMtime(old?.mtime) };
  entriesOf(uuid).set(date, { value, notes, mtime: rec.mtime });
  summaries.delete(uuid);
  state.pending.entries = state.pending.entries.filter((e) => !(e.habit === uuid && e.date === date));
  state.pending.entries.push(rec);
  save(KEY_PENDING, state.pending);
  links.entered.set(uuid, Date.now()); // for the banner, if xrnrule now opens something
  render();
  flush();
}

function writeHabit(uuid, data, deleted = false) {
  const old = state.habits.get(uuid);
  const rec = { uuid, data, deleted, mtime: nextMtime(old?.mtime) };
  state.habits.set(uuid, rec);
  summaries.delete(uuid);
  state.pending.habits = state.pending.habits.filter((p) => p.uuid !== uuid);
  state.pending.habits.push(rec);
  save(KEY_PENDING, state.pending);
  render();
  flush();
}

let flushing = false;
let flushTimer = 0;

async function flush() {
  if (flushing || !state.signedIn) return;
  const batch = { habits: [...state.pending.habits], entries: [...state.pending.entries] };
  if (!batch.habits.length && !batch.entries.length) return;
  flushing = true;
  clearTimeout(flushTimer);
  updateSyncBadge();
  let sent = false, restored = false;
  try {
    // The history this page read: a server that went back since says so instead of taking it,
    // since taking it would move its seq past what clients have read and hide that it went back.
    const body = state.epoch == null ? batch : { ...batch, epoch: state.epoch, since: state.seq };
    const result = await api("api/changes", body);
    sent = true;
    // Drop what was sent, unless it was written again meanwhile.
    state.pending.habits = state.pending.habits.filter((p) => !batch.habits.includes(p));
    state.pending.entries = state.pending.entries.filter((p) => !batch.entries.includes(p));
    save(KEY_PENDING, state.pending);
    if (ingest({ habits: result.rejected.habits, entries: result.rejected.entries })) render(true);
    syncWorked();
  } catch (e) {
    if (e instanceof AuthError) return signOut("You were signed out. Sign in again.");
    if (e instanceof StaleEpochError) restored = true;
    else {
      syncFailed(e);
      flushTimer = setTimeout(flush, 10000);
    }
  } finally {
    flushing = false;
    updateSyncBadge();
  }
  if (restored) {
    // Read it all again, then send what's waiting; it shows again once the feed brings it back.
    try {
      await loadAll(state.generation);
      render(true);
    } catch (e) {
      syncFailed(e);
      flushTimer = setTimeout(flush, 10000);
      return;
    }
    return flush();
  }
  // What was written while this batch was out goes next. After a failure the timer retries:
  // calling again at once would ask a server that's down as fast as the request can fail.
  if (sent && (state.pending.habits.length || state.pending.entries.length)) flush();
}

window.addEventListener("online", flush);

/** Records of this tab's queue that another tab has had: gone from its queue, they were sent. */
const seenElsewhere = new WeakSet();
let queueOwner = load(KEY_QUEUE_OWNER, null);

/**
 * Another tab's queue, as it saved it, merged into this one's. Taken whole, as it was until
 * 2026-11-11, it dropped a write this tab made at the moment the other made one, before either
 * had heard of the other's: it was in neither queue any more and never went out. A record (a habit
 * by uuid, an entry by habit and day) goes by its newer write. One the other queue doesn't have
 * goes from here too if that tab has had it, and stays if it may not have seen it. Returns whether
 * the other queue lacks one of this tab's, which this tab then saves again.
 */
function mergeQueue(theirs) {
  let unseen = false;
  const merge = (mine, other, key) => {
    const there = new Map((other ?? []).map((r) => [key(r), r]));
    const out = [];
    for (const r of mine) {
      const t = there.get(key(r));
      there.delete(key(r));
      if (!t) {
        if (seenElsewhere.has(r)) continue;
        unseen = true;
        out.push(r);
      } else if (t.mtime > r.mtime) {
        seenElsewhere.add(t);
        out.push(t);
      } else {
        if (t.mtime === r.mtime) seenElsewhere.add(r);
        out.push(r);
      }
    }
    for (const t of there.values()) {
      seenElsewhere.add(t);
      out.push(t);
    }
    return out;
  };
  state.pending = {
    habits: merge(state.pending.habits, theirs.habits, (r) => r.uuid),
    entries: merge(state.pending.entries, theirs.entries, (r) => `${r.habit} ${r.date}`),
  };
  return unseen;
}

// Both tabs may send a record, which is harmless: the server settles a record it gets twice.
window.addEventListener("storage", (e) => {
  if (e.key !== KEY_PENDING && e.key !== KEY_QUEUE_OWNER) return;
  const owner = load(KEY_QUEUE_OWNER, null);
  if (queueOwner != null && owner !== queueOwner) {
    // Signed in as someone else there (claimQueue): what's queued here was the account before's.
    state.pending = { habits: [], entries: [] };
  } else if (e.key === KEY_PENDING && mergeQueue(load(KEY_PENDING, { habits: [], entries: [] }))) {
    save(KEY_PENDING, state.pending);
  }
  queueOwner = owner;
  updateSyncBadge();
});

/** What's still queued is one account's: it must not go into the next one's data. */
function claimQueue(user) {
  const owner = load(KEY_QUEUE_OWNER, user);
  const queued = state.pending.habits.length + state.pending.entries.length;
  // Named before the queue is emptied: another tab then knows the empty queue is for another
  // account, and empties its own instead of saving back what it had for this one.
  save(KEY_QUEUE_OWNER, user);
  queueOwner = user;
  if (owner !== user && queued) {
    state.pending = { habits: [], entries: [] };
    save(KEY_PENDING, state.pending);
    toast(`${queued} unsent ${queued === 1 ? "change" : "changes"} from ${owner} dropped`);
  }
}

// ---- auth ----------------------------------------------------------------------------------

/** Trades a password ("Basic …") for the login cookie. */
async function session(basic) {
  const res = await fetch(new URL("api/session", BASE), {
    method: "POST",
    headers: { Authorization: basic, "X-Xrn-Client": "web" },
    cache: "no-store",
  });
  if (res.status === 401) throw new AuthError();
  if (res.status === 429) throw new LockedOutError();
  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
  state.signedIn = true;
  save(KEY_SIGNED_IN, true);
}

async function signIn(user, password) {
  try {
    await session("Basic " + btoa(unescape(encodeURIComponent(`${user}:${password}`))));
  } catch (e) {
    state.signedIn = false;
    save(KEY_SIGNED_IN, null);
    state.loginError = e instanceof AuthError ? WRONG_LOGIN : e instanceof LockedOutError ? e.message : `Can't reach the server (${e.message}).`;
    return render();
  }
  // Signed in: a server that can't be reached now is waited for, not a reason to forget the
  // session, which made the next visit ask for the password again.
  claimQueue(user);
  state.loginError = "";
  await startOrWait();
}

function signOut(message = "") {
  state.generation++;
  state.signedIn = false;
  state.loaded = false;
  state.loginError = message;
  save(KEY_SIGNED_IN, null);
  fetch(new URL("api/session", BASE), { method: "DELETE", headers: { "X-Xrn-Client": "web" } }).catch(() => {});
  render();
}

async function start() {
  const gen = ++state.generation;
  await loadAll(gen);
  render();
  live(gen);
  watchLinks(gen);
  flush();
}

/**
 * start(), and while the server can't be reached, again with live()'s backoff (5 s doubling to a
 * minute), on a page that says so; Try again cuts the wait short. A page opened while the server
 * restarted stayed there until it was reloaded by hand.
 */
async function startOrWait() {
  for (let failures = 0; state.signedIn; failures = Math.min(failures + 1, 4)) {
    try {
      return await start();
    } catch (e) {
      if (e instanceof AuthError) return signOut("You were signed out. Sign in again.");
      document.getElementById("app").replaceChildren(h("div", { class: "wrap login" },
        h("div", { class: "brand", role: "img", "aria-label": "xrnloops" }, tile("xrnloops", 64)),
        h("p", {}, `Can't reach the server (${e instanceof TypeError ? "no answer" : e.message}).`),
        h("button", { class: "btn primary big", onclick: () => [...sleepers].forEach((wake) => wake()) }, "Try again")));
      await nap(Math.min(5000 * 2 ** failures, 60000));
    }
  }
}

// ---- what each habit unlocks (xrnrule's shared state) -----------------------------------------

/**
 * The phone reads these rows from xrnrule's links provider (xrnrule/XrnruleLinks.kt); here they
 * come from the state xrnrule's devices share through xrnrule-sync, worked out the way xrnrule's
 * LoopLinks.rows does: every habit item of every enabled habit block.
 */
const links = { byHabit: new Map(), etag: null, entered: new Map() };

function linkRows(doc) {
  const rows = [];
  const items = Object.values(doc.items ?? {});
  for (const block of Object.values(doc.blocks ?? {})) {
    if (!block.enabled || block.mode !== "HABIT") continue;
    const session = block.state === "UNLOCKED" ? doc.sessions?.[block.id] : null;
    // An open unlock keeps the kind it started as; the block's setting is for the next one.
    const selfPaced = session ? !!session.selfPaced : !!block.selfPaced;
    const used = session ? Object.values(session.usedMs ?? {}).reduce((a, b) => a + b, 0) : 0;
    for (const item of items) {
      const habitUuid = item.blockId === block.id ? item.habitId ?? item.externalHabitId : null;
      if (!habitUuid) continue;
      rows.push({
        habitUuid,
        blockId: block.id,
        blockName: block.name ?? "",
        targets: [...(block.targetSites ?? []), ...(block.targetDesktopApps ?? [])].sort().join(" + "),
        unlockMinutes: Math.floor((block.reward?.unlockDurationMs ?? 0) / 60000),
        state: { ARMED: "locked", EARNED: "ready", UNLOCKED: "open" }[block.state] ?? "locked",
        openUntil: session && !session.selfPaced ? session.startedAtWall + session.durationMs : 0,
        habitDone: !!item.done,
        selfPaced,
        useLeftMs: session?.selfPaced ? Math.max(0, session.durationMs - used) : 0,
      });
    }
  }
  const byHabit = new Map();
  for (const row of rows) {
    if (!byHabit.has(row.habitUuid)) byHabit.set(row.habitUuid, []);
    byHabit.get(row.habitUuid).push(row);
  }
  return byHabit;
}

/** Long-polls xrnrule-sync (If-None-Match + wait) so a block that opens shows here at once. */
async function watchLinks(gen) {
  let failures = 0;
  while (gen === state.generation) {
    const asked = Date.now();
    try {
      const headers = { "X-Xrn-Client": "web" };
      if (links.etag) headers["If-None-Match"] = links.etag;
      const res = await fetch(new URL(links.etag ? "../xrnrule/state?wait=25" : "../xrnrule/state", BASE), { headers, cache: "no-store" });
      if (gen !== state.generation) return;
      if (res.status === 304) {
        failures = 0;
        if (Date.now() - asked < 1000) await nap(5000); // a server that doesn't hold the poll
        continue;
      }
      if (!res.ok) throw new Error(String(res.status));
      links.etag = res.headers.get("ETag");
      setLinks(linkRows(await res.json()));
      failures = 0;
      if (!links.etag) await nap(30000); // nothing to wait on: this would answer at once, for ever
    } catch {
      if (gen !== state.generation) return;
      // xrnrule's server may be down, or not set up: the lines stay as they were, quietly.
      await new Promise((r) => setTimeout(r, Math.min(10000 * 2 ** failures, 300000)));
      failures = Math.min(failures + 1, 5);
    }
  }
}

function setLinks(fresh) {
  const news = earned(links.byHabit, fresh, Date.now());
  const changed = JSON.stringify([...links.byHabit]) !== JSON.stringify([...fresh]);
  links.byHabit = fresh;
  if (news.length) earnedBanner(news);
  if (changed) render(true);
}

/** Several blocks can want one habit: the ones it still has to do something for come first. */
function orderedLinks(list) {
  const rank = (l) => (l.state === "locked" && !l.habitDone ? 0 : l.state === "ready" ? 1 : l.state === "open" ? 2 : 3);
  return [...list].sort((a, b) => rank(a) - rank(b));
}

/** How a block stands, in words (the phone's XrnruleLinkSheet.status). */
function linkStatus(link) {
  if (link.state === "open") {
    if (link.selfPaced) return `Open · ${minutes(Math.ceil(link.useLeftMs / 60000))} of use left`;
    if (link.openUntil > 0) return `Open until ${clock(link.openUntil)}`;
    return "Open";
  }
  if (link.state === "ready") return `Ready to open · ${minutes(link.unlockMinutes)}`;
  return link.habitDone ? "Locked · this part is done, it needs more" : `Locked · unlocks ${minutes(link.unlockMinutes)}`;
}

/**
 * The phone's XrnruleLinkBadge.paint: a mark like a day's, in the habit's colour. Closed while the
 * block waits on this habit, a bolt when there's an unlock to open, open while it's open; closed
 * and in the grey of an empty day once the habit has done its part and the block wants more.
 */
const lockClass = (link) => (link.state === "locked" && link.habitDone ? "unlock waiting" : "unlock");

const lockIcon = (link) => mark(link.state === "ready" ? "bolt" : link.state === "open" ? "unlock" : "lock");

/** A habit's lock, when a block uses it: a click shows the words, and only those go on to xrnrule. */
function linkBadge(habit) {
  const list = links.byHabit.get(habit.uuid);
  if (!list?.length) return null;
  const [link] = orderedLinks(list);
  return h("button", {
    type: "button", class: lockClass(link), title: "Unlocks in xrnrule",
    "aria-label": `${link.blockName || link.targets || "A block"}: ${linkStatus(link)}`,
    "aria-haspopup": "menu",
    onclick: (e) => {
      e.stopPropagation();
      linkMenu(e.currentTarget, habit, list);
    },
  }, lockIcon(link));
}

/**
 * The phone's XrnruleLinkSheet: every block that uses the habit, each going on to xrnrule, whose
 * tile ends the row where "Open in xrnrule" was.
 */
function linkMenu(anchor, habit, list) {
  openPopover(anchor, h("div", { style: habitColors(habit) },
    orderedLinks(list).map((link) => h("button", {
      type: "button", class: "item linkrow", role: "menuitem", title: "Open in xrnrule",
      onclick: () => {
        closePopover();
        openBlocks();
      },
    },
      h("div", { class: lockClass(link) }, lockIcon(link)),
      h("div", { class: "words" },
        h("b", {}, link.blockName || link.targets || "A block"),
        h("small", {}, linkStatus(link)),
        link.blockName && link.targets && h("small", { class: "faint" }, link.targets)),
      tile("xrnrule", 22)))));
}

function openBlocks() {
  window.open(new URL("../xrnrule/", BASE), "_blank", "noopener");
}

/**
 * Blocks that went from locked to earned, open or one part nearer for a habit entered here in
 * the last two minutes: one per block, the biggest news first (XrnruleLinks.earned).
 */
function earned(old, fresh, now) {
  const news = [];
  for (const list of fresh.values()) {
    for (const link of list) {
      const at = links.entered.get(link.habitUuid);
      if (!at || now - at > 120000) continue;
      const before = old.get(link.habitUuid)?.find((x) => x.blockId === link.blockId);
      if (!before || before.state !== "locked") continue;
      const kind = link.state === "ready" ? "earned" : link.state === "open" ? "open"
        : link.habitDone && !before.habitDone ? "counted" : null;
      if (kind) news.push({ link, kind });
    }
  }
  news.sort((a, b) => (a.kind === "counted") - (b.kind === "counted"));
  const seen = new Set();
  return news.filter((n) => !seen.has(n.link.blockId) && seen.add(n.link.blockId));
}

function earnedBanner(news) {
  const { link, kind } = news[0];
  const what = link.blockName || link.targets || "a block";
  let text = kind === "earned" ? `Earned ${minutes(link.unlockMinutes)} of ${what}`
    : kind === "counted" ? `Counted towards ${what} · it needs more`
      : link.selfPaced ? `${what} is open · ${minutes(Math.ceil(link.useLeftMs / 60000))} left`
        : link.openUntil > 0 ? `${what} is open until ${clock(link.openUntil)}`
          : `${what} is open`;
  if (news.length > 1) text = `${text} (+${news.length - 1} more)`;
  toast(text, { icon: kind === "counted" ? "lock" : "lockopen", action: ["Open", openBlocks], ms: 5000 });
}

// ---- derived -------------------------------------------------------------------------------

function visibleHabits() {
  return [...state.habits.values()]
    .filter((h) => !h.deleted && (state.showArchived || !h.data.archived))
    .sort((a, b) => a.data.position - b.data.position || a.data.name.localeCompare(b.data.name));
}

function summary(habit) {
  const today = L.todayNum();
  if (today !== summariesDay) { // each is worked out up to a day, and reads past it as nothing
    summaries.clear();
    summariesDay = today;
  }
  let sm = summaries.get(habit.uuid);
  if (!sm) summaries.set(habit.uuid, (sm = L.summarize(habit, entriesOf(habit.uuid))));
  return sm;
}

/** A number habit's target for one day, as the phone's list has it (70 a week is 10 a day). */
const dayTarget = (habit) => habit.data.targetValue / habit.data.freqDen;

/** Whether an amount meets a number habit's target. */
function meets(habit, amount) {
  return habit.data.targetType === L.AT_MOST ? amount <= dayTarget(habit) : amount >= dayTarget(habit);
}

// ---- rendering -----------------------------------------------------------------------------

let renderQueued = false;
let dataOnly = true;
let drawDeferred = false;

/**
 * Redraws on the next frame. `fromServer` marks a redraw for data that arrived by itself: it
 * waits while a popover, a held day or its keys are open, and skips the editor, so nothing you
 * are doing is pulled away from under you.
 */
function render(fromServer = false) {
  dataOnly &&= fromServer;
  if (renderQueued) return;
  renderQueued = true;
  requestAnimationFrame(() => {
    renderQueued = false;
    const onlyData = dataOnly;
    dataOnly = true;
    if (onlyData && (popover || press || flick)) return void (drawDeferred = true);
    if (onlyData && (lastRoute === "edit" || lastRoute === "new")) return;
    draw();
  });
}

function drawIfDeferred() {
  if (!drawDeferred || popover || press || flick) return;
  drawDeferred = false;
  render();
}

function draw() {
  formChanged = null; // the habit form sets it again if that's what gets drawn
  const app = document.getElementById("app");
  if (!state.signedIn) return app.replaceChildren(loginView());
  if (!state.loaded) return app.replaceChildren(h("p", { class: "boot" }, "Loading your habits…"));
  const [, route, id] = location.hash.match(/^#\/(\w+)?\/?(.*)$/) ?? [];
  const habit = id ? state.habits.get(id) : null;
  // A day that had the keyboard keeps it across the redraw.
  const focused = document.activeElement?.dataset?.key;
  let view;
  if (route === "habit" && habit && !habit.deleted) view = habitView(habit);
  else if (route === "edit" && habit && !habit.deleted) view = editView(habit);
  else if (route === "new") view = editView(null, id === "numerical" ? L.NUMERICAL : L.YES_NO);
  else if (route === "review") view = reviewView(Math.max(0, Number(id) || 0));
  else view = listView();
  const scrollY = window.scrollY;
  app.replaceChildren(view);
  if (route === lastRoute) window.scrollTo(0, scrollY);
  lastRoute = route;
  if (focused) document.querySelector(`[data-key="${CSS.escape(focused)}"]`)?.focus();
  updateSyncBadge();
}

let lastRoute = null;

/** Set while the habit form is open: true when leaving it would lose something. */
let formChanged = null;
let shownHash = location.hash;

window.addEventListener("hashchange", () => {
  if (location.hash === shownHash) return; // the next line, putting the address back
  if (formChanged?.() && !confirm("Discard your changes?")) return void (location.hash = shownHash);
  shownHash = location.hash;
  closePopover();
  closeSheet();
  render();
  window.scrollTo(0, 0);
});

window.addEventListener("beforeunload", (e) => {
  if (formChanged?.()) e.preventDefault();
});

// The list holds as many days as the window does; a habit page sizes its calendar.
let layoutKey = "";

function layoutNow() {
  const lay = listLayout();
  return `${lay.days}/${lay.cell}/${lay.nums}/${heatWeeks()}`;
}
let resizeQueued = false;
window.addEventListener("resize", () => {
  if (resizeQueued) return;
  resizeQueued = true;
  requestAnimationFrame(() => {
    resizeQueued = false;
    if (layoutNow() !== layoutKey) render(true); // not the editor: that would lose what's typed in it
  });
});

/**
 * The bar on every page: our tile where the name was, the two pages as tabs, then the page's own
 * button (New, on the list), xrnrule's tile, which goes there, and Settings.
 */
function topBar(tab, ...actions) {
  const tabLink = (id, href, label) => h("a", { class: "tab", href, "aria-current": tab === id ? "page" : null }, label);
  return h("header", { class: "bar" },
    h("div", { class: "wrap" },
      h("a", { class: "brand", href: "#/", title: "xrnloops", "aria-label": "xrnloops" }, tile("xrnloops", 30)),
      h("nav", { class: "tabs", "aria-label": "Pages" }, tabLink("habits", "#/", "Habits"), tabLink("review", "#/review", "Review")),
      h("span", { class: "spacer" }),
      h("span", { id: "sync", style: { display: "contents" } }),
      ...actions,
      h("a", { class: "app-link", href: "../xrnrule/", title: "xrnrule", "aria-label": "xrnrule" }, tile("xrnrule", 28)),
      h("button", { type: "button", class: "icon-btn", title: "Settings", "aria-label": "Settings", onclick: settingsPanel }, stroke("settings"))));
}

/** The phone's amber cloud while sync is failing; "Saving…" while a write is on its way. */
function updateSyncBadge() {
  const el = document.getElementById("sync");
  if (!el) return;
  const queued = state.pending.habits.length + state.pending.entries.length;
  const failing = state.sync.failures >= 2 || (!state.online && queued > 0);
  const shown = failing ? "failing" : queued ? "saving" : "";
  if (el.dataset.shown === shown) return; // keeps the button under the pointer
  el.dataset.shown = shown;
  if (failing) {
    const button = h("button", {
      type: "button", class: "icon-btn trouble", title: "Sync isn't working", "aria-label": "Sync isn't working",
      onclick: (e) => syncTrouble(e.currentTarget),
    }, icon("cloudoff"));
    el.replaceChildren(button);
  } else if (queued) {
    el.replaceChildren(h("span", { class: "saving" }, "Saving…"));
  } else {
    el.replaceChildren();
  }
}

function syncTrouble(anchor) {
  const queued = state.pending.habits.length + state.pending.entries.length;
  openPopover(anchor, [
    h("div", { class: "title" }, "Sync isn't working"),
    h("p", {}, `Sync failed ${ago(state.sync.since || Date.now())}: ${state.sync.error || "no answer"}.`),
    queued > 0 && h("p", { class: "muted" }, `${queued} unsent`),
    h("div", { class: "actions" }, h("button", {
      class: "btn primary", type: "button",
      onclick: () => {
        closePopover();
        for (const wake of [...sleepers]) wake();
        flush();
      },
    }, "Retry")),
  ]);
}

// ---- login ---------------------------------------------------------------------------------

function loginView() {
  const known = load(KEY_QUEUE_OWNER, null); // the account this browser last signed in with
  const form = h("form", {
    class: "card",
    onsubmit: (e) => {
      e.preventDefault();
      const data = new FormData(form);
      button.disabled = true;
      button.textContent = "Signing in…";
      signIn(String(data.get("user")).trim(), String(data.get("password")));
    },
  },
    // Named only if this browser signed in before: the page is public, and named here, anyone
    // could read the account name that guesses are made at.
    h("label", { class: "field" }, h("span", {}, "User"), h("input", { type: "text", name: "user", value: known ?? "", autocomplete: "username", required: true, autofocus: !known })),
    h("label", { class: "field" }, h("span", {}, "Password"), h("input", { type: "password", name: "password", autocomplete: "current-password", required: true, autofocus: !!known })),
    state.loginError && h("div", { class: "error" }, state.loginError),
  );
  const button = h("button", { class: "btn primary big", type: "submit" }, "Sign in");
  form.append(h("div", {}, button));
  return h("div", { class: "wrap login" },
    h("div", { class: "brand", role: "img", "aria-label": "xrnloops" }, tile("xrnloops", 64)),
    form);
}

// ---- the list ------------------------------------------------------------------------------

/**
 * How much the window holds: the phone's rows, wider days, and as many of them as fit (a laptop
 * shows three or four weeks). Score and streak get columns of their own once there's room.
 */
function listLayout() {
  const vw = document.documentElement.clientWidth || window.innerWidth;
  const pad = vw > 700 ? 28 : 12;
  const inner = Math.min(vw, 1880) - 2 * pad;
  const cell = vw >= 1100 ? 50 : vw >= 700 ? 46 : vw >= 420 ? 42 : 38;
  const name = vw >= 1100 ? 210 : vw >= 700 ? 170 : vw >= 420 ? 124 : 104;
  const nums = vw >= 1000;
  const fixed = name + (nums ? 124 : 0) + 8;
  let days = Math.floor((inner - fixed) / cell);
  if (days > 7) days = Math.floor((inner - fixed) / (cell + 8 / 7)); // a gap between weeks
  return { cell, name, nums, days: clamp(days, 3, 91) };
}

/** Monday-based week of a day, for the gap between weeks. */
const weekOf = (day) => day - ((L.dayDate(day).getUTCDay() + 6) % 7);

/** The list's day columns' heads. Only the number of a marked day (the 1st, the 15th) is marked. */
function listHeads(order, gaps, today, months) {
  return order.map((d, i) => {
    const date = L.dayDate(d);
    const monthStarts = date.getUTCDate() === 1 || (i === 0 && months);
    return h("div", { class: "day" + (d === today ? " today" : "") + (gaps[i] ? " wk" : "") },
      monthStarts && h("span", { class: "mo" }, monthShort.format(date).toUpperCase()),
      h("span", { class: "wd" }, weekdayShort.format(date).toUpperCase()),
      h("span", { class: "dn" + (L.markedDay(d) ? " mark" : "") }, date.getUTCDate()));
  });
}

function listView() {
  const today = L.todayNum();
  const lay = listLayout();
  layoutKey = layoutNow();
  const order = Array.from({ length: lay.days }, (_, i) => (prefs.todayRight ? today - (lay.days - 1 - i) : today - i));
  const gaps = order.map((d, i) => lay.days > 7 && i > 0 && weekOf(d) !== weekOf(order[i - 1]));
  const habits = visibleHabits();

  const head = h("div", { class: "lrow head" },
    h("div", { class: "who" }, h("span", { class: "today-label" }, h("b", {}, "Today"), lay.name >= 170 && dateDay.format(L.dayDate(today)))),
    lay.nums && h("div", { class: "nums" }, h("span", {}, "SCORE"), h("span", {}, "STREAK")),
    h("div", { class: "days" }, listHeads(order, gaps, today, lay.days > 7)));

  const anyArchived = [...state.habits.values()].some((x) => !x.deleted && x.data.archived);
  const list = habits.length
    ? h("div", { class: "list", style: { "--cell": `${lay.cell}px`, "--name": `${lay.name}px` } },
      head, habits.map((habit) => habitRow(habit, order, gaps, lay)))
    : h("div", { class: "empty" },
      h("p", {}, state.habits.size ? "All archived" : "No habits yet"),
      h("a", { class: "btn primary", href: "#/new" }, "New habit"));

  return h("div", {},
    topBar("habits", newMenuButton()),
    h("main", { class: "wrap" },
      list,
      anyArchived && h("div", { class: "foot" },
        h("label", { class: "check" }, h("input", {
          type: "checkbox", checked: state.showArchived,
          onchange: (e) => { state.showArchived = e.target.checked; render(); },
        }), "Show archived"))));
}

function newMenuButton() {
  return h("button", {
    type: "button", class: "new-btn", "aria-haspopup": "menu",
    onclick: (e) => openPopover(e.currentTarget, [
      h("div", { class: "title" }, "New habit"),
      menuItem("✓", "Yes or no", () => (location.hash = "#/new")),
      menuItem("#", "Measurable (a number)", () => (location.hash = "#/new/numerical")),
    ]),
  }, stroke("plus"), h("span", {}, "New"));
}

/**
 * Settings: the phone's settings that make sense here, kept in this browser (KEY_PREFS), and the
 * account. A panel at the side, or from the bottom on a phone; every change shows at once.
 */
function settingsPanel() {
  const choose = (key, value) => {
    prefs[key] = value;
    save(KEY_PREFS, prefs);
    if (key === "theme") applyTheme();
    render();
  };
  const seg = (key, label, options) => {
    const buttons = options.map(([value, text]) => h("button", {
      type: "button", role: "radio", "aria-checked": String(prefs[key] === value),
      onclick: () => {
        for (const b of buttons) b.setAttribute("aria-checked", String(b === buttons[options.findIndex(([v]) => v === value)]));
        choose(key, value);
      },
    }, text));
    return h("div", { class: "seg", role: "radiogroup", "aria-label": label }, buttons);
  };
  const toggle = (key, label) => h("button", {
    type: "button", class: "switch", role: "switch", "aria-label": label, "aria-checked": String(!!prefs[key]),
    onclick: (e) => {
      e.currentTarget.setAttribute("aria-checked", String(!prefs[key]));
      choose(key, !prefs[key]);
    },
  });
  const row = (label, control, cls = "") => h("div", { class: `srow ${cls}`.trim() }, h("span", { class: "slabel" }, label), control);
  const group = (title, ...rows) => h("section", { class: "sgroup" }, h("h3", {}, title), h("div", { class: "srows" }, rows));
  const account = load(KEY_QUEUE_OWNER, null);
  openSheet(null, [
    h("div", { class: "panel-head" },
      h("h2", {}, "Settings"),
      h("button", { type: "button", class: "icon-btn", title: "Close", "aria-label": "Close", onclick: closeSheet }, stroke("close"))),
    group("Days",
      row("Tap on a day", seg("tap", "Tap on a day", [["tick", "Ticks it"], ["cycle", "Cycles ✓ ✗ ?"]])),
      row("Today on the right", toggle("todayRight", "Today on the right"))),
    group("Look",
      row("Theme", seg("theme", "Theme", [["system", "System"], ["light", "Light"], ["dark", "Dark"], ["black", "Black"]]), "wide")),
    group("Account",
      row(account ?? "Signed in", h("button", {
        type: "button", class: "btn out",
        onclick: () => {
          closeSheet();
          signOut();
        },
      }, stroke("signout"), "Sign out"))),
  ], { cls: "panel", label: "Settings" });
}

function scoreRing(habit, score, size = 22, thickness = 3.5) {
  const r = size / 2 - thickness / 2 - 0.5, c = 2 * Math.PI * r;
  return s("svg", { class: "ring", width: size, height: size, viewBox: `0 0 ${size} ${size}`, role: "img", "aria-label": `Score ${Math.round(score * 100)}%` },
    s("circle", { class: "track", cx: size / 2, cy: size / 2, r, fill: "none", "stroke-width": thickness }),
    s("circle", {
      cx: size / 2, cy: size / 2, r, fill: "none", stroke: "var(--c)", "stroke-width": thickness, "stroke-linecap": "round",
      "stroke-dasharray": `${c * score} ${c}`, transform: `rotate(-90 ${size / 2} ${size / 2})`,
    }));
}

function habitRow(habit, order, gaps, lay) {
  const sm = summary(habit);
  const name = habit.data.name || "Untitled";
  return h("div", { class: "lrow habitrow" + (habit.data.archived ? " archived" : ""), style: habitColors(habit) },
    h("div", { class: "who", onclick: () => (location.hash = `#/habit/${habit.uuid}`), title: `${name} · ${L.describeFrequency(habit)}` },
      scoreRing(habit, sm.score),
      h("div", { class: "label" },
        h("div", { class: "name" + (isEmojiName(name) ? " emoji" : ""), style: { color: "var(--c)" } }, name)),
      linkBadge(habit)),
    lay.nums && h("div", { class: "nums" },
      h("span", { class: "score" }, `${Math.round(sm.score * 100)}%`),
      h("span", {}, sm.currentStreak ? [String(sm.currentStreak), h("small", {}, "d")] : "–")),
    h("div", { class: "days" }, order.map((d, i) => dayCell(habit, d, gaps[i]))));
}

/** A day as the phone draws it: Loop's glyph, or the amount with its unit under it. */
function dayContent(habit, day) {
  const entry = entriesOf(habit.uuid).get(L.dayStr(day));
  if (habit.data.type === L.NUMERICAL) {
    const value = entry?.value ?? L.UNKNOWN;
    if (value === L.SKIP) return ["num", mark("minus")];
    if (value < 0) return ["num unknown", mark("question")];
    const amount = value / 1000;
    return ["num" + (meets(habit, amount) ? " met" : ""), [
      h("span", { class: "amount" }, L.formatNumber(amount)),
      habit.data.unit && h("span", { class: "unit" }, habit.data.unit)]];
  }
  const value = summary(habit).computed.get(day) ?? L.UNKNOWN;
  if (value === L.YES_MANUAL) return ["yes", mark("check")];
  if (value === L.YES_AUTO) return ["auto", mark("check")];
  if (value === L.SKIP) return ["skip", mark("minus")];
  if (value === L.NO) return ["no", mark("times")];
  return ["unknown", mark("question")];
}

function dayCell(habit, day, gap) {
  const date = L.dayStr(day);
  const [cls, content] = dayContent(habit, day);
  const label = `${habit.data.name}, ${dateLong.format(L.dayDate(day))}`;
  const btn = h("button", {
    type: "button", class: `cell ${cls}` + (gap ? " wk" : ""),
    title: label, "aria-label": label, "data-key": `${habit.uuid}/${date}`,
  }, content);
  dayGesture(btn, habit, date);
  return btn;
}

// ---- a day: tap, hold and flick (xrntap/) --------------------------------------------------

const HOLD_MS = 170; // the phone's: well under a long press
const QUICK_MS = 300; // let go in the middle sooner than this: a slow tap
const FLICK_PX = 18; // how far from where it went down a key is picked
const SLOP_PX = 8; // a finger that moves this much before the keys open is scrolling

let press = null; // the pointer down on a day
let flick = null; // the open keys

/** What a click on a day does: the phone's "Tap on a day". A number day opens its sheet. */
function tapDay(habit, date) {
  if (habit.data.type === L.NUMERICAL) return numberSheet(habit, date);
  const value = summary(habit).computed.get(L.dayNum(date)) ?? L.UNKNOWN;
  if (prefs.tap === "cycle") setDay(habit, date, L.nextToggleValue(value, false, true));
  else setDay(habit, date, value === L.YES_MANUAL ? L.UNKNOWN : L.YES_MANUAL);
}

function setDay(habit, date, value) {
  writeEntry(habit.uuid, date, value);
}

/** The keys around a yes/no day (CheckmarkFlick): up ✓, down ✗, left skip, nothing right. */
function checkChoices(habit, date) {
  const c = "var(--c)", faint = "var(--muted)";
  return {
    center: { glyph: "question", tint: faint, label: "Clear", run: () => setDay(habit, date, L.UNKNOWN) },
    up: { glyph: "check", tint: c, label: "Done", run: () => setDay(habit, date, L.YES_MANUAL) },
    down: { glyph: "times", tint: faint, label: "Not done", run: () => setDay(habit, date, L.NO) },
    left: { glyph: "minus", tint: c, label: "Skip", run: () => setDay(habit, date, L.SKIP) },
  };
}

/**
 * The keys around a number day (NumberFlick): up adds the habit's step, down takes one off (never
 * below 0), left skip, right the day's sheet for any other amount. A key wears the habit's colour
 * when what it gives meets the target. They go from the day as stored when one is picked.
 */
function numberChoices(habit, date) {
  const c = "var(--c)", faint = "var(--muted)";
  const step = L.stepEntry(habit);
  const shown = entriesOf(habit.uuid).get(date)?.value ?? L.UNKNOWN;
  const stepKey = (steps) => {
    const text = `${steps > 0 ? "+" : "\u2212"}${L.formatStep(step)}`;
    return {
      text, tint: meets(habit, L.steppedEntry(shown, step, steps) / 1000) ? c : faint,
      label: `${steps > 0 ? "Add" : "Take off"} ${L.formatStep(step)} ${habit.data.unit}`.trim(),
      run: () => stepDay(habit, date, steps),
    };
  };
  return {
    center: { glyph: "question", tint: faint, label: "Clear", run: () => setDay(habit, date, L.UNKNOWN) },
    up: stepKey(1),
    right: { icon: "dialpad", tint: faint, label: "Another amount", run: () => numberSheet(habit, date) },
    down: stepKey(-1),
    left: { glyph: "minus", tint: c, label: "Skip", run: () => setDay(habit, date, L.SKIP) },
  };
}

/** + (1) or − (-1) on a number day, by the habit's step, from what the day holds now. */
function stepDay(habit, date, steps) {
  const now = entriesOf(habit.uuid).get(date)?.value ?? L.UNKNOWN;
  setDay(habit, date, L.steppedEntry(now, L.stepEntry(habit), steps));
}

function direction(dx, dy) {
  if (Math.hypot(dx, dy) < FLICK_PX) return "center";
  if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? "right" : "left";
  return dy > 0 ? "down" : "up";
}

/**
 * The phone's flick gesture, for a mouse, a touchpad or a finger: a click is a tap; held for
 * HOLD_MS the keys open around the day, and letting go towards one picks it. A mouse that drags
 * before then opens them at once. A finger that moves first is scrolling the page.
 */
function dayGesture(el, habit, date) {
  const choices = () => ({ habit, keys: habit.data.type === L.NUMERICAL ? numberChoices(habit, date) : checkChoices(habit, date) });
  const tap = () => tapDay(habit, date);
  const end = () => {
    el.classList.remove("pressed");
    clearTimeout(press?.timer);
    press = null;
  };
  el.addEventListener("pointerdown", (e) => {
    if (e.button !== 0 || flick) return;
    const mouse = e.pointerType === "mouse";
    press = { el, id: e.pointerId, x: e.clientX, y: e.clientY, at: e.timeStamp, moved: false, mouse };
    press.timer = setTimeout(() => {
      if (press?.el !== el) return;
      if (el.isConnected) return openFlick(el, choices(), false);
      // Drawn over under the finger (a change of page, say): its pointerup will never come here.
      press = null;
      drawIfDeferred();
    }, HOLD_MS);
    closePopover(); // after `press`: closing it draws anything held back, unless a day is held
    el.classList.add("pressed");
    try {
      el.setPointerCapture(e.pointerId);
    } catch { /* an element that has just gone */ }
    if (mouse) e.preventDefault(); // no text selection while dragging
  });
  el.addEventListener("pointermove", (e) => {
    if (press?.el !== el || e.pointerId !== press.id) return;
    const dx = e.clientX - press.x, dy = e.clientY - press.y;
    if (flick && !flick.sticky) return selectKey(direction(dx, dy));
    if (press.moved || Math.hypot(dx, dy) <= (press.mouse ? FLICK_PX : SLOP_PX)) return;
    clearTimeout(press.timer);
    if (press.mouse) {
      openFlick(el, choices(), false);
      selectKey(direction(dx, dy));
    } else {
      press.moved = true;
      el.classList.remove("pressed");
    }
  });
  el.addEventListener("pointerup", (e) => {
    if (press?.el !== el || e.pointerId !== press.id) return;
    const { at, moved } = press;
    end();
    if (flick && !flick.sticky) {
      const slowTap = flick.selected === "center" && e.timeStamp - at < QUICK_MS;
      const picked = flick.keys[flick.selected];
      closeFlick();
      if (slowTap) tap();
      else picked?.run();
    } else if (!moved) {
      tap();
    }
    drawIfDeferred();
  });
  el.addEventListener("pointercancel", () => {
    if (press?.el !== el) return;
    end();
    if (flick && !flick.sticky) closeFlick();
    drawIfDeferred();
  });
  // A right-click (or a long press the browser turns into one) leaves the keys open.
  el.addEventListener("contextmenu", (e) => {
    e.preventDefault();
    if (flick || press?.moved) return;
    end();
    openFlick(el, choices(), true);
  });
  el.addEventListener("click", (e) => {
    if (e.detail === 0) tap(); // Enter or Space; a pointer's click was handled on pointerup
  });
  el.addEventListener("keydown", (e) => {
    if (e.key === "ContextMenu" || (e.shiftKey && e.key === "F10")) {
      e.preventDefault();
      openFlick(el, choices(), true);
    }
  });
}

// While the keys are open, a finger moving picks a key instead of scrolling the page.
document.addEventListener("touchmove", (e) => {
  if (flick && !flick.sticky) e.preventDefault();
}, { passive: false });

const KEY_TILE = 56, KEY_GAP = 6;
const KEY_OFFSET = { center: [0, 0], up: [0, -1], right: [1, 0], down: [0, 1], left: [-1, 0] };

function keyFace(choice) {
  if (choice.icon) return icon(choice.icon);
  if (choice.glyph) return mark(choice.glyph);
  const size = choice.text.length > 5 ? " longer" : choice.text.length > 3 ? " long" : "";
  return h("span", { class: "glyph-text" + size }, choice.text);
}

/** The cross of keys, centred on the day and moved in from the window's edges. */
function openFlick(el, { habit, keys }, sticky) {
  closeFlick();
  const r = el.getBoundingClientRect();
  const step = KEY_TILE + KEY_GAP, reach = 1.5 * KEY_TILE + KEY_GAP + 8;
  const cx = clamp(r.left + r.width / 2, reach, window.innerWidth - reach);
  const cy = clamp(r.top + r.height / 2, reach, window.innerHeight - reach);
  const box = h("div", { class: "keys", style: { left: `${cx}px`, top: `${cy}px` } });
  const buttons = {};
  for (const [dir, [ox, oy]] of Object.entries(KEY_OFFSET)) {
    const choice = keys[dir];
    if (!choice) continue;
    buttons[dir] = h("button", {
      type: "button", class: `key ${dir}`, tabindex: sticky ? 0 : -1, title: choice.label, "aria-label": choice.label,
      style: { left: `${ox * step}px`, top: `${oy * step}px`, "--tint": choice.tint },
      onpointerenter: () => flick?.sticky && selectKey(dir),
      onclick: () => {
        if (!flick?.sticky) return;
        closeFlick();
        choice.run();
      },
    }, keyFace(choice));
    box.append(buttons[dir]);
  }
  const root = h("div", {
    class: "flick" + (sticky ? " sticky" : ""), style: habitColors(habit), role: "menu",
    onpointerdown: (e) => {
      if (sticky && e.target === root) closeFlick();
    },
  }, box);
  document.body.append(root);
  requestAnimationFrame(() => root.classList.add("shown"));
  flick = { el, keys, buttons, root, sticky, selected: null };
  selectKey("center");
  if (sticky) buttons.center?.focus({ preventScroll: true });
}

function selectKey(dir) {
  if (!flick || flick.selected === dir) return;
  flick.buttons[flick.selected]?.classList.remove("on");
  flick.selected = dir;
  flick.buttons[dir]?.classList.add("on");
  if (flick.sticky) flick.buttons[dir]?.focus({ preventScroll: true });
}

function closeFlick() {
  if (!flick) return;
  const { el, sticky } = flick;
  flick.root.remove();
  flick = null;
  if (sticky && document.contains(el)) el.focus({ preventScroll: true });
  drawIfDeferred();
}

// Open keys answer the arrows: an arrow moves to that key, Enter picks it, Escape closes.
document.addEventListener("keydown", (e) => {
  if (flick?.sticky) {
    const dir = { ArrowUp: "up", ArrowDown: "down", ArrowLeft: "left", ArrowRight: "right" }[e.key];
    if (dir) {
      e.preventDefault();
      selectKey(flick.selected === dir ? "center" : flick.keys[dir] ? dir : flick.selected);
      return;
    }
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      const picked = flick.keys[flick.selected];
      closeFlick();
      picked?.run();
      return;
    }
  }
  if (e.key !== "Escape") return;
  if (flick) closeFlick();
  else if (sheet) closeSheet();
  else closePopover();
});

// ---- the sheet: a number day's amount (xrnnote/NoteSheet.kt) --------------------------------

let sheet = null;

/** A sheet in a habit's colours, or with no habit, Settings' panel (`cls` "panel"). */
function openSheet(habit, children, { cls = "sheet", label = null } = {}) {
  closeSheet();
  closePopover();
  const panel = h("div", { class: cls, role: "dialog", "aria-modal": "true", "aria-label": label, style: habit && habitColors(habit) }, children);
  const scrim = h("div", {
    class: `scrim ${cls === "panel" ? "side" : ""}`.trim(),
    onpointerdown: (e) => {
      if (e.target === scrim) closeSheet();
    },
  }, panel);
  document.body.append(scrim);
  if (cls === "panel") panel.querySelector("button")?.focus({ preventScroll: true });
  sheet = { scrim, panel, onClose: null };
  return sheet;
}

/** Closing a sheet any way but its buttons runs its onClose: an amount typed is kept, as on the phone. */
function closeSheet() {
  if (!sheet) return;
  const closing = sheet;
  sheet = null;
  closing.scrim.remove();
  closing.onClose?.();
}

function sheetHead(habit, date) {
  const name = habit.data.name || "Untitled";
  return h("div", {},
    h("div", { class: "title" + (isEmojiName(name) ? " emoji" : "") }, name),
    h("div", { class: "date" }, describeDay(L.dayNum(date))));
}

/** The locale's decimal separator, as the phone's pad shows it (DecimalFormatSymbols). */
const decimalSeparator = new Intl.NumberFormat().formatToParts(1.5).find((p) => p.type === "decimal")?.value ?? ".";

const KEY_HOLD_MS = 400; // held this long, − and + repeat and ⌫ empties the box (the phone's)
const KEY_REPEAT_MS = 90;

/**
 * A press on `el` runs `tap`. Held for KEY_HOLD_MS it runs `hold` instead, and with `repeat`
 * again every KEY_REPEAT_MS until the pointer lifts or leaves; the click that ends a hold is
 * swallowed. A keyboard's Enter or Space on it is a tap.
 */
function holdable(el, tap, hold, repeat = false) {
  let timer = null, held = false;
  const stop = () => {
    clearTimeout(timer);
    clearInterval(timer);
    timer = null;
  };
  el.addEventListener("pointerdown", (e) => {
    if (e.button !== 0) return;
    held = false;
    stop();
    timer = setTimeout(() => {
      held = true;
      hold();
      if (repeat) timer = setInterval(hold, KEY_REPEAT_MS);
    }, KEY_HOLD_MS);
  });
  for (const type of ["pointerup", "pointerleave", "pointercancel"]) el.addEventListener(type, stop);
  el.addEventListener("click", () => {
    if (held) held = false;
    else tap();
  });
}

/**
 * A number day (the phone's NoteSheet): the amount between − and +, a bar towards the day's
 * target, quick keys for the target, skip and clear (each saves at once), the sheet's own pad and
 * Save. A keyboard types on the pad too: digits, "." or ",", Backspace (Delete empties the box),
 * ↑ and ↓ for + and −, Enter to save. What closing writes is AmountPad's: an amount nobody changed
 * writes nothing, Save with no amount empties the day (a skip stays), and leaving any other way
 * keeps an amount keyed in and never blanks a day. The day's sheet for a yes/no day went with its
 * note on 2026-11-15: the flick keys have its four values, here and on the calendar.
 */
function numberSheet(habit, date) {
  const opened = entriesOf(habit.uuid).get(date)?.value ?? L.UNKNOWN;
  const pad = new L.AmountPad(opened);
  // The day as it is now: the phone may have skipped it while this sheet was open.
  const valueNow = () => entriesOf(habit.uuid).get(date)?.value ?? opened;
  const unit = habit.data.unit;
  const target = L.dayTargetEntry(habit);
  const step = L.stepEntry(habit);
  const shown = (entry) => L.AmountPad.shown(entry).replace(".", decimalSeparator);
  const finish = (stored) => {
    current.onClose = null;
    closeSheet();
    if (stored != null) writeEntry(habit.uuid, date, stored);
  };

  const amount = h("span", { class: "amount" });
  const readout = h("div", { class: "readout", role: "status", "aria-live": "polite" },
    amount, unit && h("span", { class: "unit" }, unit));
  const fill = h("div", { class: "fill" });
  const goal = h("span", { class: "goal" }, `/ ${shown(target)}`);
  const progress = target > 0 && h("div", { class: "progress" }, h("div", { class: "bar" }, fill), goal);
  const show = () => {
    const empty = pad.text === "";
    const text = empty ? "\u2013" : pad.text.replace(".", decimalSeparator);
    amount.textContent = text;
    const size = text.length <= 3 ? "" : text.length <= 5 ? " m" : text.length <= 7 ? " s" : " xs";
    amount.setAttribute("class", `amount${size}${empty ? " none" : ""}`);
    readout.setAttribute("aria-label", empty ? "No amount" : `${text} ${unit}`.trim());
    if (!progress) return;
    const met = L.meetsDayTarget(pad.entry, target, habit.data.targetType);
    // "At most": the bar is how much of the day's allowance is used, and past it isn't met.
    const over = habit.data.targetType === L.AT_MOST && !met && !empty;
    const fraction = L.dayProgress(pad.entry, target);
    fill.style.width = `${fraction * 100}%`;
    fill.style.minWidth = fraction > 0 ? "6px" : "0"; // a start shows as a dot
    fill.setAttribute("class", over ? "fill over" : "fill");
    goal.setAttribute("class", met ? "goal met" : "goal");
    progress.setAttribute("aria-label", `${empty ? "0" : text} of ${shown(target)} ${unit}`.trim());
  };
  const type = (ok) => ok && show();
  const stepBy = (steps) => {
    pad.step(step, steps);
    show();
  };

  // The pad's keys and − + keep the focus where it is, so Enter saves after a click on one.
  const keep = (e) => e.preventDefault();
  const stepButton = (steps) => {
    const label = L.formatStep(step).replace(".", decimalSeparator);
    const sign = steps > 0 ? "+" : "\u2212";
    const text = step === 1000 ? sign : sign + label;
    const size = step === 1000 ? "" : label.length <= 2 ? " m" : label.length <= 4 ? " s" : " xs";
    const btn = h("button", {
      type: "button", class: `round${size}`, tabIndex: -1, onmousedown: keep,
      "aria-label": `${steps > 0 ? "Add" : "Take off"} ${label} ${unit}`.trim(),
    }, text);
    holdable(btn, () => stepBy(steps), () => stepBy(steps), true);
    return btn;
  };
  const key = (label, attrs, run) => {
    const btn = h("button", { type: "button", class: "key", tabIndex: -1, onmousedown: keep, ...attrs }, label);
    if (run) btn.addEventListener("click", run);
    return btn;
  };
  const back = key(icon("backspace"), { "aria-label": "Delete", "data-key": "back" });
  holdable(back, () => type(pad.backspace()), () => type(pad.clear()));
  const keys = h("div", { class: "pad" },
    [1, 2, 3, 4, 5, 6, 7, 8, 9].map((d) => key(String(d), { "data-key": String(d) }, () => type(pad.digit(d)))),
    key(decimalSeparator, { "aria-label": "Decimal point", "data-key": ".", class: "key sep" }, () => type(pad.separator())),
    key("0", { "data-key": "0" }, () => type(pad.digit(0))),
    back);

  // A day with nothing on it has nothing to pick out: "Clear" isn't what it's set to.
  const chip = (value, label, attrs = {}) => h("button", {
    type: "button", onclick: () => finish(value), ...attrs,
    class: "chip" + (value === opened && value !== L.UNKNOWN ? " on" : "") + (value === L.UNKNOWN ? " faint" : ""),
  }, label);
  const chips = h("div", { class: "chips" },
    target > 0 && chip(target, [icon("flag"), shown(target)], { "aria-label": `Target, ${shown(target)} ${unit}`.trim() }),
    chip(L.SKIP, "Skip"),
    chip(L.UNKNOWN, "Clear"));
  const save = h("button", { type: "button", class: "btn save big", onclick: () => finish(pad.toSave(valueNow())) }, "Save");

  const current = openSheet(habit, [
    sheetHead(habit, date),
    h("div", { class: "readout-row" }, stepButton(-1), readout, stepButton(1)),
    progress, chips, keys, save,
  ], { cls: "sheet number" });
  // Closing any other way keeps an amount keyed in. Only Save or Clear empties a day.
  current.onClose = () => {
    const kept = pad.toKeep();
    if (kept != null) writeEntry(habit.uuid, date, kept);
  };
  current.panel.setAttribute("tabindex", "-1");
  current.panel.addEventListener("keydown", (e) => {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    if (/^[0-9]$/.test(e.key)) type(pad.digit(Number(e.key)));
    else if (e.key === "." || e.key === "," || e.key === "Decimal") type(pad.separator());
    else if (e.key === "Backspace") type(pad.backspace());
    else if (e.key === "Delete") type(pad.clear());
    else if (e.key === "ArrowUp") stepBy(1);
    else if (e.key === "ArrowDown") stepBy(-1);
    // Enter saves, unless a quick key or Save has the focus: then it presses that one.
    else if (e.key === "Enter" && !(e.target !== current.panel && e.target?.tagName === "BUTTON" && e.target.tabIndex >= 0)) save.click();
    else return;
    e.preventDefault();
  });
  show();
  current.panel.focus({ preventScroll: true });
}

// ---- popovers ------------------------------------------------------------------------------

let popover = null;

function openPopover(anchor, children) {
  closePopover();
  popover = h("div", { class: "pop", role: "menu" }, children);
  document.body.append(popover);
  const a = anchor.getBoundingClientRect(), p = popover.getBoundingClientRect();
  const left = clamp(a.left + a.width / 2 - p.width / 2, 8, window.innerWidth - p.width - 8);
  const top = a.bottom + 6 + p.height > window.innerHeight ? Math.max(8, a.top - p.height - 6) : a.bottom + 6;
  Object.assign(popover.style, { left: `${left}px`, top: `${top}px` });
  popover.querySelector("input, textarea, button")?.focus({ preventScroll: true });
  setTimeout(() => document.addEventListener("pointerdown", outside), 0);
}

function outside(e) {
  if (popover && !popover.contains(e.target)) closePopover();
}

function closePopover() {
  popover?.remove();
  popover = null;
  document.removeEventListener("pointerdown", outside);
  drawIfDeferred();
}

function menuItem(glyph, text, action) {
  return h("button", { class: "item", type: "button", onclick: () => { closePopover(); action(); } }, h("span", {}, glyph), text);
}

/** A short notice at the bottom (XrnSnackbar): text, an icon, and one action. */
let toastEl = null;

function toast(text, { icon: name, action, ms = 5000 } = {}) {
  toastEl?.remove();
  const el = h("div", { class: "toast", role: "status" },
    name && icon(name), h("span", {}, text),
    action && h("button", { type: "button", onclick: () => { el.remove(); action[1](); } }, action[0]));
  toastEl = el;
  document.body.append(el);
  setTimeout(() => el.remove(), ms);
}

// ---- habit page ----------------------------------------------------------------------------

function habitView(habit) {
  const today = L.todayNum();
  const sm = summary(habit);
  const pct = (x) => `${Math.round(x * 100)}%`;
  const delta = (days) => {
    const d = sm.score - sm.scoreAt(today - days);
    return h("span", { class: d > 0.005 ? "up" : d < -0.005 ? "down" : "" }, `${d >= 0 ? "+" : ""}${Math.round(d * 100)}%`);
  };
  const entries = entriesOf(habit.uuid);
  const numerical = habit.data.type === L.NUMERICAL;
  const total = numerical
    ? L.formatNumber([...entries.values()].reduce((sum, e) => sum + (e.value > 0 && e.value !== L.SKIP ? e.value / 1000 : 0), 0))
    : [...entries.values()].filter((e) => e.value === L.YES_MANUAL).length;
  const name = habit.data.name || "Untitled";
  layoutKey = layoutNow();

  return h("div", {},
    topBar("habits"),
    h("main", { class: "wrap" }, h("div", { class: "cards", style: habitColors(habit) },
      h("section", { class: "card wide" },
        h("div", { class: "hero" },
          scoreRing(habit, sm.score, 64, 7),
          h("div", { class: "info" },
            h("div", { class: "named" },
              h("h1", { class: isEmojiName(name) ? "emoji" : "", style: { color: "var(--c)" } }, name),
              linkBadge(habit)),
            habit.data.question && h("div", { class: "q" }, habit.data.question),
            h("div", { class: "chips" },
              h("span", { class: "chip" }, L.describeFrequency(habit)),
              habit.data.archived && h("span", { class: "chip" }, "Archived"))),
          h("div", { class: "toolbar" },
            h("a", { class: "btn", href: `#/edit/${habit.uuid}` }, "Edit"),
            h("button", {
              class: "btn",
              onclick: () => writeHabit(habit.uuid, { ...habit.data, archived: !habit.data.archived }),
            }, habit.data.archived ? "Unarchive" : "Archive"),
            h("button", {
              class: "btn danger",
              onclick: () => {
                if (!confirm(`Delete "${name}" and its whole history, here and on the phone?`)) return;
                writeHabit(habit.uuid, habit.data, true);
                location.hash = "#/";
              },
            }, "Delete")))),
      h("section", { class: "card" },
        h("h2", {}, "Overview"),
        h("div", { class: "stats" },
          h("div", { class: "stat" }, h("b", { style: { color: "var(--c)" } }, pct(sm.score)), h("span", {}, "Score")),
          h("div", { class: "stat" }, h("b", {}, delta(30)), h("span", {}, "Month")),
          h("div", { class: "stat" }, h("b", {}, delta(365)), h("span", {}, "Year")),
          h("div", { class: "stat" }, h("b", {}, total), h("span", {}, numerical ? `Total ${habit.data.unit}` : "Total")))),
      h("section", { class: "card" },
        h("h2", {}, "Streak"),
        h("div", { class: "stats" },
          h("div", { class: "stat" }, h("b", { style: { color: "var(--c)" } }, sm.currentStreak), h("span", {}, "Current (days)")),
          h("div", { class: "stat" }, h("b", {}, sm.bestStreak), h("span", {}, "Best (days)")),
          h("div", { class: "stat" }, h("b", {}, sm.streaks.length), h("span", {}, "Streaks")))),
      scoreCard(habit, sm, today),
      historyCard(habit, sm, today),
      streaksCard(habit, sm),
      frequencyCard(habit, sm, today))));
}

function scoreCard(habit, sm, today) {
  const range = state.chartRange;
  const from = today - range + 1;
  const w = 1000, ht = 220; // the plot stretches to the card; the labels are HTML beside it
  const points = [];
  const step = Math.max(1, Math.floor(range / 180));
  for (let d = from; d <= today; d += step) points.push([d, sm.scoreAt(d)]);
  if (points.at(-1)[0] !== today) points.push([today, sm.scoreAt(today)]);
  const share = (d) => (d - from) / Math.max(1, range - 1);
  const x = (d) => share(d) * w;
  const y = (v) => (1 - v) * ht;
  const line = points.map(([d, v]) => `${x(d).toFixed(1)},${y(v).toFixed(1)}`).join(" ");
  const area = `${x(from)},${y(0)} ${line} ${x(today)},${y(0)}`;
  const labels = [];
  const ticks = range > 60 ? 6 : 5;
  for (let i = 0; i <= ticks; i++) {
    const d = from + Math.round(((range - 1) * i) / ticks);
    labels.push(h("span", { class: "xl" + (i === 0 ? " first" : i === ticks ? " last" : ""), style: { left: `${share(d) * 100}%` } },
      range > 60 ? monthShort.format(L.dayDate(d)) : L.dayDate(d).getUTCDate()));
  }
  const ranges = [[31, "Month"], [92, "Quarter"], [365, "Year"], [730, "2 years"]];
  return h("section", { class: "card chart wide" },
    h("div", { class: "head" }, h("h2", {}, "Score"),
      h("div", { class: "seg chart-range" }, ranges.map(([days, label]) => h("button", {
        type: "button", class: range === days ? "on" : "",
        onclick: () => { state.chartRange = days; render(); },
      }, label)))),
    h("div", { class: "plot" },
      s("svg", { viewBox: `0 0 ${w} ${ht}`, preserveAspectRatio: "none", role: "img", "aria-label": "Score over time" },
        [0, 0.25, 0.5, 0.75, 1].map((v) => s("line", { class: "gridline", x1: 0, x2: w, y1: y(v), y2: y(v), "vector-effect": "non-scaling-stroke" })),
        s("polygon", { points: area, fill: "var(--c)", "fill-opacity": 0.14 }),
        s("polyline", { points: line, fill: "none", stroke: "var(--c)", "stroke-width": 2.5, "stroke-linejoin": "round", "vector-effect": "non-scaling-stroke" })),
      [0, 0.25, 0.5, 0.75, 1].map((v) => h("span", { class: "yl", style: { top: `${(1 - v) * 100}%` } }, `${v * 100}%`)),
      labels));
}

/** How many weeks the calendar holds: a year on a laptop, less on a phone. */
function heatWeeks() {
  const vw = document.documentElement.clientWidth || window.innerWidth;
  return vw >= 1100 ? 53 : vw >= 700 ? 40 : 20;
}

/** Loop's calendar: a square per day with its date in it, days changed as in the list. */
function historyCard(habit, sm, today) {
  const weeks = heatWeeks(), cell = 18, gap = 4, top = 18, left = 30;
  const weekday = (L.dayDate(today).getUTCDay() + 6) % 7; // Monday = 0
  const start = today - weekday - (weeks - 1) * 7;
  const entries = entriesOf(habit.uuid);
  const c = color(habit), onC = onColor(c);
  const numerical = habit.data.type === L.NUMERICAL;
  const parts = [], months = [];
  let lastMonth = -1;
  for (let w = 0; w < weeks; w++) {
    const first = L.dayDate(start + w * 7);
    if (first.getUTCMonth() !== lastMonth) {
      lastMonth = first.getUTCMonth();
      if (w < weeks - 2) months.push(s("text", { x: left + w * (cell + gap), y: 11 }, monthShort.format(first)));
    }
    for (let d = 0; d < 7; d++) {
      const day = start + w * 7 + d;
      if (day > today) continue;
      const date = L.dayStr(day);
      const entry = entries.get(date);
      const computed = sm.computed.get(day) ?? L.UNKNOWN;
      let fill = "var(--surface-2)", opacity = 1, ink = "var(--muted)";
      if (numerical) {
        const amount = entry && entry.value > 0 && entry.value !== L.SKIP ? entry.value / 1000 : 0;
        if (entry && entry.value >= 0 && entry.value !== L.SKIP && meets(habit, amount) && (amount > 0 || habit.data.targetType === L.AT_MOST)) {
          fill = c; ink = onC;
        } else if (amount > 0) {
          fill = c; opacity = 0.35; ink = "var(--text)";
        }
      } else if (computed === L.YES_MANUAL) {
        fill = c; ink = onC;
      } else if (computed === L.YES_AUTO) {
        fill = c; opacity = 0.4; ink = "var(--text)";
      } else if (computed === L.SKIP) {
        fill = c; opacity = 0.18;
      }
      const x = left + w * (cell + gap), y = top + d * (cell + gap);
      const label = dateLong.format(L.dayDate(day));
      const rect = s("rect", {
        x, y, width: cell, height: cell, rx: 4, fill, "fill-opacity": opacity,
        stroke: day === today ? "var(--text)" : "none", "stroke-width": day === today ? 1.5 : 0,
      }, s("title", {}, label));
      dayGesture(rect, habit, date);
      parts.push(rect, s("text", { class: "dn", x: x + cell / 2, y: y + cell / 2 + 3, "text-anchor": "middle", fill: ink }, L.dayDate(day).getUTCDate()));
    }
  }
  const dayLabels = ["Mon", "", "Wed", "", "Fri", "", "Sun"].map((t, d) => t && s("text", { x: 0, y: top + d * (cell + gap) + 13 }, t));
  const width = left + weeks * (cell + gap), height = top + 7 * (cell + gap);
  return h("section", { class: "card heat wide" },
    h("h2", {}, "History"),
    s("svg", { viewBox: `0 0 ${width} ${height}`, role: "img", "aria-label": "Calendar of the last year" }, months, dayLabels, parts));
}

function streaksCard(habit, sm) {
  const best = [...sm.streaks].sort((a, b) => b[1] - b[0] - (a[1] - a[0]) || b[1] - a[1]).slice(0, 8)
    .sort((a, b) => b[1] - a[1]);
  if (!best.length) return h("section", { class: "card" }, h("h2", {}, "Best streaks"), h("p", { class: "muted small" }, "No streaks yet."));
  const longest = Math.max(...best.map(([b, e]) => e - b + 1));
  const fmt = (d) => dateLong.format(L.dayDate(d));
  return h("section", { class: "card" },
    h("h2", {}, "Best streaks"),
    h("div", { class: "streaks" }, best.map(([b, e]) => {
      const len = e - b + 1;
      return h("div", { class: "streak" },
        h("span", { class: "r" }, fmt(b)),
        h("div", { class: "barwrap" }, h("div", { class: "bar", style: { width: `${Math.max(8, (len / longest) * 100)}%`, background: "var(--c)" } }, len)),
        h("span", {}, fmt(e)));
    })));
}

/** Loop's frequency card: on which weekdays, month by month, the habit gets done. */
function frequencyCard(habit, sm, today) {
  const months = 12, colW = 40, rowH = 26, left = 40, top = 22;
  const end = L.dayDate(today);
  const cols = Array.from({ length: months }, (_, i) => {
    const m = new Date(Date.UTC(end.getUTCFullYear(), end.getUTCMonth() - (months - 1 - i), 1));
    return { y: m.getUTCFullYear(), m: m.getUTCMonth(), counts: Array(7).fill(0) };
  });
  const firstDay = Date.UTC(cols[0].y, cols[0].m, 1) / 86400000; // L.dayNum's numbering
  const numerical = habit.data.type === L.NUMERICAL;
  for (const [date, e] of entriesOf(habit.uuid)) {
    const day = L.dayNum(date);
    if (day < firstDay || day > today) continue;
    const done = numerical ? e.value > 0 && e.value !== L.SKIP : e.value === L.YES_MANUAL;
    if (!done) continue;
    const d = L.dayDate(day);
    const col = cols.find((c) => c.y === d.getUTCFullYear() && c.m === d.getUTCMonth());
    if (col) col.counts[(d.getUTCDay() + 6) % 7]++;
  }
  const max = Math.max(1, ...cols.flatMap((c) => c.counts));
  const width = left + months * colW, height = top + 7 * rowH;
  const dots = cols.flatMap((col, i) => col.counts.map((n, wd) => {
    const r = n ? 3 + 8 * Math.sqrt(n / max) : 2;
    return s("circle", {
      cx: left + i * colW + colW / 2, cy: top + wd * rowH + rowH / 2, r,
      fill: n ? "var(--c)" : "var(--surface-2)", "fill-opacity": n ? 0.35 + 0.65 * (n / max) : 1,
    }, s("title", {}, `${n} on ${["Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays", "Sundays"][wd]} in ${monthShort.format(new Date(Date.UTC(col.y, col.m, 1)))}`));
  }));
  return h("section", { class: "card freq" },
    h("h2", {}, "Frequency"),
    s("svg", { viewBox: `0 0 ${width} ${height}`, role: "img", "aria-label": "Days done by weekday and month" },
      cols.map((col, i) => s("text", { x: left + i * colW + colW / 2, y: 12, "text-anchor": "middle" }, monthShort.format(new Date(Date.UTC(col.y, col.m, 1))))),
      ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].map((t, wd) => s("text", { x: 0, y: top + wd * rowH + rowH / 2 + 4 }, t)),
      dots));
}

// ---- editor --------------------------------------------------------------------------------

const YES_NO_FREQUENCIES = [
  ["daily", "Every day"], ["weekly", "Every week"], ["monthly", "Every month"],
  ["perWeek", "Times per week"], ["perMonth", "Times per month"], ["everyN", "Every N days"], ["custom", "Times in N days"],
];

function frequencyKind(num, den) {
  if (num === den) return ["daily", 1, 1];
  if (num === 1 && den === 7) return ["weekly", 1, 7];
  if (num === 1 && den === 30) return ["monthly", 1, 30];
  if (den === 7) return ["perWeek", num, 7];
  if (den === 30) return ["perMonth", num, 30];
  if (num === 1) return ["everyN", 1, den];
  return ["custom", num, den];
}

/**
 * What the habit form saves, or a message saying what's wrong: the habit as stored when Save is
 * pressed, with each field the form `f` (its FormData) changed from `data`, the habit as the form
 * opened on it (or a new one's defaults). Everything else is as stored then, not as the form
 * opened: the phone or xrnrule may have changed the reminder, archived it, reordered the list or
 * set its icon or timer meanwhile, or renamed it while only its colour was changed here. The
 * habit's own notes (`description`) have had no box since 2026-11-15 (XRNLOOPS.md), so they go
 * along as stored too, never blanked, as on the phone (EditHabitActivity).
 */
function formHabit(uuid, data, f) {
  const edited = editedHabit(data, f);
  if (typeof edited === "string") return edited;
  const out = { ...(state.habits.get(uuid)?.data ?? data) };
  const changed = Object.keys(edited).filter((k) => JSON.stringify(edited[k]) !== JSON.stringify(data[k]));
  // How often is one setting: half of it from here and half from elsewhere would be neither's.
  if (changed.includes("freqNum") || changed.includes("freqDen")) changed.push("freqNum", "freqDen");
  for (const k of changed) out[k] = edited[k];
  return out;
}

/** `data` with what the form `f` holds, or a message saying what's wrong (formHabit). */
function editedHabit(data, f) {
  const numerical = data.type === L.NUMERICAL;
  const out = {
    ...data,
    name: String(f.get("name")).trim(),
    question: String(f.get("question")).trim(),
    color: Number(f.get("color") ?? data.color),
  };
  if (numerical) {
    out.targetType = Number(f.get("targetType"));
    out.targetValue = Number(f.get("targetValue"));
    out.unit = String(f.get("unit")).trim();
    out.freqDen = Number(f.get("per"));
    out.freqNum = out.freqDen === data.freqDen ? data.freqNum : 1;
    out.step = L.cleanStep(Number(f.get("step")));
    if (out.step == null) return `The step for + and − goes from ${L.MIN_STEP} to ${L.MAX_STEP}.`;
  } else {
    const k = String(f.get("kind"));
    const fn = Math.max(1, Number(f.get("num")) || 1), fd = Math.max(1, Number(f.get("den")) || 1);
    [out.freqNum, out.freqDen] = {
      daily: [1, 1], weekly: [1, 7], monthly: [1, 30], perWeek: [Math.min(fn, 7), 7],
      perMonth: [Math.min(fn, 30), 30], everyN: [1, fd], custom: [Math.min(fn, fd), fd],
    }[k];
  }
  if (!out.name) return "Give the habit a name.";
  return out;
}

function editView(habit, newType = L.YES_NO) {
  const data = habit ? { ...habit.data } : {
    name: "", question: "", description: "", color: 8, freqNum: 1, freqDen: 1, type: newType,
    targetType: L.AT_LEAST, targetValue: 0, unit: "", step: 1, archived: false, reminder: null,
    position: Math.max(-1, ...[...state.habits.values()].filter((x) => !x.deleted).map((x) => x.data.position)) + 1,
  };
  const numerical = data.type === L.NUMERICAL;
  const [kind, num, den] = frequencyKind(data.freqNum, data.freqDen);

  const name = h("input", { type: "text", name: "name", value: data.name, required: true, maxLength: 120, });
  const question = h("input", { type: "text", name: "question", value: data.question });
  const swatches = h("div", { class: "swatches", role: "radiogroup", "aria-label": "Colour" },
    palette().map((hex, i) => h("label", { style: { background: hex }, title: `Colour ${i + 1}` },
      h("input", { type: "radio", name: "color", value: i, checked: i === data.color }), h("i"))));

  let freqFields, stepField = null;
  if (numerical) {
    const pers = [[1, "per day"], [7, "per week"], [30, "per month"]];
    if (!pers.some(([v]) => v === data.freqDen)) pers.push([data.freqDen, `per ${data.freqDen} days`]); // the phone can set any
    const per = h("select", { name: "per" },
      pers.map(([v, t]) => h("option", { value: v, selected: data.freqDen === v }, t)));
    freqFields = h("div", { class: "inline" },
      h("select", { name: "targetType" },
        h("option", { value: L.AT_LEAST, selected: data.targetType === L.AT_LEAST }, "At least"),
        h("option", { value: L.AT_MOST, selected: data.targetType === L.AT_MOST }, "At most")),
      h("input", { type: "number", name: "targetValue", step: "any", min: 0, value: data.targetValue, required: true }),
      h("input", { type: "text", name: "unit", value: data.unit, placeholder: "Unit", style: { width: "140px" } }),
      per);
    // What the list's flick adds with + and takes off with − (NumberFlick; the phone's form has it too).
    stepField = h("label", { class: "field" }, h("span", {}, "Step for + and −"),
      h("input", {
        type: "number", name: "step", step: "any", min: L.MIN_STEP, max: L.MAX_STEP, required: true,
        value: L.formatStep(L.stepEntry(habit ?? { data })), style: { width: "140px" },
      }));
  } else {
    const kindSel = h("select", { name: "kind" }, YES_NO_FREQUENCIES.map(([v, t]) => h("option", { value: v, selected: v === kind }, t)));
    const n = h("input", { type: "number", name: "num", min: 1, max: 31, value: num });
    const d = h("input", { type: "number", name: "den", min: 1, max: 365, value: den });
    const nWrap = h("span", { class: "inline" }, n, h("span", { class: "muted" }, "times"));
    const dWrap = h("span", { class: "inline" }, h("span", { class: "muted" }, "in"), d, h("span", { class: "muted" }, "days"));
    const sync = () => {
      const k = kindSel.value;
      nWrap.style.display = ["perWeek", "perMonth", "custom"].includes(k) ? "" : "none";
      dWrap.style.display = ["everyN", "custom"].includes(k) ? "" : "none";
    };
    kindSel.addEventListener("change", sync);
    sync();
    freqFields = h("div", { class: "inline" }, kindSel, nWrap, dWrap);
  }

  const error = h("div", { class: "error" });
  const form = h("form", {
    class: "form",
    onsubmit: (e) => {
      e.preventDefault();
      const out = formHabit(habit?.uuid, data, new FormData(form));
      if (typeof out === "string") return (error.textContent = out);
      const uuid = habit?.uuid ?? crypto.randomUUID().replaceAll("-", "");
      formChanged = null;
      writeHabit(uuid, out);
      location.hash = `#/habit/${uuid}`;
    },
  },
    h("fieldset", {}, h("legend", {}, "The habit"),
      h("label", { class: "field" }, h("span", {}, "Name"), name),
      h("label", { class: "field" }, h("span", {}, "Question"), question)),
    h("fieldset", {}, h("legend", {}, numerical ? "Target" : "How often"), freqFields, stepField),
    h("fieldset", {}, h("legend", {}, "Colour"), swatches),
    error,
    h("div", { class: "inline" },
      h("button", { class: "btn primary big", type: "submit" }, habit ? "Save" : "Create habit"),
      h("a", { class: "btn ghost", href: habit ? `#/habit/${habit.uuid}` : "#/" }, "Cancel")));

  const fields = () => JSON.stringify([...new FormData(form)]);
  const opened = fields();
  formChanged = () => fields() !== opened;
  setTimeout(() => name.focus(), 0);
  return h("div", {},
    topBar("habits"),
    h("main", { class: "wrap narrow" }, h("h1", { class: "page-title" }, habit ? "Edit habit" : "New habit"), form));
}

// ---- weekly review --------------------------------------------------------------------------

/**
 * How a week went: the habits day by day, and what xrnrule's blocks did on the phone and the
 * laptop. Block history comes from xrnrule-sync's event log (/xrnrule/events, the same login),
 * which each device fills once sync is set up there. Weeks start on Monday, like the calendar.
 */
const review = { since: null, events: [], blocks: {}, status: "idle", gen: 0 };

async function loadReview(since) {
  if (review.since === since) return; // whatever came of it: this runs on every draw
  const gen = ++review.gen;
  Object.assign(review, { since, status: "loading", events: [], blocks: {} });
  try {
    const headers = { "X-Xrn-Client": "web" }; // the login cookie goes along by itself
    const [ev, st] = await Promise.all([
      fetch(new URL(`../xrnrule/events?since=${since}`, BASE), { headers, cache: "no-store" }),
      fetch(new URL("../xrnrule/state", BASE), { headers, cache: "no-store" }),
    ]);
    if (!ev.ok) throw new Error(String(ev.status));
    const events = (await ev.json()).events ?? [];
    const blocks = st.ok ? ((await st.json()).blocks ?? {}) : {};
    if (gen === review.gen) Object.assign(review, { events: distinctEvents(events), blocks, status: "ok" });
  } catch {
    if (gen === review.gen) review.status = "error";
  }
  render(true);
}

/**
 * The events with each shared happening once. An unlock is shared, so every device that saw it
 * end logs it: those carry the session's start (I28) and count once. A habit that earned an
 * unlock on both devices (each reads the tracker itself) counts once if they're minutes apart.
 */
function distinctEvents(events) {
  const sessions = new Set();
  const habits = [];
  return [...events].sort((a, b) => a.at - b.at).filter((e) => {
    if (e.t === "unlock" && e.start) {
      const key = `${e.block}@${e.start}`;
      if (sessions.has(key)) return false;
      sessions.add(key);
    }
    if (e.t === "habit") {
      if (habits.some((x) => x.block === e.block && x.d !== e.d && Math.abs(x.at - e.at) < 10 * 60000)) return false;
      habits.push(e);
    }
    return true;
  });
}

/** The local calendar day (L.dayNum's numbering) of a moment. */
function dayOfMs(ms) {
  const d = new Date(ms);
  return Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86400000;
}

/** Local midnight at the start of a day number. */
function msOfDay(day) {
  const d = L.dayDate(day);
  return new Date(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()).getTime();
}

/** One habit on one day: "met", "auto" (covered by the goal), "skip", "no" (marked not done) or "missed". */
function dayResult(habit, day) {
  if (habit.data.type === L.NUMERICAL) {
    const value = entriesOf(habit.uuid).get(L.dayStr(day))?.value ?? L.UNKNOWN;
    if (value === L.SKIP) return "skip";
    const amount = value > 0 ? value / 1000 : 0;
    if (value === L.UNKNOWN) return "missed";
    return meets(habit, amount) ? "met" : "no";
  }
  const v = summary(habit).computed.get(day) ?? L.UNKNOWN;
  if (v === L.YES_MANUAL) return "met";
  if (v === L.YES_AUTO) return "auto";
  if (v === L.SKIP) return "skip";
  return v === L.NO ? "no" : "missed";
}

/**
 * Met and possible habit-days in [from, to]; skips don't count against you, nor do days before a
 * habit's first (its first entry: a habit has no creation date, and Loop's score starts there too).
 */
function weekScore(habits, from, to) {
  let met = 0, possible = 0;
  for (const habit of habits) {
    for (let d = Math.max(from, summary(habit).from); d <= to; d++) {
      const r = dayResult(habit, d);
      if (r === "skip") continue;
      possible++;
      if (r === "met" || r === "auto") met++;
    }
  }
  return { met, possible, rate: possible ? met / possible : 0 };
}

const isDone = (result) => result === "met" || result === "auto";

/**
 * The streak running on `day`, counted up to that day (Loop's streaks, so a skip doesn't break
 * one). On today, one that ran to yesterday still runs, as the list's streak column counts it.
 */
function streakAt(sm, day, today) {
  const through = (d) => sm.streaks.find(([begin, end]) => begin <= d && d <= end);
  const streak = through(day) ?? (day === today ? through(day - 1) : null);
  return streak ? Math.min(streak[1], day) - streak[0] + 1 : 0;
}

/**
 * The week from `start` (a Monday) as the review shows it. Each habit's seven days (null for a
 * day still to come or before its first), done of possible (a skip counts for neither), and the
 * streak at the week's last day so far; each day's share of the habits done; the whole week's;
 * the best and worst habit (worst only if it did worse than the best); and the days with every
 * habit done.
 */
function weekReview(habits, start, today = L.todayNum()) {
  const days = Array.from({ length: 7 }, (_, i) => start + i);
  const last = Math.min(start + 6, today);
  const rows = habits.map((habit) => {
    const sm = summary(habit);
    const cells = days.map((d) => (d > today || d < sm.from ? null : dayResult(habit, d)));
    const counted = cells.filter((r) => r && r !== "skip");
    const done = counted.filter(isDone).length;
    return {
      habit, cells, done, possible: counted.length, rate: counted.length ? done / counted.length : null,
      streak: last < start ? 0 : streakAt(sm, last, today),
    };
  });
  const perDay = days.map((day, i) => {
    let met = 0, possible = 0;
    for (const row of rows) {
      const r = row.cells[i];
      if (!r || r === "skip") continue;
      possible++;
      if (isDone(r)) met++;
    }
    return { day, met, possible, rate: possible ? met / possible : null };
  });
  const met = rows.reduce((a, r) => a + r.done, 0);
  const possible = rows.reduce((a, r) => a + r.possible, 0);
  const ranked = rows.filter((r) => r.possible > 0)
    .sort((a, b) => b.rate - a.rate || b.done - a.done || a.habit.data.position - b.habit.data.position);
  const best = ranked[0] ?? null;
  const worst = ranked.length > 1 && ranked.at(-1).rate < best.rate ? ranked.at(-1) : null;
  const longest = rows.reduce((top, r) => (r.streak > (top?.streak ?? 0) ? r : top), null);
  return {
    days, rows, perDay, met, possible, rate: possible ? met / possible : null, best, worst, longest,
    perfect: perDay.filter((p) => p.possible > 0 && p.met === p.possible).length,
    daysSoFar: perDay.filter((p) => p.day <= today).length,
  };
}

/**
 * What xrnrule's blocks did in the week from `start`, from its event log (distinctEvents'): the
 * times something was blocked, day by day on the phone and on the laptop; the minutes unlocked
 * (an unlock is shared, so one total); unlocks earned and minutes bought; the most blocked apps
 * and sites; the unlocked time by block; and, to compare with, the same days of the week before
 * (`daysSoFar` of them: Monday to today while the week is still going).
 */
function blocksWeek(events, blocks, start, daysSoFar = 7) {
  const phone = Array(7).fill(0), laptop = Array(7).fill(0), unlocked = Array(7).fill(0);
  const before = { blocked: 0, unlocked: 0, earned: 0 };
  const targets = new Map(), byBlock = new Map();
  let earned = 0, bought = 0;
  for (const e of events) {
    const i = dayOfMs(e.at) - start;
    if (i >= -7 && i < -7 + daysSoFar) {
      if (e.t === "blocked") before.blocked++;
      else if (e.t === "unlock") before.unlocked += (e.ms ?? 0) / 60000;
      else if (e.t === "habit") before.earned++;
    }
    if (i < 0 || i > 6) continue;
    if (e.t === "blocked") {
      const device = e.d === "phone" ? "phone" : "laptop";
      (device === "phone" ? phone : laptop)[i]++;
      const name = e.name || e.target || "?";
      const t = targets.get(name) ?? { name, phone: 0, laptop: 0, total: 0 };
      t[device]++;
      t.total++;
      targets.set(name, t);
    } else if (e.t === "unlock") {
      const min = (e.ms ?? 0) / 60000;
      unlocked[i] += min;
      const name = blocks[e.block]?.name || "Deleted block";
      byBlock.set(name, (byBlock.get(name) ?? 0) + min);
    } else if (e.t === "habit") earned++;
    else if (e.t === "bought") bought += (e.ms ?? 0) / 60000;
  }
  const sum = (list) => list.reduce((a, b) => a + b, 0);
  return {
    phone, laptop, unlocked, earned, bought, before,
    blocked: sum(phone) + sum(laptop),
    phoneTotal: sum(phone), laptopTotal: sum(laptop), unlockedTotal: sum(unlocked),
    top: [...targets.values()].sort((a, b) => b.total - a.total || a.name.localeCompare(b.name)).slice(0, 5),
    byBlock: [...byBlock].map(([name, min]) => ({ name, min })).sort((a, b) => b.min - a.min).slice(0, 5),
  };
}

const RESULT_MARK = { met: "check", auto: "check", skip: "minus", no: "times" };
const RESULT_WORD = { met: "Done", auto: "Done (goal met)", skip: "Skipped", no: "Not done", missed: "Not entered" };
const dayRange = new Intl.DateTimeFormat(undefined, { day: "numeric", month: "short", timeZone: "UTC" });
const pct = (x) => (x == null ? "–" : `${Math.round(x * 100)}%`);
const signed = (n, unit = "") => `${n > 0 ? "+" : n < 0 ? "−" : "±"}${Math.abs(n)}${unit}`;

/** Minutes where a day's column has room for them: "45m", "1h20". */
function shortMinutes(min) {
  const m = Math.round(min);
  return m < 60 ? `${m}m` : `${Math.floor(m / 60)}h${m % 60 ? String(m % 60).padStart(2, "0") : ""}`;
}

function reviewView(offset) {
  const today = L.todayNum();
  const weekday = (L.dayDate(today).getUTCDay() + 6) % 7; // Monday = 0
  const start = today - weekday - offset * 7;
  loadReview(msOfDay(start - 7));
  const habits = visibleHabits().filter((x) => !x.data.archived);
  const week = weekReview(habits, start, today);
  const before = weekScore(habits, start - 7, start - 1);

  return h("div", {},
    topBar("review"),
    h("main", { class: "wrap review" },
      weekNav(offset, start),
      reviewSummary(week, before),
      habits.length
        ? habitWeek(week, today)
        : h("div", { class: "empty" }, h("p", {}, "No habits yet"), h("a", { class: "btn primary", href: "#/new" }, "New habit")),
      blocksSection(week.days, today)));
}

/** ‹ the week › — the arrows, ← and → go a week back and forth. */
function weekNav(offset, start) {
  const label = offset === 0 ? "This week" : offset === 1 ? "Last week" : `${offset} weeks ago`;
  const go = (n) => (n === 0 ? "#/review" : `#/review/${n}`);
  return h("div", { class: "week-nav" },
    h("a", { class: "icon-btn", href: go(offset + 1), title: "Week before", "aria-label": "Week before" }, stroke("prev")),
    h("div", { class: "week-title" },
      h("b", {}, dayRange.formatRange(L.dayDate(start), L.dayDate(start + 6))),
      h("span", {}, label)),
    offset > 0
      ? h("a", { class: "icon-btn", href: go(offset - 1), title: "Week after", "aria-label": "Week after" }, stroke("next"))
      : h("span", { class: "icon-btn off", "aria-hidden": "true" }, stroke("next")));
}

/** A ring for a share, in the accent or a habit's colour (--c). */
function shareRing(share, size, thickness) {
  const r = size / 2 - thickness / 2, c = 2 * Math.PI * r;
  return s("svg", { class: "ring", width: size, height: size, viewBox: `0 0 ${size} ${size}`, "aria-hidden": "true" },
    s("circle", { class: "track", cx: size / 2, cy: size / 2, r, fill: "none", "stroke-width": thickness }),
    share > 0 && s("circle", {
      cx: size / 2, cy: size / 2, r, fill: "none", stroke: "var(--c, var(--accent))", "stroke-width": thickness, "stroke-linecap": "round",
      "stroke-dasharray": `${c * share} ${c}`, transform: `rotate(-90 ${size / 2} ${size / 2})`,
    }));
}

function reviewSummary(week, before) {
  const delta = week.rate != null && before.possible ? Math.round(week.rate * 100) - Math.round(before.rate * 100) : null;
  const named = (label, row, figure) => h("div", { class: "rv-tile" + (row ? "" : " none"), style: row && habitColors(row.habit) },
    h("span", { class: "rv-label" }, label),
    row
      ? [h("a", { class: "rv-name" + (isEmojiName(row.habit.data.name) ? " emoji" : ""), href: `#/habit/${row.habit.uuid}` }, row.habit.data.name || "Untitled"),
        h("b", { class: "rv-fig" }, figure(row))]
      : h("b", { class: "rv-fig" }, "–"));
  return h("section", { class: "rv-summary" },
    h("div", { class: "rv-tile main" },
      h("div", { class: "ring-wrap" }, shareRing(week.rate ?? 0, 92, 10), h("b", {}, pct(week.rate))),
      h("div", { class: "rv-main-figs" },
        h("span", { class: "rv-label" }, "Done"),
        h("b", { class: "rv-fig" }, week.possible ? `${week.met}/${week.possible}` : "–"),
        delta != null && h("span", { class: "rv-delta " + (delta > 0 ? "up" : delta < 0 ? "down" : "") }, `${signed(delta)} pts`))),
    h("div", { class: "rv-tile" },
      h("span", { class: "rv-label" }, "Perfect days"),
      h("b", { class: "rv-fig" }, week.possible ? [String(week.perfect), h("small", {}, `/${week.daysSoFar}`)] : "–")),
    named("Best", week.best, (r) => pct(r.rate)),
    named("Worst", week.worst, (r) => pct(r.rate)),
    named("Longest streak", week.longest, (r) => [String(r.streak), h("small", {}, "d")]));
}

/** The day columns' heads, today marked as on the list. */
function dayHeads(days, today) {
  return days.map((d) => h("div", { class: "wk-day" + (d === today ? " today" : "") + (d > today ? " future" : "") },
    h("span", {}, weekdayShort.format(L.dayDate(d)).toUpperCase()),
    h("b", { class: L.markedDay(d) ? "mark" : null }, L.dayDate(d).getUTCDate())));
}

/** One habit's day: a block in its colour, joined to the day before and after when both are done. */
function weekCell(habit, cells, i, day) {
  const r = cells[i];
  if (!r) return h("div", { class: "wk-cell" }, h("i", { class: "blank" }));
  const doneish = (x) => isDone(x) || x === "skip";
  const joined = (doneish(r) ? (doneish(cells[i - 1]) ? " jl" : "") + (doneish(cells[i + 1]) ? " jr" : "") : "");
  let inside = r === "missed" ? null : mark(RESULT_MARK[r]);
  if (habit.data.type === L.NUMERICAL && r !== "skip" && r !== "missed") {
    const value = entriesOf(habit.uuid).get(L.dayStr(day))?.value ?? 0;
    inside = h("span", { class: "amt" }, L.formatNumber(value / 1000));
  }
  const word = `${dateLong.format(L.dayDate(day))}: ${RESULT_WORD[r]}`;
  return h("div", { class: "wk-cell", title: word, "aria-label": word, role: "img" }, h("i", { class: r + joined }, inside));
}

/** The habits × the week, a share and a streak each, and each day's share under them. */
function habitWeek(week, today) {
  const rows = week.rows.map((row) => {
    const name = row.habit.data.name || "Untitled";
    return h("div", { class: "wk-row", style: habitColors(row.habit) },
      h("a", { class: "wk-name" + (isEmojiName(name) ? " emoji" : ""), href: `#/habit/${row.habit.uuid}`, title: name }, name),
      row.cells.map((_, i) => weekCell(row.habit, row.cells, i, week.days[i])),
      h("div", { class: "wk-share" },
        h("div", { class: "meter" }, h("i", { style: { width: `${(row.rate ?? 0) * 100}%` } })),
        h("b", {}, pct(row.rate)),
        h("small", {}, row.possible ? `${row.done}/${row.possible}` : "")),
      h("div", { class: "wk-streak" }, row.streak ? [String(row.streak), h("small", {}, "d")] : "–"));
  });
  const dayBars = week.perDay.map((p) => h("div", { class: "wk-bar" + (p.rate == null ? " none" : "") + (p.rate === 1 ? " full" : ""), title: p.possible ? `${p.met}/${p.possible}` : "" },
    h("div", { class: "col" }, h("i", { style: { height: `${(p.rate ?? 0) * 100}%` } })),
    h("small", {}, p.rate == null ? "" : pct(p.rate))));
  return h("section", { class: "card week-grid" },
    h("div", { class: "wk-row wk-head" },
      h("div", {}),
      dayHeads(week.days, today),
      h("div", { class: "wk-h" }, "Done"),
      h("div", { class: "wk-h" }, "Streak")),
    rows,
    h("div", { class: "wk-row wk-foot" },
      h("div", { class: "wk-name all" }, "All"),
      dayBars,
      h("div", { class: "wk-share" }, h("b", {}, pct(week.rate))),
      h("div", {})));
}

/**
 * The xrnrule side, in one card under its tile: what was blocked on each device and what was
 * unlocked, day by day in the habits' columns, then the most blocked and the unlocked time by
 * block. The colours are xrnrule's: red blocked, green open, amber earned.
 */
function blocksSection(days, today) {
  const head = (...figures) => h("div", { class: "rule-head" },
    h("a", { class: "app-link", href: "../xrnrule/", title: "xrnrule", "aria-label": "xrnrule" }, tile("xrnrule", 30)),
    figures);
  const card = (...children) => h("section", { class: "card rule" }, ...children);
  if (review.status === "loading" || review.status === "idle") return card(head(h("span", { class: "muted" }, "…")));
  if (review.status === "error") {
    const again = () => {
      review.since = null;
      render();
    };
    return card(head(h("span", { class: "muted" }, "Can't reach xrnrule"), h("button", { class: "btn", onclick: again }, "Try again")));
  }
  const wk = blocksWeek(review.events, review.blocks, days[0], days.filter((d) => d <= today).length);
  if (!review.events.length || wk.blocked + wk.unlockedTotal + wk.earned + wk.bought === 0) {
    return card(head(h("span", { class: "muted" }, "Nothing logged")));
  }
  const figure = (cls, value, label, delta) => h("div", { class: `rule-fig ${cls}` },
    h("b", {}, value), h("span", {}, label), delta);
  const change = (now, then, goodWhenDown, unit = "") => {
    const n = Math.round(now - then);
    if (n === 0) return null;
    const tone = n === 0 || goodWhenDown == null ? "" : (n < 0) === goodWhenDown ? "up" : "down";
    return h("small", { class: `rv-delta ${tone}` }, signed(n, unit));
  };
  const peak = (list) => Math.max(1, ...list);
  const heatRow = (cls, glyph, label, values, total, format = String) => {
    const top = peak(values);
    return h("div", { class: `wk-row heat ${cls}` },
      h("div", { class: "wk-name" }, stroke(glyph), h("span", {}, label)),
      values.map((v, i) => {
        const k = v > 0 ? 0.18 + 0.82 * (v / top) : 0;
        const word = `${dateLong.format(L.dayDate(days[i]))}: ${format(v)}`;
        return h("div", { class: "wk-cell" + (days[i] > today ? " future" : "") },
          days[i] <= today && h("i", { class: v > 0 ? (k > 0.55 ? "strong" : "") : "zero", style: { "--k": k ? k.toFixed(3) : null }, title: word }, v > 0 ? format(v) : ""));
      }),
      h("div", { class: "wk-share" }, h("b", {}, total)),
      h("div", {}));
  };
  const listRows = (items, cls, value, bars) => {
    const top = Math.max(1, ...items.map(value));
    return h("ol", { class: `rule-list ${cls}` }, items.map((item) => h("li", {},
      h("span", { class: "nm" }, item.name),
      h("span", { class: "bar" }, bars(item, top)),
      h("b", {}, minuteOrCount(value(item), cls)))));
  };
  const minuteOrCount = (v, cls) => (cls === "unlocks" ? minutes(Math.round(v)) : String(v));
  return card(
    head(
      figure("blocked", wk.blocked, "Blocked", change(wk.blocked, wk.before.blocked, true)),
      figure("open", minutes(Math.round(wk.unlockedTotal)), "Unlocked", change(wk.unlockedTotal, wk.before.unlocked, null, " min")),
      figure("earned", wk.earned, "Earned", change(wk.earned, wk.before.earned, false)),
      wk.bought >= 0.5 && figure("bought", minutes(Math.round(wk.bought)), "Bought")),
    h("div", { class: "week-grid rule-days" },
      heatRow("blocked", "phone", "Phone", wk.phone, wk.phoneTotal),
      heatRow("blocked laptop", "laptop", "Laptop", wk.laptop, wk.laptopTotal),
      heatRow("open", "unlocked", "Unlocked", wk.unlocked, shortMinutes(wk.unlockedTotal), shortMinutes)),
    (wk.top.length > 0 || wk.byBlock.length > 0) && h("div", { class: "rule-lists" },
      wk.top.length > 0 && h("div", {}, h("h3", {}, "Most blocked"),
        listRows(wk.top, "targets", (t) => t.total, (t, top) => [
          t.phone > 0 && h("i", { class: "phone", style: { width: `${(t.phone / top) * 100}%` }, title: `Phone ${t.phone}` }),
          t.laptop > 0 && h("i", { class: "laptop", style: { width: `${(t.laptop / top) * 100}%` }, title: `Laptop ${t.laptop}` })])),
      wk.byBlock.length > 0 && h("div", {}, h("h3", {}, "Unlocked by block"),
        listRows(wk.byBlock, "unlocks", (b) => b.min, (b, top) => h("i", { style: { width: `${(b.min / top) * 100}%` } })))));
}

// A week back and forth from the keyboard, on the review.
document.addEventListener("keydown", (e) => {
  if (lastRoute !== "review" || flick || sheet || popover || e.altKey || e.ctrlKey || e.metaKey) return;
  if (e.target instanceof Element && e.target.closest("input, textarea, select")) return;
  const step = { ArrowLeft: 1, ArrowRight: -1 }[e.key];
  if (!step) return;
  const offset = Math.max(0, Number(location.hash.split("/")[2]) || 0) + step;
  if (offset < 0) return;
  location.hash = offset === 0 ? "#/review" : `#/review/${offset}`;
});

// ---- boot ----------------------------------------------------------------------------------

async function boot() {
  applyTheme();
  // A password kept by an older version of this page: traded for the cookie, then dropped.
  const old = load(KEY_OLD_AUTH, null);
  if (old) {
    try {
      await session(old);
      save(KEY_OLD_AUTH, null);
    } catch (e) {
      if (e instanceof AuthError) save(KEY_OLD_AUTH, null);
    }
  }
  if (!state.signedIn) return render();
  startOrWait();
}

boot();

// For web/test, which runs this file under node on stand-ins for a browser.
export { blocksWeek, checkChoices, dayHeads, distinctEvents, formHabit, listHeads, ingest, loginView, numberChoices, numberSheet, prefs, settingsPanel, signIn, state, tapDay, weekReview, weekScore };
