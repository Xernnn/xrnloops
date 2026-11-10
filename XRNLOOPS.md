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
| A provider xrnrule calls to ask for a sync (it once served the habits too) | `xrnrule/XrnruleProvider.kt` |
| Optional signing key shared with xrnrule, read from `../xrn-signing.properties` | `uhabits-android/build.gradle.kts` |
| "Habits changed" broadcast after every command | `xrnrule/XrnruleNotifier.kt`, started in `HabitsApplication.onCreate` |
| `build.sh` targets the new app id for instrumented-test runs | `build.sh` (`PACKAGE_NAME`) |
| Upstream fix: the Android unit tests use `kotlinx.coroutines.test` but upstream never declared it, so they didn't compile | `uhabits-android/build.gradle.kts` |
| Two-way sync with a server of your own (2026-10-16): every habit and entry, so the web app and the phone show the same thing. Settings → "Sync with home server" (address, user, password, Sync now); the password is kept in its own preferences file, outside the cloud backup. Adds the `INTERNET` permission | `xrnsync/` (`XrnSync`, `XrnSyncApi`, `XrnSyncSettings`, `XrnSyncPreferences`), started in `HabitsApplication.onCreate`; `res/xml/preferences.xml`, `res/values/xrnsync_strings.xml`, `SettingsFragment.kt`, `AndroidManifest.xml` |
| Background sync about every half hour, a sync right before each reminder, a one-off job after each change (in case Android ends the process before it is sent), and live updates from the server while a screen is open | `xrnsync/XrnSyncJob.kt` (in `AndroidManifest.xml`), `XrnSync.beforeReminder`, `XrnSync.waitForChange`, `receivers/ReminderReceiver.kt` |
| `call("sync")` on the provider: xrnrule has this app send its changes before it reads the server | `xrnrule/XrnruleProvider.kt`, `XrnruleContract.kt` |
| The sync server and a web app for ticking and reviewing habits in a browser. The web app ports Loop's auto-check, score and streak rules (`EntryList`, `ScoreList`, `StreakList`) to JavaScript | `server/xrnloops-sync.py` (+ `test_xrnloops_sync.py`), `web/` |
| Its own launcher icon (2026-11-03): the xrn family tile, green crossed by the hand-drawn x with a white loop arrow, so it isn't confused with the store Loop's blue icon on a launcher without labels | `res/mipmap-anydpi-v26/ic_launcher.xml`, `res/drawable/xrnloops_icon_*.xml`, `web/icon.svg` |
| Its own notification icon and tab icon (2026-11-06): the status bar shows our loop arrow, not Loop's old mark, and the web app's tab uses art drawn for 16 px, with a thinner x, so the x stays readable | `uhabits-android/src/main/res/drawable/ic_notification.xml`, `web/favicon.svg`, `web/index.html` |
| Our own welcome screen (2026-11-03) in place of Loop's three intro slides, in the xrn family look (xrnrule's dark palette, MiSans): the icon drawing itself, the loop with xrnrule (blocked, done, open), bringing habits over (home server sign-in with a first sync, or a Loop backup) and the day's end plus reminders. Its cards show live state (xrnrule installed, last sync, the 3 AM day, notifications), and the habit list's menu reopens it ("Welcome & setup"). Loop's `IntroActivity` stays in the tree, unused | `xrnwelcome/WelcomeActivity.kt`, `res/layout/xrnwelcome_*.xml`, `res/drawable/xrnwelcome_*.xml`, `xrn_card.xml`, `xrn_ic_*.xml`, `res/values/xrn_theme.xml`, `res/values/xrnwelcome_strings.xml`, `res/font/xrn_misans_*.ttf`; `ListHabitsScreen.showIntroScreen`, `ListHabitsMenu.kt`, `res/menu/list_habits.xml`, `AndroidManifest.xml` |
| The xrn look across the whole app (2026-11-03), not just the welcome screen: xrnrule's zinc greys and green, MiSans for every text including what Loop draws on a canvas (charts, the day header), rounded cards with a hairline edge, the list's rows as rounded tiles, and a zinc toolbar. Light, dark and pure black each get their own version (on pure black, cards and list rows keep a hairline edge); habit colours stay Loop's. Widgets use the same card colour | `res/values/xrn_app_theme.xml` (`XrnAppTheme`, `XrnAppThemeDark`, `.PureBlack`, `XrnWidgetTheme`), `xrnlook/XrnThemes.kt` (canvas colours, `XrnFonts`), `res/font/xrn_misans.xml`, `res/drawable/xrn_card_*.xml`, `xrn_row*.xml`; `AndroidManifest.xml` (app theme), `activities/AndroidThemeSwitcher.kt`, `platform/gui/AndroidCanvas.kt`, `list/views/HeaderView.kt`, `list/views/HabitCardView.kt` (row background), `widgets/BaseWidgetProvider.kt`, `res/values/styles.xml` (`Card` background) |
| What each habit unlocks (2026-11-03, reworked 2026-11-05 and 2026-11-06): right after a habit's name in the list, a small lock and no words, drawn like the row's own marks: the Font Awesome set a day's ✓ comes from, at its size, in the habit's colour, with nothing behind it. Its shape says how the most useful block stands: closed while a block waits on this habit, a bolt when an unlock is earned and only has to be opened, open once the block is open, and closed in the grey of an empty day once the habit has done its part and the block wants more. A tap on it brings up a sheet with every block that uses the habit, each with its name, how it stands ("Locked · unlocks 30 min", "Earned · 30 min to open", "Open till 9:35 PM", "Open · 12 min left") and what it holds back; only a tap on a block goes on to xrnrule. Read from xrnrule (see the contract below), again on every screen resume and when xrnrule says a block changed. Nothing shows for habits no block uses, or without xrnrule | `xrnrule/XrnruleLinks.kt` (started in `HabitsApplication.onCreate`; `ordered`), `xrnrule/XrnruleLinkBadge.kt`, `xrnrule/XrnruleLinkSheet.kt`, `res/layout/xrnlink_sheet.xml`, `res/layout/xrnlink_row.xml`, `res/values/xrnlink_strings.xml`, `res/values/xrn_app_theme.xml` (the sheets' handle and round corners, which the note sheet shares), `res/drawable/xrn_ic_lock*.xml`, `res/drawable/xrn_ic_open_in_new.xml`; `list/views/HabitCardView.kt` (the lock after the name) |
| Tap and flick on a day (2026-11-04). What a tap does is a setting, "Tap on a day" (ticks it, cycles like Loop, or opens the day's note), in place of "Toggle with short press", which it keeps in step for the habit page's calendar. A short hold (170 ms; Android's long press is 400 ms or more) opens a flick menu on the day, as on a Japanese phone keyboard: slide up for ✓, down for ✗, left for – (skip), right for the day's note, and let go; letting go in the middle clears the day to ?, unless it comes within 300 ms of touching down (a slow tap). Yes/no habits only; number habits keep their dialog | `xrntap/` (`XrnTap`, `FlickGesture`, `CheckmarkFlick`), `res/values/xrntap_strings.xml`, `res/drawable/xrn_ic_note.xml`; `list/views/CheckmarkButtonView.kt` (touch, `onClick`, `onLongClick`), `res/xml/preferences.xml`, `activities/settings/SettingsFragment.kt`, `androidTest/.../EntryButtonViewTest.kt` |
| A day's note screen (2026-11-04) in place of Loop's checkmark popup, everywhere it showed (the list, the habit page's calendar editor, a reminder's edit): a sheet from the bottom with the habit's name and the day, Done / Not done / Skip / Clear as big buttons, a multi-line note and Save. Picking a value saves it with the note; closing the sheet any other way keeps a changed note. `CheckmarkDialog` and `checkmark_popup.xml` stay in the tree, unused. To name the habit and the day, both screens' `showCheckmarkPopup` now also get the habit's name and the date | `xrnnote/NoteSheet.kt`, `res/layout/xrnnote_sheet.xml`, `res/values/xrnnote_strings.xml`, `XrnNoteSheet` in `res/values/xrn_app_theme.xml`; `list/ListHabitsScreen.kt`, `show/ShowHabitActivity.kt`, `uhabits-core/.../list/ListHabitsBehavior.kt`, `.../show/views/HistoryCard.kt` |
| What a tick earned (2026-11-05): when xrnrule earns, opens or moves a block one part nearer because of a day just entered here (within two minutes), the screen in front says so in a banner, "Earned 30 min of Social", "Video is open till 14:30", "Counted towards Video · it needs more", with an Open button. The notifier notes which habit each tick was for; the banner comes from comparing xrnrule's links before and after its next `LINKS_CHANGED` | `xrnrule/XrnruleBanner.kt`, `xrnrule/XrnruleLinks.kt` (`earned`, `openBlock`), `xrnrule/XrnruleNotifier.kt`, `xrnlook/XrnSnackbar.kt`, `res/values/xrnlink_strings.xml`; `src/test/.../xrnrule/XrnruleLinksTest.kt` |
| Flick on a number day (2026-11-05), laid out like the yes/no one: up the target ("8"), down 0, left – (skip), right the day's sheet for any other amount and the note, the middle clears to ?. A tap still opens the dialog. Keys whose amount meets the target wear the habit's colour | `xrntap/NumberFlick.kt`, `res/drawable/xrn_ic_dialpad.xml`; `list/views/NumberButtonView.kt` (touch, `onToggle`), `list/views/NumberPanelView.kt` (`onToggle`), `list/views/HabitCardView.kt` |
| Sync trouble in the list's toolbar (2026-11-05): an amber cloud while sync is failing, two failures in a row or one that needs you (a wrong password, or the lockout after too many). A tap says what went wrong and when, with Retry, or Settings for the password. A single dropped connection shows nothing. The last attempt now also records failures in a row and whether it needs the user; a 429 reads as the lockout it is | `xrnsync/XrnSyncTrouble.kt`, `xrnsync/XrnSyncSettings.kt`, `xrnsync/XrnSync.kt` (`runSync`), `res/drawable/xrn_ic_cloud_off.xml`, `res/values/xrnsync_strings.xml`; `list/ListHabitsMenu.kt`, `res/menu/list_habits.xml` |
| Instrumented tests pass again (2026-11-05): the screenshots are redrawn for the xrn look and 7 days (rendered on the `uhabitsTest35` emulator: API 35 Google APIs, Nexus 4), and the tests use the app id instead of `org.isoron.uhabits` (starting activities, waiting for the app, the backup folder, widget ids). Ticking in the list's UI tests calls Loop's toggle, since touch opens the flick menu and a long press opens the day's sheet. The backup import picks Downloads in the file picker's menu itself (2026-11-05): the picker reopens the folder it showed last, and there that folder's own title matched first, so the tap landed on "Recent". 72 medium and 19 large tests pass | `uhabits-android/src/androidTest/assets/views/`, `androidTest/.../BaseUserInterfaceTest.kt`, `acceptance/steps/CommonSteps.kt`, `acceptance/steps/WidgetSteps.kt`, `acceptance/steps/BackupSteps.kt`, `acceptance/steps/ListHabitsSteps.kt`, `list/views/EntryPanelViewTest.kt`, `docs/TEST.md` |
| CI on every push (2026-11-05): the JVM unit tests, ktlint, a compile of the instrumented tests, and the sync server's tests, on GitHub's runners. The emulator tests stay local. `gradle/actions` stays on v5, the last under MIT alone: v6 moves its caching into a proprietary component with terms of its own, which upgrading accepts | `.github/workflows/tests.yml` |
| MiSans in place of Nunito (2026-11-05), as xrnrule switched: the same three files xrnrule ships (Xiaomi's MiSans Latin, Regular, Semibold and Bold; the download came without a licence file to copy). Semibold is the family's bold and Bold its extra bold, the weights Nunito's Bold and ExtraBold had, so headings keep their weight. The screenshots are redrawn | `res/font/xrn_misans.xml`, `res/font/xrn_misans_*.ttf`; `xrnlook/XrnThemes.kt` (`XrnFonts`), `res/values/xrn_theme.xml`, `res/values/xrn_app_theme.xml`, `platform/gui/AndroidCanvas.kt`, `list/views/HeaderView.kt`, `xrnwelcome/WelcomeActivity.kt`; `src/androidTest/assets/views/` |
| The web app works and looks like the phone (2026-11-05), in xrnrule's look (its laptop page's greys, green and MiSans): the list as the phone's rows with as many days as the window holds (a laptop shows four or five weeks, gaps between weeks, month names), score and streak columns, the same day glyphs, the same lock after each name with the blocks in a menu behind it, the earned banner, the amber sync cloud, and the day as on the phone: a click does what "Tap on a day" says, a short hold opens the flick keys (a mouse that drags opens them at once; right-click leaves them open, arrows and Enter pick), and a click on a number day or the note key opens the day's sheet. The habit page is two columns of cards: score with ranges, a year's calendar you can tick, best streaks, frequency by weekday, notes. Light, dark and pure black, and the phone's settings that apply, are kept per browser. A write that can't go out is retried every 10 s, not as fast as it fails | `web/app.js`, `web/style.css`, `web/icons.js` (made by `tools/make_web_icons.py`), `web/fonts/` (MiSans Latin and Geist Mono for the wordmark, from xrnrule's desktop app; Geist Mono is SIL OFL 1.1, `GeistMono-OFL.txt`), `web/index.html`, `web/manifest.webmanifest`; `server/xrnloops-sync.py` (serves `.woff2` as `font/woff2`) |
| xrnrule's finish everywhere (2026-11-05). Bars seamless with the page, with round outlined buttons of one width, the last in line with the rows, and the list's title as a wordmark in Geist Mono Bold with the loop glyph beside it (`xrn_bar_mark`; SIL OFL 1.1, `web/fonts/GeistMono-OFL.txt`). Rows 16dp and cards 24dp with a 1dp edge, no shadows. Dialogs and popup menus are cards (`xrn_dialog`, `xrn_popup`; the vendored `TimePickerDialog` is clipped round in code, and `android:itemBackground` goes transparent or Loop's solid item colour covers the card). Forms keep their labels inside the box (`xrn_form_box`), settings are grouped cards (`xrnlook/XrnSettingsCards`). The light theme stops colouring the bar with the page's colour. |
| The words and the clock for how a block stands are xrnrule's (2026-11-05): "Ready to open · 30 min", "Open until 19:41" or "Open until Fri 00:30", "Open · 12 min of use left". The web app says the same. |
| About (2026-11-05) names xrnloops, with our icon and "Based on Loop Habit Tracker, GPLv3". Loop's credits stay. The rows that would send a user of this fork to Loop's author, store listing, translation site or privacy policy are gone, with their code since 2026-11-06, and Settings loses "Rate this app". |
| Upstream fix: a gap under a random row of the list (2026-11-05). The nav-bar inset was an item offset on the last row, offsets are cached per row, and the list loads bit by bit, so a row that was last for a moment kept 24dp under it (about 1 launch in 6). It's padding on the list now. |
| A note saved on its own keeps the day as it is now (2026-11-05), on the phone and the web: sync may have changed the day while the sheet was open, and saving wrote the old value back over it. A value picked in the sheet still wins. |
| A number day opens the same sheet as a yes/no day (2026-11-06), in place of Loop's number box, which named neither the habit nor the day: the amount and its unit, keys for the day's target, 0, skip and clear, the note and Save, as on the web. An empty box means no amount, so Save leaves a skipped day skipped, on both, and doesn't turn an empty day into a 0. An amount keeps its third decimal (Loop's box showed two, and Save wrote that back). A note on its own keeps the day as it is now, as on a yes/no day. On the web, until 2026-11-10, Save or closing the sheet wrote back the amount it opened with, over one the phone entered meanwhile; an amount nobody typed now leaves the day as it is, and Save with nothing changed writes nothing. |
| Upstream fix: "completed today" is the day's share of a number habit's target (2026-11-06), as the list shows it: 70 a week is met by 10. It wanted the whole 70 in one day, so a weekly number habit stayed in the list under Hide completed, sorted as undone, still reminded you and stayed off on its widget. Reaching the share also gets the confetti now. |
| The day's target key enters the share rounded towards meeting it (2026-11-10): up for "at least", down for "at most", on the flick, the sheet and the web. 5 a week is 0.714285… a day; rounded to 714 it fell short, so the day never completed, its reminder still rang and xrnrule (which counts 715) didn't unlock. Also 4, 6, 12 and 20 a week and 10 or 100 a month. After a habit was edited (here or by sync), the list's flick entered the whole target as one day's. | `uhabits-core` `models/DayTarget.kt` (`dayTargetEntry`, `DayTargetTest`), `xrntap/NumberFlick.kt`, `xrnnote/NoteSheet.kt`, `list/views/HabitCardView.kt`, `web/loop.js`, `web/app.js` |
| The rest of the xrn look (2026-11-06). Every message goes through `XrnSnackbar`, and Loop's "Did you know?" hint uses the same banner (it was an indigo strip with its last line on the gesture bar). The selection bar is our bar too (`actionModeTheme`, `XrnActionMode`): its edit and colour icons were white on white in the light theme, its back and overflow icons at 60%, and it was grey in the black theme, which now takes the rest of the dark theme's colours. AppCompat's strip behind the status bar goes transparent. The snooze and widget pickers are cards (`BaseDialog`), the clock is one card with the dial on it, the calendar's Edit is readable, and text on a habit's colour turns dark at the luminance where that reads better. |
| A week in the habit list (2026-11-04): 7 days across a phone instead of Loop's 5. Each day is 38dp wide (Loop: 48dp) and the name keeps at least 140dp (Loop: 160dp); wider screens still get more days, as in Loop | `res/values/dimens.xml` (`checkmarkWidth`, `habitNameWidth`) |
| The sync password dialog (2026-11-02) was a framework `android.app.AlertDialog`: on HyperOS it came out as a narrow white box whose field was nearly invisible and didn't take focus. Now the app's Material dialog with a full-width `TextInputLayout`, a show/hide eye button and the keyboard up | `xrnsync/XrnSyncPreferences.kt` |
| Reminders crashed while building their notification (2026-10-16): the `PARENT_ACTIVITY` meta-data named `.activities.habits.list.ListHabitsActivity`, and Android resolves a leading dot in that string against the app id, which is ours, not Loop's package. Now fully qualified | `AndroidManifest.xml` |
| One `EntryRepository` / `HabitRepository` serves every habit, and their statements keep state between bind and step. Upstream reads and writes from one thread at a time; the provider's binder threads and the sync thread made a second one, which crashed the app when xrnrule woke it (a provider read during `onCreate`'s `recompute()`) and could cross two writes. Every repository method now takes the repository's lock, `SQLiteEntryList.loadRecords` its list's, and the provider waits for `onCreate` to finish | `uhabits-core/.../database/EntryRepository.kt`, `HabitRepository.kt`, `models/sqlite/SQLiteEntryList.kt`, `HabitsApplication.isComponentReady` |
| Upstream fix: "today" went stale when the process outlived midnight in the background (upstream only recomputes it at process start, or at midnight while the habit list is on screen), so the first column was really yesterday and a tick there landed on yesterday's date. Now every screen resume catches up | `xrnrule/TodayRefresher.kt`, `HabitsApplication.kt`, `uhabits-core/.../utils/MidnightTimer.kt` (`refreshIfDayChanged`, tested in `MidnightTimerTest`) |
| Removed what nothing calls (2026-11-06), once xrnrule's source, its build on the phone, the laptop's installed app and the server's log all agreed: the provider's `query` and `watch`, `OpenHabitActivity`, and `/api/status` with the server's port of Loop's auto-checks, which only it used. The server's log shows the last successful call on 2026-10-19. The provider keeps `call("sync")` | `xrnrule/XrnruleProvider.kt`, `xrnrule/XrnruleContract.kt`, `AndroidManifest.xml`, `server/xrnloops-sync.py` |
| Dependencies nothing imports (2026-11-06): ktor, Jackson, Guava, OpenCSV and Apache Commons. ktor's Jackson client alone brought `kotlin-reflect`, whose own keep rules held some 27,000 methods in the app, more than Loop's own code. R8's optimiser and resource shrinking are on for release builds; NotoSans and a second FontAwesome, which only the JVM canvas uses, stay out of the APK. `-keep class org.isoron.**` stays: the manifest and `preferences.xml` name classes in plain strings R8 can't see | `uhabits-android/build.gradle.kts`, `uhabits-core/build.gradle.kts`, `gradle/libs.versions.toml` |
| Typing isn't lost on the way out (2026-11-06). Back or the toolbar's arrow on a changed habit form asks "Discard your changes?", and an unchanged form leaves at once: `EditHabitActivity` compares what Save would write with the form as it opened. The web's form asks too, on Cancel, on the browser's Back and on closing the tab. Leaving a number sheet any way but Save keeps a typed amount as it already kept a note, on the phone (`NoteSheet`) and the web (`numberSheet`); a box left blank leaves the day alone, since only Save or Clear empties one. | `EditHabitActivity`, `xrnnote/NoteSheet`, `web/app.js` |
| Loop's outside links (2026-11-06): "Generate bug report" emailed Loop's author and Help & FAQ opened Loop's site, so both are gone from the list menu and Settings, with the code behind them (`onSendBugReport`, `BugReporter`, `ListHabitsModule`, `onViewFAQ`, `showFAQScreen`, `showSendEmailScreen`). A crash still writes its log to the phone (`AndroidBugReporter`). | `ListHabitsMenu`, `ListHabitsScreen`, `preferences.xml`, `ListHabitsBehavior`, `ListHabitsMenuBehavior` |
| Sync trouble on the web clears on the first good answer (2026-11-06): while sync is failing the poll isn't held open, so Retry shows its result at once rather than up to 25 s later. | `web/app.js` |
| Card content keeps off the edge (2026-11-06). Loop's 4dp of right padding left content 2dp from the rounded card's edge, so a chart's square corner poked past the curve and the longest streak's end date touched the border. Cards now have 8dp on the right, and Best streaks has 16dp, matching its left. | `values/styles.xml`, `layout/show_habit.xml` |
| The developer section's Sync server, Sync key and Encryption key boxes are gone (2026-11-06): nothing read them, and the first held Loop's address. | `xml/preferences.xml`, `values/constants.xml` |
| Import data's description no longer says "See FAQ for more information" (2026-11-06), as the FAQ link is gone. English only: the translations keep their line, so upstream's translation updates still merge. | `values/strings.xml` |
| A sync costs less (2026-11-06). Entries from the server go in with one transaction per habit (`EntryRepository.replaceAll`, `EntryList.addAll`), not two commits each, and a round that changed nothing leaves the snapshot file alone. Nothing inside that transaction takes another lock, so it can't deadlock with a thread waiting for the database while holding one | `xrnsync/XrnSync.kt`, `uhabits-core` `EntryRepository`, `EntryList`, `SQLiteEntryList` |
| An import is no longer one long transaction (2026-11-10). Android lends its one connection to the thread that began a transaction until it ends, and the import took the habit list's and the repositories' locks as it went, which every other thread holds while it waits for that connection: sync applying the server's changes, a tick or a screen loading a habit's days during an import deadlocked with it and could freeze the app. Each importer writes a habit's days in one go (`EntryList.addAll`), so an import stays quick, and sync holds off while one runs. A new habit's id is read in its insert's transaction: `last_insert_rowid()` is the connection's, and a day inserted by another thread in between gave the habit that row's id | `tasks/ImportDataTask.kt`, `ImportDataTaskFactory.kt`; `uhabits-core` `io/*Importer.kt`, `database/HabitRepository.kt` (`ImportWhileWritingTest`, `NewHabitIdTest`, with `OneConnection`, a database that lends its connection as Android's does) |
| The web app comes from the browser's cache when it can (2026-11-06): fonts are kept, everything else revalidates with an `ETag`, and woff2 isn't gzipped again on every request | `server/xrnloops-sync.py` |

