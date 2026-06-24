import { getGuestToHostBus, getHostToGuestBus } from '@craftercms/studio-ui/utils/subjects';
import {
  clearSelectedZones,
  clearSelectForEdit,
  contentTreeFieldSelected,
  guestCheckIn,
  iceZoneSelected,
  requestEdit,
  updateFieldValueOperation
} from '@craftercms/studio-ui/state/actions/preview';
import type { ContentType } from '@craftercms/studio-ui/models/ContentType';
import {
  isAssistantScopedFieldIceSelection,
  resolveXbComponentModelIdFromSelection,
  type PreviewGuestEditSelection,
  type PreviewGuestSlice
} from './aiAssistantAuthoringScope';

/** ICE coordinates from the preview iframe (DOM or guest→host messages). */
export interface DomIceSelection {
  modelId: string;
  modelPath: string;
  fieldId: string;
  fieldIndex?: string;
}

/** Component item chrome in the preview iframe (no field id). */
export interface DomIceComponentSelection {
  modelId: string;
  modelPath: string;
}

const PREVIEW_IFRAME_ID = 'crafterCMSPreviewIframe';
const ICE_REPORTER_SCRIPT_ID = 'aiassistant-ice-click-reporter-v6';

let installed = false;
let lastSelection: DomIceSelection | null = null;
let lastComponentSelection: DomIceComponentSelection | null = null;
let lastHoverSelection: DomIceSelection | null = null;
let lastFieldEventAt = 0;
let lastComponentEventAt = 0;
const listeners = new Set<(sel: DomIceSelection | null) => void>();
const componentListeners = new Set<(sel: DomIceComponentSelection | null) => void>();
const precedenceListeners = new Set<() => void>();

const ICE_CLICK_REPORTER_SOURCE = `(function () {
  if (window.__aiassistantIceReporterInstalled) return;
  window.__aiassistantIceReporterInstalled = true;

  function fieldParts(fieldId) {
    if (!fieldId) return [];
    return fieldId.indexOf('.') >= 0 ? fieldId.split('.').filter(Boolean) : [fieldId];
  }

  function resolveIce(target) {
    if (!target || target.nodeType !== 1) return null;
    var fieldHost = target.closest('[data-craftercms-field-id]');
    if (!fieldHost) return null;
    var modelHost = fieldHost.closest('[data-craftercms-model-id]') || fieldHost;
    var modelId = modelHost.getAttribute('data-craftercms-model-id');
    var modelPath = modelHost.getAttribute('data-craftercms-model-path') || '';
    var fieldId = fieldHost.getAttribute('data-craftercms-field-id');
    var index = fieldHost.getAttribute('data-craftercms-index') || '';
    if (!modelId || !fieldId) return null;
    return { modelId: modelId, modelPath: modelPath, fieldId: fieldId, index: index };
  }

  function resolveComponent(target) {
    if (!target || target.nodeType !== 1) return null;
    if (target.closest('[data-craftercms-field-id]')) return null;
    var modelHost = target.closest('[data-craftercms-model-id]');
    if (!modelHost) return null;
    var modelId = modelHost.getAttribute('data-craftercms-model-id');
    var modelPath = modelHost.getAttribute('data-craftercms-model-path') || '';
    if (!modelId) return null;
    return { modelId: modelId, modelPath: modelPath };
  }

  var lastHoverComponent = null;
  var lastHover = null;

  document.addEventListener(
    'mouseover',
    function (e) {
      var comp = resolveComponent(e.target);
      if (comp) lastHoverComponent = comp;
    },
    true
  );

  document.addEventListener(
    'mouseover',
    function (e) {
      var ice = resolveIce(e.target);
      if (ice) lastHover = ice;
    },
    true
  );

  document.addEventListener(
    'pointerup',
    function (e) {
      var ice = resolveIce(e.target);
      if (ice && ice.fieldId) {
        var parts = fieldParts(ice.fieldId);
        e.stopImmediatePropagation();
        e.preventDefault();
        var payload = {
          modelId: ice.modelId,
          fieldId: parts,
          index: ice.index || '',
          coordinates: {
            x: e.clientX || 0,
            y: e.clientY || 0,
            modelId: ice.modelId,
            fieldId: parts,
            index: ice.index || ''
          }
        };
        window.parent.postMessage(
          { type: 'ICE_ZONE_SELECTED', payload: payload, meta: { craftercms: true, source: 'guest' } },
          '*'
        );
        return;
      }
      var comp = resolveComponent(e.target) || lastHoverComponent;
      if (!comp) return;
      e.stopImmediatePropagation();
      e.preventDefault();
      window.parent.postMessage(
        {
          type: 'AIASSISTANT_ICE_COMPONENT_SELECTED',
          payload: { modelId: comp.modelId, modelPath: comp.modelPath },
          meta: { craftercms: true, source: 'guest' }
        },
        '*'
      );
    },
    true
  );
})();`;

