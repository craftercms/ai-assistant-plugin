import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.http.AiHttpProxy
import plugins.org.craftercms.aiassistant.http.AiAssistantCentralAgentsMerge
import plugins.org.craftercms.aiassistant.orchestration.AiOrchestration
import plugins.org.craftercms.aiassistant.prompt.ToolPromptsSiteContext
import plugins.org.craftercms.aiassistant.rag.ExpertSkillVectorRegistry
import plugins.org.craftercms.aiassistant.secrets.StudioAiAssistantSecretsContext
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

/**
 * Minimal proxy for assistant chat (non-streaming).
 *
 * Routes through {@link AiOrchestration}: Spring AI tools-loop chat, Claude, or site script LLM per configured {@code llm}.
 *
 * Body:
 * {
 *   "agentId": "...",
 *   "prompt": "...",
   *   "llm": "required on the wire (POST body or merged from agents.json when siteId is set); missing/blank/unknown → 400",
 *   "chatId": "optional",
 *   "contentPath": "optional Studio preview repo path",
 *   "contentTypeId": "optional",
 *   "contentTypeLabel": "optional Studio UI label for the open item’s type",
 *   "studioPreviewPageUrl": "optional — Studio XB address bar `…/studio/preview#/?page=…&site=…` when available",
 *   "authoringSurface": "optional — formEngine for content-type form assistant",
 *   "formEngineClientJsonApply": "optional boolean — only with formEngine; XB omits",
 *   "formEngineItemPath": "optional repo path of open form item — path-scoped write blocking when using client JSON apply",
 *   "enableTools": "optional — false omits OpenAI function tools; absent defaults true",
 *   "omitTools": "optional — true omits tools for this request only (focused copy/generation); overrides enableTools; same for XB/ICE, dialog, form-engine",
 *   "previewToken": "optional — Studio crafterPreview cookie value for GetPreviewHtml",
 *   "skills": "optional array of { name, url, description, enabled } — enabled per-agent markdown for QueryExpertGuidance",
 *   "llmModel": "optional — model id for the selected LLM",
 *   "imageModel": "optional — default image model for GenerateImage on the built-in images wire",
 *   "imageGenerator": "optional — GenerateImage backend (blank = default when key+imageModel exist; none; script:{id}); see llm-configuration.md"
 * }
 */

def log = LoggerFactory.getLogger('plugins.org.craftercms.aiassistant.chat')
def parsedBody = AiHttpProxy.parseJsonBody(request)
def body = parsedBody instanceof Map ? (Map) parsedBody : [:]
if (Boolean.TRUE.equals(body.get('__aiassistantInvalidJson'))) {
  response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
  return [ok: false, message: 'Invalid JSON request body', detail: body.get('__aiassistantInvalidJsonDetail')?.toString() ?: '']
}
def agentId = body.agentId != null ? body.agentId.toString().trim() : ''
def prompt = body.prompt?.toString()
def siteIdBody = body.siteId?.toString()?.trim()
StudioToolOperations pubOpsForPrompt = null
def siteForPub = siteIdBody ?: params?.siteId?.toString()?.trim()
if (siteForPub && !AuthoringPreviewContext.isFormEngineSurface(body?.authoringSurface)) {
  try {
    pubOpsForPrompt = new StudioToolOperations(request, applicationContext, params)
  } catch (Throwable ignoredPubOps) {
  }
}
def assembledPrompt = AuthoringPreviewContext.assembleOrchestrationPrompt(
  prompt,
  body?.authoringSurface,
  body?.formEngineClientJsonApply,
  siteIdBody ?: params?.siteId,
  body?.contentPath,
  body?.contentTypeId,
  body?.contentTypeLabel,
  body?.displayTemplate,
  request,
  body?.studioPreviewPageUrl,
  pubOpsForPrompt)
