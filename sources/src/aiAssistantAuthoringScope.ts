import type { ContentType, ContentTypeField } from '@craftercms/studio-ui/models/ContentType';
import type { ContentInstance } from '@craftercms/studio-ui/models/ContentInstance';
import { extractCollectionItem, value as modelFieldValue } from '@craftercms/studio-ui/utils/model';

/** Author-selected conversation scope for XB / preview chat (not form-engine). */
export type AuthoringScope = 'project' | 'page' | 'component' | 'field';

/**
 * Studio {@code state.preview.guest.selected[0]}.
 * XB posts {@code ICE_ZONE_SELECTED} which is stored as-is — field coordinates are often nested under {@code coordinates}.
 */
export interface PreviewGuestEditSelection {
  modelId?: string;
  fieldId?: string | string[];
  index?: string | number;
  coordinates?: {
    x?: number;
    y?: number;
    modelId?: string;
    parentModelId?: string;
    fieldId?: string | string[];
    index?: string | number;
  };
}

export interface PreviewGuestModelSlice {
  craftercms?: {
    path?: string;
    contentTypeId?: string;
    label?: string;
  };
}

export interface PreviewGuestSlice {
  path?: string;
  modelId?: string;
  models?: Record<string, PreviewGuestModelSlice>;
  modelIdByPath?: Record<string, string>;
  selected?: PreviewGuestEditSelection[] | null;
}

export interface XbFieldFocus {
  modelId: string;
  contentPath: string;
  contentTypeId: string;
  fieldId: string;
  fieldIndex?: string | number;
  fieldLabel: string;
}

/** Selected shared/embedded component item in Experience Builder (not a single field). */
export interface XbComponentFocus {
  modelId: string;
  contentPath: string;
  contentTypeId: string;
  label: string;
}

function normalizeFieldIdParts(raw: string | string[] | undefined): string[] {
  if (!raw) return [];
  if (Array.isArray(raw)) {
    return raw.map((s) => String(s).trim()).filter(Boolean);
  }
  const one = String(raw).trim();
  if (!one) return [];
  return one.includes('.') ? one.split('.').map((s) => s.trim()).filter(Boolean) : [one];
}

function contentTypeFields(fields: ContentType['fields']): ContentTypeField[] {
  if (!fields) return [];
  if (Array.isArray(fields)) return fields;
  return Object.values(fields);
}

function findFieldDef(fields: ContentType['fields'], id: string): ContentTypeField | undefined {
  if (!id) return undefined;
  return contentTypeFields(fields).find((f) => f.id === id);
}

function findFieldDefByPath(
  fields: ContentType['fields'],
  idParts: string[]
): ContentTypeField | undefined {
  let currentFields = fields;
  let def: ContentTypeField | undefined;
  for (const part of idParts) {
    def = findFieldDef(currentFields, part);
    if (!def) return undefined;
    currentFields = def.fields;
  }
  return def;
}

/** Resolves Studio form-definition display name for a field path (repeat groups supported). */
export function resolveContentTypeFieldLabel(
  contentType: ContentType | undefined,
  fieldIdParts: string[]
): string {
  if (!fieldIdParts.length) return '';
  let fields = contentType?.fields;
  let label = '';
  for (const part of fieldIdParts) {
    const def = findFieldDef(fields, part);
    if (!def) break;
    label = (def.name || def.id || part).trim();
    fields = def.fields;
  }
  return label || fieldIdParts.join('.');
}

interface NormalizedIceSelection {
  modelId: string;
  fieldIdParts: string[];
  fieldIndex?: string | number;
}

/**
 * Normalizes {@code guest.selected[0]} whether stored as flat {@code EditSelection} or {@code ICE_ZONE_SELECTED} payload.
 */
