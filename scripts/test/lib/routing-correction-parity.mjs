#!/usr/bin/env node
/**
 * Offline parity for presentation-layer vs anchored copy-modification routing guards.
 * Mirrors AuthoringPreviewContext patterns (keep in sync when Groovy changes).
 */
const PRESENTATION_LAYER = new RegExp(
  [
    String.raw`\b(?:css|stylesheet|stylesheets|scss|less|static-assets|freemarker|\.ftl\b|display[-\s]?template|layout\s+markup|render\s+markup|selectors?)\b`,
    String.raw`\b(?:visual|styling|look\s+and\s+feel|theme|branding)\b.{0,64}\b(?:css|stylesheet|styles?\b|page\b)`,
    String.raw`\bstyles?\b.{0,48}\b(?:on\s+)?(?:this|the)\s+page\b`,
  ].join('|'),
  'is',
);

const ANCHORED_PAGE_CONTENT_MODIFICATION = new RegExp(
  [
    String.raw`\b(?:redo|re-?do|rewrite|re-?write|update|change|refresh|revise|replace|switch|pivot)\b.{0,96}\b(?:this|the)\s+(?:page|homepage|home\s+page|content|copy|text)\b`,
    String.raw`\b(?:this|the)\s+(?:page|homepage|home\s+page|content|copy|text)\b.{0,96}\b(?:redo|re-?do|rewrite|re-?write|update|change|refresh|revise|replace|switch|pivot)\b`,
    String.raw`\bmake\s+(?:this|the)\s+page\s+about\b`,
    String.raw`\b(?:update|change|rewrite|redo)\s+(?:the\s+)?(?:page|homepage|home\s+page)(?:\s+content|\s+copy)?\b`,
    String.raw`\b(?:page|homepage|home\s+page)\s+(?:content|copy|text)\b.{0,48}\b(?:about|to|with|for)\b`,
    String.raw`\blets?\s+redo\b`,
  ].join('|'),
  'is',
);

function suggestsPresentationLayer(text) {
  return PRESENTATION_LAYER.test(text);
}

function suggestsAnchoredPageContentModification(text) {
  if (suggestsPresentationLayer(text)) return false;
  return ANCHORED_PAGE_CONTENT_MODIFICATION.test(text);
}

const cases = [
  {
    text: 'Update the styles/CSS on this page so that the color blue becomes red. DO NOT change the structure or names of styles. Just change colors.',
    presentation: true,
    copyModification: false,
  },
  {
    text: 'Update this page so the hero headline is about our new cardiology wing.',
    presentation: false,
    copyModification: true,
  },
  {
    text: 'Summarize this page for me.',
    presentation: false,
    copyModification: false,
  },
];

const errors = [];
for (const c of cases) {
  const pres = suggestsPresentationLayer(c.text);
  const copy = suggestsAnchoredPageContentModification(c.text);
  if (pres !== c.presentation) {
    errors.push(`presentation mismatch: ${JSON.stringify(c.text.slice(0, 60))}… got ${pres} expected ${c.presentation}`);
  }
  if (copy !== c.copyModification) {
    errors.push(`copy-modification mismatch: ${JSON.stringify(c.text.slice(0, 60))}… got ${copy} expected ${c.copyModification}`);
  }
}

if (errors.length) {
  console.error('routing-correction-parity FAILED:');
  for (const e of errors) console.error(`  - ${e}`);
  process.exit(1);
}

console.log(`routing-correction-parity OK (${cases.length} cases)`);
