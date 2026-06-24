import { buildStudioAuthHeaders } from './aiAssistantApi';
import type {
  AiAssistantSecretSaveEntry,
  AiAssistantSecretsIndexResponse,
  AiAssistantSecretsMutateResponse
} from './aiAssistantSecretsModel';
import { mergeKnownSecretsWithServer } from './aiAssistantSecretsModel';
import { fetchAiAssistantScriptsIndex } from './aiAssistantScriptsApi';

const SCRIPTS_BASE = '/studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/scripts';
const SECRETS_INDEX =
  '/studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/secrets/index';

function withSite(url: string, siteId: string): string {
  const sep = url.includes('?') ? '&' : '?';
  return `${url}${sep}siteId=${encodeURIComponent(siteId)}`;
}

function unwrapPluginScriptBody(body: unknown): unknown {
  if (!body || typeof body !== 'object') return body;
  const o = body as Record<string, unknown>;
  const inner = o.result;
  if (inner && typeof inner === 'object' && !Array.isArray(inner)) return inner;
  return body;
}

async function fetchDedicatedSecretsIndex(siteId: string): Promise<AiAssistantSecretsIndexResponse | null> {
  try {
    const res = await fetch(withSite(SECRETS_INDEX, siteId), {
      method: 'GET',
      credentials: 'include',
      headers: { ...buildStudioAuthHeaders() }
    });
    const raw = await res.json().catch(() => ({}));
    const data = unwrapPluginScriptBody(raw) as AiAssistantSecretsIndexResponse;
    if (!res.ok || data.ok === false) {
      return null;
    }
    if ((data.knownSecrets ?? []).length > 0) {
      return data;
    }
    return null;
  } catch {
    return null;
  }
}

/**
 * Loads secrets admin rows. Built-in LLM provider rows always come from the client catalog;
 * server data (when present) supplies configured state and custom secrets.
 */
export async function fetchAiAssistantSecretsIndex(siteId: string): Promise<AiAssistantSecretsIndexResponse> {
  const idx = await fetchAiAssistantScriptsIndex(siteId);
  if (idx.ok === false) {
    return {
      ok: true,
      siteId,
      knownSecrets: mergeKnownSecretsWithServer(),
      customSecrets: [],
      catalogWarning: idx.message ?? 'Could not load site scripts index; showing built-in provider keys only.'
    };
  }

  let serverKnown = idx.knownSecrets;
  let customSecrets = idx.customSecrets ?? [];
  let studioPath = idx.secretsStudioPath;
  const serverError = idx.secretsError?.trim();

  if (!serverKnown?.length && !serverError) {
    const dedicated = await fetchDedicatedSecretsIndex(siteId);
    if (dedicated) {
      serverKnown = dedicated.knownSecrets;
      customSecrets = dedicated.customSecrets ?? customSecrets;
      studioPath = dedicated.studioPath ?? studioPath;
    }
  }

  const knownSecrets = mergeKnownSecretsWithServer(serverKnown);
  const secretsSeeded = Boolean(idx.secretsSeeded);
  let catalogWarning: string | undefined;
  if (serverError) {
    catalogWarning = `${serverError} Showing built-in provider keys; save may fail until the plugin is updated on Studio.`;
  } else if (!serverKnown?.length) {
    catalogWarning =
      'Server secrets catalog not available yet (reinstall the plugin on this site). Built-in provider keys are shown below — click Save secrets to write secrets.json.';
  }

  return {
    ok: true,
    siteId,
    studioPath,
    knownSecrets,
    customSecrets,
    catalogWarning,
    secretsSeeded
  };
}

export async function saveAiAssistantSecretsEntries(
  siteId: string,
  entries: AiAssistantSecretSaveEntry[]
): Promise<AiAssistantSecretsMutateResponse> {
  const res = await fetch(withSite(`${SCRIPTS_BASE}/mutate`, siteId), {
    method: 'POST',
    credentials: 'include',
    headers: {
      ...buildStudioAuthHeaders(),
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ siteId, action: 'saveSecrets', entries })
  });
  const raw = await res.json().catch(() => ({}));
  const data = unwrapPluginScriptBody(raw) as AiAssistantSecretsMutateResponse;
  if (!res.ok) {
    return { ok: false, message: data.message ?? (raw as { message?: string }).message ?? res.statusText };
  }
  return data;
}
