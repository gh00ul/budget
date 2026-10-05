# Budget

A very basic Windows budget app: enter your monthly income, list your bills, and it shows what's left over.

- Your numbers are saved to `%APPDATA%\Budget\budget.json` (never uploaded anywhere).
- When the app opens it checks this repo's latest GitHub Release in the background. If there's a newer version, an **Update now** button appears; click it and the app replaces itself and restarts.

## Install

Download `Budget.exe` from the [latest release](https://github.com/gh00ul/budget/releases/latest) and run it. Requires the [.NET 8 Desktop Runtime](https://dotnet.microsoft.com/download/dotnet/8.0) (Windows will prompt for it if missing).

## Shipping an update

Commit your changes, then tag a new version and push the tag:

```bash
git tag v1.0.1
git push origin main v1.0.1
```

GitHub Actions builds `Budget.exe` with that version and publishes the release. Next time the app opens, it offers the update.

## Building locally

```bash
dotnet run
```
