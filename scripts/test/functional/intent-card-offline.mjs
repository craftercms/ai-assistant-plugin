#!/usr/bin/env node
/**
 * Offline checks for intent-card behavior (mirrors AuthoringIntentCard heuristics).
 */
function assert(cond, msg) {
  if (!cond) {
    console.error(`FAIL: ${msg}`);
    process.exit(1);
  }
}

function isWeakTurnGoal(text) {
  const t = String(text || '').trim().toLowerCase();
  if (!t || t.length < 18) return true;
  if (t.includes('involves multiple step')) return true;
  if (t.includes('the task involves')) return true;
  if (t.includes('retrieving current news') && !t.includes('headline')) return true;
  if (t.includes('fully addressed')) return true;
  return false;
}

function deriveSteps(author) {
  const source = String(author || '').trim();
  if (!source) return [];
  let raw = source.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  if (raw.length <= 1) {
    const parts = source.split(/\s+then\s+|\s*;\s*/i);
    if (parts.length > 1) raw = parts.map((p) => p.trim()).filter(Boolean);
  }
  return raw.map((r) => r.replace(/^\d+[\).\]]\s*/, '').trim()).filter((r) => r.length > 4);
}

/** Mirrors intentNarrativeFromRouterLlm — router LLM fields, not author prompt echo. */
function intentFromRouter(turnGoal, reason, routingMode) {
  const goal = String(turnGoal || '').trim();
  const why = String(reason || '').trim();
  if (routingMode === 'chat_only') {
    let out = goal || why;
    if (why && why !== goal) out = (goal ? goal + ' ' : '') + why;
    return out.endsWith('.') ? out : out + '.';
  }
  return goal ? 'This turn: ' + goal + (goal.endsWith('.') ? '' : '.') : '';
}

const authorPrompt = `Search for today's top headline
Then update the content of this page with relevant copy based on the top headline 
Then generate an image and update the hero image on this page to match the copy`;

const routerGoal = 'The task involves multiple steps: retrieving current news, updating content, and generating an image.';

assert(!isWeakTurnGoal(authorPrompt.replace(/\s+/g, ' ')), 'author prompt should be concrete');
assert(isWeakTurnGoal(routerGoal), 'router meta goal should be weak');

const steps = deriveSteps(authorPrompt);
assert(steps.length === 3, `expected 3 steps, got ${steps.length}: ${JSON.stringify(steps)}`);

const praiseGoal = "Acknowledge the assistant's previous work as satisfactory.";
const praiseReason = 'The author is expressing approval and does not require any additional actions.';
const praiseIntent = intentFromRouter(praiseGoal, praiseReason, 'chat_only');
assert(praiseIntent.includes('Acknowledge'), 'chat_only uses router turnGoal');
assert(praiseIntent.includes('approval'), 'chat_only may include router reason');
assert(!praiseIntent.includes('GetContent'), 'chat_only must not append CMS boilerplate');

function chatOnlyIntentFromAuthor(author) {
  const req = String(author || '').trim();
  if (!req) return '';
  const opinion = req.match(/^what do you think (?:of|about) (.+?)\??$/i);
  if (opinion) return `The user wants to know what I think about ${opinion[1].trim()}.`;
  if (req.endsWith('?')) return `The user is asking: ${req}`;
  return `The user said: ${req}`;
}

const baseball = chatOnlyIntentFromAuthor('what do you think of baseball?');
assert(baseball === 'The user wants to know what I think about baseball.', `baseball intent: ${baseball}`);
assert(!baseball.includes('gather opinions'), 'no router process-speak on chat_only card');

const repairGoal = 'Identify and address the 500 error in the preview of the MLB Baseball page.';
const repairIntent = intentFromRouter(repairGoal, 'Multi-step plan to fix preview.', 'plan');
assert(repairIntent.includes('500 error'), 'tool/plan turns use router turnGoal');
assert(!repairIntent.includes('What the fuck'), 'must not quote raw author prompt');

const SELECTIVE_VERSION_RESTORE =
  /\b(?:look\s+up|get|fetch|pull)\s+(?:the\s+)?(?:previous|prior|old|earlier)\s+(?:copy|text|content|image|photo)\b|\b(?:restore|put\s+back|bring\s+back)\s+(?:the\s+)?(?:previous|prior|old|earlier|last|same)\s+(?:copy|text|content|image|photo|one)\b|\brevert\s+(?:the\s+)?(?:copy|text|content|image)\b/i;
const PRIOR_IMAGE_RESTORE =
  /\b(?:re-?insert|restore|put\s+back)\s+(?:the\s+)?(?:previous|prior|old|last|same)\s+(?:one|image|photo|picture)\b|\b(?:don't|do\s+not|stop|no)\s+(?:want\s+)?(?:a\s+)?new\s+image\b|\bwrong\s+photo\b/i;
const FULL_PAGE_REVERT =
  /\b(?:undo|revert|roll\s*back)\s+(?:the\s+)?(?:page|item)\b|\brestore\s+(?:this|the)\s+(?:page|item)\b/i;

const FORBIDS_FULL_PAGE_REVERT =
  /\bdon'?t\s+revert\s+(?:the\s+)?(?:page|item|whole|entire)\b/i;
const CONTENT_COMPLAINT =
  /\b(?:i\s+)?(?:didn't|did\s+not)\s+ask\s+(?:you\s+to\s+)?(?:change|update|rewrite|modify)\b/i;

assert(SELECTIVE_VERSION_RESTORE.test('revert the copy to the previous copy about baseball'), 'selective copy revert');
assert(FORBIDS_FULL_PAGE_REVERT.test("Don't revert the page! put the image back"), 'forbid full page revert');
assert(CONTENT_COMPLAINT.test("I didn't ask you to change the copy!"), 'content modification complaint');
assert(PRIOR_IMAGE_RESTORE.test('STOP I dont want a NEW image. re-insert the previous one'), 'prior image restore');
assert(!FULL_PAGE_REVERT.test('revert the copy to the previous copy'), 'copy revert is not full page');

console.log('intent-card-offline: OK');
