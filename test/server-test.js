'use strict';
// Prüft den Sprachserver über LSP: öffnet die Beispiele in test/samples, vergleicht
// die Diagnosen mit den erwarteten, ändert und schließt ein Dokument und beendet
// den Server. Aufruf (im Ordner use-vscode, nach ./build.sh):
//
//   node test/server-test.js              lokales Java (JAVA_HOME, USE_HOME oder .build/use)
//   node test/server-test.js --container  im Image use-ocl:7.5.0 (USE_ENGINE, sonst docker)

const cp = require('child_process');
const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');
const rpc = require('vscode-jsonrpc/node');

const ROOT = path.resolve(__dirname, '..');
const SAMPLES = path.join(__dirname, 'samples');
const MAIN_CLASS = 'de.htwk.uselsp.Main';

// [Zeile, Spalte] ab 1, Art, Ausschnitt aus der Meldung
const EXPECTED = {
  'bibliothek.use': [],
  'bibliothek.soil': [],
  'bibliothek-fehler.use': [
    [8, 14, 'error', "Expected type name, found `Boolen'"],
    [31, 8, 'error', "Undefined operation named `medien'"],
    [34, 14, 'warning', 'will always evaluate to false'],
    [37, 23, 'error', 'Undefined operation `Integer.+(Boolean)'],
  ],
  'bibliothek-syntax.use': [[14, 6, 'error', "no viable alternative at input 'Medium'"]],
  'bibliothek-fehler.soil': [
    [1, 1, 'error', 'Soil-Anweisungen beginnen mit'],
    [2, 11, 'error', 'zugewiesen'],
    [3, 9, 'error', "no viable alternative at input '('"],
    [4, 22, 'error', "'<EOF>'"],
    [5, 1, 'error', 'Statement expected'],
    [6, 1, 'error', "Unknown command `hilfe'"],
  ],
};
const SEVERITY = { 1: 'error', 2: 'warning', 3: 'information', 4: 'hint' };

function serverCommand() {
  if (process.argv.includes('--container')) {
    const engine = process.env.USE_ENGINE || 'docker';
    return [engine, ['run', '--rm', '-i', '--network', 'none', '-v', `${path.join(ROOT, 'server')}:/lsp:ro`,
      '--entrypoint', 'java', process.env.USE_IMAGE || 'use-ocl:7.5.0',
      '-Duse.home=/opt/use', '-cp', '/lsp/*:/opt/use/lib/*', MAIN_CLASS]];
  }
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java';
  const useHome = process.env.USE_HOME || path.join(ROOT, '.build', 'use');
  const classpath = [path.join(ROOT, 'server', '*'), path.join(useHome, 'lib', '*')].join(path.delimiter);
  return [java, [`-Duse.home=${useHome}`, '-cp', classpath, MAIN_CLASS]];
}

let failures = 0;
function check(condition, text) {
  console.log(`${condition ? 'ok  ' : 'FAIL'} ${text}`);
  if (!condition) failures++;
}

function compare(name, diagnostics) {
  const actual = diagnostics
    .map((d) => [d.range.start.line + 1, d.range.start.character + 1, SEVERITY[d.severity], d.message])
    .sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const expected = EXPECTED[name];
  const same = actual.length === expected.length
    && expected.every((e, i) => e[0] === actual[i][0] && e[1] === actual[i][1] && e[2] === actual[i][2]
      && actual[i][3].includes(e[3]));
  check(same, `${name}: ${actual.length} Meldung(en)`);
  if (!same) {
    console.log('     erwartet:', JSON.stringify(expected));
    console.log('     erhalten:', JSON.stringify(actual.map((a) => [a[0], a[1], a[2], a[3].slice(0, 60)])));
  }
}

async function main() {
  const [command, args] = serverCommand();
  console.log(`Server: ${command} ${args.join(' ')}`);
  const started = Date.now();
  const server = cp.spawn(command, args, { stdio: ['pipe', 'pipe', 'inherit'] });
  const exited = new Promise((resolve) => server.on('exit', (code) => resolve(code)));
  const connection = rpc.createMessageConnection(
    new rpc.StreamMessageReader(server.stdout), new rpc.StreamMessageWriter(server.stdin));

  // je URI die Diagnosen nacheinander, wie sie ankommen
  const waiting = new Map();
  const received = new Map();
  connection.onNotification('textDocument/publishDiagnostics', (params) => {
    const queue = received.get(params.uri) || [];
    queue.push(params);
    received.set(params.uri, queue);
    const resolve = waiting.get(params.uri);
    if (resolve) {
      waiting.delete(params.uri);
      resolve();
    }
  });
  async function nextDiagnostics(uri) {
    const deadline = Date.now() + 30000;
    while (!(received.get(uri) || []).length) {
      if (Date.now() > deadline) throw new Error(`keine Diagnosen für ${uri}`);
      await Promise.race([new Promise((r) => waiting.set(uri, r)), new Promise((r) => setTimeout(r, 1000))]);
    }
    return received.get(uri).shift();
  }
  connection.listen();

  const init = await connection.sendRequest('initialize', { processId: process.pid, rootUri: null, capabilities: {} });
  check(init.capabilities.textDocumentSync.change === 1, 'initialize: vollständige Synchronisation');
  connection.sendNotification('initialized', {});

  const uris = {};
  for (const name of Object.keys(EXPECTED)) {
    const file = path.join(SAMPLES, name);
    uris[name] = pathToFileURL(file).href;
    connection.sendNotification('textDocument/didOpen', {
      textDocument: { uri: uris[name], languageId: path.extname(name).slice(1), version: 1, text: fs.readFileSync(file, 'utf8') },
    });
  }
  for (const name of Object.keys(EXPECTED)) {
    compare(name, (await nextDiagnostics(uris[name])).diagnostics);
  }
  console.log(`     (Start und erste Prüfungen: ${Date.now() - started} ms)`);

  // Änderung: aus dem fehlerfreien Modell wird ein fehlerhaftes und zurück
  const ok = fs.readFileSync(path.join(SAMPLES, 'bibliothek.use'), 'utf8');
  const uri = uris['bibliothek.use'];
  connection.sendNotification('textDocument/didChange', {
    textDocument: { uri, version: 2 },
    contentChanges: [{ text: ok.replace('self.leser->notEmpty()', 'self.leserin->notEmpty()') }],
  });
  let d = await nextDiagnostics(uri);
  check(d.version === 2 && d.diagnostics.length === 1 && d.diagnostics[0].message.includes('leserin'),
    'didChange: neuer Fehler gemeldet');
  connection.sendNotification('textDocument/didChange', { textDocument: { uri, version: 3 }, contentChanges: [{ text: ok }] });
  d = await nextDiagnostics(uri);
  check(d.version === 3 && d.diagnostics.length === 0, 'didChange: Fehler wieder verschwunden');

  connection.sendNotification('textDocument/didClose', { textDocument: { uri: uris['bibliothek-fehler.use'] } });
  d = await nextDiagnostics(uris['bibliothek-fehler.use']);
  check(d.diagnostics.length === 0, 'didClose: Diagnosen gelöscht');

  await connection.sendRequest('shutdown');
  connection.sendNotification('exit');
  const code = await Promise.race([exited, new Promise((r) => setTimeout(() => r('timeout'), 15000))]);
  check(code === 0, `shutdown/exit: Server beendet (Exit-Code ${code})`);
  if (code === 'timeout') server.kill();
  connection.dispose();

  console.log(failures ? `${failures} Prüfung(en) fehlgeschlagen` : 'alle Prüfungen bestanden');
  process.exit(failures ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
