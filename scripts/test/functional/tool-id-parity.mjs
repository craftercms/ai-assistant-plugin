#!/usr/bin/env node
/**
 * Offline parity: StudioAiToolRegistry CORE_TOOLS wire names vs STUDIO_AI_BUILTIN_TOOL_IDS (TS).
 */
import { readdirSync, readFileSync, statSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '../../..');

function findToolFile(className) {
  const base = join(
    repoRoot,
    'authoring/scripts/classes/plugins/org/craftercms/aiassistant/studio/contrib/tool',
  );
  function walk(dir) {
    for (const name of readdirSync(dir)) {
      const p = join(dir, name);
      if (statSync(p).isDirectory()) {
        const hit = walk(p);
        if (hit) return hit;
      } else if (name === `${className}.groovy`) {
        return p;
      }
    }
    return null;
  }
  return walk(base);
}

function wireNameFromToolSource(toolSrc, toolFile) {
  const lit = toolSrc.match(/String\s+wireName\s*\(\s*\)\s*\{\s*['"]([^'"]+)['"]\s*\}/);
  if (lit) return lit[1];
  const ref = toolSrc.match(/String\s+wireName\s*\(\s*\)\s*\{\s*(\w+)\.WIRE\s*\}/);
  if (ref) {
    const settingsFile = join(dirname(toolFile), `${ref[1]}.groovy`);
    if (existsSync(settingsFile)) {
      const settingsSrc = readFileSync(settingsFile, 'utf8');
      const wire = settingsSrc.match(/static\s+final\s+String\s+WIRE\s*=\s*['"]([^'"]+)['"]/);
      if (wire) return wire[1];
    }
  }
  return null;
}

function coreWireNames() {
  const path = join(
    repoRoot,
    'authoring/scripts/classes/plugins/org/craftercms/aiassistant/studio/engine/catalog/StudioAiToolRegistry.groovy',
  );
  const src = readFileSync(path, 'utf8');
  const block = src.match(/CORE_TOOLS\s*=\s*Collections\.unmodifiableList\(\[([\s\S]*?)\]\)/);
  if (!block) throw new Error('Could not parse CORE_TOOLS');
  const classNames = [...block[1].matchAll(/new\s+(\w+)\(\)/g)].map((m) => m[1]);
  const names = [];
  for (const cls of classNames) {
    const f = findToolFile(cls);
    if (!f) throw new Error(`Missing tool file for ${cls}`);
    const wire = wireNameFromToolSource(readFileSync(f, 'utf8'), f);
    if (!wire) throw new Error(`wireName missing in ${f}`);
    names.push(wire);
  }
  return names.sort();
}

function uiToolIds() {
  const path = join(repoRoot, 'sources/src/studioAiOrchestrationToolIds.ts');
  const src = readFileSync(path, 'utf8');
  const block = src.match(/STUDIO_AI_BUILTIN_TOOL_IDS[^=]*=\s*\[([\s\S]*?)\]\s*as const/);
  if (!block) throw new Error('Could not parse STUDIO_AI_BUILTIN_TOOL_IDS');
  return [...block[1].matchAll(/['"]([^'"]+)['"]/g)]
    .map((m) => m[1])
    .filter((id) => id !== 'mcp:*')
    .sort();
}

const core = coreWireNames();
const ui = uiToolIds();

/** UI lists ListContentTranslationScope alias; server maps it to ListContentDependencyScope. */
const uiNormalized = ui.filter((id) => id !== 'ListContentTranslationScope');

const coreSet = new Set(core);
const uiSet = new Set(uiNormalized);

const missingInUi = core.filter((id) => !uiSet.has(id));
const missingInCore = uiNormalized.filter((id) => !coreSet.has(id));

if (missingInUi.length || missingInCore.length) {
  console.error('tool-id-parity FAILED');
  if (missingInUi.length) console.error(`  In CORE_TOOLS but not UI list: ${missingInUi.join(', ')}`);
  if (missingInCore.length) console.error(`  In UI list but not CORE_TOOLS: ${missingInCore.join(', ')}`);
  process.exit(1);
}

console.log(`tool-id-parity OK (${core.length} core tools, UI has +ListContentTranslationScope alias)`);
