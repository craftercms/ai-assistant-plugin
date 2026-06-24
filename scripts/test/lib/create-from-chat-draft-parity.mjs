/**
 * JavaScript parity for create-from-chat-draft prefetch helpers (offline tests only).
 */

/** @param {string} phrase */
export function normalizeCatalogMatchPhrase(phrase) {
  let s = String(phrase ?? '')
    .trim()
    .toLowerCase();
  s = s.replace(/\//g, ' ');
  s = s.replace(/[-_]/g, ' ');
  s = s.replace(/\s+/g, ' ').trim();
  return s;
}

/**
 * @param {string} priorBody
 * @param {string} currentRequest
 */
export function inferCreateTypePhraseCandidates(priorBody, currentRequest) {
  const phrases = new Set();
  const prior = String(priorBody ?? '');
  const current = String(currentRequest ?? '');

  if (/\b(?:create|make)\b.{0,48}\b(?:technical\s+)?(?:blog\s+)?post\b/is.test(current)) {
    phrases.add('article');
    phrases.add('blog post');
    phrases.add('post');
  }
  if (/\b(?:blog\s+)?post\b/is.test(current)) {
    phrases.add('post');
    phrases.add('blog post');
    phrases.add('article');
  }
  if (/\btechnical\s+blog\s+post\b/is.test(current)) {
    phrases.add('article');
    phrases.add('blog post');
    phrases.add('post');
  }
  if (/\bblog\s+page\b/is.test(current)) {
    phrases.add('post');
    phrases.add('blog post');
    phrases.add('blog');
  }
  if (/\barticle\b/is.test(current)) {
    phrases.add('article');
    phrases.add('blog post');
  }
  if (/\bdraft\s+(?:a\s+)?(?:\w+\s+){0,3}blog\b/is.test(prior) || /\bblog\b/is.test(prior)) {
    phrases.add('blog post');
    phrases.add('post');
    phrases.add('article');
  }
  if (/\bpost\b/is.test(prior)) {
    phrases.add('post');
    phrases.add('blog post');
  }
  if (phrases.size === 0) {
    phrases.add('article');
    phrases.add('blog post');
    phrases.add('post');
  }
  if (phrases.has('blog post') || phrases.has('article') || phrases.has('post')) {
    phrases.delete('blog');
  }
  const ordered = [];
  for (const preferred of ['article', 'blog post', 'post', 'blog']) {
    if (phrases.has(preferred)) {
      ordered.push(preferred);
    }
  }
  for (const p of phrases) {
    if (!ordered.includes(p)) {
      ordered.push(p);
    }
  }
  return ordered;
}

/**
 * @param {Record<string, string>} row
 * @param {string[]} phraseCandidates
 */
function scoreCatalogRowForCreatePhrases(row, phraseCandidates) {
  const name = String(row.name ?? '').trim();
  const label = String(row.label ?? '').trim();
  const labelNorm = normalizeCatalogMatchPhrase(label);
  const nameNorm = normalizeCatalogMatchPhrase(name);
  const tail = name.includes('/') ? name.slice(name.lastIndexOf('/') + 1) : name;
  const tailNorm = normalizeCatalogMatchPhrase(tail);
  let score = 0;
  for (const phrase of phraseCandidates) {
    const norm = normalizeCatalogMatchPhrase(phrase);
    if (!norm) {
      continue;
    }
    if (norm === tailNorm) {
      score = Math.max(score, norm.length >= 5 ? 90 : 70);
    } else if (norm === labelNorm || norm === nameNorm) {
      score = Math.max(score, 95);
    } else if (labelNorm.endsWith(` ${norm}`) || labelNorm.includes(` ${norm} `)) {
      score = Math.max(score, 85);
    } else if (labelNorm.includes(norm)) {
      score = Math.max(score, norm.length >= 4 ? 68 : 48);
    }
    if ((norm === 'article' || norm.includes('post')) && tailNorm === 'article') {
      score = Math.max(score, 92);
    }
    if (norm.includes('blog post') && labelNorm.includes('blog post')) {
      score = Math.max(score, 94);
    }
  }
  if (labelNorm.includes(' roll') || labelNorm.includes(' listing') || labelNorm.endsWith(' roll')) {
    score -= 55;
  }
  if (tailNorm === 'blog' && labelNorm.includes('blog roll')) {
    score -= 45;
  }
  return Math.max(0, score);
}

/**
 * @param {Array<Record<string, string>>} typeRows
 * @param {string[]} phraseCandidates
 */
export function resolveCreateContentTypeFromCatalog(typeRows, phraseCandidates) {
  if (!Array.isArray(typeRows) || !Array.isArray(phraseCandidates) || phraseCandidates.length === 0) {
    return '';
  }
  let bestId = '';
  let bestScore = 0;
  for (const row of typeRows) {
    const score = scoreCatalogRowForCreatePhrases(row, phraseCandidates);
    if (score > bestScore) {
      bestScore = score;
      bestId = String(row.name ?? '').trim();
    }
  }
  return bestScore >= 55 ? bestId : '';
}

/** @param {string} text */
export function assistantBlockLooksLikeToolStrip(text) {
  const t = String(text ?? '').trim();
  if (!t) {
    return true;
  }
  if (t.includes('🛠') || t.includes('\uD83D\uDEE0')) {
    return true;
  }
  if (
    t.includes('**GenerateTextNoTools**') ||
    t.includes('**WriteContent**') ||
    t.includes('**GetContent**')
  ) {
    return true;
  }
  if (t.includes('*Stopped.*') || t.includes('BodyStreamBuffer')) {
    return true;
  }
  if (t.length <= 120 && (t.includes('finished.') || t.includes('…') || t.endsWith('…'))) {
    return true;
  }
  const lines = t.split(/\r?\n/);
  let nonEmpty = 0;
  let toolish = 0;
  for (const line of lines) {
    const l = line.trim();
    if (!l) {
      continue;
    }
    nonEmpty++;
    if (l.includes('🛠') || (l.includes('**') && l.includes('finished'))) {
      toolish++;
    }
  }
  return nonEmpty > 0 && toolish >= nonEmpty;
}

/**
 * @param {string} priorBody
 * @param {number} [minChars=200]
 */
export function lastSubstantiveAssistantBlockText(priorBody, minChars = 200) {
  const prior = String(priorBody ?? '');
  if (!prior.trim()) {
    return '';
  }
  const blocks = [];
  const re = /Assistant:\s*([\s\S]*?)(?=\nUser:|$)/gis;
  let m;
  while ((m = re.exec(prior)) !== null) {
    blocks.push(String(m[1] ?? '').trim());
  }
  if (blocks.length === 0) {
    return '';
  }
  const floor = Math.max(80, minChars);
  for (let i = blocks.length - 1; i >= 0; i--) {
    const block = blocks[i];
    if (!assistantBlockLooksLikeToolStrip(block) && block.length >= floor) {
      return block;
    }
  }
  for (let i = blocks.length - 1; i >= 0; i--) {
    const block = blocks[i];
    if (!assistantBlockLooksLikeToolStrip(block) && block.length >= 80) {
      return block;
    }
  }
  return '';
}

/** @param {string} slug */
export function slugLooksLikeToolOrOrchestrationNoise(slug) {
  const s = String(slug ?? '').trim().toLowerCase();
  if (!s) {
    return true;
  }
  return (
    s.includes('generate-text-no-tools') ||
    s.includes('generatetextnotools') ||
    s.includes('tools-loop') ||
    /^get-content.*/.test(s) ||
    /^write-content.*/.test(s) ||
    /^list-content.*/.test(s) ||
    /^generate-text.*/.test(s) ||
    /^generatetext.*/.test(s)
  );
}

/** @param {string} title */
export function slugifyForRepositoryFileName(title) {
  let s = String(title ?? '')
    .trim()
    .toLowerCase();
  if (!s) {
    return '';
  }
  s = s.replace(/[^a-z0-9]+/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '');
  if (s.length > 80) {
    s = s.slice(0, 80).replace(/-+$/, '');
  }
  if (slugLooksLikeToolOrOrchestrationNoise(s)) {
    return '';
  }
  return s;
}

/** CrafterCMS.com-style catalog slice from debug session. */
export const CRAFTERCMS_BLOG_TYPE_ROWS = [
  { name: '/page/article', label: 'Page - Blog Post', type: 'page' },
  { name: '/page/blog', label: 'Page - Blog Roll', type: 'page' },
  { name: '/page/home', label: 'Page - Homepage', type: 'page' },
];
