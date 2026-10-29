'use strict';
// Builds the report of the workflow "Tests and analysis" in Markdown: test results
// (JUnit reports), coverage (JaCoCo XML) and every problem Qodana found (SARIF). It
// goes to the job summary and to the pull request as a comment; a later run updates
// that comment. Without a pull request (a direct push, "Run workflow") only the job
// summary gets it.
//
// Environment: TEST_GROUPS (lines "label|directory with JUnit XML files"),
// COVERAGE_XML, QODANA_SARIF, QODANA_OUTCOME, QODANA_LINTER, REPORT_FILE, GITHUB_TOKEN and the
// variables GitHub Actions sets. Node.js 22, no packages.

const fs = require('fs');
const path = require('path');

const MARKER = '<!-- use-ocl-ci-report';
// GitHub accepts at most 65536 characters per comment; longer reports are split
const LIMIT = 60000;
const SEVERITIES = ['Critical', 'High', 'Moderate', 'Low', 'Info'];

const env = process.env;
const repo = env.GITHUB_REPOSITORY || '';
const sha = env.GITHUB_SHA || '';
const server = env.GITHUB_SERVER_URL || 'https://github.com';
const runUrl = `${server}/${repo}/actions/runs/${env.GITHUB_RUN_ID}`;

// ---------------------------------------------------------------- input

function read(file) {
  return file && fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
}

function unescape(s) {
  return s.replace(/&(lt|gt|quot|apos|amp|#(\d+)|#x([0-9a-fA-F]+));/g, (m, name, dec, hex) => {
    if (dec) return String.fromCodePoint(Number(dec));
    if (hex) return String.fromCodePoint(parseInt(hex, 16));
    return { lt: '<', gt: '>', quot: '"', apos: "'", amp: '&' }[name];
  });
}

function attributes(tag) {
  const result = {};
  for (const m of tag.matchAll(/([\w:.-]+)="([^"]*)"/g)) {
    result[m[1]] = unescape(m[2]);
  }
  return result;
}

function xmlFiles(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = path.join(dir, e.name);
    return e.isDirectory() ? xmlFiles(p) : e.name.endsWith('.xml') ? [p] : [];
  });
}

/** Test cases of all JUnit reports in a directory, or null if there are none. */
function testCases(dir) {
  const files = xmlFiles(dir);
  if (files.length === 0) return null;
  const cases = [];
  for (const file of files) {
    const text = read(file);
    for (const m of text.matchAll(/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)) {
      const a = attributes(m[1]);
      const body = m[2] || '';
      const problem = /<(failure|error)\b([^>]*?)(?:\/>|>([\s\S]*?)<\/\1>)/.exec(body);
      cases.push({
        name: a.classname && !a.name.includes(a.classname) ? `${a.classname.replace(/^.*\./, '')} › ${a.name}` : a.name,
        time: Number(a.time) || 0,
        skipped: /<skipped\b/.test(body),
        failed: problem !== null,
        message: problem ? unescape(attributes(problem[2]).message || problem[3] || '').trim() : '',
      });
    }
  }
  return cases;
}

function groups() {
  return (env.TEST_GROUPS || '').split('\n').map((l) => l.trim()).filter(Boolean).map((l) => {
    const [label, dir] = l.split('|').map((s) => s.trim());
    return { label, cases: testCases(dir) };
  });
}

/** Line and branch counters per source file from a JaCoCo XML report. */
function coverage(file) {
  const text = read(file);
  if (!text) return null;
  const files = [];
  for (const pkg of text.matchAll(/<package name="([^"]*)">([\s\S]*?)<\/package>/g)) {
    for (const m of pkg[2].matchAll(/<sourcefile name="([^"]*)">([\s\S]*?)<\/sourcefile>/g)) {
      const counters = {};
      for (const c of m[2].matchAll(/<counter ([^>]*)\/>/g)) {
        const a = attributes(c[1]);
        counters[a.type] = { missed: Number(a.missed), covered: Number(a.covered) };
      }
      files.push({ name: `${pkg[1]}/${m[1]}`, line: counters.LINE, branch: counters.BRANCH });
    }
  }
  return files.sort((a, b) => a.name.localeCompare(b.name));
}

