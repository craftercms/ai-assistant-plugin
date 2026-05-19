import { AI_ASSISTANT_PLUGIN_VERSION } from './aiAssistantPluginVersion';

const STORAGE_KEY_PREFIX = 'org.craftercms.aiassistant.joyride.seenVersion';

/** @deprecated Global key from pre–per-site joyride; cleared when read so other sites are not blocked. */
const LEGACY_STORAGE_KEY = STORAGE_KEY_PREFIX;

function storageKeyForSite(siteId: string): string {
  return `${STORAGE_KEY_PREFIX}.${siteId.trim()}`;
}

function clearLegacyJoyrideSeenIfPresent(): void {
  try {
    if (localStorage.getItem(LEGACY_STORAGE_KEY) != null) {
      localStorage.removeItem(LEGACY_STORAGE_KEY);
    }
  } catch {
    /* ignore */
  }
}

export function joyrideSeenPluginVersion(siteId: string): string | null {
  const sid = (siteId || '').trim();
  if (!sid) {
    return null;
  }
  try {
    const v = localStorage.getItem(storageKeyForSite(sid));
    return v?.trim() || null;
  } catch {
    return null;
  }
}

/** True when this site has not completed/skipped the tour for the current plugin version. */
export function shouldShowConfigurationJoyride(siteId: string): boolean {
  const sid = (siteId || '').trim();
  if (!sid) {
    return false;
  }
  clearLegacyJoyrideSeenIfPresent();
  return joyrideSeenPluginVersion(sid) !== AI_ASSISTANT_PLUGIN_VERSION;
}

export function markConfigurationJoyrideSeen(siteId: string): void {
  const sid = (siteId || '').trim();
  if (!sid) {
    return;
  }
  try {
    localStorage.setItem(storageKeyForSite(sid), AI_ASSISTANT_PLUGIN_VERSION);
    clearLegacyJoyrideSeenIfPresent();
  } catch {
    /* ignore quota / private mode */
  }
}
