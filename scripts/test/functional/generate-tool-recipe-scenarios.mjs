#!/usr/bin/env node
/**
 * Validates fixture coverage and writes intent-recipes-all.json + tools-all.json.
 *
 * Usage: node scripts/test/functional/generate-tool-recipe-scenarios.mjs [--check]
 *   --check  Fail if generated JSON differs from committed files (CI drift guard).
 */
import { readdirSync, readFileSync, statSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { RECIPE_CASES, RECIPE_DRAFT_PRIOR_TURN, TOOL_CASES } from '../fixtures/tool-recipe-matrix.mjs';

const __dir = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(__dir, '../../..');
const scenariosDir = join(repoRoot, 'scripts/test/scenarios');

const checkOnly = process.argv.includes('--check');

function loadRecipeIdsFromBundled() {
  const path = join(
    repoRoot,
    'authoring/scripts/classes/plugins/org/craftercms/aiassistant/studio/engine/routing/authoring-intent-recipes-default.json',
  );
  const doc = JSON.parse(readFileSync(path, 'utf8'));
  const ids = (doc.recipes || []).map((r) => String(r.id || '').trim()).filter(Boolean);
  return { ids, recipeOrder: doc.recipeOrder || [] };
}

function findToolFile(repoRoot, className) {
  const base = join(
    repoRoot,
    'authoring/scripts/classes/plugins/org/craftercms/aiassistant/studio/contrib/tool',
  );
  /** @param {string} dir */
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
    const settingsClass = ref[1];
    const settingsFile = join(dirname(toolFile), `${settingsClass}.groovy`);
    if (existsSync(settingsFile)) {
      const settingsSrc = readFileSync(settingsFile, 'utf8');
      const wire = settingsSrc.match(/static\s+final\s+String\s+WIRE\s*=\s*['"]([^'"]+)['"]/);
      if (wire) return wire[1];
    }
  }
  return null;
}

function loadCoreToolWireNamesFromRegistry() {
  const path = join(
    repoRoot,
    'authoring/scripts/classes/plugins/org/craftercms/aiassistant/studio/engine/catalog/StudioAiToolRegistry.groovy',
  );
  const src = readFileSync(path, 'utf8');
  const block = src.match(/CORE_TOOLS\s*=\s*Collections\.unmodifiableList\(\[([\s\S]*?)\]\)/);
  if (!block) throw new Error('Could not parse CORE_TOOLS from StudioAiToolRegistry.groovy');
  const classNames = [...block[1].matchAll(/new\s+(\w+)\(\)/g)].map((m) => m[1]);
  /** @type {string[]} */
  const wireNames = [];
  for (const cls of classNames) {
    const toolFile = findToolFile(repoRoot, cls);
    if (!toolFile) throw new Error(`Tool class file not found for ${cls}`);
    const toolSrc = readFileSync(toolFile, 'utf8');
    const wire = wireNameFromToolSource(toolSrc, toolFile);
    if (!wire) throw new Error(`wireName() not found in ${toolFile}`);
    wireNames.push(wire);
  }
  return wireNames;
}

function loadUiToolIdsFromTs() {
  const path = join(repoRoot, 'sources/src/studioAiOrchestrationToolIds.ts');
  const src = readFileSync(path, 'utf8');
  const block = src.match(/STUDIO_AI_BUILTIN_TOOL_IDS[^=]*=\s*\[([\s\S]*?)\]\s*as const/);
  if (!block) throw new Error('Could not parse STUDIO_AI_BUILTIN_TOOL_IDS');
  return [...block[1].matchAll(/['"]([^'"]+)['"]/g)]
    .map((m) => m[1])
    .filter((id) => id !== 'mcp:*');
}

function assertFixtureCoverage(recipeIds, toolWireNames) {
  const missingRecipes = recipeIds.filter((id) => !RECIPE_CASES[id]);
  const extraRecipes = Object.keys(RECIPE_CASES).filter((id) => !recipeIds.includes(id));
  const missingTools = toolWireNames.filter((id) => !TOOL_CASES[id]);
  const extraTools = Object.keys(TOOL_CASES).filter((id) => !toolWireNames.includes(id));
  const errors = [];
  if (missingRecipes.length) errors.push(`Missing RECIPE_CASES for: ${missingRecipes.join(', ')}`);
  if (extraRecipes.length) errors.push(`Extra RECIPE_CASES not in bundled catalog: ${extraRecipes.join(', ')}`);
  if (missingTools.length) errors.push(`Missing TOOL_CASES for: ${missingTools.join(', ')}`);
  if (extraTools.length) errors.push(`Extra TOOL_CASES not in CORE_TOOLS: ${extraTools.join(', ')}`);
  if (errors.length) {
    throw new Error(`Fixture coverage failed:\n${errors.join('\n')}`);
  }
}

