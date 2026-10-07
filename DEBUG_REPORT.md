# Debug & hardening report — Budget (com.gh00ul.budget)

Started 2026-10-06 from `dc742a7` (v3.2.0 + bank-sync fixes). One commit per phase on `main`.

## Summary checklist

_(Filled in at the end — see Phase 8.)_

---

## Phase 1 — Project map

Found by: Architecture Agent, Dependency Agent, Data Layer Agent (read-only, in parallel), merged by the lead.

### Shape of the app

| | |
|---|---|
| Modules | One Gradle module at the repo root. No wrapper (CI uses Gradle 8.11.1 via `gradle/actions/setup-gradle`). |
| Toolchain | AGP 8.7.3, Kotlin 2.0.21, JDK 17 target. compileSdk/targetSdk 35, minSdk 26. |
| Dependencies | None besides the Kotlin stdlib (framework `android.app.Activity`, views, `org.json`). No AndroidX, Compose, Room, DataStore, coroutines, DI, WorkManager. |
| Release build | Not minified (no R8), signed in CI from `KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`. `versionCode` = major·10000 + minor·100 + patch from the tag. |
| Source | `MainActivity.kt` (2808 lines: all UI + money math + bank-sync glue + update flow + `InstallReceiver`), `BankSync.kt` (HTTP client, `BankStore` prefs, keystore), `BalanceChart.kt` (custom view). |

### Entry points

| Component | Exported | How it's reached |
|---|---|---|
| `MainActivity` | yes (launcher) | MAIN/LAUNCHER; static shortcuts `UPDATE_BALANCE`, `ADD_BILL` (`res/xml/shortcuts.xml`) → `handleShortcut` from `onCreate` (fresh start only) and `onNewIntent`. Shortcuts only open dialogs and read no extras. |
| `InstallReceiver` | no | Explicit `PendingIntent` handed to `PackageInstaller.Session.commit` (FLAG_MUTABLE is required so the installer can add status extras). |

No services, workers, alarms, widgets, providers, notifications or runtime permissions. Permissions: `INTERNET`, `REQUEST_INSTALL_PACKAGES`.

### Screens and flows

Three tabs (Summary, Paydays, Bills) inside one activity, plus a slide-up bill sheet (full-screen `Dialog`) and ~20 `AlertDialog`s:

- **First run**: setup card (pay → bills or "no regular bills" → bank balance).
- **Summary**: Safe-to-spend hero → "How we got $X" (full math, purchases, change paycheck); bank balance row (manual entry, or bank status when linked); log a purchase / this week's purchases; glance rows; Settings; update banner.
- **Paydays**: pay row, forecast rows (expand → change paycheck / update balance), end-of-month row, balance chart.
- **Bills**: sections, tap to edit, swipe right = mark paid, swipe left = delete (+ Undo).
- **Bill sheet**: add/edit, repeats, next due (date picker), mark paid, unmark, delete.
- **Balance follow-ups**: "Has today's pay landed?", "Did <bills> come out today?", "Did you spend $X?", "Is it already out of your balance?".
- **Settings**: pay; connect bank (URL + access key → account picker) or bank menu (sync now / different account / disconnect); check for updates.
- **Bank**: status dialog, this week's purchases, change how a transaction counts.
- **Update**: GitHub release check → download into a `PackageInstaller` session → system confirm prompt.
- **Undo bar**: after balance, pay, bill add/save, paid/unpaid, delete (deletes survive rotation).

### State and lifecycle

- **Persisted** (survives rotation and process death): everything in SharedPreferences `budget` (backed up) and `bank` (excluded from backup; access key AES-256-GCM encrypted with an AndroidKeyStore key). Every change calls `save()` → `apply()` on the main thread. Bundle keeps the tab and pending deletes.
- **Lost on recreation** (rotation, dark mode, font size; there is no `configChanges`): any open dialog/sheet and its typed input, non-delete Undo, expanded payday rows, and the in-flight state of a bank sync or update download.
- **Background work**: four raw `thread {}` blocks (bank sync, bank connect, update check, APK download) that post back with `runOnUiThread` and an `isDestroyed` guard. All model state is mutated only on the main thread, so no data races; the risks are about stale or duplicated results.
- **Saved-data compatibility**: every saved shape from v2.0 to HEAD still loads. Corrupt entries are skipped (and then lost on the next save — see F-09).

### Findings from mapping

Severity: **Critical** = crash/data loss for most users, **High** = crash or wrong money numbers in a common path, **Medium** = crash or wrong state in a reachable but uncommon path, **Low** = rare edge case, hygiene, or future risk. Fix status is tracked in the later phases.