function qodana(file) {
  const text = read(file);
  if (!text) return null;
  const run = JSON.parse(text).runs[0];
  const rules = {};
  for (const component of [run.tool.driver, ...(run.tool.extensions || [])]) {
    for (const r of component.rules || []) {
      rules[r.id] = r.shortDescription ? r.shortDescription.text : r.id;
    }
  }
  const problems = (run.results || []).map((r) => {
    const loc = (r.locations || [])[0] || {};
    const physical = loc.physicalLocation || {};
    const region = physical.region || {};
    return {
      rule: r.ruleId,
      title: rules[r.ruleId] || r.ruleId,
      severity: (r.properties && r.properties.qodanaSeverity) || r.level || 'Info',
      file: physical.artifactLocation ? physical.artifactLocation.uri : '',
      line: region.startLine || 0,
      column: region.startColumn || 0,
      message: r.message ? r.message.text : '',
    };
  });
  const order = (p) => [p.file, String(p.line).padStart(6, '0'), String(p.column).padStart(4, '0')].join(' ');
  problems.sort((a, b) => order(a).localeCompare(order(b)));
  const props = run.properties || {};
  const linter = env.QODANA_LINTER === 'qodana-jvm' ? 'Qodana for JVM' : 'Qodana Community for JVM';
  return {
    tool: `${linter}${run.tool.driver.version ? ` (build ${run.tool.driver.version})` : ''}`,
    profile: props.configProfile,
    coverage: props.coverage,
    problems,
  };
}

// ---------------------------------------------------------------- Markdown

const cell = (s) => String(s).replace(/\|/g, '\\|').replace(/\r?\n/g, '<br>');
const percent = (c) => (!c || c.missed + c.covered === 0 ? '–' : `${((100 * c.covered) / (c.missed + c.covered)).toFixed(1)} %`);
const ratio = (c) => (!c ? '–' : `${c.covered} / ${c.missed + c.covered} (${percent(c)})`);
const sum = (list, key) => list.reduce((t, f) => (f[key] ? { missed: t.missed + f[key].missed, covered: t.covered + f[key].covered } : t),
  { missed: 0, covered: 0 });

/** The report as blocks: Markdown text, or tables, which a split may continue. */
function report() {
  const blocks = [];
  const text = (s) => blocks.push(s);
  const table = (header, rows) => blocks.push({ header, rows });

  const pr = env.GITHUB_EVENT_NAME === 'pull_request';
  text(`## Tests and analysis\n\n${pr ? 'Pull request' : `After the push to \`${env.GITHUB_REF_NAME}\``}, commit ${sha.slice(0, 7)}, [workflow run](${runUrl})`);

  const results = groups();
  const all = results.flatMap((g) => g.cases || []);
  const failed = all.filter((c) => c.failed);
  const notRun = results.filter((g) => g.cases === null);
  const headline = failed.length > 0 ? `❌ ${failed.length} of ${all.length} tests failed`
    : notRun.length > 0 ? `⚠️ ${all.length} tests passed, ${notRun.length} suite(s) did not run`
      : `✅ all ${all.length} tests passed`;
  text(`### Tests: ${headline}`);
  table('| Suite | Tests | Passed | Failed | Skipped |\n|---|---:|---:|---:|---:|', results.map((g) => {
    if (g.cases === null) return `| ${cell(g.label)} | – | – | – | did not run |`;
    const f = g.cases.filter((c) => c.failed).length;
    const s = g.cases.filter((c) => c.skipped).length;
    return `| ${cell(g.label)} | ${g.cases.length} | ${g.cases.length - f - s} | ${f ? `**${f}**` : 0} | ${s} |`;
  }));
  if (failed.length > 0) {
    text('Failed tests:');
    table('| Test | Message |\n|---|---|', failed.map((c) => `| ${cell(c.name)} | ${cell(c.message.split('\n').slice(0, 6).join('\n'))} |`));
  }

  const files = coverage(env.COVERAGE_XML);
  if (files && files.length > 0) {
    text(`### Coverage: ${percent(sum(files, 'line'))} of lines, ${percent(sum(files, 'branch'))} of branches\n\n`
      + 'Measured with JaCoCo while the unit tests and the tests that start the language server with local Java ran.');
    table('| File | Lines covered | Branches covered |\n|---|---:|---:|',
      [...files.map((f) => `| ${cell(f.name)} | ${ratio(f.line)} | ${ratio(f.branch)} |`),
        `| **Total** | **${ratio(sum(files, 'line'))}** | **${ratio(sum(files, 'branch'))}** |`]);
  } else {
    text('### Coverage\n\nNo coverage data: the build or the tests did not run.');
  }

  const q = qodana(env.QODANA_SARIF);
  if (!q) {
    text(`### Qodana\n\nNo results: the analysis ${env.QODANA_OUTCOME === 'skipped' ? 'did not run' : 'failed'}; see the workflow run.`);
  } else {
    const n = q.problems.length;
    text(`### Qodana: ${n === 0 ? '✅ no problems' : `${n} problem${n === 1 ? '' : 's'}`}\n\n`
      + `${q.tool}${q.profile ? `, profile \`${q.profile}\`` : ''}, configured in \`qodana.yaml\`. The HTML report is the artifact \`qodana-report\` of the [workflow run](${runUrl}).`);
    if (q.coverage && typeof q.coverage === 'object') {
      table('| Coverage (Qodana) | Value |\n|---|---:|', Object.entries(q.coverage).map(([k, v]) => `| ${cell(k)} | ${cell(v)} |`));
    }
    if (n > 0) {
      const bySeverity = SEVERITIES.map((s) => [s, q.problems.filter((p) => p.severity === s).length]).filter(([, c]) => c > 0);
      table('| Severity | Problems |\n|---|---:|', bySeverity.map(([s, c]) => `| ${s} | ${c} |`));
      const byRule = new Map();
      for (const p of q.problems) {
        const key = `${p.rule}\u0000${p.severity}`;
        byRule.set(key, { ...p, count: ((byRule.get(key) || {}).count || 0) + 1 });
      }
      table('| Inspection | Severity | Problems |\n|---|---|---:|',
        [...byRule.values()].sort((a, b) => SEVERITIES.indexOf(a.severity) - SEVERITIES.indexOf(b.severity) || b.count - a.count)
          .map((r) => `| ${cell(r.title)} (\`${r.rule}\`) | ${r.severity} | ${r.count} |`));
      text('#### All problems');
      table('| Location | Severity | Inspection | Message |\n|---|---|---|---|', q.problems.map((p) => {
        const where = p.file ? `[${cell(p.file)}:${p.line}](${server}/${repo}/blob/${sha}/${p.file}#L${p.line})` : '–';
        return `| ${where} | ${p.severity} | ${cell(p.title)} | ${cell(p.message)} |`;
      }));
    }
  }
  return blocks;
}