## The contract

xrnrule works out what's done from the sync server's entries with its own port of Loop's rules
(`domain/.../LoopRules.kt`), and reads nothing from this app but the signals below. "Done" there
means the user really did it that day:

- Yes/no habit: a manual check only. Loop's auto-filled checks and skips don't count.
- "At least" numerical habit: the day's share of the target was reached, as the list shows it:
  70 a week is met by 10 today. A skip, or a target of 0, is never done.
- "At most" habit: never done, because Loop never counts it as completed.

`xrnrule/XrnruleContract.kt` is mirrored by xrnrule's
`../xrnrule/app/.../loop/LoopHabits.kt` (`LoopContract`). Change both together.

- **Push:** broadcast `com.xern.xrnrule.action.HABITS_CHANGED` to `com.xern.xrnrule` after every
  command, which covers ticks from the list, the history card, widgets and notifications. It
  carries no data; xrnrule asks for a sync, then reads the server.
- **Sync first:** `ContentResolver.call(content://com.xern.xrnloops.provider, "sync", …)` runs a
  sync with the home server and waits up to 5 s, then returns `configured` and `synced`
  booleans. Android doesn't apply the provider's permissions to `call`, so it checks the
  calling app itself.
- **Links (the other way):** xrnrule answers `content://com.xern.xrnrule.links/links` with one
  row per habit item of every enabled habit block: `habit_uuid, block_id, block_name, targets,
  unlock_minutes, state, open_until, habit_done, self_paced, use_left_ms`. `state` is `locked`,
  `ready` (earned, not yet opened) or `open` (`open_until` is then the wall-clock ms it closes).
  With `self_paced` = 1 an unlock is so much use rather than time and ends when it's used up or
  at midnight: `open_until` is 0, and an open one's `use_left_ms` says what's left. It broadcasts
  `com.xern.xrnloops.action.LINKS_CHANGED` to us whenever a row would change, with no data; we
  read again. `xrnrule://open?block=<block_id>` (a `VIEW` intent for `com.xern.xrnrule`) opens
  xrnrule on that block; an id it doesn't know opens its Blocks tab.
  `xrnrule/XrnruleLinks.kt` (`XrnruleLinksContract`) mirrors xrnrule's
  `../xrnrule/app/.../loop/LoopLinks.kt` (`LinksContract`). Change both together.