| ID | Sev | Where | Issue | Root cause | Found by |
|---|---|---|---|---|---|
| F-01 | Medium | MainActivity `syncBank` 778-796, `disconnectBank` 947 | A bank sync that finishes after Disconnect (or a reconnect) still saves its accounts/transactions into the just-cleared `bank` prefs, so bank data comes back after disconnecting or overwrites the new connection. | No connection token; the result is applied unconditionally. | Architecture, Data |
| F-02 | Medium | BankSync `connect` 245, called on the UI thread from MainActivity 901 | A keystore failure while saving the access key (`KeyStoreException`/`ProviderException`, seen on some devices) is uncaught → crash. | `Keys.encrypt` isn't guarded. | Data, lead |
| F-03 | Medium | MainActivity 790-792, 895 | Any non-`BankException` error text is shown in a Toast and saved as the sync error. A header-validation error could echo `Bearer <key>`. | Raw `Throwable.message` passed to the UI. | Data |
| F-04 | Medium | MainActivity 237/779, 242/2748 | Rotating during a bank sync or an update download starts a second one (two install sessions/prompts possible); the first result is dropped and the destroyed activity is held until its thread ends. | `bankSyncing`/`downloading` live on the Activity instance. | Architecture, Data |
| F-05 | Medium | MainActivity 880-905 | Cancelling "Connect your bank" while it says "Connecting…" doesn't stop it: the result still opens the account picker, and with one checking account connects without asking. | No check that the dialog is still showing. | Architecture |
| F-06 | Medium | MainActivity 2207-2214, 771 | If a background sync marks a bill paid while its edit sheet is open, Save says "<bill> saved" but drops the edit (and "Mark paid" from the sheet silently does nothing). | Bills are tracked by object identity; the sync replaces the object. | Architecture |
| F-07 | Medium | MainActivity 709-710, 1082, 2207 | Renaming a bill breaks a transaction the user assigned to it by hand: the payment flips back to "spending" while the bill stays paid, so Safe to spend drops by the bill amount. | Overrides store `bill:<name>`. | Data |
| F-08 | Medium | BankSync `saveSnapshot` 266-268, `parse` 155 | A server reply with no `transactions` array wipes every manual "how it counts" choice. | Missing array is treated as an empty list, then overrides are pruned to it. | Data |
| F-09 | Medium | MainActivity `load` 2635-2638, 2603-2623 | An unreadable bill (or an unreadable `bills` string) is skipped silently and then permanently overwritten by the next `save()`; a bad top-level string loses every bill. | Defensive `runCatching` with no logging and no preservation. | Data, lead |
| F-10 | Medium | MainActivity 2794-2807 | The install confirmation is started from a broadcast receiver; Android 10+ can block it if the user left the app during the download (and Android 16 adds intent-redirect checks). | Activity start from background. | Architecture, Dependency |
| F-11 | Low | MainActivity 388-393 + every `AlertDialog…show()` | Recreating the activity with a dialog open logs "Activity has leaked window" and loses the dialog. | Dialogs aren't tracked or dismissed in `onDestroy`. | Architecture |
| F-12 | Low | MainActivity 2626-2634, 2655 | A wrongly-typed pref (`ClassCastException`) crashes `onCreate` before the update banner can appear, contrary to the comment at 315. | Typed getters aren't guarded. | Data |
| F-13 | Low | BankSync 215, 224, 236 | One malformed entry empties the whole cached account list, transaction list or override map. | `getJSONObject`/`getString` outside the per-item guard. | Data |
| F-14 | Low | BankSync 181 | A non-numeric balance from the server becomes $0.00 and is applied as the bank balance. | `optLong` falls back to 0. | Data |
| F-15 | Low | BankSync 27, 134-135, 255 | TLS, DNS and timeout failures all say "check your connection"; the cause is dropped, so failures can't be diagnosed. | `BankException` has no cause; one catch-all mapping. | Data |
| F-16 | Low | MainActivity 318, 2693-2732 | The GitHub update check runs on every `onCreate` (each rotation; 60/h unauthenticated limit), never checks the status code, never disconnects, reads an unbounded body. | Minimal client tied to `onCreate`. | Architecture, Data |
| F-17 | Low | MainActivity 2746-2790 | Downloaded APK isn't checked against the release's size and the session doesn't pin the package name, so a truncated download only fails at install time. | Missing checks. | Data |
| F-18 | Low | MainActivity 2683-2690, 2782 | Abandon-session failures are swallowed without a log. | `runCatching` with no handling. | Data, Dependency |
| F-19 | Low | MainActivity 2803-2805 | Failure toast can read "Update failed: null" or show raw installer codes. | `EXTRA_STATUS_MESSAGE` may be null/technical. | Architecture, Data |
| F-20 | Low | MainActivity 2751 | Opening "Install unknown apps" settings is unguarded (`ActivityNotFoundException` on restricted builds). | — | Dependency |
| F-21 | Low | MainActivity 321-322 | The date at the top is set only in `onCreate`, so it shows yesterday's date when the app is resumed the next day. | Not refreshed in `refresh()`. | Architecture, lead |
| F-22 | Low | MainActivity 2592-2593 | `parseMoney` drops commas, so "12,50" in a comma-decimal locale becomes 1250. | Parsing ignores the locale `money` formats with. | Architecture, lead |
| F-23 | Low | MainActivity 380-386 | Delete-Undo entries saved at `onStop` are restored after process death even if their 5 s window had passed. | Saved state not updated when the bar hides. | Architecture |
| F-24 | Low | MainActivity 1827-1833 etc. | Undo restores a whole-state snapshot, so a bank sync that lands within the Undo window is rolled back too. | Snapshot-based undo. | Architecture |
| F-25 | Low (Medium at target 36) | MainActivity 396-400, 2066-2067 | Back-to-Summary and the sheet's animated close use deprecated `onBackPressed`, which Android 16 stops calling once targetSdk is 36. | Legacy back API. | Architecture, Dependency |
| F-26 | Low | build.gradle.kts 18-19 | A malformed `-PappVersion` ("1.2", "v1.2.3", "1.2.3-beta") fails every Gradle task with a cryptic error; "1.2.3.4" is silently truncated. | Unvalidated split/destructuring. | Dependency |
| F-27 | Low (testing) | build.gradle.kts | Debug and release share the applicationId, so a local debug build can't be installed next to the release app (signature + versionCode mismatch). | No debug suffix. | Dependency, lead |
| F-28 | Low | BalanceChart 24, 47, 52 | Chart text scales linearly while other text uses Android 14+ non-linear font scaling (user runs large font). | `sp` computed for 1sp then multiplied. | Dependency |
| F-29 | Low | MainActivity 1111-1120 | `refresh()` catches every `Exception` and shows a generic toast — an existing, deliberate safety net (logs to tag `Budget`, no personal data). | By design. | Architecture |
| F-30 | Info | network_security_config.xml | Cleartext to `localhost`/`127.0.0.1` is allowed in release builds — a deliberate test path for a fake bank server via `adb reverse`. | By design. | Architecture |
| F-31 | Info | build.gradle.kts, build.yml | Toolchain is behind (AGP 9.4.1 / Kotlin 2.4.20 / Gradle 9.8 current); AGP 8.7.3 can't compile against API 36; `kotlinOptions` becomes an error on Kotlin ≥ 2.2. CI publishes without verifying the APK's signing certificate. No wrapper, no tests, no lint config. | Pinned since 2024. | Dependency |