function notifyPrecedence(): void {
  for (const fn of precedenceListeners) {
    try {
      fn();
    } catch {
      /* ignore */
    }
  }
}

function notifyComponent(sel: DomIceComponentSelection | null): void {
  if (
    sel &&
    lastComponentSelection &&
    sel.modelId === lastComponentSelection.modelId &&
    sel.modelPath === lastComponentSelection.modelPath
  ) {
    return;
  }
  lastComponentSelection = sel;
  for (const fn of componentListeners) {
    try {
      fn(sel);
    } catch {
      /* ignore listener errors */
    }
  }
}

function notify(sel: DomIceSelection | null): void {
  if (
    sel &&
    lastSelection &&
    sel.modelId === lastSelection.modelId &&
    sel.fieldId === lastSelection.fieldId &&
    (sel.fieldIndex ?? '') === (lastSelection.fieldIndex ?? '') &&
    sel.modelPath === lastSelection.modelPath
  ) {
    return;
  }
  lastSelection = sel;
  for (const fn of listeners) {
    try {
      fn(sel);
    } catch {
      /* ignore listener errors */
    }
  }
}

function normalizeFieldId(raw: unknown): string {
  if (Array.isArray(raw)) {
    return raw.map((s) => String(s).trim()).filter(Boolean).join('.');
  }
  return String(raw ?? '').trim();
}

function selectionFromIceZoneComponentPayload(payload: Record<string, unknown>): DomIceComponentSelection | null {
  const coords = payload.coordinates as Record<string, unknown> | undefined;
  const modelId = String(payload.modelId ?? coords?.modelId ?? '').trim();
  if (!modelId) return null;
  const fieldId = normalizeFieldId(payload.fieldId ?? coords?.fieldId);
  if (fieldId) return null;
  return { modelId, modelPath: '' };
}

function selectionFromIceZonePayload(payload: Record<string, unknown>): DomIceSelection | null {
  const coords = payload.coordinates as Record<string, unknown> | undefined;
  const modelId = String(payload.modelId ?? coords?.modelId ?? '').trim();
  const fieldId = normalizeFieldId(payload.fieldId ?? coords?.fieldId);
  if (!modelId || !fieldId) return null;

  const out: DomIceSelection = { modelId, modelPath: '', fieldId };
  const idxRaw = payload.index ?? coords?.index;
  if (idxRaw != null && String(idxRaw).trim() !== '') {
    out.fieldIndex = String(idxRaw);
  }
  return out;
}

function selectionFromRequestEditPayload(payload: Record<string, unknown>): DomIceSelection | null {
  const modelId = String(payload.modelId ?? '').trim();
  const fieldId = normalizeFieldId(payload.fields ?? payload.fieldId);
  if (!modelId || !fieldId) return null;

  const out: DomIceSelection = { modelId, modelPath: '', fieldId };
  const idxRaw = payload.index;
  if (idxRaw != null && String(idxRaw).trim() !== '') {
    out.fieldIndex = String(idxRaw);
  }
  return out;
}

function selectionFromUpdateFieldPayload(payload: Record<string, unknown>): DomIceSelection | null {
  const modelId = String(payload.modelId ?? '').trim();
  const fieldId = String(payload.fieldId ?? '').trim();
  if (!modelId || !fieldId) return null;

  const out: DomIceSelection = { modelId, modelPath: '', fieldId };
  const idxRaw = payload.index;
  if (idxRaw != null && String(idxRaw).trim() !== '') {
    out.fieldIndex = String(idxRaw);
  }
  return out;
}

function getStudioStore(): {
  getState: () => {
    preview?: {
      guest?: PreviewGuestSlice;
    };
    contentTypes?: { byId?: Record<string, ContentType> };
  };
  subscribe: (listener: () => void) => () => void;
} | null {
  try {
    const w = window as Window & {
      craftercms?: {
        getStore?: () => {
          getState: () => {
            preview?: {
              guest?: PreviewGuestSlice;
            };
            contentTypes?: { byId?: Record<string, ContentType> };
          };
          subscribe: (listener: () => void) => () => void;
        };
      };
    };
    return w.craftercms?.getStore?.() ?? null;
  } catch {
    return null;
  }
}