- **Guard:** both apps declare the signature permission `com.xern.xrnrule.permission.HABITS`. Only
  apps signed with the same key can ask for a sync, read the links, or send either signal.

## Sync and the web app

`server/xrnloops-sync.py` keeps every user's habits in one SQLite file. A record is a habit (by
uuid) or an entry (habit + day), each with its writer's wall-clock `mtime`, and the last write
wins per record. Every accepted write takes the next `seq`, so a client asks for "everything
after the seq I last saw" (`GET /api/changes?since=N`, `&wait=S` to long-poll) and sends its
own (`POST /api/changes`); xrnrule reads the same feed. The phone also sends the hour its day
starts (`dayStartHour`, from "Extend the day past midnight"), and the feed passes it on, so the
web app's "today" and xrnrule's follow the phone's day. It serves `web/` at its root: a font is
kept by the browser, anything else is asked after with its `ETag`, so a deploy shows at once and
a repeat visit costs a few 304s instead of every file. The data is never cached. On the home server it runs at `<server>/xrnloops` (port 5234 behind Tailscale Funnel),
with its own htpasswd file (`~/.config/xrn-sync/users`, shared with xrnrule-sync). Ten wrong
passwords from one address within 15 minutes get 429 until they age out. A password counts as
wrong until bcrypt has found it right, so guesses sent all at once can't get past the ten
(until 2026-11-10 they were counted after the check, and every guess in flight got through).

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