No secrets in the tree or in git history (Dependency + Data agents).

### Decisions taken with the owner (2026-10-06)

- Only a Galaxy Tab S7 was connected (not the S25 Ultra) → **owner chose to skip on-device testing**: no adb this session. Phase 4 runtime checks and Phase 7 screenshots are listed as unverified.
- **Debug builds get their own package** (`com.gh00ul.budget.debug`) so a local build can be installed next to the release app without touching its data.

---

## Phase 2 — Build health

Lead ran Gradle (8.11.1, the CI version; no wrapper in the repo) locally, one build at a time. Resource lint fixes by the Resources lint-fix agent (owned `src/main/res/**`); Kotlin, manifest and build files by the lead.

| Check | Before | After |
|---|---|---|
| `clean assembleDebug` | ✅, 2 Kotlin warnings | ✅, 0 warnings |
| `assembleRelease` | ❌ locally — `release.jks` only exists in CI (expected; signing deliberately left strict so CI can never publish an unsigned APK). Verified instead with the local debug key injected via `-Pandroid.injected.signing.*`: ✅ | ✅ (same method) |
| `lintDebug` / `lintRelease` | 0 errors, 169 warnings each | 0 errors, **3 warnings** each (deliberately left, below) |
| R8 / ProGuard | Not enabled (`isMinifyEnabled` unset) → no keep rules needed. If it's ever turned on: saved bills store `Freq.name` and read it back with `Freq.valueOf`, so enum names must be kept, and `mapping.txt` should be uploaded from CI. | — |

### Changes

