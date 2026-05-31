#!/usr/bin/env node
/**
 * Offline assertions for intent router JSON extraction and parsing
 * (parity with AuthoringIntentRecipeRouter.groovy).
 */
import { extractJsonPayload, parseRouterJson } from '../lib/router-json-parity.mjs';

function assert(condition, message) {
  if (!condition) {
    console.error(`FAIL: ${message}`);
    process.exit(1);
  }
}

// --- extractJsonPayload ---

assert(extractJsonPayload('') === '', 'empty raw → empty payload');
assert(
  extractJsonPayload('{"mode":"plan","turnGoal":"test"}') === '{"mode":"plan","turnGoal":"test"}',
  'pure JSON unchanged',
);

const proseBefore = `## Plan Execution
The author wants an image.

{"mode":"plan","recipeId":null,"toolName":null,"confidence":0.8,"turnGoal":"Generate an image for the page.","successCriteria":"GenerateImage succeeds","reason":"needs page context"}`;
const extracted = extractJsonPayload(proseBefore);
assert(extracted.startsWith('{') && extracted.includes('"turnGoal"'), 'prose before JSON → extract object');
assert(JSON.parse(extracted).mode === 'plan', 'extracted JSON parses');

const fenced = '```json\n{"mode":"recipe","recipeId":"new_content_item","confidence":0.9,"turnGoal":"Create item"}\n```';
assert(parseRouterJson(fenced).recipeId === 'new_content_item', 'fenced JSON parses');

// --- parseRouterJson ---

const full = parseRouterJson(
  '{"mode":"recipe","recipeId":"translate_content_item","toolName":null,"confidence":0.8,"turnGoal":"Translate to Spanish","successCriteria":"Saved in repo","reason":"translation ask"}',
);
assert(full.mode === 'recipe', 'mode recipe');
assert(full.recipeId === 'translate_content_item', 'recipeId');
assert(full.turnGoal === 'Translate to Spanish', 'turnGoal');
assert(full.successCriteria === 'Saved in repo', 'successCriteria');

const planFromProse = parseRouterJson(proseBefore);
assert(planFromProse.mode === 'plan', 'prose-wrapped → plan mode');
assert(planFromProse.turnGoal === 'Generate an image for the page.', 'prose-wrapped turnGoal');
assert(planFromProse.confidence === 0.8, 'prose-wrapped confidence');

const badRecipe = parseRouterJson('{"mode":"recipe","recipeId":null,"confidence":0.9,"reason":"missing id"}');
assert(badRecipe.mode === 'plan', 'recipe without recipeId → plan');

const badTool = parseRouterJson('{"mode":"tool","toolName":null,"confidence":0.9}');
assert(badTool.mode === 'plan', 'tool without toolName → plan');

const garbage = parseRouterJson('not json at all');
assert(garbage.mode === 'plan' && garbage.reason.includes('parse error'), 'invalid JSON → plan + parse error');

console.log('router-json-offline: OK');
