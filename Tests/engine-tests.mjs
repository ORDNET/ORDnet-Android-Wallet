// ORDnet Wallet Android engine verification — run with: node Tests/engine-tests.mjs
// Loads the exact same bsv.min.js + wallet-core.js that the app bundles and
// checks derivation vectors + every transaction builder against a simulated chain.
import { readFileSync } from 'fs';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';

const here = dirname(fileURLToPath(import.meta.url));
globalThis.window = globalThis; globalThis.self = globalThis;
(0, eval)(readFileSync(join(here, '../app/src/main/assets/engine/bsv.min.js'), 'utf8'));
(0, eval)(readFileSync(join(here, '../app/src/main/assets/engine/wallet-core.js'), 'utf8'));
// v2.4 — the @bsv/sdk bundle loads AFTER the classic engine, same order as
// WalletEngine boots them; both must coexist in one context
(0, eval)(readFileSync(join(here, '../app/src/main/assets/engine/bsv-sdk-bundle.js'), 'utf8'));

const C = globalThis.OrdplugCore;
let passed = 0, failed = 0;
function call(fn, args) {
  const r = JSON.parse(C[fn](JSON.stringify(args)));
  if (!r.ok) throw new Error(fn + ': ' + r.error);
  return r.result;
}
function check(name, cond) {
  if (cond) { passed++; console.log('  ✓', name); }
  else { failed++; console.log('  ✗ FAIL:', name); }
}

console.log('— derivation vectors —');
const classic = 'abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about';
check('validateMnemonic accepts the BIP39 test vector', call('validateMnemonic', { mnemonic: classic }) === true);
check('validateMnemonic rejects garbage', call('validateMnemonic', { mnemonic: 'foo bar baz' }) === false);
const wifBtc = call('mnemonicToWifPath', { mnemonic: classic, path: "m/44'/0'/0'/0/0" });
check("BIP44 m/44'/0'/0'/0/0 matches the Trezor vector",
  call('wifToAddress', { wif: wifBtc }) === '1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA');
check('key-1 WIF derives the known address',
  call('wifToAddress', { wif: 'KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn' }) === '1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH');
const bip44 = call('mnemonicToWifBip44', { mnemonic: classic });
const legacy = call('mnemonicToWifLegacy', { mnemonic: classic });
check('BIP44(236) and legacy derivations are deterministic and distinct',
  call('wifToAddress', { wif: bip44 }) !== call('wifToAddress', { wif: legacy }));
const gen = call('generateMnemonic', {});
check('generated mnemonic is 12 valid words', gen.split(' ').length === 12 && call('validateMnemonic', { mnemonic: gen }));

console.log('— fees (extension parity) —');
const fees = call('fees', { bytes: 1000 });
check('sendMinerFee = 97', fees.sendMinerFee === 97);
check('ordinalMinerFee = 117', fees.ordinalMinerFee === 117);
check('inscribeMinerFee(1000) = 255', fees.inscribeMinerFee === 255);
check('totalServiceFees = 3996', fees.totalServiceFees === 3996);

console.log('— transaction builders (simulated chain) —');
const wif = 'KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn';
const addr = call('wifToAddress', { wif });
const wif2 = call('randomWif', {});
const addr2 = call('wifToAddress', { wif: wif2 });

const pk = bsv.PrivateKey.fromWIF(wif);
const fund = new bsv.Transaction()
  .from(new bsv.Transaction.UnspentOutput({
    txid: '11'.repeat(32), outputIndex: 0, address: addr,
    script: bsv.Script.buildPublicKeyHashOut(pk.toAddress()).toHex(), satoshis: 200000
  }))
  .to(addr, 100000).to(addr, 50000);
fund.fee(500); fund.sign(pk);
const fundHex = fund.toString(), fundId = fund.id;

const utxos = call('shapeUtxos', {
  raw: JSON.stringify([
    { tx_hash: fundId, tx_pos: 0, value: 100000 },
    { tx_hash: fundId, tx_pos: 1, value: 50000 },
    { tx_hash: fundId, tx_pos: 2, value: 1 }
  ]), address: addr
});
check('shapeUtxos filters 1-sat ordinal UTXOs', utxos.length === 2);