- pushes local changes stamped with when they were made, noted as each command finishes
  (`xrnsync/XrnSyncEdits.kt`, `files/xrnsync-edits.txt`, kept until the server has them), and
  past the record each replaces, so a slow phone clock can't lose a tick while a tick made
  offline still loses to a later change made elsewhere. What an import brings in is stamped
  older than anything the server has: it fills gaps and replaces nothing. Until 2026-11-10 a
  change carried the time it was sent, so an offline tick or an old backup beat newer edits;
- pulls and applies remote ones directly on its one sync thread, then announces them as
  `XrnSync.SyncedFromServer`, so lists, widgets and xrnrule refresh without triggering
  another sync;
- leaves alone any record changed here since the round began (it goes out next time).

A round is built to survive what actually goes wrong (2026-11-05, each staged on an emulator
against the real server):

- A pull comes in pages by `seq`, so an entry can arrive before its habit. It waits, and the
  saved cursor stays behind it, so a stop halfway doesn't lose it.
- The snapshot belongs to one server and account. Another one starts over as after a restore
  (below), not "you deleted everything". A habit the snapshot has and the database doesn't is a deletion only if
  it was deleted here: noted as it happens (`XrnSyncEdits`), or once in this process's database.
  Otherwise the database was lost or replaced (a restored phone, a new empty one): it isn't
  tombstoned, and the feed is read from the start, so it comes back down. Until 2026-11-10 the
  test was "an empty database under a full snapshot", so deleting your last habits brought them
  back, and a habit made in a new database before its first sync deleted all the others;