function enrichModelPath(sel: DomIceSelection): DomIceSelection {
  if (sel.modelPath) return sel;
  try {
    const guest = getStudioStore()?.getState?.()?.preview?.guest;
    const path = guest?.models?.[sel.modelId]?.craftercms?.path?.trim();
    if (path) return { ...sel, modelPath: path };
  } catch {
    /* ignore */
  }
  return sel;
}

function publishComponentSelection(sel: DomIceComponentSelection | null): void {
  if (sel) {
    lastComponentEventAt = Date.now();
    lastSelection = null;
    lastHoverSelection = null;
    for (const fn of listeners) {
      try {
        fn(null);
      } catch {
        /* ignore */
      }
    }
    notifyPrecedence();
  }
  notifyComponent(sel);
}

function publishSelection(sel: DomIceSelection | null): void {
  if (sel?.fieldId) {
    lastFieldEventAt = Date.now();
    lastComponentSelection = null;
    for (const fn of componentListeners) {
      try {
        fn(null);
      } catch {
        /* ignore */
      }
    }
    notifyPrecedence();
  }
  notify(sel);
}

function readComponentFromElement(start: EventTarget | null): DomIceComponentSelection | null {
  if (!(start instanceof Element)) return null;
  if (start.closest('[data-craftercms-field-id]')) return null;

  const modelHost = start.closest('[data-craftercms-model-id]');
  if (!modelHost) return null;

  const modelId = modelHost.getAttribute('data-craftercms-model-id')?.trim() ?? '';
  const modelPath = modelHost.getAttribute('data-craftercms-model-path')?.trim() ?? '';
  if (!modelId) return null;

  return { modelId, modelPath };
}

function readIceFromElement(start: EventTarget | null): DomIceSelection | null {
  if (!(start instanceof Element)) return null;

  const fieldHost =
    start.closest('[data-craftercms-field-id]') ??
    (start.hasAttribute('data-craftercms-field-id') ? start : null);
  const modelHost = fieldHost?.closest('[data-craftercms-model-id]') ?? start.closest('[data-craftercms-model-id]');
  if (!modelHost) return null;

  const modelId = modelHost.getAttribute('data-craftercms-model-id')?.trim() ?? '';
  const modelPath = modelHost.getAttribute('data-craftercms-model-path')?.trim() ?? '';
  if (!modelId) return null;

  const fieldSource = fieldHost ?? modelHost;
  const fieldId = fieldSource.getAttribute('data-craftercms-field-id')?.trim() ?? '';
  if (!fieldId) return null;

  const rawIndex = fieldSource.getAttribute('data-craftercms-index')?.trim();
  const out: DomIceSelection = { modelId, modelPath, fieldId };
  if (rawIndex) out.fieldIndex = rawIndex;
  return out;
}

const attachedDocs = new WeakSet<Document>();
const attachedIframes = new WeakSet<HTMLIFrameElement>();

function injectIceClickReporter(doc: Document): void {
  if (doc.getElementById(ICE_REPORTER_SCRIPT_ID)) return;
  const script = doc.createElement('script');
  script.id = ICE_REPORTER_SCRIPT_ID;
  script.textContent = ICE_CLICK_REPORTER_SOURCE;
  (doc.head || doc.documentElement).appendChild(script);
}

let lastHoverComponentSelection: DomIceComponentSelection | null = null;

function attachPreviewDocument(doc: Document): void {
  if (attachedDocs.has(doc)) return;
  attachedDocs.add(doc);
  injectIceClickReporter(doc);

  doc.addEventListener(
    'mouseover',
    (e) => {
      const ice = readIceFromElement(e.target);
      if (ice) lastHoverSelection = ice;
      const comp = readComponentFromElement(e.target);
      if (comp) lastHoverComponentSelection = comp;
    },
    true
  );

  doc.addEventListener(
    'pointerup',
    (e) => {
      const ice = readIceFromElement(e.target);
      if (ice?.fieldId) {
        e.stopImmediatePropagation();
        e.preventDefault();
        publishSelection(enrichModelPath(ice));
        return;
      }
      let comp = readComponentFromElement(e.target);
      if (!comp && lastHoverComponentSelection) {
        comp = lastHoverComponentSelection;
      }
      if (comp) {
        e.stopImmediatePropagation();
        e.preventDefault();
        publishComponentSelection(enrichComponentPath(comp));
      }
    },
    true
  );
}