const s = call('buildSend', { wif, utxos, to: addr2, amountSat: 10000 });
const stx = new bsv.Transaction(s.rawtx);
check('buildSend: signatures verify', stx.verify() === true);
check('buildSend: 11 service-fee outputs sum to 3996',
  stx.outputs.slice(1, 12).reduce((a, o) => a + o.satoshis, 0) === 3996);

const insc = call('buildInscribe', { wif, utxos, contentType: 'text/html', dataB64: Buffer.from('<html><b>hi</b></html>').toString('base64') });
const itx = new bsv.Transaction(insc.rawtx);
check('buildInscribe: 1-sat envelope-first output at vout 0',
  itx.outputs[0].satoshis === 1 && itx.outputs[0].script.toHex().startsWith('0063'));
const ext = call('extractOrd', { rawTxHex: insc.rawtx, vout: 0 });
check('extractOrd roundtrip (content-type + data)',
  ext.ct === 'text/html' && Buffer.from(ext.dataB64, 'base64').toString() === '<html><b>hi</b></html>');

const ordScriptHex = call('outputScriptHex', { rawTxHex: insc.rawtx, vout: 0 });
check('scriptLockAddress identifies the owner', call('scriptLockAddress', { scriptHex: ordScriptHex }) === addr);
const funding = call('selectFunding', { utxos, requiredSat: fees.ordinalMinerFee + fees.totalServiceFees });
funding.forEach(u => u.realScriptHex = call('outputScriptHex', { rawTxHex: fundHex, vout: u.vout }));
const itxid = call('txidOf', { rawtx: insc.rawtx });
const tr = call('buildOrdinalTransfer', { wif, ordTxid: itxid, ordVout: 0, ordScriptHex, funding, to: addr2 });
check('buildOrdinalTransfer: built + locally verified', typeof tr.rawtx === 'string' && tr.rawtx.length > 200);
let rejected = false;
try { call('buildOrdinalTransfer', { wif: wif2, ordTxid: itxid, ordVout: 0, ordScriptHex, funding, to: addr2 }); }
catch (e) { rejected = /locked to/.test(e.message); }
check('buildOrdinalTransfer: rejects a foreign key with a clear message', rejected);

const lp = call('buildListingPartial', { wif, ordTxid: itxid, ordVout: 0, ordScriptHex, priceSat: 5000 });
check('buildListingPartial: partial tx + pay script', lp.partialTx.length > 100 && lp.payScriptHex.startsWith('76a914'));
const fund3 = call('selectFunding', { utxos, requiredSat: 5000 + 1 + fees.ordinalMinerFee + fees.totalServiceFees });
fund3.forEach(u => u.realScriptHex = call('outputScriptHex', { rawTxHex: fundHex, vout: u.vout }));
const buy = call('buildPurchaseFromPartial', { wif, partialHex: lp.partialTx, priceSat: 5000, sellerAddress: addr, payScriptHex: lp.payScriptHex, funding: fund3 });
const btx = new bsv.Transaction(buy.rawtx);
check('buildPurchaseFromPartial: seller paid + ordinal to buyer',
  btx.outputs[0].satoshis === 5000 && btx.outputs[1].satoshis === 1);
let priceRejected = false;
try { call('buildPurchaseFromPartial', { wif, partialHex: lp.partialTx, priceSat: 4999, sellerAddress: addr, payScriptHex: lp.payScriptHex, funding: fund3 }); }
catch (e) { priceRejected = /refusing/.test(e.message); }
check('buildPurchaseFromPartial: refuses a price mismatch', priceRejected);

const bt = call('buildTx', {
  wif, utxos, params: {
    outputs: [
      { type: 'inscription', address: addr2, contentType: 'text/plain', data: 'ORDMAP 581319', satoshis: 1 },
      { type: 'p2pkh', address: addr2, satoshis: 2000 },
      { type: 'opreturn', data: ['bsvmap claim 581319'] }
    ]
  }
});
const bttx = new bsv.Transaction(bt.rawtx);
check('buildTx: inscription stays at vout 0', bttx.outputs[0].script.toHex().startsWith('0063'));
check('buildTx: caller outputs + 11 service fees + change', bttx.outputs.length === 15);

