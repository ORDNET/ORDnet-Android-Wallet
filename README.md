# ORDnet Wallet — Android app (v3.4.1)

[![engine tests & build](https://github.com/ORDNET/ORDnet-Android-Wallet/actions/workflows/test.yml/badge.svg)](https://github.com/ORDNET/ORDnet-Android-Wallet/actions/workflows/test.yml)
[![test count](https://img.shields.io/badge/engine_tests-69_passing-2b8a3e?style=flat-square)](#tests)
[![platform](https://img.shields.io/badge/platform-Android_%C2%B7_Jetpack_Compose-364fc7?style=flat-square)](#requirements)
[![engine](https://img.shields.io/badge/crypto_engine-byte--identical_with_iOS-5f3dc4?style=flat-square)](https://github.com/ORDNET/ORDnet-iOS-Wallet)
[![license](https://img.shields.io/badge/license-source--available-6a737d?style=flat-square)](LICENSE)

Full native Android version of the **ORDnet Web3 Browser / ORDnet Wallet** — the counterpart of the iOS app and the Chrome extension. Not a wrapper: the entire UI is Jetpack Compose; the crypto engine (`bsv.min.js` + `wallet-core.js` + `bsv-sdk-bundle.js`) runs invisibly in a network-isolated WebView JS VM.

On the engine: `wallet-core.js`, `bsv.min.js` and `bsv-sdk-bundle.js` are **byte-identical to the iOS app's copies**, and the two vendored libraries are byte-identical to the Chrome extension's as well. `brc100-shim.js` differs between the two mobile apps, and the extension does not use `wallet-core.js` at all — its wallet logic is a separate implementation in `src/wallet.js`. So transactions built here are byte-for-byte identical to the iOS app's; against the extension the shared ground is the vendored libraries and a common set of conformance vectors, not the same file.

## Requirements

- Android Studio (Ladybug or newer recommended; AGP 8.7)
- JDK 17 (bundled with Android Studio)
- Android 8.0+ (API 26) device or emulator; target SDK 35

## Build & run

1. Open the repository root in Android Studio (File → Open) — the root itself is the Gradle project.
2. Wait for the Gradle sync to finish (dependencies are downloaded automatically).
3. Pick your device or emulator and press **Run ▶** (or `./gradlew assembleDebug` for an APK in `app/build/outputs/apk/debug/`).

> For a Play Store release: `./gradlew bundleRelease` (configure signing in `app/build.gradle.kts` or via Android Studio → Generate Signed Bundle).

## Architecture

| Layer | Technology | Origin |
|---|---|---|
| UI | 100% native Jetpack Compose (Kotlin) | 1-to-1 port of the SwiftUI views; same ORDnet colors, capsule buttons and inline alerts |
| Crypto engine | `bsv.min.js` + `wallet-core.js` in an invisible, network-blocked WebView JS VM | **byte-identical to iOS**; shares the vendored libraries and conformance vectors with the extension |
| Key storage | Android Keystore (hardware-backed AES-256-GCM) + BiometricPrompt | counterpart of iOS Keychain + Face ID (see security notes) |
| Network | OkHttp (WhatsOnChain, bsvmap.io, domains.ordnet.io) | same endpoints, incl. 429 backoff and tx-hex cache |
| .web3 browser | WebView + `shouldInterceptRequest` router (`ordweb3://`) | replaces the WKURLSchemeHandler / service-worker router (sw.js) |
| dApp API | `window.ordplug` provider via `addJavascriptInterface` + native approval sheets | same method set as inpage.js / the iOS provider |

`window.ordplug.version` reports `1.0.0` — the version of the provider *API*, deliberately identical across the Chrome, iOS and Android wallets and independent of the app version.

Why a WebView as JS VM and not `androidx.javascriptengine`? Android's JS sandbox cannot bind native functions to JS and has no `crypto.getRandomValues`. The (never shown, never loading) WebView provides a full V8 with real OS entropy for `crypto.getRandomValues` — exactly what JavaScriptCore offered on iOS — while all network requests from the engine are hard-blocked (`blockNetworkLoads` + request interceptor).

## Features (parity with the iOS app and the Chrome extension)

- Upload & Inscribe tab: inscribe images, text and HTML files on-chain (1Sat Ordinal, max 100MB) with per-wallet TXID history and JPEG/PNG compression slider
- Create a wallet (BIP39, 12 words) and import: BIP44, legacy V9, WIF, plus wallet presets (RelayX, Yours/Panda, Twetch, Money Button, Simply Cash, ElectrumSV, HandCash 1.x, Centbee incl. PIN, Edge, custom path) with address preview
- Multi-account: add (generate/import), rename, switch, remove, backup reveal (biometrically gated; phrase kept in session memory only)
- Send BSV with a safety layer: first-time-address warning, near-max warning, self-send detection, clipboard verification, send-max, QR scanner, address book
- Receive with QR, history via WhatsOnChain, balance + USD rate
- Holdings: SNS names, BSVmaps (ORDnet V30 indexer) & OpNS names (OpNS index at search.ordnet.io) in a two-row segmented picker (row 1 SNS + OpNS, row 2 BSVmaps + For sale), with search, listed status + price (registry merge), and pagination (20 per page) with a pager bar above the list — the exact Chrome-extension pattern
- Pay to names from Send (v3.1): `naam.tld` / `mailbox@naam.tld` resolves via the signed SNS resolver at sns.ordnet.io (signature against a pinned key, address derived from the signed holder_script, expires + a provable spent-check via /tx/<txid>/<vout>/spent, key rotation only via a proven succession chain); bare names resolve via the OpNS index (exact match only, holder recomputed on-chain, outpoint checked unspent) — both with a two-tap confirm that re-verifies at signing
- Ordinal transfer: real 1Sat transfer with ownership check, raw-script fetch (never the verbose endpoint) and local input verification before broadcast
- Marketplace: list (SIGHASH_SINGLE|ANYONECANPAY atomic swap), delist, bulk list/delist (max 300) — all with the trust-but-verify checks on both server stores and self-heal for stuck listings
- .web3 domains: registry list with search + pagination (10 per page), whois, set-target, subdomains, routes, marketplace (USD), transfer — every action signed in the exact `ordnet-registry|…` format; domain management and the .web3 resolver talk to the ORDnet v2 platform on its main domain domains.ordnet.io
- ORDnet browser: .web3/TXID navigation, on-chain content rendering (HTML/image/video/audio/text), internal link router, security scanner, app catalog (ORD/domains, ORD/mail, ORD/app, ORD/swap, ORD/clawd, …)
- dApp provider: connect, getAddress, getPublicKey, getBalance, pay, inscribe, signMessage, purchase (ORDPAY), listOrdinal, buyOrdinal, sendTx (max 350 outputs) — with native approval sheets and connected-sites management
- ORD/ner (v3.2): on-chain file browser — accounts are folders, grid/list view with image thumbnails, file detail with preview, Open in Browser, Copy TX info and Send (1Sat ordinal transfer); the app's inscription log supplies filenames and "sent" labels
- UTXO tools (v3.2): split (N × X sats) and combine (all spendable UTXOs → one output) on the ordinal-protected set, with the standard service fees; plus an app-wide chain mechanism (own change/split outputs usable immediately, spent-guard, conflict recovery) so consecutive transactions never starve for funding
- BRC-100 (v3.2, fase 1–3): key-free window.CWI shim (detectable by @bsv/sdk WalletClient('auto')), fase-1 info methods, fase-2 keys/crypto via the bundled ProtoWallet behind BRC-43 grants with native biometric sheets, fase-3 money (outputs-only createAction, internalizeAction, listOutputs/listActions, relinquishOutput) with a per-transaction native confirmation (money ≠ grant) and a grants manager in Settings — every unsupported method rejects explicitly with a standards-shaped WERR_* error
- Service fees (**3,996 sats** across 11 outputs to 10 addresses) and fee rate (0.15 sat/byte) identical to the extension and the iOS app. The MCP server and ORDmail run a deliberately reduced **agent tier** at one tenth of this (396 sats over the same split), because an agent inscribes far more often than a person does.
- Auto-lock (5/15/60 min or never), lock button, wallet removal with double confirmation

## Verification

The JS engine is byte-identical to the iOS version and tested against the same test vectors and simulated chain (69 tests: BIP44/Trezor, WIF→address, send, inscribe + parser roundtrip, ordinal transfer incl. rejected foreign key, listing partial + purchase incl. rejected price mismatch, composed sendTx, plus the SNS-resolver section: both skill.md sighash test vectors, a live signed answer against the pinned key, 9 signed-field mutations and the rotation-deed chain; plus the fase-3 section: createAction validation refusals, build with a closing sats account, an AtomicBEEF round-trip and listOutputs pagination). Run with:

```
node Tests/engine-tests.mjs
```

The BRC-100 surface is additionally proven end-to-end with the REAL @bsv/sdk `WalletClient('auto')` against the app's actual `brc100-shim.js` (detection, fase-1 answers, deny/allow permission paths through the real ProtoWallet, and the rejection error contract):

```
npm i @bsv/sdk && node Tests/brc100-detect-test.mjs
```

## Play Store submission

- Version: 3.4.1 (versionCode 341), application id `io.ordnet.wallet` — see `CHANGELOG.md`
- `ic_launcher-playstore.png` (512×512) in the project root is the store listing icon
- `android:allowBackup="false"` + full backup/transfer exclusion: keys never leave the device
- Data safety form: no data collection, no tracking, no ads
- Required in the Play Console: privacy policy URL, "Financial features" declaration (crypto wallet), and review notes with a test wallet
- Note: crypto apps fall under Google Play's Financial Services policy — a developer account registered to an organization is recommended

## Security notes

- The vault (accounts + WIFs) is encrypted with AES-256-GCM under a **hardware-backed Android Keystore key** (key material never leaves the secure hardware) and every unlock/backup reveal is gated by **BiometricPrompt** (fingerprint/face, with device PIN as fallback — the same UX as Face ID + passcode).
- The Keystore key is deliberately **not biometrically bound**: Android invalidates biometrically bound keys as soon as the user adds a fingerprint, which would silently destroy the wallet. Hardware encryption + a mandatory biometric gate gives iOS-equivalent protection without that data-loss risk.
- No cloud backup, no device transfer (`data_extraction_rules.xml` excludes everything) — parity with iOS `ThisDeviceOnly`.
- Recovery phrases are never stored on disk — only the WIF (encrypted); phrases live in RAM for the session only.
- All errors are shown inline in the UI; the app never uses blocking alerts.

## Tests

```bash
node Tests/engine-tests.mjs
# -> 69 passed, 0 failed
```

The full crypto-engine suite on plain Node — the same vectors the iOS app
runs, against byte-identical engine files. CI additionally builds the app
(`./gradlew assembleDebug`) on every push. `Tests/brc100-detect-test.mjs`
requires `npm i @bsv/sdk`.

## License

Source-available — see [LICENSE](LICENSE). The code is published for
transparency, security review and audit. Copying, modification,
redistribution, or app-store submission is not permitted without written
permission from ORDnet.

Copyright (c) 2026 ORDnet / ODNCA