export function normalizeIceSelection(
  sel: PreviewGuestEditSelection | undefined
): NormalizedIceSelection | undefined {
  if (!sel) return undefined;

  const coords = sel.coordinates;
  const flatModelId = (sel.modelId ?? '').trim();
  if (flatModelId) {
    const fieldIdParts = normalizeFieldIdParts(sel.fieldId ?? coords?.fieldId);
    if (!fieldIdParts.length) return undefined;
    const out: NormalizedIceSelection = { modelId: flatModelId, fieldIdParts };
    const idx = sel.index ?? coords?.index;
    if (idx != null && String(idx).trim() !== '') {
      out.fieldIndex = idx;
    }
    return out;
  }

  if (!coords || typeof coords !== 'object') return undefined;

  const modelId = String(coords.modelId ?? '').trim();
  if (!modelId) return undefined;

  const fieldIdParts = normalizeFieldIdParts(coords.fieldId);
  if (!fieldIdParts.length) return undefined;

  const out: NormalizedIceSelection = { modelId, fieldIdParts };
  const idx = coords.index ?? sel.index;
  if (idx != null && String(idx).trim() !== '') {
    out.fieldIndex = idx;
  }
  return out;
}

function resolveModelContentPath(
  guest: PreviewGuestSlice,
  modelId: string,
  parentModelId?: string
): string {
  const models = guest.models ?? {};
  const model = models[modelId];
  const direct = model?.craftercms?.path?.trim();
  if (direct) return direct;

  const byPath = guest.modelIdByPath;
  if (byPath) {
    for (const [path, id] of Object.entries(byPath)) {
      if (String(id).trim() === modelId) return path.trim();
    }
  }

  const parentId = (parentModelId ?? '').trim();
  if (parentId) {
    const parentPath = models[parentId]?.craftercms?.path?.trim();
    if (parentPath) return parentPath;
  }

  const pagePath = (guest.path ?? '').trim();
  if (pagePath) return pagePath;

  const mainId = (guest.modelId ?? '').trim();
  if (mainId) {
    return models[mainId]?.craftercms?.path?.trim() ?? '';
  }
  return '';
}

function selectionKeyFromNormalized(norm: NormalizedIceSelection | undefined): string {
  if (!norm) return '';
  const idx = norm.fieldIndex != null && String(norm.fieldIndex).trim() !== '' ? String(norm.fieldIndex) : '';
  return `${norm.modelId}|${norm.fieldIdParts.join('.')}|${idx}`;
}

/**
 * Reads the highlighted XB field from a guest ICE/edit selection payload.
 */
export function resolveXbFieldFocusFromSelection(
  sel: PreviewGuestEditSelection | undefined,
  guest: PreviewGuestSlice,
  contentTypesById?: Record<string, ContentType>
): XbFieldFocus | undefined {
  if (!guest.models || !sel) return undefined;

  const norm = normalizeIceSelection(sel);
  if (!norm) return undefined;

  const parentModelId = sel.coordinates?.parentModelId;
  const contentPath = resolveModelContentPath(guest, norm.modelId, parentModelId);
  if (!contentPath) return undefined;

  const model = guest.models[norm.modelId];
  const fieldId = norm.fieldIdParts.join('.');
  const rawCt = model?.craftercms?.contentTypeId?.trim() || '';
  const contentTypeId = rawCt.startsWith('/') ? rawCt : rawCt ? `/${rawCt}` : '';
  const ct = contentTypeId ? contentTypesById?.[contentTypeId] : undefined;
  const fieldDef = findFieldDefByPath(ct?.fields, norm.fieldIdParts);
  if (fieldDef?.type === 'node-selector') {
    const idx = norm.fieldIndex;
    if (idx != null && String(idx).trim() !== '') {
      return undefined;
    }
  }
  const fieldLabel = resolveContentTypeFieldLabel(ct, norm.fieldIdParts) || fieldId;

  const focus: XbFieldFocus = {
    modelId: norm.modelId,
    contentPath,
    contentTypeId,
    fieldId,
    fieldLabel
  };
  if (norm.fieldIndex != null) {
    focus.fieldIndex = norm.fieldIndex;
  }
  return focus;
}

