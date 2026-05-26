import jakarta.servlet.http.HttpServletResponse
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet
import java.util.Set
import groovy.json.JsonOutput
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.http.AiHttpProxy
import plugins.org.craftercms.aiassistant.http.AiAssistantCentralAgentsMerge
import plugins.org.craftercms.aiassistant.orchestration.AiOrchestration
import plugins.org.craftercms.aiassistant.prompt.ToolPromptsSiteContext
import plugins.org.craftercms.aiassistant.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.rag.ExpertSkillVectorRegistry
import plugins.org.craftercms.aiassistant.secrets.StudioAiAssistantSecretsContext
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

/**
 * Single streaming chat endpoint: agentId + full prompt in, SSE stream out.
 * Always writes response body ourselves and returns null so Spring never tries
 * to serialize a return value (client sends Accept: text/event-stream).
 *
 * Contract:
 *   POST body: { ..., "contentPath", "contentTypeId", "contentTypeLabel" (optional), "displayTemplate" (optional metadata),
 *   "studioPreviewPageUrl" (optional) — `…/studio/preview#/?page=…&site=…` from the browser when available so the prompt matches the author’s address bar,
 *   "authoringSurface": "formEngine" | omit for XB/preview,
 *   "formEngineClientJsonApply": optional boolean — when true **and** formEngine, append client-JSON apply instructions (XB must omit)
 *   "formEngineItemPath": optional — repo path of the open form item; when set with client JSON apply, WriteContent/publish/revert are blocked **only** for this path (other paths may still persist). If omitted, all repo writes are suppressed for that mode (safe default).
 *   "enableTools": optional boolean — when false, the LLM chat request omits function tools (matches ui.xml enableTools false). Absent defaults true.
 *   "omitTools": optional boolean — when true, function tools are omitted for this request only (copy/image-style LLM steps); overrides enableTools. Same for XB/ICE preview chat, dialog, and form-engine (`authoringSurface`). Absent/false keeps normal tool registration from enableTools/agent defaults.
 *   "enabledBuiltInTools": optional JSON array of tool name strings — after site {@code tools.json} policy, only these built-in tools (exact wire names) remain registered; include {@code "mcp:*"} to keep all dynamic {@code mcp_*} tools. Absent or empty = no per-request subset (full catalog subject to site policy).
 *   "llmModel": optional string — chat model id for the configured vendor (e.g. gpt-4o-mini on OpenAI).
 *   "imageModel": optional string — Default image model for GenerateImage on the built-in images wire (e.g. gpt-image-1); from agent config or request when set. Ignored when **imageGenerator** selects a pure script backend unless the script reads it from context.
 *   "imageGenerator": optional string — **GenerateImage** backend: blank = built-in Images wire when key+imageModel exist; **none** / **off** / **disabled** omits the tool; **script:{id}** runs **`/scripts/aiassistant/imagegen/{id}/generate.groovy`**. Agent ui.xml **imageGenerator**; merged from site ui.xml like **imageModel** when POST omits it.
 *   "skills": optional JSON array of { name, url, description, enabled } — enabled per-agent markdown URLs for {@code QueryExpertGuidance}; normalized server-side.
 *   "translateBatchConcurrency": optional integer 1–64 — parallel {@code TranslateContentBatch} workers when the model omits {@code maxConcurrency}; from agent ui.xml; server default 25 when omitted.
 *   "previewToken": optional string — Studio {@code crafterPreview} cookie value; enables {@code GetPreviewHtml} without passing the token on every tool call. When omitted, the server still uses {@code crafterPreview} from the **incoming request cookies** (HttpOnly-safe).
 *   "crafterQChatUser": optional string — CrafterQ anonymous chat JWT ({@code X-CrafterQ-Chat-User}) for {@code ConsultCrafterQ}; otherwise use {@code builtInToolSettings.ConsultCrafterQ.chatUser} in site tools.json.
 *   Response:  text/event-stream (SSE) on success, or application/json on error
 */
