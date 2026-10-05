# Budget

A simple Android budget app for people paid weekly. Enter your bank balance, weekly pay, payday, and bills (each with how often it repeats and when it's next due). It shows:

- **Safe to spend:** this week's spending money (pay minus each bill's weekly share, minus what you've spent since payday), lowered if needed so every upcoming bill still gets paid on time. Tap it for the full breakdown.
- **Paydays:** what's left after each payday's bills, and your balance at the end of the month.
- **Bills:** what's due and when.

Your numbers are stored on your phone (and in Android's own backup if you have it turned on), never sent anywhere else.

When the app opens it checks this repo's latest GitHub Release. If there's a newer version, an **Update** banner appears; tap it and confirm Android's prompt.

## Install

On your phone, open this link and install the APK:

https://github.com/gh00ul/budget/releases/latest/download/budget.apk

(Android will ask you to allow installs from your browser the first time.)

## Shipping an update

Commit your changes, then push a tag **higher than the latest release** (see the [releases page](https://github.com/gh00ul/budget/releases)), in the form `vMAJOR.MINOR.PATCH` with minor and patch below 100:

```bash
git tag v2.9.1
git push origin main v2.9.1
```

GitHub Actions builds and signs the APK and publishes the release. Next time the app opens, it offers the update.

Releases are signed with a key stored in the repo's `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` secrets (with an offline backup kept by the owner). Every release must use that same key, or Android won't install it as an update.
