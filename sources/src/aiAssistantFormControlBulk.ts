import { firstValueFrom } from 'rxjs';
import { writeConfiguration } from '@craftercms/studio-ui/services/configuration';
import { writeContent } from '@craftercms/studio-ui/services/content';
import { fetchStudioConfigFileUtf8, studioConfigRelativePath } from './aiAssistantScriptsApi';
import { aiAssistantStudioPluginId } from './consts';

export const AI_ASSISTANT_FORM_FIELD_MARKER_BEGIN = '<!-- AI_ASSISTANT_PLUGIN_FIELD_BEGIN -->';
export const AI_ASSISTANT_FORM_FIELD_MARKER_END = '<!-- AI_ASSISTANT_PLUGIN_FIELD_END -->';

/** Must match {@code getName()} in {@code sources/control/ai-assistant/main.js} and site-config-tools control name. */
export const STUDIO_AI_ASSISTANT_CONTROL_TYPE = 'ai-assistant';

/** Earlier bulk inserts used pluginId/name; Studio only resolves {@link STUDIO_AI_ASSISTANT_CONTROL_TYPE}. */
const LEGACY_BULK_CONTROL_TYPE = `${aiAssistantStudioPluginId}/ai-assistant`;

const DEFAULT_FIELD_ID = 'cqAiAssistantStudio';

/**
 * Required for {@code getPluginInfo} in forms-engine: without {@code <plugin>}, Studio loads
 * {@code /static-assets/components/cstudio-forms/controls/ai-assistant.js} (missing) instead of the plugin URL.
 */
const AI_ASSISTANT_FIELD_PLUGIN_FRAGMENT = `<plugin>
    <pluginId>${aiAssistantStudioPluginId}</pluginId>
    <type>control</type>
    <name>${STUDIO_AI_ASSISTANT_CONTROL_TYPE}</name>
    <filename>main.js</filename>
  </plugin>`;

/** Minimal field block matching Content Types editor serialization (type + plugin + properties). */
const MARKED_FIELD_FRAGMENT = `${AI_ASSISTANT_FORM_FIELD_MARKER_BEGIN}
<field>
  <type>${STUDIO_AI_ASSISTANT_CONTROL_TYPE}</type>
  <id>${DEFAULT_FIELD_ID}</id>
  <iceId></iceId>
  <title>AI Assistant</title>
  <description></description>
  <defaultValue></defaultValue>
  <help></help>
  ${AI_ASSISTANT_FIELD_PLUGIN_FRAGMENT}
  <properties>
  </properties>
  <constraints>
  </constraints>
</field>
${AI_ASSISTANT_FORM_FIELD_MARKER_END}
`;

function normalizeContentTypeId(id: string): string {
  const t = (id || '').trim();
  return t.startsWith('/') ? t : `/${t}`;
}

function formDefinitionStudioPath(contentTypeId: string): string {
  const normalized = normalizeContentTypeId(contentTypeId);
  return studioConfigRelativePath(`content-types${normalized}/form-definition.xml`);
}

function formDefinitionSandboxPath(contentTypeId: string): string {
  const normalized = normalizeContentTypeId(contentTypeId);
  return `/config/studio/content-types${normalized}/form-definition.xml`;
}

function hasWrongBulkControlType(xml: string): boolean {
  return xml.includes(`<type>${LEGACY_BULK_CONTROL_TYPE}</type>`);
}

function aiAssistantFieldBlock(xml: string): string | null {
  const re = new RegExp(
    `<field>[\\s\\S]*?<type>\\s*${STUDIO_AI_ASSISTANT_CONTROL_TYPE}\\s*</type>[\\s\\S]*?<id>\\s*${DEFAULT_FIELD_ID}\\s*</id>[\\s\\S]*?</field>`,
    'm'
  );
  return xml.match(re)?.[0] ?? null;
}

/** Field present with correct {@code <type>} (marker or default id). */
function hasCorrectPluginAssistantField(xml: string): boolean {
  if (!xml) return false;
  if (hasWrongBulkControlType(xml)) return false;
  if (xml.includes(AI_ASSISTANT_FORM_FIELD_MARKER_BEGIN)) return true;
  return aiAssistantFieldBlock(xml) != null;
}

