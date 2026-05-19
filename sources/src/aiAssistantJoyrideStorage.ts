import { AI_ASSISTANT_PLUGIN_VERSION } from './aiAssistantPluginVersion';

const STORAGE_KEY = 'org.craftercms.aiassistant.joyride.seenVersion';

export function joyrideSeenPluginVersion(): string | null {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    return v?.trim() || null;
  } catch {
    return null;
  }
}

export function shouldShowConfigurationJoyride(): boolean {
  return joyrideSeenPluginVersion() !== AI_ASSISTANT_PLUGIN_VERSION;
}

export function markConfigurationJoyrideSeen(): void {
  try {
    localStorage.setItem(STORAGE_KEY, AI_ASSISTANT_PLUGIN_VERSION);
  } catch {
    /* ignore quota / private mode */
  }
}
