/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 * Copyright (C) 2026 the xrnrule authors
 *
 * This file is part of Loop Habit Tracker (the xrnloops fork).
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. It is distributed WITHOUT
 * ANY WARRANTY; see the GNU General Public License for more details:
 * <http://www.gnu.org/licenses/>.
 */

// Loop's rules, ported from uhabits-core: EntryList (auto-checks), ScoreList, StreakList.
// Days are plain numbers (days since 1970-01-01), so arithmetic is just + and -.

export const UNKNOWN = -1, NO = 0, YES_AUTO = 1, YES_MANUAL = 2, SKIP = 3;
export const YES_NO = 0, NUMERICAL = 1;
export const AT_LEAST = 0, AT_MOST = 1;

export const PALETTE = [
  "#D32F2F", "#E64A19", "#F57C00", "#FF8F00", "#F9A825", "#AFB42B", "#7CB342", "#388E3C",
  "#00897B", "#00ACC1", "#039BE5", "#1976D2", "#303F9F", "#5E35B1", "#8E24AA", "#D81B60",
  "#5D4037", "#303030", "#757575", "#aaaaaa",
];

const DAY_MS = 86400000;

export function dayNum(text) {
  const [y, m, d] = text.split("-").map(Number);
  return Date.UTC(y, m - 1, d) / DAY_MS;
}

export function dayStr(n) {
  return new Date(n * DAY_MS).toISOString().slice(0, 10);
}

let dayStart = 0;

/**
 * The hour the phone's day starts: 3 with xrnloops' "Extend the day past midnight" on, else 0.
 * Until then "today" is still the day before, as on the phone (the server passes it along).
 */
export function setDayStartHour(hour) {
  dayStart = hour;
}

export function dayStartHour() {
  return dayStart;
}

export function todayNum() {
  const t = new Date(Date.now() - dayStart * 3600000);
  return Date.UTC(t.getFullYear(), t.getMonth(), t.getDate()) / DAY_MS;
}

/** A Date for formatting a day; UTC fields hold the local calendar date. */
export function dayDate(n) {
  return new Date(n * DAY_MS);
}

function monthLength(n) {
  const d = dayDate(n);
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0)).getUTCDate();
}

export function frequency(habit) {
  const { freqNum: num, freqDen: den } = habit.data;
  return num === den ? [1, 1] : [num, den];
}

/** EntryList.buildIntervals. `entries` is newest first, as [day, value]. */
function buildIntervals(num, den, entries) {
  const filtered = entries.filter(([, v]) => v === YES_MANUAL).map(([d]) => d);
  const intervals = [];
  for (let i = num - 1; i < filtered.length; i++) {
    const begin = filtered[i];
    const center = filtered[i - num + 1];
    let size = den;
    if (den === 30 || den === 31) {
      size = dayDate(begin).getUTCDate() === monthLength(begin) ? monthLength(begin + 1) : monthLength(begin);
    }
    if (center - begin < size) intervals.push([begin, center, begin + size - 1]);
  }
  return intervals;
}

/** EntryList.snapIntervalsTogether, in place. */
function snapIntervalsTogether(intervals) {
  for (let i = 1; i < intervals.length; i++) {
    const [begin, center, end] = intervals[i];
    const nextBegin = intervals[i - 1][0];
    const gapNextToCurrent = end - nextBegin;
    const gapCenterToEnd = end - center;
    if (gapNextToCurrent >= 0) {
      const shift = Math.min(gapCenterToEnd, gapNextToCurrent + 1);
      intervals[i] = [begin - shift, center, end - shift];
    }
  }
}

/**
 * Habit.computedEntries: what the user entered plus Loop's automatic checks for days covered
 * by a met goal ("3 times a week"). `entries` is a Map of day string -> {value}. Returns a Map
 * of day number -> value, without UNKNOWN days.
 */
export function computeEntries(habit, entries) {
  const original = [...entries].map(([d, e]) => [dayNum(d), e.value]).sort((a, b) => b[0] - a[0]);
  const out = new Map();
  if (habit.data.type === NUMERICAL) {
    for (const [d, v] of original) out.set(d, v);
    return out;
  }
  if (!original.length) return out;
  const [num, den] = frequency(habit);
  const intervals = buildIntervals(num, den, original);
  snapIntervalsTogether(intervals);
  for (const [begin, , end] of intervals) for (let d = begin; d <= end; d++) out.set(d, YES_AUTO);
  for (const [d, v] of original) {
    const current = out.get(d) ?? UNKNOWN;
    out.set(d, current === UNKNOWN || v === SKIP || v === YES_MANUAL ? v : YES_AUTO);
  }
  for (const [d, v] of out) if (v === UNKNOWN) out.delete(d);
  return out;
}

function scoreStep(freq, previous, checkmark) {
  const multiplier = Math.pow(0.5, Math.sqrt(freq) / 13);
  return previous * multiplier + checkmark * (1 - multiplier);
}