/** True when ICE selection maps to assistant Field scope (content-type field defs, not field ids). */
export function isAssistantScopedFieldIceSelection(
  sel: PreviewGuestEditSelection | undefined,
  guest: PreviewGuestSlice,
  contentTypesById?: Record<string, ContentType>
): boolean {
  return Boolean(resolveXbFieldFocusFromSelection(sel, guest, contentTypesById));
}

/**
 * Reads the highlighted XB field from {@code preview.guest} (when the author clicks a field in Experience Builder).
 */
export function resolveXbFieldFocus(
  guest: PreviewGuestSlice | null | undefined,
  contentTypesById: Record<string, ContentType> | undefined
): XbFieldFocus | undefined {
  if (!guest?.models) return undefined;
  return resolveXbFieldFocusFromSelection(guest.selected?.[0], guest, contentTypesById);
}

export function buildXbSelectionKey(guest: PreviewGuestSlice | null | undefined): string {
  return selectionKeyFromNormalized(normalizeIceSelection(guest?.selected?.[0]));
}

interface NormalizedComponentIceSelection {
  modelId: string;
  parentModelId?: string;
}

function resolveNodeSelectorChildModelId(
  hostModel: ContentInstance,
  fieldId: string,
  index: string | number,
  models: Record<string, PreviewGuestModelSlice>
): string | undefined {
  let childRef: unknown;
  try {
    if (fieldId.includes('.')) {
      childRef = extractCollectionItem(hostModel, fieldId, index);
    } else {
      const collection = modelFieldValue(hostModel, fieldId);
      if (Array.isArray(collection)) {
        childRef = collection[Number(index)];
      }
    }
  } catch {
    return undefined;
  }

  if (typeof childRef === 'string') {
    const id = childRef.trim();
    return id && models[id] ? id : undefined;
  }
  if (childRef && typeof childRef === 'object') {
    const id = (childRef as { craftercms?: { id?: string } }).craftercms?.id?.trim();
    if (id && models[id]) return id;
  }
  return undefined;
}

function hostModelIdFromSelection(sel: PreviewGuestEditSelection | undefined): string {
  if (!sel) return '';
  const coords = sel.coordinates;
  return String(sel.modelId ?? coords?.modelId ?? '').trim();
}

function fieldIdPartsFromSelection(sel: PreviewGuestEditSelection | undefined): string[] {
  if (!sel) return [];
  const coords = sel.coordinates;
  return normalizeFieldIdParts(sel.fieldId ?? coords?.fieldId);
}

function indexFromSelection(sel: PreviewGuestEditSelection | undefined): string | number | undefined {
  if (!sel) return undefined;
  const coords = sel.coordinates;
  const idx = sel.index ?? coords?.index;
  if (idx == null || String(idx).trim() === '') return undefined;
  return idx;
}

/**
 * Resolves the XB component model id from guest selection — including page section picks
 * (node-selector collection field + item index) that Studio surfaces as component chrome.
 */
export function resolveXbComponentModelIdFromSelection(
  sel: PreviewGuestEditSelection | undefined,
  guest: PreviewGuestSlice,
  contentTypesById?: Record<string, ContentType>
): string | undefined {
  if (!sel || !guest.models) return undefined;

  const hostModelId = hostModelIdFromSelection(sel);
  if (!hostModelId) return undefined;

  const fieldIdParts = fieldIdPartsFromSelection(sel);
  if (!fieldIdParts.length) {
    return hostModelId;
  }

  const index = indexFromSelection(sel);
  if (index == null) return undefined;

  const hostModel = guest.models[hostModelId] as ContentInstance | undefined;
  if (!hostModel) return undefined;

  const rawCt = hostModel?.craftercms?.contentTypeId?.trim() || '';
  const contentTypeId = rawCt.startsWith('/') ? rawCt : rawCt ? `/${rawCt}` : '';
  const ct = contentTypeId ? contentTypesById?.[contentTypeId] : undefined;
  const fieldDef = findFieldDefByPath(ct?.fields, fieldIdParts);
  if (fieldDef?.type !== 'node-selector') {
    return undefined;
  }

  const fieldId = fieldIdParts.join('.');
  return resolveNodeSelectorChildModelId(hostModel, fieldId, index, guest.models);
}

