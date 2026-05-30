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

console.log('sse-telemetry-offline: OK');
