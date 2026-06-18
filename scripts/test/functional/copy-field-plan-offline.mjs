#!/usr/bin/env node
/**
 * Offline parity for content field plan (purpose from form-definition metadata).
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  buildFieldPurpose,
  harvestCopyFieldsFromFormXml,
  inferWritePolicy,
} from '../lib/copy-field-plan-parity.mjs';

function assert(cond, msg) {
  if (!cond) {
    console.error(`FAIL: ${msg}`);
    process.exit(1);
  }
}

const titlePurpose = buildFieldPurpose('Page Properties', {
  type: 'input',
  title: 'Title',
  description: 'Main page headline shown in browser title and listings.',
});
assert(titlePurpose.includes('Main page headline'), 'purpose includes form description');
assert(
  inferWritePolicy('input', titlePurpose, 'Page Properties', 'Title') === 'original-headline',
  'page title from description → original-headline',
);

const heroPurpose = buildFieldPurpose('Hero Section', {
  type: 'rte',
  title: 'Hero Title',
  description: 'Primary hero headline (H1). The main news line for the page.',
});
assert(
  inferWritePolicy('rte', heroPurpose, 'Hero Section', 'Hero Title') === 'original-headline',
  'hero headline from description → original-headline',
);

const heroTextPurpose = buildFieldPurpose('Hero Section', {
  type: 'rte',
  title: 'Hero Text',
  description: 'Supporting hero copy — one or two sentences expanding on the headline.',
});
assert(
  inferWritePolicy('rte', heroTextPurpose, 'Hero Section', 'Hero Text') === 'supporting-copy',
  'hero supporting copy from description → supporting-copy',
);

const featuresPurpose = buildFieldPurpose('Features', {
  type: 'input',
  title: 'Features Title',
  description: 'Short section label above the features grid — not the article headline.',
});
assert(
  inferWritePolicy('input', featuresPurpose, 'Features', 'Features Title') === 'section-label',
  'features label from description → section-label',
);

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
const titleField = fields.find((f) => f.fieldId === 'title_t');
assert(titleField?.writePolicy === 'original-headline', 'fixture title_t policy from purpose');
const heroTitle = fields.find((f) => f.fieldId === 'hero_title_html');
assert(heroTitle?.writePolicy === 'original-headline', 'fixture hero_title_html policy from label');
const heroDeck = fields.find((f) => f.fieldId === 'hero_text_html');
assert(heroDeck?.writePolicy === 'supporting-copy', 'fixture hero_text_html policy');
const heroImage = fields.find((f) => f.fieldId === 'hero_image_s');
assert(heroImage?.writePolicy === 'image-path', 'fixture hero_image_s policy');
assert(heroImage?.purpose.includes('Hero banner'), 'fixture image purpose from description');
assert(!ids.includes('sections_o'), 'node-selector sections_o must not be a copy field');

console.log('copy-field-plan-offline: OK');
