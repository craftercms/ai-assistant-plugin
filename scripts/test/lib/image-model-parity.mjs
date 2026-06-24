#!/usr/bin/env node
/**
 * Mirrors sources/src/studioAiOrchestrationToolIds.ts normalizeImageModelId (and server AiOrchestration).
 */
import assert from 'node:assert/strict';

const STUDIO_AI_DEFAULT_IMAGE_MODEL = 'gpt-image-1';

function isDeprecatedDallEImageModel(raw) {
  const m = raw.trim().toLowerCase().replace(/_/g, '-');
  return m.startsWith('dall-e') || m.startsWith('dalle');
}

function normalizeImageModelId(raw) {
  const trimmed = (raw ?? '').trim();
  if (!trimmed) return undefined;
  if (isDeprecatedDallEImageModel(trimmed)) return STUDIO_AI_DEFAULT_IMAGE_MODEL;
  return trimmed;
}

assert.equal(normalizeImageModelId('dall-e-3'), STUDIO_AI_DEFAULT_IMAGE_MODEL);
assert.equal(normalizeImageModelId('DALL-E-2'), STUDIO_AI_DEFAULT_IMAGE_MODEL);
assert.equal(normalizeImageModelId('dalle-3'), STUDIO_AI_DEFAULT_IMAGE_MODEL);
assert.equal(normalizeImageModelId('gpt-image-1'), 'gpt-image-1');
assert.equal(normalizeImageModelId(''), undefined);
assert.equal(normalizeImageModelId(null), undefined);

console.log('image-model-parity: OK');