export function resolveXbComponentModelId(
  guest: PreviewGuestSlice | null | undefined,
  contentTypesById?: Record<string, ContentType>
): string | undefined {
  if (!guest?.models) return undefined;
  return resolveXbComponentModelIdFromSelection(guest.selected?.[0], guest, contentTypesById);
}

/**
 * Guest selection with a model but no field id (component zone / item chrome).
 */
export function normalizeComponentIceSelection(
  sel: PreviewGuestEditSelection | undefined
): NormalizedComponentIceSelection | undefined {
  if (!sel) return undefined;

  const coords = sel.coordinates;
  const flatModelId = (sel.modelId ?? '').trim();
  if (flatModelId) {
    const fieldIdParts = normalizeFieldIdParts(sel.fieldId ?? coords?.fieldId);
    if (fieldIdParts.length) return undefined;
    const out: NormalizedComponentIceSelection = { modelId: flatModelId };
    const parentModelId = String(coords?.parentModelId ?? '').trim();
    if (parentModelId) out.parentModelId = parentModelId;
    return out;
  }

  if (!coords || typeof coords !== 'object') return undefined;

  const modelId = String(coords.modelId ?? '').trim();
  if (!modelId) return undefined;

  const fieldIdParts = normalizeFieldIdParts(coords.fieldId);
  if (fieldIdParts.length) return undefined;

  const out: NormalizedComponentIceSelection = { modelId };
  const parentModelId = String(coords.parentModelId ?? '').trim();
  if (parentModelId) out.parentModelId = parentModelId;
  return out;
}

function isComponentRepoPath(path: string): boolean {
  return path.startsWith('/site/components/');
}

function resolveModelLabel(
  guest: PreviewGuestSlice | null | undefined,
  modelId: string,
  contentPath: string
): string {
  const fromModel = guest?.models?.[modelId]?.craftercms?.label?.trim();
  if (fromModel) return fromModel;
  const base = contentPath.split('/').filter(Boolean).pop() ?? '';
  const stem = base.replace(/\.xml$/i, '');
  return stem || 'Component';
}

/**
 * Reads the highlighted XB component from {@code preview.guest} when selection has model id only.
 */
export function resolveXbComponentFocus(
  guest: PreviewGuestSlice | null | undefined,
  contentTypesById?: Record<string, ContentType>
): XbComponentFocus | undefined {
  if (!guest?.models) return undefined;

  const componentModelId = resolveXbComponentModelId(guest, contentTypesById);
  if (!componentModelId) return undefined;

  const sel = guest.selected?.[0];
  const parentModelId = sel?.coordinates?.parentModelId;
  const contentPath = resolveModelContentPath(guest, componentModelId, parentModelId);
  if (!contentPath || !isComponentRepoPath(contentPath)) return undefined;

  const model = guest.models[componentModelId];
  const rawCt = model?.craftercms?.contentTypeId?.trim() || '';
  const contentTypeId = rawCt.startsWith('/') ? rawCt : rawCt ? `/${rawCt}` : '';

  return {
    modelId: componentModelId,
    contentPath,
    contentTypeId,
    label: resolveModelLabel(guest, componentModelId, contentPath)
  };
}

export function buildXbComponentSelectionKey(
  guest: PreviewGuestSlice | null | undefined,
  contentTypesById?: Record<string, ContentType>
): string {
  return resolveXbComponentModelId(guest, contentTypesById)?.trim() ?? '';
}

export interface DomIceComponentSelectionLike {
  modelId?: string;
  modelPath?: string;
}

