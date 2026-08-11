# Security Policy

## Reporting a vulnerability

Please report security issues privately first. Do not open a public issue for
anything that could move funds or expose a key or recovery phrase.

**Preferred channel:** [GitHub private vulnerability reporting](https://github.com/ORDNET/ORDnet-Android-Wallet/security/advisories/new)
— the "Report a vulnerability" button on the Security tab of this repository.
This opens a private advisory only the maintainers can see.

Please include what the issue is, which file and line, how to reproduce it,
and what an attacker gains.

## What to expect

- **Acknowledgement:** within 3 working days.
- **Assessment:** within 10 working days, with a severity.
- **Fix:** anything that can move funds, expose a key or recovery phrase, or
  let a web page act as the wallet without consent is prioritised over
  everything else.
- **Credit:** we will name you in the release notes unless you prefer otherwise.

We do not currently operate a bug bounty.

## Threat model

This app holds BSV keys and browses arbitrary web3 / on-chain pages in an
in-app WebView. The assumptions that matter:

1. **Every page is hostile.** Anything a page posts to the native bridge
   (fragments, BRC-100 calls, `window.ordplug` calls) is attacker-controlled.
   The wallet derives the page's origin natively — it never trusts an
   `originator` the page supplies — and escapes any page-supplied string
   before it re-enters a WebView context.

2. **Reading the wallet requires the device owner.** The vault key requires a
   recent user authentication (biometric or device credential); decryption is
   refused by the Android Keystore otherwise.

3. **Money is never a silent grant.** Spending and destructive actions
   (`relinquishOutput`) require a fresh confirmation per call. Read access to
   history / outputs requires an explicit, revocable per-origin consent.

## Running the JS engine tests

```
cd Tests && npm install @bsv/sdk && node run-tests.mjs
```
