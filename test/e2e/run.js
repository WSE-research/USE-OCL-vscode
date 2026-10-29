'use strict';
// Starts VS Code invisibly (xvfb-run) with the extension from this folder and a
// fresh profile and runs suite.js in it. Usage: node test/e2e/run.js [--java]
//   no flag:       language server in the container (the usual setup for students)
//   --java:        local Java 21 (JAVA_HOME) with USE from USE_HOME or .build/use

const cp = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const java = process.argv.includes('--java');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'use-vscode-e2e-'));
const result = path.join(tmp, 'result.txt');

const settings = java
  ? {
      'useOcl.server.mode': 'java',
      'useOcl.useHome': process.env.USE_HOME || path.join(ROOT, '.build', 'use'),
      'useOcl.javaHome': process.env.JAVA_HOME || '',
    }
  : { 'useOcl.server.mode': 'container', 'useOcl.container.engine': process.env.USE_ENGINE || 'auto' };
fs.mkdirSync(path.join(tmp, 'user', 'User'), { recursive: true });
fs.writeFileSync(
  path.join(tmp, 'user', 'User', 'settings.json'),
  JSON.stringify({
    ...settings,
    'security.workspace.trust.enabled': false,
    'update.mode': 'none',
    'extensions.autoUpdate': false,
    'telemetry.telemetryLevel': 'off',
    'workbench.startupEditor': 'none',
  }, null, 2),
);

console.log(`VS Code mit Sprachserver ${java ? 'über lokales Java' : 'im Container'} ...`);
const args = ['-a', '-s', '-screen 0 1280x800x24', 'code', '--wait',
  `--extensionDevelopmentPath=${ROOT}`,
  `--extensionTestsPath=${path.join(__dirname, 'suite.js')}`,
  `--user-data-dir=${path.join(tmp, 'user')}`,
  `--extensions-dir=${path.join(tmp, 'extensions')}`,
  '--disable-workspace-trust', '--skip-welcome', '--skip-release-notes', '--disable-gpu',
  path.join(ROOT, 'test', 'samples')];
const run = cp.spawnSync('xvfb-run', args, {
  stdio: ['ignore', 'pipe', 'pipe'],
  env: { ...process.env, USE_E2E_RESULT: result },
  timeout: 300000,
});

const report = fs.existsSync(result) ? fs.readFileSync(result, 'utf8') : '';
fs.rmSync(tmp, { recursive: true, force: true });
if (!report) {
  console.log('FAIL keine Ergebnisse aus VS Code');
  console.log(String(run.stdout).slice(-3000), String(run.stderr).slice(-3000));
  process.exit(1);
}
process.stdout.write(report);
process.exit(report.includes('FAIL') ? 1 : 0);
