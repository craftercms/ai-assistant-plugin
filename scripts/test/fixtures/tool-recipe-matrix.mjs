/**
 * Curated prompts for intent-recipe and built-in tool matrix scenarios.
 * When adding a recipe or core tool, extend the maps here — generate-tool-recipe-scenarios.mjs fails if any are missing.
 */

/** @typedef {{ summary?: string, prompt: string, request?: Record<string, unknown>, expect?: Record<string, unknown>, optional?: boolean, skipUnless?: string, freshChat?: boolean, group?: string }} MatrixCase */

/** @type {Record<string, MatrixCase>} */
export const RECIPE_CASES = {
  web_research: {
    summary: 'Web search recipe (WebSearch force tool).',
    prompt:
      'Search the web for the latest headlines about renewable energy today. Cite sources briefly.',
    expect: { recipeId: 'web_research', recipeIdSoft: true, toolsAny: ['WebSearch'] },
  },
  site_content_research: {
    summary: 'Site content research (ResearchSiteContent force tool).',
    prompt: 'Search our site repository for pages about the home page and list what you find.',
    expect: { recipeId: 'site_content_research', recipeIdSoft: true, toolsAny: ['ResearchSiteContent'] },
  },
  llm_research: {
    summary: 'General knowledge — tools loop disabled on recipe.',
    prompt: 'Explain the difference between REST and GraphQL APIs in plain language.',
    expect: { maxToolStarts: 0 },
  },
  open_page_inquiry: {
    summary: 'Read-only summary of anchored page.',
    prompt: 'Summarize this page — what is it about?',
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
  },
  generate_image: {
    summary: 'Generate image recipe.',
    prompt: 'Generate a simple 256x256 abstract test image for QA.',
    expect: { recipeId: 'generate_image', toolsAny: ['GenerateImage', 'GeneratePlaceholderImage'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_IMAGE',
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
  },
  publish_site: {
    summary: 'Publish entire site (destructive).',
    prompt: 'Publish the entire site to delivery now.',
    expect: { recipeId: 'publish_site' },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_PUBLISH',
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
  },
  new_content_item_from_chat_draft: {
    summary: 'Turn 2 — persist prior assistant draft (needs prior turn in same chat).',
    prompt: 'Save the prior assistant draft as a new blog post in the repository.',
    expect: { recipeId: 'new_content_item_from_chat_draft', toolsAny: ['WriteContent'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_WRITES',
  },
  new_content_item: {
    summary: 'Create brand-new repository item from scratch.',
    prompt:
      'Create a new generic page called matrix-harness-test with internal name Matrix Harness Test and title Matrix Harness Test.',
    expect: { recipeId: 'new_content_item' },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_WRITES',
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
      toolsAny: ['TranslateContentItem', 'TranslateContentBatch', 'ListContentDependencyScope'],
    },
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
};

/** @type {Record<string, MatrixCase>} */
export const TOOL_CASES = {
  ContentExists: {
    summary: 'ContentExists on home page path.',
    prompt: 'Use ContentExists only: does /site/website/index.xml exist? Reply with the boolean.',
    request: { enabledBuiltInTools: ['ContentExists'] },
    expect: { toolsAny: ['ContentExists'] },
  },
  GetContent: {
    summary: 'GetContent read title_t.',
    prompt: 'Use GetContent only on /site/website/index.xml and report title_t.',
    request: { enabledBuiltInTools: ['GetContent'] },
    expect: { toolsAny: ['GetContent'] },
  },
  ListContentDependencyScope: {
    summary: 'List translation/dependency scope.',
    prompt:
      'Use ListContentDependencyScope only for /site/website/index.xml and summarize dependency paths.',
    request: { enabledBuiltInTools: ['ListContentDependencyScope'] },
    expect: { toolsAny: ['ListContentDependencyScope'] },
  },
  ListStudioContentTypes: {
    summary: 'List content types.',
    prompt: 'Use ListStudioContentTypes only and list page content type ids.',
    request: { enabledBuiltInTools: ['ListStudioContentTypes'] },
    expect: { toolsAny: ['ListStudioContentTypes'] },
  },
  GetContentTypeFormDefinition: {
    summary: 'Form definition for /page/home.',
    prompt: 'Use GetContentTypeFormDefinition only for content type /page/home.',
    request: { enabledBuiltInTools: ['GetContentTypeFormDefinition'] },
    expect: { toolsAny: ['GetContentTypeFormDefinition'] },
  },
  GetContentVersionHistory: {
    summary: 'Version history for home page.',
    prompt: 'Use GetContentVersionHistory only for /site/website/index.xml.',
    request: { enabledBuiltInTools: ['GetContentVersionHistory'] },
    expect: { toolsAny: ['GetContentVersionHistory'] },
  },
  GetPreviewHtml: {
    summary: 'Preview HTML for home (needs preview token).',
    prompt: 'Use GetPreviewHtml only for the home page and summarize the HTML title.',
    request: {
      enabledBuiltInTools: ['GetPreviewHtml'],
      authoringSurface: 'preview',
      contentPath: '/site/website/index.xml',
    },
    expect: { toolsAny: ['GetPreviewHtml'] },
  },
  FetchHttpUrl: {
    summary: 'Fetch public URL.',
    prompt: 'Use FetchHttpUrl only to fetch https://example.com and report the page title.',
    request: { enabledBuiltInTools: ['FetchHttpUrl'] },
    expect: { toolsAny: ['FetchHttpUrl'] },
  },
  PostHttpUrl: {
    summary: 'POST to httpbin (optional).',
    prompt:
      'Use PostHttpUrl only to POST JSON {"test":true} to https://httpbin.org/post and report the echoed json field.',
    request: { enabledBuiltInTools: ['PostHttpUrl'] },
    expect: { toolsAny: ['PostHttpUrl'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_HTTP_POST',
  },
  WebSearch: {
    summary: 'Built-in web search.',
    prompt: 'Use WebSearch only: who founded CrafterCMS?',
    request: { enabledBuiltInTools: ['WebSearch'] },
    expect: { toolsAny: ['WebSearch'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_WEB_SEARCH',
  },
  SerpApiWebSearch: {
    summary: 'SerpAPI web search (optional keys).',
    prompt: 'Use SerpApiWebSearch only: latest CrafterCMS release news.',
    request: { enabledBuiltInTools: ['SerpApiWebSearch'] },
    expect: { toolsAny: ['SerpApiWebSearch'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_SERPAPI',
  },
  ConsultCrafterQ: {
    summary: 'CrafterQ integration (optional).',
    prompt: 'Use ConsultCrafterQ only: what is Experience Builder?',
    request: { enabledBuiltInTools: ['ConsultCrafterQ'] },
    expect: { toolsAny: ['ConsultCrafterQ'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_CRAFTERQ',
  },
  SlackPostMessage: {
    summary: 'Slack post (optional webhook).',
    prompt: 'Use SlackPostMessage only with a test message "matrix harness ping".',
    request: { enabledBuiltInTools: ['SlackPostMessage'] },
    expect: { toolsAny: ['SlackPostMessage'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_SLACK',
  },
  ResearchSiteContent: {
    summary: 'Research site content index.',
    prompt: 'Use ResearchSiteContent only to find pages mentioning home.',
    request: { enabledBuiltInTools: ['ResearchSiteContent'] },
    expect: { toolsAny: ['ResearchSiteContent'] },
  },
  QueryExpertGuidance: {
    summary: 'Expert guidance query (requires enabled agent skills + embeddings API).',
    prompt: 'Use QueryExpertGuidance only: best practices for CrafterCMS content modeling.',
    request: { enabledBuiltInTools: ['QueryExpertGuidance'] },
    expect: { toolsAny: ['QueryExpertGuidance'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_EXPERT_GUIDANCE',
  },
  GeneratePlaceholderImage: {
    summary: 'Placeholder image generation.',
    prompt: 'Use GeneratePlaceholderImage only: 64x64 gray placeholder.',
    request: { enabledBuiltInTools: ['GeneratePlaceholderImage'] },
    expect: { toolsAny: ['GeneratePlaceholderImage'] },
  },
  GenerateImage: {
    summary: 'LLM image generation.',
    prompt: 'Use GenerateImage only: simple blue circle on white, 256x256.',
    request: { enabledBuiltInTools: ['GenerateImage'] },
    expect: { toolsAny: ['GenerateImage'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_IMAGE',
  },
  GenerateTextNoTools: {
    summary: 'Inner one-shot completion tool.',
    prompt:
      'You must call the GenerateTextNoTools tool exactly once with userPrompt set to the literal string harness-ok. Do not answer in prose without calling the tool.',
    request: { enabledBuiltInTools: ['GenerateTextNoTools'] },
    expect: { toolsAny: ['GenerateTextNoTools'], recipeIdSoft: false },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_GENERATE_TEXT_NO_TOOLS',
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
  },
  TransformContentSubgraph: {
    summary: 'Transform content subgraph (off-wire unless ENABLED_ON_WIRE in plugin source).',
    prompt:
      'Use TransformContentSubgraph only on /site/website/index.xml to list field ids (no writes).',
    request: { enabledBuiltInTools: ['TransformContentSubgraph'] },
    expect: { toolsAny: ['TransformContentSubgraph'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_TRANSFORM_SUBGRAPH',
  },
  WriteContent: {
    summary: 'WriteContent (destructive).',
    prompt:
      'Use WriteContent only to create /site/components/matrix-harness/ping.xml as a minimal component stub if the type exists; otherwise explain why not.',
    request: { enabledBuiltInTools: ['WriteContent'] },
    expect: { toolsAny: ['WriteContent'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_WRITES',
  },
  ListPagesAndComponents: {
    summary: 'List pages and components.',
    prompt: 'Use ListPagesAndComponents only and list the first five page paths.',
    request: { enabledBuiltInTools: ['ListPagesAndComponents'] },
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
  },
  update_content_type: {
    summary: 'Update content type definition (destructive).',
    prompt: 'Use GetContentTypeFormDefinition and update_content_type only for /page/home — read-only summary.',
    request: { enabledBuiltInTools: ['GetContentTypeFormDefinition', 'update_content_type'] },
    expect: { toolsAny: ['GetContentTypeFormDefinition'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_WRITES',
  },
  analyze_template: {
    summary: 'Analyze FTL template.',
    prompt: 'Use analyze_template only on /templates/web/pages/home.ftl and summarize structure.',
    request: { enabledBuiltInTools: ['analyze_template'] },
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
  },
  GetCrafterizingPlaybook: {
    summary: 'Crafterizing playbook retrieval.',
    prompt: 'Use GetCrafterizingPlaybook only and summarize the first section.',
    request: { enabledBuiltInTools: ['GetCrafterizingPlaybook'] },
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
  },
  InvokeSiteUserTool: {
    summary: 'Site-defined user tool (site-specific).',
    prompt: 'Use InvokeSiteUserTool only if a site user tool exists; list available tools first.',
    request: { enabledBuiltInTools: ['InvokeSiteUserTool'] },
    expect: { toolsAny: ['InvokeSiteUserTool'] },
    optional: true,
    skipUnless: 'CHAT_MATRIX_ALLOW_SITE_USER_TOOLS',
  },
};