function enrichComponentPath(sel: DomIceComponentSelection): DomIceComponentSelection {
  if (sel.modelPath) return sel;
  try {
    const guest = getStudioStore()?.getState?.()?.preview?.guest as
      | {
          models?: Record<string, { craftercms?: { path?: string } }>;
          modelIdByPath?: Record<string, string>;
        }
      | undefined;
    const path = guest?.models?.[sel.modelId]?.craftercms?.path?.trim();
    if (path) return { ...sel, modelPath: path };
    const byPath = guest?.modelIdByPath;
    if (byPath) {
      for (const [repoPath, id] of Object.entries(byPath)) {
        if (String(id).trim() === sel.modelId) return { ...sel, modelPath: repoPath.trim() };
      }
    }
  } catch {
    /* ignore */
  }
  return sel;
}

function attachPreviewIframe(iframe: HTMLIFrameElement): void {
  if (attachedIframes.has(iframe)) return;
  attachedIframes.add(iframe);
  const onLoad = () => {
    try {
      const doc = iframe.contentDocument;
      if (doc?.body) attachPreviewDocument(doc);
    } catch {
      /* cross-origin */
    }
  };
  iframe.addEventListener('load', onLoad);
  onLoad();
}

function selectionFromGuestStoreSelected(sel: Record<string, unknown> | undefined): DomIceSelection | null {
  if (!sel) return null;
  const coords = sel.coordinates as Record<string, unknown> | undefined;
  const modelId = String(sel.modelId ?? coords?.modelId ?? '').trim();
  const fieldId = normalizeFieldId(sel.fieldId ?? coords?.fieldId);
  if (!modelId || !fieldId) return null;

  const out: DomIceSelection = { modelId, modelPath: '', fieldId };
  const idxRaw = sel.index ?? coords?.index;
  if (idxRaw != null && String(idxRaw).trim() !== '') {
    out.fieldIndex = String(idxRaw);
  }
  return out;
}

function getContentTypesByIdFromStore(): Record<string, ContentType> | undefined {
  try {
    return getStudioStore()?.getState?.()?.contentTypes?.byId as Record<string, ContentType> | undefined;
  } catch {
    return undefined;
  }
}

function getGuestFromStore(): PreviewGuestSlice | undefined {
  try {
    return getStudioStore()?.getState?.()?.preview?.guest as PreviewGuestSlice | undefined;
  } catch {
    return undefined;
  }
}

function editSelectionFromRecord(sel: Record<string, unknown> | undefined): PreviewGuestEditSelection | undefined {
  if (!sel) return undefined;
  const coords = sel.coordinates as PreviewGuestEditSelection['coordinates'];
  return {
    modelId: sel.modelId as string | undefined,
    fieldId: (sel.fieldId ?? coords?.fieldId) as PreviewGuestEditSelection['fieldId'],
    index: (sel.index ?? coords?.index) as PreviewGuestEditSelection['index'],
    coordinates: coords
  };
}

function publishFromGuestStoreSelection(sel: Record<string, unknown> | undefined): void {
  const guest = getGuestFromStore();
  if (!guest || !sel) return;

  const editSel = editSelectionFromRecord(sel);
  const contentTypesById = getContentTypesByIdFromStore();
  const componentModelId = resolveXbComponentModelIdFromSelection(editSel, guest, contentTypesById);
  if (componentModelId) {
    publishComponentSelection(enrichComponentPath({ modelId: componentModelId, modelPath: '' }));
    return;
  }

  const fieldSel = selectionFromGuestStoreSelected(sel);
  if (fieldSel) {
    publishSelection(enrichModelPath(fieldSel));
  }
}

function watchGuestSelectedInStore(): void {
  const store = getStudioStore();
  if (!store?.subscribe) {
    window.setTimeout(watchGuestSelectedInStore, 400);
    return;
  }

  let prevKey = '';
  store.subscribe(() => {
    const sel = store.getState()?.preview?.guest?.selected?.[0] as Record<string, unknown> | undefined;
    const guest = getGuestFromStore();
    const contentTypesById = getContentTypesByIdFromStore();
    const editSel = editSelectionFromRecord(sel);
    const componentModelId =
      guest && editSel ? resolveXbComponentModelIdFromSelection(editSel, guest, contentTypesById) : undefined;
    const fieldSel = componentModelId ? null : selectionFromGuestStoreSelected(sel);
    const key = componentModelId
      ? `component:${componentModelId}`
      : fieldSel
        ? `field:${fieldSel.modelId}|${fieldSel.fieldId}|${fieldSel.fieldIndex ?? ''}`
        : '';
    if (!key) {
      prevKey = '';
      return;
    }
    if (key === prevKey) return;
    prevKey = key;
    publishFromGuestStoreSelection(sel);
  });
}

