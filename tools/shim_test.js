// Extracts both fullscreen shims - the Android one out of MainActivity.kt and
// the desktop one out of src/main.rs - and exercises them against a minimal DOM
// stub. Tests the real strings, not copies.
// Run: node tools/shim_test.js
const fs = require('fs');
const path = require('path');

const src = fs.readFileSync(
  path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'java',
    'com', 'foton', 'frontend', 'MainActivity.kt'), 'utf8');
const start = src.indexOf('private fun injectFullscreenShim');
const end = src.indexOf('private fun setFullscreen', start);
const body = src.slice(start, end);
const parts = [...body.matchAll(/"((?:[^"\\]|\\.)*)"/g)]
  .map(m => m[1].replace(/\\"/g, '"').replace(/\\\\/g, '\\'))
  .filter(s => s.length);
const shim = parts.join('');
if (!shim.startsWith('(function ()') || !shim.endsWith('})();')) {
  throw new Error('extraction looks wrong: ' + shim.slice(0, 40));
}

const rust = fs.readFileSync(path.join(__dirname, '..', 'src', 'main.rs'), 'utf8');
const RUST_MARK = 'const FULLSCREEN_SCRIPT: &str = r#"';
const rStart = rust.indexOf(RUST_MARK);
if (rStart < 0) throw new Error('no FULLSCREEN_SCRIPT raw string in src/main.rs');
const rEnd = rust.indexOf('"#;', rStart + RUST_MARK.length);
const desktopShim = rust.slice(rStart + RUST_MARK.length, rEnd);
if (!desktopShim.includes('(function ()') || !desktopShim.includes('})();')) {
  throw new Error('desktop extraction looks wrong: ' + desktopShim.slice(0, 40));
}
console.log('extracted ' + shim.length + ' chars of Android JS, '
  + desktopShim.trim().length + ' chars of desktop JS\n');


const calls = [];
let changes = 0;

class Element {}
class Doc {}

function freshPage() {
  const doc = new Doc();
  doc.documentElement = new Element();
  doc.addEventListener = () => {};
  doc.dispatchEvent = () => { changes++; };
  global.document = doc;
  global.Element = Element;
  global.Document = Doc;
  global.Event = function (name) { this.type = name; };
  global.fotonHost = { fullscreen: on => calls.push(on) };
  global.window = global;
  delete global.__fotonFullscreenShim;
  delete global.__fotonSetFullscreen;
  eval(shim);
}

let pass = 0, fail = 0;
function check(name, cond) {
  if (cond) { pass++; console.log('  ok   ' + name); }
  else { fail++; console.log('  FAIL ' + name); }
}

console.log('1. injection is idempotent');
freshPage();
eval(shim);
check('second injection is a no-op', document.documentElement.requestFullscreen !== undefined
  && document.fullscreenElement === null);

console.log('2. page-driven requestFullscreen');
changes = 0; calls.length = 0;
document.documentElement.requestFullscreen();
check('host notified true', calls.length === 1 && calls[0] === true);
check('fullscreenElement is html', document.fullscreenElement === document.documentElement);
check('one fullscreenchange', changes === 1);
check('fullscreenEnabled true', document.fullscreenEnabled === true);

console.log('3. page-driven exitFullscreen');
changes = 0; calls.length = 0;
document.exitFullscreen();
check('host notified false', calls.length === 1 && calls[0] === false);
check('fullscreenElement null', document.fullscreenElement === null);
check('one fullscreenchange', changes === 1);

console.log('4. host push (menu / back gesture) moves the page, no bounce');
changes = 0; calls.length = 0;
window.__fotonSetFullscreen(true);
check('page entered fullscreen', document.fullscreenElement === document.documentElement);
check('one fullscreenchange', changes === 1);
check('no callback into the host', calls.length === 0);

console.log('5. host push is idempotent');
changes = 0; calls.length = 0;
window.__fotonSetFullscreen(true);
window.__fotonSetFullscreen(true);
check('no duplicate change events', changes === 0);
check('still fullscreen', document.fullscreenElement === document.documentElement);
check('no host calls', calls.length === 0);

console.log('6. host push false, page exit stays consistent');
changes = 0; calls.length = 0;
window.__fotonSetFullscreen(false);
check('page left fullscreen', document.fullscreenElement === null);
check('one fullscreenchange', changes === 1);
document.exitFullscreen();
check('page exit still notifies host false', calls.length === 1 && calls[0] === false);

console.log('7. reload-while-fullscreen heal');
window.__fotonSetFullscreen(true);
calls.length = 0;
freshPage();
check('fresh document starts windowed', document.fullscreenElement === null);
window.__fotonSetFullscreen(true);
check('host re-assert moved the page', document.fullscreenElement === document.documentElement);
check('heal did not call the host', calls.length === 0);

console.log('8. push before the shim exists must not throw');
delete window.__fotonSetFullscreen;
let threw = false;
try { window.__fotonSetFullscreen && window.__fotonSetFullscreen(true); }
catch (e) { threw = true; }
check('no throw', !threw);

// --- desktop shim (src/main.rs, posts over window.ipc) ---
// Run in a fresh vm realm, the way the shell injects it: a new document per
// load, and no chance of the Android stubs above shadowing Element/Document.
const vm = require('vm');
let dchanges = 0;
let dposts = [];
let dlisteners = {};
let dsandbox = null;

function freshDesktopPage() {
  dchanges = 0;
  dposts = [];
  dlisteners = {};
  class DElement {}
  class DDocument {}
  const doc = new DDocument();
  doc.documentElement = new DElement();
  doc.addEventListener = (type, fn) => { dlisteners[type] = fn; };
  doc.dispatchEvent = () => { dchanges++; };
  const sandbox = {
    document: doc,
    Element: DElement,
    Document: DDocument,
    Event: function (name) { this.type = name; },
    ipc: { postMessage: m => dposts.push(m) },
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  dsandbox = sandbox;
  vm.runInContext(desktopShim, sandbox);
}

function pressKey(key) {
  if (dlisteners.keydown) dlisteners.keydown({ key });
}

console.log('9. desktop shim injects once and patches the prototypes');
freshDesktopPage();
const patchedBefore = dsandbox.Element.prototype.requestFullscreen;
vm.runInContext(desktopShim, dsandbox);
check('second injection is a no-op',
  dsandbox.Element.prototype.requestFullscreen === patchedBefore
  && dsandbox.document.fullscreenElement === null);
check('Element.prototype patched', typeof patchedBefore === 'function');
check('Document.prototype patched',
  typeof dsandbox.Document.prototype.exitFullscreen === 'function');
check('any element inherits it',
  typeof Object.getPrototypeOf(dsandbox.document.documentElement).requestFullscreen
    === 'function');
check('keydown listener registered', typeof dlisteners.keydown === 'function');
check('fullscreenEnabled true', dsandbox.document.fullscreenEnabled === true);

console.log('10. desktop requestFullscreen posts the on message');
freshDesktopPage();
const req = dsandbox.document.documentElement.requestFullscreen();
check('posts foton-fullscreen:on', dposts.length === 1
  && dposts[0] === 'foton-fullscreen:on');
check('fullscreenElement is html',
  dsandbox.document.fullscreenElement === dsandbox.document.documentElement);
check('one fullscreenchange', dchanges === 1);
check('returns a promise', req && typeof req.then === 'function');

console.log('11. desktop exitFullscreen posts the off message');
dsandbox.document.exitFullscreen();
check('posts foton-fullscreen:off', dposts.length === 2
  && dposts[1] === 'foton-fullscreen:off');
check('fullscreenElement null', dsandbox.document.fullscreenElement === null);
check('one fullscreenchange', dchanges === 2);

console.log('12. ESC exits native fullscreen through the same off path');
dsandbox.document.documentElement.requestFullscreen();
dposts.length = 0; dchanges = 0;
pressKey('Escape');
check('posts foton-fullscreen:off', dposts.length === 1
  && dposts[0] === 'foton-fullscreen:off');
check('fullscreenElement null', dsandbox.document.fullscreenElement === null);
check('one fullscreenchange', dchanges === 1);

console.log('13. ESC while windowed is inert');
dposts.length = 0; dchanges = 0;
pressKey('Escape');
pressKey('a');
check('no ipc traffic', dposts.length === 0);
check('no change events', dchanges === 0);
check('still windowed', dsandbox.document.fullscreenElement === null);

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