export function buildDomIceComponentSelectionKey(
  dom: DomIceComponentSelectionLike | null | undefined
): string {
  const modelId = dom?.modelId?.trim() ?? '';
  const modelPath = dom?.modelPath?.trim() ?? '';
  if (!modelId) return '';
  if (modelPath && isComponentRepoPath(modelPath)) return `${modelId}|${modelPath}`;
  return modelId;
}

/**
 * Resolves component focus from preview iframe DOM when the author clicks component chrome (not a field).
 */
export function resolveXbComponentFocusFromDom(
  dom: DomIceComponentSelectionLike | null | undefined,
  guest: PreviewGuestSlice | null | undefined
): XbComponentFocus | undefined {
  const modelId = dom?.modelId?.trim();
  if (!modelId) return undefined;

  const contentPath =
    dom?.modelPath?.trim() ||
    guest?.models?.[modelId]?.craftercms?.path?.trim() ||
    resolveModelContentPath(guest ?? {}, modelId);
  if (!contentPath || !isComponentRepoPath(contentPath)) return undefined;

  const model = guest?.models?.[modelId];
  const rawCt = model?.craftercms?.contentTypeId?.trim() || '';
  const contentTypeId = rawCt.startsWith('/') ? rawCt : rawCt ? `/${rawCt}` : '';

  return {
    modelId,
    contentPath,
    contentTypeId,
    label: resolveModelLabel(guest, modelId, contentPath)
  };
}

/** When a field on a shared component is focused, the same item can be targeted as Component scope. */
export function xbComponentFocusFromField(
  field: XbFieldFocus | undefined,
  guest?: PreviewGuestSlice | null
): XbComponentFocus | undefined {
  if (!field?.contentPath || !isComponentRepoPath(field.contentPath)) return undefined;
  return {
    modelId: field.modelId,
    contentPath: field.contentPath,
    contentTypeId: field.contentTypeId,
    label: resolveModelLabel(guest ?? null, field.modelId, field.contentPath)
  };
}

export interface DomIceSelectionLike {
  modelId?: string;
  modelPath?: string;
  fieldId?: string;
  fieldIndex?: string;
}

export function buildDomIceSelectionKey(dom: DomIceSelectionLike | null | undefined): string {
  if (!dom?.fieldId?.trim() || !dom.modelId?.trim()) return '';
  const idx = dom.fieldIndex != null && String(dom.fieldIndex).trim() !== '' ? String(dom.fieldIndex) : '';
  return `${dom.modelId.trim()}|${dom.fieldId.trim()}|${idx}`;
}

/**
 * Resolves field focus from preview iframe DOM / injected ICE reporter when {@code guest.selected} is empty.
 */
export function resolveXbFieldFocusFromDom(
  dom: DomIceSelectionLike | null | undefined,
  guest: PreviewGuestSlice | null | undefined,
  contentTypesById: Record<string, ContentType> | undefined
): XbFieldFocus | undefined {
  const fieldIdRaw = dom?.fieldId?.trim();
  const modelId = dom?.modelId?.trim();
  if (!fieldIdRaw || !modelId) return undefined;

  const fieldIdParts = normalizeFieldIdParts(fieldIdRaw);
  if (!fieldIdParts.length) return undefined;

  const contentPath =
    dom?.modelPath?.trim() ||
    guest?.models?.[modelId]?.craftercms?.path?.trim() ||
    resolveModelContentPath(guest ?? {}, modelId);
  if (!contentPath) return undefined;

  const model = guest?.models?.[modelId];
  const fieldId = fieldIdParts.join('.');
  const rawCt = model?.craftercms?.contentTypeId?.trim() || '';
  const contentTypeId = rawCt.startsWith('/') ? rawCt : rawCt ? `/${rawCt}` : '';
  const ct = contentTypeId ? contentTypesById?.[contentTypeId] : undefined;
  const fieldLabel = resolveContentTypeFieldLabel(ct, fieldIdParts) || fieldId;

  const focus: XbFieldFocus = {
    modelId,
    contentPath,
    contentTypeId,
    fieldId,
    fieldLabel
  };
  if (dom?.fieldIndex != null && String(dom.fieldIndex).trim() !== '') {
    focus.fieldIndex = dom.fieldIndex;
  }
  return focus;
}

