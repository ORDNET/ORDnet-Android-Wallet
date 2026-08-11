/* =========================================================================
   ORD/plug Wallet Core — iOS JavaScriptCore edition (ported from V11/V3.4)
   Pure, network-free crypto engine. All functions are synchronous; all
   network I/O (UTXO fetch, tx-hex fetch, broadcast) is done in Swift and
   the results are passed in. The transaction-building logic is copied
   VERBATIM from the Chrome extension so byte-level behaviour is identical.

   Requires (set up by Swift BEFORE this file is evaluated):
     - window / self globals
     - crypto.getRandomValues (native SecRandomCopyBytes bridge)
     - bsv.min.js already evaluated (global `bsv`)
   ========================================================================= */
'use strict';

var OrdplugCore = (function(){

/* ---------- fees (identical to ORDPLUG v009 / ord-app v39 / ext V3.4) ---------- */
var SERVICE_FEE_ADDRESSES = {
  ordiBuilderAddress: '1HdbyucjYU2yfDFXzAQt3kCdP3VvM4tjzr',
  onnoBuilderAddress: '1JKcD1kx8XeJFfd32sug1MaXfruurHTCjv',
  algoBuilderAddress: '1AHEUcWuCfdRnfwNsvwZhZSetXjEuAvBot',
  colleagueIAddress:  '1ENW3XBoAv4KQ4FuQ4MtzNkLq82eJd12PV',
  protocolFeeAddress: '15q8YQSqUa9uTh6gh4AVixxq29xkpBBP9z',
  colleagueDAddress:  '1GeifRjPLWTDqL1DZ2vaqorX6pqCi9PyJB',
  monitorFeeAddress:  '1EXupec98g8TDTG5cwJwH3U8V3PezvvLv8',
  indexerFeeAddress:  '18RHRqQhsKKZwMnGevvnRQ8KrryAXvQUWQ',
  partnerFeeAddress:  '19o4rByWRvdq6zziJEfhpe4xdq5z43jYrr',
  founderFeeAddress:  '1EXupec98g8TDTG5cwJwH3U8V3PezvvLv8',
  foundationFeeAddress: '1ATEXPH6FSctbZdAz8MnXCfDpCvDnFrWma'
};
var SERVICE_FEES = {
  ordiBuilderFee:111, onnoBuilderFee:111, algoBuilderFee:111, colleagueIFee:111,
  protocolFee:222, colleagueDFee:222, monitorFee:333, indexerFee:444,
  partnerFee:666, founderFee:777, foundationFee:888
};
var TOTAL_SERVICE_FEES = Object.keys(SERVICE_FEES).reduce(function(a,k){ return a+SERVICE_FEES[k]; },0); // 3996
var FEE_RATE = 0.15; // 150 sats/kb = 0.15 sat/byte
function sendMinerFee(){ return Math.ceil((200 + 13*34) * FEE_RATE); }
function inscribeMinerFee(bytes){ return Math.ceil(((bytes||0) + 700) * FEE_RATE); }
function ordinalMinerFee(){ return Math.ceil((300 + 14*34) * FEE_RATE); }

/* sats as a safe integer — NEVER use `|0` on sat amounts (32-bit cast corrupts >21.47 BSV) */
function satNum(v){ var n=Math.round(Number(v)||0); return n>0?n:0; }

/* ---------- BIP39 (pure bsv — no Web Crypto dependency) ---------- */
var BIP39_WORDLIST = ["abandon","ability","able","about","above","absent","absorb","abstract","absurd","abuse","access","accident","account","accuse","achieve","acid","acoustic","acquire","across","act","action","actor","actress","actual","adapt","add","addict","address","adjust","admit","adult","advance","advice","aerobic","affair","afford","afraid","again","age","agent","agree","ahead","aim","air","airport","aisle","alarm","album","alcohol","alert","alien","all","alley","allow","almost","alone","alpha","already","also","alter","always","amateur","amazing","among","amount","amused","analyst","anchor","ancient","anger","angle","angry","animal","ankle","announce","annual","another","answer","antenna","antique","anxiety","any","apart","apology","appear","apple","approve","april","arch","arctic","area","arena","argue","arm","armed","armor","army","around","arrange","arrest","arrive","arrow","art","artefact","artist","artwork","ask","aspect","assault","asset","assist","assume","asthma","athlete","atom","attack","attend","attitude","attract","auction","audit","august","aunt","author","auto","autumn","average","avocado","avoid","awake","aware","away","awesome","awful","awkward","axis","baby","bachelor","bacon","badge","bag","balance","balcony","ball","bamboo","banana","banner","bar","barely","bargain","barrel","base","basic","basket","battle","beach","bean","beauty","because","become","beef","before","begin","behave","behind","believe","below","belt","bench","benefit","best","betray","better","between","beyond","bicycle","bid","bike","bind","biology","bird","birth","bitter","black","blade","blame","blanket","blast","bleak","bless","blind","blood","blossom","blouse","blue","blur","blush","board","boat","body","boil","bomb","bone","bonus","book","boost","border","boring","borrow","boss","bottom","bounce","box","boy","bracket","brain","brand","brass","brave","bread","breeze","brick","bridge","brief","bright","bring","brisk","broccoli","broken","bronze","broom","brother","brown","brush","bubble","buddy","budget","buffalo","build","bulb","bulk","bullet","bundle","bunker","burden","burger","burst","bus","business","busy","butter","buyer","buzz","cabbage","cabin","cable","cactus","cage","cake","call","calm","camera","camp","can","canal","cancel","candy","cannon","canoe","canvas","canyon","capable","capital","captain","car","carbon","card","cargo","carpet","carry","cart","case","cash","casino","castle","casual","cat","catalog","catch","category","cattle","caught","cause","caution","cave","ceiling","celery","cement","census","century","cereal","certain","chair","chalk","champion","change","chaos","chapter","charge","chase","chat","cheap","check","cheese","chef","cherry","chest","chicken","chief","child","chimney","choice","choose","chronic","chuckle","chunk","churn","cigar","cinnamon","circle","citizen","city","civil","claim","clap","clarify","claw","clay","clean","clerk","clever","click","client","cliff","climb","clinic","clip","clock","clog","close","cloth","cloud","clown","club","clump","cluster","clutch","coach","coast","coconut","code","coffee","coil","coin","collect","color","column","combine","come","comfort","comic","common","company","concert","conduct","confirm","congress","connect","consider","control","convince","cook","cool","copper","copy","coral","core","corn","correct","cost","cotton","couch","country","couple","course","cousin","cover","coyote","crack","cradle","craft","cram","crane","crash","crater","crawl","crazy","cream","credit","creek","crew","cricket","crime","crisp","critic","crop","cross","crouch","crowd","crucial","cruel","cruise","crumble","crunch","crush","cry","crystal","cube","culture","cup","cupboard","curious","current","curtain","curve","cushion","custom","cute","cycle","dad","damage","damp","dance","danger","daring","dash","daughter","dawn","day","deal","debate","debris","decade","december","decide","decline","decorate","decrease","deer","defense","define","defy","degree","delay","deliver","demand","demise","denial","dentist","deny","depart","depend","deposit","depth","deputy","derive","describe","desert","design","desk","despair","destroy","detail","detect","develop","device","devote","diagram","dial","diamond","diary","dice","diesel","diet","differ","digital","dignity","dilemma","dinner","dinosaur","direct","dirt","disagree","discover","disease","dish","dismiss","disorder","display","distance","divert","divide","divorce","dizzy","doctor","document","dog","doll","dolphin","domain","donate","donkey","donor","door","dose","double","dove","draft","dragon","drama","drastic","draw","dream","dress","drift","drill","drink","drip","drive","drop","drum","dry","duck","dumb","dune","during","dust","dutch","duty","dwarf","dynamic","eager","eagle","early","earn","earth","easily","east","easy","echo","ecology","economy","edge","edit","educate","effort","egg","eight","either","elbow","elder","electric","elegant","element","elephant","elevator","elite","else","embark","embody","embrace","emerge","emotion","employ","empower","empty","enable","enact","end","endless","endorse","enemy","energy","enforce","engage","engine","enhance","enjoy","enlist","enough","enrich","enroll","ensure","enter","entire","entry","envelope","episode","equal","equip","era","erase","erode","erosion","error","erupt","escape","essay","essence","estate","eternal","ethics","evidence","evil","evoke","evolve","exact","example","excess","exchange","excite","exclude","excuse","execute","exercise","exhaust","exhibit","exile","exist","exit","exotic","expand","expect","expire","explain","expose","express","extend","extra","eye","eyebrow","fabric","face","faculty","fade","faint","faith","fall","false","fame","family","famous","fan","fancy","fantasy","farm","fashion","fat","fatal","father","fatigue","fault","favorite","feature","february","federal","fee","feed","feel","female","fence","festival","fetch","fever","few","fiber","fiction","field","figure","file","film","filter","final","find","fine","finger","finish","fire","firm","first","fiscal","fish","fit","fitness","fix","flag","flame","flash","flat","flavor","flee","flight","flip","float","flock","floor","flower","fluid","flush","fly","foam","focus","fog","foil","fold","follow","food","foot","force","forest","forget","fork","fortune","forum","forward","fossil","foster","found","fox","fragile","frame","frequent","fresh","friend","fringe","frog","front","frost","frown","frozen","fruit","fuel","fun","funny","furnace","fury","future","gadget","gain","galaxy","gallery","game","gap","garage","garbage","garden","garlic","garment","gas","gasp","gate","gather","gauge","gaze","general","genius","genre","gentle","genuine","gesture","ghost","giant","gift","giggle","ginger","giraffe","girl","give","glad","glance","glare","glass","glide","glimpse","globe","gloom","glory","glove","glow","glue","goat","goddess","gold","good","goose","gorilla","gospel","gossip","govern","gown","grab","grace","grain","grant","grape","grass","gravity","great","green","grid","grief","grit","grocery","group","grow","grunt","guard","guess","guide","guilt","guitar","gun","gym","habit","hair","half","hammer","hamster","hand","happy","harbor","hard","harsh","harvest","hat","have","hawk","hazard","head","health","heart","heavy","hedgehog","height","hello","helmet","help","hen","hero","hidden","high","hill","hint","hip","hire","history","hobby","hockey","hold","hole","holiday","hollow","home","honey","hood","hope","horn","horror","horse","hospital","host","hotel","hour","hover","hub","huge","human","humble","humor","hundred","hungry","hunt","hurdle","hurry","hurt","husband","hybrid","ice","icon","idea","identify","idle","ignore","ill","illegal","illness","image","imitate","immense","immune","impact","impose","improve","impulse","inch","include","income","increase","index","indicate","indoor","industry","infant","inflict","inform","inhale","inherit","initial","inject","injury","inmate","inner","innocent","input","inquiry","insane","insect","inside","inspire","install","intact","interest","into","invest","invite","involve","iron","island","isolate","issue","item","ivory","jacket","jaguar","jar","jazz","jealous","jeans","jelly","jewel","job","join","joke","journey","joy","judge","juice","jump","jungle","junior","junk","just","kangaroo","keen","keep","ketchup","key","kick","kid","kidney","kind","kingdom","kiss","kit","kitchen","kite","kitten","kiwi","knee","knife","knock","know","lab","label","labor","ladder","lady","lake","lamp","language","laptop","large","later","latin","laugh","laundry","lava","law","lawn","lawsuit","layer","lazy","leader","leaf","learn","leave","lecture","left","leg","legal","legend","leisure","lemon","lend","length","lens","leopard","lesson","letter","level","liar","liberty","library","license","life","lift","light","like","limb","limit","link","lion","liquid","list","little","live","lizard","load","loan","lobster","local","lock","logic","lonely","long","loop","lottery","loud","lounge","love","loyal","lucky","luggage","lumber","lunar","lunch","luxury","lyrics","machine","mad","magic","magnet","maid","mail","main","major","make","mammal","man","manage","mandate","mango","mansion","manual","maple","marble","march","margin","marine","market","marriage","mask","mass","master","match","material","math","matrix","matter","maximum","maze","meadow","mean","measure","meat","mechanic","medal","media","melody","melt","member","memory","mention","menu","mercy","merge","merit","merry","mesh","message","metal","method","middle","midnight","milk","million","mimic","mind","minimum","minor","minute","miracle","mirror","misery","miss","mistake","mix","mixed","mixture","mobile","model","modify","mom","moment","monitor","monkey","monster","month","moon","moral","more","morning","mosquito","mother","motion","motor","mountain","mouse","move","movie","much","muffin","mule","multiply","muscle","museum","mushroom","music","must","mutual","myself","mystery","myth","naive","name","napkin","narrow","nasty","nation","nature","near","neck","need","negative","neglect","neither","nephew","nerve","nest","net","network","neutral","never","news","next","nice","night","noble","noise","nominee","noodle","normal","north","nose","notable","note","nothing","notice","novel","now","nuclear","number","nurse","nut","oak","obey","object","oblige","obscure","observe","obtain","obvious","occur","ocean","october","odor","off","offer","office","often","oil","okay","old","olive","olympic","omit","once","one","onion","online","only","open","opera","opinion","oppose","option","orange","orbit","orchard","order","ordinary","organ","orient","original","orphan","ostrich","other","outdoor","outer","output","outside","oval","oven","over","own","owner","oxygen","oyster","ozone","pact","paddle","page","pair","palace","palm","panda","panel","panic","panther","paper","parade","parent","park","parrot","party","pass","patch","path","patient","patrol","pattern","pause","pave","payment","peace","peanut","pear","peasant","pelican","pen","penalty","pencil","people","pepper","perfect","permit","person","pet","phone","photo","phrase","physical","piano","picnic","picture","piece","pig","pigeon","pill","pilot","pink","pioneer","pipe","pistol","pitch","pizza","place","planet","plastic","plate","play","please","pledge","pluck","plug","plunge","poem","poet","point","polar","pole","police","pond","pony","pool","popular","portion","position","possible","post","potato","pottery","poverty","powder","power","practice","praise","predict","prefer","prepare","present","pretty","prevent","price","pride","primary","print","priority","prison","private","prize","problem","process","produce","profit","program","project","promote","proof","property","prosper","protect","proud","provide","public","pudding","pull","pulp","pulse","pumpkin","punch","pupil","puppy","purchase","purity","purpose","purse","push","put","puzzle","pyramid","quality","quantum","quarter","question","quick","quit","quiz","quote","rabbit","raccoon","race","rack","radar","radio","rail","rain","raise","rally","ramp","ranch","random","range","rapid","rare","rate","rather","raven","raw","razor","ready","real","reason","rebel","rebuild","recall","receive","recipe","record","recycle","reduce","reflect","reform","refuse","region","regret","regular","reject","relax","release","relief","rely","remain","remember","remind","remove","render","renew","rent","reopen","repair","repeat","replace","report","require","rescue","resemble","resist","resource","response","result","retire","retreat","return","reunion","reveal","review","reward","rhythm","rib","ribbon","rice","rich","ride","ridge","rifle","right","rigid","ring","riot","ripple","risk","ritual","rival","river","road","roast","robot","robust","rocket","romance","roof","rookie","room","rose","rotate","rough","round","route","royal","rubber","rude","rug","rule","run","runway","rural","sad","saddle","sadness","safe","sail","salad","salmon","salon","salt","salute","same","sample","sand","satisfy","satoshi","sauce","sausage","save","say","scale","scan","scare","scatter","scene","scheme","school","science","scissors","scorpion","scout","scrap","screen","script","scrub","sea","search","season","seat","second","secret","section","security","seed","seek","segment","select","sell","seminar","senior","sense","sentence","series","service","session","settle","setup","seven","shadow","shaft","shallow","share","shed","shell","sheriff","shield","shift","shine","ship","shiver","shock","shoe","shoot","shop","short","shoulder","shove","shrimp","shrug","shuffle","shy","sibling","sick","side","siege","sight","sign","silent","silk","silly","silver","similar","simple","since","sing","siren","sister","situate","six","size","skate","sketch","ski","skill","skin","skirt","skull","slab","slam","sleep","slender","slice","slide","slight","slim","slogan","slot","slow","slush","small","smart","smile","smoke","smooth","snack","snake","snap","sniff","snow","soap","soccer","social","sock","soda","soft","solar","soldier","solid","solution","solve","someone","song","soon","sorry","sort","soul","sound","soup","source","south","space","spare","spatial","spawn","speak","special","speed","spell","spend","sphere","spice","spider","spike","spin","spirit","split","spoil","sponsor","spoon","sport","spot","spray","spread","spring","spy","square","squeeze","squirrel","stable","stadium","staff","stage","stairs","stamp","stand","start","state","stay","steak","steel","stem","step","stereo","stick","still","sting","stock","stomach","stone","stool","story","stove","strategy","street","strike","strong","struggle","student","stuff","stumble","style","subject","submit","subway","success","such","sudden","suffer","sugar","suggest","suit","summer","sun","sunny","sunset","super","supply","supreme","sure","surface","surge","surprise","surround","survey","suspect","sustain","swallow","swamp","swap","swarm","swear","sweet","swift","swim","swing","switch","sword","symbol","symptom","syrup","system","table","tackle","tag","tail","talent","talk","tank","tape","target","task","taste","tattoo","taxi","teach","team","tell","ten","tenant","tennis","tent","term","test","text","thank","that","theme","then","theory","there","they","thing","this","thought","three","thrive","throw","thumb","thunder","ticket","tide","tiger","tilt","timber","time","tiny","tip","tired","tissue","title","toast","tobacco","today","toddler","toe","together","toilet","token","tomato","tomorrow","tone","tongue","tonight","tool","tooth","top","topic","topple","torch","tornado","tortoise","toss","total","tourist","toward","tower","town","toy","track","trade","traffic","tragic","train","transfer","trap","trash","travel","tray","treat","tree","trend","trial","tribe","trick","trigger","trim","trip","trophy","trouble","truck","true","truly","trumpet","trust","truth","try","tube","tuition","tumble","tuna","tunnel","turkey","turn","turtle","twelve","twenty","twice","twin","twist","two","type","typical","ugly","umbrella","unable","unaware","uncle","uncover","under","undo","unfair","unfold","unhappy","uniform","unique","unit","universe","unknown","unlock","until","unusual","unveil","update","upgrade","uphold","upon","upper","upset","urban","urge","usage","use","used","useful","useless","usual","utility","vacant","vacuum","vague","valid","valley","valve","van","vanish","vapor","various","vast","vault","vehicle","velvet","vendor","venture","venue","verb","verify","version","very","vessel","veteran","viable","vibrant","vicious","victory","video","view","village","vintage","violin","virtual","virus","visa","visit","visual","vital","vivid","vocal","voice","void","volcano","volume","vote","voyage","wage","wagon","wait","walk","wall","walnut","want","warfare","warm","warrior","wash","wasp","waste","water","wave","way","wealth","weapon","wear","weasel","weather","web","wedding","weekend","weird","welcome","west","wet","whale","what","wheat","wheel","when","where","whip","whisper","wide","width","wife","wild","will","win","window","wine","wing","wink","winner","winter","wire","wisdom","wise","wish","witness","wolf","woman","wonder","wood","wool","word","work","world","worry","worth","wrap","wreck","wrestle","wrist","write","wrong","yard","year","yellow","you","young","youth","zebra","zero","zone","zoo"];

function entropyToMnemonic(entHexOrBytes){
  var ent = (typeof entHexOrBytes === 'string') ? hexToU8(entHexOrBytes) : entHexOrBytes;
  var hash = bsv.crypto.Hash.sha256(bsv.deps.Buffer.from(ent));
  var bits=''; for(var i=0;i<ent.length;i++) bits += ent[i].toString(2).padStart(8,'0');
  bits += hash[0].toString(2).padStart(8,'0').slice(0, (ent.length*8)/32);
  var words=[];
  for(var j=0;j<bits.length/11;j++) words.push(BIP39_WORDLIST[parseInt(bits.slice(j*11,(j+1)*11),2)]);
  return words.join(' ');
}
function mnemonicToSeed(mnemonic, passphrase){
  passphrase = passphrase || '';
  var Buf=bsv.deps.Buffer, Hash=bsv.crypto.Hash;
  var pw=Buf.from(mnemonic.normalize('NFKD'),'utf8');
  var salt=Buf.from(('mnemonic'+passphrase).normalize('NFKD'),'utf8');
  var U=Hash.sha512hmac(Buf.concat([salt, Buf.from([0,0,0,1])]), pw);
  var T=Buf.from(U);
  for(var i=1;i<2048;i++){ U=Hash.sha512hmac(U, pw); for(var j=0;j<T.length;j++) T[j]^=U[j]; }
  return T;
}
function mnemonicToWif(mnemonic){ /* legacy ORD/plug V9 derivation */
  var seed=mnemonicToSeed(mnemonic);
  return bsv.PrivateKey.fromBuffer(seed.slice(0,32)).toWIF();
}
function validateMnemonic(m){
  var w=String(m).trim().toLowerCase().split(/\s+/);
  if([12,15,18,21,24].indexOf(w.length)===-1) return false;
  return w.every(function(x){ return BIP39_WORDLIST.indexOf(x)!==-1; });
}
function wifToAddress(wif){ return bsv.PrivateKey.fromWIF(wif).toAddress().toString(); }
function wifToPubKey(wif){ return bsv.PrivateKey.fromWIF(wif).toPublicKey().toString(); }

var BIP44_PATH = "m/44'/236'/0'/0/0"; // SLIP-44 coin type 236 = BSV
function mnemonicToWifBip44(mnemonic, passphrase){
  var seed = mnemonicToSeed(mnemonic, passphrase||'');
  var hd = bsv.HDPrivateKey.fromSeed(bsv.deps.Buffer.from(seed));
  return hd.deriveChild(BIP44_PATH).privateKey.toWIF();
}
function mnemonicToWifPath(mnemonic, path, passphrase){
  var seed = mnemonicToSeed(mnemonic, passphrase||'');
  var hd = bsv.HDPrivateKey.fromSeed(bsv.deps.Buffer.from(seed));
  return hd.deriveChild(path).privateKey.toWIF();
}

/* ---------- known BSV wallet import presets (identical to the extension) ---------- */
var WALLET_PRESETS = [
  { id:'ordplug', name:'ORD/net (BIP44)', path:"m/44'/236'/0'/0/0", note:'Standard BSV path (coin type 236).' },
  { id:'relayx',  name:'RelayX',           path:"m/44'/236'/0'/0/0", alt:["m/44'/236'/0'/2/0"], note:'Payment m/44’/236’/0’/0/0. Ordinals were on …/2/0.' },
  { id:'yours',   name:'Yours / Panda',    path:"m/44'/236'/0'/0/0", alt:["m/44'/236'/1'/0/0"], note:'Payment …0’/0/0; ordinals key on …1’/0/0.' },
  { id:'twetch',  name:'Twetch',           path:"m/0/0",             note:'Twetch uses the non-hardened path m/0/0.' },
  { id:'moneybutton', name:'Money Button', path:"m/44'/0'/0'/0/0",   note:'Money Button used coin type 0 (m/44’/0’/0’).' },
  { id:'simplycash',  name:'Simply Cash',  path:"m/44'/145'/0'/0/0", note:'Simply Cash used coin type 145 (BCH numbering).' },
  { id:'electrumsv',  name:'ElectrumSV',   path:"m/44'/0'/0'/0/0",   alt:["m/44'/236'/0'/0/0","m/44'/145'/0'/0/0"], note:'Default coin type 0; also supports 236 and 145.' },
  { id:'handcash1',   name:'HandCash 1.x', path:"m/0'",              note:'Older HandCash (1.x) and Unit wallet used m/0’. HandCash 2.0 cannot be exported.' },
  { id:'centbee',     name:'Centbee',      path:"m/44'/0'/0'/0/0",   note:'Centbee reportedly uses m/44’/0 with your 4-digit PIN as passphrase — enter the PIN if set.', pin:true },
  { id:'edge',        name:'Edge',         path:"m/44'/236'/0'/0/0", alt:["m/44'/145'/0'/0/0"], note:'Typically coin type 236; 145 if the wallet came from a BCH split.' },
  { id:'custom',      name:'Custom path…', path:"m/44'/236'/0'/0/0", custom:true, note:'Enter any BIP32 path yourself.' }
];

/* ---------- helpers ---------- */
function hexToU8(h){ var b=new Uint8Array(h.length/2); for(var i=0;i<b.length;i++) b[i]=parseInt(h.substr(i*2,2),16); return b; }
function generateMnemonic(){
  var ent=new Uint8Array(16);
  crypto.getRandomValues(ent);
  return entropyToMnemonic(ent);
}
function randomWif(){ return bsv.PrivateKey.fromRandom().toWIF(); }
function validateAddress(a){ try{ bsv.Address.fromString(a); return true; }catch(e){ return false; } }
function validatePath(p){ return /^m(\/\d+'?)+$/.test(String(p)); }

/* UTXO shaping — mirror of getUTXOs() minus the network fetch. Input: raw
   WhatsOnChain unspent list [{tx_hash,tx_pos,value,isSpentInMempoolTx?}]. */
function shapeUtxos(rawListJson, address){
  var list = typeof rawListJson==='string' ? JSON.parse(rawListJson) : rawListJson;
  var script = bsv.Script.buildPublicKeyHashOut(bsv.Address.fromString(address)).toHex();
  return list
    .filter(function(u){ return u && u.tx_hash && !u.isSpentInMempoolTx; })
    .filter(function(u){ return u.tx_hash.length === 64; })
    .filter(function(u){ return u.value > 1; }) // ordinal protection: 1-sat UTXOs may be SNS names / BSVmaps
    .slice(0, 200)
    .map(function(u){ return { txid:u.tx_hash, vout:u.tx_pos, satoshis:u.value, script:script, scriptPubKey:script }; });
}

function addServiceFees(tx){
  var A=SERVICE_FEE_ADDRESSES, F=SERVICE_FEES;
  tx.to(bsv.Address.fromString(A.ordiBuilderAddress), F.ordiBuilderFee);
  tx.to(bsv.Address.fromString(A.onnoBuilderAddress), F.onnoBuilderFee);
  tx.to(bsv.Address.fromString(A.algoBuilderAddress), F.algoBuilderFee);
  tx.to(bsv.Address.fromString(A.colleagueIAddress),  F.colleagueIFee);
  tx.to(bsv.Address.fromString(A.protocolFeeAddress), F.protocolFee);
  tx.to(bsv.Address.fromString(A.colleagueDAddress),  F.colleagueDFee);
  tx.to(bsv.Address.fromString(A.monitorFeeAddress),  F.monitorFee);
  tx.to(bsv.Address.fromString(A.indexerFeeAddress),  F.indexerFee);
  tx.to(bsv.Address.fromString(A.partnerFeeAddress),  F.partnerFee);
  tx.to(bsv.Address.fromString(A.founderFeeAddress),  F.founderFee);
  tx.to(bsv.Address.fromString(A.foundationFeeAddress), F.foundationFee);
}

/* ---------- send BSV ---------- */
function buildSend(wif, utxos, toAddress, amountSat, dataStr, feeSat){
  if(typeof utxos==='string') utxos=JSON.parse(utxos);
  amountSat = satNum(amountSat);
  if(!feeSat) feeSat = sendMinerFee();
  var pk=bsv.PrivateKey.fromWIF(wif), from=pk.toAddress();
  if(!utxos.length) throw new Error('No spendable UTXOs. Your balance may be locked in pending (unconfirmed) transactions — wait for them to confirm, then retry.');
  var required=amountSat+feeSat+(dataStr?1:0)+TOTAL_SERVICE_FEES;
  var total=0, sel=[];
  for(var i=0;i<utxos.length;i++){ sel.push(utxos[i]); total+=utxos[i].satoshis; if(total>=required) break; }
  if(total<required) throw new Error('Insufficient balance for amount + fee + service fee.');
  var tx=new bsv.Transaction();
  sel.forEach(function(u){ tx.from(new bsv.Transaction.UnspentOutput({ txid:u.txid, outputIndex:u.vout, address:from, script:u.scriptPubKey||u.script, satoshis:u.satoshis })); });
  tx.to(toAddress, amountSat);
  addServiceFees(tx);
  if(dataStr) tx.addOutput(new bsv.Transaction.Output({ satoshis:1, script:bsv.Script.buildDataOut([dataStr]) }));
  var change=total-(amountSat+(dataStr?1:0)+feeSat+TOTAL_SERVICE_FEES);
  if(change>546) tx.to(from, change);
  tx.fee(feeSat); tx.sign(pk);
  return { rawtx: tx.toString(), fee: feeSat };
}

/* ---------- inscribe (1Sat Ordinal) ---------- */
function buildInscribe(wif, utxos, contentType, dataB64, feeSat){
  if(typeof utxos==='string') utxos=JSON.parse(utxos);
  var dataBytes = bsv.deps.Buffer.from(String(dataB64||''), 'base64');
  if(!feeSat) feeSat = inscribeMinerFee(dataBytes.length);
  var pk=bsv.PrivateKey.fromWIF(wif), from=pk.toAddress();
  if(!utxos.length) throw new Error('No spendable UTXOs. Your balance may be locked in pending (unconfirmed) transactions — wait for them to confirm, then retry.');
  var required=1+feeSat+1+TOTAL_SERVICE_FEES;
  var total=0, sel=[];
  for(var i=0;i<utxos.length;i++){ sel.push(utxos[i]); total+=utxos[i].satoshis; if(total>=required) break; }
  if(total<required) throw new Error('Insufficient balance for 1-sat ordinal + fee + service fee.');
  var tx=new bsv.Transaction();
  sel.forEach(function(u){ tx.from(new bsv.Transaction.UnspentOutput({ txid:u.txid, outputIndex:u.vout, address:from, script:u.scriptPubKey||u.script, satoshis:u.satoshis })); });
  var ins=new bsv.Script();
  ins.add(bsv.Opcode.OP_FALSE); ins.add(bsv.Opcode.OP_IF);
  ins.add(bsv.deps.Buffer.from('ord','utf8'));
  ins.add(bsv.Opcode.OP_1); ins.add(bsv.deps.Buffer.from(String(contentType),'utf8'));
  ins.add(bsv.Opcode.OP_0); ins.add(dataBytes);
  ins.add(bsv.Opcode.OP_ENDIF);
  var lock=bsv.Script.buildPublicKeyHashOut(from);
  var finalScript=new bsv.Script();
  ins.chunks.forEach(function(c){ finalScript.chunks.push(c); });
  lock.chunks.forEach(function(c){ finalScript.chunks.push(c); });
  tx.addOutput(new bsv.Transaction.Output({ satoshis:1, script:finalScript }));
  tx.addOutput(new bsv.Transaction.Output({ satoshis:1, script:bsv.Script.buildDataOut(['ORDnet.io']) }));
  addServiceFees(tx);
  var change=total-(1+1+feeSat+TOTAL_SERVICE_FEES);
  if(change>546) tx.to(from, change);
  tx.fee(feeSat); tx.sign(pk);
  return { rawtx: tx.toString(), fee: feeSat };
}

/* ---------- sendTx: caller-composed transaction (dApp API) ---------- */
var SENDTX_MAX_OUTPUTS = 350;
function buildTx(wif, utxos, params){
  if(typeof utxos==='string') utxos=JSON.parse(utxos);
  if(typeof params==='string') params=JSON.parse(params);
  var outs=Array.isArray(params.outputs)?params.outputs:[];
  if(!outs.length) throw new Error('sendTx: outputs[] required');
  if(outs.length>SENDTX_MAX_OUTPUTS) throw new Error('sendTx: too many outputs (max '+SENDTX_MAX_OUTPUTS+')');
  var pk=bsv.PrivateKey.fromWIF(wif), from=pk.toAddress();
  var changeAddr=params.changeAddress?bsv.Address.fromString(String(params.changeAddress)):from;
  if(!utxos.length) throw new Error('No spendable UTXOs. Your balance may be locked in pending (unconfirmed) transactions — wait, then retry.');

  var tx=new bsv.Transaction();
  var spend=0, outBytes=0;
  outs.forEach(function(o){
    if(o.type==='p2pkh'){
      var sats=satNum(o.satoshis); if(sats<1) throw new Error('sendTx: p2pkh satoshis');
      tx.to(bsv.Address.fromString(String(o.address)), sats); spend+=sats; outBytes+=34;
    } else if(o.type==='inscription'){
      var sats2=satNum(o.satoshis)||1;
      var bytes=o.dataB64?bsv.deps.Buffer.from(String(o.dataB64),'base64'):bsv.deps.Buffer.from(String(o.data),'utf8');
      var ct=String(o.contentType||'text/plain');
      var ins=new bsv.Script();
      ins.add(bsv.Opcode.OP_FALSE); ins.add(bsv.Opcode.OP_IF);
      ins.add(bsv.deps.Buffer.from('ord','utf8'));
      ins.add(bsv.Opcode.OP_1); ins.add(bsv.deps.Buffer.from(ct,'utf8'));
      ins.add(bsv.Opcode.OP_0); ins.add(bsv.deps.Buffer.from(bytes));
      ins.add(bsv.Opcode.OP_ENDIF);
      var lock=bsv.Script.buildPublicKeyHashOut(bsv.Address.fromString(String(o.address)));
      var script=new bsv.Script();
      ins.chunks.forEach(function(c){ script.chunks.push(c); });
      lock.chunks.forEach(function(c){ script.chunks.push(c); });
      tx.addOutput(new bsv.Transaction.Output({ satoshis:sats2, script:script })); spend+=sats2;
      outBytes+=12+bytes.length+ct.length+45;
    } else if(o.type==='opreturn'){
      var parts=(o.data||[]).map(function(s){ return String(s).indexOf('0x')===0?bsv.deps.Buffer.from(String(s).slice(2),'hex'):String(s); });
      tx.addOutput(new bsv.Transaction.Output({ satoshis:1, script:bsv.Script.buildDataOut(parts) })); spend+=1;
      outBytes+=14+parts.reduce(function(a,p){ return a+(typeof p==='string'?p.length:p.length)+3; },0);
    } else if(o.type==='script'){
      var sats3=satNum(o.satoshis);
      if(sats3<1) throw new Error('sendTx: script outputs need at least 1 satoshi (0-sat outputs are rejected as dust).');
      tx.addOutput(new bsv.Transaction.Output({ satoshis:sats3, script:bsv.Script.fromHex(String(o.scriptHex)) })); spend+=sats3;
      outBytes+=12+Math.ceil(String(o.scriptHex).length/2);
    } else throw new Error('sendTx: unknown output type '+o.type);
  });

  if(params.includeServiceFees!==false) addServiceFees(tx);
  var svc=(params.includeServiceFees!==false)?TOTAL_SERVICE_FEES:0;
  var svcBytes=(params.includeServiceFees!==false)?11*34:0;

  var feeSat=(params.fee|0), total=0, sel=[];
  for(var nIn=1;;){
    var fee=feeSat||Math.ceil((10 + nIn*148 + outBytes + svcBytes + 34) * FEE_RATE);
    var required=spend+svc+fee;
    total=0; sel=[];
    for(var i=0;i<utxos.length;i++){ sel.push(utxos[i]); total+=utxos[i].satoshis; if(total>=required) break; }
    if(total<required) throw new Error('Insufficient balance for outputs + fees.');
    if(feeSat || sel.length<=nIn){ feeSat=fee; break; }
    nIn=sel.length;
  }
  sel.forEach(function(u){ tx.from(new bsv.Transaction.UnspentOutput({ txid:u.txid, outputIndex:u.vout, address:from, script:u.scriptPubKey||u.script, satoshis:u.satoshis })); });

  var change=total-(spend+svc+feeSat);
  if(change>546) tx.to(changeAddr, change);
  tx.fee(feeSat); tx.sign(pk);
  return { rawtx: tx.toString(), fee: feeSat };
}

/* ---------- UTXO tools (v2.3): consolidate all funding UTXOs into one ----------
   Spends EVERY (ordinal-protected, pre-shaped) funding UTXO into a single
   output to self. Service fees apply like everywhere else in the app. */
function buildConsolidate(wif, utxos, feeSat){
  if(typeof utxos==='string') utxos=JSON.parse(utxos);
  var pk=bsv.PrivateKey.fromWIF(wif), from=pk.toAddress();
  if(!utxos.length) throw new Error('No spendable UTXOs to combine. Your balance may be locked in pending transactions.');
  if(utxos.length<2) throw new Error('Nothing to combine — you have only one spendable UTXO.');
  var total=0;
  utxos.forEach(function(u){ total+=u.satoshis; });
  if(!feeSat) feeSat=Math.ceil((10 + utxos.length*148 + 34 + 11*34) * FEE_RATE);
  var out=total-feeSat-TOTAL_SERVICE_FEES;
  if(out<546) throw new Error('Combined balance too small to cover fee + service fee (needs at least '+(feeSat+TOTAL_SERVICE_FEES+546)+' sats).');
  var tx=new bsv.Transaction();
  utxos.forEach(function(u){ tx.from(new bsv.Transaction.UnspentOutput({ txid:u.txid, outputIndex:u.vout, address:from, script:u.scriptPubKey||u.script, satoshis:u.satoshis })); });
  tx.to(from, out);
  addServiceFees(tx);
  tx.fee(feeSat); tx.sign(pk);
  return { rawtx: tx.toString(), fee: feeSat, outputSat: out };
}

/* ---------- broadcast bookkeeping (v2.3 chain mechanism) ----------
   Parse a signed rawtx and report: the inputs it spends and the outputs that
   pay >1 sat to `address` (spendable change/split outputs — 1-sat outputs are
   ordinals and are NEVER funding, consistent with the shapeUtxos protection). */
function txSpendInfo(rawtx, address){
  var tx=new bsv.Transaction(rawtx);
  var script=bsv.Script.buildPublicKeyHashOut(bsv.Address.fromString(address)).toHex();
  var inputs=tx.inputs.map(function(i){ return { txid:i.prevTxId.toString('hex'), vout:i.outputIndex }; });
  var own=[];
  tx.outputs.forEach(function(o,idx){
    if(o.script.toHex()===script && o.satoshis>1){
      own.push({ txid:tx.id, vout:idx, satoshis:o.satoshis, script:script, scriptPubKey:script });
    }
  });
  return { txid: tx.id, inputs: inputs, ownOutputs: own };
}

/* ---------- message signing (extension-compatible: sha256d + DER ECDSA) ---------- */
function signMessage(wif, message){
  var pk=bsv.PrivateKey.fromWIF(wif);
  if(bsv.Message) return { signature:new bsv.Message(message).sign(pk), pubkey:pk.toPublicKey().toString() };
  var hash=bsv.crypto.Hash.sha256sha256(bsv.deps.Buffer.from(message,'utf8'));
  return { signature:bsv.crypto.ECDSA.sign(hash, pk).toString(), pubkey:pk.toPublicKey().toString() };
}

/* ---------- raw-tx parsing helpers (Swift fetches the hex, JS parses) ---------- */
function outputScriptHex(rawTxHex, vout){
  var t=new bsv.Transaction(rawTxHex);
  var out=t.outputs && t.outputs[vout];
  var hex=out && out.script && out.script.toHex();
  if(!hex) throw new Error('Could not read the ordinal output script.');
  return hex;
}
/* address that a locking script's P2PKH tail pays to (ownership check) — null if none */
function scriptLockAddress(scriptHex){
  try{
    var s=bsv.Script.fromHex(scriptHex); var lockPkh=null;
    s.chunks.forEach(function(c){ if(c.buf && c.buf.length===20) lockPkh=c.buf; });
    if(!lockPkh) return null;
    return bsv.Address.fromPublicKeyHash(lockPkh).toString();
  }catch(e){ return null; }
}

/* funding selection for ordinal transfer / purchase: Swift fetches real script
   hexes for the SELECTED utxos afterwards, then calls the build function. */
function selectFunding(utxos, requiredSat){
  if(typeof utxos==='string') utxos=JSON.parse(utxos);
  var total=0, sel=[];
  for(var i=0;i<utxos.length;i++){ sel.push(utxos[i]); total+=utxos[i].satoshis; if(total>=requiredSat) break; }
  if(total<requiredSat) return null;
  return sel;
}

/* manual per-input signing (SIGHASH_ALL|FORKID) — envelope ordinal + P2PKH alike */
function signAllInputs(tx, pk){
  var SIG = bsv.crypto.Signature;
  var sigtype = SIG.SIGHASH_ALL | SIG.SIGHASH_FORKID;
  for(var i=0;i<tx.inputs.length;i++){
    var input=tx.inputs[i];
    var sig=bsv.Transaction.Sighash.sign(tx, pk, sigtype, i, input.output.script, new bsv.crypto.BN(input.output.satoshis));
    input.setScript(bsv.Script.buildPublicKeyHashIn(pk.publicKey, sig, sigtype));
  }
}

/* local verification BEFORE broadcast — clear per-input errors */
function verifyTxInputs(tx){
  try{
    var I=bsv.Script.Interpreter;
    var flags=I.SCRIPT_VERIFY_P2SH|I.SCRIPT_VERIFY_STRICTENC|I.SCRIPT_ENABLE_SIGHASH_FORKID|I.SCRIPT_VERIFY_DERSIG|I.SCRIPT_VERIFY_LOW_S|I.SCRIPT_VERIFY_NULLFAIL;
    for(var i=0;i<tx.inputs.length;i++){
      var inp=tx.inputs[i];
      var spk=inp.output && inp.output.script;
      var sats=inp.output && inp.output.satoshis;
      if(!spk) return 'Input '+i+' has no known locking script — the wallet could not read the UTXO it is spending.';
      var ok=false, errS='';
      try{
        var it=new I();
        ok=it.verify(inp.script, spk, tx, i, flags, new bsv.crypto.BN(sats));
        errS=it.errstr||'';
      }catch(e){ errS=(e&&e.message)||String(e); }
      if(!ok){
        return 'Input '+i+' failed local verification ('+(errS||'unknown')+'). '
          + 'This usually means the wallet used a wrong locking script or amount for that UTXO. '
          + (i===0 ? 'Input 0 is the ordinal itself.' : 'This is a funding UTXO.');
      }
    }
    return null;
  }catch(e){ return null; }
}

/* ---------- ordinal transfer (SNS name / BSVmap) — true 1Sat transfer ----------
   ordScriptHex: raw locking script of the ordinal output (Swift-fetched raw hex,
   parsed via outputScriptHex — NEVER the WoC verbose endpoint, it mangles
   envelope-first scripts by dropping the leading OP_FALSE).
   funding: selected utxos, each ideally with realScriptHex set. */
function buildOrdinalTransfer(wif, ordTxid, ordVout, ordScriptHex, funding, toAddress){
  if(typeof funding==='string') funding=JSON.parse(funding);
  var pk=bsv.PrivateKey.fromWIF(wif), from=pk.toAddress();
  var feeSat=ordinalMinerFee();

  /* OWNERSHIP CHECK */
  var lockAddr=scriptLockAddress(ordScriptHex);
  if(lockAddr && lockAddr!==from.toString()){
    throw new Error('This ordinal is locked to '+lockAddr+', but your active wallet key controls '+from.toString()
      +'. You can only send it from the wallet that owns it — import the seed/key for '+lockAddr+' and try again.');
  }

  var required=feeSat+TOTAL_SERVICE_FEES;
  var total=0;
  funding.forEach(function(u){ total+=u.satoshis; });
  if(!funding.length) throw new Error('No spendable funding UTXOs for the fee. Your balance may be locked in pending transactions.');
  if(total<required) throw new Error('Insufficient balance for fee + service fee.');

  var tx=new bsv.Transaction();
  tx.addInput(new bsv.Transaction.Input({
    prevTxId: ordTxid, outputIndex: ordVout, script: new bsv.Script(),
    output: new bsv.Transaction.Output({ script: bsv.Script.fromHex(ordScriptHex), satoshis: 1 })
  }));
  funding.forEach(function(u){ tx.addInput(new bsv.Transaction.Input({
    prevTxId: u.txid, outputIndex: u.vout, script: new bsv.Script(),
    output: new bsv.Transaction.Output({ script: bsv.Script.fromHex(u.realScriptHex||u.scriptPubKey||u.script), satoshis: u.satoshis })
  })); });
  tx.to(bsv.Address.fromString(toAddress), 1);
  addServiceFees(tx);
  var change=(1+total)-(1+feeSat+TOTAL_SERVICE_FEES);
  if(change>546) tx.to(from, change);
  signAllInputs(tx, pk);
  var vErr=verifyTxInputs(tx);
  if(vErr) throw new Error(vErr + ' The transaction was NOT broadcast.');
  return { rawtx: tx.toString(), fee: feeSat };
}

/* ---------- Optie-1 atomic swap: list (sell) + buy ---------- */
function buildListingPartial(wif, ordTxid, ordVout, ordScriptHex, priceSat){
  var pk = bsv.PrivateKey.fromWIF(wif), from = pk.toAddress();
  var tx = new bsv.Transaction();
  tx.addInput(new bsv.Transaction.Input({
    prevTxId: ordTxid, outputIndex: ordVout, script: new bsv.Script(),
    output: new bsv.Transaction.Output({ script: bsv.Script.fromHex(ordScriptHex), satoshis: 1 })
  }));
  var payScript = bsv.Script.buildPublicKeyHashOut(from);
  tx.addOutput(new bsv.Transaction.Output({ script: payScript, satoshis: satNum(priceSat) }));
  var SIG = bsv.crypto.Signature;
  var sigtype = SIG.SIGHASH_SINGLE | SIG.SIGHASH_ANYONECANPAY | SIG.SIGHASH_FORKID;
  var sig = bsv.Transaction.Sighash.sign(tx, pk, sigtype, 0, tx.inputs[0].output.script, new bsv.crypto.BN(1));
  tx.inputs[0].setScript(bsv.Script.buildPublicKeyHashIn(pk.publicKey, sig, sigtype));
  return { partialTx: tx.toString(), payScriptHex: payScript.toHex() };
}

function buildPurchaseFromPartial(wif, partialHex, priceSat, sellerAddress, payScriptHex, funding){
  if(typeof funding==='string') funding=JSON.parse(funding);
  priceSat = satNum(priceSat);
  var pk = bsv.PrivateKey.fromWIF(wif), buyer = pk.toAddress();
  var tx = new bsv.Transaction(partialHex);
  var out0 = tx.outputs[0];
  if (!out0 || out0.satoshis !== priceSat || out0.script.toHex() !== payScriptHex)
    throw new Error('Listing payment output does not match the advertised price — refusing.');
  tx.addOutput(new bsv.Transaction.Output({ script: bsv.Script.buildPublicKeyHashOut(buyer), satoshis: 1 }));
  var feeSat = ordinalMinerFee();
  var need = priceSat + 1 + feeSat + TOTAL_SERVICE_FEES;
  var total = 0;
  funding.forEach(function(u){ total += u.satoshis; });
  if (total < need) throw new Error('Insufficient balance for price + fee + service fee.');
  var firstBuyerInput = tx.inputs.length;
  funding.forEach(function(u){ tx.addInput(new bsv.Transaction.Input({
    prevTxId: u.txid, outputIndex: u.vout, script: new bsv.Script(),
    output: new bsv.Transaction.Output({ script: bsv.Script.fromHex(u.realScriptHex||u.scriptPubKey||u.script), satoshis: u.satoshis })
  })); });
  addServiceFees(tx);
  var change = total - (priceSat + 1 + feeSat + TOTAL_SERVICE_FEES);
  if (change > 546) tx.to(buyer, change);
  var SIG = bsv.crypto.Signature;
  var sigtype = SIG.SIGHASH_ALL | SIG.SIGHASH_FORKID;
  for (var i = firstBuyerInput; i < tx.inputs.length; i++){
    var inp = tx.inputs[i];
    var sig = bsv.Transaction.Sighash.sign(tx, pk, sigtype, i, inp.output.script, new bsv.crypto.BN(inp.output.satoshis));
    inp.setScript(bsv.Script.buildPublicKeyHashIn(pk.publicKey, sig, sigtype));
  }
  return { rawtx: tx.toString(), fee: feeSat };
}

/* ---------- purchase (ORDPAY) helpers ---------- */
function purchaseMessage(shop, itemTitle, orderId, amountSat, to){
  return 'ORDPAY/v1 | shop:'+(shop||'')+' | item:'+(itemTitle||'')+' | order:'+(orderId||'')+' | amount:'+satNum(amountSat)+' sats | to:'+(to||'');
}
function purchaseFee(opReturnByteLength){
  return Math.ceil((200 + 13*34 + opReturnByteLength) * FEE_RATE);
}

/* ---------- registry / marketplace signing messages (server-verified formats) ---------- */
function signAction(wif, address, action, fields, ts){
  var msg=['ordnet-registry',action].concat(fields.map(String)).concat([String(ts)]).join('|');
  var sig=signMessage(wif, msg);
  return { ts:ts, address:address, message:msg, signature:sig.signature, pubkey:sig.pubkey };
}
function delistMessage(district, ordinalTxid, ordinalVout, ts){
  return 'bsvmap delist '+district+' '+ordinalTxid+'_'+ordinalVout+' '+ts;
}

/* ---------- viewer: 1SatOrdinals inscription parser (port of sw.js/viewer.js) ---------- */
function extractOrd(hex, vout){
  var b = hexToU8(hex); var pos = 4;
  var ic = rv(b, pos); pos += ic[1];
  for (var i = 0; i < ic[0]; i++){ pos += 36; var sl = rv(b, pos); pos += sl[1] + sl[0] + 4; }
  var oc = rv(b, pos); pos += oc[1];
  for (var o = 0; o < oc[0]; o++){
    pos += 8; var sl2 = rv(b, pos); pos += sl2[1];
    if (o === vout){ var sb = b.slice(pos, pos + sl2[0]); var r = parseEnv(sb); if (r) return r; }
    pos += sl2[0];
  }
  return null;
}
/* first inscription in ANY output (main-page path of viewer.js) */
function extractFirstOrd(hex){
  var b = hexToU8(hex); var pos = 4;
  var ic = rv(b, pos); pos += ic[1];
  for (var i = 0; i < ic[0]; i++){ pos += 36; var sl = rv(b, pos); pos += sl[1] + sl[0] + 4; }
  var oc = rv(b, pos); pos += oc[1];
  for (var o = 0; o < oc[0]; o++){
    pos += 8; var sl2 = rv(b, pos); pos += sl2[1];
    var sb = b.slice(pos, pos + sl2[0]); pos += sl2[0];
    var r = parseEnv(sb); if (r) return r;
  }
  return null;
}
function parseEnv(sb){
  for (var i = 0; i < sb.length - 5; i++){
    if (sb[i] !== 0x00 || sb[i+1] !== 0x63) continue;
    var pos = i + 2;
    var p = rpd(sb, pos); if (!p[0]) continue; pos = p[1];
    if (u8str(p[0]) !== 'ord') continue;
    if (sb[pos] !== 0x51) continue; pos++;
    var c = rpd(sb, pos); if (!c[0]) continue; pos = c[1];
    var ct = u8str(c[0]);
    if (sb[pos] !== 0x00) continue; pos++;
    var chunks = [], ts = 0;
    while (pos < sb.length){
      if (sb[pos] === 0x68) break;
      var d = rpd(sb, pos);
      if (!d[0] || d[0].length === 0) break;
      chunks.push(d[0]); ts += d[0].length; pos = d[1];
    }
    var combined = new Uint8Array(ts); var off = 0;
    for (var j = 0; j < chunks.length; j++){ var cj=(chunks[j] instanceof Uint8Array)?chunks[j]:new Uint8Array(chunks[j]); combined.set(cj, off); off += cj.length; }
    return { ct: ct, dataB64: u8b64(combined) };
  }
  return null;
}
function rpd(b, p){
  if (p >= b.length) return [null, p];
  var o = b[p];
  if (o === 0) return [[], p+1];
  if (o >= 1 && o <= 0x4b){ p++; return [b.slice(p, p+o), p+o]; }
  if (o === 0x4c){ p++; var l = b[p]; p++; return [b.slice(p, p+l), p+l]; }
  if (o === 0x4d){ p++; var l2 = b[p] | (b[p+1] << 8); p += 2; return [b.slice(p, p+l2), p+l2]; }
  if (o === 0x4e){ p++; var l3 = b[p] | (b[p+1] << 8) | (b[p+2] << 16) | (b[p+3] << 24); p += 4; return [b.slice(p, p+l3), p+l3]; }
  return [null, p];
}
function rv(b, p){
  var f = b[p];
  if (f < 0xfd) return [f, 1];
  if (f === 0xfd) return [b[p+1] | (b[p+2] << 8), 3];
  if (f === 0xfe) return [b[p+1] | (b[p+2] << 8) | (b[p+3] << 16) | (b[p+4] << 24), 5];
  return [0, 9];
}
function u8str(b){ var u=(b instanceof Uint8Array)?b:new Uint8Array(b); var s=''; for(var i=0;i<u.length;i++) s+=String.fromCharCode(u[i]); try{ return decodeURIComponent(escape(s)); }catch(e){ return s; } }
function u8b64(u){
  var CH='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  var out=''; var i;
  for(i=0;i+2<u.length;i+=3){
    var n=(u[i]<<16)|(u[i+1]<<8)|u[i+2];
    out+=CH[(n>>18)&63]+CH[(n>>12)&63]+CH[(n>>6)&63]+CH[n&63];
  }
  if(i<u.length){
    var rem=u.length-i;
    var n2=(u[i]<<16)|((rem>1?u[i+1]:0)<<8);
    out+=CH[(n2>>18)&63]+CH[(n2>>12)&63]+(rem>1?CH[(n2>>6)&63]:'=')+'=';
  }
  return out;
}

/* ---------- SNS resolver verification (sns.ordnet.io, resolver v1.3) ----------
   Canonical sighash per skill.md §6: prefix + 0x1f + fields joined with 0x1f,
   double-SHA256; ECDSA(secp256k1) DER over that digest. Verify-only: no key
   material, no network — Swift fetches, this code proves. NO own crypto:
   sha256d and ECDSA come from the battle-tested bsv.min.js.
   Acceptance (both skill.md test vectors) is enforced in Tests/engine-tests.mjs:
     answer sighash  28a4252e92fdcdb70d6fd287cdb602cda504d288963e106b47a6d8d19420ec6b
     rotation sighash ddc9eefe6e0097a6312171f0dad76b6822e08f31498bd7f47f51ba163481cb31 */
var SNS_SEP = '\x1f';
function snsSighashHex(prefix, fields){
  var pre = prefix + SNS_SEP + fields.map(String).join(SNS_SEP);
  return bsv.crypto.Hash.sha256sha256(bsv.deps.Buffer.from(pre, 'utf8')).toString('hex');
}
/* signed fields of a resolve answer, in canonical order (skill.md §6).
   NOT signed (and therefore never trusted): input, source, holder_address,
   signer — the display address is derived from the SIGNED holder_script. */
function snsAnswerFields(a){
  return [ a.v, a.name, (a.mailbox == null ? '' : a.mailbox), a.holder_script,
           a.origin.txid, a.origin.vout, a.current.txid, a.current.vout,
           a.as_of_height, (a.fallback ? 'true' : 'false'), a.expires ];
}
function snsEcdsaVerify(digestHex, sigDerHex, pubkeyHex){
  var digest = bsv.deps.Buffer.from(digestHex, 'hex');
  var sig = bsv.crypto.Signature.fromDER(bsv.deps.Buffer.from(sigDerHex, 'hex'));
  var pub = bsv.PublicKey.fromString(pubkeyHex);
  return bsv.crypto.ECDSA.verify(digest, sig, pub) === true;
}
/* verify one ok-answer against the pinned resolver key.
   Returns { valid, reason?, ... } — reason 'unknown_signer' is the caller's
   cue to run the rotation-chain path; everything else is terminal. */
function snsVerifyAnswer(answerJson, expectedSigner, nowTs){
  var a = typeof answerJson === 'string' ? JSON.parse(answerJson) : answerJson;
  if (!a || a.ok !== true || !a.sig || !a.signer || !a.holder_script || !a.current)
    return { valid:false, reason:'malformed' };
  var signer = String(a.signer).toLowerCase();
  if (signer !== String(expectedSigner || '').toLowerCase())
    return { valid:false, reason:'unknown_signer', signer:signer };
  var digestHex = snsSighashHex('ORDNS-RESOLVE', snsAnswerFields(a));
  if (!snsEcdsaVerify(digestHex, String(a.sig), signer))
    return { valid:false, reason:'bad_signature' };
  if (Number(a.expires) <= Number(nowTs))
    return { valid:false, reason:'expired' };
  var derived = scriptLockAddress(String(a.holder_script));
  if (!derived)
    return { valid:false, reason:'unsupported_holder_script' };
  return {
    valid: true,
    name: String(a.name),
    mailbox: String(a.mailbox == null ? '' : a.mailbox),
    fallback: a.fallback === true,
    holderAddress: derived,               // derived from the SIGNED script
    addressMismatch: !!(a.holder_address && a.holder_address !== derived),
    currentTxid: String(a.current.txid),
    currentVout: Number(a.current.vout) || 0,
    asOfHeight: Number(a.as_of_height) || 0,
    expires: Number(a.expires)
  };
}
/* key rotation (v1.3): succession deeds signed by the OLD key. Walk the chain
   from the pinned key; only a closing chain moves the pin. Returns the final
   pubkey; throws with a clear message otherwise. */
function snsRotationFields(r){
  return [ r.rv, r.seq, String(r.old_pub).toLowerCase(), String(r.new_pub).toLowerCase(), r.valid_from ];
}
function snsVerifyRotationChain(pinnedPub, records){
  if (typeof records === 'string') records = JSON.parse(records);
  if (!Array.isArray(records) || !records.length)
    throw new Error('No rotation records to verify.');
  var cur = String(pinnedPub).toLowerCase();
  for (var i = 0; i < records.length; i++){
    var r = records[i];
    if (String(r.old_pub).toLowerCase() !== cur)
      throw new Error('Rotation record ' + i + ' does not connect to the pinned key — chain broken.');
    var digestHex = snsSighashHex('ORDNS-KEYROTATE', snsRotationFields(r));
    if (!snsEcdsaVerify(digestHex, String(r.sig), cur))
      throw new Error('Rotation record ' + i + ' carries an invalid signature — refusing to re-pin.');
    cur = String(r.new_pub).toLowerCase();
  }
  return cur;
}

/* ---------- BRC-100 fase 2 (v2.5): keys & crypto via @bsv/sdk ----------
   The bundled BSVSDK (loaded AFTER this file, referenced at call time)
   provides ProtoWallet/KeyDeriver — the spec-conform BRC-42/43 core. All of
   this runs INSIDE the engine; the page only ever sees relayed results.
   ProtoWallet methods are async (CPU-only, resolve on the microtask queue),
   while the Swift<->JS boundary is synchronous — hence the start/poll pair:
   Swift calls brc100Start, JSC drains microtasks when the call returns, and
   the very next brc100Poll delivers the outcome. */
var _brc100 = { wallet: null, deriver: null, wif: null, seq: 0, results: {} };
function _sdk(){
  var S = (typeof globalThis !== 'undefined' && globalThis.BSVSDK) || (typeof BSVSDK !== 'undefined' ? BSVSDK : null);
  if (!S) throw new Error('BRC-100 engine bundle (BSVSDK) is not loaded.');
  return S;
}
function brc100Init(wif){
  if (_brc100.wif === wif && _brc100.wallet) return true;
  var S = _sdk();
  var pk = S.PrivateKey.fromWif(wif);
  _brc100.wallet = new S.ProtoWallet(pk);
  _brc100.deriver = new S.KeyDeriver(pk);
  _brc100.wif = wif;
  return true;
}
/* wipe key material on lock — Swift calls this from WalletStore.lock() */
function brc100Reset(){
  _brc100 = { wallet: null, deriver: null, wif: null, seq: 0, results: {} };
  return true;
}
var BRC100_METHODS = ['getPublicKey','encrypt','decrypt','createHmac','verifyHmac','createSignature','verifySignature'];
function brc100Start(callId, method, argsJson){
  if (!_brc100.wallet) throw new Error('BRC-100 engine not initialised.');
  if (BRC100_METHODS.indexOf(method) === -1) throw new Error('Unsupported BRC-100 engine method: ' + method);
  var args = JSON.parse(argsJson || '{}');
  _brc100.results[callId] = { done: false };
  _brc100.wallet[method](args).then(function(r){
    _brc100.results[callId] = { done: true, ok: true, result: r };
  }).catch(function(e){
    _brc100.results[callId] = { done: true, ok: false,
      error: { name: (e && e.name) || 'WERR_UNKNOWN', message: (e && e.message) || String(e), code: (e && e.code) || 1 } };
  });
  return true;
}
function brc100Poll(callId){
  var r = _brc100.results[callId];
  if (r && r.done) { delete _brc100.results[callId]; return r; }
  return { done: false };
}

/* ---------- BRC-100 fase 3 (v2.6): geld — createAction c.s. ----------
   Zelfbouw op @bsv/sdk + de bestaande, bewezen buildTx-fundamenten (stap-0-
   besluit: uitkomst B; de wallet-toolbox blijft referentie voor semantiek).
   Validatie geeft {valid:false, werr:{name,code,message}} terug (patroon van
   snsVerifyAnswer) zodat de Swift-laag het BRC-100-foutcontract exact kan
   naleven: een weigering wordt in de pagina een promise-REJECTION met
   WERR_*-naam — nooit een stil leeg resultaat. */
function _werr(name, code, message){ return { valid:false, werr:{ name:name, code:code, message:message } }; }

/* toolbox-conforme stringlengtes */
function _validDesc(s){ return typeof s === 'string' && s.length >= 5 && s.length <= 2000; }
function _validLabel(s){ return typeof s === 'string' && s.length >= 1 && s.length <= 300; }
function _validHexScript(h){
  if (typeof h !== 'string' || !h.length || h.length % 2 || /[^0-9a-fA-F]/.test(h)) return false;
  try { bsv.Script.fromHex(h); return true; } catch(e){ return false; }
}

/* createAction-argumenten valideren + normaliseren (puur, deterministisch,
   getest in engine-tests). Regel 1: alleen-outputs — custom inputs weigeren
   expliciet tot het signableTransaction-pad er echt is. */
function brc100ValidateCreate(argsJson){
  var a; try { a = typeof argsJson === 'string' ? JSON.parse(argsJson) : (argsJson || {}); }
  catch(e){ return _werr('WERR_INVALID_PARAMETER', 3, 'createAction: args must be valid JSON.'); }

  if (a.inputs && a.inputs.length)
    return _werr('WERR_UNSUPPORTED_ACTION', 2, 'createAction with custom inputs (signableTransaction) is not supported yet by the ORDnet wallet — outputs-only actions are.');
  if (a.inputBEEF && a.inputBEEF.length)
    return _werr('WERR_UNSUPPORTED_ACTION', 2, 'createAction: inputBEEF requires the signableTransaction path, which is not supported yet.');
  if (a.lockTime !== undefined && a.lockTime !== 0)
    return _werr('WERR_INVALID_PARAMETER', 3, 'createAction: custom lockTime is not supported.');
  if (a.version !== undefined && a.version !== 1)
    return _werr('WERR_INVALID_PARAMETER', 3, 'createAction: custom version is not supported.');
  if (!_validDesc(a.description))
    return _werr('WERR_INVALID_PARAMETER', 3, 'createAction: description must be a string of 5..2000 characters.');

  var o = a.options || {};
  /* opties die de semantiek veranderen en die wij (nog) niet waarmaken:
     expliciet weigeren — nooit stil negeren (eis uit de fase-3-briefing) */
  if (o.noSend === true)
    return _werr('WERR_INVALID_PARAMETER', 3, 'createAction: options.noSend is not supported — this wallet broadcasts processed actions directly.');
  if (o.sendWith && o.sendWith.length)
    return _werr('WERR_INVALID_PARAMETER', 3, 'createAction: options.sendWith batching is not supported.');
  if (o.signAndProcess === false)
    return _werr('WERR_INVALID_PARAMETER', 3, 'createAction: options.signAndProcess=false requires the signableTransaction path, which is not supported yet.');
  if (o.trustSelf !== undefined || (o.knownTxids && o.knownTxids.length) || o.noSendChange !== undefined)
    return _werr('WERR_INVALID_PARAMETER', 3, 'createAction: options trustSelf/knownTxids/noSendChange are not supported.');

  if (!Array.isArray(a.outputs) || !a.outputs.length)
    return _werr('WERR_INVALID_PARAMETER', 3, 'createAction: outputs[] is required (at least one output).');
  if (a.outputs.length > SENDTX_MAX_OUTPUTS)
    return _werr('WERR_INVALID_PARAMETER', 3, 'createAction: too many outputs (max ' + SENDTX_MAX_OUTPUTS + ').');

  var outs = [], total = 0;
  for (var i = 0; i < a.outputs.length; i++){
    var out = a.outputs[i] || {};
    if (out.basket !== undefined)
      return _werr('WERR_INVALID_PARAMETER', 3, 'createAction: output baskets are not tracked by this wallet (output ' + i + ').');
    if (!_validHexScript(out.lockingScript))
      return _werr('WERR_INVALID_PARAMETER', 3, 'createAction: output ' + i + ' needs a valid lockingScript (hex).');
    var sats = satNum(out.satoshis);
    if (sats < 1)
      return _werr('WERR_INVALID_PARAMETER', 3, 'createAction: output ' + i + ' needs satoshis >= 1 (0-sat outputs are rejected as dust).');
    if (!_validDesc(out.outputDescription))
      return _werr('WERR_INVALID_PARAMETER', 3, 'createAction: output ' + i + ' needs an outputDescription of 5..2000 characters.');
    var tags = [];
    if (out.tags !== undefined){
      if (!Array.isArray(out.tags) || !out.tags.every(_validLabel))
        return _werr('WERR_INVALID_PARAMETER', 3, 'createAction: output ' + i + ' tags must be strings of 1..300 characters.');
      tags = out.tags.map(function(t){ return String(t).toLowerCase(); });
    }
    var dest = scriptLockAddress(String(out.lockingScript));
    outs.push({
      satoshis: sats,
      lockingScript: String(out.lockingScript).toLowerCase(),
      outputDescription: String(out.outputDescription),
      tags: tags,
      dest: dest || null                     // null = niet-P2PKH (toon als script)
    });
    total += sats;
  }

  var labels = [];
  if (a.labels !== undefined){
    if (!Array.isArray(a.labels) || !a.labels.every(_validLabel))
      return _werr('WERR_INVALID_PARAMETER', 3, 'createAction: labels must be strings of 1..300 characters.');
    labels = a.labels.map(function(l){ return String(l).toLowerCase(); });
  }

  /* minerfee-schatting met de formule van buildTx (nIn nog onbekend → 1);
     de sheet toont dit als schatting, de bouw rekent exact */
  var outBytes = outs.reduce(function(s,x){ return s + 12 + Math.ceil(x.lockingScript.length/2); }, 0);
  var feeEstimate = Math.ceil((10 + 148 + outBytes + 11*34 + 34) * FEE_RATE);

  return {
    valid: true,
    description: String(a.description),
    labels: labels,
    outputs: outs,
    totalSat: total,
    serviceFees: TOTAL_SERVICE_FEES,
    minerFeeEstimate: feeEstimate,
    returnTXIDOnly: o.returnTXIDOnly === true,
    randomizeOutputs: o.randomizeOutputs !== false   // BRC-100 default: true
  };
}

/* na Face ID-akkoord: bouwen + ondertekenen via het bestaande buildTx-pad
   (service fees, dynamische fee, change, ordinal-beschermde utxos) */
function brc100BuildCreate(wif, utxos, argsJson){
  var v = brc100ValidateCreate(argsJson);
  if (!v.valid) throw new Error(v.werr.message);
  var outs = v.outputs.slice();
  if (v.randomizeOutputs && outs.length > 1){          // BRC-100 privacyregel
    for (var i = outs.length - 1; i > 0; i--){
      var r = new Uint8Array(1); crypto.getRandomValues(r);
      var j = r[0] % (i + 1); var t = outs[i]; outs[i] = outs[j]; outs[j] = t;
    }
  }
  var params = { outputs: outs.map(function(o){ return { type:'script', satoshis:o.satoshis, scriptHex:o.lockingScript }; }) };
  var r2 = buildTx(wif, utxos, JSON.stringify(params));
  return { rawtx: r2.rawtx, txid: new bsv.Transaction(r2.rawtx).id, fee: r2.fee,
           totalSat: v.totalSat, serviceFees: TOTAL_SERVICE_FEES };
}

/* internalizeAction: AtomicBEEF (BRC-100 Byte[]) van de app → alleen het
   'wallet payment'-protocol met directe betalingen aan het wallet-adres.
   BRC-29-afgeleide betalingen en 'basket insertion' weigeren EXPLICIET
   (dat vergt sleutel-afleiding per output resp. basket-boekhouding). */
function brc100ParseInternalize(argsJson, walletAddress){
  var a; try { a = typeof argsJson === 'string' ? JSON.parse(argsJson) : (argsJson || {}); }
  catch(e){ return _werr('WERR_INVALID_PARAMETER', 3, 'internalizeAction: args must be valid JSON.'); }
  if (!_validDesc(a.description))
    return _werr('WERR_INVALID_PARAMETER', 3, 'internalizeAction: description must be a string of 5..2000 characters.');
  if (!Array.isArray(a.tx) || !a.tx.length)
    return _werr('WERR_INVALID_PARAMETER', 3, 'internalizeAction: tx must be the AtomicBEEF byte array of the transaction.');
  if (!Array.isArray(a.outputs) || !a.outputs.length)
    return _werr('WERR_INVALID_PARAMETER', 3, 'internalizeAction: outputs[] is required.');

  var S = _sdk(), tx;
  try { tx = S.Transaction.fromAtomicBEEF(a.tx); }
  catch(e){ return _werr('WERR_INVALID_PARAMETER', 3, 'internalizeAction: tx is not valid AtomicBEEF: ' + ((e && e.message) || e)); }
  var rawtx = tx.toHex();
  var txid = new bsv.Transaction(rawtx).id;   // txid via het bestaande bsv-pad
  var lockHex = bsv.Script.buildPublicKeyHashOut(bsv.Address.fromString(walletAddress)).toHex().toLowerCase();

  var accepted = [], total = 0;
  for (var i = 0; i < a.outputs.length; i++){
    var o = a.outputs[i] || {};
    var vout = Number(o.outputIndex);
    if (!Number.isInteger(vout) || vout < 0 || vout >= tx.outputs.length)
      return _werr('WERR_INVALID_PARAMETER', 3, 'internalizeAction: outputIndex ' + o.outputIndex + ' does not exist in the transaction.');
    if (o.protocol === 'basket insertion')
      return _werr('WERR_INVALID_PARAMETER', 3, 'internalizeAction: basket insertion is not supported — this wallet does not track custom baskets.');
    if (o.protocol !== 'wallet payment')
      return _werr('WERR_INVALID_PARAMETER', 3, 'internalizeAction: unknown protocol for output ' + vout + ' (expected "wallet payment").');
    var txo = tx.outputs[vout];
    var scriptHex = txo.lockingScript.toHex().toLowerCase();
    if (scriptHex !== lockHex)
      return _werr('WERR_INVALID_PARAMETER', 3, 'internalizeAction: output ' + vout + ' does not pay this wallet\'s address directly — BRC-29 derived payments are not supported yet.');
    var sats = satNum(txo.satoshis);
    accepted.push({ vout: vout, satoshis: sats });
    total += sats;
  }
  return { valid: true, txid: txid, rawtx: rawtx, outputs: accepted, totalSat: total };
}

/* listOutputs over de live (ordinal-beschermde) UTXO-set — alleen de
   'default' basket bestaat; al het andere weigert expliciet. */
function brc100ListOutputs(utxos, argsJson){
  var a; try { a = typeof argsJson === 'string' ? JSON.parse(argsJson) : (argsJson || {}); }
  catch(e){ return _werr('WERR_INVALID_PARAMETER', 3, 'listOutputs: args must be valid JSON.'); }
  if (typeof utxos === 'string') utxos = JSON.parse(utxos);
  var basket = a.basket === undefined ? 'default' : a.basket;
  if (basket !== 'default')
    return _werr('WERR_INVALID_PARAMETER', 3, 'listOutputs: basket "' + basket + '" is not tracked by this wallet — only "default" (the spendable funding outputs) exists.');
  if (a.tags && a.tags.length)
    return _werr('WERR_INVALID_PARAMETER', 3, 'listOutputs: output tags are not tracked by this wallet.');
  if (a.include === 'entire transactions')
    return _werr('WERR_INVALID_PARAMETER', 3, 'listOutputs: include="entire transactions" (BEEF) is not supported yet — use "locking scripts".');
  var withScripts = a.include === 'locking scripts';
  var limit = Number.isInteger(a.limit) && a.limit > 0 ? Math.min(a.limit, 10000) : 10;
  var offset = Number.isInteger(a.offset) && a.offset > 0 ? a.offset : 0;
  var page = utxos.slice(offset, offset + limit);
  return {
    valid: true,
    totalOutputs: utxos.length,
    outputs: page.map(function(u){
      var out = { outpoint: u.txid + '.' + u.vout, satoshis: satNum(u.satoshis), spendable: true };
      if (withScripts) out.lockingScript = String(u.script || u.scriptPubKey || '');
      return out;
    })
  };
}

/* ---------- security scanner (viewer) ---------- */
var SEC_PATTERNS = [
  { name: 'Crypto Miner', level: 4, re: /coinhive|cryptonight|webminer|coin-?hive|cryptoloot/gi },
  { name: 'Keylogger', level: 4, re: /addEventListener\s*\(\s*['"]key(down|up|press)['"][\s\S]{0,200}(fetch|XMLHttpRequest)/gi },
  { name: 'Cookie Theft', level: 3, re: /document\.cookie[\s\S]{0,150}(location|window\.open)/gi },
  { name: 'Phishing', level: 3, re: /<form[^>]*action\s*=\s*['"][^'"]*(?:login|signin|account)/gi },
  { name: 'Eval/Decode', level: 2, re: /eval\s*\(\s*(atob|decodeURIComponent|unescape)/gi },
  { name: 'Script Inject', level: 2, re: /document\.createElement\s*\(\s*['"]script['"]/gi }
];
function scanSecurity(html){
  var maxLevel = 0;
  for (var i = 0; i < SEC_PATTERNS.length; i++){
    var m = String(html).match(SEC_PATTERNS[i].re);
    if (m && SEC_PATTERNS[i].level > maxLevel) maxLevel = SEC_PATTERNS[i].level;
  }
  return maxLevel;
}

/* ---------- JSON-string wrappers (stable Swift <-> JS boundary) ----------
   Every wrapper takes ONE JSON string and returns a JSON string:
   {"ok":true,"result":...} or {"ok":false,"error":"..."} */
function _wrap(fn){
  return function(argsJson){
    try{
      var a = argsJson ? JSON.parse(argsJson) : {};
      return JSON.stringify({ ok:true, result: fn(a) });
    }catch(e){
      return JSON.stringify({ ok:false, error: (e && e.message) || String(e) });
    }
  };
}

var api = {
  generateMnemonic:  _wrap(function(a){ return generateMnemonic(); }),
  validateMnemonic:  _wrap(function(a){ return validateMnemonic(a.mnemonic); }),
  mnemonicToWifBip44:_wrap(function(a){ return mnemonicToWifBip44(a.mnemonic, a.passphrase||''); }),
  mnemonicToWifLegacy:_wrap(function(a){ return mnemonicToWif(a.mnemonic); }),
  mnemonicToWifPath: _wrap(function(a){ if(!validatePath(a.path)) throw new Error("Enter a valid path like m/44'/236'/0'/0/0."); return mnemonicToWifPath(a.mnemonic, a.path, a.passphrase||''); }),
  wifToAddress:      _wrap(function(a){ return wifToAddress(a.wif); }),
  wifToPubKey:       _wrap(function(a){ return wifToPubKey(a.wif); }),
  randomWif:         _wrap(function(a){ return randomWif(); }),
  validateAddress:   _wrap(function(a){ return validateAddress(a.address); }),
  walletPresets:     _wrap(function(a){ return WALLET_PRESETS; }),
  fees:              _wrap(function(a){ return { sendMinerFee:sendMinerFee(), inscribeMinerFee:inscribeMinerFee(a.bytes||0), ordinalMinerFee:ordinalMinerFee(), totalServiceFees:TOTAL_SERVICE_FEES, feeRate:FEE_RATE }; }),
  shapeUtxos:        _wrap(function(a){ return shapeUtxos(a.raw, a.address); }),
  buildSend:         _wrap(function(a){ return buildSend(a.wif, a.utxos, a.to, a.amountSat, a.dataStr||null, a.feeSat||0); }),
  buildInscribe:     _wrap(function(a){ return buildInscribe(a.wif, a.utxos, a.contentType, a.dataB64, a.feeSat||0); }),
  buildTx:           _wrap(function(a){ return buildTx(a.wif, a.utxos, a.params); }),
  signMessage:       _wrap(function(a){ return signMessage(a.wif, a.message); }),
  outputScriptHex:   _wrap(function(a){ return outputScriptHex(a.rawTxHex, a.vout); }),
  scriptLockAddress: _wrap(function(a){ return scriptLockAddress(a.scriptHex); }),
  selectFunding:     _wrap(function(a){ return selectFunding(a.utxos, a.requiredSat); }),
  buildOrdinalTransfer:_wrap(function(a){ return buildOrdinalTransfer(a.wif, a.ordTxid, a.ordVout, a.ordScriptHex, a.funding, a.to); }),
  buildListingPartial:_wrap(function(a){ return buildListingPartial(a.wif, a.ordTxid, a.ordVout, a.ordScriptHex, a.priceSat); }),
  buildPurchaseFromPartial:_wrap(function(a){ return buildPurchaseFromPartial(a.wif, a.partialHex, a.priceSat, a.sellerAddress, a.payScriptHex, a.funding); }),
  purchaseMessage:   _wrap(function(a){ return purchaseMessage(a.shop, a.itemTitle, a.orderId, a.amountSat, a.to); }),
  purchaseFee:       _wrap(function(a){ return purchaseFee(a.opReturnByteLength||0); }),
  signAction:        _wrap(function(a){ return signAction(a.wif, a.address, a.action, a.fields||[], a.ts); }),
  delistMessage:     _wrap(function(a){ return delistMessage(a.district, a.ordinalTxid, a.ordinalVout, a.ts); }),
  brc100Init:        _wrap(function(a){ return brc100Init(a.wif); }),
  brc100Reset:       _wrap(function(a){ return brc100Reset(); }),
  brc100Start:       _wrap(function(a){ return brc100Start(a.callId, a.method, a.argsJson); }),
  brc100Poll:        _wrap(function(a){ return brc100Poll(a.callId); }),
  brc100ValidateCreate:   _wrap(function(a){ return brc100ValidateCreate(a.argsJson); }),
  brc100BuildCreate:      _wrap(function(a){ return brc100BuildCreate(a.wif, a.utxos, a.argsJson); }),
  brc100ParseInternalize: _wrap(function(a){ return brc100ParseInternalize(a.argsJson, a.address); }),
  brc100ListOutputs:      _wrap(function(a){ return brc100ListOutputs(a.utxos, a.argsJson); }),
  buildConsolidate:  _wrap(function(a){ return buildConsolidate(a.wif, a.utxos, a.feeSat||0); }),
  txSpendInfo:       _wrap(function(a){ return txSpendInfo(a.rawtx, a.address); }),
  snsSighash:        _wrap(function(a){ return snsSighashHex(a.prefix, a.fields||[]); }),
  snsVerifyAnswer:   _wrap(function(a){ return snsVerifyAnswer(a.answerJson, a.expectedSigner, a.nowTs); }),
  snsVerifyRotationChain:_wrap(function(a){ return snsVerifyRotationChain(a.pinnedPub, a.records); }),
  extractOrd:        _wrap(function(a){ return (a.vout===null||a.vout===undefined) ? extractFirstOrd(a.rawTxHex) : extractOrd(a.rawTxHex, a.vout); }),
  scanSecurity:      _wrap(function(a){ return scanSecurity(a.html); }),
  txidOf:            _wrap(function(a){ return new bsv.Transaction(a.rawtx).id; })
};

return api;
})();

/* make the API reachable from JSContext / any evaluator regardless of strict-eval scoping */
(function(g){ g.OrdplugCore = OrdplugCore; })(typeof globalThis!=='undefined'?globalThis:(typeof self!=='undefined'?self:this));
