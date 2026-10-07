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
