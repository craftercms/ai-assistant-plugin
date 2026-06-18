/**
 * Curated prompts for intent-recipe and built-in tool matrix scenarios.
 * When adding a recipe or core tool, extend the maps here — generate-tool-recipe-scenarios.mjs fails if any are missing.
 */

/** @typedef {{ summary?: string, prompt: string, request?: Record<string, unknown>, expect?: Record<string, unknown>, optional?: boolean, skipUnless?: string, partialOnMissingConfig?: boolean, freshChat?: boolean, group?: string }} MatrixCase */

/** Forces the model to invoke a single whitelisted tool (matrix harness). */
function mustCallTool(toolName, callDetail) {
  return (
    `[Matrix harness — required tool call] You MUST invoke the ${toolName} tool exactly once before writing any assistant reply. ` +
    `Do not use any other tool. Do not answer from memory or guess. ${callDetail} ` +
    `After the tool finishes, reply in one short sentence that cites one concrete value from the tool result.`
  );
}

/** @type {Record<string, MatrixCase>} */
export const RECIPE_CASES = {
  web_research: {
    summary: 'Web search recipe (WebSearch force tool).',
    prompt:
      'Search the web for the latest headlines about renewable energy today. Cite sources briefly.',
    expect: { recipeId: 'web_research', recipeIdSoft: true, toolsAny: ['WebSearch'] },
    partialOnMissingConfig: true,
  },
  site_content_research: {
    summary: 'Site content research (ResearchSiteContent force tool).',
    prompt: 'Search our site repository for pages about the home page and list what you find.',
    expect: { recipeId: 'site_content_research', recipeIdSoft: true, toolsAny: ['ResearchSiteContent'] },
    partialOnMissingConfig: true,
  },
  llm_research: {
    summary: 'General knowledge — tools loop disabled on recipe.',
    prompt: 'Explain the difference between REST and GraphQL APIs in plain language.',
    expect: { maxToolStarts: 0 },
  },
  open_page_inquiry: {
    summary: 'Read-only summary of anchored page.',
    prompt:
      'Summarize this anchored home page. You MUST call GetContent on /site/website/index.xml before answering; do not guess from memory.',
    request: {
      authoringSurface: 'preview',
      contentPath: '/site/website/index.xml',
      contentTypeId: '/page/home',
      contentTypeLabel: 'Home',
    },
    expect: { recipeId: 'open_page_inquiry', toolsAny: ['GetContent'] },
  },
  modify_page_content: {
    summary: 'Modify anchored page content (no write in harness unless CHAT_MATRIX_ALLOW_WRITES).',
    prompt:
      'Update the page title (title_t) on this page to exactly: Matrix harness modify title. Use update tools if available.',
    request: {
      authoringSurface: 'preview',
      contentPath: '/site/website/index.xml',
      contentTypeId: '/page/home',
      contentTypeLabel: 'Home',
    },
    expect: { recipeId: 'modify_page_content' },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_WRITES',
    partialOnMissingConfig: true,
  },
  revert_content_version: {
    summary: 'Revert anchored item to prior version.',
    prompt: 'Revert this page to the previous repository version.',
    request: {
      authoringSurface: 'preview',
      contentPath: '/site/website/index.xml',
    },
    expect: { recipeId: 'revert_content_version' },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_WRITES',
    partialOnMissingConfig: true,
  },
  generate_image: {
    summary: 'Generate image recipe.',
    prompt: 'Generate a simple 256x256 abstract test image for QA.',
    expect: {
      recipeId: 'generate_image',
      recipeIdSoft: true,
      toolsAny: ['GenerateImage', 'GeneratePlaceholderImage'],
      maxToolStartCounts: { GenerateImage: 1 },
      generateImagePromptSeen: true,
    },
    optional: true,
    partialOnMissingConfig: true,
  },
  template_display_change: {
    summary: 'Template / display change on anchored page.',
    prompt:
      'Change how this home page displays in the template — suggest a layout tweak using analyze/update template tools.',
    request: {
      authoringSurface: 'preview',
      contentPath: '/site/website/index.xml',
      displayTemplate: '/templates/web/pages/home.ftl',
    },
    expect: { recipeId: 'template_display_change' },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_WRITES',
    partialOnMissingConfig: true,
  },
  publish_site: {
    summary: 'Publish entire site (destructive).',
    prompt: 'Publish the entire site to delivery now.',
    expect: { recipeId: 'publish_site' },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_PUBLISH',
    partialOnMissingConfig: true,
  },
  publish_item: {
    summary: 'Publish single anchored item.',
    prompt: 'Publish this page to delivery.',
    request: {
      authoringSurface: 'preview',
      contentPath: '/site/website/index.xml',
    },
    expect: { recipeId: 'publish_item' },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_PUBLISH',
    partialOnMissingConfig: true,
  },
  new_content_item_from_chat_draft: {
    summary: 'Turn 2 — persist prior assistant draft (needs prior turn in same chat).',
    prompt: 'Save the prior assistant draft as a new blog post in the repository.',
    expect: { recipeId: 'new_content_item_from_chat_draft', toolsAny: ['WriteContent'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_WRITES',
    partialOnMissingConfig: true,
  },
  new_content_item: {
    summary: 'Create brand-new repository item from scratch.',
    prompt:
      'Create a new generic page called matrix-harness-test with internal name Matrix Harness Test and title Matrix Harness Test.',
    expect: { recipeId: 'new_content_item' },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_WRITES',
    partialOnMissingConfig: true,
  },
  translate_content_item: {
    summary: 'Translate anchored page to French.',
    prompt: 'Translate this page content to French using translation tools.',
    request: {
      authoringSurface: 'preview',
      contentPath: '/site/website/index.xml',
      contentTypeId: '/page/home',
    },
    expect: {
      recipeId: 'translate_content_item',
      recipeIdSoft: true,
      toolsAny: ['TranslateContentItem', 'TranslateContentBatch', 'ListContentDependencyScope'],
    },
    optional: true,
    partialOnMissingConfig: true,
  },
};

/** Prior turn for new_content_item_from_chat_draft (same chatId). */
export const RECIPE_DRAFT_PRIOR_TURN = {
  id: 'new_content_item_from_chat_draft-prior',
  summary: 'Prior turn: assistant prose draft (not saved).',
  prompt:
    'Draft a short blog post about hiking in markdown with a title and two paragraphs. Do not save anything to the repository.',
  group: 'intent-recipes',
  expect: { recipeId: 'llm_research', maxToolStarts: 0 },
  optional: true,
  skipUnless: 'CHAT_MATRIX_ALLOW_WRITES',
  partialOnMissingConfig: true,
};

/** @type {Record<string, MatrixCase>} */
export const TOOL_CASES = {
  ContentExists: {
    summary: 'ContentExists on home page path.',
    prompt: mustCallTool('ContentExists', 'Call with path "/site/website/index.xml".'),
    request: { enabledBuiltInTools: ['ContentExists'], enableTools: true },
    expect: { toolsAny: ['ContentExists'] },
  },
  GetContent: {
    summary: 'GetContent read title_t.',
    prompt: mustCallTool('GetContent', 'Call with path "/site/website/index.xml".'),
    request: { enabledBuiltInTools: ['GetContent'], enableTools: true },
    expect: { toolsAny: ['GetContent'] },
  },
  ListContentDependencyScope: {
    summary: 'List translation/dependency scope.',
    prompt: mustCallTool('ListContentDependencyScope', 'Call with contentPath "/site/website/index.xml".'),
    request: { enabledBuiltInTools: ['ListContentDependencyScope'], enableTools: true },
    expect: { toolsAny: ['ListContentDependencyScope'] },
  },
  ListStudioContentTypes: {
    summary: 'List content types.',
    prompt: mustCallTool('ListStudioContentTypes', 'Call with no filters; list ids only from the tool result.'),
    request: { enabledBuiltInTools: ['ListStudioContentTypes'], enableTools: true },
    expect: { toolsAny: ['ListStudioContentTypes'] },
  },
  GetContentTypeFormDefinition: {
    summary: 'Form definition for /page/home.',
    prompt: mustCallTool(
      'GetContentTypeFormDefinition',
      'Call with contentTypeId "/page/home" (not GetContent).',
    ),
    request: { enabledBuiltInTools: ['GetContentTypeFormDefinition'], enableTools: true },
    expect: { toolsAny: ['GetContentTypeFormDefinition'], forbidTools: ['GetContent'] },
  },
  GetContentVersionHistory: {
    summary: 'Version history for home page.',
    prompt: mustCallTool('GetContentVersionHistory', 'Call with path "/site/website/index.xml".'),
    request: { enabledBuiltInTools: ['GetContentVersionHistory'], enableTools: true },
    expect: { toolsAny: ['GetContentVersionHistory'] },
  },
  FindContentVersion: {
    summary: 'Find version by criteria (read-only).',
    prompt: mustCallTool(
      'FindContentVersion',
      'Call with path "/site/website/index.xml" and revertToPrevious true.',
    ),
    request: { enabledBuiltInTools: ['FindContentVersion'], enableTools: true },
    expect: { toolsAny: ['FindContentVersion'] },
  },
  CompareContentVersions: {
    summary: 'Compare HEAD vs prior version (read-only).',
    prompt: mustCallTool(
      'CompareContentVersions',
      'Call GetContentVersionHistory first, then CompareContentVersions with path "/site/website/index.xml" and compareCommitRef set to the second-newest versionNumber from history.',
    ),
    request: { enabledBuiltInTools: ['CompareContentVersions', 'GetContentVersionHistory'], enableTools: true },
    expect: { toolsAny: ['CompareContentVersions'] },
  },
  GetPreviewHtml: {
    summary: 'Preview HTML for home (needs preview token).',
    prompt: mustCallTool(
      'GetPreviewHtml',
      'Call with the anchored home preview URL for /site/website/index.xml (use studioPreviewPageUrl or url from context). Do not use GetContent.',
    ),
    request: {
      enabledBuiltInTools: ['GetPreviewHtml'],
      enableTools: true,
      authoringSurface: 'preview',
      contentPath: '/site/website/index.xml',
      contentTypeId: '/page/home',
      contentTypeLabel: 'Home',
    },
    expect: { toolsAny: ['GetPreviewHtml'], forbidTools: ['GetContent'] },
  },
  FetchHttpUrl: {
    summary: 'Fetch public URL.',
    prompt: mustCallTool('FetchHttpUrl', 'Call with url "https://example.com".'),
    request: { enabledBuiltInTools: ['FetchHttpUrl'], enableTools: true },
    expect: { toolsAny: ['FetchHttpUrl'] },
  },
  PostHttpUrl: {
    summary: 'POST to httpbin (optional).',
    prompt: mustCallTool(
      'PostHttpUrl',
      'Call with url "https://httpbin.org/post" and body JSON {"test":true}.',
    ),
    request: { enabledBuiltInTools: ['PostHttpUrl'], enableTools: true },
    expect: { toolsAny: ['PostHttpUrl'] },
    optional: true,
  },
  WebSearch: {
    summary: 'Built-in web search.',
    prompt: mustCallTool('WebSearch', 'Call with query "who founded CrafterCMS".'),
    request: { enabledBuiltInTools: ['WebSearch'], enableTools: true },
    expect: { toolsAny: ['WebSearch'] },
    optional: true,
  },
  SerpApiWebSearch: {
    summary: 'SerpAPI web search (optional keys).',
    prompt: mustCallTool('SerpApiWebSearch', 'Call with query "latest CrafterCMS release news".'),
    request: { enabledBuiltInTools: ['SerpApiWebSearch'], enableTools: true },
    expect: { toolsAny: ['SerpApiWebSearch'] },
    optional: true,
    partialOnMissingConfig: true,
  },
  ConsultCrafterQ: {
    summary: 'CrafterQ integration (optional).',
    prompt: mustCallTool('ConsultCrafterQ', 'Call with question "what is Experience Builder".'),
    request: { enabledBuiltInTools: ['ConsultCrafterQ'], enableTools: true },
    expect: { toolsAny: ['ConsultCrafterQ'] },
    optional: true,
    partialOnMissingConfig: true,
  },
  SlackPostMessage: {
    summary: 'Slack post (optional webhook).',
    prompt: mustCallTool('SlackPostMessage', 'Call with message text "matrix harness ping".'),
    request: { enabledBuiltInTools: ['SlackPostMessage'], enableTools: true },
    expect: { toolsAny: ['SlackPostMessage'] },
    optional: true,
    partialOnMissingConfig: true,
  },
  ResearchSiteContent: {
    summary: 'Research site content index.',
    prompt: mustCallTool('ResearchSiteContent', 'Call with query "home page".'),
    request: { enabledBuiltInTools: ['ResearchSiteContent'], enableTools: true },
    expect: { toolsAny: ['ResearchSiteContent'] },
  },
  QueryExpertGuidance: {
    summary: 'Expert guidance query (requires enabled agent skills + embeddings API).',
    prompt: mustCallTool(
      'QueryExpertGuidance',
      'Call with query "best practices for CrafterCMS content modeling".',
    ),
    request: { enabledBuiltInTools: ['QueryExpertGuidance'], enableTools: true },
    expect: { toolsAny: ['QueryExpertGuidance'] },
    optional: true,
    partialOnMissingConfig: true,
  },
  GeneratePlaceholderImage: {
    summary: 'Placeholder image generation.',
    prompt: mustCallTool('GeneratePlaceholderImage', 'Call with width 64 and height 64.'),
    request: { enabledBuiltInTools: ['GeneratePlaceholderImage'], enableTools: true },
    expect: { toolsAny: ['GeneratePlaceholderImage'] },
  },
  GenerateImage: {
    summary: 'LLM image generation.',
    prompt: mustCallTool(
      'GenerateImage',
      'Call with prompt "simple blue circle on white background" and size 256x256.',
    ),
    request: { enabledBuiltInTools: ['GenerateImage'], enableTools: true },
    expect: {
      toolsAny: ['GenerateImage'],
      maxToolStartCounts: { GenerateImage: 1 },
      generateImagePromptSeen: true,
    },
    optional: true,
    partialOnMissingConfig: true,
  },
  GenerateTextNoTools: {
    summary: 'Inner one-shot completion tool.',
    prompt: mustCallTool(
      'GenerateTextNoTools',
      'Call with userPrompt set to the literal string harness-ok.',
    ),
    request: { enabledBuiltInTools: ['GenerateTextNoTools'], enableTools: true },
    expect: { toolsAny: ['GenerateTextNoTools'], recipeIdSoft: false },
    optional: true,
  },
  TranslateContentItem: {
    summary: 'Translate single item.',
    prompt: 'Use TranslateContentItem only to translate /site/website/index.xml title to French.',
    request: {
      enabledBuiltInTools: ['TranslateContentItem'],
      authoringSurface: 'preview',
      contentPath: '/site/website/index.xml',
    },
    expect: { toolsAny: ['TranslateContentItem'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_WRITES',
    partialOnMissingConfig: true,
  },
  TranslateContentBatch: {
    summary: 'Batch translate scoped paths.',
    prompt:
      'Use ListContentDependencyScope then TranslateContentBatch only to translate /site/website/index.xml to Spanish.',
    request: {
      enabledBuiltInTools: ['ListContentDependencyScope', 'TranslateContentBatch'],
      authoringSurface: 'preview',
      contentPath: '/site/website/index.xml',
    },
    expect: { toolsAny: ['TranslateContentBatch'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_WRITES',
    partialOnMissingConfig: true,
  },
  TransformContentSubgraph: {
    summary: 'Transform content subgraph (off-wire unless ENABLED_ON_WIRE in plugin source).',
    prompt: mustCallTool(
      'TransformContentSubgraph',
      'Call on /site/website/index.xml to list field ids (read-only, no writes).',
    ),
    request: { enabledBuiltInTools: ['TransformContentSubgraph'], enableTools: true },
    expect: { toolsAny: ['TransformContentSubgraph'] },
    optional: true,
    partialOnMissingConfig: true,
  },
  WriteContent: {
    summary: 'WriteContent (destructive).',
    prompt:
      'Use WriteContent only to create /site/components/matrix-harness/ping.xml as a minimal component stub if the type exists; otherwise explain why not.',
    request: { enabledBuiltInTools: ['WriteContent'] },
    expect: { toolsAny: ['WriteContent'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_WRITES',
    partialOnMissingConfig: true,
  },
  ListPagesAndComponents: {
    summary: 'List pages and components.',
    prompt: mustCallTool('ListPagesAndComponents', 'Call with default args; report the first path from the result.'),
    request: { enabledBuiltInTools: ['ListPagesAndComponents'], enableTools: true },
    expect: { toolsAny: ['ListPagesAndComponents'] },
  },
  update_template: {
    summary: 'Update FTL template (destructive).',
    prompt:
      'Use analyze_template then update_template only on /templates/web/pages/home.ftl — describe one safe comment-only change, do not apply unless sure.',
    request: { enabledBuiltInTools: ['analyze_template', 'update_template'] },
    expect: { toolsAny: ['analyze_template'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_WRITES',
    partialOnMissingConfig: true,
  },
  update_content: {
    summary: 'Update repository XML (destructive).',
    prompt:
      'Use GetContent and update_content only on /site/website/index.xml — propose a title change but skip write unless confirmed.',
    request: {
      enabledBuiltInTools: ['GetContent', 'update_content'],
      contentPath: '/site/website/index.xml',
    },
    expect: { toolsAny: ['GetContent'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_WRITES',
    partialOnMissingConfig: true,
  },
  update_content_type: {
    summary: 'Update content type definition (destructive).',
    prompt: 'Use GetContentTypeFormDefinition and update_content_type only for /page/home — read-only summary.',
    request: { enabledBuiltInTools: ['GetContentTypeFormDefinition', 'update_content_type'] },
    expect: { toolsAny: ['GetContentTypeFormDefinition'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_WRITES',
    partialOnMissingConfig: true,
  },
  analyze_template: {
    summary: 'Analyze FTL template.',
    prompt: mustCallTool('analyze_template', 'Call with templatePath "/templates/web/pages/home.ftl".'),
    request: { enabledBuiltInTools: ['analyze_template'], enableTools: true },
    expect: { toolsAny: ['analyze_template'] },
  },
  publish_content: {
    summary: 'Publish content (destructive).',
    prompt: 'Use publish_content only with publishScope=item for /site/website/index.xml.',
    request: {
      enabledBuiltInTools: ['publish_content'],
      contentPath: '/site/website/index.xml',
    },
    expect: { toolsAny: ['publish_content'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_PUBLISH',
    partialOnMissingConfig: true,
  },
  GetCrafterizingPlaybook: {
    summary: 'Crafterizing playbook retrieval.',
    prompt: mustCallTool('GetCrafterizingPlaybook', 'Call with no args.'),
    request: { enabledBuiltInTools: ['GetCrafterizingPlaybook'], enableTools: true },
    expect: { toolsAny: ['GetCrafterizingPlaybook'] },
  },
  revert_change: {
    summary: 'Revert last change (destructive).',
    prompt: 'Use revert_change only for the last edit on /site/website/index.xml if any.',
    request: {
      enabledBuiltInTools: ['revert_change'],
      contentPath: '/site/website/index.xml',
    },
    expect: { toolsAny: ['revert_change'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_WRITES',
    partialOnMissingConfig: true,
  },
  InvokeSiteUserTool: {
    summary: 'Site-defined user tool (site-specific).',
    prompt: mustCallTool(
      'InvokeSiteUserTool',
      'Call only if a site user tool exists; otherwise report that none are configured.',
    ),
    request: { enabledBuiltInTools: ['InvokeSiteUserTool'], enableTools: true },
    expect: { toolsAny: ['InvokeSiteUserTool'] },
    optional: true,
    partialOnMissingConfig: true,
  },
};
