# xrnloops: our Loop Habit Tracker fork

This is a modified copy of [Loop Habit Tracker](https://github.com/iSoron/uhabits)
(GPLv3), renamed **xrnloops** and changed on 2026-10-05 so that xrnhabit (`../xrnhabit`,
formerly HabitFirst) can unlock blocked apps when you tick a habit here. The modified version stays
GPLv3. xrnhabit talks to it only over Android IPC and contains no Loop code.

Cloned shallow from upstream `7e993e1` (2.3.1). To rebase onto a newer upstream, run
`git fetch --unshallow` first.

## What changed

| Change | Where |
|---|---|
| App id `com.xern.xrnloops`, launcher name "xrnloops", so it installs next to the store Loop. Loop's Kotlin packages stay `org.isoron.uhabits` so upstream changes still merge | `uhabits-android/build.gradle.kts`, `AndroidManifest.xml`, `res/values/xrnloops.xml` |
| Places that hardcoded `org.isoron.uhabits` now follow the app id: the FileProvider authority, `BackupManager.dataChanged`, and the About screen intent | `AndroidManifest.xml`, `utils/ViewExtensions.kt`, `activities/settings/SettingsFragment.kt`, `res/xml/preferences.xml` |
| Read-only habit provider | `xrnhabit/XrnhabitProvider.kt` |
| "Open this habit" entry point for xrnhabit's block screen | `xrnhabit/OpenHabitActivity.kt`, `AndroidManifest.xml` |
| Optional signing key shared with xrnhabit, read from `../xrn-signing.properties` | `uhabits-android/build.gradle.kts` |
| "Habits changed" broadcast after every command | `xrnhabit/XrnhabitNotifier.kt`, started in `HabitsApplication.onCreate` |
| `build.sh` targets the new app id for instrumented-test runs | `build.sh` (`PACKAGE_NAME`) |
| Upstream fix: the Android unit tests use `kotlinx.coroutines.test` but upstream never declared it, so they didn't compile | `uhabits-android/build.gradle.kts` |
| Upstream fix: "today" went stale when the process outlived midnight in the background (upstream only recomputes it at process start, or at midnight while the habit list is on screen), so the first column was really yesterday and a tick there landed on yesterday's date. Now every screen resume catches up | `xrnhabit/TodayRefresher.kt`, `HabitsApplication.kt`, `uhabits-core/.../utils/MidnightTimer.kt` (`refreshIfDayChanged`, tested in `MidnightTimerTest`) |

## The contract

`xrnhabit/XrnhabitContract.kt` is mirrored by xrnhabit's
`../xrnhabit/app/.../loop/LoopHabits.kt` (`LoopContract`). Change both together.

- **Read:** `content://com.xern.xrnloops.provider/habits?date=YYYY-MM-DD` returns one
  row per habit, with columns `uuid, name, position, archived, numerical, countable, done,
  goal_met, daily`. `done` is 1 when the habit was really done on that date:
  - Yes/no habit: a manual check only. Loop's auto-filled checks and skips don't count.
  - "At least" numerical habit: the target was reached.
  - "At most" habit: never done, because Loop never counts it as completed (`countable = 0`).

  `goal_met` is 1 when it's done, or, for a yes/no habit that isn't every day ("3 times a
  week"), when Loop has filled the day with an automatic check because the goal is met.
  `daily` is 1 for every-day and numerical habits, where `goal_met` equals `done`.
- **Open:** activity action `com.xern.xrnhabit.action.OPEN_HABIT` with extra `uuid` opens that
  habit's page (`OpenHabitActivity`), where today or yesterday can be ticked from the history
  card; it falls back to the habit list if the habit is gone.
- **Push:** broadcast `com.xern.xrnhabit.action.HABITS_CHANGED` to `com.xern.xrnhabit` after every
  command, which covers ticks from the list, the history card, widgets and notifications. It
  carries no data; xrnhabit re-reads the provider.
- **Guard:** both apps declare the signature permission `com.xern.xrnhabit.permission.HABITS`. Only
  apps signed with the same key can read habits, open a habit, or send the signal.

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

Then, in xrnhabit, open a habit block's checklist and tap **Link xrnloops habit**.
