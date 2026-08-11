# Changelog — ORDnet Wallet for Android (ORDplug Android)

All notable changes to the Android app, reconstructed from the 15 archived
build ZIPs in the `ORDPLUG ANDROID V1` and `ORDPLUG ANDROID V2` folders, based
on each build's bundled release notes and cross-checked against the code.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Dates are build dates taken from the archive files.

> Archive notes: `ORDnetAndroid.zip` is an early prototype (versionName 1.0.0)
> with its own native Kotlin crypto core — an approach later replaced by the
> shared JS engine. `ORDplug-Android-Fase1.zip` is the phase-1 delivery of the
> 1.8.0 port. `ORDplugAndroidv1.8.1.zip` is an intermediate build; the actual
> 1.8.1 release is `ORDplugAndroid-v1.8.1-fixed.zip`. `ORDnetWallet-v3.0.1.zip`
> is a repackaged Android Studio project snapshot of 3.0 (same versionName/
> versionCode; only IDE/build housekeeping differs). The Android app tracks the
> iOS app; most releases are explicit parity ports of iOS versions.

---

## [3.4] — 2026-08-11 (versionCode 340) — external security audit

An external review of all ORDnet repositories on 11 August 2026 reported four
high-severity findings in this app. Full detail in
[SECURITY-FIXES-audit-2026-08-11.md](SECURITY-FIXES-audit-2026-08-11.md).

### Security

- **H6 — the vault key required no authentication.** The Keystore key was
  created without `setUserAuthenticationRequired`, so the biometric prompt was
  UI only: any path that reached `readVault()` without calling `authenticate()`
  decrypted the wallet anyway. The key now requires authentication, with a
  short validity window (biometric or device credential) rather than a
  per-operation CryptoObject — unlocking once covers the operations that
  follow, while a cold read with no recent authentication is refused by the
  Keystore itself. `createWallet` and `importWallet` authenticate before
  saving; `saveAccounts` re-authenticates on `UserNotAuthenticatedException`.
- **H4 — BRC-100 trusted a page-supplied originator.** A page could pass
  `originator: "https://trusted.dapp"` and inherit that dApp's grants and
  budget. The originator is now the real origin of the active page, matching
  the `window.ordplug` path.
- **H7 — read methods had no per-origin consent.** `listActions` and
  `listOutputs` now require consent per origin; `relinquishOutput`, which is
  destructive, requires a confirmation on every call.

### Fixed

- Parity drift against iOS in account removal and wallet reset, found while
  fixing the above.

---


## [3.3] — 2026-08-06 (versionCode 330) — iOS v2.6.1/v2.6.2 parity

### Fixed
- Wallet ↔ Domains listing-sync: a domain listed for sale via the Domains tab
  (v2 domain registry, USD) showed "held" in the SNS holdings — two separate
  marketplaces, and the holdings only knew the ordinal listings (sats).
  Loading holdings now also fetches the wallet's own domain-registry listings
  (best effort, own try/catch): green "For sale · $X" pill, counted in the
  "For sale" tab, and the row menu jumps to "Manage domain listing".
  Deliberately no second (ordinal) listing for such a name.
- Upload: the success message after an inscribe existed in the code but was
  never visible (it lived in the file-selection section that is cleared right
  after success). Now a persistent "Inscribed successfully ✓" section with the
  full TXID (one tap = copy) and a pointer to the ORD/ner tab; only cleared
  when a new file/text is picked.
### Changed
- ORD/ner detail: one tap on the TXID, Origin or Current UTXO row copies the
  full value (long-press used to copy only the truncated display text), with
  an inline "copied ✓" confirmation; new "Copy TXID" button and full-width
  separators between the buttons.
- Upload layout (iOS v2.6.2): the "Selected" section sits directly under
  "Pick a file to inscribe"; the "Upload this text/HTML" button auto-scrolls
  to it, so the typed-text flow keeps working identically.
- Engine and tests unchanged (69/69 + 12/12). Note: the set-target `name`
  field fix from the same iOS handover had already shipped in 3.1.2/3.2.

## [3.2] — 2026-08-05 (versionCode 320) — iOS v2.3.0 → v2.6.0 parity

Port of four iOS releases in one Android update; the JS engine is again
byte-identical to iOS, now shipped alongside the bundled BSV SDK.

