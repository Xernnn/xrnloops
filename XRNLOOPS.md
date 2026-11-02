# xrnloops: our Loop Habit Tracker fork

This is a modified copy of [Loop Habit Tracker](https://github.com/iSoron/uhabits)
(GPLv3), renamed **xrnloops** and changed on 2026-10-05 so that xrnrule (`../xrnrule`,
formerly xrnhabit and HabitFirst) can unlock blocked apps when you tick a habit here. The modified version stays
GPLv3. xrnrule talks to it only over Android IPC and contains no Loop code.

Cloned shallow from upstream `7e993e1` (2.3.1). To rebase onto a newer upstream, run
`git fetch --unshallow` first.

## What changed

| Change | Where |
|---|---|
| App id `com.xern.xrnloops`, launcher name "xrnloops", so it installs next to the store Loop. Loop's Kotlin packages stay `org.isoron.uhabits` so upstream changes still merge | `uhabits-android/build.gradle.kts`, `AndroidManifest.xml`, `res/values/xrnloops.xml` |
| Places that hardcoded `org.isoron.uhabits` now follow the app id: the FileProvider authority, `BackupManager.dataChanged`, and the About screen intent | `AndroidManifest.xml`, `utils/ViewExtensions.kt`, `activities/settings/SettingsFragment.kt`, `res/xml/preferences.xml` |
| Read-only habit provider | `xrnrule/XrnruleProvider.kt` |
| "Open this habit" entry point for xrnrule's block screen | `xrnrule/OpenHabitActivity.kt`, `AndroidManifest.xml` |
| Optional signing key shared with xrnrule, read from `../xrn-signing.properties` | `uhabits-android/build.gradle.kts` |
| "Habits changed" broadcast after every command | `xrnrule/XrnruleNotifier.kt`, started in `HabitsApplication.onCreate` |
| `build.sh` targets the new app id for instrumented-test runs | `build.sh` (`PACKAGE_NAME`) |
| Upstream fix: the Android unit tests use `kotlinx.coroutines.test` but upstream never declared it, so they didn't compile | `uhabits-android/build.gradle.kts` |
| Two-way sync with a server of your own (2026-10-16): every habit and entry, so the web app and the phone show the same thing. Settings → "Sync with home server" (address, user, password, Sync now); the password is kept in its own preferences file, outside the cloud backup. Adds the `INTERNET` permission | `xrnsync/` (`XrnSync`, `XrnSyncApi`, `XrnSyncSettings`, `XrnSyncPreferences`), started in `HabitsApplication.onCreate`; `res/xml/preferences.xml`, `res/values/xrnsync_strings.xml`, `SettingsFragment.kt`, `AndroidManifest.xml` |
| Background sync about every half hour, a sync right before each reminder, a one-off job after each change (in case Android ends the process before it is sent), and live updates from the server while a screen is open | `xrnsync/XrnSyncJob.kt` (in `AndroidManifest.xml`), `XrnSync.beforeReminder`, `XrnSync.waitForChange`, `receivers/ReminderReceiver.kt` |
| `call("sync")` on the provider: xrnrule asks for a sync before it reads; `call("watch")`: it waits for the next change from the server while a block screen is up; `day_start_hour` in each read, so xrnrule reads the right day with "Extend the day past midnight" on | `xrnrule/XrnruleProvider.kt`, `XrnruleContract.kt` |
| The sync server and a web app for ticking and reviewing habits in a browser. They port Loop's auto-check, score and streak rules (`EntryList`, `ScoreList`, `StreakList`) to Python and JavaScript, checked against Loop's own test values | `server/xrnloops-sync.py` (+ `test_xrnloops_sync.py`), `web/` |
| The sync password dialog (2026-11-02) was a framework `android.app.AlertDialog`: on HyperOS it came out as a narrow white box whose field was nearly invisible and didn't take focus. Now the app's Material dialog with a full-width `TextInputLayout`, a show/hide eye button and the keyboard up | `xrnsync/XrnSyncPreferences.kt` |
| Reminders crashed while building their notification (2026-10-16): the `PARENT_ACTIVITY` meta-data named `.activities.habits.list.ListHabitsActivity`, and Android resolves a leading dot in that string against the app id, which is ours, not Loop's package. Now fully qualified | `AndroidManifest.xml` |
| One `EntryRepository` / `HabitRepository` serves every habit, and their statements keep state between bind and step. Upstream reads and writes from one thread at a time; the provider's binder threads and the sync thread made a second one, which crashed the app when xrnrule woke it (a provider read during `onCreate`'s `recompute()`) and could cross two writes. Every repository method now takes the repository's lock, `SQLiteEntryList.loadRecords` its list's, and the provider waits for `onCreate` to finish | `uhabits-core/.../database/EntryRepository.kt`, `HabitRepository.kt`, `models/sqlite/SQLiteEntryList.kt`, `HabitsApplication.isComponentReady` |
| Upstream fix: "today" went stale when the process outlived midnight in the background (upstream only recomputes it at process start, or at midnight while the habit list is on screen), so the first column was really yesterday and a tick there landed on yesterday's date. Now every screen resume catches up | `xrnrule/TodayRefresher.kt`, `HabitsApplication.kt`, `uhabits-core/.../utils/MidnightTimer.kt` (`refreshIfDayChanged`, tested in `MidnightTimerTest`) |

