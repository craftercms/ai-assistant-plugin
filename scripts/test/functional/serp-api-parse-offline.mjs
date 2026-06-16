#!/usr/bin/env node
/**
 * Offline parity for SerpApiWebSearch result merge and failure messages.
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  formatSerpFailureMessage,
  mergeSerpApiHits,
} from '../lib/serp-api-parse-parity.mjs';

function assert(cond, msg) {
  if (!cond) {
    console.error(`FAIL: ${msg}`);
    process.exit(1);
  }
}

const fixturePath = join(
  dirname(fileURLToPath(import.meta.url)),
  '../fixtures/serpapi-mixed-empty-organic.json',
);
const fixture = JSON.parse(readFileSync(fixturePath, 'utf8'));
const { results, meta } = mergeSerpApiHits(fixture, 10);

assert(results.length === 2, `expected 2 merged hits, got ${results.length}`);
assert(meta.organicResultsRaw === 0, 'fixture organic count');
assert(meta.topStoriesRaw === 1, 'fixture top_stories count');
assert(meta.newsResultsRaw === 1, 'fixture news_results count');
assert(meta.organicResultsState === 'Fully empty', 'organic_results_state');
assert(
  results.some((r) => r.resultKind === 'top_stories'),
  'must include top_stories hit',
);
assert(results.some((r) => r.resultKind === 'news_results'), 'must include news_results hit');

const emptyMsg = formatSerpFailureMessage(
  {
    organicResultsState: 'Fully empty',
    organicResultsRaw: 0,
    newsResultsRaw: 2,
    topStoriesRaw: 1,
    serpParams: { tbs: 'qdr:w' },
  },
  'Salesforce buys Contentful',
);
assert(
  emptyMsg.includes('organic_results_state=Fully empty'),
  'failure message includes organic_results_state',
);
assert(!emptyMsg.startsWith('No results for this query'), 'generic no-results message removed');

const httpMsg = formatSerpFailureMessage({ httpStatus: 503 }, 'test');
assert(httpMsg.includes('HTTP 503'), 'HTTP failures are distinct');

const ioMsg = formatSerpFailureMessage({ fetchError: 'connection reset' }, 'test');
assert(ioMsg.includes('network/I/O'), 'I/O failures are distinct');

console.log('serp-api-parse-offline: OK');
