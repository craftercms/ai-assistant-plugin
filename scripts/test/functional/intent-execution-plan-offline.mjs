#!/usr/bin/env node
/**
 * Offline checks for intent → execution-plan tool chains (mirrors AuthoringIntentExecutionPlan).
 */
import {
  classifyStepKind,
  derivePlanRows,
  deriveSteps,
  mentionsExternalLookup,
  mentionsImageStep,
} from '../lib/intent-execution-plan-parity.mjs';

function assert(cond, msg) {
  if (!cond) {
    console.error(`FAIL: ${msg}`);
    process.exit(1);
  }
}

const authorPrompt = `Search for today's top headline
Then update the content of this page with relevant copy based on the top headline 
Then generate an image and update the hero image on this page to match the copy`;

const steps = deriveSteps(authorPrompt);
assert(steps.length === 3, `expected 3 steps, got ${steps.length}`);

const rows = derivePlanRows(authorPrompt);
assert(rows.length === 3, `three explicit steps → three plan rows; got ${rows.length}`);
assert(rows[0].kind === 'external_lookup', 'first row should be external_lookup');
assert(rows.some((r) => r.kind === 'repo_update'), 'must include repo_update');
assert(rows.some((r) => r.kind === 'image_generate'), 'must include image_generate');

const combined =
  "Search for today's top headline and update the content of this page with relevant copy based on it";
const combinedRows = derivePlanRows(combined);
assert(
  combinedRows.length >= 2 && combinedRows[0].kind === 'external_lookup',
  `combined lookup+write step should split rows; got ${combinedRows.length}`,
);

assert(!mentionsExternalLookup('find the about page in the repository'), 'repo find must not trigger web lookup');
assert(mentionsExternalLookup("search for today's top headline"), 'search for headline must trigger lookup');
assert(!mentionsImageStep('update the hero headline and body copy'), 'hero copy edit alone is not image_generate');
assert(mentionsImageStep('generate an image and update the hero image'), 'hero image + generate is image step');
assert(classifyStepKind('update the hero headline') !== 'image_generate', 'text-only hero is not image_generate');

console.log('intent-execution-plan-offline: OK');