### Added
- ORD/ner tab (fifth tab): the on-chain file browser, native. Accounts are
  folders; a folder shows every inscription the address currently holds (1Sat
  index, paged up to 500) with grid/list view, thumbnails and type icons. File
  detail: preview, TXID/origin/current-outpoint with copy, "Open in Browser",
  "Copy TX info" and "Send" via the existing 1-sat transfer. Index down →
  degrades inline to the app's own inscription log. "Inscribed with this
  wallet" moved from Upload to ORD/ner, with "sent" labels + hide toggle.
- UTXO tools (Wallet screen top bar): split (N × X sats to your own address,
  2–200, live validation) and combine (all spendable UTXOs → one output),
  both on the ordinal-protected set, with the standard service fees.
- Chain mechanism app-wide: after every broadcast the wallet registers its own
  change/split outputs as immediately-spendable chain tips and guards the
  inputs it just spent — Send, Inscribe, ordinal transfers and the UTXO tools
  run back-to-back without "no spendable UTXOs". Tips persist per address, are
  validated on unlock/account switch; a mempool conflict drops the local chain
  with an inline retry message. 1-sat outputs are never chain tips.
- BRC-100 phase 1 — the wallet is detectable: a key-free `window.CWI` shim
  injected into every browser page; all 28 methods exist. Implemented:
  getVersion, getNetwork, getHeight, isAuthenticated/waitForAuthentication;
  every other method fails explicitly with a standards-shaped WalletError as a
  promise rejection. Keys stay in the engine, never in the page.
- BRC-100 phase 2 — keys & crypto behind native permission grants:
  getPublicKey (incl. identity key), encrypt, decrypt, createSignature,
  verifySignature, createHmac, verifyHmac — executed by the bundled SDK
  ProtoWallet inside the network-isolated engine (BRC-42/43 conform); key
  material never reaches the page and is wiped on lock. BRC-43 grant levels;
  first use shows a native sheet with biometrics on Allow; Deny returns
  `WERR_PERMISSION_DENIED`.
- BRC-100 phase 3 — money + grants manager: outputs-only `createAction` (all
  unsupported options refuse explicitly), built via the proven buildTx path
  and broadcast via the chain mechanism; money ≠ grant — every transaction its
  own native confirmation sheet with biometrics, never stored;
  `internalizeAction` (AtomicBEEF, direct wallet payments only, own incoming
  confirmation sheet); `listOutputs`/`listActions` with explicit refusals for
  anything the wallet doesn't track — never silently empty lists;
  `relinquishOutput`; `signAction`/`abortAction` refuse explicitly. Grants
  manager in Settings to view and revoke phase-2 grants per app (money is
  deliberately not listed there).
### Changed
- Navigation (user layout, iOS v2.3.2 parity): five tabs — Wallet · Browser ·
  Domains · Upload · ORD/ner; Settings and UTXO tools moved to the Wallet
  screen's top bar.
### Fixed
- Set-target on a root domain failed with `invalid_domain` (iOS v2.5.2
  parity): the app now sends the platform's canonical `name` field (keeping
  `domain` for compatibility); same fix for "Remove target".
### Tests
- Engine tests 39 → 69; new provider-detection suite runs the real SDK
  WalletClient against the actual Android shim (12/12).

## [3.1.2] — 2026-08-03 (versionCode 312) — iOS v2.2.3 parity

### Fixed
- No more false `stale_outpoint` refusals on busy holder addresses: the
  spent-check looked the outpoint up in the address's unspent list, which the
  block explorer silently truncates on busy addresses — absence proved
  nothing but was read as "spent". New check queries the outpoint's
  spent-status directly: spent / unspent / unknown (with backoff on rate
  limits); only a provably spent outpoint may produce a stale/spent message.
  SNS payments proceed on unknown with an inline note (the signed resolver
  answer is the authority); OpNS payments fail closed with an honest "could
  not verify — try again". The old address-list check is removed entirely, and
  the SNS path saves one network round-trip per resolve.

## [3.1.1] — 2026-08-03 (versionCode 311)

### Fixed
- The keyboard can be dismissed again: the confirm key on numeric keyboards
  did nothing and there was no other way to close the keyboard, which could
  block the Send button entirely. Single-line fields now advertise the Done
  action, every keyboard action without its own handler dismisses the
  keyboard, and tapping outside a text field clears focus. The browser address
  bar keeps its Go action; multiline fields keep Enter = newline.

