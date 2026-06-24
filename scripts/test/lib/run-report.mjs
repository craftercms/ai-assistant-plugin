/**
 * Collect and print per-test results for run-all and chat scenario runners.
 * Append JSONL lines when RUN_ALL_REPORT_FILE (or --file) is set.
 */
import { appendFileSync, readFileSync, existsSync } from 'node:fs';

/** @typedef {'pass'|'fail'|'partial'|'skip'} TestStatus */

/**
 * @typedef {object} ReportEntry
 * @property {string} suite
 * @property {string} id
 * @property {string} [label]
 * @property {TestStatus} status
 * @property {string} [reason]
 * @property {number|null} [durationMs]
 */

const STATUS_ICON = {
  pass: '✅',
  fail: '❌',
  partial: '🟡',
  skip: '⏭',
};

/**
 * @param {string} filePath
 * @param {ReportEntry} entry
 */
export function appendEntry(filePath, entry) {
  if (!filePath?.trim()) return;
  const line = JSON.stringify({
    suite: entry.suite ?? '',
    id: entry.id ?? '',
    label: entry.label ?? '',
    status: entry.status ?? 'fail',
    reason: entry.reason ?? '',
    durationMs: entry.durationMs ?? null,
    ts: Date.now(),
  });
  appendFileSync(filePath, `${line}\n`, 'utf8');
}

/**
 * @param {string} filePath
 * @returns {ReportEntry[]}
 */
export function loadEntries(filePath) {
  if (!filePath || !existsSync(filePath)) return [];
  const out = [];
  for (const line of readFileSync(filePath, 'utf8').split('\n')) {
    const t = line.trim();
    if (!t) continue;
    try {
      out.push(JSON.parse(t));
    } catch {
      // ignore corrupt lines
    }
  }
  return out;
}

/**
 * @param {ReportEntry[]} entries
 */
export function summarizeEntries(entries) {
  return entries.reduce(
    (acc, e) => {
      const s = e.status || 'fail';
      if (s in acc) acc[s] += 1;
      else acc.fail += 1;
      return acc;
    },
    { pass: 0, partial: 0, fail: 0, skip: 0 },
  );
}

/**
 * @param {ReportEntry[]} entries
 * @param {{ title?: string, groupBySuite?: boolean }} [opts]
 */
export function formatDetailedReport(entries, opts = {}) {
  const lines = [];
  const title = opts.title || 'Test report';
  lines.push('');
  lines.push(`======== ${title} ========`);

  const groupBySuite = opts.groupBySuite !== false;
  /** @type {Map<string, ReportEntry[]>} */
  const bySuite = new Map();
  for (const e of entries) {
    const suite = e.suite || '(unspecified)';
    if (!bySuite.has(suite)) bySuite.set(suite, []);
    bySuite.get(suite).push(e);
  }

  const suites = groupBySuite ? [...bySuite.keys()] : [''];
  for (const suite of suites) {
    const rows = groupBySuite ? bySuite.get(suite) : entries;
    if (groupBySuite && suite) {
      lines.push('');
      lines.push(`--- ${suite} ---`);
    }
    for (const r of rows) {
      const icon = STATUS_ICON[r.status] || '?';
      const dur = r.durationMs != null ? ` (${r.durationMs}ms)` : '';
      const label =
        r.label && r.label !== r.id ? `: ${r.label}` : r.label ? `: ${r.label}` : '';
      lines.push(`${icon} ${r.id}${label}${dur}`);
      if (r.reason && r.status !== 'pass') {
        lines.push(`       → ${r.reason}`);
      }
    }
  }

  const counts = summarizeEntries(entries);
  lines.push('');
  lines.push(
    `-------- ${counts.pass} passed, ${counts.partial} partial, ${counts.fail} failed, ${counts.skip} skipped (${entries.length} total) --------`,
  );
  return lines.join('\n');
}

/**
 * @param {ReportEntry[]} entries
 * @param {{ title?: string, groupBySuite?: boolean }} [opts]
 */
export function printDetailedReport(entries, opts = {}) {
  console.log(formatDetailedReport(entries, opts));
}

function parseArg(name) {
  const pref = `--${name}=`;
  const hit = process.argv.find((a) => a.startsWith(pref));
  return hit ? hit.slice(pref.length) : '';
}

function cliRecord() {
  const file = parseArg('file') || process.env.RUN_ALL_REPORT_FILE || '';
  const suite = parseArg('suite');
  const id = parseArg('id');
  const status = /** @type {TestStatus} */ (parseArg('status') || 'fail');
  const label = parseArg('label');
  const reason = parseArg('reason');
  const durationRaw = parseArg('duration-ms');
  const durationMs = durationRaw ? Number(durationRaw) : null;
  if (!file || !id) {
    console.error('run-report record requires --file and --id');
    process.exit(2);
  }
  appendEntry(file, { suite, id, label, status, reason, durationMs });
}

function cliPrint() {
  const file = parseArg('file') || process.env.RUN_ALL_REPORT_FILE || '';
  const title = parseArg('title') || 'run-all: complete test report';
  const entries = loadEntries(file);
  if (!entries.length) {
    console.log('');
    console.log(`======== ${title} ========`);
    console.log('(no test results recorded)');
    return;
  }
  printDetailedReport(entries, { title, groupBySuite: true });
}

if (process.argv[1]?.endsWith('run-report.mjs')) {
  const cmd = process.argv[2];
  if (cmd === 'record') cliRecord();
  else if (cmd === 'print') cliPrint();
  else {
    console.error('Usage: run-report.mjs record|print --file=... [--suite=] [--id=] [--status=] [--reason=]');
    process.exit(2);
  }
}