| ID | Change | Files |
|---|---|---|
| F-27 | Debug builds are a separate app: `com.gh00ul.budget.debug`, label "Budget (debug)", with their own shortcuts file (a shortcut must name its own package). Release package, label and shortcuts unchanged — checked with `aapt2 dump` on both APKs. | build.gradle.kts, AndroidManifest.xml (`${appLabel}`), src/debug/res/xml/shortcuts.xml, src/main/res/xml/shortcuts.xml (comment) |
| F-26 | `appVersion` is validated (`\d{1,4}.\d{1,2}.\d{1,2}`) with a clear error; `1.2` / `v1.2.3` now fail with "appVersion must look like 3.2.1…", `3.2.1` configures normally. | build.gradle.kts |
| — | Kotlin warnings: unsuppressed deprecated `setDecorFitsSystemWindows` in the bill sheet (API 30–34 still need it); a redundant `existing != null` check. | MainActivity.kt |
| — | Every existing `@Suppress("DEPRECATION")` now has a comment saying why (back handling F-25, `obtain()` replacements are API 33+, ADJUST_RESIZE needed on API 26–29). `getParcelableExtra(String)` uses the typed overload on API 34+. | MainActivity.kt |
| — | `DrawAllocation` ×4: the chart reuses its two `Path`s and builds its gradient only when the data or size changes, not every frame. | BalanceChart.kt |
| — | `AccessibilityFocus`: kept (moving TalkBack focus to Undo after the deleted row disappears is deliberate), moved into a small function with a justified `@SuppressLint`. | MainActivity.kt |
| — | `RtlEnabled`: `android:supportsRtl="true"` (layouts already use start/end); `RtlSymmetry` ×4: added the matching side as `0dp`, identical to today. | AndroidManifest.xml, layouts |
| — | Accessibility/autofill: 17 decorative images marked `importantForAccessibility="no"` (most already were through their style, which lint can't see); money and bill-name fields `importantForAutofill="no"`; `inputType` on the amount field. | layouts |
| — | Themed (monochrome) launcher icon for Android 13+; `mipmap-anydpi-v26` → `mipmap-anydpi` (minSdk is 26). | mipmap-anydpi/ic_launcher.xml |
| — | Removed 17 unused resources (leftovers from v2.9/v3.0: avatar colors, 4 drawables, a style), each confirmed unreferenced by a repo-wide search. | drawable/, values/, values-night/ |
| — | `lint.xml` records the deliberate exceptions with reasons. | lint.xml |

### Left on purpose

- **HardcodedText / SetTextI18n (97)**: ignored in `lint.xml`. English-only app that builds most text from live numbers; moving it all to resources is a large refactor with no user-visible benefit unless the app is translated.
- **UnusedAttribute for `accessibilityHeading` / `screenReaderFocusable`**: ignored by message in `lint.xml`; they're API 28 screen-reader hints that Android 8.x simply ignores.
- **Justified `tools:ignore`** (each with an XML comment): `LabelFor` ×2 (the label is the dialog's title / both label and example hint are wanted), `SmallSp` (10sp month in the 44dp date badge), `MergeRootFrame` (root is looked up by id), `Overdraw` (background is a press ripple), `UseCompoundDrawables` (separately sized/tinted icon), `TooManyViews` (three tabs in one layout), `VectorPath` (small gear icon).
- **OldTargetApi, AndroidGradlePluginVersion, GradleDependency** (the 3 remaining warnings): kept visible as reminders. Upgrading AGP/Kotlin/targetSdk 36 is a separate, riskier step (predictive back must be migrated first, F-25) — see Remaining risks.

---

## Phase 3 — Crash hunt

Six read-only audits over the whole codebase, run in two batches of three (this PC's limit): **Null-Safety** (N-), **Lifecycle** (L-), **Threading** (T-; the app has no coroutines, so raw threads were audited), **UI rendering** (U-; Compose isn't used — confirmed by grep — so the View-system equivalents were audited), **Permissions & Intents** (P-), **Resources** (R-). The lead deduplicated them against the Phase 1 list (F-). Fixes: the **BankSync fix agent** owned `BankSync.kt`; the lead owned `MainActivity.kt` and the manifest (every other fix touched that one file). Build + lint after fixes: ✅ 0 Kotlin warnings, lint 0 errors / 3 deliberate warnings. An independent **Reviewer agent** then checked the diff (see "Review" below).

No Critical crash was found. Most of the risk was in work that outlives a screen (rotation, dark mode, split-screen, and home-screen shortcuts — which always start a fresh screen), and in the update installer on Android 10+.

### Fixed

| ID (dupes) | Sev | Bug | Root cause → fix | Files | Found by |
|---|---|---|---|---|---|
| L-1 | High | After using a home-screen shortcut, tapping the app icon stacked a second, stale copy of the screen whose next save could overwrite newer data. | The launcher intent didn't match the task's root intent → `launchMode="singleTop"`, so the icon returns to the same screen (`onNewIntent`). | AndroidManifest.xml | Lifecycle |
| F-04 (T-3, L-2, P-2, L-4, L-8, T-6, L-10, T-1) | Medium | Rotating or using a shortcut during a bank sync / connect / update download started a second one, dropped the first result, kept the old screen alive, and a fresh screen abandoned the install session still in use. "Sync now" during a sync did nothing. | Per-screen state → one file-private `Running` object (process-wide, main thread only) holds sync/connect/download/install-session state; workers post results to whichever screen is current, never to the one that started them; stale install sessions are cleared once per process and never the one in use; a "Sync now" during a running sync is announced when it finishes. | MainActivity.kt | Architecture, Data, Threading, Lifecycle, Permissions |
| F-10 (P-1, P-3) | Medium | The "Update this app?" prompt silently never appeared if you left the app during the download; install failures were silent on Android 13+; a missing installer could crash. | Android 10+ (and the 2023 security fix on 11–13) only lets the installer's result open the prompt while the app is on screen → the prompt waits in `Running` and opens on the next `onResume`; failure messages wait for a screen too; `ActivityNotFoundException`/`SecurityException` handled. | MainActivity.kt | Architecture, Dependency, Permissions |
| F-01 | Medium | A sync that finished after Disconnect (or a reconnect) saved its data back / overwrote the new connection. | No connection identity → `BankStore.connection` (the key's random IV); results for an old connection are dropped and the new one gets its own sync. | BankSync.kt, MainActivity.kt | Architecture, Data, Threading |
| F-02 (T-4) | Medium | A keystore error while connecting crashed the app; key generation ran on the main thread. | Unguarded `Keys.encrypt` → `sealAccessKey()` on the connect worker; keystore errors become a readable `BankException`; `Keys.key()` is `@Synchronized` so two connects can't both create the key. | BankSync.kt, MainActivity.kt | Data, lead, Threading |
| F-03 | Medium | Unexpected errors showed their raw text (could include the URL or `Bearer <key>`). | Raw `Throwable.message` → only `BankException` text is shown; anything else gets a plain message and only its class is logged; keys with unsendable characters are rejected before the header is built (`isUsableKey`). | both | Data, Null-Safety |
| F-05 | Medium | Cancelling "Connect your bank" while it said "Connecting…" still connected. | Result applied unconditionally → Cancel/Back cancel the attempt (a rotation doesn't — the account choice then comes up on the new screen). | MainActivity.kt | Architecture |
| F-06 | Medium | Editing a bill while a background sync marked it paid: "saved", but the edit was lost; "Mark paid" from the sheet did nothing. | Bills tracked by object identity → each bill has an in-memory `id` kept across paid-mark copies; lookups use `indexOfBill`; the sheet merges paid marks added meanwhile. | MainActivity.kt | Architecture |
| F-07 | Medium | Renaming a bill flipped transactions you'd assigned to it back to "spending" (counted twice). | Overrides store `bill:<name>` → renaming rewrites them (and Undo rewrites them back). | both | Data |
| F-08 | Medium | A reply without a `transactions` list wiped every "how it counts" choice. | Missing list treated as empty → it's an error now (the server always sends it; checked in its source). | BankSync.kt | Data |
| F-09 | Medium | An unreadable saved bill was dropped and then erased by the next save; a damaged list lost every bill. | Silent `runCatching` → unreadable bills are kept as-is and written back on every save; an unparseable list is copied to `bills_unreadable` first (same for paycheck changes). | MainActivity.kt | Data, lead |
| L-3 | Medium | Rotating (or a dark-mode switch) while "Has today's pay landed?" / "Did Rent come out today?" / "Did you spend $X?" was open lost the answer, leaving Safe to spend wrong. | The questions lived only in dialog closures → the chain is a member (`askBalanceFollowUps`); the open question is saved with the screen's state and asked again after recreation. | MainActivity.kt | Lifecycle |
| T-2 (U-1) | Medium | 30–100 ms main-thread stalls on every redraw with a linked bank (≈9,000 regex compiles). | Regex built per transaction × bill → compiled once, bill names split once, names compared only when the amount is close (same results). | MainActivity.kt | Threading, UI |
| T-7 (U-3) | Medium | A background sync replaced a pending Undo ("Deleted Rent · Undo"), making the delete permanent. | Every sync message cleared Undo state → a sync nobody asked for doesn't replace a pending Undo. | MainActivity.kt | Threading, UI |
| U-2 (part) | Medium | A background sync landing mid-swipe cancelled the swipe. | Full redraw replaced the touched row → redraws wait until the finger lifts. (TalkBack focus restore → Phase 7.) | MainActivity.kt | UI |
| F-11 | Low | Rotating with any dialog open leaked its window. | Untracked dialogs → all dialogs go through `present()` and are dismissed (not cancelled) in `onDestroy`; nothing is shown on a finishing screen. | MainActivity.kt | Architecture, Lifecycle |
| F-12 | Low | A wrongly-typed saved value crashed startup before the update banner. | Unguarded getters → treated as missing (logged by key name only). | MainActivity.kt | Data |
| F-13, F-14, F-15 | Low | One bad saved item emptied a whole list; a non-numeric balance became $0.00; TLS/DNS/timeouts all said "check your connection". | Per-item parsing with counts logged; non-numbers → null; specific messages with the cause kept. | BankSync.kt | Data |
| F-16…F-20, P-5 | Low | Update check on every rotation, no status check/disconnect/size limit; truncated download installed; swallowed errors; "Update failed: null"; unguarded settings launch; debug builds offered to install the release app. | Cached for 30 min, status-checked, bounded, disconnected; download size checked against the release; session pinned to this package; per-status messages (Auto Blocker, conflict — "don't uninstall", storage…); `ActivityNotFoundException` handled; debug builds only say a release is out. | MainActivity.kt | Architecture, Data, Dependency, Permissions |
| F-21 (U-6) | Low | The date at the top (and all numbers) stayed on yesterday while the app stayed open; "Log a purchase" across midnight used the old balance. | Set once → set on every redraw, plus a redraw at midnight while on screen; the purchase uses the balance at save time. | MainActivity.kt | Architecture, UI |
| F-23 | Low | An expired delete-Undo came back after process death. | Saved without expiry → saved with its end time. | MainActivity.kt | Architecture |
| N-1, N-2 | Low | A server date near year 999,999,999 crashed date math; a corrupted saved date could hang the app. | No bounds → future-dated transactions (>31 days) dropped; saved dates outside 1900–2200 ignored. | both | Null-Safety |
| L-5 (T-8) | Low | A swipe finishing during a rotation acted on the old screen's data. | End action didn't check → skipped on a destroyed screen. | MainActivity.kt | Lifecycle, Threading |
| L-7 | Low | Reopening from Recents replayed the shortcut that first opened the app. | `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` checked. | MainActivity.kt | Lifecycle |
| T-9, T-10, L-9 | Low | Failed syncs retried on every resume; unnamed threads; a local `finish()` shadowing `Activity.finish()`. | 5-minute back-off after a failure; named threads; renamed. | both | Threading, Lifecycle |

How verified: compiled (0 warnings) and lint-clean; reviewed by the lead and the Reviewer agent; logic covered by unit tests where it's pure (Phase 6). **Not verified on a device** (owner chose no on-device testing this session).

### Review (Reviewer agent, read-only, on the uncommitted diff)

Verdict: sound, one blocker. All findings were fixed before this phase was committed:

| ID | Sev | Problem | Fix |
|---|---|---|---|
| RV-1 | High | My F-22 change was itself a bug: Android's `numberDecimal` fields (no `imeHintLocales`) only accept digits and `.` in every locale, so a comma can't be typed, and the new parser then read "12.50" as 1250 on comma-decimal phones. | Reverted to the original `.`-only parsing, which matches what the fields accept. **F-22 is not a bug** (withdrawn). |
| RV-2 | Low | Keeping an Undo through a background sync (T-7) also kept snapshot Undos, which would roll back the sync's paid marks and balance. | Only a delete's Undo (re-inserts one bill) is kept; other Undos are replaced by the sync message, as before. |
| RV-3 | Low | After a rotation, a connect result could re-enable/dismiss a different, newer "Connect" dialog. | Results only touch the dialog that started that attempt; Connect is re-enabled only if its fields are still valid. |
| RV-4 | Low | The update-check thread called a screen method (`isNewer`), keeping that screen in memory. | `isNewer` is a top-level function. |
| RV-5 | Low | A balance question saved before process death could be re-asked the next day against yesterday's numbers. | Saved with its day; only re-asked the same day. |
| RV-6 | Low | If the installer's result started the app with no screen, the first screen then abandoned that session as stale. | The receiver records the session as in use before holding the prompt. |
| RV-7 | Low | A held redraw ran during the 160 ms swipe-out, so a deleted row flashed back. | A committed swipe releases the hold only after the delete / mark-paid runs. |
| RV-8 | Low | A rename's Undo moved back every `bill:<new name>` choice, including another bill's. | `renameBillOverrides` returns the transactions it moved; Undo moves back exactly those. |

Also from the review: `isUsableKey` allowed only `!`..`~`, so an existing key containing a space (legal in a header) would have started failing — now spaces inside a key are allowed.

### Moved to later phases / left

- **Phase 5**: P-6 (a sync cut off because the app went to the background is saved as an error), F-24 (whole-state Undo vs. a sync landing in its window), P-7 (cloud backup without encryption — owner's choice), orphan keys from v2.0/v2.3.
- **Phase 7 (UI)**: R-1 (High: money amounts split mid-number at the owner's font size), R-2…R-11, U-2 TalkBack focus, U-4 (sheet under the status bar), U-5, U-7…U-14, F-28.
- **Left on purpose**: F-25 (predictive back — must be migrated before targetSdk 36), F-29 (`refresh()` safety net kept), T-5 (6–18 ms prefs load at cold start), L-6 (shortcuts always start a fresh screen — platform behavior; keeping open work would need a trampoline activity), U-15 (a half-typed bill sheet is lost on rotation), F-30 (cleartext to localhost — the fake-bank test path), a choice made on a *pending* transaction is lost when it posts under a new ID (the server doesn't pass Plaid's link through — needs a server change).

---

## Phase 4 — Runtime verification

**Skipped by the owner's choice (2026-10-06).** Only a Galaxy Tab S7 was connected (not the S25 Ultra); the owner chose no on-device testing, so no Device Agent ran and no adb command was issued after the initial `adb devices -l`. `connectedAndroidTest` was not run (there are no instrumented tests).

Code change made for future runs (lead):

- **StrictMode in debug builds only** (`enableStrictModeInDebug()`, once per process, `penaltyLog` only — never crashes): thread policy `detectAll`; VM policy: activity/closable/registration/SQLite leaks, file-URI exposure, cleartext network, content-URI permission, non-SDK API use (28+), credential-protected storage while locked (29+), unsafe intent launch (31+). Untagged-socket detection is left out (every `HttpURLConnection` request would trip it). Expected, known entries: a disk read when preferences first load in `onCreate` (T-5), and on API 31+ an "unsafe intent launch" for the installer's own confirm intent (that launch is the documented PackageInstaller flow).

### Still needs checking on a device (S25 Ultra preferred)

Build and install the debug app next to the release one — see "Build & install" at the end. Watch `adb logcat --pid=$(adb shell pidof com.gh00ul.budget.debug)` and `adb logcat -s Budget StrictMode`.

1. **Every screen once** (light + dark, font scale 1.0 and the owner's large font): Summary, Paydays, Bills, the bill sheet (add/edit, date picker, mark paid, unmark, delete), every balance question, Settings, Connect your bank, bank status, purchases + "change how it counts", the explain dialog, the update banner.
2. **Rotation / dark-mode switch / split-screen resize while**: a bank sync runs (one sync, result shown on the new screen); "Connecting…" (account picker appears on the new screen; Cancel before rotating stops it); the APK downloads (banner keeps "Downloading…", one install prompt); any dialog is open (no "leaked window" in logcat); a balance question is open (asked again).
3. **Shortcuts**: "Update balance" / "Add bill" from cold start and while open; then tap the app icon → one screen, not two (`adb shell dumpsys activity activities | grep budget`); reopen from Recents → shortcut not replayed.
4. **Update flow**: on the release app only (debug builds don't offer updates). Start Update, press Home during the download → the prompt appears on returning to Budget; cancel the prompt → Update can be tapped again; Samsung Auto Blocker on → readable message.
5. **Process death**: background the app with a delete-Undo or a balance question showing, `adb shell am kill com.gh00ul.budget.debug`, reopen from Recents.
6. **Midnight**: leave the app open across midnight → date and "due in" days move on.
7. **Bank sync** (fake server via `adb reverse`, as before): sync, disconnect mid-sync (no data comes back), reconnect mid-sync, swipe a bill while a sync lands, a server reply without `transactions` (error, choices kept).
8. **Large font (R-1…R-7)** after Phase 7: Summary rows with ≥ $1,000 due, the message bar, bill rows, a changed paycheck row.

---

## Phase 5 — Data & network

Three read-only audits in parallel on the post-Phase-3 code: **Persistence** (S-; Room/DataStore don't exist — confirmed — so SharedPreferences, JSON, the keystore and installer file writes were audited), **Network** (W-), **Error UX** (E-). Fixes: the **BankSync fix agent** owned `BankSync.kt` (it also compiled the file standalone and checked the new pure functions with 35 assertions in a scratch JVM run); the lead did `MainActivity.kt`. Build + lint: ✅ 0 Kotlin warnings, lint 0 errors / 3 deliberate warnings.

### Fixed

| ID (dupes) | Sev | Bug | Root cause → fix | Files | Found by |
|---|---|---|---|---|---|
| P-6 (W-1, E-2, S-6) | Medium | Android 15+ cuts an app's network ~20 s after it leaves the screen. A sync cut off that way was saved as "Sync problem · Couldn't find the bank server… check the address", and started the 5-minute back-off. | Every failure was saved the same way → screens count `onStart`/`onStop` (rotations excluded); a network failure of a sync that started before Budget left the screen is *not* saved — the sync runs again on return (or right away if Budget is already back). `BankException.network` marks connection failures only. | both | Network, Error UX, Persistence |
| W-2 | Medium | No overall time limit: a slow or trickling server could keep a sync "running" for 20+ minutes, blocking every later sync including "Sync now". | Per-read timeouts only → 2-minute budget per sync (1 minute for Connect); timeouts shrink to the time left; reading stops at the deadline. A sync that runs out of time or pages after the first page applies the latest data with "Some of your banks weren't refreshed this time." | BankSync.kt | Network |
| S-1 | Medium | A saved bill with `NaN` (damaged data) made the next save write *no* bills at all — every bill lost. Introduced by the Phase 3 "keep unreadable bills" change. | Android's JSON refuses to write NaN and `toString()` returns null (which Kotlin can't see) → such an item is never kept for writing back (the original text is copied aside instead), and `save()` keeps the saved bills if the list can't be written. | MainActivity.kt | Persistence |
| E-1 | High | Long instructions ("Don't uninstall Budget…", Auto Blocker, keystore, connect failures) were toasts, which Android 12+ cuts to two lines and hides after ~3.5 s. | → `tell()`: a plain dialog with OK, used for every message that asks the user to do something; short confirmations stay toasts. | MainActivity.kt | Error UX |
| S-4 | Medium | Linked users saw "Rent marked paid from your bank" again after every reload for old bills. | Paid marks are kept 60 days but bank transactions 70 → one constant; payments older than the kept marks aren't re-marked. | MainActivity.kt | Persistence |
| E-3 (S-5, F-24) | Medium | "Mark paid" → Undo restored the whole balance state, rolling back a bank sync that landed meanwhile (balance off by a paycheck). | Snapshot Undo → when the balance wasn't touched, Undo only removes that paid mark. | MainActivity.kt | Error UX, Persistence |
| E-4 | Medium | After Connect (or Sync now), "account not found" / "no balance" was only saved — during setup nothing at all was visible. | → said in a dialog when someone asked, with what to do. | MainActivity.kt | Error UX |
| E-5 | Medium | "Use a different account" with no accounts showed an empty list; with one, silently re-picked it. | → explains instead. | MainActivity.kt | Error UX |
| E-6 (S-3) | Medium | Bills the app couldn't read were kept but invisible (and not counted). | → the Bills subtitle says "· N saved bills can't be shown by this version; update the app". | MainActivity.kt | Error UX, Persistence |
| E-7 | Medium | The redraw safety net toasted "Try updating the app" on every redraw. | → said once per screen, in a dialog: "Budget couldn't show everything. Your numbers are still saved. Check Settings › Check for updates." | MainActivity.kt | Error UX |
| E-8 (W-14) | Medium | Connect stayed greyed out for an `http://` address or a short key with no reason; failures vanished in a toast. | → field errors ("Use the https:// address…", "That's not the whole access key…"); the failure is shown in the dialog itself and read out. | MainActivity.kt | Error UX, Network |
| E-9, W-5, W-6, W-7, E-10, E-11, E-23 | Medium/Low | Messages that sent users to places that can't fix it ("Check it in Settings" — Settings can't edit the key), "check the address" when simply offline, "(301)", raw server text of any length, "Open ClearBudget and tap Connect bank" (confusable with this app). | → separate wording for Connect vs. sync, with the real path (Settings → your bank → Disconnect → Connect); redirects explained; server text cleaned and capped at 200 characters; no status codes; next steps on TLS/unreadable/too-much/no-accounts. | BankSync.kt | Network, Error UX |
| E-12 | Medium | The install-conflict advice ("get the update from the releases page") couldn't work and "Don't uninstall" could be cut off. | → "Don't uninstall Budget: that deletes your data" first; installer-blocked gives the real releases address. | MainActivity.kt | Error UX |
| E-18 | Medium | Two bills with the same name: bank payments assigned by name went to the first one. | → first Save warns (on the field), a second Save keeps it — nothing is blocked. | MainActivity.kt | Error UX |
| S-7 | Low | A valid but partial server reply (a bank temporarily missing) erased that bank's "how it counts" choices. | Pruned by absence → kept while the transaction is less than 70 days old (`keptChoices`). | BankSync.kt | Persistence |
| — | Low | A choice made on a **pending** transaction was lost when it posted under a new id. | → strict one-to-one carry-over (`carryOverChoices`: same account, same amount to the cent, posted within 10 days, exactly one match each way). The exact fix still needs the server to pass Plaid's `pending_transaction_id`. | BankSync.kt | Persistence, Error UX |
| W-3 (E-13) | Low | A download cut off by leaving the app said "check your connection", then the banner reset to "ready" on return. | → "The download stopped when you left Budget. Tap Update to try again." kept on the banner until the next try. | MainActivity.kt | Network, Error UX |
| W-4 | Low | "Syncing…" could stay forever (no balance from the server) or vanish while a sync ran. | → shown only while a sync someone asked for is really running; a sync problem wins over it. | MainActivity.kt | Network |
| W-8 | Low | No retries. | One retry after 2 s for connection refused/no route and gateway 502/504 (both endpoints are safe to repeat — checked in the server source); never for 4xx, TLS, DNS or read timeouts. | BankSync.kt | Network |
| W-9, E-14, E-15 | Low | Every update-check failure said "check your connection" (also GitHub's rate limit); no feedback on tap; a failed check at launch was never retried. | → GitHub problems say so; "Checking for updates…"; retried on resume 5 minutes after a failure. | MainActivity.kt | Network, Error UX |
| W-10 | Low | A wrong/oversized download filled storage before being rejected. | → the copy stops as soon as it's larger than the release. | MainActivity.kt | Network |
| S-2, S-9 | Low | The aside-copy of damaged data was kept only once; wrong-typed lists got no copy; unreadable saved choices were overwritten; a NaN saved transaction broke every redraw. | → a later, different copy is kept as `…_unreadable_latest`; wrong-typed lists are copied too; `overrides_unreadable`; NaN transactions skipped. | both | Persistence |
| S-10 | Low | A closing (not yet destroyed) screen could still save from an animation end. | → `isFinishing` checked too. | MainActivity.kt | Persistence |
| E-16, E-17, E-19, E-20, E-21, E-22, E-24 | Low | Old estimates said only a weekday; a past "next due" moved silently; a $0 purchase "saved" nothing; no "Pay saved" before the payday-changed question; empty purchase list after a failed sync looked like $0 spent; sync problem hidden behind "marked paid"; sheet errors not read by TalkBack. | → date for estimates ≥ 7 days old; "Oct 1 has passed, so it's next due Nov 1."; Save disabled at $0; snack first; "Last synced …, so the newest purchases may be missing."; both said; errors announced. | MainActivity.kt | Error UX |
| Orphan keys | Low | `income` (v2.0) and `bills_open` (v2.3–2.4) were kept and backed up forever. | → removed with the next save (nothing can read them; no older version can be reinstalled over this data). | MainActivity.kt | Persistence |

### Left (with reasons)

- **P-7 — cloud backup without requiring encryption** (owner's call). `budget.xml` holds balances and bills, no credentials; `bank.xml` is correctly excluded. Recommendation: `<cloud-backup disableIfNoEncryptionCapabilities="true">` (plus the Android 9–11 `requireFlags="clientSideEncryption"` form). It changes nothing on a phone with a screen lock; on one without, it trades "no cloud backup" for "a backup Google could read".
- **S-8 — Disconnect + Connect again erases every "how it counts" choice** (owner's call: keep choices across a reconnect, or add a "Re-enter access key" item).
- **E-11 — keep the server address after Disconnect** so it's prefilled (owner's call; small privacy trade-off).
- **W-11** (all banks "already syncing" still says "Synced"), **W-13** (sync timers use the wall clock), **ACCESS_NETWORK_STATE** for an instant "You're offline" (a manifest change; the message fix covers most of it).
- S-7 survives one partial reply only (the transaction list itself is replaced); a fuller fix needs a saved date per choice.
- Whole-file corruption of `budget.xml` (Android treats a damaged file as empty) — very rare thanks to Android's write-backup; detection would be new code with no way to test it here.