console.log('— signing formats (server-verified) —');
const act = call('signAction', { wif, address: addr, action: 'set-target', fields: ['test.web3', 'ab'.repeat(32), '0'], ts: 1700000000000 });
check('signAction message format matches ordnet-registry|action|…|ts',
  act.message === 'ordnet-registry|set-target|test.web3|' + 'ab'.repeat(32) + '|0|1700000000000');
check('delistMessage format matches',
  call('delistMessage', { district: 581319, ordinalTxid: itxid, ordinalVout: 0, ts: 1700000000000 })
  === 'bsvmap delist 581319 ' + itxid + '_0 1700000000000');
const sm = call('signMessage', { wif, message: 'hello' });
check('signMessage returns DER signature + pubkey', /^30/.test(sm.signature) && /^0[23]/.test(sm.pubkey));
check('scanSecurity flags eval(atob)', call('scanSecurity', { html: 'eval(atob(x))' }) === 2);
check('purchaseMessage format', call('purchaseMessage', { shop: 's', itemTitle: 'i', orderId: 'o', amountSat: 42, to: addr })
  === 'ORDPAY/v1 | shop:s | item:i | order:o | amount:42 sats | to:' + addr);

console.log('— UTXO tools + chain bookkeeping (v2.3) —');
// splitter goes through the existing buildTx (N p2pkh outputs to self)
const split = call('buildTx', { wif, utxos, params: { outputs: [
  { type: 'p2pkh', address: addr, satoshis: 5000 },
  { type: 'p2pkh', address: addr, satoshis: 5000 },
  { type: 'p2pkh', address: addr, satoshis: 5000 }
] } });
const splitTx = new bsv.Transaction(split.rawtx);
check('split via buildTx: 3 equal outputs to self + 11 service fees',
  splitTx.outputs.slice(0, 3).every(o => o.satoshis === 5000)
  && splitTx.outputs.slice(3, 14).reduce((a, o) => a + o.satoshis, 0) === 3996);
const spendInfo = call('txSpendInfo', { rawtx: split.rawtx, address: addr });
check('txSpendInfo: reports spent inputs + own >1-sat outputs (split + change), never 1-sat ordinals',
  spendInfo.txid === splitTx.id
  && spendInfo.inputs.length === splitTx.inputs.length
  && spendInfo.ownOutputs.filter(o => o.satoshis === 5000).length === 3
  && spendInfo.ownOutputs.every(o => o.satoshis > 1));
const insSpend = call('txSpendInfo', { rawtx: insc.rawtx, address: addr });
check('txSpendInfo on an inscribe: the 1-sat ordinal output is NOT a spendable tip',
  insSpend.ownOutputs.every(o => o.satoshis > 1));
const cons = call('buildConsolidate', { wif, utxos });
const consTx = new bsv.Transaction(cons.rawtx);
check('buildConsolidate: all inputs -> 1 output to self + service fees, signatures verify',
  consTx.inputs.length === utxos.length && consTx.outputs[0].satoshis === cons.outputSat
  && consTx.outputs.length === 12 && consTx.verify() === true);
let oneUtxoRejected = false;
try { call('buildConsolidate', { wif, utxos: [utxos[0]] }); }
catch (e) { oneUtxoRejected = /only one spendable/.test(e.message); }
check('buildConsolidate: refuses a single-UTXO combine with a clear message', oneUtxoRejected);

console.log('— SNS resolver verification (skill.md test vectors are the referee) —');
// test vector 1: resolve answer (skill.md §6)
const tvFields = ['1','ordnet.web3','alexander','76a914e8e5f64b0c7943b93e58b24e3f82d533e70b3db188ac',
  '367a0a1d553002f0f3427168a10f86835e2741c111df43262d35fb475400e3ee','0',
  'dc54c20af97682eebf99dc8392c21b904908398d543aae6fabffe09a9b7780ac','0',
  '959941','true','1785312000'];
check('answer sighash reproduces test vector 28a4252e…ec6b',
  call('snsSighash', { prefix: 'ORDNS-RESOLVE', fields: tvFields })
  === '28a4252e92fdcdb70d6fd287cdb602cda504d288963e106b47a6d8d19420ec6b');
// test vector 2: rotation deed (skill.md §6, v1.3)
check('rotation sighash reproduces test vector ddc9eefe…cb31',
  call('snsSighash', { prefix: 'ORDNS-KEYROTATE', fields: ['1','1',
    '034f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa',
    '02466d7fcae563e5cb09a0d1870bb580344804617879a14949cf22285f1bae3f27','1785500000'] })
  === 'ddc9eefe6e0097a6312171f0dad76b6822e08f31498bd7f47f51ba163481cb31');

