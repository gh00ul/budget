# Budget

A very basic Android budget app: enter your monthly income, list your bills, and it shows what's left over.

- Your numbers stay on your phone.
- When the app opens it checks this repo's latest GitHub Release in the background. If there's a newer version, an **Update now** button appears. Tap it, confirm Android's prompt, and you're updated.

## Install

On your phone, open this link and install the APK:

https://github.com/gh00ul/budget/releases/latest/download/budget.apk

(Android will ask you to allow installs from your browser the first time.)

## Shipping an update

Commit your changes, then tag a new version and push it:

```bash
git tag v2.0.1
git push origin main v2.0.1
```

GitHub Actions builds and signs the APK and publishes the release. Next time the app opens, it offers the update.

Releases are signed with a key stored in the repo's `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` secrets. Every release must use that same key, or Android won't install it as an update.