/** ScoreList.recompute: one score (0..1) per day from `from` to `to`, oldest first. */
export function computeScores(habit, computed, from, to) {
  const { type, targetType, targetValue } = habit.data;
  const numerical = type === NUMERICAL;
  const atMost = targetType === AT_MOST;
  let [num, den] = frequency(habit);
  const freq = num / den;
  if (!numerical && freq < 1) {
    num *= 2;
    den *= 2;
  }
  const values = [];
  for (let d = from; d <= to; d++) values.push(computed.get(d) ?? UNKNOWN);
  const scores = new Array(values.length);
  let rolling = 0;
  let previous = numerical && atMost ? 1 : 0;
  for (let i = 0; i < values.length; i++) {
    const v = values[i];
    if (numerical) {
      rolling += Math.max(0, v);
      if (i - den >= 0) rolling -= Math.max(0, values[i - den]);
      const sum = rolling / 1000;
      if (v !== SKIP) {
        let pct;
        if (!atMost) pct = targetValue > 0 ? Math.min(1, sum / targetValue) : 1;
        else if (targetValue > 0) pct = Math.min(1, Math.max(0, 1 - (sum - targetValue) / targetValue));
        else pct = sum > 0 ? 0 : 1;
        previous = scoreStep(freq, previous, pct);
      }
    } else {
      if (v === YES_MANUAL) rolling += 1;
      if (i - den >= 0 && values[i - den] === YES_MANUAL) rolling -= 1;
      if (v !== SKIP) previous = scoreStep(freq, previous, Math.min(1, rolling / num));
    }
    scores[i] = previous;
  }
  return scores;
}

/** StreakList.recompute: [begin, end] day ranges, newest first. */
export function computeStreaks(habit, computed, from, to) {
  const { type, targetType, targetValue } = habit.data;
  const hit = (v) => {
    if (type !== NUMERICAL) return v > 0;
    return targetType === AT_LEAST ? v / 1000 >= targetValue : v !== UNKNOWN && v / 1000 <= targetValue;
  };
  const days = [];
  for (let d = to; d >= from; d--) if (hit(computed.get(d) ?? UNKNOWN)) days.push(d);
  if (!days.length) return [];
  const streaks = [];
  let begin = days[0], end = days[0];
  for (let i = 1; i < days.length; i++) {
    if (days[i] === begin - 1) begin = days[i];
    else {
      streaks.push([begin, end]);
      begin = end = days[i];
    }
  }
  streaks.push([begin, end]);
  return streaks;
}

/** Everything the screens show about one habit, computed the way Loop's Habit.recompute does. */
export function summarize(habit, entries, today = todayNum()) {
  const computed = computeEntries(habit, entries);
  let from = today;
  for (const d of computed.keys()) if (d < from) from = d;
  const scores = computeScores(habit, computed, from, today);
  const streaks = computeStreaks(habit, computed, from, today);
  const scoreAt = (d) => (d < from ? 0 : scores[d - from] ?? 0);
  const current = streaks.find(([, end]) => end >= today - 1);
  const best = streaks.reduce((b, s) => (s[1] - s[0] > (b ? b[1] - b[0] : -1) ? s : b), null);
  return {
    computed,
    from,
    scores,
    streaks,
    scoreAt,
    score: scoreAt(today),
    currentStreak: current ? current[1] - current[0] + 1 : 0,
    bestStreak: best ? best[1] - best[0] + 1 : 0,
  };
}

/** Loop's toggle order for a tap on a yes/no day (Entry.nextToggleValue). */
export function nextToggleValue(value, skipEnabled = false, questionMarksEnabled = false) {
  switch (value) {
    case YES_AUTO: return YES_MANUAL;
    case YES_MANUAL: return skipEnabled ? SKIP : NO;
    case SKIP: return NO;
    case NO: return questionMarksEnabled ? UNKNOWN : YES_MANUAL;
    default: return YES_MANUAL;
  }
}

/** "Every day", "3 times per week", "Every 3 days", ... as Loop words them. */
export function describeFrequency(habit) {
  const [num, den] = frequency(habit);
  if (habit.data.type === NUMERICAL) {
    const per = den === 1 ? "day" : den === 7 ? "week" : den >= 30 ? "month" : `${den} days`;
    const unit = habit.data.unit ? ` ${habit.data.unit}` : "";
    const bound = habit.data.targetType === AT_MOST ? "At most" : "At least";
    return `${bound} ${formatNumber(habit.data.targetValue)}${unit} per ${per}`;
  }
  if (num === 1 && den === 1) return "Every day";
  if (num === 1 && den === 7) return "Every week";
  if (num === 1 && den >= 30) return "Every month";
  if (den === 7) return `${num} times per week`;
  if (den >= 30) return `${num} times per month`;
  if (num === 1) return `Every ${den} days`;
  return `${num} times in ${den} days`;
}

export function formatNumber(x) {
  return Number.isInteger(x) ? String(x) : x.toFixed(x < 10 ? 2 : 1).replace(/\.?0+$/, "");
}
