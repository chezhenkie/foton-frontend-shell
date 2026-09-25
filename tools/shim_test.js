// Extracts the Android fullscreen shim JS out of MainActivity.kt and exercises
// it against a minimal DOM stub. Tests the real string, not a copy.
// Run: node tools/shim_test.js
const fs = require('fs');

const src = fs.readFileSync(
  require('path').join(__dirname, '..', 'android', 'app', 'src', 'main', 'java',
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
console.log('extracted ' + shim.length + ' chars of JS\n');

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

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
