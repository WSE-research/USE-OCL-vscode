'use strict';
// Turns the output of test/server-test.js or test/e2e/run.js into a JUnit report, so
// that the workflow can show these tests next to the unit tests. Each "ok   ..." or
// "FAIL ..." line is a test case; indented lines after it are its details.
//
//   node junit-from-log.js <suite name> <log file> <exit code> <report.xml>
//
// A run that failed without a FAIL line (a crash, or VS Code did not start) becomes
// one failed test case with the end of the log.

const fs = require('fs');
const path = require('path');

const [suite, logFile, exitCode, report] = process.argv.slice(2);
if (!report) {
  console.error('usage: node junit-from-log.js <suite name> <log file> <exit code> <report.xml>');
  process.exit(2);
}

const log = fs.existsSync(logFile) ? fs.readFileSync(logFile, 'utf8') : '';
const cases = [];
for (const line of log.split(/\r?\n/)) {
  const m = /^(ok  |FAIL) (.*)$/.exec(line);
  if (m) {
    cases.push({ name: m[2].trim(), failed: m[1] === 'FAIL', details: [] });
  } else if (/^\s{5}\S/.test(line) && cases.length > 0) {
    cases[cases.length - 1].details.push(line.trim());
  }
}
if (Number(exitCode) !== 0 && !cases.some((c) => c.failed)) {
  const tail = log.split(/\r?\n/).slice(-40).join('\n');
  cases.push({ name: `${suite} finished`, failed: true, details: [`exit code ${exitCode}`, tail] });
}

const xml = (s) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c])
  // characters XML 1.0 does not allow, e.g. terminal escape sequences
  .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '');
const failures = cases.filter((c) => c.failed).length;
const body = cases.map((c) => {
  const open = `    <testcase classname="${xml(suite)}" name="${xml(c.name)}" time="0"`;
  if (!c.failed) {
    return `${open}/>`;
  }
  const details = c.details.join('\n');
  return `${open}>\n      <failure message="${xml(c.details[0] || c.name)}">${xml(details)}</failure>\n    </testcase>`;
});
fs.mkdirSync(path.dirname(report), { recursive: true });
fs.writeFileSync(report, [
  '<?xml version="1.0" encoding="UTF-8"?>',
  `<testsuites name="${xml(suite)}" tests="${cases.length}" failures="${failures}" errors="0">`,
  `  <testsuite name="${xml(suite)}" tests="${cases.length}" failures="${failures}" errors="0" skipped="0" time="0">`,
  ...body,
  '  </testsuite>',
  '</testsuites>',
  '',
].join('\n'));
console.log(`${report}: ${cases.length} test cases, ${failures} failed`);