- A server restored from a backup gets a new epoch; every client starts over from `since=0`.
  The phone sends everything again, each record with the stamp it was agreed with (or, changed
  here since, when it changed) and every deletion it knows of (the snapshot keeps them), so the
  restored server's older copies lose and deleted habits stay deleted. A write carries the epoch
  and how far its writer read (`epoch`, `since`; the phone and the web app): one from a history
  the server no longer has gets 409 and isn't taken, since taking it would move `seq` past what
  clients have read and hide the restore from all of them. Until 2026-11-10 starting over pushed
  a blank snapshot with `ifAbsent`, so the server's older copy beat newer phone edits and
  deleted habits came back; an unreadable snapshot did the same.
- A push of a new order only is stamped just past the record's own `mtime`, so reordering on the
  phone can't undo a rename made on the web.
- Recompute, resort and the announcement run even if the round failed partway, and a remote
  change to today's entry, or a deletion, clears that habit's notification.

Remote positions are ignored: Loop keeps positions dense, and the phone's order is pushed.
A first sync, or one whose snapshot is unreadable, sends what has no known age so that it only
fills gaps; a change noted here keeps its stamp. A sync runs after any command (3 s later, and a
one-off `XrnSyncJob` 5 s later in case Android ends the process first, which leaving the app
right after a tick can do), when the app comes to the front, when xrnrule asks
(`call("sync")`), about every half hour in the background (`XrnSyncJob`, a JobScheduler job
that needs a network, so widgets and the list catch up with the web app), and right before a
reminder shows (`XrnSync.beforeReminder`, up to 8 s), so a habit ticked in the web app doesn't
ring. While one of the app's screens is open, a long-poll (`XrnSync.waitForChange`) brings in
ticks from the web app as they happen. After a failed poll it stays away from the server
(2026-11-03): 15 s doubling to 5 minutes when the server can't be reached, 15 minutes after a
429, and 30 minutes after a wrong password, cut short as soon as the sync settings change. It
used to retry only after failures quicker than 2 s, and the server holds a wrong-password answer
for 2 s, so a phone with an old password sent 246 requests in 12 minutes. The web app's live
updates back off from 5 s to a minute the same way.