## The contract

`xrnrule/XrnruleContract.kt` is mirrored by xrnrule's
`../xrnrule/app/.../loop/LoopHabits.kt` (`LoopContract`). Change both together.

- **Read:** `content://com.xern.xrnloops.provider/habits?date=YYYY-MM-DD` returns one
  row per habit, with columns `uuid, name, position, archived, numerical, countable, done,
  goal_met, daily`. `done` is 1 when the habit was really done on that date:
  - Yes/no habit: a manual check only. Loop's auto-filled checks and skips don't count.
  - "At least" numerical habit: the target was reached.
  - "At most" habit: never done, because Loop never counts it as completed (`countable = 0`).

  `goal_met` is 1 when it's done, or, for a yes/no habit that isn't every day ("3 times a
  week"), when Loop has filled the day with an automatic check because the goal is met.
  `daily` is 1 for every-day and numerical habits, where `goal_met` equals `done`.
- **Open:** activity action `com.xern.xrnrule.action.OPEN_HABIT` with extra `uuid` opens that
  habit's page (`OpenHabitActivity`), where today or yesterday can be ticked from the history
  card; it falls back to the habit list if the habit is gone.
- **Push:** broadcast `com.xern.xrnrule.action.HABITS_CHANGED` to `com.xern.xrnrule` after every
  command, which covers ticks from the list, the history card, widgets and notifications. It
  carries no data; xrnrule re-reads the provider.
- **Sync first:** `ContentResolver.call(content://com.xern.xrnloops.provider, "sync", …)` runs a
  sync with the home server and waits up to 5 s, then returns `configured` and `synced`
  booleans. Android doesn't apply the provider's permissions to `call`, so it checks the
  calling app itself.
- **Wait:** `call(…, "watch", …)` long-polls the home server for up to 25 s and syncs as soon as
  anything arrives (then the usual broadcast goes out), returning `configured` and `changed`.
  xrnrule loops on it while a block screen shows a linked habit, so a tick in the web app or
  on the laptop opens the block within about a second.
- **Day start:** every read's cursor extras carry `day_start_hour`: 3 with Loop's "Extend the
  day past midnight" on, else 0. Until that hour a tick lands on the day before, so xrnrule
  asks for that date (it works the date out with its own clock, which ignores a hand-set phone
  clock).
- **Guard:** both apps declare the signature permission `com.xern.xrnrule.permission.HABITS`. Only
  apps signed with the same key can read habits, open a habit, or send the signal.

## Sync and the web app

`server/xrnloops-sync.py` keeps every user's habits in one SQLite file. A record is a habit (by
uuid) or an entry (habit + day), each with its writer's wall-clock `mtime`, and the last write
wins per record. Every accepted write takes the next `seq`, so a client asks for "everything
after the seq I last saw" (`GET /api/changes?since=N`, `&wait=S` to long-poll) and sends its
own (`POST /api/changes`). `GET /api/status?date=` answers `done` / `goal_met` / `daily` with
the provider's meaning; xrnrule on the laptop reads ticks from it. The phone also sends the
hour its day starts (`dayStartHour`, from "Extend the day past midnight"); both answers pass
it on, so the web app's "today" and the laptop's reads follow the phone's day. It serves `web/` at its
root. On the home server it runs at `<server>/xrnloops` (port 5234 behind Tailscale Funnel),
with its own htpasswd file (`~/.config/xrn-sync/users`, shared with xrnrule-sync). Ten wrong
passwords from one address within 15 minutes get 429 until they age out.