function attachPreviewIframes(): void {
  if (typeof document === 'undefined') return;
  const primary = document.getElementById(PREVIEW_IFRAME_ID);
  if (primary instanceof HTMLIFrameElement) {
    attachPreviewIframe(primary);
    return;
  }
  document.querySelectorAll('iframe').forEach((node) => {
    if (node instanceof HTMLIFrameElement) attachPreviewIframe(node);
  });
}

function isStudioComponentIceMenuSelection(action: unknown): boolean {
  if (!action || typeof action !== 'object' || !('type' in action)) return false;
  const a = action as { type: string; payload?: Record<string, unknown> };
  if (a.type !== iceZoneSelected.type || !a.payload) return false;

  const guest = getGuestFromStore();
  if (!guest) return false;

  const editSel = editSelectionFromRecord(a.payload);
  if (editSel && resolveXbComponentModelIdFromSelection(editSel, guest, getContentTypesByIdFromStore())) {
    return true;
  }

  return Boolean(selectionFromIceZoneComponentPayload(a.payload));
}

function isStudioFieldIceMenuSelection(action: unknown): boolean {
  if (!action || typeof action !== 'object' || !('type' in action)) return false;
  const a = action as { type: string; payload?: Record<string, unknown> };
  if (a.type !== iceZoneSelected.type || !a.payload) return false;

  const guest = getGuestFromStore();
  if (!guest) return false;

  const editSel = editSelectionFromRecord(a.payload);
  return Boolean(editSel && isAssistantScopedFieldIceSelection(editSel, guest, getContentTypesByIdFromStore()));
}

function shouldSuppressStudioIceMenu(action: unknown): boolean {
  return isStudioComponentIceMenuSelection(action) || isStudioFieldIceMenuSelection(action);
}

function dismissStudioEditMenu(): void {
  try {
    const store = getStudioStore();
    if (!store?.dispatch) return;
    const selected = store.getState()?.preview?.guest?.selected;
    if (!selected?.length) return;
    store.dispatch(clearSelectForEdit());
    getHostToGuestBus().next(clearSelectedZones());
  } catch {
    /* ignore */
  }
}
function payloadHasIceFieldId(payload: Record<string, unknown> | undefined): boolean {
  if (!payload) return false;
  const iceProps = payload.iceProps as Record<string, unknown> | undefined;
  const raw = iceProps?.fieldId ?? payload.fieldId;
  if (raw == null || raw === '') return false;
  if (Array.isArray(raw)) return raw.map((s) => String(s).trim()).filter(Boolean).length > 0;
  return String(raw).trim().length > 0;
}

function handleHostToGuestAction(action: unknown): void {
  if (!action || typeof action !== 'object' || !('type' in action)) return;
  const a = action as { type: string; payload?: Record<string, unknown> };

  if (a.type === contentTreeFieldSelected.type && a.payload) {
    const payload = a.payload;
    const iceProps = payload.iceProps as Record<string, unknown> | undefined;
    const modelId = String(iceProps?.modelId ?? payload.modelId ?? payload.parentId ?? '').trim();
    if (!modelId || payloadHasIceFieldId(payload)) return;
    publishComponentSelection(enrichComponentPath({ modelId, modelPath: '' }));
  }
}