function caseToTurn(id, matrixCase, group) {
  /** @type {Record<string, unknown>} */
  const turn = {
    id,
    group,
    summary: matrixCase.summary || id,
    prompt: matrixCase.prompt,
  };
  if (matrixCase.request) turn.request = matrixCase.request;
  if (matrixCase.expect) turn.expect = matrixCase.expect;
  if (matrixCase.optional) turn.optional = true;
  if (matrixCase.skipUnless) turn.skipUnless = matrixCase.skipUnless;
  if (matrixCase.partialOnMissingConfig) turn.partialOnMissingConfig = true;
  if (matrixCase.freshChat) turn.freshChat = true;
  return turn;
}

function buildRecipesDoc(recipeIds) {
  /** @type {Record<string, unknown>[]} */
  const turns = [];
  for (const id of recipeIds) {
    if (id === 'new_content_item_from_chat_draft') {
      turns.push({ ...RECIPE_DRAFT_PRIOR_TURN });
    }
    turns.push(caseToTurn(id, RECIPE_CASES[id], 'intent-recipes'));
  }
  return {
    description:
      'One turn per bundled intent recipe (generated from tool-recipe-matrix.mjs). Requires intentRecipeRouting.enabled on the site. Integration optional recipes run by default; missing keys/routing → partial. Destructive turns need CHAT_MATRIX_ALLOW_WRITES / CHAT_MATRIX_ALLOW_PUBLISH.',
    defaults: {
      siteId: 'aiat-2',
      llm: 'openAI',
      llmModel: 'gpt-4o-mini',
      authoringSurface: 'formEngine',
      formEngineClientJsonApply: true,
      formEngineItemPath: '/site/website/index.xml',
      contentTypeId: '/page/home',
      contentTypeLabel: 'Home',
      enableTools: true,
    },
    turns,
  };
}

function buildToolsDoc(toolWireNames) {
  const turns = toolWireNames.map((id) => caseToTurn(id, TOOL_CASES[id], 'builtin-tools'));
  return {
    description:
      'One turn per CORE_TOOLS wire name with enabledBuiltInTools allowlist (generated). Optional turns need env flags.',
    defaults: {
      siteId: 'aiat-2',
      llm: 'openAI',
      llmModel: 'gpt-4o-mini',
      enableTools: true,
    },
    turns,
  };
}

function writeOrCheck(name, doc) {
  const path = join(scenariosDir, name);
  const text = `${JSON.stringify(doc, null, 2)}\n`;
  if (checkOnly) {
    const existing = readFileSync(path, 'utf8');
    if (existing !== text) {
      throw new Error(`${name} is out of date — run: node scripts/test/functional/generate-tool-recipe-scenarios.mjs`);
    }
    console.log(`✓ ${name} up to date`);
  } else {
    writeFileSync(path, text, 'utf8');
    console.log(`Wrote ${path} (${doc.turns.length} turns)`);
  }
}

function main() {
  const { ids: recipeIds } = loadRecipeIdsFromBundled();
  const toolWireNames = loadCoreToolWireNamesFromRegistry();
  const uiIds = loadUiToolIdsFromTs();

  const uiSet = new Set(uiIds);
  const coreSet = new Set(toolWireNames);
  const uiOnly = uiIds.filter((id) => !coreSet.has(id) && id !== 'ListContentTranslationScope');
  const coreOnly = toolWireNames.filter((id) => !uiSet.has(id));
  if (uiOnly.length || coreOnly.length) {
    console.warn(
      `Note: UI vs CORE_TOOLS diff — uiOnly=[${uiOnly.join(', ')}] coreOnly=[${coreOnly.join(', ')}] (ListContentTranslationScope is a server alias)`,
    );
  }

  assertFixtureCoverage(recipeIds, toolWireNames);
  writeOrCheck('intent-recipes-all.json', buildRecipesDoc(recipeIds));
  writeOrCheck('tools-all.json', buildToolsDoc(toolWireNames));
  console.log(`Coverage OK: ${recipeIds.length} recipes, ${toolWireNames.length} tools`);
}

main();
