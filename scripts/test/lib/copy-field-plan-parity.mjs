/**
 * JS mirror of FormDefinitionCopyFieldPlan purpose + writePolicy for offline parity tests.
 */

/** @param {string} sectionTitle @param {{ title?: string, description?: string, help?: string, type?: string, id?: string }} field */
export function buildFieldPurpose(sectionTitle, field) {
  const label = String(field.title || field.id || '').trim();
  const description = String(field.description || '').trim();
  const help = String(field.help || '').trim();
  const type = String(field.type || '').trim();
  const parts = [];
  if (String(sectionTitle || '').trim()) parts.push(`Section: ${sectionTitle.trim()}`);
  if (label) parts.push(`Label: ${label}`);
  if (description) parts.push(description);
  if (help) parts.push(help);
  if (!description && !help) parts.push(typeHintForFieldType(type));
  return parts.join(' — ').trim();
}

/** @param {string} fieldType */
function typeHintForFieldType(fieldType) {
  switch (String(fieldType || '').trim()) {
    case 'image-picker':
      return 'Image asset for this slot — set a repository path from GenerateImage or an existing upload.';
    case 'rte':
    case 'rich-text':
      return 'Rich HTML for this field.';
    case 'textarea':
      return 'Multi-line plain text for this field.';
    case 'input':
    case 'text':
      return 'Plain text for this field.';
    default:
      return 'Author-visible content for this field.';
  }
}

function purposeHaystack(purposeText, sectionTitle, fieldTitle) {
  return `${purposeText || ''} ${sectionTitle || ''} ${fieldTitle || ''}`
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .trim();
}

function isOriginalHeadlinePurpose(hay) {
  if (!hay) return false;
  if (hay.includes('nav ') || hay.includes('navigation') || hay.includes('menu ')) return false;
  if (
    hay.includes('not the article') ||
    hay.includes('not the page headline') ||
    hay.includes('not the headline') ||
    hay.includes('block label') ||
    hay.includes('section label') ||
    hay.includes('grid label')
  ) {
    return false;
  }
  if (hay.includes('supporting') || hay.includes('subhead') || hay.includes('deck')) return false;
  if (hay.includes('intro') && hay.includes('expand')) return false;
  if (hay.includes('page title') || hay.includes('main headline') || hay.includes('primary headline')) {
    return true;
  }
  if (hay.includes('browser title') || hay.includes('listing title')) return true;
  if (hay.includes('(h1)') || (hay.includes('primary ') && hay.includes('headline'))) return true;
  if (hay.includes('label:')) {
    if (hay.includes('nav ')) return false;
    if (hay.includes('short label') || hay.includes('section label') || hay.includes('grid label')) {
      return false;
    }
    if (hay.includes('headline') || hay.includes(' h1')) {
      if (!hay.includes('supporting')) return true;
    }
    if (hay.includes(' title') && !hay.includes('section title')) {
      if (hay.includes('section: page propert')) return true;
      if (!hay.includes('features') && !hay.includes('above the')) return true;
    }
  }
  return false;
}

function isSectionLabelPurpose(hay) {
  if (!hay) return false;
  return (
    hay.includes('section label') ||
    hay.includes('block label') ||
    hay.includes('grid label') ||
    hay.includes('not the article') ||
    hay.includes('not the page headline') ||
    hay.includes('not the headline') ||
    (hay.includes('short label') && hay.includes('above'))
  );
}

function isSupportingCopyPurpose(hay) {
  if (!hay) return false;
  if (
    hay.includes('supporting') ||
    hay.includes('subhead') ||
    hay.includes('deck') ||
    hay.includes('lead paragraph') ||
    hay.includes('summary')
  ) {
    return true;
  }
  if (hay.includes('label:') && (hay.includes('headline') || hay.includes(' h1'))) return false;
  return (
    hay.includes('intro') ||
    (hay.includes('description') && !hay.includes('meta description')) ||
    (hay.includes('expand') && hay.includes('headline'))
  );
}

/** @param {string} fieldType @param {string} purposeText @param {string} [sectionTitle] @param {string} [fieldTitle] */
export function inferWritePolicy(fieldType, purposeText, sectionTitle = '', fieldTitle = '') {
  const type = String(fieldType || '').trim().toLowerCase();
  const hay = purposeHaystack(purposeText, sectionTitle, fieldTitle);

  if (type === 'image-picker') return 'image-path';
  if (
    hay.includes('nav label') ||
    hay.includes('navigation label') ||
    (hay.includes('navigation') && hay.includes('label')) ||
    hay.includes('menu label')
  ) {
    return 'navigation';
  }
  if (hay.includes('seo') || hay.includes('meta description') || hay.includes('search engine')) {
    return 'seo-metadata';
  }
  if (isSupportingCopyPurpose(hay)) return 'supporting-copy';
  if (isOriginalHeadlinePurpose(hay)) return 'original-headline';
  if (isSectionLabelPurpose(hay)) return 'section-label';
  if (type === 'rte' || type === 'rich-text') return 'rich-copy';
  if (type === 'input' || type === 'text' || type === 'textarea') return 'short-copy';
  return 'author-copy';
}

const COPY_FIELD_TYPES = new Set(['input', 'text', 'textarea', 'rte', 'rich-text']);
const SKIP_FIELD_IDS = new Set([
  'file-name',
  'internal-name',
  'objectId',
  'objectGroupId',
  'folder-name',
]);

/**
 * Harvest copy fields from form-definition XML (fixture-sized forms).
 * @param {string} xml
 */
export function harvestCopyFieldsFromFormXml(xml) {
  /** @type {{ fieldId: string, fieldType: string, fieldTitle: string, purpose: string, writePolicy: string, sectionTitle: string }[]} */
  const out = [];
  const sectionRe = /<section>\s*<title>([^<]*)<\/title>\s*<fields>([\s\S]*?)<\/fields>\s*<\/section>/g;
  let sectionMatch;
  while ((sectionMatch = sectionRe.exec(xml)) !== null) {
    const sectionTitle = sectionMatch[1].trim();
    const fieldsBlock = sectionMatch[2];
    const fieldRe = /<field>([\s\S]*?)<\/field>/g;
    let fieldMatch;
    while ((fieldMatch = fieldRe.exec(fieldsBlock)) !== null) {
      const block = fieldMatch[1];
      const type = (block.match(/<type>([^<]+)<\/type>/) || [])[1]?.trim() || '';
      const fieldId = (block.match(/<id>([^<]+)<\/id>/) || [])[1]?.trim() || '';
      const fieldTitle = (block.match(/<title>([^<]*)<\/title>/) || [])[1]?.trim() || '';
      const description = (block.match(/<description>([^<]*)<\/description>/) || [])[1]?.trim() || '';
      const help = (block.match(/<help>([^<]*)<\/help>/) || [])[1]?.trim() || '';
      if (!fieldId || SKIP_FIELD_IDS.has(fieldId)) continue;
      if (type === 'node-selector') continue;
      const field = { type, id: fieldId, title: fieldTitle, description, help };
      const purpose = buildFieldPurpose(sectionTitle, field);
      const writePolicy = inferWritePolicy(type, purpose, sectionTitle, fieldTitle);
      if (type === 'image-picker') {
        out.push({ fieldId, fieldType: type, fieldTitle, purpose, writePolicy, sectionTitle });
        continue;
      }
      if (!COPY_FIELD_TYPES.has(type)) continue;
      out.push({ fieldId, fieldType: type, fieldTitle, purpose, writePolicy, sectionTitle });
    }
  }
  return out;
}
