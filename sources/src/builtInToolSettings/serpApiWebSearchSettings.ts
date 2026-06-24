import type { BuiltInToolSettingsDescriptor, BuiltInToolSettingsValidation } from './types';
import SerpApiWebSearchConfigureDialog from './SerpApiWebSearchConfigureDialog';

export const SERP_API_WEB_SEARCH_WIRE = 'SerpApiWebSearch';

/** Site defaults for SerpAPI (API key is on Project Tools → Secrets). */
export interface SerpApiWebSearchSettingsFormState {
  engine: string;
  googleDomain: string;
  gl: string;
  hl: string;
  location: string;
  num: string;
  device: string;
  safe: string;
}

export function defaultSerpApiWebSearchSettingsFormState(): SerpApiWebSearchSettingsFormState {
  return {
    engine: 'google',
    googleDomain: 'google.com',
    gl: 'us',
    hl: 'en',
    location: 'United States',
    num: '10',
    device: 'desktop',
    safe: 'active'
  };
}

function parseSerpApiSettingsFromUnknown(raw: unknown): SerpApiWebSearchSettingsFormState {
  const base = defaultSerpApiWebSearchSettingsFormState();
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    return base;
  }
  const o = raw as Record<string, unknown>;
  const defaults =
    o.defaults && typeof o.defaults === 'object' && !Array.isArray(o.defaults)
      ? (o.defaults as Record<string, unknown>)
      : {};
  const numRaw = defaults.num ?? o.num;
  return {
    engine: defaults.engine != null ? String(defaults.engine) : base.engine,
    googleDomain: defaults.googleDomain != null ? String(defaults.googleDomain) : base.googleDomain,
    gl: defaults.gl != null ? String(defaults.gl) : base.gl,
    hl: defaults.hl != null ? String(defaults.hl) : base.hl,
    location: defaults.location != null ? String(defaults.location) : base.location,
    num: numRaw != null ? String(numRaw) : base.num,
    device: defaults.device != null ? String(defaults.device) : base.device,
    safe: defaults.safe != null ? String(defaults.safe) : base.safe
  };
}

function serpApiSettingsToJsonObject(state: SerpApiWebSearchSettingsFormState): Record<string, unknown> {
  const num = Number(state.num.trim());
  const defaults: Record<string, unknown> = {
    engine: state.engine.trim() || 'google',
    googleDomain: state.googleDomain.trim() || 'google.com',
    gl: state.gl.trim() || 'us',
    hl: state.hl.trim() || 'en',
    device: state.device.trim() || 'desktop',
    safe: state.safe.trim() || 'active'
  };
  const loc = state.location.trim();
  if (loc) {
    defaults.location = loc;
  }
  if (Number.isFinite(num) && num >= 1 && num <= 20) {
    defaults.num = Math.round(num);
  }
  return { defaults };
}

function validateSerpApiWebSearchSettings(state: SerpApiWebSearchSettingsFormState): BuiltInToolSettingsValidation {
  const serpNum = Number(state.num.trim());
  if (state.num.trim() && (!Number.isFinite(serpNum) || serpNum < 1 || serpNum > 20)) {
    return { ok: false, message: 'SerpAPI default result count must be between 1 and 20 when set.' };
  }
  return { ok: true };
}

export const serpApiWebSearchSettingsDescriptor: BuiltInToolSettingsDescriptor<SerpApiWebSearchSettingsFormState> =
  {
    wireName: SERP_API_WEB_SEARCH_WIRE,
    defaultState: defaultSerpApiWebSearchSettingsFormState,
    parse: parseSerpApiSettingsFromUnknown,
    serialize: serpApiSettingsToJsonObject,
    validate: validateSerpApiWebSearchSettings,
    ConfigureDialog: SerpApiWebSearchConfigureDialog
  };
