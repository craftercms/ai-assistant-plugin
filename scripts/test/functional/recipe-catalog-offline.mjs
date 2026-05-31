#!/usr/bin/env node
/**
 * Offline validation of bundled authoring-intent-recipes-default.json.
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const path = join(
  dirname(fileURLToPath(import.meta.url)),
  '../../../authoring/scripts/classes/plugins/org/craftercms/aiassistant/studio/engine/routing/authoring-intent-recipes-default.json',
);

const doc = JSON.parse(readFileSync(path, 'utf8'));
const errors = [];

const recipeIds = (doc.recipes || []).map((r) => String(r.id || '').trim()).filter(Boolean);
const order = (doc.recipeOrder || []).map((id) => String(id).trim());

if (!recipeIds.length) errors.push('recipes array is empty');

const idSet = new Set(recipeIds);
for (const id of order) {
  if (!idSet.has(id)) errors.push(`recipeOrder references unknown id: ${id}`);
}
for (const id of recipeIds) {
  if (!order.includes(id)) errors.push(`recipe ${id} missing from recipeOrder`);
}

for (const r of doc.recipes || []) {
  const id = String(r.id || '').trim();
  if (!r.title) errors.push(`${id}: missing title`);
  if (!Array.isArray(r.matchHints) || !r.matchHints.length) errors.push(`${id}: missing matchHints`);
  if (id === 'generate_image') {
    const dont = (r.dontMatchHints || []).map((h) => String(h).toLowerCase());
    for (const required of ['this page', 'for this page']) {
      if (!dont.some((h) => h.includes(required))) {
        errors.push(`${id}: dontMatchHints must include "${required}" (anchored page defer)`);
      }
    }
    if (r.toolsLoopForceTool) {
      errors.push(`${id}: toolsLoopForceTool must be omitted (plan defer for anchored page without subject)`);
    }
  }
}

if (doc.routingRecipeFamilies && typeof doc.routingRecipeFamilies === 'object') {
  for (const [family, members] of Object.entries(doc.routingRecipeFamilies)) {
    if (!Array.isArray(members)) continue;
    for (const id of members) {
      if (!idSet.has(String(id))) errors.push(`routingRecipeFamilies.${family} unknown id: ${id}`);
    }
  }
}

if (errors.length) {
  console.error('recipe-catalog-offline FAILED:');
  for (const e of errors) console.error(`  - ${e}`);
  process.exit(1);
}

console.log(`recipe-catalog-offline OK (${recipeIds.length} recipes)`);
