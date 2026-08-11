# Security fixes — external audit of 11 August 2026 (Android wallet)

**Released in:** v3.4 (versionCode 340)
**Audit date:** 11 August 2026

Findings H4, H6 and H7 from the 11 Aug 2026 external audit, plus the
Android↔iOS drift items that affected this platform.

## H4 — BRC-100 originator was taken from the page

`handleBrc100Message` used `originator` straight from the page's message body.
Grants and daily budgets key on `address|origin|level|protocol`, so a page that
sent `originator: "https://trusted.dapp"` inherited that dApp's grants and
budget. The `window.ordplug` path already derived the origin natively.

**Fix.** The BRC-100 path now uses `currentOrigin` (derived from the actually
loaded URL) and ignores the page-supplied field entirely.

## H6 — the keystore key did not require authentication

The vault key was created **without** `setUserAuthenticationRequired(true)`, so
`readVault()` decrypted with a key that required nothing. The biometric prompt
was UI only: any code path that skipped `authenticate()` could read the wallet.

**Fix.** The key is now created with `setUserAuthenticationRequired(true)` and a
short authentication **validity window** (30 s): `setUserAuthenticationParameters`
on API 30+, the duration API on 26-29. The Keystore itself refuses vault crypto
unless the user authenticated recently, so the prompt is no longer cosmetic.

To keep the window model usable without prompting on every unrelated write:

- `unlock()` authenticates before `readVault()` (already did).
- `createWallet` / `importWallet` authenticate before the **first** encrypt.
- `saveAccounts()` catches `UserNotAuthenticatedException` and re-prompts
  through a weakly-held activity reference, then retries the save once.

The original data-loss protection is preserved: the key does **not** set
`setInvalidatedByBiometricEnrollment(true)` and allows `DEVICE_CREDENTIAL`, so
enrolling a new fingerprint/face does not wipe the wallet.

## H7 — listActions / listOutputs / relinquishOutput were ungated

These three BRC-100 methods ran with no permission check. `listActions` leaked
the full action history, `listOutputs` leaked every UTXO, and `relinquishOutput`
could be called in a loop to permanently remove every output from funding —
bricking the wallet's spendability — with no confirmation.

**Fix.**

- `listActions` / `listOutputs` now require an explicit **per-origin read
  consent** (a persistent grant, revocable in Settings) before any wallet data
  is returned.
- `relinquishOutput` requires a **fresh biometric confirmation per call**
  (money-grade, never a persistent grant), naming the outpoint, and only after
  the outpoint has been validated as a real, owned output.

## Android↔iOS drift

The drift items in the audit were fixes iOS was missing relative to Android;
Android already had the correct behaviour for `removeAccount` (index shift),
`removeWallet` (wipes every BRC-100 store), the "one approval sheet at a time"
guard, and the reachable-vs-empty registry check. No Android change was needed
for these — the iOS side was brought up to Android's behaviour.
