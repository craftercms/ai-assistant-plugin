/**
 * JS mirror of FormDefinitionCopyFieldPlan.inferCopyRole / guidanceForRole for offline parity tests.
 */

/** @param {string} fieldId @param {string} fieldType @param {string} fieldTitle */
export function inferCopyRole(fieldId, fieldType, fieldTitle) {
  const id = String(fieldId || '').toLowerCase();
  const title = String(fieldTitle || '').toLowerCase();

  if (fieldType === 'image-picker' || fieldType === 'image-asset' || id.endsWith('_image_s')) {
    return 'image-asset';
  }
  if (id.includes('seo') && (id.endsWith('_t') || id.endsWith('_s'))) return 'seo-metadata';
  if (id === 'title_t' || (title === 'title' && !id.includes('hero') && !id.includes('feature'))) {
    return 'page-title';
  }
  if (id === 'navlabel' || id.includes('navlabel')) return 'navigation-label';
  if (id.endsWith('_title_html')) {
    return id.includes('hero') ? 'hero-headline' : 'section-headline-html';
  }
  if (id.endsWith('_text_html') || id.endsWith('_body_html') || id.endsWith('_description_html')) {
    if (id.includes('hero')) return 'hero-deck';
    if (id.includes('seo')) return 'seo-body';
    return 'body-copy';
  }
  if (id.endsWith('_title_t') || (title.includes('title') && !id.includes('internal'))) {
    return 'section-title';
  }
  if (fieldType === 'rte' || id.endsWith('_html')) return 'rich-text';
  if (id.endsWith('_t')) return 'short-text';
  return 'copy-field';
}

/** @param {string} role */
export function guidanceForRole(role) {
  switch (String(role || '').trim()) {
    case 'page-title':
      return 'Plain-text page title. Use the core headline without editorial prefixes ("Breaking news:", "Latest:", etc.).';
    case 'hero-headline':
      return 'Primary hero headline (rich HTML, often an `<h1>`). The main news line only — no "Breaking news" prefix.';
    case 'hero-deck':
      return 'Hero supporting copy (rich HTML). One or two sentences expanding on the headline with context — do **not** repeat the headline verbatim.';
    case 'image-asset':
      return 'Image path (`*_image_s`). Use **GenerateImage** repository path or existing asset — not inline chat URLs.';
    default:
      return 'Author-visible copy. Match the field label and section purpose; do not duplicate the same string used in other fields.';
  }
}

const COPY_FIELD_TYPES = new Set(['input', 'text', 'textarea', 'rte', 'rich-text']);
const SKIP_FIELD_IDS = new Set([
  'file-name', 'internal-name', 'objectId', 'objectGroupId', 'folder-name',
]);

/**
 * Minimal form-definition field harvest (fixture-sized forms only).
 * @param {string} xml
 */
export function harvestCopyFieldsFromFormXml(xml) {
  /** @type {{ fieldId: string, fieldType: string, fieldTitle: string, copyRole: string }[]} */
  const out = [];
  const fieldRe = /<field>\s*<type>([^<]+)<\/type>\s*<id>([^<]+)<\/id>\s*<title>([^<]*)<\/title>/g;
  let m;
  while ((m = fieldRe.exec(xml)) !== null) {
    const fieldType = m[1].trim();
    const fieldId = m[2].trim();
    const fieldTitle = m[3].trim();
    if (SKIP_FIELD_IDS.has(fieldId)) continue;
    if (fieldType === 'node-selector') continue;
    if (fieldType === 'image-picker' || fieldId.endsWith('_image_s')) {
      out.push({
        fieldId,
        fieldType,
        fieldTitle,
        copyRole: inferCopyRole(fieldId, 'image-asset', fieldTitle),
      });
      continue;
    }
    if (!COPY_FIELD_TYPES.has(fieldType) && !fieldId.endsWith('_t') && !fieldId.endsWith('_html')) {
      continue;
    }
    const copyRole = inferCopyRole(fieldId, fieldType, fieldTitle);
    out.push({ fieldId, fieldType, fieldTitle, copyRole });
  }
  return out;
}