/** Splits the blocks into comments of at most LIMIT characters; a table goes on with its header. */
function split(blocks) {
  const parts = [];
  let current = '';
  const push = (s) => {
    if (current && current.length + s.length + 2 > LIMIT) {
      parts.push(current);
      current = '';
    }
    current += (current ? '\n\n' : '') + s;
  };
  for (const b of blocks) {
    if (typeof b === 'string') {
      push(b);
      continue;
    }
    let rows = b.header;
    for (const r of b.rows) {
      if (current.length + rows.length + r.length + 3 > LIMIT && rows !== b.header) {
        push(rows);
        parts.push(current);
        current = '';
        rows = `*(continued)*\n\n${b.header}`;
      }
      rows += `\n${r}`;
    }
    push(rows);
  }
  if (current) parts.push(current);
  return parts;
}

// ---------------------------------------------------------------- GitHub

async function api(method, url, body) {
  const response = await fetch(`${env.GITHUB_API_URL || 'https://api.github.com'}${url}`, {
    method,
    headers: {
      Authorization: `Bearer ${env.GITHUB_TOKEN}`,
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    throw new Error(`${method} ${url}: ${response.status} ${await response.text()}`);
  }
  return response.status === 204 ? null : response.json();
}

/** The pull request of this run: the one it runs for, or the one whose merge it built. */
async function pullRequest() {
  const event = JSON.parse(read(env.GITHUB_EVENT_PATH) || '{}');
  if (event.pull_request) return event.pull_request.number;
  if (env.GITHUB_EVENT_NAME !== 'push') return null;
  const pulls = await api('GET', `/repos/${repo}/commits/${sha}/pulls`);
  const merged = pulls.find((p) => p.merge_commit_sha === sha) || pulls.find((p) => p.merged_at);
  return merged ? merged.number : null;
}

async function comment(number, parts) {
  const existing = [];
  for (let page = 1; ; page++) {
    const list = await api('GET', `/repos/${repo}/issues/${number}/comments?per_page=100&page=${page}`);
    existing.push(...list.filter((c) => c.user.type === 'Bot' && c.body.startsWith(MARKER)));
    if (list.length < 100) break;
  }
  for (let i = 0; i < parts.length; i++) {
    const header = `${MARKER} part ${i + 1} -->${parts.length > 1 ? `\n*Part ${i + 1} of ${parts.length}*\n` : ''}\n`;
    const body = header + parts[i];
    if (existing[i]) {
      await api('PATCH', `/repos/${repo}/issues/comments/${existing[i].id}`, { body });
    } else {
      await api('POST', `/repos/${repo}/issues/${number}/comments`, { body });
    }
  }
  // parts left over from a longer earlier report
  for (const old of existing.slice(parts.length)) {
    await api('DELETE', `/repos/${repo}/issues/comments/${old.id}`);
  }
}

async function main() {
  const parts = split(report());
  const markdown = parts.join('\n\n');
  if (env.REPORT_FILE) {
    fs.mkdirSync(path.dirname(env.REPORT_FILE), { recursive: true });
    fs.writeFileSync(env.REPORT_FILE, `${markdown}\n`);
  }
  if (env.GITHUB_STEP_SUMMARY) {
    fs.appendFileSync(env.GITHUB_STEP_SUMMARY, `${markdown}\n`);
  }
  if (!env.GITHUB_TOKEN) {
    console.log(markdown);
    return;
  }
  const number = await pullRequest();
  if (number === null) {
    console.log('No pull request for this run: the report is in the job summary only.');
    return;
  }
  await comment(number, parts);
  console.log(`Report posted to ${server}/${repo}/pull/${number} (${parts.length} comment${parts.length === 1 ? '' : 's'}).`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
