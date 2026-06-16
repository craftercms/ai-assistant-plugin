#!/usr/bin/env node
/**
 * Offline checks for step-bridge SSE card kinds (mirrors AuthoringStepBridgeCard labels).
 */
function assert(cond, msg) {
  if (!cond) {
    console.error(`FAIL: ${msg}`);
    process.exit(1);
  }
}

const KINDS = new Set([
  'execution_plan',
  'tool_outputs',
  'research_grounding',
  'turn_reminder',
  'fetched_context',
]);

function parseStepBridgeEvent(ev) {
  const meta = ev?.metadata || {};
  if (meta.status !== 'step-bridge-card') return null;
  const bridge = meta.stepBridge || {};
  return {
    kind: String(bridge.kind || '').trim(),
    markdown: String(bridge.markdown || ev.text || '').trim(),
  };
}

const sampleEvents = [
  {
    text: '## How this request will run\n\n1. Search…',
    metadata: {
      status: 'step-bridge-card',
      stepBridge: { kind: 'execution_plan', markdown: '## How this request will run' },
    },
  },
  {
    text: '## Carrying forward\n\n1. **WebSearch**',
    metadata: {
      status: 'step-bridge-card',
      stepBridge: { kind: 'tool_outputs', markdown: '## Carrying forward' },
    },
  },
];

for (const ev of sampleEvents) {
  const card = parseStepBridgeEvent(ev);
  assert(card && card.markdown, 'expected markdown on step-bridge event');
  assert(KINDS.has(card.kind), `unknown kind ${card.kind}`);
}

assert(
  parseStepBridgeEvent({ metadata: { status: 'tool-progress' } }) === null,
  'non-bridge events should not parse'
);

console.log('step-bridge-card-offline: OK');