### The web app

`web/` is the phone's list and habit page for a browser, with no build step. `loop.js` holds
Loop's rules; `app.js` draws the screens the way the phone's xrn code does, so each piece names
its phone counterpart in a comment: the day glyphs are Loop's Font Awesome shapes (`icons.js`),
the colours are Loop's palettes as `xrnlook/XrnThemes.kt` sets them, a day's input is
`xrntap/` (tap setting, 170 ms hold, the four-way keys, `CheckmarkFlick` and `NumberFlick`'s
choices), its sheet is `xrnnote/NoteSheet.kt`, and the banner and sync cloud follow
`XrnruleBanner` and `XrnSyncTrouble`, and the lock and its menu `XrnruleLinkBadge` and
`XrnruleLinkSheet`. The locks come from xrnrule-sync's shared state
(`/xrnrule/state`, same login, long-polled with `If-None-Match`), worked out as xrnrule's
`LoopLinks.rows` does, since the phone's links provider isn't reachable from a browser. The
settings menu (tap, today's side, theme) is kept in localStorage per browser; a small inline
script in `index.html` sets the theme before the first paint.

The web app's **Review** page (`#/review`) shows a week: each habit's days and score trend,
and, from xrnrule-sync's event log on the same server (`/xrnrule/events`, same login),
unlock minutes and blocked app opens per day on the phone and the laptop.

```fish
python3 -m unittest server/test_xrnloops_sync.py     # the store + HTTP
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
