#!/usr/bin/env node
/**
 * Offline parity for stylesheet write guard heuristics (mirror CmsStylesheetWriteGuard).
 */
function normalizeBody(raw) {
  let s = (raw ?? '').toString();
  if (!s.trim()) return '';
  return s.replace(/^<\?xml[^?]*\?>\s*/is, '');
}

function looksLikePlaceholder(body) {
  const s = normalizeBody(body).trim();
  if (!s) return true;
  const lower = s.toLowerCase();
  if (lower === '/*placeholder*/' || lower === 'placeholder') return true;
  if (lower.includes('placeholder') && s.length < 512) return true;
  if (s.length < 32) return true;
  return false;
}

function countChar(s, ch) {
  let n = 0;
  for (const c of s) if (c === ch) n++;
  return n;
}

function assertPreservedStructure(path, baseline, proposed) {
  const b = normalizeBody(baseline);
  const p = normalizeBody(proposed);
  if (looksLikePlaceholder(p)) {
    throw new Error(`placeholder rejected for ${path}`);
  }
  const bLen = b.length;
  const pLen = p.length;
  if (bLen > 400 && pLen < bLen * 0.75) {
    throw new Error(`truncated: ${pLen} vs ${bLen}`);
  }
  const bBlocks = countChar(b, '{');
  const pBlocks = countChar(p, '{');
  if (bBlocks >= 4 && pBlocks < bBlocks - 1) {
    throw new Error(`blocks dropped: ${pBlocks} vs ${bBlocks}`);
  }
}

const baseline = `:root { --accent-color: #175cdd; }
.btn { color: #175cdd; background: #fff; padding: 1rem; }
.nav a:hover { color: var(--accent-color); }
@media (min-width: 768px) { .hero { margin: 2rem; } }`;

const good = baseline.replaceAll('#175cdd', '#c41e3a');
const badPlaceholder = '<?xml version="1.0"?>\n/*placeholder*/';
const badTruncated = ':root { --accent-color: #c41e3a; }';

const errors = [];
try {
  assertPreservedStructure('/static-assets/app/css/main.css', baseline, good);
} catch (e) {
  errors.push(`good edit should pass: ${e.message}`);
}
for (const [label, body] of [
  ['placeholder', badPlaceholder],
  ['truncated', badTruncated],
]) {
  try {
    assertPreservedStructure('/static-assets/app/css/main.css', baseline, body);
    errors.push(`${label} should fail but passed`);
  } catch {
    /* expected */
  }
}

if (errors.length) {
  console.error('stylesheet-write-guard-parity FAILED:');
  for (const e of errors) console.error(`  - ${e}`);
  process.exit(1);
}
console.log('stylesheet-write-guard-parity OK');