/** {@code <plugin>} block so forms-engine loads plugin {@code main.js}, not built-in controls path. */
function hasAiAssistantPluginLinkage(xml: string): boolean {
  const block = aiAssistantFieldBlock(xml);
  if (!block) return false;
  return (
    block.includes('<plugin>') &&
    block.includes(`<pluginId>${aiAssistantStudioPluginId}</pluginId>`) &&
    block.includes(`<name>${STUDIO_AI_ASSISTANT_CONTROL_TYPE}</name>`)
  );
}

/** Repair bulk inserts that used {@code pluginId/control-name} in {@code <type>}. */
function repairBulkControlType(xml: string): string {
  if (!hasWrongBulkControlType(xml)) return xml;
  return xml.split(`<type>${LEGACY_BULK_CONTROL_TYPE}</type>`).join(`<type>${STUDIO_AI_ASSISTANT_CONTROL_TYPE}</type>`);
}

/** Bulk inserts before plugin linkage fix omitted {@code <plugin>}; control script never loads in Content Form. */
function repairMissingPluginLinkage(xml: string): string {
  const block = aiAssistantFieldBlock(xml);
  if (!block || hasAiAssistantPluginLinkage(xml)) return xml;
  const indented = AI_ASSISTANT_FIELD_PLUGIN_FRAGMENT.split('\n')
    .map((line) => (line.trim() ? `  ${line}` : line))
    .join('\n');
  const patchedBlock = block.replace(/<help>\s*<\/help>\s*/i, (m) => `${m}${indented}\n  `);
  if (patchedBlock === block) {
    return xml.replace(block, block.replace(/<type>\s*ai-assistant\s*<\/type>\s*/i, (m) => `${m}${indented}\n  `));
  }
  return xml.replace(block, patchedBlock);
}

function stripMarkedBlock(xml: string): string {
  const re = new RegExp(
    `${AI_ASSISTANT_FORM_FIELD_MARKER_BEGIN.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}[\\s\\S]*?${AI_ASSISTANT_FORM_FIELD_MARKER_END.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*`,
    'm'
  );
  return xml.replace(re, '');
}

