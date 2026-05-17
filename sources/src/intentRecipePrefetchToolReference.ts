/**
 * Prefetch {@code engineSteps} args — aligned with
 * {@code AuthoringIntentRecipeEngine#executeReadOnlyTool} and {@code #resolveArgValue}.
 */

export type PrefetchBindingToken = {
  token: string;
  description: string;
};

export const PREFETCH_BINDING_TOKENS: readonly PrefetchBindingToken[] = [
  { token: '$siteId', description: 'Studio project id for the current author session' },
  { token: '$contentPath', description: 'Open form/preview item path (e.g. /site/website/.../index.xml)' },
  { token: '$contentTypeId', description: 'Content type id when already known from Studio context' },
  { token: '$previewUrl', description: 'Engine preview URL when available' },
  {
    token: '$step0.fieldName',
    description: 'Field from a prior step result (0-based index), e.g. $step0.contentXml'
  },
  {
    token: '$initial.pageItem.contentXml',
    description: 'Named prefetch snapshot at turn start (step as: pageItem)'
  },
  {
    token: '$current.pageItem.contentXml',
    description: 'Same binding after WriteContent on the item path (falls back to initial)'
  },
  {
    token: '{{initial.pageItem}}',
    description: 'Expanded in phase hints when the recipe matches (server prelude)'
  },
  {
    token: '{{current.pageItem}}',
    description: 'Expanded after writes in the same turn when available'
  }
];

export type PrefetchToolArgField = {
  name: string;
  required?: boolean;
  description: string;
  example?: string;
};

export type PrefetchToolDoc = {
  summary: string;
  args: PrefetchToolArgField[];
  /** Suggested args object (binding tokens preserved as strings). */
  defaultArgs: Record<string, string | number | boolean>;
};

export const INTENT_RECIPE_PREFETCH_TOOL_DOCS: Record<string, PrefetchToolDoc> = {
  GetContent: {
    summary: 'Read repository XML for the anchored or specified path (read-only prefetch).',
    args: [
      { name: 'siteId', required: true, description: 'Project id', example: '$siteId' },
      {
        name: 'path',
        required: true,
        description: 'Repository path (alias: contentPath)',
        example: '$contentPath'
      },
      { name: 'contentPath', description: 'Same as path', example: '$contentPath' },
      { name: 'commitId', description: 'Optional git commit id / commitRef for historical read' }
    ],
    defaultArgs: { siteId: '$siteId', path: '$contentPath' }
  },
  GetContentTypeFormDefinition: {
    summary: 'Load form-definition XML for the item’s content type (via contentPath or contentTypeId).',
    args: [
      { name: 'siteId', required: true, description: 'Project id', example: '$siteId' },
      {
        name: 'contentPath',
        description: 'Item path; content type inferred from item XML (preferred in recipes)',
        example: '$contentPath'
      },
      { name: 'contentTypeId', description: 'Direct type id when path is not used', example: '$contentTypeId' }
    ],
    defaultArgs: { siteId: '$siteId', contentPath: '$contentPath' }
  },
  ListContentTranslationScope: {
    summary: 'Tree of translatable paths for batch translation (read-only scope discovery).',
    args: [
      { name: 'siteId', required: true, description: 'Project id', example: '$siteId' },
      { name: 'contentPath', required: true, description: 'Root page/item path', example: '$contentPath' },
      { name: 'path', description: 'Alias for contentPath' },
      { name: 'maxItems', description: 'Optional cap on items in tree' },
      { name: 'maxDepth', description: 'Optional depth limit' },
      { name: 'chunkSize', description: 'Optional chunk size for large trees' }
    ],
    defaultArgs: { siteId: '$siteId', contentPath: '$contentPath' }
  },
  ListContentDependencyScope: {
    summary: 'Dependency scope tree (same arg shape as ListContentTranslationScope).',
    args: [
      { name: 'siteId', required: true, description: 'Project id', example: '$siteId' },
      { name: 'contentPath', required: true, description: 'Root path', example: '$contentPath' },
      { name: 'maxItems', description: 'Optional cap' },
      { name: 'maxDepth', description: 'Optional depth limit' }
    ],
    defaultArgs: { siteId: '$siteId', contentPath: '$contentPath' }
  },
  ListStudioContentTypes: {
    summary: 'List content types in the project (e.g. before creating a new item).',
    args: [
      { name: 'siteId', required: true, description: 'Project id', example: '$siteId' },
      {
        name: 'searchable',
        description: 'Boolean — false lists all types; true limits to searchable types',
        example: 'false'
      },
      { name: 'contentPath', description: 'Optional path hint for filtering' }
    ],
    defaultArgs: { siteId: '$siteId', searchable: false }
  },
  GetContentVersionHistory: {
    summary: 'Git version history for a content path (read-only).',
    args: [
      { name: 'siteId', required: true, description: 'Project id', example: '$siteId' },
      { name: 'path', required: true, description: 'Repository path', example: '$contentPath' },
      { name: 'contentPath', description: 'Alias for path' }
    ],
    defaultArgs: { siteId: '$siteId', path: '$contentPath' }
  },
  GetPreviewHtml: {
    summary: 'Fetch rendered preview HTML from Engine (confirmation phase).',
    args: [
      {
        name: 'url',
        description: 'Preview URL (alias: previewUrl)',
        example: '$previewUrl'
      },
      { name: 'previewUrl', description: 'Same as url', example: '$previewUrl' },
      { name: 'siteId', description: 'Optional project id for preview auth', example: '$siteId' },
      { name: 'previewToken', description: 'Optional preview token when required by Engine' }
    ],
    defaultArgs: { url: '$previewUrl', siteId: '$siteId' }
  }
};

export function prefetchToolDoc(tool: string): PrefetchToolDoc | undefined {
  const t = tool?.trim();
  if (!t) return undefined;
  return INTENT_RECIPE_PREFETCH_TOOL_DOCS[t];
}

export function defaultPrefetchArgsJsonForTool(tool: string): string | null {
  const doc = prefetchToolDoc(tool);
  if (!doc) return null;
  return JSON.stringify(doc.defaultArgs, null, 2);
}
