#!/usr/bin/env node
/**
 * Offline parity for content field roles (mirrors FormDefinitionCopyFieldPlan heuristics).
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  guidanceForRole,
  harvestCopyFieldsFromFormXml,
  inferCopyRole,
} from '../lib/copy-field-plan-parity.mjs';

function assert(cond, msg) {
  if (!cond) {
    console.error(`FAIL: ${msg}`);
    process.exit(1);
  }
}

assert(inferCopyRole('title_t', 'input', 'Title') === 'page-title', 'title_t → page-title');
assert(inferCopyRole('hero_title_html', 'rte', 'Hero Title') === 'hero-headline', 'hero_title_html → hero-headline');
assert(inferCopyRole('hero_text_html', 'rte', 'Hero Text') === 'hero-deck', 'hero_text_html → hero-deck');
assert(inferCopyRole('hero_image_s', 'image-picker', 'Hero Image') === 'image-asset', 'hero_image_s → image-asset');
assert(inferCopyRole('features_title_t', 'input', 'Features Title') === 'section-title', 'features_title_t → section-title');

const pageTitleGuide = guidanceForRole('page-title');
assert(
  pageTitleGuide.includes('without editorial prefixes') && pageTitleGuide.includes('Breaking news:'),
  'page-title guidance warns against Breaking news prefix',
);
const heroDeckGuide = guidanceForRole('hero-deck');
assert(heroDeckGuide.includes('do **not** repeat the headline'), 'hero-deck guidance forbids headline repeat');

const fixturePath = join(
  dirname(fileURLToPath(import.meta.url)),
  '../fixtures/page-home-form-definition.xml',
);
const xml = readFileSync(fixturePath, 'utf8');
const fields = harvestCopyFieldsFromFormXml(xml);
const ids = fields.map((f) => f.fieldId);
for (const required of ['title_t', 'hero_title_html', 'hero_text_html', 'features_title_t', 'hero_image_s']) {
  assert(ids.includes(required), `fixture form must include ${required}`);
}
const heroDeck = fields.find((f) => f.fieldId === 'hero_text_html');
assert(heroDeck?.copyRole === 'hero-deck', 'fixture hero_text_html role');
assert(!ids.includes('sections_o'), 'node-selector sections_o must not be a copy field');

console.log('copy-field-plan-offline: OK');