/** Remove legacy unmarked field (correct or wrong type + default id). */
function stripLegacyUnmarkedField(xml: string): string {
  const types = [STUDIO_AI_ASSISTANT_CONTROL_TYPE, LEGACY_BULK_CONTROL_TYPE.replace(/\//g, '\\/')];
  let out = xml;
  for (const typePat of types) {
    const re = new RegExp(
      `<field>\\s*<type>\\s*${typePat}\\s*</type>[\\s\\S]*?<id>\\s*${DEFAULT_FIELD_ID}\\s*</id>[\\s\\S]*?</field>\\s*`,
      'm'
    );
    out = out.replace(re, '');
  }
  return out;
}

/**
 * Inserts the marked field after the opening {@code <fields>} of the first {@code <section>} under {@code <sections>}.
 * Crafter form definitions almost always have {@code <title>}, {@code <defaultOpen>}, etc. between {@code <section>} and {@code <fields>}.
 */
function insertAfterFirstSectionFieldsOpen(xml: string): string | null {
  const sectionsIdx = xml.search(/<sections\b[^>]*>/i);
  if (sectionsIdx < 0) return null;
  const afterSections = xml.slice(sectionsIdx);
  const sectionRel = afterSections.search(/<section\b[^>]*>/i);
  if (sectionRel < 0) return null;
  const afterSection = afterSections.slice(sectionRel);
  const fieldsMatch = afterSection.match(/<fields\b[^>]*>/i);
  if (!fieldsMatch || fieldsMatch.index == null) return null;
  const insertPos = sectionsIdx + sectionRel + fieldsMatch.index + fieldsMatch[0].length;
  return xml.slice(0, insertPos) + '\n' + MARKED_FIELD_FRAGMENT + '\n' + xml.slice(insertPos);
}

/** Fallback when {@code <sections>} is absent (legacy or minimal form definitions). */
function insertAfterFirstFieldsOpen(xml: string): string | null {
  const fieldsMatch = xml.match(/<fields\b[^>]*>/i);
  if (!fieldsMatch || fieldsMatch.index == null) return null;
  const insertPos = fieldsMatch.index + fieldsMatch[0].length;
  return xml.slice(0, insertPos) + '\n' + MARKED_FIELD_FRAGMENT + '\n' + xml.slice(insertPos);
}

export async function fetchFormDefinitionXml(siteId: string, contentTypeId: string): Promise<string> {
  const rel = formDefinitionStudioPath(contentTypeId);
  return fetchStudioConfigFileUtf8(siteId, rel);
}

export async function writeFormDefinitionXml(siteId: string, contentTypeId: string, xml: string): Promise<void> {
  const rel = formDefinitionStudioPath(contentTypeId);
  const sandboxPath = formDefinitionSandboxPath(contentTypeId);
  try {
    await firstValueFrom(writeConfiguration(siteId, rel, 'studio', xml));
  } catch (configErr) {
    try {
      await firstValueFrom(writeContent(siteId, sandboxPath, xml, { unlock: true }));
    } catch (contentErr) {
      const a = configErr instanceof Error ? configErr.message : String(configErr);
      const b = contentErr instanceof Error ? contentErr.message : String(contentErr);
      throw new Error(`Could not save form-definition (${rel}): ${a}; sandbox write (${sandboxPath}): ${b}`);
    }
  }
}

export type BulkFormMutation = 'add' | 'remove';

export async function mutateFormDefinitionForContentType(
  siteId: string,
  contentTypeId: string,
  mode: BulkFormMutation
): Promise<'ok' | 'skipped' | 'missing_form' | 'unchanged'> {
  let xml = await fetchFormDefinitionXml(siteId, contentTypeId);
  if (!xml.trim()) return 'missing_form';

  if (mode === 'remove') {
    const next = stripLegacyUnmarkedField(stripMarkedBlock(xml));
    if (next === xml) return 'unchanged';
    await writeFormDefinitionXml(siteId, contentTypeId, next);
    return 'ok';
  }

  // add: fix wrong <type>, missing <plugin>, insert when absent; skip only when fully wired
  if (hasWrongBulkControlType(xml)) {
    xml = repairBulkControlType(xml);
    await writeFormDefinitionXml(siteId, contentTypeId, xml);
    return 'ok';
  }
  if (hasCorrectPluginAssistantField(xml) && !hasAiAssistantPluginLinkage(xml)) {
    const repaired = repairMissingPluginLinkage(xml);
    if (repaired !== xml) {
      await writeFormDefinitionXml(siteId, contentTypeId, repaired);
      return 'ok';
    }
  }
  if (hasCorrectPluginAssistantField(xml) && hasAiAssistantPluginLinkage(xml)) return 'skipped';

  const inserted = insertAfterFirstSectionFieldsOpen(xml) ?? insertAfterFirstFieldsOpen(xml);
  if (inserted == null) return 'missing_form';
  await writeFormDefinitionXml(siteId, contentTypeId, inserted);
  const verify = await fetchFormDefinitionXml(siteId, contentTypeId);
  if (!hasCorrectPluginAssistantField(verify) || !hasAiAssistantPluginLinkage(verify)) {
    throw new Error(`Write did not persist AI Assistant field for ${contentTypeId}`);
  }
  return 'ok';
}

export async function runBulkFormControlChange(
  siteId: string,
  mode: BulkFormMutation,
  contentTypeIds: string[]
): Promise<{ ok: number; skipped: number; missing: number; unchanged: number; errors: string[] }> {
  const stats = { ok: 0, skipped: 0, missing: 0, unchanged: 0, errors: [] as string[] };
  const ids = [...new Set(contentTypeIds.map(normalizeContentTypeId).filter(Boolean))];
  for (const id of ids) {
    try {
      const r = await mutateFormDefinitionForContentType(siteId, id, mode);
      if (r === 'ok') stats.ok++;
      else if (r === 'skipped') stats.skipped++;
      else if (r === 'missing_form') stats.missing++;
      else stats.unchanged++;
    } catch (e) {
      stats.errors.push(`${id}: ${e instanceof Error ? e.message : String(e)}`);
    }
  }
  return stats;
}