// live answer captured from https://sns.ordnet.io/resolve/ditiseentest.web3 on 03-08-2026
const liveAnswer = {"ok":true,"v":1,"input":"ditiseentest.web3","name":"ditiseentest.web3","mailbox":"","source":"sns","fallback":false,
  "holder_address":"1NdU53DPAv7ftxoWDpM9c5P4nx1hFnJ6Ry","holder_script":"76a914ed403671607a9d077082219581c5328b8fa2d55088ac",
  "origin":{"txid":"5bebe49ade63904afd9ff4afb6b2562897b788c5680fba5f37cbbbe47897948f","vout":0},
  "current":{"txid":"5bebe49ade63904afd9ff4afb6b2562897b788c5680fba5f37cbbbe47897948f","vout":0},
  "as_of_height":960687,"expires":1785764663,
  "sig":"3045022100916f0d3855b83d045383ee1fe2d0b5c0719d3c956e35c00dc695c913677066cd02203d4614479e1d8704c682dc56507b1cee2fec6fd5dacb579679ffb8060b78092d",
  "signer":"03088f1da3bfc998c1bc7bbc1ffcb7d96c47e094624a52d78406f8c3105b0d0b46"};
const PIN = '03088f1da3bfc998c1bc7bbc1ffcb7d96c47e094624a52d78406f8c3105b0d0b46';
const okV = call('snsVerifyAnswer', { answerJson: JSON.stringify(liveAnswer), expectedSigner: PIN, nowTs: 1785764000 });
check('live answer verifies against the pinned key', okV.valid === true);
check('holder address is DERIVED from the signed script',
  okV.holderAddress === '1NdU53DPAv7ftxoWDpM9c5P4nx1hFnJ6Ry' && okV.addressMismatch === false);
check('expired answer is rejected',
  call('snsVerifyAnswer', { answerJson: JSON.stringify(liveAnswer), expectedSigner: PIN, nowTs: 1785764664 }).valid === false);
check('unknown signer is flagged for the rotation path',
  call('snsVerifyAnswer', { answerJson: JSON.stringify({ ...liveAnswer, signer: '02' + 'ab'.repeat(32) }), expectedSigner: PIN, nowTs: 1785764000 }).reason === 'unknown_signer');
// manipulation test: every SIGNED field flipped individually must fail
const mutations = [
  ['name', 'ordnet.web3'], ['mailbox', 'x'], ['holder_script', '76a914' + '00'.repeat(20) + '88ac'],
  ['fallback', true], ['as_of_height', 960688], ['expires', 1785764664 + 999],
  ['origin', { txid: '11'.repeat(32), vout: 0 }], ['current', { txid: '22'.repeat(32), vout: 1 }], ['v', 2]
];
let allRejected = true;
for (const [k, val] of mutations) {
  const m = { ...liveAnswer, [k]: val };
  const r = call('snsVerifyAnswer', { answerJson: JSON.stringify(m), expectedSigner: PIN, nowTs: 1785764000 });
  if (r.valid !== false) { allRejected = false; console.log('    ! mutation survived:', k); }
}
check('every mutated signed field breaks verification (9 mutations)', allRejected);
// unsigned fields may differ without breaking the signature — mismatch is flagged
const mm = call('snsVerifyAnswer', { answerJson: JSON.stringify({ ...liveAnswer, holder_address: '1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH' }), expectedSigner: PIN, nowTs: 1785764000 });
check('unsigned holder_address mismatch is flagged, signed script wins',
  mm.valid === true && mm.addressMismatch === true && mm.holderAddress === '1NdU53DPAv7ftxoWDpM9c5P4nx1hFnJ6Ry');

