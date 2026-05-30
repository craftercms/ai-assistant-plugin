import type { PluginFileBuilder } from '@craftercms/studio-ui/models/PluginFileBuilder';
import type { WidgetDescriptor } from '@craftercms/studio-ui/models/WidgetDescriptor';
import {
  aiAssistantStudioPluginId,
  autonomousAssistantsWidgetId,
  dialogContentWidgetId,
  formControlWidgetId,
  helperWidgetId,
  logoWidgetId,
  popoverWidgetId,
  projectToolsAiAssistantConfigWidgetId,
  projectToolsCentralAgentsWidgetId,
  projectToolsScriptsSandboxWidgetId,
  projectToolsStudioUiSettingsWidgetId
} from './consts';

const AI_ASSISTANT_COMPONENTS_BUNDLE = 'components';
const AI_ASSISTANT_COMPONENTS_FILE = 'index.js';
const AI_ASSISTANT_PLUGIN_TYPE = 'aiassistant';

/** Widget ids exported from the Studio components bundle and resolved via {@link importPlugin}. */
const AI_ASSISTANT_WIDGET_IDS = new Set<string>([
  helperWidgetId,
  dialogContentWidgetId,
  formControlWidgetId,
  autonomousAssistantsWidgetId,
  popoverWidgetId,
  logoWidgetId,
  'craftercms.components.aiassistant.AiAssistantLogo',
  projectToolsAiAssistantConfigWidgetId,
  projectToolsCentralAgentsWidgetId,
  projectToolsScriptsSandboxWidgetId,
  projectToolsStudioUiSettingsWidgetId
]);

export function isAiAssistantWidgetId(id: unknown): id is string {
  return typeof id === 'string' && AI_ASSISTANT_WIDGET_IDS.has(id);
}

export function createAiAssistantPluginFileBuilder(siteId: string): PluginFileBuilder {
  const site = (siteId || '').trim();
  return {
    site,
    type: AI_ASSISTANT_PLUGIN_TYPE,
    name: AI_ASSISTANT_COMPONENTS_BUNDLE,
    file: AI_ASSISTANT_COMPONENTS_FILE,
    id: aiAssistantStudioPluginId
  };
}

function pluginNeedsSite(plugin: unknown): boolean {
  if (!plugin || typeof plugin !== 'object') return true;
  return !(plugin as PluginFileBuilder).site?.trim();
}

/** True when a widget tree references AI Assistant widgets without a loadable plugin descriptor. */
export function widgetDescriptorTreeNeedsAiAssistantPlugin(descriptor: unknown): boolean {
  if (!descriptor || typeof descriptor !== 'object') return false;
  const d = descriptor as WidgetDescriptor;
  if (isAiAssistantWidgetId(d.id) && pluginNeedsSite(d.plugin)) return true;
  const nested = d.configuration?.widgets;
  if (Array.isArray(nested)) {
    return nested.some((w) => widgetDescriptorTreeNeedsAiAssistantPlugin(w));
  }
  return false;
}

/** Attach {@link createAiAssistantPluginFileBuilder} to AI Assistant widgets missing `plugin.site`. */
export function patchWidgetDescriptorTreeWithAiAssistantPlugin<T>(descriptor: T, siteId: string): T {
  if (!descriptor || typeof descriptor !== 'object') return descriptor;
  const d = descriptor as WidgetDescriptor & Record<string, unknown>;
  const next: WidgetDescriptor & Record<string, unknown> = { ...d };
  if (isAiAssistantWidgetId(next.id) && pluginNeedsSite(next.plugin)) {
    next.plugin = createAiAssistantPluginFileBuilder(siteId);
  }
  const nested = next.configuration?.widgets;
  if (Array.isArray(nested)) {
    next.configuration = {
      ...next.configuration,
      widgets: nested.map((w) => patchWidgetDescriptorTreeWithAiAssistantPlugin(w, siteId))
    };
  }
  return next as T;
}

const ICE_PANEL_PAGE_STORAGE_PREFIX = '.ICEToolsPanel.';

function icePanelStorageKey(username: string, siteUuid: string): string {
  return `craftercms.${username}${ICE_PANEL_PAGE_STORAGE_PREFIX}${siteUuid}`;
}

/** Patch persisted ICE panel pages saved before plugin descriptors were embedded (cold-reload race). */
export function patchStoredIcePanelPageInLocalStorage(
  siteUuid: string,
  username: string,
  siteId: string
): WidgetDescriptor | null {
  if (typeof localStorage === 'undefined' || !siteUuid?.trim() || !username?.trim() || !siteId?.trim()) {
    return null;
  }
  const key = icePanelStorageKey(username, siteUuid);
  const raw = localStorage.getItem(key);
  if (!raw) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!widgetDescriptorTreeNeedsAiAssistantPlugin(parsed)) return null;
  const patched = patchWidgetDescriptorTreeWithAiAssistantPlugin(parsed as WidgetDescriptor, siteId);
  try {
    localStorage.setItem(key, JSON.stringify(patched));
  } catch {
    // ignore quota / private mode
  }
  return patched;
}

/** Best-effort migration for any persisted ICE panel page missing plugin metadata. */
export function patchAllStoredIcePanelPagesInLocalStorage(siteId: string): void {
  if (typeof localStorage === 'undefined' || !siteId.trim()) return;
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i);
    if (!key?.includes(ICE_PANEL_PAGE_STORAGE_PREFIX)) continue;
    try {
      const raw = localStorage.getItem(key);
      if (!raw) continue;
      const parsed = JSON.parse(raw) as unknown;
      if (!widgetDescriptorTreeNeedsAiAssistantPlugin(parsed)) continue;
      localStorage.setItem(key, JSON.stringify(patchWidgetDescriptorTreeWithAiAssistantPlugin(parsed as WidgetDescriptor, siteId)));
    } catch {
      // ignore malformed entries
    }
  }
}
