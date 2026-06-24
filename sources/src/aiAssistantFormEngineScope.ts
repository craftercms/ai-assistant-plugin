/**
 * Author-selected conversation scope for the Studio **content-type form** assistant (not XB / preview).
 */
export type FormEngineAuthoringScope = 'content' | 'field';

export interface FormEngineFieldFocus {
  fieldId: string;
  fieldLabel: string;
  fieldIndex?: string | number;
}

export type FormEngineFieldFocusSelection = FormEngineFieldFocus | null;

const FORM_FIELD_FOCUS_EVENT = 'aiassistant-form-field-focus';

type WindowWithFormFocus = Window & {
  __aiassistantFormEngineFieldFocus?: FormEngineFieldFocusSelection;
};

/** Stable snapshot for `useSyncExternalStore` — must reuse reference when focus is unchanged. */
let cachedFocusSnapshot: FormEngineFieldFocus | null = null;
let cachedFocusSnapshotKey = '';

function focusSnapshotKey(raw: FormEngineFieldFocus | null | undefined): string {
  if (!raw?.fieldId) return '';
  const id = String(raw.fieldId).trim();
  if (!id) return '';
  const label = String(raw.fieldLabel ?? id).trim() || id;
  const idx = raw.fieldIndex;
  const idxPart = idx != null && String(idx).trim() !== '' ? `#${String(idx)}` : '';
  return `${id}\x1e${label}${idxPart}`;
}

function normalizeWindowFocusRaw(raw: unknown): FormEngineFieldFocus | null {
  if (!raw || typeof raw !== 'object') return null;
  const fieldId = String((raw as FormEngineFieldFocus).fieldId ?? '').trim();
  if (!fieldId) return null;
  const fieldLabel = String((raw as FormEngineFieldFocus).fieldLabel ?? fieldId).trim() || fieldId;
  const out: FormEngineFieldFocus = { fieldId, fieldLabel };
  const idx = (raw as FormEngineFieldFocus).fieldIndex;
  if (idx != null && String(idx).trim() !== '') {
    out.fieldIndex = idx;
  }
  return out;
}

function syncCachedFocusSnapshot(next: FormEngineFieldFocus | null): FormEngineFieldFocus | null {
  const key = focusSnapshotKey(next);
  if (key === cachedFocusSnapshotKey) {
    return cachedFocusSnapshot;
  }
  cachedFocusSnapshotKey = key;
  cachedFocusSnapshot = next;
  return cachedFocusSnapshot;
}

function readWindowFocus(): FormEngineFieldFocusSelection {
  if (typeof window === 'undefined') return null;
  const w = window as WindowWithFormFocus;
  return syncCachedFocusSnapshot(normalizeWindowFocusRaw(w.__aiassistantFormEngineFieldFocus));
}

/** Legacy control (`control/ai-assistant/main.js`) publishes focus via {@link publishFormEngineFieldFocus}. */
export function publishFormEngineFieldFocus(focus: FormEngineFieldFocusSelection): void {
  if (typeof window === 'undefined') return;
  const w = window as WindowWithFormFocus;
  const normalized = focus ? normalizeWindowFocusRaw(focus) : null;
  const prevKey = cachedFocusSnapshotKey;
  const nextKey = focusSnapshotKey(normalized);
  w.__aiassistantFormEngineFieldFocus = normalized;
  syncCachedFocusSnapshot(normalized);
  if (nextKey === prevKey) return;
  try {
    window.dispatchEvent(new CustomEvent(FORM_FIELD_FOCUS_EVENT, { detail: normalized }));
  } catch {
    /* ignore */
  }
}

export function getFormEngineFieldFocus(): FormEngineFieldFocusSelection {
  return readWindowFocus();
}

export function subscribeFormEngineFieldFocus(listener: () => void): () => void {
  if (typeof window === 'undefined') return () => undefined;
  const handler = () => listener();
  window.addEventListener(FORM_FIELD_FOCUS_EVENT, handler);
  return () => window.removeEventListener(FORM_FIELD_FOCUS_EVENT, handler);
}

export function buildFormEngineFieldSelectionKey(focus: FormEngineFieldFocus | null | undefined): string {
  if (!focus?.fieldId) return '';
  const idx = focus.fieldIndex;
  return idx != null && String(idx).trim() !== ''
    ? `${focus.fieldId}#${String(idx)}`
    : focus.fieldId;
}

/** Resolve Studio form-definition field title for a top-level or dotted field id. */
export function resolveFormFieldLabelFromDefinitionXml(definitionXml: string, fieldId: string): string {
  const id = fieldId.trim();
  if (!id || !definitionXml.trim()) return '';
  const topId = id.split('.')[0]?.trim() || id;
  const fieldRe = /<field\b[^>]*>([\s\S]*?)<\/field>/gi;
  let m: RegExpExecArray | null;
  while ((m = fieldRe.exec(definitionXml)) !== null) {
    const block = m[1];
    const idm = /<id>([^<]+)<\/id>/i.exec(block);
    if (!idm || idm[1].trim() !== topId) continue;
    const titlem = /<title>([^<]*)<\/title>/i.exec(block);
    return titlem ? titlem[1].trim() : '';
  }
  return '';
}

export function formatFormEngineFieldScopeButtonLabel(focus: FormEngineFieldFocus | undefined): string {
  if (!focus?.fieldId) return '';
  return focus.fieldLabel?.trim() || focus.fieldId;
}

/**
 * Maps form-engine UI scope to stream POST fields (reuses xbFocused* wire names for field metadata).
 */
export function buildScopedFormEngineStreamContext(args: {
  scope: FormEngineAuthoringScope;
  contentPath?: string;
  fieldFocus?: FormEngineFieldFocus;
}): {
  authoringScope: FormEngineAuthoringScope;
  formEngineItemPath?: string;
  xbFocusedFieldId?: string;
  xbFocusedFieldIndex?: string | number;
  xbFocusedFieldLabel?: string;
} {
  const itemPath = (args.contentPath ?? '').trim();
  if (args.scope === 'field' && args.fieldFocus?.fieldId) {
    const f = args.fieldFocus;
    return {
      authoringScope: 'field',
      ...(itemPath ? { formEngineItemPath: itemPath } : {}),
      xbFocusedFieldId: f.fieldId,
      xbFocusedFieldLabel: f.fieldLabel || f.fieldId,
      ...(f.fieldIndex != null ? { xbFocusedFieldIndex: f.fieldIndex } : {})
    };
  }
  return {
    authoringScope: 'content',
    ...(itemPath ? { formEngineItemPath: itemPath } : {})
  };
}