// rotation chain: build a real signed deed with fresh keys, prove it, tamper it
const oldPk = bsv.PrivateKey.fromRandom(), newPk = bsv.PrivateKey.fromRandom();
const oldPub = oldPk.toPublicKey().toString().toLowerCase(), newPub = newPk.toPublicKey().toString().toLowerCase();
const rotHash = call('snsSighash', { prefix: 'ORDNS-KEYROTATE', fields: ['1', '1', oldPub, newPub, '1785500000'] });
const rotSig = bsv.crypto.ECDSA.sign(bsv.deps.Buffer.from(rotHash, 'hex'), oldPk).toString();
const deed = { rv: 1, seq: 1, old_pub: oldPub, new_pub: newPub, valid_from: 1785500000, sig: rotSig };
check('valid succession deed re-pins to the new key',
  call('snsVerifyRotationChain', { pinnedPub: oldPub, records: [deed] }) === newPub);
let rotRejected = false;
try { call('snsVerifyRotationChain', { pinnedPub: oldPub, records: [{ ...deed, new_pub: '02' + 'cd'.repeat(32) }] }); }
catch (e) { rotRejected = /invalid signature/.test(e.message); }
check('tampered deed is refused, pin untouched', rotRejected);
let chainRejected = false;
try { call('snsVerifyRotationChain', { pinnedPub: '02' + 'ef'.repeat(32), records: [deed] }); }
catch (e) { chainRejected = /does not connect/.test(e.message); }
check('deed that does not connect to the pin is refused', chainRejected);

console.log('— BRC-100 engine bundle (v2.4) —');
check('BSVSDK bundle coexists with bsv.min + wallet-core in one context',
  typeof globalThis.BSVSDK === 'object' && typeof bsv === 'object' && typeof C === 'object');
const kd = new globalThis.BSVSDK.KeyDeriver(globalThis.BSVSDK.PrivateKey.fromWif('KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn'));
check('KeyDeriver reproduces the reference vector next to the classic engine',
  kd.derivePublicKey([1, 'ordnet tests'], 'key 1',
    globalThis.BSVSDK.PrivateKey.fromWif('L1aW4aubDFB7yfras2S1mN3bqg9nwySY8nkoLmJebSLD5BWv3ENZ').toPublicKey().toString()
  ).toString() === '03535be391a7179f3f424bd6aed620edcd73b90684311b84eab799fd84a601fff3');

console.log('— BRC-100 fase 2: engine start/poll bridge (v2.5) —');
const drain = () => new Promise(r => setImmediate(r));
const asyncCall = async (method, args) => {
  const callId = 'test-' + method + '-' + Math.random();
  call('brc100Start', { callId, method, argsJson: JSON.stringify(args) });
  await drain();
  const r = call('brc100Poll', { callId });
  if (!r.done) throw new Error('poll not done');
  if (!r.ok) { const e = new Error(r.error.message); e.name = r.error.name; throw e; }
  return r.result;
};
const MASTER = 'KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn';
const CPTY_WIF = 'L1aW4aubDFB7yfras2S1mN3bqg9nwySY8nkoLmJebSLD5BWv3ENZ';
call('brc100Init', { wif: MASTER });
const cptyPub = globalThis.BSVSDK.PrivateKey.fromWif(CPTY_WIF).toPublicKey().toString();
const identity = new globalThis.BSVSDK.KeyDeriver(globalThis.BSVSDK.PrivateKey.fromWif(MASTER)).identityKey.toString();

const gp = await asyncCall('getPublicKey', { identityKey: true });
check('getPublicKey({identityKey}) == KeyDeriver.identityKey (stable)', gp.publicKey === identity);

// cross-check against an INDEPENDENT ProtoWallet as the counterparty
const cptyWallet = new globalThis.BSVSDK.ProtoWallet(globalThis.BSVSDK.PrivateKey.fromWif(CPTY_WIF));
const encR = await asyncCall('encrypt', { plaintext: [7,7,7,42], protocolID: [1, 'ordnet tests'], keyID: 'key 1', counterparty: cptyPub });
const decR = await cptyWallet.decrypt({ ciphertext: encR.ciphertext, protocolID: [1, 'ordnet tests'], keyID: 'key 1', counterparty: identity });
check('engine encrypt -> counterparty ProtoWallet decrypt', JSON.stringify(decR.plaintext) === JSON.stringify([7,7,7,42]));

const sigR = await asyncCall('createSignature', { data: [1,2,3], protocolID: [1, 'ordnet tests'], keyID: 'sig 1', counterparty: cptyPub });
const verR = await cptyWallet.verifySignature({ data: [1,2,3], signature: sigR.signature, protocolID: [1, 'ordnet tests'], keyID: 'sig 1', counterparty: identity });
check('engine createSignature -> counterparty verifySignature', verR.valid === true);

