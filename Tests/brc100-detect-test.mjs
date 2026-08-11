// ORDplug BRC-100 — contract-/detectietest (testplan stap 2)
// Draait de ECHTE @bsv/sdk WalletClient('auto') tegen onze eigen
// brc100-shim.js, met de Kotlin-kant (Brc100.handle) 1-op-1 gesimuleerd.
// Bewijst: (a) detectie via window.CWI, (b) fase-1 antwoorden,
// (c) het foutcontract — refusals zijn REJECTIONS met WERR_*, nooit succes.
//
// Vereist: npm i @bsv/sdk   (of NODE_PATH naar een map waar die staat)
// Draaien: node Tests/brc100-detect-test.mjs
import { readFileSync } from 'fs'
import { dirname, join } from 'path'
import { fileURLToPath } from 'url'

const here = dirname(fileURLToPath(import.meta.url))
let passed = 0, failed = 0
const check = (n, c) => { c ? (passed++, console.log('  ✓', n)) : (failed++, console.log('  ✗ FAIL:', n)) }

// ---- browseromgeving nabootsen + Swift-kant simuleren ----
globalThis.window = globalThis
globalThis.location = { origin: 'https://test.ordnet.io' }

// exact de logica van core/Brc100.kt (fase 1 + fase 2 + expliciete
// fouten). De permissielaag wordt gesimuleerd via `simulateDeny`; de crypto
// draait via de ECHTE ProtoWallet uit @bsv/sdk, zoals in de app-engine.
let simulateDeny = false
let protoWallet = null
const PHASE2 = ['getPublicKey', 'encrypt', 'decrypt', 'createSignature', 'verifySignature', 'createHmac', 'verifyHmac']
async function swiftHandle (method, args) {
  switch (true) {
    case method === 'getVersion': return { version: 'ordplug-1.0.0' }
    case method === 'getNetwork': return { network: 'mainnet' }
    case method === 'getHeight': return { height: 960799 }   // in de app: Api.chainHeight()
    case method === 'isAuthenticated':
    case method === 'waitForAuthentication': return { authenticated: true }
    case PHASE2.includes(method): {
      if (simulateDeny) {
        throw { name: 'WERR_PERMISSION_DENIED', code: 1, message: `The user denied ${method} for test.ordnet.io.` }
      }
      return await protoWallet[method](args)   // in de app: engine.callBrc100
    }
    case method === 'revealCounterpartyKeyLinkage':
    case method === 'revealSpecificKeyLinkage':
      throw { name: 'WERR_UNSUPPORTED_ACTION', code: 2, message: `${method} is privacy-sensitive and not supported by the ORDnet wallet.` }
    default:
      throw { name: 'WERR_UNSUPPORTED_ACTION', code: 2, message: `${method} is not yet supported by the ORDnet wallet.` }
  }
}

// Android-bridge: addJavascriptInterface-object OrdplugBrc100Android dat één
// JSON-string ontvangt — exact het transport van de Android-shim
globalThis.OrdplugBrc100Android = { postMessage (json) {
  const msg = JSON.parse(json)
  // asynchroon, zoals de echte bridge
  queueMicrotask(async () => {
    try {
      const result = await swiftHandle(msg.method, JSON.parse(msg.args || '{}'))
      globalThis.window.__brc100Deliver({ id: msg.id, ok: true, result })
    } catch (e) {
      globalThis.window.__brc100Deliver({ id: msg.id, ok: false, error: { name: e.name, code: e.code, message: e.message } })
    }
  })
} }

// ---- de ECHTE shim laden die de app injecteert ----
;(0, eval)(readFileSync(join(here, '../app/src/main/assets/brc100-shim.js'), 'utf8'))
check('shim definieert window.CWI met alle 28 methodes',
  typeof globalThis.window.CWI === 'object' && Object.keys(globalThis.window.CWI).length === 28)

// ---- de ECHTE client uit @bsv/sdk ----
const { WalletClient } = await import('@bsv/sdk')
const client = new WalletClient('auto', 'test.ordnet.io')
await client.connectToSubstrate()
check("WalletClient('auto') detecteert de wallet via window.CWI",
  client.substrate?.constructor?.name === 'WindowCWISubstrate')

const v = await client.getVersion()
check('getVersion -> {version: string}', typeof v.version === 'string' && v.version === 'ordplug-1.0.0')
const n = await client.getNetwork({})
check('getNetwork -> mainnet', n.network === 'mainnet')
const h = await client.getHeight({})
check('getHeight -> hoogte', typeof h.height === 'number' && h.height > 0)
const a = await client.isAuthenticated({})
check('isAuthenticated -> true', a.authenticated === true)

// ---- fase 2 (v2.5): keys/crypto via de echte ProtoWallet + permissiecontract ----
const { ProtoWallet, PrivateKey, KeyDeriver } = await import('@bsv/sdk')
const masterKey = PrivateKey.fromWif('KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn')
protoWallet = new ProtoWallet(masterKey)
const refIdentity = new KeyDeriver(masterKey).identityKey.toString()

// deny-pad: eerste gebruik zonder grant + gebruiker weigert
simulateDeny = true
let denied = false, denyName = ''
try { await client.getPublicKey({ identityKey: true }) }
catch (e) { denied = true; denyName = e.name }
check('geweigerde permissie REJECT met WERR_PERMISSION_DENIED (nooit succes)',
  denied && denyName === 'WERR_PERMISSION_DENIED')

// allow-pad: grant aanwezig -> echte ProtoWallet-resultaten door de hele keten
simulateDeny = false
const pk = await client.getPublicKey({ identityKey: true })
check('getPublicKey({identityKey}) levert de stabiele identiteitssleutel',
  pk.publicKey === refIdentity)
const pk2 = await client.getPublicKey({ identityKey: true })
check('identiteitssleutel is deterministisch (tweede aanroep identiek)', pk2.publicKey === pk.publicKey)

const cpty = new ProtoWallet(PrivateKey.fromWif('L1aW4aubDFB7yfras2S1mN3bqg9nwySY8nkoLmJebSLD5BWv3ENZ'))
const encd = await client.encrypt({ plaintext: [3,1,4,1,5], protocolID: [1, 'ordnet tests'], keyID: 'key 1',
  counterparty: PrivateKey.fromWif('L1aW4aubDFB7yfras2S1mN3bqg9nwySY8nkoLmJebSLD5BWv3ENZ').toPublicKey().toString() })
const decd = await cpty.decrypt({ ciphertext: encd.ciphertext, protocolID: [1, 'ordnet tests'], keyID: 'key 1', counterparty: refIdentity })
check('client.encrypt -> tegenpartij decrypt door de hele keten', JSON.stringify(decd.plaintext) === JSON.stringify([3,1,4,1,5]))

// foutcontract: niet-geïmplementeerde methode = REJECTION met WERR_*
let rejected = false, rejName = '', rejCode = 0
try { await client.getHeaderForHeight({ height: 1 }) }
catch (e) { rejected = true; rejName = e.name; rejCode = e.code }
check('niet-geïmplementeerde methode REJECT met WERR_UNSUPPORTED_ACTION (code 2)',
  rejected && rejName === 'WERR_UNSUPPORTED_ACTION' && rejCode === 2)

let privRejected = false
try { await client.revealCounterpartyKeyLinkage({}) }
catch (e) { privRejected = /privacy-sensitive/.test(e.message) }
check('linkage-methodes weigeren expliciet als privacygevoelig', privRejected)

console.log(`\n${passed} geslaagd, ${failed} gefaald`)
process.exit(failed ? 1 : 0)