def promptForOrchestration = assembledPrompt.orchestrationPrompt
def promptStepDeltas = assembledPrompt.stepDeltas
def chatId = body.chatId?.toString()
def llmApiKey = (body?.apiKey ?: body?.llmApiKey ?: body?.openAiApiKey)?.toString()
def llmSecretKey = body?.llmSecretKey?.toString()?.trim() ?: null
if (siteIdBody) {
  try {
    request.setAttribute('aiassistant.siteId', siteIdBody)
  } catch (Throwable ignored) {}
}
def normContentPath = AuthoringPreviewContext.normalizeRepoPath(body?.contentPath?.toString())
if (normContentPath) {
  try {
    request.setAttribute('aiassistant.contentPath', normContentPath)
  } catch (Throwable ignoredCp) {}
}
def normFormItemPath = AuthoringPreviewContext.normalizeRepoPath(body?.formEngineItemPath?.toString())
if (normFormItemPath) {
  try {
    request.setAttribute('aiassistant.formEngineItemPath', normFormItemPath)
  } catch (Throwable ignoredFp) {}
}
def ctIdBody = body?.contentTypeId?.toString()?.trim()
if (ctIdBody) {
  try {
    request.setAttribute('aiassistant.contentTypeId', ctIdBody)
  } catch (Throwable ignoredCt) {}
}
def previewTokenBody = body?.previewToken?.toString()?.trim()
if (previewTokenBody) {
  try {
    request.setAttribute('aiassistant.previewToken', previewTokenBody)
  } catch (Throwable ignored) {}
}
Map projectToolCfg = [:]
try {
  def cfgOps = new StudioToolOperations(request, applicationContext, params)
  projectToolCfg = plugins.org.craftercms.aiassistant.config.StudioAiAssistantProjectConfig.load(cfgOps)
} catch (Throwable ignoredCfg) {}
def skillsNorm = ExpertSkillVectorRegistry.normalizeRequestExpertSkills(body?.skills, projectToolCfg)
try {
  request.setAttribute('aiassistant.expertSkills', skillsNorm)
} catch (Throwable ignored) {}
def siteForBearer = siteIdBody ?: params?.siteId?.toString()?.trim()
if (body instanceof Map && siteForBearer) {
  try {
    AiAssistantCentralAgentsMerge.mergeStreamAgentFieldsFromSiteAgentsFileIfMissing(
      applicationContext, (Map) body, siteForBearer, agentId)
  } catch (Throwable mergeEx) {
    log.debug('Central agents.json merge skipped: {}', mergeEx.message ?: mergeEx.toString())
  }
}
def llm = body.llm?.toString()
def llmNormalized
try {
  llmNormalized = AiOrchestration.normalizeLlmProvider(llm)
} catch (IllegalArgumentException iae) {
  response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
  return [ok: false, message: (iae.message ?: 'Invalid llm').toString()]
}
if (body instanceof Map && siteForBearer) {
  try {
    AiAssistantCentralAgentsMerge.applyOpenAiDefaultImageModelIfMissing((Map) body, llmNormalized)
  } catch (Throwable defIm) {
    log.debug('OpenAI default imageModel skipped: {}', defIm.message ?: defIm.toString())
  }
}
def llmModel = body.llmModel?.toString()
def imageModelRaw = body.imageModel?.toString()
def imageModel = null
if (imageModelRaw?.trim()) {
  imageModel = AiOrchestration.normalizeImagesApiModelId(imageModelRaw.trim())
  if (body instanceof Map) {
    try {
      body.put('imageModel', imageModel)
    } catch (Throwable ignoredIm) {
    }
  }
}

def imageGenerator = body?.imageGenerator?.toString()?.trim() ?: null

if (!prompt) {
  response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
  return [ok: false, message: 'Missing required fields: prompt']
}

try {
  String siteForPrompts = (siteIdBody ?: params?.siteId?.toString()?.trim() ?: '')
  ToolPromptsSiteContext.enter(applicationContext, siteForPrompts)
  StudioAiAssistantSecretsContext.bind(siteForPrompts, applicationContext)
  try {
    def formEngineClientForward = AuthoringPreviewContext.isFormEngineSurface(body?.authoringSurface) && AuthoringPreviewContext.isTruthy(body?.formEngineClientJsonApply)
    def formEngineItemPathRaw = body?.formEngineItemPath?.toString()
    def omitTools = AuthoringPreviewContext.isTruthy(body?.omitTools)
    def enableToolsRequested = AuthoringPreviewContext.parseEnableTools(body?.enableTools)
    def enableTools = omitTools ? false : enableToolsRequested
    def promptAssemblyTelemetry = AuthoringPreviewContext.buildPromptAssemblyTelemetry([
      clientWirePrompt     : prompt,
      orchestrationPrompt  : promptForOrchestration?.toString() ?: '',
      authoringSurface     : body?.authoringSurface,
      contentPath          : body?.contentPath,
      displayTemplate      : body?.displayTemplate,
      stepDeltas           : promptStepDeltas,
      enableToolsRequested : enableToolsRequested,
      enableToolsEffective : enableTools,
      trivialTurn          : false
    ])
    log.info(
      'CHAT endpoint hit: agentId={} llm={} clientWireLen={} orchestrationLen={} authorVisibleLen={} serverInjectedLen={} stepDeltas={}',
      agentId,
      llm,
      promptAssemblyTelemetry.clientWirePromptChars,
      promptAssemblyTelemetry.orchestrationPromptChars,
      promptAssemblyTelemetry.authorVisibleChars,
      promptAssemblyTelemetry.serverInjectedChars,
      promptAssemblyTelemetry.stepDeltas ?: [:]
    )
    def authoringIntentExpansion = AuthoringPreviewContext.parseAuthoringIntentExpansion(body?.authoringIntentExpansion)
    try {
      request.setAttribute('aiassistant.authoringIntentExpansion', Boolean.valueOf(authoringIntentExpansion))
    } catch (Throwable ignoredAie) {
    }
    def orchestration = new AiOrchestration(request, response, applicationContext, params, pluginConfig)
    return orchestration.chatProxy(agentId, promptForOrchestration, chatId, llm, llmModel, llmApiKey, imageModel, formEngineClientForward, formEngineItemPathRaw, enableTools, imageGenerator, llmSecretKey)
  } finally {
    ToolPromptsSiteContext.exit()
    StudioAiAssistantSecretsContext.clear()
  }
} catch (IllegalStateException ise) {
  response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
  return [ok: false, message: ise.message ?: 'Configuration error']
} catch (Throwable e) {
  response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
  return [ok: false, message: "Chat request failed: ${e.message ?: e.class.simpleName}"]
}
