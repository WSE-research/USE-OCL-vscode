'use strict';
// VS Code extension for USE (UML-based Specification Environment): highlighting
// via the grammars in syntaxes/, validation and auto-completion via the language
// server in server/ (Java, uses USE's own compiler). The server runs in the container
// image of run-use.sh or with a local Java 21 and a USE installation.

const vscode = require('vscode');
const cp = require('child_process');
const fs = require('fs');
const path = require('path');
const { LanguageClient } = require('vscode-languageclient/node');

const MAIN_CLASS = 'de.htwk.uselsp.Main';

let output;
let client;
let queue = Promise.resolve();

function activate(context) {
  output = vscode.window.createOutputChannel('USE');
  context.subscriptions.push(
    output,
    vscode.commands.registerCommand('useOcl.restartServer', () => restart(context)),
    vscode.commands.registerCommand('useOcl.buildImage', () => buildImage(context)),
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration('useOcl') && !e.affectsConfiguration('useOcl.trace')) {
        restart(context);
      }
    }),
  );
  return restart(context);
}

function deactivate() {
  return stop();
}

/** (Re)starts the server; multiple calls run one after another. */
function restart(context) {
  queue = queue
    .then(async () => {
      await stop();
      await start(context);
    })
    .catch((e) => output.appendLine(`Fehler: ${e.stack || e}`));
  return queue;
}

async function stop() {
  if (!client) {
    return;
  }
  const old = client;
  client = undefined;
  try {
    await old.stop();
  } catch (e) {
    output.appendLine(`Beim Beenden des Sprachservers: ${e}`);
  }
}

async function start(context) {
  const problems = { messages: [], imageMissing: false };
  const launch = await findLaunch(context, problems);
  if (!launch) {
    report(problems);
    return;
  }
  output.appendLine(`Sprachserver (${launch.how}): ${[launch.command, ...launch.args].join(' ')}`);
  client = new LanguageClient(
    'useOcl',
    'USE',
    { command: launch.command, args: launch.args },
    {
      documentSelector: [{ language: 'use' }, { language: 'soil' }],
      outputChannel: output,
    },
  );
  client.onRequest('useOcl/models', (params) => modelsNextTo(params.uri));
  await client.start();
}

/**
 * The models (.use) in the folder of a Soil script, for completion in the
 * script; open models with their text from the editor. The server does not read
 * any files itself.
 */
async function modelsNextTo(uri) {
  const script = vscode.Uri.parse(uri);
  const openModels = vscode.workspace.textDocuments.filter((d) => d.languageId === 'use');
  if (script.scheme === 'untitled') {
    return openModels.map((d) => ({ uri: d.uri.toString(), text: d.getText() }));
  }
  const folder = vscode.Uri.joinPath(script, '..');
  let entries;
  try {
    entries = await vscode.workspace.fs.readDirectory(folder);
  } catch {
    return [];
  }
  const names = entries
    .filter(([name, type]) => type === vscode.FileType.File && name.endsWith('.use'))
    .map(([name]) => name)
    .slice(0, 20);
  const models = [];
  for (const name of names) {
    const file = vscode.Uri.joinPath(folder, name);
    const doc = openModels.find((d) => d.uri.toString() === file.toString());
    try {
      const text = doc ? doc.getText() : new TextDecoder().decode(await vscode.workspace.fs.readFile(file));
      models.push({ uri: file.toString(), text });
    } catch {
      // not readable: leave this model out
    }
  }
  return models;
}

/** How the server can start: local Java or container, depending on useOcl.server.mode. */
async function findLaunch(context, problems) {
  const cfg = vscode.workspace.getConfiguration('useOcl');
  const mode = cfg.get('server.mode', 'auto');
  const serverDir = context.asAbsolutePath('server');
  if (mode !== 'container') {
    const launch = await javaLaunch(cfg, serverDir, problems);
    if (launch || mode === 'java') {
      return launch;
    }
  }
  return containerLaunch(cfg, serverDir, problems);
}

async function javaLaunch(cfg, serverDir, problems) {
  const useHome = cfg.get('useHome') || process.env.USE_HOME;
  if (!useHome) {
    problems.messages.push('Ohne Container: keine USE-Installation eingestellt (useOcl.useHome).');
    return undefined;
  }
  const lib = path.join(useHome, 'lib');
  if (!hasUseCore(lib)) {
    problems.messages.push(`Ohne Container: ${lib} enthält keine use-core-*.jar.`);
    return undefined;
  }
  const javaHome = cfg.get('javaHome') || process.env.JAVA_HOME;
  const java = javaHome ? path.join(javaHome, 'bin', 'java') : 'java';
  const version = await javaVersion(java);
  if (version === undefined) {
    problems.messages.push(`Ohne Container: ${java} lässt sich nicht starten.`);
    return undefined;
  }
  if (version < 21) {
    problems.messages.push(`Ohne Container: ${java} ist Java ${version}, USE 7.5 braucht Java 21.`);
    return undefined;
  }
  const classpath = [path.join(serverDir, '*'), path.join(lib, '*')].join(path.delimiter);
  return {
    how: `Java ${version}`,
    command: java,
    args: [`-Duse.home=${useHome}`, '-cp', classpath, MAIN_CLASS],
  };
}

