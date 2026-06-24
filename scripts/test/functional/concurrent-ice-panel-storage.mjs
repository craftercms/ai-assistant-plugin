#!/usr/bin/env node
/**
 * Offline: ICE panel localStorage entries are scoped per Studio username so concurrent
 * authors on the same browser profile do not share persisted widget descriptors.
 *
 * Mirrors key rules from sources/src/aiAssistantPluginDescriptor.ts (no browser/Studio required).
 */
import { randomUUID } from 'node:crypto';

const ICE_PANEL_PAGE_STORAGE_PREFIX = '.ICEToolsPanel.';
const HELPER_WIDGET_ID = 'craftercms.components.aiassistant.Helper';

function icePanelStorageKey(username, siteUuid) {
  return `craftercms.${username}${ICE_PANEL_PAGE_STORAGE_PREFIX}${siteUuid}`;
}

function widgetNeedsPlugin(descriptor) {
  if (!descriptor || typeof descriptor !== 'object') return false;
  const plugin = descriptor.plugin;
  if (!plugin || typeof plugin !== 'object') return true;
  return !String(plugin.site || '').trim();
}

function patchWidgetWithPlugin(descriptor, siteId) {
  if (!descriptor || typeof descriptor !== 'object') return descriptor;
  const next = { ...descriptor };
  if (next.id === HELPER_WIDGET_ID && widgetNeedsPlugin(next)) {
    next.plugin = { site: siteId, type: 'aiassistant', name: 'components', file: 'index.js' };
  }
  return next;
}

function createMockLocalStorage() {
  /** @type {Map<string, string>} */
  const map = new Map();
  return {
    getItem(key) {
      return map.has(key) ? map.get(key) : null;
    },
    setItem(key, value) {
      map.set(key, String(value));
    },
    key(i) {
      return [...map.keys()][i] ?? null;
    },
    get length() {
      return map.size;
    },
    _dump: () => new Map(map),
  };
}

function patchStoredIcePanelPage(localStorage, siteUuid, username, siteId) {
  const key = icePanelStorageKey(username, siteUuid);
  const raw = localStorage.getItem(key);
  if (!raw) return null;
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!widgetNeedsPlugin(parsed)) return null;
  const patched = patchWidgetWithPlugin(parsed, siteId);
  localStorage.setItem(key, JSON.stringify(patched));
  return patched;
}

function main() {
  const siteUuid = randomUUID();
  const siteId = 'concurrent-test-site';
  const ls = createMockLocalStorage();

  const legacyHelper = { id: HELPER_WIDGET_ID, configuration: { title: 'AI Assistant' } };
  ls.setItem(icePanelStorageKey('alice', siteUuid), JSON.stringify(legacyHelper));
  ls.setItem(icePanelStorageKey('bob', siteUuid), JSON.stringify(legacyHelper));

  const aliceBefore = JSON.parse(ls.getItem(icePanelStorageKey('alice', siteUuid)));
  const bobBefore = JSON.parse(ls.getItem(icePanelStorageKey('bob', siteUuid)));
  if (aliceBefore.plugin || bobBefore.plugin) {
    console.error('setup failed: fixtures should start without plugin');
    process.exit(1);
  }

  patchStoredIcePanelPage(ls, siteUuid, 'alice', siteId);

  const aliceAfter = JSON.parse(ls.getItem(icePanelStorageKey('alice', siteUuid)));
  const bobAfter = JSON.parse(ls.getItem(icePanelStorageKey('bob', siteUuid)));

  const errors = [];
  if (!aliceAfter.plugin?.site) {
    errors.push('alice: expected plugin.site after patch');
  }
  if (bobAfter.plugin) {
    errors.push('bob: must remain unpached when only alice is migrated');
  }
  if (icePanelStorageKey('alice', siteUuid) === icePanelStorageKey('bob', siteUuid)) {
    errors.push('storage keys must differ per username');
  }

  if (errors.length) {
    console.error('concurrent-ice-panel-storage FAILED:');
    for (const e of errors) console.error(`  - ${e}`);
    process.exit(1);
  }

  console.log('concurrent-ice-panel-storage OK (per-user ICE panel localStorage isolation)');
}

main();
