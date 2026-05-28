/** Default Groovy when creating a site user tool (see {@code StudioAiUserSiteTools#invokeRegisteredTool}). */
export const AI_ASSISTANT_USER_TOOL_GROOVY_STUB = `// InvokeSiteUserTool — bindings: studio, args, toolId, siteId, log
[
  ok     : true,
  message: 'Replace this stub — return a Map (ok, message, data, etc.).'
]
`;

/** Default Groovy for script image generator {@code script:{id}} (see {@code StudioAiScriptImageGenLoader}). */
export const AI_ASSISTANT_IMAGEGEN_GROOVY_STUB = `{ Map input, Map context ->
  String p = (input?.prompt ?: '').toString()
  if (p.length() > 120) {
    p = p.substring(0, 120) + '…'
  }
  [
    error  : true,
    message: 'Stub image generator — implement (input, context) -> Map per GenerateImage contract. prompt=' + p
  ]
}
`;

/** Default Groovy for script LLM {@code script:id} (see {@code StudioAiScriptLlmLoader} and demo runtime). */
export const AI_ASSISTANT_LLM_RUNTIME_GROOVY_STUB = `import plugins.org.craftercms.aiassistant.studio.contrib.llm.wire.openaispec.OpenAiSpecSpringAiLlmRuntime
import plugins.org.craftercms.aiassistant.studio.spi.llm.StudioAiLlmKind
import plugins.org.craftercms.aiassistant.studio.spi.llm.StudioAiRuntimeBuildRequest

[
  supportsNativeStudioTools: true,
  normalizedKind          : StudioAiLlmKind.SCRIPT_LLM_PREFIX + llmId,
  buildSessionBundle      : { StudioAiRuntimeBuildRequest r ->
    StudioAiRuntimeBuildRequest sub = new StudioAiRuntimeBuildRequest()
    sub.orchestration = r.orchestration
    sub.toolResultConverter = r.toolResultConverter
    sub.studioOps = r.studioOps
    sub.studioServletRequest = r.studioServletRequest
    sub.agentId = r.agentId
    sub.chatId = r.chatId
    sub.llmNormalized = StudioAiLlmKind.OPENAI_NATIVE
    sub.llmModelParam = r.llmModelParam
    sub.llmApiKeyFromRequest = r.llmApiKeyFromRequest
    sub.toolProgressListener = r.toolProgressListener
    sub.imageModelParam = r.imageModelParam
    sub.imageGeneratorParam = r.imageGeneratorParam
    sub.fullSuppressRepoWrites = r.fullSuppressRepoWrites
    sub.protectedFormItemPath = r.protectedFormItemPath
    sub.enableTools = r.enableTools
    sub.agentEnabledBuiltInTools = r.agentEnabledBuiltInTools
    OpenAiSpecSpringAiLlmRuntime.INSTANCE.buildSessionBundle(sub)
  }
]
`;

export const AI_ASSISTANT_USER_TOOLS_REGISTRY_STUB = `{
  "tools": [
    {
      "id": "example_tool",
      "script": "ExampleTool.groovy",
      "description": "Example InvokeSiteUserTool registration",
      "matchHints": [],
      "dontMatchHints": []
    }
  ]
}
`;

/**
 * Starter {@code config/studio/scripts/aiassistant/config/tools.json} — built-in tool allow/deny and MCP
 * (see {@code StudioAiAssistantProjectConfig}).
 */
export const AI_ASSISTANT_TOOLS_JSON_STUB = `{
  "disabledBuiltInTools": [],
  "enabledBuiltInTools": [],
  "mcpEnabled": false,
  "mcpServers": [
    {
      "id": "example",
      "url": "https://your-mcp-host.example/mcp",
      "headers": {},
      "readTimeoutMs": 120000
    }
  ],
  "disabledMcpTools": []
}
`;

/** Studio module path for per-site project context (appended to every chat turn when non-empty). */
export const AI_ASSISTANT_PROJECT_CONTEXT_STUDIO_PATH = '/scripts/aiassistant/context/site-authoring.md';

/** Starter markdown for {@link AI_ASSISTANT_PROJECT_CONTEXT_STUDIO_PATH}. */
export const AI_ASSISTANT_PROJECT_CONTEXT_MARKDOWN_STUB = `# Project authoring context

Markdown here is appended to every AI Assistant chat turn for this site when non-empty. It is not the author's request — use it for stable site facts: content-type paths, folder conventions, naming rules, and workflows.

`;

/**
 * Suggested prompt authors can paste into an agent to analyze the site and draft project context markdown.
 * Shown on Project Tools → Context and Prompts with a one-click copy control.
 */
export const AI_ASSISTANT_PROJECT_CONTEXT_GENERATION_PROMPT = `Analyze the site content, structure, and form definitions, and recommend a context prompt I can use to improve AI agent results for this site.

- If it is a website, note that pages are stored under \`/site/website/{path}/{slug}/index.xml\`.
- Where specific content types are used to create pages or components within specific areas of the information architecture, call them out.
- If a generic page type exists, call it out. Also indicate that when a more specific type exists for a given use case, based on its name, the more specific type should be used instead.
- If the site is multilingual, explain how languages are organized.
- List key details about the information architecture, such as date structures in paths.
- If the site has \`/site/taxonomy/*\` items, such as \`/site/taxonomy/categories.xml\`, generate a markdown table of taxonomies:
  - One row per item, listing the file.
  - Include the content types that use each item.
- Create a graph of the information architecture that shows paths and purpose.

Respond with the insights for this site in the shape of a skill. Include specific details, not recommendations or a prompt. Be specific and cite examples. You do not need to list every content type in the system. The objective is to identify and call out non-obvious, non-trivial, project-specific structures and rules.`;

/** Starter markdown when creating a site override for {@code config/studio/scripts/aiassistant/prompts/&lt;KEY&gt;.md}. */
export function aiAssistantToolPromptMarkdownStub(key: string): string {
  return `# ${key}

Non-empty markdown replaces the built-in prompt for this key. Leave the file blank or delete it to keep the plugin default (see ToolPromptsLoader).

`;
}
