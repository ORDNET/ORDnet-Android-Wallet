# Security fixes — ORDnet Android Wallet v3.4.1

**Audit:** external GitHub review of 13 August 2026
**Supersedes:** v3.4 (versionCode 340)

## Escaping drift in flushPendingFragment

`deliver()` and `deliverBrc100()` escape U+2028/U+2029 before handing JSON to
`evaluateJavaScript`. `flushPendingFragment` (`BrowserView.kt:416`) does not —
even though iOS fixed exactly this path in v2.7.0 with the note *"Android
escaped this; iOS did not."* The drift now runs the other way.

The fragment arrives from the page in an `ordnetNavigate` message, so it is
attacker-controlled input reaching a JavaScript string literal. Same class as
the iOS H5 finding.

**Now** the same escaping helper covers all three delivery paths.

## Still open

Native test coverage. The 69 tests are JavaScript engine tests; there are no
Kotlin unit tests, so `Vault`, the biometric flow and the WebView bridges have
no automated coverage. The CI workflow added in this release runs
`./gradlew assembleDebug`, which at least catches what a missing brace in
`Vault.kt` cost on 11 August.