## [3.1] — 2026-08-03 (versionCode 310) — iOS v2.1.0 + v2.2.x parity

### Added
- OpNS names as third holdings category: new "OpNS" segment on Home — bare
  names (no TLD) from the OpNS index, deliberately without the ✓ mark. Its own
  status flag and error handling: a broken OpNS API only affects the OpNS tab.
  Sending an OpNS name is the existing 1-sat ordinal transfer, with an inline
  warning that a paymail binding expires on transfer. No marketplace flows for
  OpNS (deliberately absent); display, resolve and send only.
- Paying **to** an OpNS name from Send under four hard rules: exact match only
  (a fallback answer becomes an inline "did you mean …?" error); outpoint
  checked unspent right before broadcast; holder address recomputed from the
  outpoint's on-chain locking script and required to match the index's claim;
  paymail forms rejected as payment target. Two-tap confirm always shows the
  exact name + verified holder address.
- SNS resolver payments: type `name.tld` or `mailbox@name.tld` in Send — the
  signed resolver answer is verified against a pre-pinned key inside the JS
  engine (both specification test vectors enforced; 9-way field-mutation and
  rotation-deed tests); the pay-to address is derived from the signed holder
  script (the unsigned address field is never trusted); 300-second expiry
  enforced, outpoint checked unspent right before broadcast, and two-tap
  confirm re-resolves at signing. Unknown mailbox (fallback) is an inline
  note, not an error; resolver errors arrive inline with readable messages;
  the TLD list is never hardcoded. Key rotation: succession-deed chain
  cryptographically verified from the pinned key; only a closing chain re-pins.
### Changed
- Two-row holdings picker (user design): row 1 SNS + OpNS, row 2 BSVmaps +
  For sale — no separator between the bars (iOS v2.2.2 layout parity); each
  segment shows "—" when its own index is unreachable.
- Engine updated to the iOS v2.2.2 engine (byte-identical); engine tests
  extended to 39.
### Security
- Recognition strictly separated: dotted names → SNS resolver, bare names →
  OpNS, anything else with @ → inline paymail refusal; ASCII-lowercase input
  by construction. All errors and notices inline — never popups/alerts.

## [3.0.1] — 2026-07-25

### Changed
- Repackaged Android Studio project snapshot of 3.0 (same versionName and
  versionCode; only IDE and build housekeeping differ — no functional
  changes).

## [3.0] — 2026-07-22 (versionCode 300)

### Changed
- Main-domain switch: domain management and the .web3 resolver now use the
  ORDnet v2 platform's main production domain (previously the staging alias,
  which keeps working). One constant — nothing else changed in the flows.
- App identity confirmed for release: the app ships as **ORDnet Wallet** with
  its own application id, deliberately distinct from the ORDnet Web3 Browser
  app.
- README and CHANGELOG fully in English.

## [2.1] — 2026-07-21

### Added
- Pagination everywhere: SNS names, BSVmaps and For sale — 20 per page with a
  pager bar above the list; switching tabs or typing a search jumps back to
  page 1. Domains tab: same pager (10 per page) plus a search field.
### Changed
- Browser: home and search buttons removed for a wider address field;
  navigation via Enter/Go on the keyboard.
- Domains/web3 moved onto the ORDnet v2 platform (list, whois, TXID/target,
  subdomains, routes, transfer and the browser resolver) — every call verified
  1-to-1 against the v2 registry server.

## [2.0.1] — 2026-07-18

### Fixed
- Holdings tab froze the app (ANR) for wallets holding thousands of BSVmaps:
  the eagerly-composed list is replaced by a single lazy list so rows compose
  on demand; duplicate entries from the indexer are de-duplicated by id so
  they can no longer crash the list.

## [2.0] — 2026-07-17

### Changed
- New app identity: the app is now called **ORDnet Wallet** everywhere, with a
  new application id (Kotlin package renamed to match). Note: the changed id
  makes this a new app on the device and in the Play Console — it does not
  update-in-place over a 1.x install.
- New launcher icon: the ORDnet segmented-donut mark on the warm-paper brand
  background as a proper adaptive icon (foreground/background layers plus a
  monochrome layer for Android 13+ themed icons, legacy PNGs for API 26) and a
  512×512 store-listing icon.