The phone sends its password (Basic auth) with every request. The web app trades it once for a
cookie (`POST /api/session`; `DELETE` signs out) and keeps nothing but a "signed in" hint in
localStorage: the cookie is HttpOnly, so no script on the host (it also serves the calendar,
XrnTube and xrnrule's status page) can read it, and it counts only with the `X-Xrn-Client`
header, which another site can't send. It lasts 30 days and is signed with `session.key`, made
next to the users file, which xrnrule-sync shares, so one sign-in covers both pages; a new
password ends every session. Every answer carries `X-Frame-Options: DENY`, `nosniff`,
`no-referrer` and a CSP that runs only the web app's own files (index.html's inline script by
hash, worked out at start), and gzip bodies are unpacked only up to the 32 MB limit.

The phone side (`xrnsync/XrnSync.kt`) finds local changes by diffing the habit list against
`files/xrnsync-snapshot.json`, what the server last agreed to. That also catches backup
imports, which bypass `CommandRunner`. It then:

- pushes local changes with a stamp newer than the record it replaces, so a slow phone clock
  can't lose a tick;
- pulls and applies remote ones directly on its one sync thread, then announces them as
  `XrnSync.SyncedFromServer`, so lists, widgets and xrnrule refresh without triggering
  another sync;
- leaves alone any record changed here since the round began (it goes out next time).

Remote positions are ignored: Loop keeps positions dense, and the phone's order is pushed.
The first sync only fills gaps on the server. A sync runs after any command (3 s later, and a
one-off `XrnSyncJob` 5 s later in case Android ends the process first, which leaving the app
right after a tick can do), when the app comes to the front, when xrnrule asks
(`call("sync")`), about every half hour in the background (`XrnSyncJob`, a JobScheduler job
that needs a network, so widgets and the list catch up with the web app), and right before a
reminder shows (`XrnSync.beforeReminder`, up to 8 s), so a habit ticked in the web app doesn't
ring. While one of the app's screens is open, a long-poll (`XrnSync.waitForChange`) brings in
ticks from the web app as they happen.

The web app's **Review** page (`#/review`) shows a week: each habit's days and score trend,
and, from xrnrule-sync's event log on the same server (`/xrnrule/events`, same login),
unlock minutes and blocked app opens per day on the phone and the laptop.

```fish
python3 -m unittest server/test_xrnloops_sync.py     # Loop's rules + the store + HTTP
```

## Build and install

```fish
fish -l -c './gradlew :uhabits-android:assembleDebug'
adb install -r uhabits-android/build/outputs/apk/debug/uhabits-android-debug.apk
```

Needs SDK platform 36. Both apps must be signed with the **same key**. Debug builds on the same
machine share `~/.android/debug.keystore`. With different keys, the second install fails with
`INSTALL_FAILED_DUPLICATE_PERMISSION`. To use a key that doesn't depend on this machine, both
builds read `../xrn-signing.properties` when it exists (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`; `storeFile` relative to that file's folder).

## Moving your habits over from the store Loop

1. In the store Loop: Settings → **Export full backup**.
2. In xrnloops: Settings → **Import data**, and pick that file.
3. Once you've checked everything came across, uninstall the store Loop (or keep it; the two
   apps don't interfere).

Then, in xrnrule, open a habit block's checklist and tap **Link xrnloops habit**.
