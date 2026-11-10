# Budget

> **TL;DR:** Simple Android budget app: income minus bills.

For people paid weekly. Enter your balance, pay, payday and bills, and it shows what's **safe to spend** this week, what's left after each payday, and what's due next. Includes CSV export and a home-screen widget. Your numbers stay on your phone.

## Install

Open **[budget.apk](https://github.com/gh00ul/budget/releases/latest/download/budget.apk)** on your phone and install it. When a new release is out, the app shows an **Update** banner.

## Bank sync (optional)

Pulls your balance and purchases through the Plaid server in [clearbudget-personal](https://github.com/gh00ul/clearbudget-personal) (`bank-sync/`). Set it up in **Settings → Connect your bank**.

## Shipping an update

Push a tag higher than the [latest release](https://github.com/gh00ul/budget/releases), as `vMAJOR.MINOR.PATCH` with minor and patch below 100:

```bash
git tag vX.Y.Z
git push origin main vX.Y.Z
```

GitHub Actions builds, signs and publishes it. Every release must use the same signing key (the `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` secrets) or Android won't install it as an update.

## More

[Full details](docs/README-full.md): how safe-to-spend, bank sync and bill matching work.
