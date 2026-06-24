/**
 * JS mirror of SerpApiWebSearchTool result merge (offline parity).
 */

/** @param {string} url */
function skipResultUrl(url) {
  const u = String(url || '').trim().toLowerCase();
  if (!u.startsWith('http://') && !u.startsWith('https://')) return true;
  return u.includes('duckduckgo.com/') || u.includes('duck.com/');
}

/**
 * @param {unknown} root SerpAPI JSON root
 * @param {number} maxResults
 */
export function mergeSerpApiHits(root, maxResults = 10) {
  if (!root || typeof root !== 'object') {
    return { results: [], meta: {} };
  }
  const r = /** @type {Record<string, unknown>} */ (root);
  const meta = {
    serpApiError: typeof r.error === 'string' ? r.error.trim() : '',
    organicResultsState:
      r.search_information &&
      typeof r.search_information === 'object' &&
      typeof /** @type {Record<string, unknown>} */ (r.search_information).organic_results_state ===
        'string'
        ? String(
            /** @type {Record<string, unknown>} */ (r.search_information).organic_results_state,
          ).trim()
        : '',
    organicResultsRaw: Array.isArray(r.organic_results) ? r.organic_results.length : 0,
    newsResultsRaw: Array.isArray(r.news_results) ? r.news_results.length : 0,
    topStoriesRaw: Array.isArray(r.top_stories) ? r.top_stories.length : 0,
  };

  /** @type {{ position: number; title: string; url: string; snippet: string; resultKind: string }[]} */
  const results = [];
  const seen = new Set();

  const append = (list, kind) => {
    if (!Array.isArray(list)) return;
    for (const row of list) {
      if (results.length >= maxResults) break;
      if (!row || typeof row !== 'object') continue;
      const hit = /** @type {Record<string, unknown>} */ (row);
      const url = String(hit.link || hit.url || '').trim();
      if (!url || skipResultUrl(url)) continue;
      const key = url.toLowerCase();
      if (seen.has(key)) continue;
      seen.add(key);
      let snippet = String(hit.snippet || hit.description || '').trim();
      if (!snippet) {
        const src = String(hit.source || '').trim();
        const date = String(hit.date || '').trim();
        snippet = [src, date].filter(Boolean).join(' · ');
      }
      results.push({
        position: results.length + 1,
        title: String(hit.title || '').trim(),
        url,
        snippet,
        resultKind: kind,
      });
    }
  };

  append(r.organic_results, 'organic');
  append(r.top_stories, 'top_stories');
  append(r.news_results, 'news_results');

  return { results, meta };
}

/**
 * @param {Record<string, unknown>} diag
 * @param {string} displayQuery
 */
export function formatSerpFailureMessage(diag, displayQuery) {
  const parseErr = String(diag.parseError || '').trim();
  if (parseErr) return `SerpAPI response could not be parsed as JSON: ${parseErr}`;

  const fetchErr = String(diag.fetchError || '').trim();
  if (fetchErr) return `SerpAPI request failed (network/I/O): ${fetchErr}`;

  const ssrf = String(diag.ssrfBlocked || '').trim();
  if (ssrf) return `SerpAPI blocked by outbound URL policy: ${ssrf}`;

  const httpStatus = diag.httpStatus;
  if (typeof httpStatus === 'number' && (httpStatus < 200 || httpStatus >= 300)) {
    return `SerpAPI returned HTTP ${httpStatus} (not an empty Google results page).`;
  }

  const serpErr = String(diag.serpApiError || '').trim();
  if (serpErr) return `SerpAPI error: ${serpErr}`;

  const state = String(diag.organicResultsState || '').trim();
  const organic = Number(diag.organicResultsRaw) || 0;
  const news = Number(diag.newsResultsRaw) || 0;
  const top = Number(diag.topStoriesRaw) || 0;
  const tbs =
    diag.serpParams &&
    typeof diag.serpParams === 'object' &&
    typeof /** @type {Record<string, unknown>} */ (diag.serpParams).tbs === 'string'
      ? String(/** @type {Record<string, unknown>} */ (diag.serpParams).tbs).trim()
      : '';

  let msg = 'No web results parsed from SerpAPI';
  if (state) msg += ` (organic_results_state=${state})`;
  msg += ` — organic=${organic}, news=${news}, top_stories=${top}`;
  if (tbs) msg += `; tbs=${tbs}`;
  msg += `. Query: ${displayQuery}`;
  return msg;
}
