/*
 * Copyright (C) 2026 the xrnhabit authors
 *
 * This file is part of Loop Habit Tracker (the xrnloops fork). GPLv3 or later; see loop.js.
 */

// The xrnloops web app: Loop's habit list, a page per habit, and an editor. It talks to
// xrnloops-sync next to it (`api/...`): the whole history once, then a long-poll for changes,
// so a tick on the phone shows up here within a few seconds. Writes are queued in
// localStorage until the server has them.

import * as L from "./loop.js";

const BASE = new URL(".", location.href);
const KEY_AUTH = "xrnloops.auth";
const KEY_PENDING = "xrnloops.pending";
const DAYS_SHOWN = 7;

const state = {
  auth: load(KEY_AUTH, null),
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
};

const summaries = new Map(); // uuid -> L.summarize(...), dropped when that habit changes

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
    else if (k === "style" && typeof v === "object") Object.assign(el.style, v);
    else if (k in el && !(el instanceof SVGElement)) el[k] = v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const c of children.flat(Infinity)) if (c != null && c !== false) el.append(c instanceof Node ? c : String(c));
  return el;
}

const s = (tag, attrs = {}, ...children) => h(tag, { ...attrs, svg: true }, ...children);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

class AuthError extends Error {}

const weekdayShort = new Intl.DateTimeFormat(undefined, { weekday: "short", timeZone: "UTC" });
const dateLong = new Intl.DateTimeFormat(undefined, { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
const monthShort = new Intl.DateTimeFormat(undefined, { month: "short", timeZone: "UTC" });

// ---- data ----------------------------------------------------------------------------------

async function api(path, body) {
  const res = await fetch(new URL(path, BASE), {
    method: body ? "POST" : "GET",
    headers: {
      Authorization: state.auth,
      "X-Xrn-Client": "web",
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    cache: "no-store",
  });
  if (res.status === 401) throw new AuthError();
  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
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
  return page.habits.length + page.entries.length > 0;
}

async function loadAll(gen) {
  const habits = new Map(), entries = new Map();
  state.habits = habits;
  state.entries = entries;
  summaries.clear();
  let since = 0, more = true;
  while (more) {
    const page = await api(`api/changes?since=${since}`);
    if (gen !== state.generation) return;
    ingest(page);
    since = page.seq;
    more = page.more;
  }
  state.seq = since;
  state.loaded = true;
}

async function live(gen) {
  while (gen === state.generation) {
    try {
      const page = await api(`api/changes?since=${state.seq}&wait=25`);
      if (gen !== state.generation) return;
      const changed = ingest(page);
      state.seq = page.seq;
      setOnline(true);
      if (changed) render(true);
    } catch (e) {
      if (gen !== state.generation) return;
      if (e instanceof AuthError) return signOut("Your password was refused. Sign in again.");
      setOnline(false);
      await sleep(5000);
    }
  }
}

function setOnline(online) {
  if (state.online === online) return;
  state.online = online;
  updateSyncBadge();
  if (online) flush();
}

/** A write always wins over what it replaces, even if this computer's clock runs behind. */
function nextMtime(previous) {
  return Math.max(Date.now(), (previous ?? 0) + 1);
}

function writeEntry(uuid, date, value, notes = "") {
  const old = entriesOf(uuid).get(date);
  const rec = { habit: uuid, date, value, notes, mtime: nextMtime(old?.mtime) };
  entriesOf(uuid).set(date, { value, notes, mtime: rec.mtime });
  summaries.delete(uuid);
  state.pending.entries = state.pending.entries.filter((e) => !(e.habit === uuid && e.date === date));
  state.pending.entries.push(rec);
  save(KEY_PENDING, state.pending);
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

async function flush() {
  if (flushing || !state.auth) return;
  const batch = { habits: [...state.pending.habits], entries: [...state.pending.entries] };
  if (!batch.habits.length && !batch.entries.length) return;
  flushing = true;
  updateSyncBadge();
  try {
    const result = await api("api/changes", batch);
    // Drop what was sent, unless it was written again meanwhile.
    state.pending.habits = state.pending.habits.filter((p) => !batch.habits.includes(p));
    state.pending.entries = state.pending.entries.filter((p) => !batch.entries.includes(p));
    save(KEY_PENDING, state.pending);
    if (ingest({ habits: result.rejected.habits, entries: result.rejected.entries })) render(true);
    setOnline(true);
  } catch (e) {
    if (e instanceof AuthError) return signOut("Your password was refused. Sign in again.");
    setOnline(false);
    setTimeout(flush, 10000);
  } finally {
    flushing = false;
    updateSyncBadge();
  }
  if (state.pending.habits.length || state.pending.entries.length) flush();
}

window.addEventListener("online", flush);

// ---- auth ----------------------------------------------------------------------------------

async function signIn(user, password) {
  state.auth = "Basic " + btoa(unescape(encodeURIComponent(`${user}:${password}`)));
  try {
    await start();
    save(KEY_AUTH, state.auth);
    state.loginError = "";
  } catch (e) {
    state.auth = null;
    state.loginError = e instanceof AuthError ? "Wrong user name or password." : `Can't reach the server (${e.message}).`;
    render();
  }
}

function signOut(message = "") {
  state.generation++;
  state.auth = null;
  state.loaded = false;
  state.loginError = message;
  save(KEY_AUTH, null);
  render();
}

async function start() {
  const gen = ++state.generation;
  await loadAll(gen);
  render();
  live(gen);
  flush();
}

// ---- derived -------------------------------------------------------------------------------

function visibleHabits() {
  return [...state.habits.values()]
    .filter((h) => !h.deleted && (state.showArchived || !h.data.archived))
    .sort((a, b) => a.data.position - b.data.position || a.data.name.localeCompare(b.data.name));
}

function summary(habit) {
  let sm = summaries.get(habit.uuid);
  if (!sm) summaries.set(habit.uuid, (sm = L.summarize(habit, entriesOf(habit.uuid))));
  return sm;
}

function color(habit) {
  return L.PALETTE[habit.data.color] ?? L.PALETTE[8];
}

// ---- rendering -----------------------------------------------------------------------------

let renderQueued = false;
let dataOnly = true;
let drawAfterPopover = false;

/**
 * Redraws on the next frame. `fromServer` marks a redraw for data that arrived by itself: it
 * waits while a popover is open and skips the editor, so neither loses what you're typing.
 */
function render(fromServer = false) {
  dataOnly &&= fromServer;
  if (renderQueued) return;
  renderQueued = true;
  requestAnimationFrame(() => {
    renderQueued = false;
    const onlyData = dataOnly;
    dataOnly = true;
    if (onlyData && popover) return void (drawAfterPopover = true);
    if (onlyData && (lastRoute === "edit" || lastRoute === "new")) return;
    draw();
  });
}

function draw() {
  const app = document.getElementById("app");
  if (!state.auth) return app.replaceChildren(loginView());
  if (!state.loaded) return app.replaceChildren(h("p", { class: "boot" }, "Loading your habits…"));
  const [, route, id] = location.hash.match(/^#\/(\w+)?\/?(.*)$/) ?? [];
  const habit = id ? state.habits.get(id) : null;
  let view;
  if (route === "habit" && habit && !habit.deleted) view = habitView(habit);
  else if (route === "edit" && habit && !habit.deleted) view = editView(habit);
  else if (route === "new") view = editView(null, id === "numerical" ? L.NUMERICAL : L.YES_NO);
  else view = listView();
  const scrollY = window.scrollY;
  app.replaceChildren(view);
  if (route === lastRoute) window.scrollTo(0, scrollY);
  lastRoute = route;
  updateSyncBadge();
}

let lastRoute = null;

window.addEventListener("hashchange", () => {
  closePopover();
  render();
  window.scrollTo(0, 0);
});

function topBar(...right) {
  return h("header", { class: "bar" },
    h("div", { class: "wrap" },
      h("h1", {}, h("a", { href: "#/", style: { display: "contents", color: "inherit" } },
        h("img", { src: "icon.svg", alt: "" }), "xrnloops")),
      h("span", { class: "spacer" }),
      h("span", { class: "sync", id: "sync" }),
      ...right));
}

function updateSyncBadge() {
  const el = document.getElementById("sync");
  if (!el) return;
  const queued = state.pending.habits.length + state.pending.entries.length;
  el.className = "sync" + (!state.online ? " off" : queued ? " busy" : "");
  el.textContent = !state.online ? (queued ? `Offline · ${queued} to send` : "Offline") : queued ? "Saving…" : "Synced";
}

// ---- login ---------------------------------------------------------------------------------

function loginView() {
  const form = h("form", {
    class: "form card",
    onsubmit: (e) => {
      e.preventDefault();
      const data = new FormData(form);
      button.disabled = true;
      button.textContent = "Signing in…";
      signIn(String(data.get("user")).trim(), String(data.get("password")));
    },
  },
    h("label", { class: "field" }, "User", h("input", { type: "text", name: "user", value: "sonn", autocomplete: "username", required: true })),
    h("label", { class: "field" }, "Password", h("input", { type: "password", name: "password", autocomplete: "current-password", required: true, autofocus: true })),
    state.loginError && h("div", { class: "error" }, state.loginError),
  );
  const button = h("button", { class: "btn primary", type: "submit" }, "Sign in");
  form.append(h("div", { class: "actions inline" }, button));
  return h("div", { class: "wrap login" },
    h("h1", {}, h("img", { src: "icon.svg", alt: "", width: 32, height: 32 }), "xrnloops"),
    h("p", {}, "Your habits, synced with xrnloops on the phone. Sign in with the home server's password."),
    form);
}

// ---- list ----------------------------------------------------------------------------------

function listView() {
  const today = L.todayNum();
  const habits = visibleHabits();
  const head = h("div", { class: "row head" },
    h("div", { class: "habit", style: { cursor: "default" } }, h("span", { class: "muted small" }, dateLong.format(L.dayDate(today)))),
    h("div", { class: "days" }, Array.from({ length: DAYS_SHOWN }, (_, i) => {
      const d = L.dayDate(today - i);
      return h("div", { class: `day d${i}` + (i === 0 ? " today" : "") }, weekdayShort.format(d), h("b", {}, d.getUTCDate()));
    })));
  const rows = habits.map((habit) => habitRow(habit, today));
  const anyArchived = [...state.habits.values()].some((x) => !x.deleted && x.data.archived);
  const grid = habits.length
    ? h("div", { class: "grid" }, head, rows)
    : h("div", { class: "grid empty" },
      h("p", {}, state.habits.size ? "Every habit is archived." : "No habits yet. Make one here or in xrnloops on the phone."),
      h("a", { class: "btn primary", href: "#/new" }, "New habit"));
  const view = h("div", {},
    topBar(
      h("a", { class: "btn ghost", href: "../xrnhabit/", title: "What xrnhabit is blocking right now" }, "Blocks"),
      newMenuButton()),
    h("main", { class: "wrap" },
      grid,
      h("div", { class: "foot" },
        h("label", {}, h("input", {
          type: "checkbox", checked: state.showArchived, disabled: !anyArchived && !state.showArchived,
          onchange: (e) => { state.showArchived = e.target.checked; render(); },
        }), "Show archived"),
        h("span", {}, "Click a day to check it · right-click for skip, notes and more · ",
          h("a", { href: "#", onclick: (e) => { e.preventDefault(); signOut(); } }, "Sign out")))));
  return view;
}

function newMenuButton() {
  return h("button", {
    class: "btn primary",
    onclick: (e) => openPopover(e.currentTarget, [
      h("div", { class: "title" }, "New habit"),
      menuItem("✓", "Yes or no", () => (location.hash = "#/new")),
      menuItem("#", "Measurable (a number)", () => (location.hash = "#/new/numerical")),
    ]),
  }, "+ New");
}

function scoreRing(habit, score, size = 34) {
  const r = size / 2 - 4, c = 2 * Math.PI * r;
  return s("svg", { class: "ring", width: size, height: size, viewBox: `0 0 ${size} ${size}`, role: "img", "aria-label": `Score ${Math.round(score * 100)}%` },
    s("circle", { class: "track", cx: size / 2, cy: size / 2, r, fill: "none", "stroke-width": 4 }),
    s("circle", {
      cx: size / 2, cy: size / 2, r, fill: "none", stroke: color(habit), "stroke-width": 4, "stroke-linecap": "round",
      "stroke-dasharray": `${c * score} ${c}`, transform: `rotate(-90 ${size / 2} ${size / 2})`,
    }));
}

function habitRow(habit, today) {
  const sm = summary(habit);
  const sub = [L.describeFrequency(habit)];
  if (sm.currentStreak > 1) sub.push(`${sm.currentStreak}-day streak`);
  return h("div", { class: "row" + (habit.data.archived ? " archived" : "") },
    h("div", { class: "habit", onclick: () => (location.hash = `#/habit/${habit.uuid}`) },
      scoreRing(habit, sm.score),
      h("div", {},
        h("div", { class: "name", style: { color: color(habit) } }, habit.data.name || "Untitled"),
        h("div", { class: "sub" }, habit.data.question || sub.join(" · ")))),
    h("div", { class: "days" }, Array.from({ length: DAYS_SHOWN }, (_, i) => dayCell(habit, today - i, `d${i}`))));
}

function dayCell(habit, day, extraClass = "") {
  const date = L.dayStr(day);
  const entry = entriesOf(habit.uuid).get(date);
  const value = entry?.value ?? L.UNKNOWN;
  const computed = summary(habit).computed.get(day) ?? L.UNKNOWN;
  const numerical = habit.data.type === L.NUMERICAL;
  let cls, mark, style = {};
  if (numerical) {
    const amount = value > 0 ? value / 1000 : 0;
    const met = habit.data.targetType === L.AT_LEAST ? amount >= habit.data.targetValue : value !== L.UNKNOWN && amount <= habit.data.targetValue;
    cls = "num" + (met ? "" : " miss");
    mark = value === L.UNKNOWN ? "0" : value === L.SKIP ? "»" : L.formatNumber(amount);
    if (met) style.color = color(habit);
  } else if (computed === L.YES_MANUAL) {
    cls = "yes"; mark = "✓"; style.color = color(habit);
  } else if (computed === L.YES_AUTO) {
    cls = "auto"; mark = "✓"; style.color = color(habit);
  } else if (computed === L.SKIP) {
    cls = "skip"; mark = "»";
  } else if (value === L.NO) {
    cls = "no"; mark = "×";
  } else {
    cls = "unknown"; mark = "·";
  }
  const label = `${habit.data.name}, ${dateLong.format(L.dayDate(day))}`;
  const btn = h("button", {
    class: `cell ${cls} ${extraClass}` + (entry?.notes ? " noted" : ""),
    title: entry?.notes ? `${label}\n${entry.notes}` : label,
    "aria-label": label,
    onclick: (e) => {
      if (numerical) return numberPopover(e.currentTarget, habit, date);
      writeEntry(habit.uuid, date, L.nextToggleValue(computed), entry?.notes ?? "");
    },
    oncontextmenu: (e) => {
      e.preventDefault();
      dayMenu(e.currentTarget, habit, date);
    },
  }, h("span", { class: "mark", style }, mark));
  longPress(btn, () => dayMenu(btn, habit, date));
  return btn;
}

function longPress(el, action) {
  let timer = null;
  el.addEventListener("pointerdown", (e) => {
    if (e.pointerType !== "touch") return;
    timer = setTimeout(() => {
      timer = null;
      el.dataset.longpressed = "1";
      action();
    }, 500);
  });
  const cancel = () => clearTimeout(timer);
  el.addEventListener("pointerup", cancel);
  el.addEventListener("pointerleave", cancel);
  el.addEventListener("click", (e) => {
    if (el.dataset.longpressed) {
      delete el.dataset.longpressed;
      e.stopImmediatePropagation();
    }
  }, true);
}

// ---- popovers ------------------------------------------------------------------------------

let popover = null;

function openPopover(anchor, children) {
  closePopover();
  popover = h("div", { class: "pop", role: "menu" }, children);
  document.body.append(popover);
  const a = anchor.getBoundingClientRect(), p = popover.getBoundingClientRect();
  const left = Math.min(Math.max(8, a.left + a.width / 2 - p.width / 2), window.innerWidth - p.width - 8);
  const top = a.bottom + 6 + p.height > window.innerHeight ? Math.max(8, a.top - p.height - 6) : a.bottom + 6;
  Object.assign(popover.style, { left: `${left}px`, top: `${top}px` });
  popover.querySelector("input, textarea, button")?.focus();
  setTimeout(() => document.addEventListener("pointerdown", outside), 0);
}

function outside(e) {
  if (popover && !popover.contains(e.target)) closePopover();
}

function closePopover() {
  popover?.remove();
  popover = null;
  document.removeEventListener("pointerdown", outside);
  if (drawAfterPopover) {
    drawAfterPopover = false;
    render();
  }
}

document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") closePopover();
});

function menuItem(icon, text, action) {
  return h("button", { class: "item", type: "button", onclick: () => { closePopover(); action(); } }, h("span", {}, icon), text);
}

function dayMenu(anchor, habit, date) {
  const entry = entriesOf(habit.uuid).get(date);
  const notes = entry?.notes ?? "";
  const title = h("div", { class: "title" }, `${habit.data.name} · ${dateLong.format(L.dayDate(L.dayNum(date)))}`);
  if (habit.data.type === L.NUMERICAL) return numberPopover(anchor, habit, date);
  openPopover(anchor, [
    title,
    menuItem("✓", "Done", () => writeEntry(habit.uuid, date, L.YES_MANUAL, notes)),
    menuItem("»", "Skipped", () => writeEntry(habit.uuid, date, L.SKIP, notes)),
    menuItem("×", "Not done", () => writeEntry(habit.uuid, date, L.NO, notes)),
    menuItem("·", "Clear", () => writeEntry(habit.uuid, date, L.UNKNOWN, notes)),
    menuItem("✎", notes ? "Edit note…" : "Add a note…", () => notePopover(anchor, habit, date)),
  ]);
}

function notePopover(anchor, habit, date) {
  const entry = entriesOf(habit.uuid).get(date);
  const text = h("textarea", { value: entry?.notes ?? "", placeholder: "How did it go?" });
  const form = h("form", {
    onsubmit: (e) => {
      e.preventDefault();
      closePopover();
      writeEntry(habit.uuid, date, entry?.value ?? L.UNKNOWN, text.value.trim());
    },
  }, text, h("div", { class: "actions" },
    h("button", { class: "btn ghost", type: "button", onclick: closePopover }, "Cancel"),
    h("button", { class: "btn primary", type: "submit" }, "Save")));
  openPopover(anchor, [h("div", { class: "title" }, `Note · ${dateLong.format(L.dayDate(L.dayNum(date)))}`), form]);
}

function numberPopover(anchor, habit, date) {
  const entry = entriesOf(habit.uuid).get(date);
  const current = entry && entry.value >= 0 && entry.value !== L.SKIP ? entry.value / 1000 : "";
  const amount = h("input", { type: "number", step: "any", min: 0, value: current, placeholder: "0" });
  const notes = h("textarea", { value: entry?.notes ?? "", placeholder: "Note (optional)" });
  const form = h("form", {
    onsubmit: (e) => {
      e.preventDefault();
      closePopover();
      const n = amount.value === "" ? L.UNKNOWN : Math.round(Number(amount.value) * 1000);
      writeEntry(habit.uuid, date, n, notes.value.trim());
    },
  },
    h("div", { class: "inline" }, amount, h("span", { class: "muted" }, habit.data.unit)),
    notes,
    h("div", { class: "actions" },
      h("button", { class: "btn ghost", type: "button", onclick: closePopover }, "Cancel"),
      h("button", { class: "btn primary", type: "submit" }, "Save")));
  openPopover(anchor, [h("div", { class: "title" }, `${habit.data.name} · ${dateLong.format(L.dayDate(L.dayNum(date)))}`), form]);
  amount.select();
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
  const total = habit.data.type === L.NUMERICAL
    ? L.formatNumber([...entries.values()].reduce((sum, e) => sum + (e.value > 0 && e.value !== L.SKIP ? e.value / 1000 : 0), 0))
    : [...entries.values()].filter((e) => e.value === L.YES_MANUAL).length;
  const c = color(habit);

  return h("div", {},
    topBar(h("a", { class: "btn ghost", href: "#/" }, "← All habits")),
    h("main", { class: "wrap" },
      h("section", { class: "card" },
        h("div", { class: "hero" },
          scoreRing(habit, sm.score, 56),
          h("div", { class: "info" },
            h("h1", { style: { color: c } }, habit.data.name || "Untitled"),
            habit.data.question && h("div", { class: "q" }, habit.data.question),
            h("span", { class: "freq" }, L.describeFrequency(habit)),
            habit.data.archived && h("span", { class: "freq", style: { marginLeft: "6px" } }, "Archived"),
            habit.data.description && h("p", { class: "muted small", style: { whiteSpace: "pre-wrap", marginBottom: 0 } }, habit.data.description))),
        h("div", { class: "toolbar" },
          h("a", { class: "btn", href: `#/edit/${habit.uuid}` }, "Edit"),
          h("button", {
            class: "btn",
            onclick: () => writeHabit(habit.uuid, { ...habit.data, archived: !habit.data.archived }),
          }, habit.data.archived ? "Unarchive" : "Archive"),
          h("button", {
            class: "btn danger",
            onclick: () => {
              if (!confirm(`Delete "${habit.data.name}" and its whole history, here and on the phone?`)) return;
              writeHabit(habit.uuid, habit.data, true);
              location.hash = "#/";
            },
          }, "Delete"))),
      h("section", { class: "card" },
        h("h2", {}, "Overview"),
        h("div", { class: "stats" },
          h("div", { class: "stat" }, h("b", { style: { color: c } }, pct(sm.score)), h("span", {}, "Score")),
          h("div", { class: "stat" }, h("b", {}, delta(30)), h("span", {}, "Month")),
          h("div", { class: "stat" }, h("b", {}, delta(365)), h("span", {}, "Year")),
          h("div", { class: "stat" }, h("b", {}, total), h("span", {}, habit.data.type === L.NUMERICAL ? `Total ${habit.data.unit}` : "Total")))),
      h("section", { class: "card" },
        h("div", { class: "stats" },
          h("div", { class: "stat" }, h("b", {}, sm.currentStreak), h("span", {}, "Current streak (days)")),
          h("div", { class: "stat" }, h("b", {}, sm.bestStreak), h("span", {}, "Best streak (days)")))),
      scoreCard(habit, sm, today),
      historyCard(habit, sm, today),
      streaksCard(habit, sm),
      notesCard(habit)));
}

function scoreCard(habit, sm, today) {
  const range = state.chartRange;
  const from = today - range + 1;
  const w = 800, ht = 160, pad = 28;
  const points = [];
  const step = Math.max(1, Math.floor(range / 120));
  for (let d = from; d <= today; d += step) points.push([d, sm.scoreAt(d)]);
  if (points.at(-1)[0] !== today) points.push([today, sm.scoreAt(today)]);
  const x = (d) => pad + ((d - from) / Math.max(1, range - 1)) * (w - pad);
  const y = (v) => 6 + (1 - v) * (ht - 24);
  const line = points.map(([d, v]) => `${x(d).toFixed(1)},${y(v).toFixed(1)}`).join(" ");
  const area = `${x(from)},${y(0)} ${line} ${x(today)},${y(0)}`;
  const labels = [];
  for (let i = 0; i <= 3; i++) {
    const d = from + Math.round(((range - 1) * i) / 3);
    labels.push(s("text", { x: x(d), y: ht, "text-anchor": i === 0 ? "start" : i === 3 ? "end" : "middle" },
      range > 60 ? monthShort.format(L.dayDate(d)) : L.dayDate(d).getUTCDate()));
  }
  const rangeBtn = (days, label) => h("button", {
    class: "btn" + (range === days ? " primary" : " ghost"),
    onclick: () => { state.chartRange = days; render(); },
  }, label);
  return h("section", { class: "card chart" },
    h("div", { class: "head" }, h("h2", {}, "Score"),
      h("div", { class: "chart-range" }, rangeBtn(31, "Month"), rangeBtn(92, "Quarter"), rangeBtn(365, "Year"))),
    s("svg", { viewBox: `0 0 ${w} ${ht}`, preserveAspectRatio: "none", role: "img", "aria-label": "Score over time" },
      [0, 0.5, 1].map((v) => [
        s("line", { class: "gridline", x1: pad, x2: w, y1: y(v), y2: y(v) }),
        s("text", { x: 0, y: y(v) + 4 }, `${v * 100}%`),
      ]),
      s("polygon", { points: area, fill: color(habit), "fill-opacity": 0.12 }),
      s("polyline", { points: line, fill: "none", stroke: color(habit), "stroke-width": 2.5, "stroke-linejoin": "round", "vector-effect": "non-scaling-stroke" }),
      labels));
}

function historyCard(habit, sm, today) {
  const weeks = 53, cell = 13, gap = 3, top = 16, left = 26;
  const todayDate = L.dayDate(today);
  const weekday = (todayDate.getUTCDay() + 6) % 7; // Monday = 0
  const start = today - weekday - (weeks - 1) * 7;
  const entries = entriesOf(habit.uuid);
  const c = color(habit);
  const numerical = habit.data.type === L.NUMERICAL;
  const rects = [], months = [];
  let lastMonth = -1;
  for (let w = 0; w < weeks; w++) {
    const first = L.dayDate(start + w * 7);
    if (first.getUTCMonth() !== lastMonth) {
      lastMonth = first.getUTCMonth();
      if (w < weeks - 2) months.push(s("text", { x: left + w * (cell + gap), y: 10 }, monthShort.format(first)));
    }
    for (let d = 0; d < 7; d++) {
      const day = start + w * 7 + d;
      if (day > today) continue;
      const date = L.dayStr(day);
      const entry = entries.get(date);
      const computed = sm.computed.get(day) ?? L.UNKNOWN;
      let fill = "var(--surface-2)", opacity = 1;
      if (numerical) {
        const amount = entry && entry.value > 0 ? entry.value / 1000 : 0;
        if (amount > 0) {
          fill = c;
          opacity = habit.data.targetValue > 0 ? Math.max(0.25, Math.min(1, amount / habit.data.targetValue)) : 1;
        }
      } else if (computed === L.YES_MANUAL) fill = c;
      else if (computed === L.YES_AUTO) { fill = c; opacity = 0.35; }
      else if (computed === L.SKIP) { fill = c; opacity = 0.15; }
      const label = `${dateLong.format(L.dayDate(day))}${entry?.notes ? ` — ${entry.notes}` : ""}`;
      rects.push(s("rect", {
        x: left + w * (cell + gap), y: top + d * (cell + gap), width: cell, height: cell, rx: 3,
        fill, "fill-opacity": opacity,
        onclick: (e) => {
          if (numerical) return numberPopover(e.currentTarget, habit, date);
          writeEntry(habit.uuid, date, L.nextToggleValue(computed), entry?.notes ?? "");
        },
        oncontextmenu: (e) => { e.preventDefault(); dayMenu(e.currentTarget, habit, date); },
      }, s("title", {}, label)));
    }
  }
  const dayLabels = ["Mon", "", "Wed", "", "Fri", "", ""].map((t, d) => t && s("text", { x: 0, y: top + d * (cell + gap) + 10 }, t));
  const width = left + weeks * (cell + gap), height = top + 7 * (cell + gap);
  return h("section", { class: "card heat" },
    h("h2", {}, "History"),
    s("svg", { width, height, viewBox: `0 0 ${width} ${height}` }, months, dayLabels, rects),
    h("p", { class: "muted small", style: { margin: "8px 0 0" } }, "Click a day to change it; right-click for a note."));
}

function streaksCard(habit, sm) {
  const best = [...sm.streaks].sort((a, b) => b[1] - b[0] - (a[1] - a[0]) || b[1] - a[1]).slice(0, 5)
    .sort((a, b) => b[1] - a[1]);
  if (!best.length) return null;
  const longest = Math.max(...best.map(([b, e]) => e - b + 1));
  const fmt = (d) => dateLong.format(L.dayDate(d));
  return h("section", { class: "card" },
    h("h2", {}, "Best streaks"),
    h("div", { class: "streaks" }, best.map(([b, e]) => {
      const len = e - b + 1;
      return h("div", { class: "streak" },
        h("span", { class: "r" }, fmt(b)),
        h("div", { class: "barwrap" }, h("div", { class: "bar", style: { width: `${Math.max(8, (len / longest) * 100)}%`, background: color(habit) } }, len)),
        h("span", {}, fmt(e)));
    })));
}

function notesCard(habit) {
  const notes = [...entriesOf(habit.uuid)].filter(([, e]) => e.notes).sort((a, b) => (a[0] < b[0] ? 1 : -1)).slice(0, 30);
  if (!notes.length) return null;
  return h("section", { class: "card" },
    h("h2", {}, "Notes"),
    h("div", { class: "notes" }, notes.map(([date, e]) =>
      h("div", { class: "note" }, h("time", { datetime: date }, dateLong.format(L.dayDate(L.dayNum(date)))), h("div", { style: { whiteSpace: "pre-wrap" } }, e.notes)))));
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

function editView(habit, newType = L.YES_NO) {
  const data = habit ? { ...habit.data } : {
    name: "", question: "", description: "", color: 8, freqNum: 1, freqDen: 1, type: newType,
    targetType: L.AT_LEAST, targetValue: 0, unit: "", archived: false, reminder: null,
    position: Math.max(-1, ...[...state.habits.values()].filter((x) => !x.deleted).map((x) => x.data.position)) + 1,
  };
  const numerical = data.type === L.NUMERICAL;
  const [kind, num, den] = frequencyKind(data.freqNum, data.freqDen);

  const name = h("input", { type: "text", name: "name", value: data.name, required: true, maxLength: 120, placeholder: numerical ? "e.g. Run" : "e.g. Meditate" });
  const question = h("input", { type: "text", name: "question", value: data.question, placeholder: numerical ? "e.g. How many km did you run today?" : "e.g. Did you meditate today?" });
  const description = h("textarea", { name: "description", value: data.description, placeholder: "Notes about this habit (optional)" });
  const swatches = h("div", { class: "swatches", role: "radiogroup", "aria-label": "Colour" },
    L.PALETTE.map((hex, i) => h("label", { style: { background: hex }, title: `Colour ${i + 1}` },
      h("input", { type: "radio", name: "color", value: i, checked: i === data.color }), h("i"))));

  let freqFields;
  if (numerical) {
    const per = h("select", { name: "per" },
      [[1, "per day"], [7, "per week"], [30, "per month"]].map(([v, t]) => h("option", { value: v, selected: data.freqDen === v || (v === 30 && data.freqDen > 7) }, t)));
    freqFields = [
      h("label", { class: "field" }, "Target",
        h("div", { class: "inline" },
          h("select", { name: "targetType" },
            h("option", { value: L.AT_LEAST, selected: data.targetType === L.AT_LEAST }, "At least"),
            h("option", { value: L.AT_MOST, selected: data.targetType === L.AT_MOST }, "At most")),
          h("input", { type: "number", name: "targetValue", step: "any", min: 0, value: data.targetValue, required: true }),
          h("input", { type: "text", name: "unit", value: data.unit, placeholder: "unit, e.g. km", style: { width: "140px" } }),
          per)),
    ];
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
    freqFields = [h("label", { class: "field" }, "Frequency", h("div", { class: "inline" }, kindSel, nWrap, dWrap))];
  }

  const error = h("div", { class: "error" });
  const form = h("form", {
    class: "form",
    onsubmit: (e) => {
      e.preventDefault();
      const f = new FormData(form);
      const out = {
        ...data,
        name: String(f.get("name")).trim(),
        question: String(f.get("question")).trim(),
        description: String(f.get("description")).trim(),
        color: Number(f.get("color") ?? data.color),
      };
      if (numerical) {
        out.targetType = Number(f.get("targetType"));
        out.targetValue = Number(f.get("targetValue"));
        out.unit = String(f.get("unit")).trim();
        out.freqNum = 1;
        out.freqDen = Number(f.get("per"));
      } else {
        const k = String(f.get("kind"));
        const fn = Math.max(1, Number(f.get("num")) || 1), fd = Math.max(1, Number(f.get("den")) || 1);
        [out.freqNum, out.freqDen] = {
          daily: [1, 1], weekly: [1, 7], monthly: [1, 30], perWeek: [Math.min(fn, 7), 7],
          perMonth: [Math.min(fn, 30), 30], everyN: [1, fd], custom: [Math.min(fn, fd), fd],
        }[k];
      }
      if (!out.name) return (error.textContent = "Give the habit a name.");
      const uuid = habit?.uuid ?? crypto.randomUUID().replaceAll("-", "");
      writeHabit(uuid, out);
      location.hash = `#/habit/${uuid}`;
    },
  },
    h("label", { class: "field" }, "Name", name),
    h("label", { class: "field" }, "Question", question),
    ...freqFields,
    h("div", { class: "field" }, h("span", { class: "muted small" }, "Colour"), swatches),
    h("label", { class: "field" }, "Notes", description),
    error,
    h("div", { class: "inline" },
      h("button", { class: "btn primary", type: "submit" }, habit ? "Save" : "Create habit"),
      h("a", { class: "btn ghost", href: habit ? `#/habit/${habit.uuid}` : "#/" }, "Cancel")));

  setTimeout(() => name.focus(), 0);
  return h("div", {},
    topBar(h("a", { class: "btn ghost", href: habit ? `#/habit/${habit.uuid}` : "#/" }, "← Back")),
    h("main", { class: "wrap" },
      h("section", { class: "card" },
        h("h2", {}, habit ? "Edit habit" : numerical ? "New measurable habit" : "New habit"),
        form),
      habit?.data.reminder && h("p", { class: "muted small" }, "Reminders are set in xrnloops on the phone.")));
}

// ---- boot ----------------------------------------------------------------------------------

if (state.auth) {
  start().catch((e) => {
    if (e instanceof AuthError) signOut("Your password was refused. Sign in again.");
    else {
      state.loginError = "";
      document.getElementById("app").replaceChildren(h("div", { class: "wrap login" },
        h("h1", {}, "Can't reach the server"),
        h("p", {}, e.message),
        h("button", { class: "btn primary", onclick: () => location.reload() }, "Try again")));
    }
  });
} else {
  render();
}