- Documentation translated to English.

## [1.8.1] — 2026-07-17

Bugfix release. (The archive's `v1.8.1.zip` is an intermediate build; the
release is the `-fixed` package.)

### Fixed
- Marketplace verification no longer reports false failures: list/delist
  verification checks the global registry with a propagation grace period
  (multiple attempts over ~30 s instead of a single zero-delay check), and an
  unreachable registry is no longer mistaken for an empty one.
- The UTXO fetch is honest about network failures: it now has the same
  rate-limit backoff as the tx-hex fetch and throws a clear
  "unreachable/rate-limited" error instead of silently returning an empty
  list — which used to surface as a misleading "No spendable UTXOs" on every
  send/inscribe.
- Removing an account before the active one no longer silently switches the
  wallet to a different account's keys.
- The engine can no longer deadlock permanently: if the WebView renderer dies
  mid-call, the call is aborted with a proper error instead of holding the
  engine mutex forever.
- dApp provider: a second request while one is awaiting approval is rejected
  cleanly; the approval sheet can no longer be swipe-dismissed while an action
  is executing; result injection now escapes line/paragraph separators and
  backslashes.
- Upload & Inscribe: preview images are downsampled and decoded off the main
  thread (no more ANR/OOM on large photos); bulk pre-selection respects the
  300-item cap.

## [1.8.0] — 2026-07-17 — first Android release

Complete native Android port of the iOS app v1.8.0 (itself a port of Chrome
extension V3.4). Functionality, colors, fonts and flows carried over 1-to-1.

### Added
- 100% Jetpack Compose UI with the ORDnet design system (warm paper / deep
  night, capsule buttons, inline alerts, status pills, segmented-donut logo).
- Crypto engine: the byte-identical shared JS engine in a network-isolated
  WebView JS VM (all 28 engine tests green) — transactions byte-for-byte
  identical to iOS and the extension.
- Wallet: create/import (BIP44, legacy, WIF, 11 wallet presets with address
  preview), multi-account, backup reveal.
- Send/Receive/History with the full safety layer, QR scanner and address
  book.
- Holdings (SNS/BSVmaps) with marketplace: list/delist, bulk (max 300),
  trust-but-verify and self-heal; ordinal transfers with ownership check and
  local input verification before broadcast.
- .web3 domain management: whois, set-target, subdomains, routes, USD
  marketplace, transfer.
- ORDnet Web3 Browser with an on-chain scheme router, security scanner, app
  catalog and `window.ordplug` dApp provider with native approval sheets.
- Upload & Inscribe with compression slider and per-wallet inscription log.
### Security
- Android Keystore (AES-256-GCM, hardware-backed) + BiometricPrompt,
  auto-lock, keys excluded from backups/device transfer.

## [1.8.0 phase 1] — 2026-07-16 (`ORDplug-Android-Fase1.zip`)

### Added
- Phase-1 delivery of the 1.8.0 port: project scaffold and wallet core as a
  1-to-1 port of the iOS app — the crypto engine is literally the same file
  pair as on iOS and in the extension, running in an invisible, network-less
  WebView; Android Keystore + BiometricPrompt key storage; backups excluded;
  same endpoints, backoff and tx-hex cache; errors always inline, never
  blocking alerts. The browser and dApp provider were scheduled as a later
  phase and completed in the 1.8.0 release.

## [1.0.0 prototype] — 2026-07-13 (`ORDnetAndroid.zip`)

### Added
- First ground-up native Android reimplementation of the Chrome extension
  (V34): wallet and .web3 browser in Kotlin + Jetpack Compose with a fully
  native Kotlin BSV crypto core (secp256k1 with deterministic ECDSA,
  BIP39/32/44, legacy derivation, WIF, P2PKH, FORKID signing, the 1Sat
  inscription envelope, message signing, PBKDF2, AES-256-GCM) verified against
  the extension's JS engine; encrypted vault in the extension's on-disk
  format; native `window.ordplug` provider and on-chain browser router.
### Changed
- This native-crypto approach was superseded three days later by the shared
  JS engine strategy (see 1.8.0 phase 1), chosen so all three platforms build
  byte-identical transactions from literally the same engine files.