function handleGuestToHostAction(action: unknown): void {
  if (!action || typeof action !== 'object' || !('type' in action)) return;
  const a = action as { type: string; payload?: Record<string, unknown> };

  if (a.type === clearSelectedZones.type) {
    lastHoverSelection = null;
    lastHoverComponentSelection = null;
    lastFieldEventAt = 0;
    lastComponentEventAt = 0;
    publishSelection(null);
    publishComponentSelection(null);
    notifyPrecedence();
    return;
  }

  if (a.type === guestCheckIn.type) {
    lastHoverSelection = null;
    lastHoverComponentSelection = null;
    lastSelection = null;
    lastComponentSelection = null;
    window.setTimeout(attachPreviewIframes, 0);
    window.setTimeout(attachPreviewIframes, 400);
    return;
  }

  if (a.type === iceZoneSelected.type && a.payload) {
    const guest = getGuestFromStore();
    const editSel = editSelectionFromRecord(a.payload);
    const componentModelId =
      guest && editSel
        ? resolveXbComponentModelIdFromSelection(editSel, guest, getContentTypesByIdFromStore())
        : undefined;
    if (componentModelId) {
      publishComponentSelection(enrichComponentPath({ modelId: componentModelId, modelPath: '' }));
      return;
    }

    const raw = selectionFromIceZonePayload(a.payload);
    if (raw) {
      publishSelection(enrichModelPath(raw));
      return;
    }
    const comp = selectionFromIceZoneComponentPayload(a.payload);
    if (comp) {
      publishComponentSelection(enrichComponentPath(comp));
    }
    return;
  }

  if (a.type === requestEdit.type && a.payload) {
    const raw = selectionFromRequestEditPayload(a.payload);
    if (raw) publishSelection(enrichModelPath(raw));
    return;
  }

  if (a.type === updateFieldValueOperation.type && a.payload) {
    const raw = selectionFromUpdateFieldPayload(a.payload);
    if (raw) publishSelection(enrichModelPath(raw));
  }
}

/**
 * Bridges XB field clicks into the AI Assistant. Studio guest keeps FIELD_SELECTED local and does not post
 * {@code ICE_ZONE_SELECTED}; this injects a reporter into {@code #crafterCMSPreviewIframe} and taps guest→host bus.
 */
export function installXbIceSelectionBridge(): void {
  if (installed || typeof window === 'undefined') return;
  installed = true;

  window.addEventListener('message', (event) => {
    const previewIframe = document.getElementById(PREVIEW_IFRAME_ID);
    if (!(previewIframe instanceof HTMLIFrameElement) || event.source !== previewIframe.contentWindow) return;

    const data = event.data as { type?: string; payload?: Record<string, unknown>; meta?: Record<string, unknown> };
    if (
      !data ||
      typeof data !== 'object' ||
      data.type !== 'AIASSISTANT_ICE_COMPONENT_SELECTED' ||
      data.meta?.craftercms !== true ||
      data.meta?.source !== 'guest'
    ) {
      return;
    }
    const payload = data.payload as Record<string, unknown> | undefined;
    const modelId = String(payload?.modelId ?? '').trim();
    if (!modelId) return;
    const modelPath = String(payload?.modelPath ?? '').trim();
    publishComponentSelection(enrichComponentPath({ modelId, modelPath }));
  });

  watchGuestSelectedInStore();

  attachPreviewIframes();
  const mo = new MutationObserver(() => attachPreviewIframes());
  if (document.body) {
    mo.observe(document.body, { childList: true, subtree: true });
  }

  const bus = getGuestToHostBus();
  const rawNext = bus.next.bind(bus);
  bus.next = (action: unknown) => {
    handleGuestToHostAction(action);
    if (shouldSuppressStudioIceMenu(action)) {
      window.setTimeout(dismissStudioEditMenu, 0);
      return;
    }
    rawNext(action);
  };

  const hostToGuest = getHostToGuestBus();
  const rawHostNext = hostToGuest.next.bind(hostToGuest);
  hostToGuest.next = (action: unknown) => {
    handleHostToGuestAction(action);
    rawHostNext(action);
  };
  hostToGuest.subscribe?.((action: unknown) => handleHostToGuestAction(action));
}

export function subscribeXbDomIceSelection(listener: (sel: DomIceSelection | null) => void): () => void {
  listeners.add(listener);
  listener(lastSelection);
  return () => listeners.delete(listener);
}

export function getLastXbDomIceSelection(): DomIceSelection | null {
  return lastSelection;
}

export function subscribeXbDomComponentSelection(
  listener: (sel: DomIceComponentSelection | null) => void
): () => void {
  componentListeners.add(listener);
  listener(lastComponentSelection);
  return () => componentListeners.delete(listener);
}

export function getLastXbDomComponentSelection(): DomIceComponentSelection | null {
  return lastComponentSelection;
}

/** Which XB target the author clicked most recently — avoids stale field DOM overriding component scope. */
export function getXbSelectionPrecedence(): 'field' | 'component' | null {
  if (lastFieldEventAt <= 0 && lastComponentEventAt <= 0) return null;
  if (lastComponentEventAt > lastFieldEventAt) return 'component';
  if (lastFieldEventAt > lastComponentEventAt) return 'field';
  return null;
}

export function subscribeXbSelectionPrecedence(listener: () => void): () => void {
  precedenceListeners.add(listener);
  listener();
  return () => precedenceListeners.delete(listener);
}