async function containerLaunch(cfg, serverDir, problems) {
  const engine = await findEngine(cfg.get('container.engine', 'auto'));
  if (!engine) {
    problems.messages.push(
      'Container: Weder Docker noch Podman ist nutzbar (bei Docker: Dienst gestartet und Benutzer in der Gruppe docker?).',
    );
    return undefined;
  }
  const image = cfg.get('container.image', 'use-ocl:7.5.0');
  if (!(await run(engine[0], [...engine.slice(1), 'image', 'inspect', image])).ok) {
    problems.messages.push(
      `Container: Das Image ${image} fehlt noch. „USE: Container-Image bauen“ legt es an (oder einmal ./run-use.sh).`,
    );
    problems.imageMissing = true;
    return undefined;
  }
  // The server only reads the text from the editor: no network, only its own code mounted
  const args = [...engine.slice(1), 'run', '--rm', '-i', '--network', 'none', '-v', `${serverDir}:/lsp:ro`];
  if (process.platform === 'linux' && (await run('selinuxenabled', [])).ok) {
    args.push('--security-opt', 'label=disable');
  }
  args.push('--entrypoint', 'java', image, '-Duse.home=/opt/use', '-cp', '/lsp/*:/opt/use/lib/*', MAIN_CLASS);
  return { how: engine.join(' '), command: engine[0], args };
}

/**
 * The container tool as in run-use.sh: the first one whose `info` succeeds;
 * otherwise Podman with its own storage under /var/tmp (USE_PODMAN_STORE).
 */
async function findEngine(preference) {
  const candidates = preference === 'auto' ? ['docker', 'podman'] : [preference];
  for (const c of candidates) {
    if ((await run(c, ['info'])).ok) {
      return [c];
    }
  }
  if (process.platform !== 'linux') {
    return undefined;
  }
  for (const c of candidates) {
    if (!/podman/i.test((await run(c, ['--version'])).out)) {
      continue;
    }
    const uid = process.getuid();
    const store = process.env.USE_PODMAN_STORE || `/var/tmp/use-ocl-${uid}`;
    const runroot = path.join(process.env.XDG_RUNTIME_DIR || '/tmp', `use-ocl-${uid}`);
    fs.mkdirSync(store, { recursive: true });
    fs.mkdirSync(runroot, { recursive: true });
    const engine = [c, '--root', path.join(store, 'storage'), '--runroot', runroot, '--storage-opt', 'ignore_chown_errors=true'];
    if ((await run(c, [...engine.slice(1), 'info'])).ok) {
      return engine;
    }
  }
  return undefined;
}

/** Builds the image from container/Dockerfile (the same image as run-use.sh in the SE exercises). */
async function buildImage(context) {
  const cfg = vscode.workspace.getConfiguration('useOcl');
  const engine = await findEngine(cfg.get('container.engine', 'auto'));
  if (!engine) {
    vscode.window.showErrorMessage('USE: Weder Docker noch Podman ist nutzbar.');
    return;
  }
  const image = cfg.get('container.image', 'use-ocl:7.5.0');
  const name = `Image ${image} bauen`;
  const task = new vscode.Task(
    { type: 'useOcl' },
    vscode.TaskScope.Global,
    name,
    'USE',
    new vscode.ProcessExecution(engine[0], [...engine.slice(1), 'build', '-t', image, context.asAbsolutePath('container')]),
  );
  const finished = new Promise((resolve) => {
    const listener = vscode.tasks.onDidEndTaskProcess((e) => {
      if (e.execution.task.source === 'USE' && e.execution.task.name === name) {
        listener.dispose();
        resolve(e.exitCode);
      }
    });
  });
  await vscode.tasks.executeTask(task);
  const exitCode = await finished;
  if (exitCode === 0) {
    vscode.window.showInformationMessage(`USE: Image ${image} ist gebaut, der Sprachserver startet.`);
    await restart(context);
  } else {
    vscode.window.showErrorMessage(`USE: Das Image ließ sich nicht bauen (Exit-Code ${exitCode}), Details im Terminal.`);
  }
}

function report(problems) {
  for (const m of problems.messages) {
    output.appendLine(m);
  }
  const actions = problems.imageMissing ? ['Image bauen', 'Einstellungen', 'Ausgabe'] : ['Einstellungen', 'Ausgabe'];
  const last = problems.messages[problems.messages.length - 1] || '';
  vscode.window
    .showWarningMessage(`USE: Der Sprachserver startet nicht, Dateien werden nur hervorgehoben. ${last}`, ...actions)
    .then((choice) => {
      if (choice === 'Image bauen') {
        vscode.commands.executeCommand('useOcl.buildImage');
      } else if (choice === 'Einstellungen') {
        vscode.commands.executeCommand('workbench.action.openSettings', 'useOcl');
      } else if (choice === 'Ausgabe') {
        output.show();
      }
    });
}

function hasUseCore(lib) {
  try {
    return fs.readdirSync(lib).some((f) => /^use-core-.*\.jar$/.test(f));
  } catch {
    return false;
  }
}

async function javaVersion(java) {
  const r = await run(java, ['-version']);
  const m = /version "(\d+)(?:\.(\d+))?/.exec(r.out);
  if (!r.ok || !m) {
    return undefined;
  }
  return m[1] === '1' ? Number(m[2]) : Number(m[1]);
}

/** Runs a program without a shell; out contains stdout and stderr. */
function run(command, args, timeout = 30000) {
  return new Promise((resolve) => {
    cp.execFile(command, args, { timeout, windowsHide: true }, (err, stdout, stderr) => {
      resolve({ ok: !err, out: `${stdout}${stderr}` });
    });
  });
}

module.exports = { activate, deactivate };