/**
 * Label for the Field scope control — form-definition field title, plus component item name when the
 * field lives on a shared component (disambiguates multiple image fields on a page).
 */
export function formatXbFieldScopeButtonLabel(
  focus: XbFieldFocus,
  guest?: PreviewGuestSlice | null,
  contentTypesById?: Record<string, ContentType>
): string {
  const parts = focus.fieldId.split('.').filter(Boolean);
  let fieldPart = focus.fieldLabel?.trim() || '';
  if (contentTypesById && focus.contentTypeId) {
    const fromCt = resolveContentTypeFieldLabel(contentTypesById[focus.contentTypeId], parts);
    if (fromCt) fieldPart = fromCt;
  }
  if (!fieldPart) fieldPart = focus.fieldId;

  const path = focus.contentPath?.trim() ?? '';
  if (isComponentRepoPath(path)) {
    const componentName = resolveModelLabel(guest ?? null, focus.modelId, path);
    if (componentName) {
      return `${fieldPart} · ${componentName}`;
    }
  }
  return fieldPart;
}

export interface ScopedPreviewContext {
  scope: AuthoringScope;
  /** Page-level repository path (preview item / guest.path). */
  pageContentPath: string;
  pageContentTypeId: string;
  displayTemplate: string;
  fieldFocus?: XbFieldFocus;
  componentFocus?: XbComponentFocus;
}

/**
 * Maps UI scope + preview anchors to stream POST fields.
 * Project scope omits item paths; field scope uses the focused item path and field metadata.
 */
export function buildScopedPreviewStreamContext(args: {
  scope: AuthoringScope;
  pageContentPath: string;
  pageContentTypeId: string;
  displayTemplate: string;
  fieldFocus?: XbFieldFocus;
  componentFocus?: XbComponentFocus;
}): {
  contentPath?: string;
  contentTypeId?: string;
  displayTemplate?: string;
  authoringScope: AuthoringScope;
  pageContentPath?: string;
  xbFocusedContentPath?: string;
  xbFocusedFieldId?: string;
  xbFocusedFieldIndex?: string | number;
  xbFocusedFieldLabel?: string;
  xbFocusedComponentLabel?: string;
} {
  const pagePath = args.pageContentPath.trim();
  const pageType = args.pageContentTypeId.trim();
  const tpl = args.displayTemplate.trim();

  if (args.scope === 'project') {
    return { authoringScope: 'project' };
  }

  if (args.scope === 'field' && args.fieldFocus) {
    const f = args.fieldFocus;
    return {
      authoringScope: 'field',
      contentPath: f.contentPath,
      contentTypeId: f.contentTypeId || pageType || undefined,
      displayTemplate: tpl || undefined,
      xbFocusedContentPath: f.contentPath,
      xbFocusedFieldId: f.fieldId,
      ...(f.fieldIndex != null ? { xbFocusedFieldIndex: f.fieldIndex } : {}),
      xbFocusedFieldLabel: f.fieldLabel,
      ...(pagePath ? { pageContentPath: pagePath } : {})
    };
  }

  if (args.scope === 'component' && args.componentFocus) {
    const c = args.componentFocus;
    return {
      authoringScope: 'component',
      contentPath: c.contentPath,
      contentTypeId: c.contentTypeId || pageType || undefined,
      displayTemplate: tpl || undefined,
      xbFocusedContentPath: c.contentPath,
      xbFocusedComponentLabel: c.label,
      ...(pagePath ? { pageContentPath: pagePath } : {})
    };
  }

  return {
    authoringScope: 'page',
    ...(pagePath ? { contentPath: pagePath } : {}),
    ...(pageType ? { contentTypeId: pageType } : {}),
    ...(tpl ? { displayTemplate: tpl } : {})
  };
}