def log = LoggerFactory.getLogger('plugins.org.craftercms.aiassistant.stream')
try {
  def body = AiHttpProxy.parseJsonBody(request)
  if (Boolean.TRUE.equals(body?.get('__aiassistantInvalidJson'))) {
    response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
    response.setContentType('application/json')
    response.getOutputStream().withWriter('UTF-8') {
      it.write(JsonOutput.toJson([
        ok     : false,
        message: 'Invalid JSON request body',
        detail : body?.get('__aiassistantInvalidJsonDetail')?.toString() ?: ''
      ]))
    }
    return null
  }

  def agentId = body?.agentId != null ? body.agentId.toString().trim() : ''
  def prompt = body?.prompt != null ? body.prompt.toString() : ''
  def contentPathBody = body?.contentPath
  def contentTypeIdBody = body?.contentTypeId
  def contentTypeLabelBody = body?.contentTypeLabel
  def authoringSurface = body?.authoringSurface
  def clientJsonApply = body?.formEngineClientJsonApply
  def siteIdBody = body?.siteId?.toString()?.trim()
  StudioToolOperations pubOpsForPrompt = null
  def siteForPub = siteIdBody ?: params?.siteId?.toString()?.trim()
  if (siteForPub && !AuthoringPreviewContext.isFormEngineSurface(authoringSurface)) {
    try {
      pubOpsForPrompt = new StudioToolOperations(request, applicationContext, params)
    } catch (Throwable ignoredPubOps) {
    }
  }
  def assembledPrompt = AuthoringPreviewContext.assembleOrchestrationPrompt(
    prompt,
    authoringSurface,
    clientJsonApply,
    siteIdBody ?: params?.siteId,
    contentPathBody,
    contentTypeIdBody,
    contentTypeLabelBody,
    body?.displayTemplate,
    request,
    body?.studioPreviewPageUrl,
    pubOpsForPrompt,
    applicationContext)
  def promptForOrchestration = assembledPrompt.orchestrationPrompt
  def promptStepDeltas = assembledPrompt.stepDeltas
  def chatId = body?.chatId?.toString()
  if (siteIdBody) {
    try {
      request.setAttribute('aiassistant.siteId', siteIdBody)
    } catch (Throwable ignored) {
      // non-mutable request in some contexts
    }
  }
  def normContentPath = AuthoringPreviewContext.normalizeRepoPath(contentPathBody?.toString())
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
  def ctIdBody = contentTypeIdBody?.toString()?.trim()
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
  def crafterQChatUserBody = (body?.crafterQChatUser ?: body?.crafterqChatUser)?.toString()?.trim()
  if (crafterQChatUserBody) {
    try {
      request.setAttribute('aiassistant.crafterQChatUser', crafterQChatUserBody)
    } catch (Throwable ignoredCq) {}
  }
  Map projectToolCfg = [:]
  try {
    def cfgOps = new StudioToolOperations(request, applicationContext, params)
    projectToolCfg = StudioAiAssistantProjectConfig.load(cfgOps)
  } catch (Throwable ignoredCfg) {}
  def skillsNorm = ExpertSkillVectorRegistry.normalizeRequestExpertSkills(body?.skills, projectToolCfg)
  try {
    request.setAttribute('aiassistant.expertSkills', skillsNorm)
  } catch (Throwable ignored) {}
  def agentToolsRaw = body?.enabledBuiltInTools
  if (agentToolsRaw instanceof List && !((List) agentToolsRaw).isEmpty()) {
    Set wl = new LinkedHashSet()
    for (Object o : (List) agentToolsRaw) {
      String n = o?.toString()?.trim()
      if (n) {
        wl.add(n)
      }
    }
    if (!wl.isEmpty()) {
      try {
        request.setAttribute('aiassistant.agentEnabledBuiltInTools', wl)
      } catch (Throwable ignoredWl) {}
    }
  }
  def siteForBearer = siteIdBody ?: params?.siteId?.toString()?.trim()
  if (body instanceof Map && siteForBearer) {
    try {
      AiAssistantCentralAgentsMerge.mergeStreamAgentFieldsFromSiteAgentsFileIfMissing(
        applicationContext, (Map) body, siteForBearer, agentId)
    } catch (Throwable mergeEx) {
      log.debug('Central agents.json merge skipped: {}', mergeEx.message ?: mergeEx.toString())
    }
  }
  def llm = body?.llm?.toString()
  def llmNormalized
  try {
    llmNormalized = AiOrchestration.normalizeLlmProvider(llm)
  } catch (IllegalArgumentException iae) {
    response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
    response.setContentType('application/json')
    response.getOutputStream().withWriter('UTF-8') {
      it.write(JsonOutput.toJson([ok: false, message: (iae.message ?: 'Invalid llm').toString()]))
    }
    return null
  }
  if (body instanceof Map && siteForBearer) {
    try {
      AiAssistantCentralAgentsMerge.applyOpenAiDefaultImageModelIfMissing((Map) body, llmNormalized)
    } catch (Throwable defIm) {
      log.debug('OpenAI default imageModel skipped: {}', defIm.message ?: defIm.toString())
    }
  }
  def llmApiKey = (body?.llmApiKey ?: body?.openAiApiKey ?: body?.apiKey)?.toString()
  def llmSecretKey = body?.llmSecretKey?.toString()?.trim() ?: null
  def openAiModel = body?.llmModel?.toString()
  def imageModelRaw = body?.imageModel?.toString()
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
  def tbcRaw = body?.translateBatchConcurrency
  if (tbcRaw != null) {
    try {
      int tbc =
        (tbcRaw instanceof Number)
          ? ((Number) tbcRaw).intValue()
          : Integer.parseInt(tbcRaw.toString().trim())
      tbc = Math.max(1, Math.min(64, tbc))
      try {
        request.setAttribute('aiassistant.translateBatchConcurrency', Integer.valueOf(tbc))
      } catch (Throwable ignored2) {}
    } catch (Throwable ignored) {}
  }
  def previewPathForLog = AuthoringPreviewContext.normalizeRepoPath(contentPathBody?.toString())
  def previewTokenResolvedPresent = false
  try {
    previewTokenResolvedPresent = (StudioToolOperations.readCrafterPreviewTokenFromServletRequest(request) ?: '').trim().length() > 0
  } catch (Throwable ignored) {
    previewTokenResolvedPresent = false
  }
  def formEngineForLog = AuthoringPreviewContext.isFormEngineSurface(authoringSurface)
  def clientJsonApplyForLog = AuthoringPreviewContext.isTruthy(clientJsonApply)
  def formEngineClientForward = formEngineForLog && clientJsonApplyForLog
  def formEngineItemPathRaw = body?.formEngineItemPath?.toString()
  def formEngineItemNorm = AuthoringPreviewContext.normalizeRepoPath(formEngineItemPathRaw)
  def fullSuppressWritesFallback = formEngineClientForward && !formEngineItemNorm
  def omitTools = AuthoringPreviewContext.isTruthy(body?.omitTools)
  def enableToolsRequested = AuthoringPreviewContext.parseEnableTools(body?.enableTools)
  def enableTools = omitTools ? false : enableToolsRequested
  def enableToolsBeforeTrivial = enableTools
  def trivialTurn = false
  if (!formEngineForLog && enableTools && AuthoringPreviewContext.isTrivialNonAuthoringTurn(promptForOrchestration?.toString() ?: '')) {
    enableTools = false
    trivialTurn = true
    log.info(
      'STREAM endpoint: trivial non-authoring turn — forcing enableTools=false (authorVisibleLen={})',
      AuthoringPreviewContext.stripStudioInjectedPromptBlocks(promptForOrchestration?.toString() ?: '').length()
    )
  }
  def promptAssemblyTelemetry = AuthoringPreviewContext.buildPromptAssemblyTelemetry([
    clientWirePrompt      : prompt,
    orchestrationPrompt   : promptForOrchestration?.toString() ?: '',
    authoringSurface      : authoringSurface,
    contentPath           : contentPathBody,
    displayTemplate       : body?.displayTemplate,
    stepDeltas            : promptStepDeltas,
    enableToolsRequested  : enableToolsRequested,
    enableToolsEffective  : enableTools,
    trivialTurn           : trivialTurn
  ])
  try {
    request.setAttribute('aiassistant.promptAssemblyTelemetry', promptAssemblyTelemetry)
  } catch (Throwable ignoredPa) {
  }
  log.info(
    'STREAM endpoint hit: agentId={} llm={} clientWireLen={} orchestrationLen={} authorVisibleLen={} serverInjectedLen={} chatIdPresent={} siteId={} contentPathPresent={} displayTemplatePresent={} previewTokenResolvedPresent={} formEngineSurface={} formEngineClientJsonApply={} formEngineItemPath={} fullSuppressWritesFallback={} omitTools={} enableToolsRequested={} enableToolsEffective={} trivialNoToolsOverride={} stepDeltas={}',
    agentId,
    llm,
    promptAssemblyTelemetry.clientWirePromptChars,
    promptAssemblyTelemetry.orchestrationPromptChars,
    promptAssemblyTelemetry.authorVisibleChars,
    promptAssemblyTelemetry.serverInjectedChars,
    (chatId != null && chatId.toString().trim().length() > 0),
    siteIdBody ?: params?.siteId,
    promptAssemblyTelemetry.contentPathPresent,
    promptAssemblyTelemetry.displayTemplatePresent,
    previewTokenResolvedPresent,
    formEngineForLog,
    clientJsonApplyForLog,
    formEngineItemNorm ?: '(none)',
    fullSuppressWritesFallback,
    omitTools,
    enableToolsRequested,
    enableTools,
    (enableToolsBeforeTrivial && !enableTools),
    promptAssemblyTelemetry.stepDeltas ?: [:]
  )

  String siteForPrompts = (siteIdBody ?: params?.siteId?.toString()?.trim() ?: '')
  ToolPromptsSiteContext.enter(applicationContext, siteForPrompts)
  StudioAiAssistantSecretsContext.bind(siteForPrompts, applicationContext)
  try {
    try {
      def orchestration = new AiOrchestration(request, response, applicationContext, params, pluginConfig)
      def result = orchestration.chatStreamWithSpringAi(agentId, promptForOrchestration.toString(), chatId, llm, openAiModel, llmApiKey, imageModel, formEngineClientForward, formEngineItemPathRaw, enableTools, imageGenerator, llmSecretKey)
      if (result != null) {
        if (response.isCommitted()) {
          log.warn('chatStreamWithSpringAi returned error map but response already committed (SSE). Client should read metadata.error from stream. result={}', result)
          return null
        }
        response.resetBuffer()
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
        response.setContentType('application/json')
        response.getOutputStream().withWriter('UTF-8') { it.write(JsonOutput.toJson(result)) }
      }
      return null
    } catch (IllegalStateException ise) {
    if (response.isCommitted()) {
      log.error('stream.post IllegalStateException after response committed: {}', ise.message, ise)
      try {
        def os = response.getOutputStream()
        def frame =
          'data: ' +
            JsonOutput.toJson([
              text    : '',
              metadata: [error: true, completed: true, message: (ise.message ?: ise.class.simpleName).toString()]
            ]) +
            '\n\n'
        synchronized (os) {
          os.write(frame.getBytes(StandardCharsets.UTF_8))
          os.flush()
        }
      } catch (Throwable ignored) {
      }
      return null
    }
    response.resetBuffer()
    response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
    response.setContentType('application/json')
    response.getOutputStream().withWriter('UTF-8') { it.write(JsonOutput.toJson([message: ise.message ?: 'Configuration error'])) }
    return null
  } catch (Throwable e) {
    if (response.isCommitted()) {
      log.error('stream.post Throwable after response committed: {}', e.message, e)
      try {
        def os = response.getOutputStream()
        def frame =
          'data: ' +
            JsonOutput.toJson([
              text    : '',
              metadata: [error: true, completed: true, message: (e.message ?: e.class.simpleName).toString()]
            ]) +
            '\n\n'
        synchronized (os) {
          os.write(frame.getBytes(StandardCharsets.UTF_8))
          os.flush()
        }
      } catch (Throwable ignored) {
      }
      return null
    }
    response.resetBuffer()
    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
    response.setContentType('application/json')
    response.getOutputStream().withWriter('UTF-8') { it.write(JsonOutput.toJson([message: "Stream failed: ${e.message ?: e.class.simpleName}"])) }
    log.error('stream.post: orchestration failed', e)
    return null
  }
  } finally {
    ToolPromptsSiteContext.exit()
    StudioAiAssistantSecretsContext.clear()
  }
} catch (Throwable outer) {
  if (response?.isCommitted()) {
    log.error('stream.post: failure after response committed: {}', outer.message, outer)
    return null
  }
  try {
    response.resetBuffer()
    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
    response.setContentType('application/json')
    response.getOutputStream().withWriter('UTF-8') {
      it.write(JsonOutput.toJson([message: "AI Assistant stream failed: ${outer.message ?: outer.class.simpleName}".toString()]))
    }
  } catch (Throwable ignored) {
  }
  log.error('stream.post: unhandled failure before or during stream setup', outer)
  return null
}
