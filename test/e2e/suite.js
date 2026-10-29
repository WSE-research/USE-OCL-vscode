'use strict';
// Runs inside VS Code (via --extensionTestsPath, started by run.js): opens the
// samples, waits for the language server's diagnostics, changes a document and
// requests completion suggestions.
// The results go to the file USE_E2E_RESULT.

const fs = require('fs');
const path = require('path');
const vscode = require('vscode');

const SAMPLES = path.resolve(__dirname, '..', 'samples');
const lines = [];

function check(condition, text) {
  lines.push(`${condition ? 'ok  ' : 'FAIL'} ${text}`);
  return condition;
}

async function waitFor(condition, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (condition()) {
      return true;
    }
    await new Promise((r) => setTimeout(r, 250));
  }
  return condition();
}

async function open(name) {
  const doc = await vscode.workspace.openTextDocument(path.join(SAMPLES, name));
  await vscode.window.showTextDocument(doc, { preview: false });
  return doc;
}

async function completions(doc, after) {
  const position = doc.positionAt(doc.getText().indexOf(after) + after.length);
  const list = await vscode.commands.executeCommand('vscode.executeCompletionItemProvider', doc.uri, position);
  return list.items.map((i) => (typeof i.label === 'string' ? i.label : i.label.label));
}

function count(doc, severity) {
  return vscode.languages.getDiagnostics(doc.uri).filter((d) => severity === undefined || d.severity === severity).length;
}

async function suite() {
  const extension = vscode.extensions.all.find((e) => e.packageJSON.name === 'use-ocl');
  check(extension !== undefined, 'Erweiterung geladen');

  // faulty files first: the first call starts the server
  const model = await open('bibliothek-fehler.use');
  check(model.languageId === 'use', `bibliothek-fehler.use hat die Sprache ${model.languageId}`);
  const started = Date.now();
  const arrived = await waitFor(() => count(model) === 4, 120000);
  check(arrived, `bibliothek-fehler.use: ${count(model)} Diagnosen (erwartet 4) nach ${Date.now() - started} ms`);
  check(count(model, vscode.DiagnosticSeverity.Warning) === 1, 'davon 1 Warnung (includes, immer false)');
  const warning = vscode.languages.getDiagnostics(model.uri).find((d) => d.severity === vscode.DiagnosticSeverity.Warning);
  check(warning && warning.range.start.line === 33 && warning.source === 'USE', 'Warnung in Zeile 34, Quelle USE');

  const script = await open('bibliothek-fehler.soil');
  check(script.languageId === 'soil', `bibliothek-fehler.soil hat die Sprache ${script.languageId}`);
  check(await waitFor(() => count(script) === 6, 30000), `bibliothek-fehler.soil: ${count(script)} Diagnosen (erwartet 6)`);

  const clean = await open('bibliothek.use');
  const cleanScript = await open('bibliothek.soil');
  await new Promise((r) => setTimeout(r, 3000));
  check(count(clean) === 0 && count(cleanScript) === 0, 'bibliothek.use und bibliothek.soil: keine Diagnosen');

  // Typing: introduce an error (unsaved), then undo it
  const editor = await vscode.window.showTextDocument(clean);
  const at = clean.getText().indexOf('self.leser->notEmpty()');
  await editor.edit((e) => e.replace(new vscode.Range(clean.positionAt(at), clean.positionAt(at + 10)), 'self.leserin'));
  check(await waitFor(() => count(clean) === 1, 15000), `nach dem Tippen: ${count(clean)} Diagnose (erwartet 1)`);
  await vscode.commands.executeCommand('undo');
  check(await waitFor(() => count(clean) === 0, 15000), `nach Rückgängig: ${count(clean)} Diagnosen (erwartet 0)`);

  // Completion: in the model from the model, in the script from the models next to
  // it, which the extension sends to the server
  let labels = await completions(clean, 'implies self.');
  check(labels.includes('leser') && labels.includes('titel'), `Vervollständigung nach self.: ${labels.slice(0, 6).join(', ')}`);
  labels = await completions(cleanScript, '!ada.');
  check(labels.includes('ausweis') && labels.includes('ausleihen'),
    `Vervollständigung im Skript nach ada.: ${labels.slice(0, 6).join(', ')}`);
  await vscode.commands.executeCommand('workbench.action.revertAndCloseActiveEditor');
}

exports.run = async function run() {
  try {
    await suite();
  } catch (e) {
    check(false, `Ausnahme: ${e.stack || e}`);
  }
  fs.writeFileSync(process.env.USE_E2E_RESULT, `${lines.join('\n')}\n`);
  if (lines.some((l) => l.startsWith('FAIL'))) {
    throw new Error('VS-Code-Test fehlgeschlagen');
  }
};
