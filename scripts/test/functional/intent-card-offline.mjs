#!/usr/bin/env node
/**
 * Offline checks for intent-card weak-router sanitization (mirrors AuthoringIntentCard heuristics).
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

const authorPrompt = `Search for today's top headline
Then update the content of this page with relevant copy based on the top headline 
Then generate an image and update the hero image on this page to match the copy`;

const routerGoal = 'The task involves multiple steps: retrieving current news, updating content, and generating an image.';

assert(!isWeakTurnGoal(authorPrompt.replace(/\s+/g, ' ')), 'author prompt should be concrete');
assert(isWeakTurnGoal(routerGoal), 'router meta goal should be weak');

const steps = deriveSteps(authorPrompt);
assert(steps.length === 3, `expected 3 steps, got ${steps.length}: ${JSON.stringify(steps)}`);
assert(steps[0].toLowerCase().includes('headline'), 'step 1 should mention headline');
assert(steps[2].toLowerCase().includes('hero'), 'step 3 should mention hero');

console.log('intent-card-offline: OK');
