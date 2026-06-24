#!/usr/bin/env node
/**
 * Offline assertions for intent router JSON extraction and parsing
 * (parity with AuthoringIntentRecipeRouter.groovy).
 */
import { extractJsonPayload, parseRouterJson } from '../lib/router-json-parity.mjs';
import {
  finalizeAfterCorrections,
  authorVisibleLooksLikeChatProseBrief,
  authorVisibleReportsBrokenPreviewRepair,
  authorVisibleSuggestsXbScopedFieldCopyEdit,
} from '../lib/deliverable-policy-parity.mjs';

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

const extended = parseRouterJson(
  '{"mode":"chat_only","deliverable":"chat_prose","turnRelation":"correction","sessionObjective":"Original blog brief.","authorUnderstanding":"Re-deliver architectural blog copy in chat.","turnGoal":"Produce blog sections in chat.","confidence":0.9,"reason":"correction"}',
);
assert(extended.deliverable === 'chat_prose', 'deliverable parsed');
assert(extended.turnRelation === 'correction', 'turnRelation parsed');
assert(extended.sessionObjective === 'Original blog brief.', 'sessionObjective parsed');
assert(extended.authorUnderstanding?.includes('blog'), 'authorUnderstanding parsed');

const forced = finalizeAfterCorrections({
  mode: 'recipe',
  recipeId: 'new_content_item',
  deliverable: 'chat_prose',
  confidence: 0.9,
});
assert(forced.mode === 'chat_only', 'chat_prose forces chat_only');
assert(forced.recipeId == null, 'create recipe cleared for chat_prose');

const blogBrief = `Draft a substantive blog copy about the Crafter Studio AI Assistant for CrafterCMS.
Audience: Enterprise architects evaluating CMS platforms.
Summarizing or rewriting page content
Check for broken references
the page, component, field, content type, site, repository path
Developer Skills are not just prompt engineering.`.repeat(3);
assert(authorVisibleLooksLikeChatProseBrief(blogBrief), 'long blog brief → chat_prose inference');
assert(!authorVisibleReportsBrokenPreviewRepair(blogBrief), 'blog brief must not trigger broken-preview repair');
assert(authorVisibleReportsBrokenPreviewRepair('The preview is broken and shows HTTP 500'), 'real broken preview still matches');

const repaired = finalizeAfterCorrections(
  {
    mode: 'recipe',
    recipeId: 'modify_page_content',
    deliverable: 'repo_write',
    reason: 'Author reports preview/render failure; repair anchored page content and re-verify preview.',
    sessionObjective: 'Draft blog copy about the AI Assistant.',
  },
  blogBrief,
);
assert(repaired.mode === 'chat_only', 'finalizeAfterCorrections undoes modify_page_content for blog brief');
assert(repaired.recipeId == null, 'recipe cleared after finalize for blog brief');

const xbWire = `--- Author scope (Studio UI — not the author's request) ---
Scope: **selected Experience Builder field** (author chose Field in the AI Assistant).
XB focused field label (Studio UI): Title
XB focused field id: title_html
XB focused content item path: /site/components/en/heroes/home-hero.xml
---`;

const xbFieldDecision = finalizeAfterCorrections(
  {
    mode: 'recipe',
    deliverable: 'repo_write',
    recipeId: 'modify_page_content',
  },
  'update this copy to be more action based',
  xbWire,
);
assert(
  authorVisibleSuggestsXbScopedFieldCopyEdit(xbWire, 'update this copy to be more action based'),
  'xb field scope copy edit detected',
);
assert(xbFieldDecision.mode === 'recipe', 'xb field scope keeps recipe mode after finalize');
assert(xbFieldDecision.recipeId === 'modify_page_content', 'xb field scope keeps modify_page_content');

console.log('router-json-offline: OK');