let engineRejected = false;
try { await asyncCall('createAction', {}); } catch (e) { engineRejected = /Unsupported BRC-100 engine method/.test(e.message); }
check('engine bridge refuses non-fase-2 methods explicitly', engineRejected);

call('brc100Reset', {});
let afterReset = false;
try { await asyncCall('getPublicKey', { identityKey: true }); } catch (e) { afterReset = /not initialised/.test(e.message); }
check('brc100Reset wipes key material (lock semantics)', afterReset);

console.log('— BRC-100 fase 3: geld (v2.6) — validatie, bouw, internalize, listOutputs —');
{
  const S = globalThis.BSVSDK;
  const wifM = 'KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn';
  const addrM = call('wifToAddress', { wif: wifM });
  const p2pkhHex = bsv.Script.buildPublicKeyHashOut(bsv.Address.fromString(addrM)).toHex();

  const vErr = (args) => call('brc100ValidateCreate', { argsJson: JSON.stringify(args) });
  const goodOut = { satoshis: 1500, lockingScript: p2pkhHex, outputDescription: 'test output one' };

  let r = vErr({ description: 'kort', outputs: [goodOut] });
  check('createAction: te korte description -> WERR_INVALID_PARAMETER',
    r.valid === false && r.werr.name === 'WERR_INVALID_PARAMETER');
  r = vErr({ description: 'geldige omschrijving', inputs: [{}], outputs: [goodOut] });
  check('createAction: custom inputs -> WERR_UNSUPPORTED_ACTION (regel 1)',
    r.valid === false && r.werr.name === 'WERR_UNSUPPORTED_ACTION');
  r = vErr({ description: 'geldige omschrijving', outputs: [{ ...goodOut, lockingScript: 'zz' }] });
  check('createAction: ongeldige scripthex -> WERR_INVALID_PARAMETER',
    r.valid === false && /lockingScript/.test(r.werr.message));
  r = vErr({ description: 'geldige omschrijving', outputs: [{ ...goodOut, satoshis: 0 }] });
  check('createAction: 0-sat output -> geweigerd als dust',
    r.valid === false && /satoshis/.test(r.werr.message));
  r = vErr({ description: 'geldige omschrijving', outputs: [{ ...goodOut, basket: 'todo tokens' }] });
  check('createAction: output-basket -> expliciet geweigerd (geen basket-boekhouding)',
    r.valid === false && /basket/.test(r.werr.message));
  r = vErr({ description: 'geldige omschrijving', outputs: [goodOut], options: { noSend: true } });
  check('createAction: options.noSend -> expliciet geweigerd',
    r.valid === false && /noSend/.test(r.werr.message));

  r = vErr({ description: 'betaling aan testadres', outputs: [goodOut, { ...goodOut, satoshis: 500 }], labels: ['Fase3', 'test'] });
  check('createAction: geldige args -> genormaliseerd (totaal, dest, labels lowercase)',
    r.valid === true && r.totalSat === 2000 && r.outputs[0].dest === addrM &&
    JSON.stringify(r.labels) === JSON.stringify(['fase3', 'test']) &&
    r.serviceFees === 3996 && r.randomizeOutputs === true);

  // bouw op de gesimuleerde keten (zelfde patroon als de andere builders)
  const pkM = bsv.PrivateKey.fromWIF(wifM);
  const fundTx = new bsv.Transaction()
    .from(new bsv.Transaction.UnspentOutput({
      txid: '33'.repeat(32), outputIndex: 0, address: addrM,
      script: bsv.Script.buildPublicKeyHashOut(bsv.Address.fromString(addrM)), satoshis: 60000 }))
    .to(addrM, 50000).sign(pkM);
  const fundUtxo = [{ txid: fundTx.id, vout: 0, satoshis: 50000,
    script: bsv.Script.buildPublicKeyHashOut(bsv.Address.fromString(addrM)).toHex() }];
  const built = call('brc100BuildCreate', { wif: wifM, utxos: JSON.stringify(fundUtxo),
    argsJson: JSON.stringify({ description: 'betaling aan testadres', outputs: [goodOut] }) });
  const btx = new bsv.Transaction(built.rawtx);
  check('brc100BuildCreate: txid consistent met rawtx', built.txid === btx.id);
  check('brc100BuildCreate: actie-output + 11 servicefees + change aanwezig',
    btx.outputs.length === 1 + 11 + 1);
  const spendInfo = btx.outputs.reduce((s, o) => s + o.satoshis, 0);
  check('brc100BuildCreate: in = uit + minerfee (sluitende rekening)',
    50000 - spendInfo === built.fee && built.fee > 0);
  check('brc100BuildCreate: servicefees exact volgens app-beleid', built.serviceFees === 3996);

  // internalizeAction: AtomicBEEF-rondje via de uitgebreide SDK-bundel
  const lockM = new S.P2PKH().lock(addrM);
  const rootS = new S.Transaction(); rootS.addOutput({ lockingScript: lockM, satoshis: 9000 });
  const payS = new S.Transaction();
  payS.addInput({ sourceTransaction: rootS, sourceOutputIndex: 0,
    unlockingScriptTemplate: new S.P2PKH().unlock(S.PrivateKey.fromWif(wifM)) });
  payS.addOutput({ lockingScript: lockM, satoshis: 5000 });
  await payS.sign();
  const beefBytes = Array.from(payS.toAtomicBEEF());
  let ir = call('brc100ParseInternalize', { address: addrM, argsJson: JSON.stringify({
    description: 'inkomende testbetaling', tx: beefBytes,
    outputs: [{ outputIndex: 0, protocol: 'wallet payment' }] }) });
  check('internalizeAction: AtomicBEEF geparsed, output aan wallet-adres geaccepteerd',
    ir.valid === true && ir.totalSat === 5000 && ir.txid === payS.id('hex'));
  ir = call('brc100ParseInternalize', { address: addrM, argsJson: JSON.stringify({
    description: 'inkomende testbetaling', tx: beefBytes,
    outputs: [{ outputIndex: 0, protocol: 'basket insertion' }] }) });
  check('internalizeAction: basket insertion -> expliciet geweigerd',
    ir.valid === false && /basket/.test(ir.werr.message));
  const addrX = call('wifToAddress', { wif: call('randomWif', {}) });
  ir = call('brc100ParseInternalize', { address: addrX, argsJson: JSON.stringify({
    description: 'inkomende testbetaling', tx: beefBytes,
    outputs: [{ outputIndex: 0, protocol: 'wallet payment' }] }) });
  check('internalizeAction: output aan vreemd adres -> expliciet geweigerd (geen BRC-29 stil)',
    ir.valid === false && /derived|address/.test(ir.werr.message));
  ir = call('brc100ParseInternalize', { address: addrM, argsJson: JSON.stringify({
    description: 'inkomende testbetaling', tx: [1,2,3],
    outputs: [{ outputIndex: 0, protocol: 'wallet payment' }] }) });
  check('internalizeAction: kapotte BEEF -> WERR_INVALID_PARAMETER',
    ir.valid === false && ir.werr.name === 'WERR_INVALID_PARAMETER');

  // listOutputs over de live UTXO-set
  const many = Array.from({ length: 15 }, (_, i) => ({ txid: 'aa'.repeat(32), vout: i, satoshis: 1000 + i, script: p2pkhHex }));
  let lo = call('brc100ListOutputs', { utxos: JSON.stringify(many), argsJson: JSON.stringify({}) });
  check('listOutputs: default basket, paginering default 10',
    lo.valid === true && lo.totalOutputs === 15 && lo.outputs.length === 10 &&
    lo.outputs[0].outpoint === 'aa'.repeat(32) + '.0' && lo.outputs[0].spendable === true);
  lo = call('brc100ListOutputs', { utxos: JSON.stringify(many), argsJson: JSON.stringify({ limit: 5, offset: 12, include: 'locking scripts' }) });
  check('listOutputs: limit/offset + locking scripts',
    lo.valid === true && lo.outputs.length === 3 && lo.outputs[0].lockingScript === p2pkhHex);
  lo = call('brc100ListOutputs', { utxos: JSON.stringify(many), argsJson: JSON.stringify({ basket: 'todo tokens' }) });
  check('listOutputs: vreemde basket -> expliciet geweigerd (geen stille lege lijst)',
    lo.valid === false && lo.werr.name === 'WERR_INVALID_PARAMETER');
}

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
