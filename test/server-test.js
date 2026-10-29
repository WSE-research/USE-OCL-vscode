'use strict';
// Tests the language server via LSP: opens the samples in test/samples, compares
// the diagnostics with the expected ones, changes and closes a document, checks
// completion and shuts the server down. Usage (in the use-vscode folder, after ./build.sh):
//
//   node test/server-test.js              local Java (JAVA_HOME, USE_HOME or .build/use)
//   node test/server-test.js --container  in the image use-ocl:7.5.0 (USE_ENGINE, else docker)

const cp = require('child_process');
const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');
const rpc = require('vscode-jsonrpc/node');

const ROOT = path.resolve(__dirname, '..');
const SAMPLES = path.join(__dirname, 'samples');
const MAIN_CLASS = 'de.htwk.uselsp.Main';

// [line, column] from 1, severity, excerpt from the message
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

// Completion: text appended to the sample (§ is the cursor), trigger
// character, names that must appear, names that must not
const COMPLETIONS = {
  'bibliothek.use': [
    ['context Medium inv T:\n  self.§', '.', ['titel', 'zustand', 'leser', 'oclIsKindOf'], ['ausweis', 'medium']],
    ['context Mitglied inv T:\n  self.medium->§', '>', ['notEmpty', 'includes', 'select', 'forAll'], ['first', 'titel']],
    ['context Medium inv T:\n  self.zustand = Zustand::§', ':', ['verfuegbar', 'ausgeliehen', 'gesperrt'], ['Medium']],
    ['context Mitglied inv T:\n  self.medium->forAll(x | x.§', '.', ['titel', 'leser'], ['ausweis']],
    ['context Mitglied inv T:\n  medi§', null, ['medium', 'self', 'Medium', 'implies'], []],
    ['context Mitglied::ausleihen(m : Medium)\n  post P: self.medium->includes(m) and m.§', '.', ['titel'], []],
    ['context Mitglied inv T:\n  self.medium->select(m : §', null, ['Integer', 'Medium', 'Zustand'], ['self']],
    ['context Mitglied inv T:\n  self.medium->size() >§', '>', [], ['select', 'size']],
    ['context Mitglied inv T:\n  self.anzahl() >= 0 -- self.§', '.', [], ['titel', 'ausweis']],
    ['§', null, ['class', 'association', 'context', 'enum'], []],
  ],
  'bibliothek.soil': [
    ['!ada.§', '.', ['ausweis', 'medium', 'anzahl', 'ausleihen'], ['titel']],
    ['? ada.§', '.', ['ausweis', 'anzahl'], ['ausleihen']],
    ['? ada.medium->select(x | x.§', '.', ['titel', 'zustand'], []],
    ['!new §', null, ['Mitglied', 'Medium'], ['ada', 'Zustand']],
    ['!insert (ada, m1) into §', null, ['Leiht'], []],
    ['!m1.zustand := Zustand::§', ':', ['verfuegbar'], []],
    ['!destroy §', null, ['ada', 'm1'], ['Mitglied']],
    ['che§', null, ['check', '!new'], []],
  ],
};

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

  // per URI, the diagnostics in the order they arrive
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
  // the client sends the server the models next to a Soil script
  let modelRequests = 0;
  connection.onRequest('useOcl/models', ({ uri }) => {
    modelRequests++;
    const folder = path.dirname(new URL(uri).pathname);
    return fs.readdirSync(folder).filter((f) => f.endsWith('.use'))
      .map((f) => ({ uri: pathToFileURL(path.join(folder, f)).href, text: fs.readFileSync(path.join(folder, f), 'utf8') }));
  });
  connection.listen();

  const init = await connection.sendRequest('initialize', { processId: process.pid, rootUri: null, capabilities: {} });
  check(init.capabilities.textDocumentSync.change === 1, 'initialize: vollständige Synchronisation');
  check(init.capabilities.completionProvider && init.capabilities.completionProvider.triggerCharacters.includes('.'),
    'initialize: Vervollständigung mit "." als Auslöser');
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

  // Change: the error-free model becomes a faulty one and back again
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

  // Completion: the models are incomplete while typing, the server uses the
  // last error-free version (bibliothek.use is currently error-free)
  let version = 10;
  for (const [name, cases] of Object.entries(COMPLETIONS)) {
    const original = fs.readFileSync(path.join(SAMPLES, name), 'utf8');
    for (const [appended, trigger, wanted, unwanted] of cases) {
      const text = `${original}\n${appended}`;
      const at = text.indexOf('§');
      const lines = text.slice(0, at).split('\n');
      connection.sendNotification('textDocument/didChange', {
        textDocument: { uri: uris[name], version: ++version }, contentChanges: [{ text: text.replace('§', '') }],
      });
      const result = await connection.sendRequest('textDocument/completion', {
        textDocument: { uri: uris[name] },
        position: { line: lines.length - 1, character: lines[lines.length - 1].length },
        context: trigger ? { triggerKind: 2, triggerCharacter: trigger } : { triggerKind: 1 },
      });
      const labels = (Array.isArray(result) ? result : result.items).map((i) => i.label);
      const missing = wanted.filter((w) => !labels.includes(w));
      const extra = unwanted.filter((u) => labels.includes(u));
      const where = appended.split('\n').pop().replace('§', '|');
      check(!missing.length && !extra.length && (wanted.length || !labels.length || unwanted.length),
        `Vervollständigung ${name}: ${where} (${labels.length} Vorschläge)`);
      if (missing.length || extra.length) {
        console.log('     fehlt:', missing, 'zu viel:', extra, 'erhalten:', labels.slice(0, 30));
      }
    }
    connection.sendNotification('textDocument/didChange', {
      textDocument: { uri: uris[name], version: ++version }, contentChanges: [{ text: original }],
    });
  }
  check(modelRequests > 0, `Vervollständigung in Skripten: Modelle beim Client angefragt (${modelRequests}-mal)`);

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
