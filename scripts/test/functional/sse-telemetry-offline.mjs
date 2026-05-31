#!/usr/bin/env node
/**
 * Offline assertions for scripts/test/lib/sse-telemetry.mjs (GenerateImage caps + prompt metadata).
 */
import { summarizeSseTelemetry, evaluateExpectations } from '../lib/sse-telemetry.mjs';

function assert(condition, message) {
  if (!condition) {
    console.error(`FAIL: ${message}`);
    process.exit(1);
  }
}

const sampleEvents = [
  {
    metadata: {
      status: 'tool-progress',
      tool: 'GenerateImage',
      phase: 'start',
      generateImagePrompt: 'blue circle on white',
    },
  },
  {
    metadata: {
      status: 'tool-progress',
      tool: 'GenerateImage',
      phase: 'done',
      generateImagePrompt: 'blue circle on white',
    },
  },
  {
    metadata: {
      status: 'tool-progress',
      tool: 'GenerateImage',
      phase: 'start',
    },
  },
  {
    metadata: {
      status: 'tool-progress',
      tool: 'GenerateImage',
      phase: 'warn',
    },
  },
  { metadata: { completed: true } },
];

const tel = summarizeSseTelemetry(sampleEvents);
assert(tel.toolStartCounts.get('GenerateImage') === 2, 'expected two GenerateImage start phases');
assert(tel.generateImagePrompts.length === 2, 'expected two generateImagePrompt metadata values');

const pass = evaluateExpectations(tel, {
  toolsAny: ['GenerateImage'],
  maxToolStartCounts: { GenerateImage: 1 },
  generateImagePromptSeen: true,
});
assert(pass.failures.some((f) => f.includes('GenerateImage')), 'maxToolStartCounts should fail when count > 1');

const passOk = evaluateExpectations(
  summarizeSseTelemetry(sampleEvents.slice(0, 2)),
  {
    maxToolStartCounts: { GenerateImage: 1 },
    generateImagePromptSeen: true,
  },
);
assert(passOk.failures.length === 0, `unexpected failures: ${passOk.failures.join('; ')}`);

const planTel = summarizeSseTelemetry([
  {
    metadata: {
      status: 'intent-recipe-routing',
      intentRecipeRouting: {
        outcome: 'plan',
        recipeId: 'generate_image',
        deferToPlanLoop: true,
      },
    },
  },
  { metadata: { completed: true } },
]);
const planPass = evaluateExpectations(planTel, {
  forbidRecipeId: 'generate_image',
  deferToPlanLoop: true,
});
assert(planPass.failures.length === 0, `plan defer expectations: ${planPass.failures.join('; ')}`);

const badMatch = summarizeSseTelemetry([
  {
    metadata: {
      status: 'intent-recipe-routing',
      intentRecipeRouting: { outcome: 'matched', recipeId: 'generate_image' },
    },
  },
]);
const badPass = evaluateExpectations(badMatch, { forbidRecipeId: 'generate_image' });
assert(badPass.failures.length === 1, 'forbidRecipeId should fail on matched generate_image');

const turnGoalTel = summarizeSseTelemetry([
  {
    metadata: {
      status: 'intent-recipe-routing',
      intentRecipeRouting: {
        outcome: 'plan',
        turnGoal: 'Generate an image for the page based on its content.',
        successCriteria: 'GenerateImage succeeded.',
      },
    },
  },
  { metadata: { completed: true } },
]);
const turnGoalPass = evaluateExpectations(turnGoalTel, {
  turnGoalPresent: true,
  turnGoalContains: 'image',
});
assert(turnGoalPass.failures.length === 0, `turnGoal expectations: ${turnGoalPass.failures.join('; ')}`);

const noTurnGoal = summarizeSseTelemetry([
  {
    metadata: {
      status: 'intent-recipe-routing',
      intentRecipeRouting: { outcome: 'plan' },
    },
  },
]);
const noTurnGoalFail = evaluateExpectations(noTurnGoal, { turnGoalPresent: true });
assert(noTurnGoalFail.failures.length === 1, 'turnGoalPresent should fail when turnGoal absent');

console.log('sse-telemetry-offline: OK');
