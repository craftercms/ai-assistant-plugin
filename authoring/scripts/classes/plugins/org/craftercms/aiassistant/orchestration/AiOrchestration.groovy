package plugins.org.craftercms.aiassistant.orchestration

import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.http.AiHttpProxy
import plugins.org.craftercms.aiassistant.llm.StudioAiLlmKind
import plugins.org.craftercms.aiassistant.llm.StudioAiLlmRuntimeFactory
import plugins.org.craftercms.aiassistant.llm.StudioAiProviderCredentials
import plugins.org.craftercms.aiassistant.llm.StudioAiRuntimeBuildRequest
import plugins.org.craftercms.aiassistant.plan.PlanOrchestration
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.rag.PluginRagVectorRegistry
import plugins.org.craftercms.aiassistant.recipes.AuthoringIntentRecipeBindings
import plugins.org.craftercms.aiassistant.recipes.AuthoringIntentRecipeCatalog
import plugins.org.craftercms.aiassistant.recipes.AuthoringIntentRecipeEngine
import plugins.org.craftercms.aiassistant.recipes.AuthoringIntentRecipeRouter
import plugins.org.craftercms.aiassistant.orchestration.chatcompletions.ChatCompletionsToolWire
import plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

@Grab(group='org.springframework.ai', module='spring-ai-core', version='1.0.0-M6', initClass=false)
@Grab(group='org.springframework.ai', module='spring-ai-openai', version='1.0.0-M6', initClass=false)
@Grab(group='org.springframework.ai', module='spring-ai-anthropic', version='1.0.0-M6', initClass=false)
@Grab(group='io.projectreactor', module='reactor-core', version='3.6.6', initClass=false)

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.ModelOptionsUtils
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest
import org.springframework.ai.openai.api.common.OpenAiApiConstants
import org.springframework.ai.tool.function.FunctionToolCallback
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.reactive.function.client.WebClientResponseException

import java.time.Duration
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Set
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

import reactor.core.publisher.Flux

/**
 * Central place for server-side orchestration for the <strong>Studio AI Assistant</strong> plugin.
 *
 * <p><strong>LLM adapters:</strong> Chat sessions are built through {@link StudioAiLlmRuntime} implementations
 * ({@link OpenAiSpringAiLlmRuntime}, {@link AnthropicSpringAiLlmRuntime}, {@link StudioAiScriptLlmContainerRuntime} for
 * {@code script:…} site Groovy). Each agent must configure a supported {@code llm}; there is no implicit hosted remote chat adapter.
 * Additional providers should implement {@link StudioAiLlmRuntime}, register in {@link StudioAiLlmRuntimeFactory}, and extend
 * {@link StudioAiLlmKind}.</p>
 *
 * <p>Provider-specific wire/stream logic (e.g. Chat Completions–style RestClient native tool loops) still lives here
 * until split into per-provider transports.</p>
 *
 * REST scripts are thin wrappers: validate input, call this class, return map or null for streaming.
 */
class AiOrchestration {
  private static final Logger log = LoggerFactory.getLogger(AiOrchestration.class)

  /**
   * Max wait for Spring AI {@code chatResponse()} flux to finish (complete or error), and for the native-tools
   * RestClient loop ({@code Future#get}) on the worker thread.
   * Default **10 minutes** — aligns with {@code CHAT_STREAM_TIMEOUT_MS} in {@code AiAssistantChat.tsx} (600_000).
   * Override JVM {@code aiassistant.chatFluxAwaitMs} (120_000–1_200_000).
   * On expiry we {@code dispose()} the subscription / cancel the future so the outbound HTTP call is torn down
   * (the upstream chat host sees a client disconnect on that connection).
   */
  private static final long CHAT_FLUX_AWAIT_MS = resolveChatFluxAwaitMs()

  /** Worker throws {@link InterruptedException} with this message when the SSE client disconnects or Stop cancels the pipeline. */
  private static final String AIASSISTANT_PIPELINE_CANCELLED = 'aiassistant.pipeline.cancelled'

  /**
   * Max characters per {@code role:tool} message in the native-tools RestClient loop. Huge tool JSON (e.g.
   * {@code ListPagesAndComponents} with a large {@code size}) must not blow the model context window.
   * <p><strong>{@code GenerateImage}:</strong> bitmaps are <strong>not</strong> sent in {@code role:tool} content — a
   * compact JSON with {@code inlineImageRef} is wired instead; the full {@code data:image/...;base64,...} is
   * held server-side and expanded into the final assistant text for SSE only (see
   * {@link ChatCompletionsToolWire#STUDIO_AI_INLINE_IMAGE_REF_PREFIX}).</p>
   */
  /** Cap for tools-loop {@code /v1/chat/completions} JSON body size (any native-tools vendor — not OpenAI-specific). */
  private static final int NATIVE_TOOLS_WIRE_JSON_MAX_CHARS = 36_000

  /**
   * Max characters per {@code text} field for the **final** assistant SSE payload after native tools.
   * One {@code data:image/...;base64,...} markdown line can exceed browser/proxy JSON-parse limits; chunking keeps
   * each {@code data:} frame parseable while the UI concatenates chunks.
   */
  private static final int NATIVE_TOOLS_FINAL_SSE_TEXT_CHUNK_CHARS = 48_000

  /**
   * Omit huge {@code data:image} payloads from tool-progress JSON metadata — the browser drops the whole SSE line if
   * {@code JSON.parse} fails (see {@code aiAssistantApi} guard). Large images ship in final assistant markdown instead
   * (chunked + client blob-ref preprocess).
   */
  private static final int GENERATE_IMAGE_TOOL_PROGRESS_METADATA_MAX_URL_CHARS = 28_000

  /**
   * Latest worker phase for logs and **SSE heartbeats** (Tools-loop+tools worker sets it; servlet thread reads it while
   * awaiting {@code Future#get}). Per-stream {@link #AIASSISTANT_TOOL_WORKER_DIAG_PHASE_BY_SESSION} avoids cross-talk when
   * several authors chat concurrently. Worker threads must call {@link #aiAssistantToolWorkerDiagSessionBind(String)}
   * before phases are visible to the servlet thread.
   */
  private static final ConcurrentHashMap<String, String> AIASSISTANT_TOOL_WORKER_DIAG_PHASE_BY_SESSION = new ConcurrentHashMap<>()

  private static final ThreadLocal<String> AIASSISTANT_TOOL_WORKER_DIAG_SESSION_ID = new ThreadLocal<>()

  /**
   * In-flight {@link #toolsLoopSimpleCompletionAssistantText} calls whose {@code workerPhasePrefix} is
   * {@code TranslateContentItem} (parallel {@code TranslateContentBatch} workers). When {@code inflight > 1},
   * heartbeats can hint that several inner completions may run in parallel.
   */
  private static final AtomicInteger AIASSISTANT_TRANSLATE_ITEM_INNER_INFLIGHT = new AtomicInteger(0)

  /**
   * Native tools worker thread: shared with {@link AiOrchestrationTools#runWithToolProgress} so repository
   * tools skip side effects after author Stop / SSE disconnect / timeout sets {@code cancelRequested}, or after
   * {@link Future#cancel(boolean)} interrupts the worker. Cleared in {@link #executeNativeToolsViaRestClientReturnText}
   * {@code finally} so the next chat prompt does not inherit a stale flag.
   */
  private static final ThreadLocal<AtomicBoolean> AIASSISTANT_PIPELINE_CANCEL_REQUESTED = new ThreadLocal<>()

  static void aiAssistantPipelineCancelBindingSet(AtomicBoolean cancelRequestedRef) {
    try {
      if (cancelRequestedRef != null) {
        AIASSISTANT_PIPELINE_CANCEL_REQUESTED.set(cancelRequestedRef)
      } else {
        AIASSISTANT_PIPELINE_CANCEL_REQUESTED.remove()
      }
    } catch (Throwable ignored) {
    }
  }

  static void aiAssistantPipelineCancelBindingClear() {
    try {
      AIASSISTANT_PIPELINE_CANCEL_REQUESTED.remove()
    } catch (Throwable ignored) {
    }
  }

  /**
   * True when this thread is running the Tools-loop+tools pipeline and the author cancelled, or the worker was interrupted.
   * Repository tools should treat this as "do not read/write the repo for this call".
   */
  static boolean aiAssistantPipelineCancelEffective() {
    try {
      AtomicBoolean a = AIASSISTANT_PIPELINE_CANCEL_REQUESTED.get()
      if (a == null) {
        return false
      }
      if (a.get()) {
        return true
      }
      return Thread.currentThread().isInterrupted()
    } catch (Throwable ignored) {
      return false
    }
  }

  /**
   * Binds a unique id for this Tools-loop worker so {@link #aiAssistantToolWorkerDiagPhaseGet(String)} on the servlet
   * thread reads the correct phase. Call {@link #aiAssistantToolWorkerDiagSessionEnd()} in a worker {@code finally}.
   */
  static void aiAssistantToolWorkerDiagSessionBind(String sessionId) {
    try {
      String s = (sessionId ?: '').toString().trim()
      if (s) {
        AIASSISTANT_TOOL_WORKER_DIAG_SESSION_ID.set(s)
        AIASSISTANT_TOOL_WORKER_DIAG_PHASE_BY_SESSION.put(s, '')
      }
    } catch (Throwable ignored) {
    }
  }

  static void aiAssistantToolWorkerDiagSessionEnd() {
    try {
      String sid = AIASSISTANT_TOOL_WORKER_DIAG_SESSION_ID.get()
      if (sid != null && sid.toString().trim()) {
        AIASSISTANT_TOOL_WORKER_DIAG_PHASE_BY_SESSION.remove(sid.toString().trim())
      }
    } catch (Throwable ignored) {
    }
    try {
      AIASSISTANT_TOOL_WORKER_DIAG_SESSION_ID.remove()
    } catch (Throwable ignored2) {
    }
  }

  /** Set from the worker thread only (tool loop, RestClient POST, TransformContentSubgraph, etc.). */
  static void aiAssistantToolWorkerDiagPhase(String phase) {
    try {
      String sid = AIASSISTANT_TOOL_WORKER_DIAG_SESSION_ID.get()
      if (sid != null && sid.toString().trim()) {
        String key = sid.toString().trim()
        if (phase != null && phase.toString().trim()) {
          AIASSISTANT_TOOL_WORKER_DIAG_PHASE_BY_SESSION.put(key, phase.toString().trim())
        } else {
          AIASSISTANT_TOOL_WORKER_DIAG_PHASE_BY_SESSION.put(key, '')
        }
      }
    } catch (Throwable ignoredBind) {
    }
  }

  /**
   * @param sessionId when non-blank (same value passed to {@link #aiAssistantToolWorkerDiagSessionBind}), reads the
   *                    phase for that stream.
   */
  static String aiAssistantToolWorkerDiagPhaseGet(String sessionId = null) {
    try {
      String key = (sessionId ?: '')?.toString()?.trim()
      if (key) {
        def v = AIASSISTANT_TOOL_WORKER_DIAG_PHASE_BY_SESSION.get(key)
        return v != null ? v.toString() : ''
      }
    } catch (Throwable ignoredMap) {
    }
    return ''
  }

  static void aiAssistantToolWorkerDiagPhaseClear() {
    try {
      String sid = AIASSISTANT_TOOL_WORKER_DIAG_SESSION_ID.get()
      if (sid != null && sid.toString().trim()) {
        AIASSISTANT_TOOL_WORKER_DIAG_PHASE_BY_SESSION.put(sid.toString().trim(), '')
      }
    } catch (Throwable ignored) {
    }
  }

  /**
   * Short author-facing hint for SSE heartbeats while the Tools-loop+tools worker is busy — derived from
   * {@link #aiAssistantToolWorkerDiagPhaseGet(String)} (per-stream session on the servlet thread) so we do not imply the main chat POST is slow when
   * {@link AiOrchestrationTools} is inside a bundled inner completion (e.g. {@code TransformContentSubgraph}).
   */
  private static String pipelineWaitHintMarkdown(String workerPhase) {
    int translateInnerInflight = AIASSISTANT_TRANSLATE_ITEM_INNER_INFLIGHT.get()
    if (translateInnerInflight > 0) {
      return translateInnerInflight > 1
        ? 'Applying updates to **several** content files in parallel…'
        : 'Applying updates to **one** content file…'
    }
    String p = (workerPhase ?: '').toString()
    if (!p.trim()) {
      return 'Organizing the next step…'
    }
    if (p.contains('TranslateContentItem_await_inner')) {
      return 'Applying updates to a content file…'
    }
    if (p.contains('TransformContentSubgraph_await_inner')) {
      return 'Processing linked pages together (larger jobs take longer)…'
    }
    if (p.contains('TranslateContentItem_simple_completion_awaiting_chat_upstream_response_body')) {
      return 'Finishing an automated content edit…'
    }
    if (p.contains('TranslateContentItem_simple_completion_HttpURLConnection_POST')) {
      return 'Sending an automated content edit…'
    }
    if (p.contains('TransformContentSubgraph_simple_completion_awaiting_chat_upstream_response_body')) {
      return 'Receiving updates for linked pages…'
    }
    if (p.contains('TransformContentSubgraph_simple_completion_HttpURLConnection_POST')) {
      return 'Sending a bundled content update…'
    }
    if (p.contains('simple_completion_awaiting_chat_upstream_response_body')) {
      return 'Waiting on a background content edit…'
    }
    if (p.contains('simple_completion_HttpURLConnection_POST')) {
      return 'Waiting on a background response…'
    }
    if (p.contains('TranslateContentItem_apply_writes')) {
      return 'Saving updated content…'
    }
    if (p.contains('TranslateContentItem_ContentSubgraphAggregator_build')) {
      return 'Loading content for editing…'
    }
    if (p.contains('TranslateContentItem_parsing_validating')) {
      return 'Checking edited content before save…'
    }
    if (p.contains('TranslateContentItem')) {
      return 'Applying content updates…'
    }
    if (p.contains('TransformContentSubgraph_apply_writes')) {
      return 'Saving linked pages…'
    }
    if (p.contains('TransformContentSubgraph_ContentSubgraphAggregator_build')) {
      return 'Gathering linked content…'
    }
    if (p.contains('TransformContentSubgraph_parsing_validating')) {
      return 'Checking bundled edits…'
    }
    if (p.contains('TransformContentSubgraph')) {
      return 'Processing linked content…'
    }
    if (p.contains('native_tools_RestClient_POST_/v1/chat/completions') && !p.contains('response_ok')) {
      return 'Choosing the next step…'
    }
    if (p.contains('native_tool_loop_round') && p.contains('repository_tool') && !p.contains('repository_tool_done')) {
      return 'Updating your site…'
    }
    if (p.contains('native_tool_loop_round') && p.contains('_build_request')) {
      return 'Preparing the next step…'
    }
    return 'Organizing the next step…'
  }

  private static long resolveChatFluxAwaitMs() {
    try {
      def p = System.getProperty('aiassistant.chatFluxAwaitMs')
      if (p != null && p.toString().trim()) {
        long n = Long.parseLong(p.toString().trim())
        if (n >= 120_000L && n <= 1_200_000L) {
          return n
        }
      }
    } catch (Throwable ignored) {}
    return 600_000L
  }

  /**
   * While the servlet waits on the Tools-loop+tools worker, emit periodic SSE lines so authors are not silent for minutes.
   * Override JVM {@code aiassistant.openai.sseWaitHeartbeatMs} (3000–120000; default 5000).
   */
  private static long resolveToolsLoopSseWaitHeartbeatMs() {
    try {
      def p = System.getProperty('aiassistant.openai.sseWaitHeartbeatMs')
      if (p != null && p.toString()?.trim()) {
        long n = Long.parseLong(p.toString().trim())
        if (n >= 3_000L && n <= 120_000L) {
          return n
        }
      }
    } catch (Throwable ignored) {}
    return 5_000L
  }

  private static final long TOOLS_LOOP_SSE_WAIT_HEARTBEAT_MS = resolveToolsLoopSseWaitHeartbeatMs()

  /**
   * HTTP read timeout for Spring {@link RestClient} POSTs to {@code /v1/chat/completions} (tools-on/off sync paths).
   * Defaults to {@link #CHAT_FLUX_AWAIT_MS} + 30s so the client does not hit {@link ResourceAccessException} read
   * timeouts (JDK default is often ~60s) before the outer pipeline budget cancels. Override
   * {@code aiassistant.openai.restReadTimeoutMs} (60_000–1_260_000).
   */
  private static int resolveChatCompletionsRestReadTimeoutMs() {
    try {
      def p = System.getProperty('aiassistant.openai.restReadTimeoutMs')
      if (p != null && p.toString().trim()) {
        int n = Integer.parseInt(p.toString().trim())
        if (n >= 60_000 && n <= 1_260_000) {
          return n
        }
      }
    } catch (Throwable ignored) {}
    return (int) Math.min(1_260_000L, CHAT_FLUX_AWAIT_MS + 30_000L)
  }

  private static SimpleClientHttpRequestFactory chatCompletionsRestRequestFactory() {
    def rf = new SimpleClientHttpRequestFactory()
    rf.setReadTimeout(resolveChatCompletionsRestReadTimeoutMs())
    rf.setConnectTimeout(30_000)
    return rf
  }

  private static RestClient.Builder chatCompletionsRestClientBuilder(String apiKey, String wireBaseUrl = null) {
    String base = (wireBaseUrl ?: '').toString().trim()
    if (!base) {
      base = (OpenAiApiConstants.DEFAULT_BASE_URL ?: 'https://api.openai.com').toString().trim()
    }
    base = base.replaceAll(/\/+$/, '')
    RestClient.builder()
      .baseUrl(base)
      .defaultHeader(HttpHeaders.AUTHORIZATION, 'Bearer ' + apiKey)
      .requestFactory(chatCompletionsRestRequestFactory())
  }

  /**
   * Effective URL for {@code POST .../chat/completions}, matching {@link #chatCompletionsRestClientBuilder}
   * {@code .post().uri("/v1/chat/completions")}. Spring AI's default base is often {@code https://api.openai.com}
   * (no {@code /v1}); appending only {@code /chat/completions} yields {@code .../chat/completions} and many hosts return
   * <strong>404</strong>. Always normalize so {@link #toolsLoopSimpleCompletionAssistantText} hits the same host/path as
   * {@link #httpPostChatCompletionsReadBody}.
   */
  private static String resolveSyncChatCompletionsUrl(String wireBaseUrl = null) {
    String b = (wireBaseUrl ?: '').toString().trim()
    if (!b) {
      b = (OpenAiApiConstants.DEFAULT_BASE_URL?.toString()?.trim() ?: 'https://api.openai.com')
    }
    b = b.replaceAll(/\/+$/, '')
    if (b.endsWith('/v1')) {
      return b + '/chat/completions'
    }
    return b + '/v1/chat/completions'
  }

  /**
   * Spring AI / WebClient / Netty: optional DEBUG in Studio logs (Log4j2). **Off by default.**
   * Set JVM system property {@code aiassistant.springAiHttpDebug=true} to enable once per JVM.
   */
  private static final AtomicBoolean springAiVerboseHttpLoggingArmed = new AtomicBoolean(false)

  private static void ensureVerboseSpringAiHttpLogging() {
    String raw = System.getProperty('aiassistant.springAiHttpDebug', 'false')
    if (!Boolean.parseBoolean((raw != null ? raw.trim() : 'false') ?: 'false')) {
      return
    }
    if (!springAiVerboseHttpLoggingArmed.compareAndSet(false, true)) {
      return
    }
    try {
      Class cfgCls = Class.forName('org.apache.logging.log4j.core.config.Configurator')
      Class levelCls = Class.forName('org.apache.logging.log4j.Level')
      Object debug = levelCls.getField('DEBUG').get(null)
      def setLevel = cfgCls.getMethod('setLevel', String, levelCls)
      [
        'org.springframework.ai',
        'org.springframework.ai.openai',
        'org.springframework.ai.chat.client',
        'org.springframework.web.reactive.function.client',
        'org.springframework.http.codec',
        'reactor.netty.http.client'
      ].each { String name -> setLevel.invoke(null, name, debug) }
      log.info(
        'AI Assistant: Spring AI / WebClient / Reactor Netty HTTP loggers set to DEBUG (aiassistant.springAiHttpDebug=true).'
      )
    } catch (Throwable t) {
      log.warn('AI Assistant: failed to enable Spring AI HTTP debug loggers (Log4j2 Configurator): {}', t.message)
    }
  }

  private final def request
  private final def response
  private final def applicationContext
  private final def params
  private final def pluginConfig

  AiOrchestration(def request, def response, def applicationContext, def params, def pluginConfig) {
    this.request = request
    this.response = response
    this.applicationContext = applicationContext
    this.params = params
    this.pluginConfig = pluginConfig
  }

  private static boolean isToolRequiredIntent(String prompt) {
    def p = (prompt ?: '').toLowerCase()
    if (!p) return false
    def patterns = [
      /.*\bupdate\b.*/,
      /.*\bmodify\b.*/,
      /.*\bchange\b.*/,
      /.*\bedit\b.*/,
      /.*\bcreate\b.*/,
      /.*\bwrite\b.*/,
      /.*\brewrite\b.*/,
      /.*\btranslate\b.*/,
      /.*\btranslation\b.*/,
      /.*\blocalize\b.*/,
      /.*\blocalise\b.*/,
      /.*\brephrase\b.*/,
      /.*\bpublish\b.*/,
      /.*\brevert\b.*/,
      /.*\brollback\b.*/,
      /.*\btemplate\b.*/,
      /.*\bcontent type\b.*/,
      /.*\bform-definition\b.*/,
      /.*\bhome page\b.*/
    ]
    return patterns.any { p ==~ it }
  }

  /**
   * Prepended to the user message when {@code formEngineClientForward} — models often ignore trailing instructions
   * and answer with generic CMS docs unless this block is first.
   */
  private static String prependFormEngineClientApplyEnforcement(String prompt) {
    def tail = (prompt ?: '').toString()
    return '''[FORM-ENGINE — CLIENT FIELD APPLY — READ FIRST]
The author is in Studio's **legacy content form**. When the UI attaches **Current Studio content form**, it sends **metadata only** (content type, path, field ids, linked paths, model keys) — **not** full XML/JSON bodies. **GetContent** / **update_content** read the **git** copy; unsaved form edits are **not** inlined in the prompt — use **GetContent** after Save for repo truth, or return **`aiassistantFormFieldUpdates`** from visible task + ids when the author expects client-side apply without Save.

**Content-changing requests** (translate, localize, rephrase, rewrite, fix grammar, shorten, expand, fill, update, change tone, write copy, etc.) mean **field values and item XML** — not FreeMarker templates, scripts, or other **code** unless the author explicitly asked for those. If the author updates **this page** / **the page** in preview **without** naming a single block, they mean **the page file and every referenced component** that shows copy (`sections_o`, `header_o` / `footer_o` / `left_rail_o`, etc.) — not the page item alone; apply or output updates for each path that holds visible text.
1) **Do the work** in the target language or style. **End your reply** with a Markdown **```json** fenced block containing ONLY valid JSON of the form: {"aiassistantFormFieldUpdates":{"field_id":"new value",...}} using **exact** field element names from the form definition / XML in the prompt. List **every** field you changed. HTML/RTE fields: string values may include markup.
2) **Forbidden:** Generic CrafterCMS tutorials ("Access the Content Item", "Translation Configuration", "add a language", "click Save", workflow documentation), MCP/plugin commands, or refusing to translate when you can output the target language. A short intro sentence is OK; the **JSON block is mandatory** for these requests.

**Pure Q&A** (no edits to the open item): answer normally and **omit** the JSON block.

---

''' + tail
  }

  private static String addToolRequiredGuard(String prompt, boolean fullSuppressRepoWrites = false, String protectedFormItemPathNormalized = null) {
    def p = (prompt ?: '').toString()
    if (!isToolRequiredIntent(p)) return p
    def normProt = AuthoringPreviewContext.normalizeRepoPath(protectedFormItemPathNormalized)
    if (fullSuppressRepoWrites) {
      return '''[TOOL-GUARD]
This user request is a content/template/config modification task.
You MUST call at least one tool before giving your final response.
Do not respond with prose-only output for this request (no final answer that skips tools).
**WriteContent**, **publish_content**, and **revert_change** are **not registered** — never call them.
After **update_content** (or sufficient **GetContent** / **GetContentTypeFormDefinition**), your **final** reply must include **`aiassistantFormFieldUpdates`** JSON (see system **Form-engine client-forward mode**) so the Studio form can apply edits — do not substitute MCP commands or “paste into Studio” tutorials.
First output a **business-readable ## Plan** (**📋** per numbered step, enough detail for a non-developer — see system STUDIO POLICY). **Do not** write plan steps that only restate that you will run tools or obey policy — each **📋** line must name a **concrete visitor- or editor-visible outcome** (what changes, where you verify it). **Do not** call tools until that heading and steps are visible. Then **follow that plan**; after each tool refresh the **same** **📋** lines with **✅** / **❌** / **⚠️** / **⬜** only — keep mid-flight updates compact. When you narrate tool use in your own words, prefix with **🛠️**. Do **not** fake server-style tool log lines (see system STUDIO POLICY).
Do **not** paste full FreeMarker (`.ftl`) bodies or large XML dumps into the author's chat — summarize outcomes; they edit in the form.
If target path/id is unclear and the user message does not include **Studio authoring context** with a current repository path, call discovery tools first.
Your **final** reply after tools must state **success or problems** using **✅** / **❌** / **⚠️**, include a **clear business-friendly** recap under **## Plan Execution** (not **## Plan** again) that mirrors the **📋** checklist — **open that section** with one short line that **core work is done** and the bullets are **recap / verification**, then **ask what's next**.
For **content XML** (pages/components): preserve `<page>`/`<component>` and field tags from the current file and content type (`formFieldIds` / GetContentTypeFormDefinition). For GetContentTypeFormDefinition use **contentPath** or copy **contentTypeId** from `<content-type>` — never infer content type from filename. For **page-wide** translate/tone/rewrite, include **all referenced component** items, not the page file only (see system **“This page”** rule).
When the author only asked to **update content** — **field values and item XML / static-assets**, not template or schema **file edits** (e.g. tone, grammar, proofreading, translate/localize, copy, rephrase) — use **update_content** / **GetContent** — **do not** call **update_template** or **update_content_type** to fix gaps in those tasks. You **may** use **analyze_template** or **GetContent** on `.ftl` **read-only** to diagnose why preview still disagrees with the goal; if the issue is **in the template** (hardcoded copy, wrong defaults, etc.), **tell the author** (path + brief evidence) — do not patch FTL without explicit consent to change templates.
[/TOOL-GUARD]

''' + p
    }
    if (normProt) {
      return """[TOOL-GUARD]
This user request is a content/template/config modification task.
You MUST call at least one tool before giving your final response.
The repository item **${normProt}** is open in the Studio **content form** with client-side apply: for **that path only**, do **not** call **WriteContent**, **publish_content**, or **revert_change** (they are blocked) — use **`aiassistantFormFieldUpdates`** in your **final** JSON for that item.
For **any other path**, you may call **WriteContent** (and publish/revert) as usual after **update_*** tools.
First output a **business-readable ## Plan** (**📋** per step, stakeholder-friendly — see system STUDIO POLICY). **Do not** write plan steps that only restate that you will run tools or obey policy — each **📋** line must name a **concrete visitor- or editor-visible outcome**. **Do not** call tools until that heading and steps are visible. Then **follow that plan**; after each tool refresh the **same** **📋** lines with **✅** / **❌** / **⚠️** / **⬜** only. When you narrate tool use in your own words, prefix with **🛠️**. Do **not** fake server-style tool log lines (see system STUDIO POLICY).
Do **not** paste full FreeMarker (`.ftl`) bodies or large XML dumps into the author's chat — summarize outcomes.
Your **final** reply after tools must state **success or problems** using **✅** / **❌** / **⚠️**, include a **clear business-friendly** recap under **## Plan Execution** — **lead with** one short **done + now wrapping up** line, then markers — and **ask what's next**.
When the author only asked to **update content** — **field values and item XML / static-assets**, not template or schema **file edits** — use **update_content** / **GetContent** — **do not** call **update_template** or **update_content_type** to fix those tasks. You **may** use **analyze_template** or **GetContent** on `.ftl` **read-only** to diagnose; if the issue is **in the template**, **tell the author** — do not patch FTL without explicit consent to change templates. **Page-level** translate/rewrite: update the **page** and **each referenced component** with visible text (not the page file alone) unless the author limited scope.
[/TOOL-GUARD]

""" + p
    }
    return '''[TOOL-GUARD]
This user request is a content/template/config modification task.
You MUST call at least one tool before giving your final response.
Do not respond with prose-only output for this request (no final answer that skips tools).
First output a **business-readable ## Plan** (**📋** per step — see system STUDIO POLICY). **Do not** write plan steps that only restate that you will run tools or obey policy — each **📋** line must name a **concrete visitor- or editor-visible outcome**. **Do not** call tools until that heading and steps are visible. Then **follow that plan**; after each tool refresh the **same** **📋** lines with **✅** / **❌** / **⚠️** / **⬜** only — keep the step list stable. When you narrate tool use in your own words, prefix with **🛠️**. Do **not** fake server-style tool log lines (see system STUDIO POLICY).
Do **not** paste full FreeMarker (`.ftl`) bodies or large XML dumps into the author's chat — summarize what was saved; they edit files in Studio.
If target path/id is unclear and the user message does not include **Studio authoring context** with a current repository path, call discovery tools first.
After **update_content**, **update_template**, or **update_content_type** returns, you must still call **WriteContent** with the full file — those tools do not save.
When the author only asked to **update content** — **field values and item XML / static-assets**, not template or schema **file edits** — use **update_content** → **WriteContent** on the **content item** path — **do not** call **update_template** or **update_content_type** to fix those tasks. You **may** use **analyze_template** or **GetContent** on `.ftl` **read-only** to diagnose; if the issue is **in the template**, **tell the author** — do not patch FTL without explicit consent to change templates. For **page-wide** translate/rewrite, call **GetContent**/**update_content** and **WriteContent** for the **page** and **each referenced component** (see system **“This page”** rule) unless the author limited scope.
Your **final** reply after tools must state **success or problems** using **✅** / **❌** / **⚠️**, include a **clear business-friendly** recap under **## Plan Execution** with those markers — **first** a tight **main outcome shipped; below is scorecard** cue — and **ask what's next**—not only mid-flight progress.
For **content XML** (pages/components): do not invent a new element tree — preserve `<page>`/`<component>` and field tags from the current file and content type (`formFieldIds` / GetContentTypeFormDefinition). For GetContentTypeFormDefinition use **contentPath** (item XML path) or copy **contentTypeId** from `<content-type>` — never infer content type from filename.
[/TOOL-GUARD]

''' + p
  }

  /**
   * Convert tool result to string for the model after a tool call.
   * <p>{@link GetContent} results are sent as JSON (path, commit ref, content-type hints, diagnostics, plus
   * {@code contentXml}) so the model keeps repository grounding; large bodies are still capped by
   * {@link #truncateNativeToolWireContent}. Preparatory tools ({@code update_content}, {@code update_template}, …)
   * embed {@code nextStep} / {@code promptGuidance}; those maps must <strong>not</strong> be collapsed in a way that
   * drops follow-up instructions.</p>
   */
  private String mapResultToString(Object result, java.lang.reflect.Type returnType) {
    return toolResultToWireString(result, returnType)
  }

  /**
   * Shared tool callback → wire string (Spring AI tool result converter) for chat and headless runs.
   */
  static String toolResultToWireString(Object result, java.lang.reflect.Type returnType) {
    if (result instanceof Map) {
      def m = (Map) result
      if (m?.nextStep != null || m?.promptGuidance != null) {
        return JsonOutput.toJson(m)
      }
      if (m?.contentXml != null) {
        return JsonOutput.toJson(m)
      }
      if (m?.formDefinitionXml != null) {
        return JsonOutput.toJson(m)
      }
      if ('GenerateImage'.equals(m?.tool?.toString())) {
        def u = m.url?.toString()
        if (u && u.startsWith('data:image') && m.containsKey('b64_json')) {
          Map m2 = new LinkedHashMap<>(m)
          m2.remove('b64_json')
          return JsonOutput.toJson(m2)
        }
      }
    }
    return JsonOutput.toJson(result != null ? result : [])
  }

  /** Delegates to {@link StudioAiLlmKind#normalize(String)} — stable entry for REST scripts. */
  static String normalizeLlmProvider(String raw) {
    return StudioAiLlmKind.normalize(raw)
  }

  /**
   * Resolution order: {@code OPENAI_API_KEY} env, {@code crafter.openai.apiKey} JVM,
   * {@code OPENAI_API_KEY} JVM, then optional {@code fromWidgetOrRequest} (ui.xml / POST body — testing only).
   */
  static String resolveApiKey(String fromWidgetOrRequest = null) {
    def fromEnv = System.getenv('OPENAI_API_KEY')
    if (fromEnv?.trim()) return fromEnv.trim()
    def p = System.getProperty('crafter.openai.apiKey')
    if (p?.trim()) return p.trim()
    p = System.getProperty('OPENAI_API_KEY')
    if (p?.trim()) return p.trim()
    def w = (fromWidgetOrRequest ?: '').toString().trim()
    return w ?: ''
  }

  /**
   * Vendor-aware API key for orchestration callers. When {@code llmNormalized} is omitted, defaults to OpenAI resolution
   * (expert-skill embeddings and legacy image paths).
   */
  static String resolveLlmApiKey(String fromWidgetOrRequest = null, String llmNormalized = null) {
    String kind = (llmNormalized ?: StudioAiLlmKind.OPENAI_NATIVE).toString()
    if (StudioAiLlmKind.isAnthropicClaude(kind)) {
      return StudioAiProviderCredentials.resolveAnthropicApiKey(fromWidgetOrRequest)
    }
    if (StudioAiLlmKind.useToolsLoopChatRestClientBuiltInKinds(kind) || StudioAiLlmKind.isScriptHostedLlm(kind)) {
      return StudioAiProviderCredentials.resolveApiKey(kind, fromWidgetOrRequest)
    }
    return resolveApiKey(fromWidgetOrRequest)
  }

  /**
   * For logs only: which path {@link #resolveApiKey(String)} took (mirrors resolution order; no secret material).
   */
  static String apiKeyResolutionSource() {
    if (System.getenv('OPENAI_API_KEY')?.toString()?.trim()) return 'OPENAI_API_KEY(env)'
    if (System.getProperty('crafter.openai.apiKey')?.trim()) return 'crafter.openai.apiKey(jvm)'
    if (System.getProperty('OPENAI_API_KEY')?.trim()) return 'OPENAI_API_KEY(jvm)'
    return 'widget-or-request'
  }

  /**
   * For logs only: leading + trailing characters of the key (never the full secret; middle elided).
   * Uses a longer tail than 4 chars so typical API key suffixes (e.g. last 6) are visible for verification.
   */
  /** For logs only: elided preview of any provider API key (never the full secret). */
  static String llmApiKeyLogPreview(String key) {
    def k = (key ?: '').toString().trim()
    if (!k) return '(empty)'
    int n = k.length()
    int showTail = n >= 48 ? 8 : (n >= 20 ? 6 : Math.min(4, Math.max(2, n.intdiv(4))))
    int showHead = Math.min(12, n - showTail - 1)
    if (showHead < 1) {
      showHead = 1
    }
    if (showHead + showTail >= n) {
      showTail = n - showHead - 1
    }
    if (showTail < 1) {
      return k.substring(0, 1) + '…' + k.substring(n - 1)
    }
    return k.substring(0, showHead) + '…' + k.substring(n - showTail)
  }

  /** Plain text from a Spring AI {@code Message} for request logging (best-effort across M6 shapes). */
  private static String messagePlainTextForLog(Object m) {
    if (m == null) return ''
    try {
      if (m.metaClass.respondsTo(m, 'getText')) {
        def t = m.getText()
        if (t != null) return t.toString()
      }
    } catch (Throwable ignored) {}
    try {
      if (m.metaClass.respondsTo(m, 'getContent')) {
        def c = m.getContent()
        if (c != null) return c.toString()
      }
    } catch (Throwable ignored) {}
    try {
      return m.text?.toString() ?: ''
    } catch (Throwable ignored) {}
    return m.toString()
  }

  private static List chatMessagesWireShape(Prompt prompt) {
    def out = []
    if (prompt == null) return out
    def list = null
    try {
      list = prompt.getInstructions()
    } catch (Throwable ignored) {
      try {
        list = prompt.instructions
      } catch (Throwable ignored2) {}
    }
    if (!list) return out
    list.each { msg ->
      String role = 'user'
      try {
        def n = msg?.getClass()?.name ?: ''
        if (n.endsWith('SystemMessage') || n.contains('.SystemMessage')) role = 'system'
        else if (n.endsWith('UserMessage') || n.contains('.UserMessage')) role = 'user'
        else if (n.endsWith('AssistantMessage') || n.contains('.AssistantMessage')) role = 'assistant'
      } catch (Throwable ignored) {}
      out << [role: role, content: messagePlainTextForLog(msg)]
    }
    out
  }

  /**
   * Chat Completions {@code tools[]} shape (function name, description, parameters object) from Spring AI callbacks.
   * Omits api_key; mirrors Chat Completions body aside from Spring-only / optional fields.
   */
  private static List toolsWireShape(def tools) {
    def out = []
    if (tools == null) return out
    def slurper = new JsonSlurper()
    (tools as List).each { t ->
      String name = ''
      String desc = ''
      Object params = [:]
      try {
        if (t?.metaClass?.respondsTo(t, 'getToolDefinition')) {
          def td = t.getToolDefinition()
          if (td != null) {
            try {
              if (td.metaClass.respondsTo(td, 'name')) name = td.name()?.toString()?.trim() ?: ''
            } catch (Throwable ignored) {}
            try {
              if (td.metaClass.respondsTo(td, 'description')) desc = (td.description() ?: '').toString()
            } catch (Throwable ignored) {}
            try {
              if (td.metaClass.respondsTo(td, 'inputSchema')) {
                def raw = td.inputSchema()?.toString()?.trim()
                if (raw) {
                  try {
                    params = slurper.parseText(raw)
                  } catch (Throwable ignored) {
                    params = [ _unparsedSchema: raw ]
                  }
                }
              }
            } catch (Throwable ignored) {}
          }
        }
      } catch (Throwable ignored) {}
      if (!name) {
        try {
          name = t?.name?.toString()?.trim() ?: ''
        } catch (Throwable ignored) {}
      }
      if (name) {
        out << [type: 'function', function: [name: name, description: desc, parameters: params ?: [:]]]
      }
    }
    out
  }

  /**
   * Pretty-printed JSON approximating POST {@code /v1/chat/completions} (no api_key).
   * Logged as separate records (envelope, messages, one record per tool) so nothing is split mid-string
   * — fixed-size slicing produced invalid JSON fragments in logs.
   */
  private void logChatCompletionsPayloadApprox(String agentId, String resolvedModel, Prompt prompt, def tools) {
    if (!log.isDebugEnabled()) {
      return
    }
    try {
      def messages = chatMessagesWireShape(prompt)
      def toolsList = toolsWireShape(tools)
      def envelope = [
        model        : resolvedModel,
        stream       : true,
        messageCount : messages.size(),
        toolCount    : toolsList.size()
      ]
      // tool_choice is invalid unless tools[] is non-empty; omit from approx log when no tools.
      if (!toolsList.isEmpty()) {
        envelope.tool_choice = 'auto'
      }
      log.debug(
        'Tools-loop /v1/chat/completions outbound (approx) agentId={} envelope:\n{}',
        agentId,
        JsonOutput.prettyPrint(JsonOutput.toJson(envelope))
      )
      log.debug(
        'Tools-loop /v1/chat/completions outbound (approx) agentId={} messages:\n{}',
        agentId,
        JsonOutput.prettyPrint(JsonOutput.toJson([messages: messages]))
      )
      int n = toolsList.size()
      for (int i = 0; i < n; i++) {
        log.debug(
          'Tools-loop /v1/chat/completions outbound (approx) agentId={} tools[{}/{}]:\n{}',
          agentId,
          i + 1,
          n,
          JsonOutput.prettyPrint(JsonOutput.toJson(toolsList[i]))
        )
      }
    } catch (Throwable t) {
      log.warn('Tools-loop outbound JSON log failed: {}', t.message)
    }
  }

  /**
   * Turns Studio / agent display strings into wire model ids: lowercase, hyphens, no interior spaces
   * (e.g. {@code GPT-5.4 nano} → {@code gpt-5.4-nano}, {@code GPT 4o mini} → {@code gpt-4o-mini}).
   */
  static String llmCanonicalizeApiModelToken(String raw) {
    if (raw == null || !raw.toString().trim()) {
      return ''
    }
    String s = normalizeModelIdForHeuristics(raw.toString().trim())
    s = s.replaceAll(/\s+/, '-')
    s = s.replace('_', '-')
    s = s.replaceAll(/-+/, '-')
    s = s.replaceAll(/^-+/, '')
    s = s.replaceAll(/-+$/, '')
    return s
  }

  /**
   * Canonical image model id for {@code POST /v1/images/generations}: {@link #llmCanonicalizeApiModelToken(String)}
   * on trimmed input. Returns the raw parameter when blank or when canonicalization yields an empty string.
   */
  static String normalizeImagesApiModelId(String modelIdRawOrCanonical) {
    if (modelIdRawOrCanonical == null || !modelIdRawOrCanonical.toString().trim()) {
      return modelIdRawOrCanonical
    }
    String canon = llmCanonicalizeApiModelToken(modelIdRawOrCanonical.toString().trim())
    if (!canon) {
      return modelIdRawOrCanonical
    }
    return canon
  }

  /** Wire JSON body for {@code /v1/chat/completions}: read {@code model} for author-facing errors. */
  private static String extractWireModelFromChatCompletionsRequestJson(String jsonBody) {
    if (jsonBody == null || !jsonBody.toString().trim()) {
      return ''
    }
    try {
      def p = new JsonSlurper().parseText(jsonBody.toString())
      if (p instanceof Map) {
        return (p.get('model') ?: '').toString().trim()
      }
    } catch (Throwable ignored) {
    }
    return ''
  }

  private static boolean responseBodyLooksLikeInvalidModelId(String responseBody) {
    String b = (responseBody ?: '').toLowerCase(Locale.ROOT)
    return b.contains('invalid model')
  }

  /**
   * When the chat host returns HTTP 400 for an unknown model id, surface a clear configuration error (no silent fallback model).
   */
  private static IllegalStateException newIllegalStateForInvalidWireModel(String requestJsonBody, String responseBody) {
    String wireModel = extractWireModelFromChatCompletionsRequestJson(requestJsonBody)
    String apiMsg = ''
    try {
      def p = new JsonSlurper().parseText((responseBody ?: '').toString())
      if (p instanceof Map && p.get('error') instanceof Map) {
        apiMsg = (p.get('error').get('message') ?: '').toString().trim()
      }
    } catch (Throwable ignored) {
    }
    StringBuilder sb = new StringBuilder()
    sb.append('The LLM model is not accepted by the configured chat host. ')
    sb.append('The model id sent on the wire was "').append(wireModel ? wireModel : '(unknown)').append('". ')
    if (apiMsg) {
      sb.append('Provider message: ').append(apiMsg).append(' ')
    }
    sb.append(
      'Set the agent chat model to an id your host and API key support (for example in ui.xml / control payload), pass llmModel on the chat request, or set JVM crafter.openai.model when using the default bundled chat host row.'
    )
    return new IllegalStateException(sb.toString())
  }

  /**
   * If {@code rce} is HTTP 400 with an "invalid model" style error body, return {@link IllegalStateException}; otherwise return {@code rce}.
   */
  private static Throwable preferIllegalStateForInvalidModel(RestClientResponseException rce, String requestJsonBody) {
    int code = 0
    try {
      code = rce.getStatusCode().value()
    } catch (Throwable ignored) {
    }
    String body = ''
    try {
      body = rce.getResponseBodyAsString(StandardCharsets.UTF_8) ?: ''
    } catch (Throwable ignored) {
    }
    if (code == 400 && responseBodyLooksLikeInvalidModelId(body)) {
      return newIllegalStateForInvalidWireModel(requestJsonBody, body)
    }
    return rce
  }

  static String resolveChatModel(String fromRequest) {
    String base = (fromRequest ?: '').toString().trim() ?: (System.getProperty('crafter.openai.model') ?: '').toString().trim()
    if (!base) {
      throw new IllegalStateException(
        'The chat model is not configured properly. Set the agent LLM / llmModel in Studio (for example ui.xml), pass llmModel on the chat request, or set JVM property crafter.openai.model when using the default bundled chat host configuration.'
      )
    }
    String canon = llmCanonicalizeApiModelToken(base)
    if (!canon) {
      throw new IllegalStateException(
        "The chat model is not configured properly. The value could not be turned into an API model id: \"${base}\"."
      )
    }
    return canon
  }

  /**
   * True for {@code gpt-5*} chat models (including dated ids); false for {@code o1}/{@code o3}/{@code o4} reasoning lines.
   */
  private static boolean modelIsGpt5Family(String model) {
    String m = normalizeModelIdForHeuristics(model)
    if (!m) {
      return false
    }
    if (m.startsWith('o1') || m.startsWith('o3') || m.startsWith('o4')) {
      return false
    }
    if (m.contains('gpt-5') || m.contains('gpt_5')) {
      return true
    }
    return Pattern.compile('(?i)gpt[^a-z0-9]*5').matcher(m).find()
  }

  /**
   * Max completion tokens for {@code TranslateContentItem} inner {@code /v1/chat/completions} calls (smaller → faster stop).
   * Default {@code 8192}; clamp {@code 1024–32768}. Override: {@code -Daiassistant.translateContentItemMaxOutTokens=4096}.
   */
  static int resolveTranslateContentItemMaxOutTokens() {
    try {
      def p = System.getProperty('aiassistant.translateContentItemMaxOutTokens')?.toString()?.trim()
      if (p) {
        int v = Integer.parseInt(p)
        return Math.max(1024, Math.min(32_768, v))
      }
    } catch (Throwable ignored) {
    }
    return 8192
  }

  /**
   * When bundled inner tools omit {@code llmModel}, pick a **smaller** model in the **same** OpenAI family as
   * {@code defaultChatModel} (main chat): e.g. {@code gpt-5-2025-08-07} → {@code gpt-5-nano}, {@code gpt-4o} → {@code gpt-4o-mini}.
   * Used by {@code TransformContentSubgraph} and {@code TranslateContentItem}.
   */
  static String transformSubgraphDefaultInnerModel(String defaultChatModel) {
    String raw = (defaultChatModel ?: '').trim()
    String m = normalizeModelIdForHeuristics(raw)
    if (!m) {
      throw new IllegalStateException(
        'The LLM model is not configured properly: the main chat model is missing, so Translate/Transform subgraph cannot choose an inner completion model. Set the agent chat model, or pass llmModel (or model) on the tool input.'
      )
    }
    if (modelIsGpt5Family(raw)) {
      String pick = m.contains('nano') ? raw : 'gpt-5-nano'
      String c = llmCanonicalizeApiModelToken(pick)
      if (!c) {
        throw new IllegalStateException(
          'The LLM model is not configured properly: could not derive an inner tools-loop model id from the main chat model.'
        )
      }
      return c
    }
    if (m.startsWith('o1') || m.startsWith('o3') || m.startsWith('o4')) {
      if (m.contains('mini')) {
        String c = llmCanonicalizeApiModelToken(raw)
        if (!c) {
          throw new IllegalStateException(
            'The LLM model is not configured properly: could not normalize the main chat model to an inner tools-loop model id.'
          )
        }
        return c
      }
      if (m.startsWith('o4')) {
        return 'o4-mini'
      }
      if (m.startsWith('o3')) {
        return 'o3-mini'
      }
      return 'o1-mini'
    }
    if (m.contains('gpt-4') || m.contains('gpt4') || m.contains('4o')) {
      if (m.contains('mini') && m.contains('4o')) {
        String c = llmCanonicalizeApiModelToken(raw)
        if (!c) {
          throw new IllegalStateException(
            'The LLM model is not configured properly: could not normalize the main chat model to an inner tools-loop model id.'
          )
        }
        return c
      }
      return 'gpt-4o-mini'
    }
    String c2 = llmCanonicalizeApiModelToken(raw)
    if (!c2) {
      throw new IllegalStateException(
        'The LLM model is not configured properly: the main chat model could not be normalized to a chat wire model id.'
      )
    }
    return c2
  }

  /**
   * Image model for logging only: returns canonical id or {@code null} when the agent/request sent no {@code imageModel}.
   * No JVM-side override; only the request value is considered.
   */
  static String imageModelFromRequestOrNull(String fromRequest) {
    String base = (fromRequest ?: '').toString().trim()
    if (!base) {
      return null
    }
    String canon = llmCanonicalizeApiModelToken(base)
    if (!canon) {
      throw new IllegalStateException(
        "The GenerateImage model is not configured properly. The value could not be turned into an API model id: \"${base}\"."
      )
    }
    return normalizeImagesApiModelId(canon)
  }

  /**
   * OpenAI Images API model id (e.g. {@code gpt-image-1}). Source: agent **{@code <imageModel>}** or POST **{@code imageModel}** only.
   * Canonicalized via {@link #normalizeImagesApiModelId(String)}.
   */
  static String resolveImageModel(String fromRequest) {
    String base = (fromRequest ?: '').toString().trim()
    if (!base) {
      throw new IllegalStateException(
        'The GenerateImage model is not configured properly. Set imageModel on the agent (ui.xml element imageModel) or pass imageModel on the chat request JSON body.'
      )
    }
    String canon = llmCanonicalizeApiModelToken(base)
    if (!canon) {
      throw new IllegalStateException(
        "The GenerateImage model is not configured properly. The value could not be turned into an API model id: \"${base}\"."
      )
    }
    return normalizeImagesApiModelId(canon)
  }

  /** Per-request expert skill URLs from the client (see {@code aiassistant.expertSkills} request attribute). */
  List<Map> readExpertSkillSpecsFromRequest() {
    try {
      def v = request?.getAttribute('aiassistant.expertSkills')
      if (v instanceof List) {
        List<Map> out = new ArrayList<>()
        for (Object o : (List) v) {
          if (o instanceof Map) {
            out.add((Map) o)
          }
        }
        return out
      }
    } catch (Throwable ignored) {}
    return []
  }

  /**
   * Builds the Spring AI chat client + tools via {@link StudioAiLlmRuntime} ({@link OpenAiSpringAiLlmRuntime}, Claude, script hosts).
   */
  private Map buildSpringAiChatClient(
    String agentId,
    String chatId,
    String llmRaw,
    String chatModelParam,
    String llmApiKeyFromRequest = null,
    Closure toolProgressListener = null,
    String imageModelParam = null,
    boolean fullSuppressRepoWrites = false,
    String protectedFormItemPath = null,
    boolean enableTools = true,
    String imageGeneratorParam = null
  ) {
    def converter = { Object result, java.lang.reflect.Type returnType -> toolResultToWireString(result, returnType) }
    /** Spring AI tool callbacks run on Reactor/HTTP-client threads; copy servlet SecurityContext for Studio permission checks. */
    def securityContextForTools = null
    try {
      def ctx = SecurityContextHolder.getContext()
      if (ctx != null && ctx.getAuthentication() != null) {
        def copy = SecurityContextHolder.createEmptyContext()
        copy.setAuthentication(ctx.getAuthentication())
        securityContextForTools = copy
      }
    } catch (Throwable ignored) {}
    def studioOps = new StudioToolOperations(request, applicationContext, params, securityContextForTools)
    String llmNorm = StudioAiLlmKind.normalize(llmRaw)
    Collection agentToolSubset = null
    try {
      def raw = request?.getAttribute('aiassistant.agentEnabledBuiltInTools')
      if (raw instanceof Collection && !((Collection) raw).isEmpty()) {
        agentToolSubset = (Collection) raw
      }
    } catch (Throwable ignoredSubset) {}
    def req = new StudioAiRuntimeBuildRequest(
      orchestration: this,
      toolResultConverter: converter,
      studioOps: studioOps,
      studioServletRequest: request,
      agentId: agentId,
      chatId: chatId,
      llmNormalized: llmNorm,
      llmModelParam: chatModelParam,
      llmApiKeyFromRequest: llmApiKeyFromRequest,
      toolProgressListener: toolProgressListener,
      imageModelParam: imageModelParam,
      imageGeneratorParam: imageGeneratorParam,
      fullSuppressRepoWrites: fullSuppressRepoWrites,
      protectedFormItemPath: protectedFormItemPath,
      enableTools: enableTools,
      agentEnabledBuiltInTools: agentToolSubset
    )
    return StudioAiLlmRuntimeFactory.runtimeFor(llmNorm).buildSessionBundle(req)
  }

  private boolean requestAuthoringIntentExpansionEnabled() {
    try {
      def v = request?.getAttribute('aiassistant.authoringIntentExpansion')
      if (v != null) {
        return AuthoringPreviewContext.parseAuthoringIntentExpansion(v)
      }
    } catch (Throwable ignored) {
    }
    return false
  }

  /**
   * After pass-1 recipe routing fails: allow LLM intent expansion + rematch when the turn is an expansion
   * candidate (unless the request explicitly disables {@code authoringIntentExpansion}).
   */
  private boolean effectiveAuthoringIntentExpansionRematchEnabled(String bodyPrompt) {
    try {
      def v = request?.getAttribute('aiassistant.authoringIntentExpansion')
      if (v != null) {
        return AuthoringPreviewContext.parseAuthoringIntentExpansion(v)
      }
    } catch (Throwable ignored) {
    }
    return AuthoringPreviewContext.isAuthoringIntentExpansionCandidate((bodyPrompt ?: '').toString())
  }

  /**
   * OpenAI authoring <strong>system</strong> text only — same assembly as {@link #authoringPrompt} uses for
   * {@link SystemMessage}, without servlet {@code request}. Used by the autonomous worker (and keeps stream + headless aligned).
   *
   * @param expertSkillSpecsNormalized maps with {@code skillId}, {@code name}, {@code url}, {@code description}
   *        (e.g. {@link plugins.org.craftercms.aiassistant.rag.ExpertSkillVectorRegistry#normalizeRequestExpertSkills}); may be null or empty
   */
  static String llmAuthoringSystemOnlyForHeadless(
    String siteId,
    String userTextForRagAdjust,
    StudioToolOperations studioOps,
    String llmApiKey,
    boolean fullSuppressRepoWrites,
    String protectedFormItemPathNormalized,
    boolean toolSchemasOnApi,
    List expertSkillSpecsNormalized
  ) {
    String site = (siteId ?: '').toString().trim()
    String utEarly = (userTextForRagAdjust ?: '').toString()
    String normProt = AuthoringPreviewContext.normalizeRepoPath(protectedFormItemPathNormalized)
    def core = toolSchemasOnApi ? ToolPrompts.getLlm_AUTHORING_INSTRUCTIONS() : ToolPrompts.getLlm_CHAT_ONLY_SYSTEM()
    core = PluginRagVectorRegistry.adjustAuthoringCore(core, site, utEarly, studioOps, llmApiKey, toolSchemasOnApi)
    String sys = core
    if (fullSuppressRepoWrites) {
      sys += ToolPrompts.getLlm_FORM_ENGINE_SUPPRESS_REPO_WRITES()
    } else if (normProt) {
      sys += ToolPrompts.formEngineProtectedItemAddendum(normProt)
    }
    if (site) {
      if (toolSchemasOnApi) {
        if (fullSuppressRepoWrites) {
          sys += "\n\nCurrent CrafterCMS site id: \"${site}\". Always pass siteId=\"${site}\" on GetContent, ListContentDependencyScope, ListStudioContentTypes, ListPagesAndComponents, GetContentTypeFormDefinition, and update_* tools unless the user explicitly names another site. Never use \"default\" as siteId."
        } else {
          sys += "\n\nCurrent CrafterCMS site id: \"${site}\". Always pass siteId=\"${site}\" on GetContent, ListContentDependencyScope, TranslateContentItem, TranslateContentBatch, WriteContent, ListStudioContentTypes, ListPagesAndComponents, GetContentTypeFormDefinition, publish_content, revert_change, and update_* tools unless the user explicitly names another site. Never use \"default\" as siteId."
        }
      } else {
        sys += "\n\nCurrent CrafterCMS site id: \"${site}\"."
      }
    }
    if (toolSchemasOnApi) {
      List exList = expertSkillSpecsNormalized
      if (exList != null && !exList.isEmpty()) {
        sys += ToolPrompts.expertSkillsRagAppendix(exList)
      }
    }
    if (toolSchemasOnApi) {
      sys += PlanOrchestration.machineInstructionsAddendum()
    }
    sys
  }

  /**
   * Includes active site id when available. When {@code toolSchemasOnApi} is false, system text matches OpenAI requests
   * that omit function tools ({@code <enableTools>false</enableTools>}).
   */
  private Prompt authoringPrompt(
    String userText,
    boolean fullSuppressRepoWrites = false,
    String protectedFormItemPathNormalized = null,
    boolean toolSchemasOnApi = true,
    StudioToolOperations studioOps = null,
    String llmApiKey = null
  ) {
    def site = ''
    try {
      site = request?.getAttribute('aiassistant.siteId')?.toString()?.trim() ?: ''
      if (!site) site = request?.getParameter('siteId')?.toString()?.trim() ?: ''
      if (!site) site = request?.getParameter('crafterSite')?.toString()?.trim() ?: ''
      if (!site && params != null) {
        try {
          site = params['siteId']?.toString()?.trim() ?: ''
        } catch (Throwable e) {
          try {
            site = params.siteId?.toString()?.trim() ?: ''
          } catch (Throwable e2) {}
        }
      }
    } catch (Throwable ignored) {}

    def utEarly = (userText ?: '').toString()
    List exList = []
    try {
      def raw = request?.getAttribute('aiassistant.expertSkills')
      if (raw instanceof List && !((List) raw).isEmpty()) {
        exList = (List) raw
      }
    } catch (Throwable ignored) {}
    String sys = llmAuthoringSystemOnlyForHeadless(
      site,
      utEarly,
      studioOps,
      llmApiKey,
      fullSuppressRepoWrites,
      protectedFormItemPathNormalized,
      toolSchemasOnApi,
      exList
    )
    def ut = utEarly
    if (toolSchemasOnApi) {
      ut = ToolPrompts.getLlm_USER_MESSAGE_TOOLS_POLICY_PREFIX() + ut
    }
    return new Prompt([
      new SystemMessage(sys),
      new UserMessage(ut)
    ])
  }

  /**
   * Build Chat Completions wire messages (tools-off {@link RestClient} path) without going through
   * {@link OpenAiChatModel#createRequest} + {@code ModelOptionsUtils.merge}, which can drop the
   * {@code stream} flag on {@link ChatCompletionRequest} (record + merge) and break both streaming
   * (hung read) and non-streaming (SSE body parsed as JSON → JsonEOFException).
   */
  private static List<ChatCompletionMessage> chatCompletionMessagesForApi(Prompt prompt) {
    def instr = null
    try {
      instr = prompt.getInstructions()
    } catch (Throwable ignored) {
      try {
        instr = prompt.instructions
      } catch (Throwable ignored2) {}
    }
    if (!instr) {
      return []
    }
    def out = []
    instr.each { msg ->
      ChatCompletionMessage.Role role = ChatCompletionMessage.Role.USER
      try {
        def n = msg?.getClass()?.name ?: ''
        if (n.endsWith('SystemMessage') || n.contains('.SystemMessage')) {
          role = ChatCompletionMessage.Role.SYSTEM
        } else if (n.endsWith('UserMessage') || n.contains('.UserMessage')) {
          role = ChatCompletionMessage.Role.USER
        } else if (n.endsWith('AssistantMessage') || n.contains('.AssistantMessage')) {
          role = ChatCompletionMessage.Role.ASSISTANT
        }
      } catch (Throwable ignored) {}
      def text = messagePlainTextForLog(msg)
      out << new ChatCompletionMessage(text, role)
    }
    out
  }

  /** One {@code data:} line from Tools-loop chat SSE — assistant text delta. */
  private static String streamChunkDeltaText(Object root) {
    if (!(root instanceof Map)) {
      return ''
    }
    Map m = root as Map
    def choices = m.get('choices')
    if (!(choices instanceof List) || choices.isEmpty()) {
      return ''
    }
    def c0 = choices[0]
    if (!(c0 instanceof Map)) {
      return ''
    }
    def delta = ((Map) c0).get('delta')
    if (!(delta instanceof Map)) {
      return ''
    }
    def content = ((Map) delta).get('content')
    if (content instanceof CharSequence) {
      return content.toString()
    }
    if (content instanceof List) {
      StringBuilder sb = new StringBuilder()
      for (def part : (List) content) {
        if (part instanceof Map) {
          Map pm = part as Map
          def t = pm.get('text')
          if (t != null) {
            sb.append(t.toString())
          }
        }
      }
      return sb.toString()
    }
    return content != null ? content.toString() : ''
  }

  private static String streamChunkFinishReason(Object root) {
    if (!(root instanceof Map)) {
      return ''
    }
    def choices = ((Map) root).get('choices')
    if (!(choices instanceof List) || choices.isEmpty()) {
      return ''
    }
    def c0 = choices[0]
    if (!(c0 instanceof Map)) {
      return ''
    }
    def fr = ((Map) c0).get('finish_reason')
    return fr != null ? fr.toString() : ''
  }

  private static String streamChunkProviderErrorMessage(Object root) {
    if (!(root instanceof Map)) {
      return ''
    }
    def err = ((Map) root).get('error')
    if (err instanceof Map) {
      def em = ((Map) err).get('message')
      return em != null ? em.toString() : err.toString()
    }
    if (err != null) {
      return err.toString()
    }
    return ''
  }

  /**
   * Reads OpenAI {@code text/event-stream} chat.completions chunks and forwards assistant deltas as Studio SSE.
   */
  private static void copyUpstreamSseChatCompletionsToStudio(
    InputStream upstream,
    OutputStream out,
    String agentId,
    String model
  ) {
    if (upstream == null) {
      throw new IllegalStateException('Tools-loop chat (stream): empty response body')
    }
    def slurper = new JsonSlurper()
    boolean completedSent = false
    BufferedReader br = null
    try {
      br = new BufferedReader(new InputStreamReader(upstream, StandardCharsets.UTF_8))
      String line
      while ((line = br.readLine()) != null) {
        if (!line.startsWith('data:')) {
          continue
        }
        def payload = line.substring(5).trim()
        if (!payload) {
          continue
        }
        if ('[DONE]' == payload) {
          break
        }
        Object chunk
        try {
          chunk = slurper.parseText(payload)
        } catch (Throwable pe) {
          log.warn(
            'Tools-loop tools-off SSE: skip unparseable line agentId={} model={} line=\n{}',
            agentId,
            model,
            AiHttpProxy.elideForLog(payload, 500)
          )
          continue
        }
        def errMsg = streamChunkProviderErrorMessage(chunk)
        if (errMsg) {
          def ev = [text: '', metadata: [error: true, completed: true, message: 'Chat host: ' + errMsg]]
          synchronized (out) {
            out.write(("data: ${JsonOutput.toJson(ev)}\n\n").getBytes(StandardCharsets.UTF_8))
            out.flush()
          }
          completedSent = true
          return
        }
        def delta = streamChunkDeltaText(chunk)
        if (delta) {
          synchronized (out) {
            out.write(("data: ${JsonOutput.toJson([text: delta, metadata: [:]])}\n\n").getBytes(StandardCharsets.UTF_8))
            out.flush()
          }
        }
        def fr = streamChunkFinishReason(chunk)
        if (finishReasonImpliesStreamDone(fr)) {
          synchronized (out) {
            out.write(("data: ${JsonOutput.toJson([text: '', metadata: [completed: true]])}\n\n").getBytes(StandardCharsets.UTF_8))
            out.flush()
          }
          completedSent = true
          break
        }
      }
    } finally {
      try {
        br?.close()
      } catch (Throwable ignored) {}
    }
    if (!completedSent) {
      synchronized (out) {
        out.write(("data: ${JsonOutput.toJson([text: '', metadata: [completed: true]])}\n\n").getBytes(StandardCharsets.UTF_8))
        out.flush()
      }
    }
  }

  private static Map wireMessageFromChatCompletionMessage(ChatCompletionMessage cm) {
    if (cm == null) {
      return [:]
    }
    def roleStr = cm.role() != null ? cm.role().name().toLowerCase() : 'user'
    def c = null
    try {
      c = cm.content()
    } catch (Throwable ignored) {}
    def text = c != null ? c.toString() : ''
    return [role: roleStr, content: text]
  }

  private static List<Map> buildWireToolsFromCallbacks(List tools) {
    def out = []
    if (!tools) {
      return out
    }
    def slurper = new JsonSlurper()
    tools.each { t ->
      if (t instanceof FunctionToolCallback) {
        def td = t.getToolDefinition()
        if (td == null) {
          return
        }
        Object paramsObj
        try {
          def schema = td.inputSchema()
          paramsObj = schema ? slurper.parseText(schema.toString()) : null
        } catch (Throwable ignored) {
          paramsObj = null
        }
        if (!(paramsObj instanceof Map)) {
          paramsObj = [type: 'object', properties: [:]]
        }
        out << [
          type: 'function',
          function: [
            name: td.name(),
            description: (td.description() ?: '') as String,
            parameters: paramsObj
          ]
        ]
      }
    }
    out
  }

  private static Map<String, FunctionToolCallback> toolCallbacksByName(List tools) {
    Map<String, FunctionToolCallback> m = new LinkedHashMap<>()
    if (!tools) {
      return m
    }
    tools.each { t ->
      if (t instanceof FunctionToolCallback) {
        def td = t.getToolDefinition()
        if (td?.name()) {
          m.put(td.name(), (FunctionToolCallback) t)
        }
      }
    }
    m
  }

  /**
   * True when assistant {@code content} reads like an execution plan (## Plan / ## Revised Plan / …) plus concrete
   * next steps, but the API message had no {@code tool_calls} — used to inject a one-shot recovery user nudge.
   * Models often use {@code ## Revised Plan} or bullet lists about CSS/FTL without echoing wire tool names.
   */
  private static boolean assistantProsePromisedToolsButOmittedCalls(String assistFlat) {
    if (!assistFlat?.trim()) {
      return false
    }
    def a = assistFlat
    // ## Plan Execution wrap-up after tools ran is not a stalled ## Plan missing tool_calls.
    if (a.contains('## Plan Execution') &&
      assistantProseClaimsTurnCompleteDespitePlanBullets(a) &&
      !Pattern.compile('(?im)^##\\s+Plan\\s*$').matcher(a).find() &&
      !a.contains('## Revised Plan')) {
      return false
    }
    boolean hasPlanHeading =
      a.contains('## Plan') ||
        a.contains('## Revised Plan') ||
        a.contains('## Next Steps') ||
        (Pattern.compile('(?is)##\\s+(plan|revised\\s+plan|next\\s+steps)\\b').matcher(a).find())
    if (!hasPlanHeading) {
      return false
    }
    boolean namedWireTool =
      a.contains('GetContent') ||
        a.contains('FetchHttpUrl') ||
        a.contains('WriteContent') ||
        a.contains('update_template') ||
        a.contains('update_content') ||
        a.contains('ListStudioContentTypes') ||
        a.contains('ListContentDependencyScope') ||
        a.contains('ListContentTranslationScope') ||
        a.contains('GetPreviewHtml') ||
        a.contains("Let's proceed") ||
        a.contains("Let's start") ||
        a.contains('🛠️') ||
        a.contains('`tool_calls`')
    boolean planBulletsWithTemplateOrCss =
      a.contains('📋') &&
        (
          a.contains('.css') ||
            a.contains('CSS') ||
            a.contains('.ftl') ||
            a.contains('FreeMarker') ||
            a.toLowerCase(Locale.ROOT).contains('stylesheet') ||
            a.toLowerCase(Locale.ROOT).contains('template')
        )
    return namedWireTool || planBulletsWithTemplateOrCss
  }

  /** Model printed a fake {@code GenerateImage} JSON/code block without {@code tool_calls}. */
  private static boolean assistantProseFakedGenerateImageWithoutCalls(String assistFlat) {
    if (!assistFlat?.trim()) {
      return false
    }
    boolean mentionsTool =
      assistFlat.contains('GenerateImage') ||
        (assistFlat.contains('generate') && assistFlat.toLowerCase(Locale.ROOT).contains('image'))
  boolean fencedPayload =
      assistFlat.contains('```json') ||
        assistFlat.contains('```\n{') ||
        assistFlat.contains('```\n{"prompt"')
    return mentionsTool && fencedPayload
  }

  /**
   * Assistant claimed wrap-up (### Execution / ✅ / successfully updated) without ❌ — used to avoid a
   * tools-required nudge when repository work already ran but 📋 lines still mention optional FTL/CSS.
   */
  private static boolean assistantProseClaimsTurnCompleteDespitePlanBullets(String assistFlat) {
    if (!assistFlat?.trim()) {
      return false
    }
    String a = assistFlat
    boolean hasExec =
      a.contains('### Execution') ||
        a.contains('## Plan Execution') ||
        (Pattern.compile('(?is)\\bexecution\\b').matcher(a).find() && a.contains('✅'))
    boolean hasSuccess =
      a.contains('✅') &&
        (
          a.toLowerCase(Locale.ROOT).contains('successfully') ||
            a.toLowerCase(Locale.ROOT).contains('has been updated') ||
            a.toLowerCase(Locale.ROOT).contains('updated to') ||
            a.toLowerCase(Locale.ROOT).contains('has been written')
        )
    boolean hasFailure =
      a.contains('❌') ||
        Pattern.compile('(?i)\\b(failed|could not|unable to)\\b').matcher(a).find()
    return hasExec && hasSuccess && !hasFailure
  }

  private static String authorVisibleFromPromptText(String promptText) {
    String current = AuthoringPreviewContext.extractAuthorCurrentRequestVisible(promptText ?: '')
    if (current?.trim()) {
      return current.trim()
    }
    String flat = (promptText ?: '').toString()
    try {
      flat = AuthoringPreviewContext.stripStudioInjectedPromptBlocks(flat)
    } catch (Throwable ignored) {
    }
    return (flat ?: '').trim()
  }

  /**
   * Applies {@code orchestration.toolsLoopAllowlist} from matched-recipe telemetry when the author
   * did not trigger {@code toolsLoopAllowlistBypassIfAuthorMentions}.
   */
  private static List effectiveToolsForIntentRecipe(List tools, Map intentTel, String authorVisible, String agentId) {
    if (!(intentTel instanceof Map) || !'matched'.equals(intentTel.get('outcome')?.toString())) {
      return tools
    }
    Object allowObj = intentTel.get('toolsLoopAllowlist')
    if (!(allowObj instanceof List) || ((List) allowObj).isEmpty()) {
      return tools
    }
    List<String> bypassKw = []
    Object bypassObj = intentTel.get('toolsLoopAllowlistBypassIfAuthorMentions')
    if (bypassObj instanceof List) {
      for (Object o : (List) bypassObj) {
        String s = o?.toString()?.trim()
        if (s) {
          bypassKw.add(s)
        }
      }
    }
    if (AuthoringIntentRecipeCatalog.authorVisibleMatchesOrchestrationBypass(authorVisible, bypassKw)) {
      return tools
    }
    Set<String> allowNames = new LinkedHashSet<>()
    for (Object o : (List) allowObj) {
      String n = o?.toString()?.trim()
      if (n) {
        allowNames.add(n)
      }
    }
    List filtered = filterToolCallbacksAllowlist(tools, allowNames)
    if (filtered == null || filtered.isEmpty()) {
      String ridEmpty = intentTel.get('recipeId')?.toString()?.trim() ?: ''
      if ('generate_image'.equals(ridEmpty) && allowNames.contains('GenerateImage')) {
        intentTel.put('generateImageToolUnavailable', Boolean.TRUE)
        log.warn(
          'Tools-loop: generate_image recipe but GenerateImage is not registered (set imageModel on agent or request) agentId={}',
          agentId
        )
      } else {
        log.warn(
          'Tools-loop: recipe {} toolsLoopAllowlist matched no registered tools ({} requested) — using empty tool list agentId={}',
          ridEmpty ?: '(unknown)',
          allowNames.size(),
          agentId
        )
      }
      return []
    }
    String rid = intentTel.get('recipeId')?.toString()?.trim() ?: ''
    log.info(
      'Tools-loop: recipe {} toolsLoopAllowlist active ({} of {} tools) agentId={}',
      rid ?: '(unknown)',
      filtered.size(),
      tools?.size() ?: 0,
      agentId
    )
    filtered
  }

  private static List filterToolCallbacksExcludeNames(List tools, Set<String> excludeNames) {
    if (!tools || !excludeNames) {
      return tools ?: []
    }
    List out = []
    tools.each { t ->
      if (t instanceof FunctionToolCallback) {
        String n = t.getToolDefinition()?.name()
        if (n && !excludeNames.contains(n)) {
          out << t
        }
      }
    }
    out
  }

  /** Image bitmap turn — never offer {@code GenerateTextNoTools} as a substitute for {@code GenerateImage}. */
  private static List applyGenerateImageTurnToolPolicy(List tools, Map intentTel, String fullWireUserPrompt, String agentId) {
    String rid = (intentTel instanceof Map) ? intentTel.get('recipeId')?.toString()?.trim() : ''
    boolean imageTurn =
      'generate_image'.equals(rid) ||
      AuthoringPreviewContext.authorCurrentRequestLooksLikeImageOnlyGenerate(fullWireUserPrompt ?: '')
    if (!imageTurn) {
      return tools
    }
    List out = filterToolCallbacksExcludeNames(tools, ['GenerateTextNoTools'] as Set)
    if (!wireToolsIncludeNamedTool(buildWireToolsFromCallbacks(out), 'GenerateImage')) {
      if (intentTel instanceof Map) {
        intentTel.put('generateImageToolUnavailable', Boolean.TRUE)
      }
      log.warn(
        'Tools-loop: image-only turn but GenerateImage tool missing (imageModel / imageGenerator not configured) agentId={} recipeId={}',
        agentId,
        rid ?: '(signal)'
      )
    }
    return out
  }

  private static String synthesizeGenerateImageUnavailableMarkdown() {
    return '''## Image generation unavailable

Studio matched **Generate image (bitmap)** for this turn, but the **GenerateImage** tool is not available in this session.

**Check:**
- **Project Tools → AI Assistant → Agents** — save the chat agent with an **Image model** (stored in `config/studio/ai-assistant/agents.json`), or
- **OpenAI API key** — set `OPENAI_API_KEY` (or the site LLM key your Studio uses for `openAI`).

OpenAI chat with no explicit image model uses **`gpt-image-1`** by default when generation is enabled. Retry the same prompt after keys/catalog are in place.'''
  }

  /**
   * When a matched recipe sets {@code toolsLoopDisable}, turn off CMS tools for this turn (e.g. {@code llm_research}).
   */
  private static void applyIntentRecipeRouteEffects(Map springAi, Map route) {
    if (!(springAi instanceof Map)) {
      return
    }
    Map tel =
      (route?.intentRecipeRoutingTelemetry instanceof Map) ?
        (Map) route.intentRecipeRoutingTelemetry :
        ((springAi.intentRecipeRoutingTelemetry instanceof Map) ?
          (Map) springAi.intentRecipeRoutingTelemetry :
          null)
    if (!(tel instanceof Map) || !'matched'.equals(tel.get('outcome')?.toString())) {
      return
    }
    if (Boolean.TRUE.equals(tel.get('toolsLoopDisable'))) {
      springAi.useTools = false
      log.info(
        'Tools-loop: recipe {} toolsLoopDisable — CMS tools off for this turn agentId={}',
        tel.get('recipeId')?.toString()?.trim() ?: '(unknown)',
        springAi.agentId ?: ''
      )
      return
    }
  }

  private static List filterToolCallbacksAllowlist(List tools, Set<String> allowNames) {
    if (!tools || !allowNames) {
      return []
    }
    List out = []
    tools.each { t ->
      if (t instanceof FunctionToolCallback) {
        String n = t.getToolDefinition()?.name()
        if (n && allowNames.contains(n)) {
          out << t
        }
      }
    }
    out
  }

  private static boolean isInternalAiAssistantWireUserMessage(String flat) {
    if (!flat?.trim()) {
      return true
    }
    String t = flat.trim()
    return t.startsWith('[aiassistant:') ||
      t.startsWith('[Studio — intent recipe') ||
      t.startsWith('[Studio — recipe intent router') ||
      t.startsWith('[Studio — intent recipe catalog') ||
      t.startsWith('[Studio — matched authoring intent') ||
      t.startsWith('[Studio — recipe engine prefetch]') ||
      t.startsWith('[Studio — skip redundant GetContent')
  }

  /**
   * First non-internal user message in the wire (author request), not recovery nudges appended later.
   */
  private static String firstAuthorVisibleUserFromWire(List wireMessages) {
    if (!(wireMessages instanceof List)) {
      return ''
    }
    for (Object o : (List) wireMessages) {
      if (!(o instanceof Map)) {
        continue
      }
      Map m = (Map) o
      if (!'user'.equals(m.get('role')?.toString())) {
        continue
      }
      String flat = flattenWireUserContent(m.get('content')) ?: ''
      try {
        flat = AuthoringPreviewContext.stripStudioInjectedPromptBlocks(flat)
      } catch (Throwable ignored) {
      }
      flat = (flat ?: '').trim()
      if (flat && !isInternalAiAssistantWireUserMessage(flat)) {
        return flat
      }
    }
    return ''
  }

  private static String authorVisibleRequestFromWire(List wireMessages) {
    String first = firstAuthorVisibleUserFromWire(wireMessages)
    if (first) {
      return first
    }
    Map u = lastUserWireMessage(wireMessages)
    if (!(u instanceof Map)) {
      return ''
    }
    String flat = flattenWireUserContent(u.get('content')) ?: ''
    try {
      flat = AuthoringPreviewContext.stripStudioInjectedPromptBlocks(flat)
    } catch (Throwable ignored) {
    }
    return (flat ?: '').trim()
  }

  /**
   * Author line for outcome-phrase parsing — not recipe prefetch, expansion bullets, or Studio metadata.
   */
  private static String authorVisibleTailForOutcomePhrase(String authorVisible) {
    if (!authorVisible?.trim()) {
      return ''
    }
    String v = authorVisible.trim()
    int expIdx = v.indexOf(AUTHORING_INTENT_EXPANSION_BLOCK_HEADER)
    if (expIdx >= 0) {
      int sep = v.indexOf('\n---\n\n', expIdx)
      if (sep >= 0) {
        v = v.substring(sep + 5).trim()
      }
    }
    try {
      v = AuthoringPreviewContext.stripStudioInjectedPromptBlocks(v) ?: v
    } catch (Throwable ignored) {
    }
    v = (v ?: '').trim()
    if (!v) {
      return ''
    }
    if (v.contains('[Studio — recipe engine prefetch]')) {
      int prefetchEnd = v.lastIndexOf('```')
      if (prefetchEnd >= 0 && prefetchEnd + 3 < v.length()) {
        v = v.substring(prefetchEnd + 3).trim()
      }
    }
    String[] lines = v.split(/\r?\n/)
    for (int i = lines.length - 1; i >= 0; i--) {
      String line = (lines[i] ?: '').trim()
      if (!line) {
        continue
      }
      if (line.startsWith('[') || line.startsWith('```') || line.startsWith('---')) {
        continue
      }
      if (line.length() <= 600) {
        return line
      }
    }
    return v.length() <= 600 ? v : v.substring(Math.max(0, v.length() - 600))
  }

  private static String extractAuthorFieldLabelFromLine(String line) {
    String tail = (line ?: '').trim()
    if (!tail) {
      return ''
    }
    def mTo = (tail =~ /(?is)^(?:update|change|set|replace)\s+(?:the\s+)?(.+?)\s+to\s+.+$/)
    if (mTo.matches()) {
      return mTo.group(1)?.trim() ?: ''
    }
    def mWith = (tail =~ /(?is)^(?:update|change|set|replace)\s+(?:the\s+)?(.+?)\s+with\s+.+$/)
    if (mWith.matches()) {
      return mWith.group(1)?.trim() ?: ''
    }
    def mPutIn = (tail =~ /(?is)\bput\b(?:\s+(?:them|it|those|that|the))?\s+(?:in|into)\s+(?:the\s+)?(.+?)\s*\.?\s*$/)
    if (mPutIn.find()) {
      return mPutIn.group(1)?.trim() ?: ''
    }
    def mAddTo = (tail =~ /(?is)\badd\b.+\bto\s+(?:the\s+)?(.+?)\s*\.?\s*$/)
    if (mAddTo.find()) {
      return mAddTo.group(1)?.trim() ?: ''
    }
    return ''
  }

  /** Field label from "update the hero title to …" / "put … in the hero title" (not the new value). */
  private static String extractAuthorFieldLabelPhrase(String authorVisible) {
    String fromTail = extractAuthorFieldLabelFromLine(authorVisibleTailForOutcomePhrase(authorVisible))
    if (fromTail) {
      return fromTail
    }
    if (!(authorVisible ?: '').contains('User:')) {
      return ''
    }
    String found = ''
    def m = (authorVisible =~ /(?is)User:\s*([\s\S]*?)(?=\n\nAssistant:|\n\nUser:|\n\nCurrent request:|\z)/)
    while (m.find()) {
      String block = (m.group(1) ?: '').trim()
      String firstLine = block.split(/\r?\n/).find { (it ?: '').trim() } ?: ''
      String label = extractAuthorFieldLabelFromLine(firstLine)
      if (label) {
        found = label
      }
    }
    return found
  }

  /** True when the author already named a concrete field-level edit (skip pre-tools intent expansion). */
  private static boolean authorRequestIsConcreteFieldEdit(String authorVisible) {
    String tail = authorVisibleTailForOutcomePhrase(authorVisible)
    if (!tail) {
      return false
    }
    if ((tail =~ /(?is)^(?:update|change|set|replace)\s+(?:the\s+)?[\w\s'-]+\s+to\s+\S.+$/).matches() ||
      (tail =~ /(?is)^(?:update|change|set|replace)\s+(?:the\s+)?[\w\s'-]+\s+with\s+\S.+$/).matches()) {
      return true
    }
    return AuthoringPreviewContext.anchoredSiteXmlFieldPlacementIntent(authorVisible ?: '')
  }

  /** “Add … to my &lt;field label&gt;” — {@code to} names the field, not publishable copy. */
  private static boolean authorPlacementRequestsFieldTargetNotLiteralContent(String authorVisible) {
    String tail = authorVisibleTailForOutcomePhrase(authorVisible)
    String scan = [tail, (authorVisible ?: '').toString()].findAll { it?.trim() }.join('\n')
    if (!scan?.trim()) {
      return false
    }
    return (scan =~ /(?is)\b(add|put|place|insert|set|update)\b.+\bto\b.+\b(my\s+)?(hero|title|headline|subtitle|body|copy|text|field)\b/).find()
  }

  private static boolean authorRequestNeedsPriorTurnContentResolution(String authorVisible) {
    String tail = authorVisibleTailForOutcomePhrase(authorVisible)
    String scan = [tail, (authorVisible ?: '').toString()].findAll { it?.trim() }.join('\n')
    return AuthoringPreviewContext.authorVisibleSuggestsPriorTurnContent(scan)
  }

  private static boolean outcomePhraseEqualsResolvedFieldLabel(String outcomePhrase, String fieldLabel) {
    if (!outcomePhrase?.trim() || !fieldLabel?.trim()) {
      return false
    }
    String a = outcomePhrase.trim().toLowerCase(Locale.ROOT)
    String b = fieldLabel.trim().toLowerCase(Locale.ROOT)
    return a == b || a.contains(b) || b.contains(a)
  }

  /**
   * Author needs externally resolved copy (lyrics lookup, fetch text) before a field write — not a literal hotpath value.
   */
  private static boolean authorRequestNeedsExternalContentResolution(String authorVisible) {
    String tail = authorVisibleTailForOutcomePhrase(authorVisible)
    String scan = [tail, (authorVisible ?: '').toString()].findAll { it?.trim() }.join('\n')
    if (!scan?.trim()) {
      return false
    }
    if ((scan =~ /(?is)\b(look\s*up|fetch|retrieve|get|find|search\s+for)\b.{0,160}\b(lyrics?|song|poem)\b/).find()) {
      return true
    }
    if ((scan =~ /(?is)\b(look\s*up|fetch|get)\b.{0,60}\b(lyrics?|text)\b/).find()) {
      return true
    }
    if ((scan =~ /(?is)\bwith\s+the\s+lyrics\s+of\b/).find()) {
      return true
    }
    if ((scan =~ /(?is)\b(?:lyrics?|song)\s+of\s+/).find()) {
      return true
    }
    if ((scan =~ /(?is)\b(lyrics?)\s+to\s+/).find() && (scan =~ /(?is)\band\s+update\b/).find()) {
      return true
    }
    return false
  }

  /** True when {@code phrase} is meta-instruction text, not CMS body copy to publish. */
  private static boolean outcomePhraseLooksLikeInstructionNotContent(String phrase) {
    String p = (phrase ?: '').trim()
    if (!p) {
      return true
    }
    String low = p.toLowerCase(Locale.ROOT)
    if ((low =~ /(?is)\b(look\s*up|fetch|get|find)\b/).find()) {
      return true
    }
    if ((low =~ /(?is)\b(and\s+)?update\s+(the\s+)?(hero\s+)?title\b/).find()) {
      return true
    }
    if ((low =~ /(?is)\bthe\s+lyrics\s+of\b/).find() || (low =~ /(?is)\blyrics?\s+of\b/).find()) {
      return true
    }
    if ((low =~ /(?is)\b(update|change|set|replace)\b/).find() &&
      (low =~ /(?is)\b(hero\s+title|page\s+title|field)\b/).find()) {
      return true
    }
    return false
  }

  /** Hotpath may write {@code phrase} only when it is literal author copy, not a deferred lookup instruction. */
  private static boolean isUsableHotpathOutcomePhrase(String phrase, String authorVisible) {
    String p = (phrase ?: '').trim()
    if (!p) {
      return false
    }
    if (authorRequestNeedsExternalContentResolution(authorVisible ?: '')) {
      return false
    }
    if (outcomePhraseLooksLikeInstructionNotContent(p)) {
      return false
    }
    if (p.contains('\n') && p.length() >= 24) {
      return true
    }
    return p.length() <= 280
  }

  /**
   * Author asked to translate/localize into another language — route to {@code translate_content_item}, not {@code modify_page_content}.
   */
  private static boolean authorRequestLooksLikeTranslateIntent(String cand, String visible) {
    String probe = (visible ?: '').trim() ?: (cand ?: '').trim()
    if (!probe) {
      return false
    }
    if (plainTextLooksLikeImageOnlyGenerateRequest((visible ?: '').trim() ?: probe)) {
      return false
    }
    String low = probe.toLowerCase(Locale.ROOT)
    return low =~ /(?s).*\b(translate|translation|localize|localise|localization|localisation)\b.*/
  }

  /**
   * Deterministic {@code modify_page_content} when anchored XML + field target is known (including lyrics lookup).
   */
  private static boolean intentRecipeDeterministicMatchForFieldEdit(
    String cand,
    String visible,
    String authorFieldLabelEarly,
    boolean concreteField
  ) {
    if (authorRequestLooksLikeTranslateIntent(cand, visible)) {
      return false
    }
    if ((authorFieldLabelEarly ?: '').trim() && concreteField) {
      if (authorRequestNeedsPriorTurnContentResolution(cand) || authorRequestNeedsPriorTurnContentResolution(visible)) {
        // Match the recipe even when abbreviated chat history omits tip bodies — tools loop can resolve copy.
        return true
      }
      return true
    }
    if (!authorRequestNeedsExternalContentResolution(cand) && !authorRequestNeedsExternalContentResolution(visible)) {
      return false
    }
    String label = (authorFieldLabelEarly ?: '').trim()
    if (!label) {
      label = extractAuthorFieldLabelPhrase(cand) ?: extractAuthorFieldLabelPhrase(visible)
    }
    return (label ?: '').length() > 0
  }

  /** Phrase the author asked to appear in content (e.g. "Russ was Here" from "… with Russ was Here"). */
  private static String extractAuthoringOutcomePhrase(String authorVisible) {
    String v = authorVisibleTailForOutcomePhrase(authorVisible)
    if (v) {
      if (!authorRequestNeedsExternalContentResolution(authorVisible)) {
        def mTo = (v =~ /(?is)\b(?:update|change|set|replace)\s+.+?\s+to\s+(.+)$/)
        if (mTo.matches()) {
          String cap = normalizeOutcomePhrase(mTo.group(1))
          if (isUsableHotpathOutcomePhrase(cap, authorVisible)) {
            return cap
          }
        }
        def m1 = (v =~ /(?is)\b(?:update|change|set|replace)\s+(?:the\s+)?[\w\s-]+?\s+with\s+(.+)$/)
        if (m1.matches()) {
          String cap = normalizeOutcomePhrase(m1.group(1))
          if (isUsableHotpathOutcomePhrase(cap, authorVisible)) {
            return cap
          }
        }
        def m2 = (v =~ /(?is)\b(?:to|with)\s+["']([^"']+)["']/)
        if (m2.find()) {
          String cap = normalizeOutcomePhrase(m2.group(1))
          if (isUsableHotpathOutcomePhrase(cap, authorVisible)) {
            return cap
          }
        }
        if (!authorPlacementRequestsFieldTargetNotLiteralContent(authorVisible) && v.length() <= 400) {
          def m3 = (v =~ /(?is)\b(?:to|with)\s+(.+)$/)
          if (m3.find()) {
            String cap = normalizeOutcomePhrase(m3.group(1))
            if (isUsableHotpathOutcomePhrase(cap, authorVisible)) {
              return cap
            }
          }
        }
      }
    }
    String fromPriorTurn = extractPriorTurnAssistantContentForOutcome(authorVisible)
    if (isUsableHotpathOutcomePhrase(fromPriorTurn, authorVisible)) {
      return fromPriorTurn
    }
    String fromPrior = extractOutcomeFromPriorAssistantContentBlock(authorVisible)
    if (isUsableHotpathOutcomePhrase(fromPrior, authorVisible)) {
      return fromPrior
    }
    return ''
  }

  /**
   * After “let’s do it”, reuse prose the assistant already produced (e.g. lyrics in a fenced block) as write payload.
   */
  private static String extractOutcomeFromPriorAssistantContentBlock(String fullPrompt) {
    String s = (fullPrompt ?: '').toString()
    if (!s.contains('[Prior conversation') || !AuthoringPreviewContext.isShortAffirmationContinuingPriorCmsWork(s)) {
      return ''
    }
    int curIdx = s.indexOf('Current request:')
    String prior = curIdx > 0 ? s.substring(0, curIdx) : s
    int lastAssist = prior.toLowerCase(Locale.ROOT).lastIndexOf('assistant:')
    if (lastAssist < 0) {
      return ''
    }
    String assistBody = prior.substring(lastAssist + 'assistant:'.length()).trim()
    int fenceStart = assistBody.indexOf('```')
    if (fenceStart < 0) {
      return ''
    }
    int contentStart = assistBody.indexOf('\n', fenceStart)
    if (contentStart < 0) {
      return ''
    }
    contentStart++
    int fenceEnd = assistBody.indexOf('```', contentStart)
    if (fenceEnd <= contentStart) {
      return ''
    }
    String block = assistBody.substring(contentStart, fenceEnd).trim()
    return block.length() > 12_000 ? block.substring(0, 12_000).trim() : block
  }

  /**
   * Reuse assistant-generated copy from the prior turn (numbered tips, research blocks) when the author says
   * “use these tips” — not limited to short affirmations + fenced blocks.
   */
  private static String extractPriorTurnAssistantContentForOutcome(String fullPrompt) {
    String s = (fullPrompt ?: '').toString()
    if (!s.contains('[Prior conversation')) {
      return ''
    }
    int curIdx = s.indexOf('Current request:')
    String prior = curIdx > 0 ? s.substring(0, curIdx) : s
    int lastAssist = prior.toLowerCase(Locale.ROOT).lastIndexOf('assistant:')
    if (lastAssist < 0) {
      return ''
    }
    String assistBody = prior.substring(lastAssist + 'assistant:'.length()).trim()
    List<String> numbered = []
    assistBody.eachLine { String line ->
      def m = (line =~ /^\s*\d+\.\s+\*\*([^*]+)\*\*:?\s*(.*)$/)
      if (m.find()) {
        String tip = m.group(1).trim()
        String rest = m.group(2).trim()
        numbered << (rest ? "${tip}: ${rest}" : tip)
      }
    }
    if (numbered.size() >= 3) {
      return numbered.join('\n')
    }
    return ''
  }

  private static String normalizeOutcomePhrase(String s) {
    if (!s?.trim()) {
      return ''
    }
    return s.trim().replaceAll(/[.!?]+\s*$/, '').trim()
  }

  private static String htmlToRoughPlainText(String html) {
    if (!html?.trim()) {
      return ''
    }
    return html
      .replaceAll('(?is)<script[^>]*>[\\s\\S]*?</script>', ' ')
      .replaceAll('(?is)<style[^>]*>[\\s\\S]*?</style>', ' ')
      .replaceAll('<[^>]+>', ' ')
      .replaceAll('\\s+', ' ')
      .trim()
  }

  private static boolean plainTextContainsPhrase(String haystackPlain, String phrase) {
    if (!phrase?.trim() || !haystackPlain) {
      return false
    }
    return haystackPlain.toLowerCase(Locale.ROOT).contains(phrase.trim().toLowerCase(Locale.ROOT))
  }

  private static String repoPathFromToolArgsMap(Map args) {
    if (!(args instanceof Map)) {
      return ''
    }
    return plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools.repoPathFromToolInput(args) ?: ''
  }

  private static String siteIdFromWireMessages(List<Map> wireMessages) {
    String user = firstAuthorVisibleUserFromWire(wireMessages) ?: ''
    def m = (user =~ /Current CrafterCMS site id:\s*"([^"]+)"/)
    if (m.find()) {
      return m.group(1)?.trim() ?: ''
    }
    return ''
  }

  private static String enginePreviewUrlFromWire(List<Map> wireMessages, Map toolsLoopSessionBundle = null) {
    String user = firstAuthorVisibleUserFromWire(wireMessages) ?: ''
    def m = (user =~ /(?m)^--- Engine preview URL[^\n]*\n[\s\S]*?\n(https?:\/\/\S+)/)
    if (m.find()) {
      return m.group(1)?.trim() ?: ''
    }
    try {
      def ops = toolsLoopSessionBundle?.get('studioOps')
      if (ops != null) {
        def bindings = ops.recipeEngineAuthoringBindings()
        if (bindings instanceof Map) {
          return bindings.previewUrl?.toString()?.trim() ?: ''
        }
      }
    } catch (Throwable ignored) {
    }
    return ''
  }

  private static String minimalPlanWhenToolsWithoutProse(int zeroBasedRound) {
    if (zeroBasedRound > 0) {
      return ''
    }
    return '## Plan\n\n📋 Apply the author\'s request with the tools in this turn.\n'
  }

  /**
   * Applies phrase verification fields on GetPreviewHtml tool JSON; returns updated JSON string.
   */
  private static Map enrichGetPreviewHtmlToolResult(
    String toolOut,
    String frozenAuthorOutcomePhrase,
    JsonSlurper slurper
  ) {
    Boolean found = null
    String phrase = frozenAuthorOutcomePhrase ?: ''
    String outJson = toolOut ?: ''
    try {
      def parsedPrev = slurper.parseText(outJson)
      if (parsedPrev instanceof Map && Boolean.TRUE.equals(((Map) parsedPrev).get('ok'))) {
        def html = ((Map) parsedPrev).get('html')
        if (html != null && html.toString().trim() && phrase) {
          String plain = htmlToRoughPlainText(html.toString())
          boolean hit = plainTextContainsPhrase(plain, phrase)
          parsedPrev.put('contentGoalPhrase', phrase)
          parsedPrev.put('contentGoalFoundInPreviewHtml', hit)
          found = hit
          if (!hit) {
            parsedPrev.put(
              'verificationWarning',
              'Preview HTML does not contain the expected phrase "' + phrase + '". ' +
                'Do not tell the author the change is visible until preview shows it. ' +
                'Use GetContentTypeFormDefinition (or prefetched formDefinitionXml) to pick the correct field id for what the author asked for. ' +
                'If XML is correct but preview is wrong, use analyze_template read-only to check for hardcoded FTL copy.'
            )
          }
          outJson = JsonOutput.toJson((Map) parsedPrev)
        }
      }
    } catch (Throwable ignored) {
    }
    return [toolOut: outJson, previewGoalFound: found, previewGoalPhrase: phrase]
  }

  private static void maybeAppendAutoConfirmationPreviewAfterRound(
    List<Map> wireMessages,
    Map<String, FunctionToolCallback> byName,
    JsonSlurper slurper,
    boolean roundHadWriteSuccess,
    boolean roundRanGetPreviewHtml,
    boolean roundHadWriteFailure,
    String frozenAuthorOutcomePhrase,
    Map toolsLoopSessionBundle,
    int round,
    String agentId,
    Map previewState
  ) {
    if (!roundHadWriteSuccess || roundRanGetPreviewHtml || roundHadWriteFailure) {
      return
    }
    String url = enginePreviewUrlFromWire(wireMessages, toolsLoopSessionBundle)
    if (!url) {
      return
    }
    FunctionToolCallback tcb = byName?.get('GetPreviewHtml')
    if (tcb == null) {
      return
    }
    String siteId = siteIdFromWireMessages(wireMessages)
    Map args = [url: url]
    if (siteId) {
      args.siteId = siteId
    }
    String toolOut
    try {
      toolOut = tcb.call(JsonOutput.toJson(args))
    } catch (Throwable tex) {
      log.warn('Tools-loop: auto GetPreviewHtml after write failed: {}', tex.message)
      return
    }
    if (toolOut instanceof Map) {
      toolOut = JsonOutput.toJson((Map) toolOut)
    } else {
      toolOut = toolOut?.toString() ?: ''
    }
    Map enriched = enrichGetPreviewHtmlToolResult(toolOut, frozenAuthorOutcomePhrase, slurper)
    toolOut = enriched.toolOut?.toString() ?: ''
    if (enriched.previewGoalFound instanceof Boolean) {
      previewState.lastPreviewContentGoalFound = enriched.previewGoalFound
    }
    if (enriched.previewGoalPhrase) {
      previewState.lastPreviewContentGoalPhrase = enriched.previewGoalPhrase.toString()
    }
    String wire = truncateNativeToolWireContent('GetPreviewHtml', toolOut, 'aiassistant-auto-preview', [:])
    wireMessages << [
      role   : 'user',
      content:
        '[aiassistant: confirmation preview after successful WriteContent — internal]\n' +
          'Studio ran **GetPreviewHtml** automatically after a successful write. Use this result for verification; do not repeat GetPreviewHtml unless you changed content again.\n\n' +
          wire
    ]
    log.info(
      'Tools-loop: auto GetPreviewHtml after successful WriteContent round={} agentId={} phraseFound={}',
      round,
      agentId,
      enriched.previewGoalFound
    )
  }

  private static String promotePlanToPlanExecutionIfNeeded(String text) {
    if (!text?.trim()) {
      return text ?: ''
    }
    if (text.contains('## Plan Execution')) {
      return text
    }
    if (text.contains('## Plan')) {
      return text.replaceFirst('(?m)^##\\s+Plan\\b', '## Plan Execution')
    }
    return text
  }

  private static String appendPreviewVerificationWarningIfNeeded(
    String assistantText,
    Boolean previewGoalFound,
    String previewGoalPhrase
  ) {
    if (previewGoalFound != Boolean.FALSE || !previewGoalPhrase?.trim()) {
      return assistantText ?: ''
    }
    String phraseForWarn = previewGoalPhrase.trim()
    if (phraseForWarn.length() > 200) {
      phraseForWarn = phraseForWarn.substring(0, 197) + '…'
    }
    String warn =
      '\n\n⚠️ **Preview check:** Engine preview HTML did **not** contain **"' +
        phraseForWarn +
        '"**. The copy may still be wrong (wrong field, template hardcoding, or stale cache). Open preview in Studio and confirm before publishing.\n'
    String base = (assistantText ?: '').toString()
    if (base.contains('Preview check:') || base.contains('did **not** contain')) {
      return base
    }
    return base + warn
  }

  private static boolean choiceMessageHasToolCalls(Map msg) {
    if (!(msg instanceof Map)) {
      return false
    }
    def tc = msg.get('tool_calls')
    return tc instanceof List && !((List) tc).isEmpty()
  }

  private static String assistantTextFromChoiceMessageMap(Map msg) {
    if (!(msg instanceof Map)) {
      return ''
    }
    def refusal = msg.get('refusal')
    if (refusal != null && refusal.toString().trim()) {
      return refusal.toString()
    }
    def c = msg.get('content')
    if (c instanceof CharSequence) {
      return c.toString()
    }
    if (c instanceof List) {
      StringBuilder sb = new StringBuilder()
      for (def part : (List) c) {
        if (part instanceof Map) {
          Map pm = (Map) part
          def t = pm.get('text')
          if (t == null && pm.containsKey('content')) {
            t = pm.get('content')
          }
          if (t instanceof CharSequence && t.toString().trim()) {
            sb.append(t.toString())
          } else if (t instanceof List) {
            for (def inner : (List) t) {
              if (inner instanceof Map && ((Map) inner).get('text') != null) {
                sb.append(((Map) inner).get('text').toString())
              }
            }
          }
        } else if (part instanceof CharSequence) {
          sb.append(part.toString())
        }
      }
      return sb.toString()
    }
    return c != null ? c.toString() : ''
  }

  /**
   * Policy: never stream {@code ## Plan Execution} in the same assistant message as {@code tool_calls}
   * (models sometimes recap before tools finish). Strip from the first {@code ## Plan Execution} onward.
   */
  private static String stripPlanExecutionWhenToolCallsPresent(String flat, boolean hasToolCalls) {
    if (!hasToolCalls) {
      return flat
    }
    if (flat == null || !flat.toString().trim()) {
      return flat == null ? '' : flat.toString()
    }
    String s = flat.toString()
    int idx = s.indexOf('## Plan Execution')
    if (idx < 0) {
      return s
    }
    return s.substring(0, idx).replaceAll(/\s+$/, '').trim()
  }

  /** Strips {@link PlanOrchestration} machine block from assistant {@code content} before wire history / next tools-loop turn. */
  private static void mutateAssistantContentStripOrchestratorBlock(Map msgCopy) {
    if (!(msgCopy instanceof Map)) {
      return
    }
    boolean hasTc = choiceMessageHasToolCalls(msgCopy)
    String flat = assistantTextFromChoiceMessageMap(msgCopy)
    String stripped = PlanOrchestration.stripOrchestrationPlanBlock(flat)
    stripped = stripPlanExecutionWhenToolCallsPresent(stripped, hasTc)
    if (stripped != flat) {
      msgCopy.put('content', stripped)
    }
  }

  /**
   * Normalizes model ids for API-compat heuristics: lower case, strip soft hyphen, map Unicode hyphens to ASCII
   * so {@code gpt‑5} (U+2011) still matches {@code gpt-5} token checks.
   */
  private static String normalizeModelIdForHeuristics(String model) {
    if (model == null) {
      return ''
    }
    String s = model.toString().trim().toLowerCase(Locale.ROOT)
    s = s.replace('\u2011', '-').replace('\u2010', '-').replace('\u2212', '-')
    s = s.replace('\u00ad', '')
    // Zero-width / format chars that break naive `contains('gpt-5')` matching on some agent configs.
    s = s.replaceAll(/[\u200B-\u200D\uFEFF\u2060]/, '')
    return s
  }

  /**
   * Reasoning / gpt-5 family: {@code max_completion_tokens} instead of {@code max_tokens}, and non-default
   * {@code temperature} rejected (400 {@code unsupported_value}).
   */
  private static boolean modelNeedsNeoChatCompletionWireParams(String model) {
    String m = normalizeModelIdForHeuristics(model)
    if (!m) {
      return false
    }
    // o-series + gpt-5 family: non-default temperature rejected (400 unsupported_value); use max_completion_tokens not max_tokens.
    // Dated ids like gpt-5-2025-08-07 normalize to lowercase and still contain "gpt-5".
    if (m.startsWith('o1') ||
      m.startsWith('o3') ||
      m.startsWith('o4') ||
      m.contains('gpt-5') ||
      m.contains('gpt_5')) {
      return true
    }
    // Rare aliases / exotic separators between "gpt" and "5" (still the fixed-temperature family).
    return Pattern.compile('(?i)gpt[^a-z0-9]*5').matcher(m).find()
  }

  /**
   * Output token limit map for {@code /v1/chat/completions}. Uses {@code max_completion_tokens} for models that
   * reject non-default temperature (see {@link #modelNeedsNeoChatCompletionWireParams}), or when
   * {@link StudioAiLlmKind#toolsLoopChatPreferMaxCompletionTokensFromBundle} is true on the script session bundle.
   * Other hosts use {@code max_tokens}.
   *
   * @param toolsLoopSessionBundle optional map from {@code StudioAiLlmRuntime#buildSessionBundle} (script or future built-ins)
   */
  private static Map chatCompletionOutputLimitParams(String model, int cap, Map toolsLoopSessionBundle = null) {
    if (StudioAiLlmKind.toolsLoopChatPreferMaxCompletionTokensFromBundle(toolsLoopSessionBundle)) {
      return [max_completion_tokens: cap]
    }
    if (modelNeedsNeoChatCompletionWireParams(model)) {
      return [max_completion_tokens: cap]
    }
    return [max_tokens: cap]
  }

  /**
   * Models wired with {@code max_tokens} (non-neo) cap completion output below 32k (e.g. {@code gpt-4o-mini} ~16k);
   * larger values can yield HTTP 400 from the configured chat host.
   */
  private static int clampMaxOutTokensForChatCompletionsModel(String model, int requested) {
    if (requested <= 0) {
      return 4096
    }
    if (modelNeedsNeoChatCompletionWireParams(model)) {
      return Math.min(requested, 128_000)
    }
    return Math.min(requested, 16_384)
  }

  /**
   * Native tools-loop sync POSTs default a high completion budget ({@code 16000}); hosts may reject large values.
   * Optional {@link StudioAiLlmKind#toolsLoopChatMaxCompletionOutTokensFromBundle} caps completion output for script LLMs.
   */
  private static int clampMaxOutTokensForToolsLoopWire(String model, int requested, Map toolsLoopSessionBundle = null) {
    int r = requested > 0 ? requested : 8192
    Integer scriptCap = StudioAiLlmKind.toolsLoopChatMaxCompletionOutTokensFromBundle(toolsLoopSessionBundle)
    if (scriptCap != null) {
      int out = Math.min(r, scriptCap)
      if (out < r) {
        log.debug(
          'Tools-loop: completion out tokens clamped requested={} -> {} (model={}; bundle {}={})',
          r,
          out,
          model,
          StudioAiLlmKind.BUNDLE_TOOLS_LOOP_CHAT_MAX_COMPLETION_OUT_TOKENS,
          scriptCap
        )
      }
      return out
    }
    return clampMaxOutTokensForChatCompletionsModel(model, r)
  }

  private static void truncateToolsLoopWireToolTopLevelDescriptions(List wireTools, int maxLen) {
    if (!(wireTools instanceof List) || maxLen < 40) {
      return
    }
    for (def t : (List) wireTools) {
      if (!(t instanceof Map)) {
        continue
      }
      Map tm = (Map) t
      def fn = tm.get('function')
      if (!(fn instanceof Map)) {
        continue
      }
      Map fm = (Map) fn
      String d = fm.get('description')?.toString() ?: ''
      if (d.length() > maxLen) {
        fm.put('description', d.substring(0, Math.max(0, maxLen - 1)) + '…')
      }
    }
  }

  private static void shrinkToolsLoopJsonSchemaDescriptionStrings(Object node, int maxLen) {
    if (node == null || maxLen < 8) {
      return
    }
    if (node instanceof Map) {
      Map map = (Map) node
      for (Object k : new ArrayList<>(map.keySet())) {
        Object val = map.get(k)
        String key = k != null ? k.toString() : ''
        if ('description'.equals(key) && val instanceof CharSequence) {
          String s = val.toString()
          if (s.length() > maxLen) {
            map.put(k, s.substring(0, Math.max(0, maxLen - 1)) + '…')
          }
        } else {
          shrinkToolsLoopJsonSchemaDescriptionStrings(val, maxLen)
        }
      }
    } else if (node instanceof List) {
      for (Object item : (List) node) {
        shrinkToolsLoopJsonSchemaDescriptionStrings(item, maxLen)
      }
    }
  }

  private static void clearToolsLoopJsonSchemaDescriptionStrings(Object node) {
    if (node == null) {
      return
    }
    if (node instanceof Map) {
      Map map = (Map) node
      for (Object k : new ArrayList<>(map.keySet())) {
        if ('description'.equals(k?.toString())) {
          map.remove(k)
        } else {
          clearToolsLoopJsonSchemaDescriptionStrings(map.get(k))
        }
      }
    } else if (node instanceof List) {
      for (Object item : (List) node) {
        clearToolsLoopJsonSchemaDescriptionStrings(item)
      }
    }
  }

  private static void capToolsLoopWireMessageContents(List<Map> wireMessages, int maxPerMessage) {
    if (!(wireMessages instanceof List) || maxPerMessage < 256) {
      return
    }
    int lastUserIdx = -1
    for (int i = 0; i < wireMessages.size(); i++) {
      Map m = wireMessages.get(i) as Map
      if (m != null && 'user'.equalsIgnoreCase((m.get('role') ?: '').toString().trim())) {
        lastUserIdx = i
      }
    }
    for (int i = 0; i < wireMessages.size(); i++) {
      Map m = wireMessages.get(i) as Map
      if (m == null) {
        continue
      }
      def c = m.get('content')
      if (!(c instanceof CharSequence)) {
        continue
      }
      String s = c.toString()
      int cap = maxPerMessage
      if (i == lastUserIdx) {
        cap = (int) Math.min((long) maxPerMessage * 2L, 200_000L)
      }
      if (s.length() > cap) {
        int reserve = Math.min(220, Math.max(48, (int) (cap * 0.14d)))
        int head = Math.max(64, cap - reserve)
        m.put(
          'content',
          s.substring(0, head) +
            '\n\n[aiassistant: content truncated for tools-loop request size; originalChars=' +
            s.length() +
            ']\n'
        )
      }
    }
  }

  /**
   * When {@code maxWireChars > 0} and serialized {@code reqMap} exceeds that budget, shrinks tool copy + message text in place.
   * Session bundle key: {@link StudioAiLlmKind#BUNDLE_TOOLS_LOOP_CHAT_MAX_WIRE_PAYLOAD_CHARS} (set from site script for strict hosts).
   */
  private static void shrinkToolsLoopWirePayloadIfOverBudget(Map reqMap, List<Map> wireMessages, List wireTools, int maxWireChars) {
    if (maxWireChars <= 0) {
      return
    }
    String body
    try {
      body = JsonOutput.toJson(reqMap)
    } catch (Throwable t) {
      return
    }
    int n = body.length()
    if (n <= maxWireChars) {
      return
    }
    log.warn(
      'Tools-loop: wire JSON large ({} chars > cap {}); shrinking tool + message payload before POST',
      n,
      maxWireChars
    )
    for (topDesc in [2000, 900, 450, 220, 120]) {
      truncateToolsLoopWireToolTopLevelDescriptions(wireTools, topDesc as int)
      body = JsonOutput.toJson(reqMap)
      n = body.length()
      if (n <= maxWireChars) {
        log.info('Tools-loop: shrink ok after top-level tool description cap={} newChars={}', topDesc, n)
        return
      }
    }
    for (sl in [360, 180, 90]) {
      for (def t : (List) wireTools) {
        if (!(t instanceof Map)) {
          continue
        }
        def fn = ((Map) t).get('function')
        if (fn instanceof Map) {
          def params = ((Map) fn).get('parameters')
          shrinkToolsLoopJsonSchemaDescriptionStrings(params, sl as int)
        }
      }
      body = JsonOutput.toJson(reqMap)
      n = body.length()
      if (n <= maxWireChars) {
        log.info('Tools-loop: shrink ok after JSON-schema description cap={} newChars={}', sl, n)
        return
      }
    }
    for (def t : (List) wireTools) {
      if (!(t instanceof Map)) {
        continue
      }
      def fn = ((Map) t).get('function')
      if (fn instanceof Map) {
        def params = ((Map) fn).get('parameters')
        clearToolsLoopJsonSchemaDescriptionStrings(params)
      }
    }
    body = JsonOutput.toJson(reqMap)
    n = body.length()
    if (n <= maxWireChars) {
      log.info('Tools-loop: shrink ok after stripping nested JSON-schema descriptions newChars={}', n)
      return
    }
    for (mc in [
      48_000, 28_000, 18_000, 12_000, 9000, 6000, 5000, 4000, 3200, 2600, 2000, 1600, 1200, 900, 768, 640, 512, 448, 384, 320, 288, 256
    ]) {
      capToolsLoopWireMessageContents(wireMessages, mc as int)
      body = JsonOutput.toJson(reqMap)
      n = body.length()
      if (n <= maxWireChars) {
        log.info('Tools-loop: shrink ok after message content cap={} newChars={}', mc, n)
        return
      }
    }
    log.warn(
      'Tools-loop: wire JSON still large after shrink ({} chars > cap {}); upstream may reject the request',
      n,
      maxWireChars
    )
  }

  /**
   * o1 / o3 / o4 / gpt-5* reject non-default {@code temperature} (400 {@code unsupported_value}); omit the field so the API default applies.
   */
  private static Map chatCompletionTemperatureParams(String model, double valueWhenSupported) {
    if (!normalizeModelIdForHeuristics(model)) {
      return [temperature: valueWhenSupported]
    }
    if (modelNeedsNeoChatCompletionWireParams(model)) {
      return [:]
    }
    return [temperature: valueWhenSupported]
  }

  /**
   * Last line of defense: some Studio builds / classpath merges have still sent {@code temperature} for gpt-5/o
   * and OpenAI returns 400. Parse wire JSON and drop {@code temperature} when {@link #modelNeedsNeoChatCompletionWireParams} applies.
   */
  /**
   * Last-resort strip when JsonSlurper path fails or another layer reintroduces {@code temperature}.
   * Keeps JSON valid for typical Groovy {@link JsonOutput} shapes (single chat.completions object).
   */
  private static String chatCompletionJsonStripTemperatureRegex(String jsonBody) {
    if (!jsonBody?.toString()?.trim() || !jsonBody.contains('temperature')) {
      return jsonBody.toString()
    }
    String s = jsonBody.toString()
    s = s.replaceAll(/,\s*"temperature"\s*:\s*-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/, '')
    s = s.replaceAll(/"temperature"\s*:\s*-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\s*,/, '')
    s = s.replaceAll(/"temperature"\s*:\s*-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/, '')
    return s
  }

  private static String chatCompletionJsonStripTemperatureForNeoModel(String model, String jsonBody) {
    if (!jsonBody?.toString()?.trim() || !modelNeedsNeoChatCompletionWireParams(model)) {
      return jsonBody.toString()
    }
    if (!jsonBody.contains('temperature')) {
      return jsonBody.toString()
    }
    String out = jsonBody.toString()
    try {
      def parsed = new JsonSlurper().parseText(out)
      if (!(parsed instanceof Map)) {
        out = chatCompletionJsonStripTemperatureRegex(out)
        return out
      }
      Map m = new LinkedHashMap((Map) parsed)
      if (m.remove('temperature') != null) {
        log.warn(
          'chatCompletionJsonStripTemperatureForNeoModel: removed temperature from chat.completions body (model={})',
          model
        )
      }
      out = JsonOutput.toJson(m)
    } catch (Throwable t) {
      log.warn('chatCompletionJsonStripTemperatureForNeoModel: parse failed — regex strip fallback: {}', t.message)
      out = chatCompletionJsonStripTemperatureRegex(out)
    }
    if (out.contains('temperature')) {
      log.warn(
        'chatCompletionJsonStripTemperatureForNeoModel: temperature still present after strip — second regex pass (model={})',
        model
      )
      out = chatCompletionJsonStripTemperatureRegex(out)
    }
    return out
  }

  private static final Set<String> PIPELINE_VERIFICATION_TOOL_NAMES = Collections.unmodifiableSet(
    new LinkedHashSet<>(['GetPreviewHtml', 'analyze_template'])
  )

  /** {@code main} | {@code verification} | {@code summary} — echoed on tool-progress SSE for UI grouping. */
  private static String pipelineStageForRepoTool(String toolName) {
    String n = (toolName ?: '').trim()
    if (PIPELINE_VERIFICATION_TOOL_NAMES.contains(n)) {
      return 'verification'
    }
    return 'main'
  }

  private static String pipelineStageForToolsLoopChatLine(
    String markdownLine,
    String phase,
    boolean previousRoundHadRepoMutation,
    int zeroBasedRound
  ) {
    String p = (phase ?: '').trim().toLowerCase(Locale.ROOT)
    if ('debug'.equals(p)) {
      return 'summary'
    }
    String line = (markdownLine ?: '').toLowerCase(Locale.ROOT)
    if (line.contains('post-tool review') || line.contains('correction pass')) {
      return 'summary'
    }
    if (zeroBasedRound > 0 && previousRoundHadRepoMutation) {
      return 'verification'
    }
    if (line.contains('validation') && line.contains('preview')) {
      return 'verification'
    }
    return 'main'
  }

  /** Emits one {@code tool-progress} SSE line (same channel as 🛠️ repo tool rows) for long-running chat phases. */
  private static void emitSseToolProgressLine(
    OutputStream o,
    String markdownLine,
    String phase,
    String pipelineStage = null
  ) {
    if (o == null || !markdownLine?.toString()?.trim()) {
      return
    }
    try {
      String stage = (pipelineStage ?: '').trim()
      if (!stage) {
        stage = pipelineStageForToolsLoopChatLine(markdownLine.toString(), phase, false, 0)
      }
      def ev = [
        text    : markdownLine.toString(),
        metadata: [
          status        : 'tool-progress',
          tool          : 'Tools-loop chat',
          phase         : (phase ?: 'start').toString(),
          pipelineStage : stage
        ]
      ]
      synchronized (o) {
        o.write(("data: ${JsonOutput.toJson(ev)}\n\n").getBytes(StandardCharsets.UTF_8))
        o.flush()
      }
    } catch (Throwable ignored) {
    }
  }

  /**
   * Long-wait keepalive for the Tools-loop+tools worker: **does not** append a markdown line to the tool log — the Studio
   * client shows a single animated row that this frame **updates** in place.
   * <p>{@link Number} parameters accept Groovy {@code /} results (often {@link BigDecimal}) as well as {@code long}.</p>
   */
  private static void emitSsePipelineHeartbeat(OutputStream o, Number elapsedSec, Number nextInSec, String hintMd) {
    if (o == null) {
      return
    }
    long el = elapsedSec != null ? elapsedSec.longValue() : 0L
    long nx = (nextInSec != null && nextInSec.longValue() > 0L) ? nextInSec.longValue() : 5L
    try {
      def ev = [
        text    : '',
        metadata: [
          status    : 'pipeline-heartbeat',
          elapsedSec: el,
          nextInSec : nx,
          hint      : hintMd?.toString() ?: ''
        ]
      ]
      synchronized (o) {
        o.write(("data: ${JsonOutput.toJson(ev)}\n\n").getBytes(StandardCharsets.UTF_8))
        o.flush()
      }
    } catch (Throwable ignored) {
    }
  }

  /** Escapes ``` so wrapping {@code raw} in a Markdown ``` fence does not break Studio rendering. */
  private static String escapeTripleBackticksForMarkdownFence(String raw) {
    if (raw == null) {
      return ''
    }
    return raw.toString().replace('```', '\\`\\`\\`')
  }

  /**
   * Tool-progress debug wraps assistant text in a {@code ```text} fence with a size cap (~12k). Inline
   * {@code data:image/...;base64,...} payloads are often hundreds of KB — they (a) blow the cap mid-base64 so the
   * debug panel shows misleading garbage, and (b) duplicate the real preview in the final assistant markdown below.
   * Replace each with a short note so authors see intent without truncated ciphertext.
   */
  private static String elideDataImageUrlsForToolProgressDebug(String raw) {
    if (raw == null || raw.isEmpty() || raw.indexOf('data:image') < 0) {
      return raw ?: ''
    }
    Pattern p = Pattern.compile('(?is)data:image/[a-z0-9.+-]+;base64,')
    Matcher mat = p.matcher(raw)
    StringBuilder out = new StringBuilder(Math.min(raw.length(), 200_000))
    int pos = 0
    int len = raw.length()
    while (pos < len) {
      mat.region(pos, len)
      if (!mat.find()) {
        out.append(raw, pos, len)
        break
      }
      out.append(raw, pos, mat.start())
      int payloadStart = mat.end()
      int i = payloadStart
      while (i < len) {
        char c = raw.charAt(i)
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=' ||
          c == '\n' || c == '\r' || c == ' ' || c == '\t') {
          i++
          continue
        }
        break
      }
      out.append('[inline image omitted from tool-progress debug (')
        .append(i - payloadStart)
        .append(' base64 chars); full image renders in the assistant reply below]')
      pos = i
    }
    return out.toString()
  }

  /**
   * Maintainer-only round trace (JVM debug log). Not emitted on author SSE — plan prose streams as normal markdown
   * when present; inline {@code Plan snippet} backticks stripped formatting in Studio.
   */
  private static void emitSseAssistantTurnDebugPreview(
    OutputStream o,
    String assistantFlatAsReceived,
    Map msgCopy,
    boolean hasTc,
    int zeroBasedRound,
    String agentId
  ) {
    if (!log.isDebugEnabled()) {
      return
    }
    StringBuilder sb = new StringBuilder(256)
    sb.append('Tools-loop round ').append(zeroBasedRound + 1).append(' agentId=').append(agentId ?: '')
    if (hasTc) {
      def tcl = msgCopy != null ? msgCopy.get('tool_calls') : null
      List<String> names = new ArrayList<>()
      if (tcl instanceof List) {
        for (def tcObj : (List) tcl) {
          if (!(tcObj instanceof Map)) {
            continue
          }
          def fn = ((Map) tcObj).get('function')
          String fnName = fn instanceof Map ? (fn.get('name')?.toString()?.trim() ?: '') : ''
          if (fnName) {
            names.add(fnName)
          }
        }
      }
      if (!names.isEmpty()) {
        sb.append(' tool_calls=').append(names.join(', '))
      }
    } else {
      sb.append(' text-only')
    }
    String assist = (assistantFlatAsReceived ?: '').toString().trim()
    if (assist) {
      final int cap = 320
      String snippet = assist.length() > cap ? assist.substring(0, cap) + '…' : assist
      sb.append(' assistantChars=').append(assist.length()).append(' head=').append(snippet.replaceAll(/\s+/, ' '))
    } else if (hasTc) {
      sb.append(' assistantContent=empty')
    }
    log.debug(sb.toString())
  }

  /**
   * Parses {@code model} from wire JSON and strips {@code temperature} when {@link #modelNeedsNeoChatCompletionWireParams}
   * applies. Called for <strong>every</strong> synchronous {@code /v1/chat/completions} POST so no caller can send {@code 0.1}
   * (or any explicit value) to GPT‑5 / o‑series — fixes divergent agent {@code llmModel} strings and Spring merge quirks.
   */
  private static String chatCompletionsWireBodyApplyNeoTemperaturePolicy(String jsonBody) {
    if (!jsonBody?.toString()?.trim()) {
      return jsonBody?.toString() ?: ''
    }
    String raw = jsonBody.toString()
    try {
      def parsed = new JsonSlurper().parseText(raw)
      if (!(parsed instanceof Map)) {
        return raw
      }
      String m = (parsed.get('model') ?: '').toString()
      return chatCompletionJsonStripTemperatureForNeoModel(m, raw)
    } catch (Throwable t) {
      log.warn('chatCompletionsWireBodyApplyNeoTemperaturePolicy: parse failed, POSTing unchanged: {}', t.message)
      String low = raw.toLowerCase(Locale.ROOT)
      if (low.contains('gpt-5') || low.contains('gpt_5') || low.contains('"o1') || low.contains('"o3') || low.contains('"o4')) {
        return chatCompletionJsonStripTemperatureRegex(raw)
      }
      return raw
    }
  }

  /**
   * Groq and similar hosts return 429 with {@code try again in Ns} in JSON; honors {@code Retry-After} when numeric.
   */
  private static long toolsLoop429BackoffMs(RestClientResponseException e, int zeroBasedAttempt) {
    try {
      String ra = e.getResponseHeaders()?.getFirst(HttpHeaders.RETRY_AFTER)
      if (ra?.trim()) {
        String firstToken = ra.trim().split(/\s+/)[0]
        long sec = Long.parseLong(firstToken)
        if (sec > 0 && sec < 900) {
          return Math.min(180_000L, Math.max(400L, sec * 1000L))
        }
      }
    } catch (Throwable ignored) {
    }
    try {
      String body = e.getResponseBodyAsString(StandardCharsets.UTF_8)
      if (body) {
        Matcher m = Pattern.compile('(?i)try again in\\s+([0-9.]+)\\s*s').matcher(body)
        if (m.find()) {
          double sec = Double.parseDouble(m.group(1))
          if (sec > 0 && sec < 900) {
            return (long) Math.min(180_000L, Math.max(400L, Math.round(sec * 1000.0)))
          }
        }
      }
    } catch (Throwable ignored) {
    }
    long exp = 900L * (1L << Math.min(3, zeroBasedAttempt))
    return Math.min(45_000L, exp)
  }

  /**
   * POST {@code /v1/chat/completions} with {@code stream:false} and return the raw JSON body (UTF-8).
   * Bypasses {@link org.springframework.ai.openai.api.OpenAiApi#chatCompletionEntity} / Jackson binding.
   * On HTTP 429, sleeps with backoff and retries up to two additional attempts (helps Groq on_demand TPM bursts).
   */
  private static String httpPostChatCompletionsReadBody(
    String apiKey,
    String jsonBody,
    boolean logFailuresAsWarn = false,
    String wireBaseUrl = null
  ) {
    jsonBody = chatCompletionsWireBodyApplyNeoTemperaturePolicy(jsonBody)
    final int maxTries = 3
    for (int attempt = 1; attempt <= maxTries; attempt++) {
      try {
        return httpPostChatCompletionsReadBodyOnce(apiKey, jsonBody, logFailuresAsWarn, wireBaseUrl)
      } catch (RestClientResponseException e) {
        if (e.getStatusCode()?.value() != 429 || attempt >= maxTries) {
          throw e
        }
        long ms = toolsLoop429BackoffMs(e, attempt - 1)
        log.warn(
          'Tools-loop chat HTTP 429 Too Many Requests; backing off {} ms then retry {}/{}',
          ms,
          attempt + 1,
          maxTries
        )
        try {
          Thread.sleep(ms)
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt()
          throw ie
        }
      }
    }
    throw new IllegalStateException('Tools-loop chat: 429 retries exhausted')
  }

  private static String httpPostChatCompletionsReadBodyOnce(
    String apiKey,
    String jsonBody,
    boolean logFailuresAsWarn,
    String wireBaseUrl
  ) {
    aiAssistantToolWorkerDiagPhase("native_tools_RestClient_POST_/v1/chat/completions stream=false jsonChars=${(jsonBody ?: '').toString().length()}")
    chatCompletionsRestClientBuilder(apiKey, wireBaseUrl)
      .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
      .build()
      .post()
      .uri('/v1/chat/completions')
      .contentType(MediaType.APPLICATION_JSON)
      .body(jsonBody)
      .exchange { httpReq, resp ->
        def status = resp.getStatusCode()
        def statusText = resp.getStatusText()
        def headers = resp.getHeaders()
        byte[] bytes
        try {
          def is = resp.getBody()
          bytes = is != null ? is.readAllBytes() : new byte[0]
        } finally {
          try {
            resp.close()
          } catch (Throwable ignored) {}
        }
        def bodyStr = new String(bytes, StandardCharsets.UTF_8)
        if (!status.is2xxSuccessful()) {
          String hint401 = ''
          if (status.value() == 401) {
            hint401 =
              ' Troubleshooting (401): the Bearer key for tools-loop chat must be the API key for the configured wire base URL (script bundle or Studio provider config). A secret issued for a different vendor than the host typically returns 401.'
          }
          String hint413 = ''
          if (status.value() == 413) {
            hint413 =
              ' Troubleshooting (413): request too large for the chat host (token / TPM limits). Reduce tools or prompt size, set toolsLoopChatMaxWirePayloadChars on the script session bundle, or raise your provider tier.'
          }
          String hint429 = ''
          if (status.value() == 429) {
            hint429 =
              ' Troubleshooting (429): rate limit / TPM — the plugin retries a few times with backoff; if this persists, reduce prompt and tool output, disable unused tools on the agent, or upgrade the chat host tier.'
          }
          def msg =
            "Tools-loop chat HTTP ${status.value()} ${statusText} responseBody=\n${AiHttpProxy.elideForLog(bodyStr, 4000)}${hint401}${hint413}${hint429}"
          if (logFailuresAsWarn) {
            log.warn(msg)
          } else {
            log.error(msg)
          }
          def rce = new RestClientResponseException(
            'Tools-loop chat',
            status.value(),
            statusText,
            headers,
            bytes,
            StandardCharsets.UTF_8
          )
          Throwable toThrow = preferIllegalStateForInvalidModel(rce, jsonBody?.toString())
          if (toThrow instanceof IllegalStateException) {
            throw (IllegalStateException) toThrow
          }
          throw (RestClientResponseException) toThrow
        }
        aiAssistantToolWorkerDiagPhase('native_tools_RestClient_POST_/v1/chat/completions response_ok')
        bodyStr
      }
  }

  /**
   * Single non-streaming chat completion (no tools) for server-side helpers (e.g. subgraph transform / translate item).
   * Uses {@link HttpURLConnection} with an extended read timeout so large translate/rephrase jobs can finish.
   * @param workerPhasePrefix optional tag (e.g. {@code TranslateContentItem}) prefixed onto {@code aiAssistantToolWorkerDiagPhase}
   *        strings so SSE heartbeats distinguish per-item inner calls from true bundled transforms.
   */
  static String toolsLoopSimpleCompletionAssistantText(
    String apiKey,
    String model,
    String systemText,
    String userText,
    int maxOutTokens,
    int readTimeoutMs = 600_000,
    String workerPhasePrefix = null,
    String wireBaseUrl = null,
    Map toolsLoopSessionBundle = null
  ) {
    String phasePfx = (workerPhasePrefix != null && workerPhasePrefix.toString().trim())
      ? workerPhasePrefix.toString().trim() + '_'
      : ''
    boolean countTranslateItemInflight = 'TranslateContentItem'.equals((workerPhasePrefix ?: '').toString().trim())
    if (countTranslateItemInflight) {
      AIASSISTANT_TRANSLATE_ITEM_INNER_INFLIGHT.incrementAndGet()
    }
    try {
    if (aiAssistantPipelineCancelEffective()) {
      aiAssistantToolWorkerDiagPhase(phasePfx + 'simple_completion_skipped_pipeline_cancelled')
      throw new InterruptedException(AIASSISTANT_PIPELINE_CANCELLED)
    }
    int effMaxOut = clampMaxOutTokensForToolsLoopWire(model, maxOutTokens, toolsLoopSessionBundle)
    if (effMaxOut < maxOutTokens) {
      log.info(
        'toolsLoopSimpleCompletionAssistantText: clamping maxOutTokens {} -> {} for model {} wireBaseUrl={}',
        maxOutTokens,
        effMaxOut,
        model,
        wireBaseUrl ?: '(default)'
      )
    }
    def reqMap = [
      model   : model,
      messages: [
        [role: 'system', content: (systemText ?: '').toString()],
        [role: 'user', content: (userText ?: '').toString()]
      ],
      stream  : false
    ]
    reqMap.putAll(chatCompletionOutputLimitParams(model, effMaxOut, toolsLoopSessionBundle))
    String jsonBody = chatCompletionsWireBodyApplyNeoTemperaturePolicy(JsonOutput.toJson(reqMap))
    String urlStr = resolveSyncChatCompletionsUrl(wireBaseUrl)
    aiAssistantToolWorkerDiagPhase(
      phasePfx +
        "simple_completion_HttpURLConnection_POST_/v1/chat/completions model=${model} wireJsonChars=${jsonBody.length()} userMsgChars=${(userText ?: '').toString().length()} readTimeoutMs=${readTimeoutMs}"
    )
    log.debug(
      'Tools-loop wire → POST /v1/chat/completions phase=simple_completion worker={} model={} systemChars={} userChars={} maxOutTokens={} readTimeoutMs={} wireJsonChars={} urlTail={}',
      (workerPhasePrefix ?: '(none)'),
      model,
      (systemText ?: '').length(),
      (userText ?: '').length(),
      effMaxOut,
      readTimeoutMs,
      jsonBody.length(),
      urlStr.contains('?') ? urlStr.substring(0, urlStr.indexOf('?')) : urlStr
    )
    HttpURLConnection conn = null
    try {
      conn = (HttpURLConnection) new URL(urlStr).openConnection()
      conn.setRequestMethod('POST')
      conn.setConnectTimeout(30_000)
      conn.setReadTimeout(Math.max(60_000, readTimeoutMs))
      conn.setRequestProperty(HttpHeaders.AUTHORIZATION, 'Bearer ' + apiKey)
      conn.setRequestProperty(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
      conn.setRequestProperty(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
      conn.setDoOutput(true)
      byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8)
      conn.setFixedLengthStreamingMode(bodyBytes.length)
      conn.outputStream.write(bodyBytes)
      conn.outputStream.flush()
      if (aiAssistantPipelineCancelEffective()) {
        aiAssistantToolWorkerDiagPhase(phasePfx + 'simple_completion_skipped_after_request_body_pipeline_cancelled')
        throw new InterruptedException(AIASSISTANT_PIPELINE_CANCELLED)
      }
      aiAssistantToolWorkerDiagPhase(
        phasePfx +
          "simple_completion_awaiting_chat_upstream_response_body model=${model} httpURLConnection readTimeoutMs=${Math.max(60_000, readTimeoutMs)}"
      )
      int code = conn.responseCode
      InputStream rawStream = code >= 200 && code < 300 ? conn.inputStream : conn.errorStream
      byte[] bytes
      try {
        bytes = rawStream != null ? rawStream.readAllBytes() : new byte[0]
      } finally {
        try {
          rawStream?.close()
        } catch (Throwable ignored) {}
      }
      String raw = new String(bytes, StandardCharsets.UTF_8)
      if (code < 200 || code >= 300) {
        log.error('Tools-loop simple completion HTTP {} body=\n{}', code, AiHttpProxy.elideForLog(raw, 4000))
        if (code == 400 && responseBodyLooksLikeInvalidModelId(raw)) {
          throw newIllegalStateForInvalidWireModel(jsonBody, raw)
        }
        throw new IllegalStateException("Tools-loop chat HTTP ${code}: ${AiHttpProxy.elideForLog(raw, 800)}")
      }
      if (!raw?.trim()) {
        throw new IllegalStateException('Tools-loop simple completion: empty response body')
      }
      def slurper = new JsonSlurper()
      Object parsed = slurper.parseText(raw)
      if (!(parsed instanceof Map)) {
        throw new IllegalStateException('Tools-loop simple completion: expected JSON object')
      }
      Map root = (Map) parsed
      def errMsg = streamChunkProviderErrorMessage(root)
      if (errMsg) {
        throw new IllegalStateException('Tools-loop simple completion: ' + errMsg)
      }
      def choices = root.get('choices')
      if (!(choices instanceof List) || choices.isEmpty()) {
        throw new IllegalStateException('Tools-loop simple completion: missing choices')
      }
      def c0 = choices[0] as Map
      def message = c0.get('message')
      if (!(message instanceof Map)) {
        throw new IllegalStateException('Tools-loop simple completion: missing message')
      }
      aiAssistantToolWorkerDiagPhase(phasePfx + 'simple_completion_chat_upstream_response_parsed_ok')
      return assistantTextFromChoiceMessageMap((Map) message)
    } finally {
      try {
        conn?.disconnect()
      } catch (Throwable ignored) {}
    }
    } finally {
      if (countTranslateItemInflight) {
        AIASSISTANT_TRANSLATE_ITEM_INNER_INFLIGHT.decrementAndGet()
      }
    }
  }

  private static final String AUTHORING_INTENT_EXPANSION_BLOCK_HEADER =
    '[Studio — expanded authoring intent (model-generated for this turn; execute with tools)]'

  private static final String AUTHORING_INTENT_EXPANSION_RECIPE_REMATCH_BLOCK_HEADER =
    '[Studio — expanded intent aligned to recipe catalog (pass-2 routing)]'

  /**
   * LLM intent-expansion bullets only (no wire prefix). Empty when expansion should not run or failed.
   */
  static String generateAuthoringIntentExpansionText(
    String bodyPromptForCandidate,
    String apiKey,
    String model,
    String wireBaseUrl,
    Map toolsLoopSessionBundle
  ) {
    def cand = (bodyPromptForCandidate ?: '').toString()
    if (!cand.trim()) {
      return ''
    }
    if (!AuthoringPreviewContext.isAuthoringIntentExpansionCandidate(cand)) {
      return ''
    }
    boolean needsExternal = authorRequestNeedsExternalContentResolution(cand)
    if (!needsExternal &&
      authorRequestIsConcreteFieldEdit(cand) &&
      isUsableHotpathOutcomePhrase(extractAuthoringOutcomePhrase(cand), cand)) {
      log.debug('generateAuthoringIntentExpansionText: skip — concrete field-level edit with explicit outcome text')
      return ''
    }
    def key = (apiKey ?: '').toString().trim()
    if (!key) {
      return ''
    }
    if (aiAssistantPipelineCancelEffective()) {
      return ''
    }
    def mdl = (model ?: '').toString().trim()
    if (!mdl) {
      log.warn('generateAuthoringIntentExpansionText: missing model id, skipping expansion')
      return ''
    }
    try {
      String expanded = toolsLoopSimpleCompletionAssistantText(
        key,
        mdl,
        ToolPrompts.getLlm_AUTHORING_INTENT_EXPANSION_SYSTEM(),
        cand,
        1024,
        120_000,
        'AuthoringIntentExpansion',
        wireBaseUrl,
        toolsLoopSessionBundle
      )
      expanded = (expanded ?: '').toString().trim()
      if (!expanded || expanded.length() > 6_000) {
        return ''
      }
      return expanded
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt()
      return ''
    } catch (Throwable t) {
      log.warn('generateAuthoringIntentExpansionText skipped: {}', t.message)
      return ''
    }
  }

  /**
   * Pass-2 expansion when pass-1 recipe routing missed: restate author goal toward a catalog {@code recipeId}
   * (see {@link ToolPrompts#getLlm_AUTHORING_INTENT_EXPANSION_RECIPE_REMATCH_SYSTEM}).
   */
  static String generateAuthoringIntentExpansionTextForRecipeRematch(
    String bodyPromptForCandidate,
    String recipeCatalogMarkdown,
    String apiKey,
    String model,
    String wireBaseUrl,
    Map toolsLoopSessionBundle
  ) {
    def cand = (bodyPromptForCandidate ?: '').toString()
    if (!cand.trim()) {
      return ''
    }
    if (!AuthoringPreviewContext.isAuthoringIntentExpansionCandidate(cand)) {
      return ''
    }
    def key = (apiKey ?: '').toString().trim()
    if (!key) {
      return ''
    }
    if (aiAssistantPipelineCancelEffective()) {
      return ''
    }
    def mdl = (model ?: '').toString().trim()
    if (!mdl) {
      log.warn('generateAuthoringIntentExpansionTextForRecipeRematch: missing model id, skipping')
      return ''
    }
    String catalogMd = (recipeCatalogMarkdown ?: '').toString().trim()
    if (!catalogMd) {
      catalogMd = '(no recipes configured)'
    }
    String userRematch =
      '## Recipe catalog\n\n' +
        catalogMd +
        '\n\n## Author message (pass-1 intent router did not match)\n\n' +
        cand
    try {
      String expanded = toolsLoopSimpleCompletionAssistantText(
        key,
        mdl,
        ToolPrompts.getLlm_AUTHORING_INTENT_EXPANSION_RECIPE_REMATCH_SYSTEM(),
        userRematch,
        768,
        120_000,
        'AuthoringIntentExpansionRecipeRematch',
        wireBaseUrl,
        toolsLoopSessionBundle
      )
      expanded = (expanded ?: '').toString().trim()
      if (!expanded || expanded.length() > 6_000) {
        return ''
      }
      if (!expanded.toLowerCase(Locale.ROOT).contains('recipe match hint:')) {
        log.info(
          'AuthoringIntentExpansionRecipeRematch: output missing Recipe match hint line — still using for pass-2 router'
        )
      }
      return expanded
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt()
      return ''
    } catch (Throwable t) {
      log.warn('generateAuthoringIntentExpansionTextForRecipeRematch skipped: {}', t.message)
      return ''
    }
  }

  static String formatAuthoringIntentExpansionWirePrefix(String expansionText) {
    return formatAuthoringIntentExpansionWirePrefix(expansionText, false)
  }

  static String formatAuthoringIntentExpansionWirePrefix(String expansionText, boolean recipeRematch) {
    String exp = (expansionText ?: '').toString().trim()
    if (!exp) {
      return ''
    }
    String header = Boolean.TRUE.equals(recipeRematch) ?
      AUTHORING_INTENT_EXPANSION_RECIPE_REMATCH_BLOCK_HEADER :
      AUTHORING_INTENT_EXPANSION_BLOCK_HEADER
    return header + '\n' + exp + '\n\n---\n\n'
  }

  /**
   * Optional **pre-tools** expansion prefix (legacy callers). Prefer {@link #intentRecipeRoutingPrelude} rematch path.
   */
  static String maybePrependAuthoringIntentExpansionBlock(
    String bodyPromptForCandidate,
    String userMessageAfterGuard,
    String apiKey,
    String model,
    String wireBaseUrl,
    Map toolsLoopSessionBundle,
    boolean authoringIntentExpansionEnabled = false
  ) {
    def guard = (userMessageAfterGuard ?: '').toString()
    if (!authoringIntentExpansionEnabled || !guard.trim()) {
      return guard
    }
    String expanded = generateAuthoringIntentExpansionText(bodyPromptForCandidate, apiKey, model, wireBaseUrl, toolsLoopSessionBundle)
    if (!expanded) {
      return guard
    }
    if (guard.contains('[Studio — recipe engine prefetch]') || guard.contains('[Studio — matched authoring intent recipe]')) {
      return guard
    }
    return formatAuthoringIntentExpansionWirePrefix(expanded) + guard
  }

  private static String intentRecipeRematchRouterVisible(String expansionText, String originalRouterVisible) {
    String exp = (expansionText ?: '').toString().trim()
    String orig = (originalRouterVisible ?: '').toString().trim()
    if (!exp) {
      return orig
    }
    if (!orig) {
      return exp
    }
    return exp + '\n\n' + orig
  }

  /**
   * One routing pass: deterministic signals, JSON router, deterministic fallback after router miss.
   * @return map with {@code matched} (boolean) and match fields when true
   */
  private static Map intentRecipeRoutingMatchPass(
    List recipes,
    Map cfg,
    Map detCtx,
    String routerVisible,
    String apiKey,
    String model,
    String wireBaseUrl,
    Map toolsLoopSessionBundle,
    String authorFieldLabelEarly
  ) {
    Map out = [matched: false]
    Map detMatch = AuthoringIntentRecipeCatalog.findDeterministicRecipeMatch(recipes, detCtx)
    if (detMatch != null) {
      out.matched = true
      out.recipe = detMatch.recipe
      out.recipeId = detMatch.recipeId?.toString()?.trim()
      out.confidence = 1.0d
      out.routerReason = detMatch.routerReason?.toString()
      out.skipRecipePrefetch = Boolean.TRUE.equals(detMatch.skipPrefetch)
      out.matchPass = 'deterministic'
      return out
    }
    List routerRecipes = AuthoringIntentRecipeCatalog.filterRecipesEligibleForRouter(recipes, routerVisible)
    String catalogMd = AuthoringIntentRecipeCatalog.toRouterCatalogMarkdown(routerRecipes)
    String userRouter = '## Recipe catalog\n\n' + catalogMd + '\n\n## Author message\n\n' + routerVisible
    String rawJson = toolsLoopSimpleCompletionAssistantText(
      apiKey,
      model,
      ToolPrompts.getLlm_AUTHORING_INTENT_RECIPE_ROUTER_SYSTEM(),
      userRouter,
      256,
      120_000,
      'IntentRecipeRouter',
      wireBaseUrl,
      toolsLoopSessionBundle
    )
    Map decision = AuthoringIntentRecipeRouter.parseRouterJson(rawJson)
    double minC = StudioAiAssistantProjectConfig.intentRecipeMinConfidence(cfg)
    double conf = 0.0d
    try {
      def c = decision.get('confidence')
      if (c instanceof Number) {
        conf = ((Number) c).doubleValue()
      }
    } catch (Throwable ignoredConf) {
      conf = 0.0d
    }
    String rid = decision.recipeId?.toString()?.trim()
    Map recipe = rid ? AuthoringIntentRecipeCatalog.findRecipeById(recipes, rid) : null
    if (recipe != null && AuthoringIntentRecipeCatalog.recipeExcludedByDontMatchHints(recipe, routerVisible)) {
      recipe = null
      rid = null
    }
    if (recipe != null && conf >= minC) {
      out.matched = true
      out.recipe = recipe
      out.recipeId = rid
      out.confidence = conf
      out.minConfidence = minC
      out.routerReason = decision.reason?.toString()
      out.skipRecipePrefetch = false
      out.matchPass = 'router'
      out.catalogMd = catalogMd
      return out
    }
    Map fbDet = AuthoringIntentRecipeCatalog.findDeterministicRecipeMatch(recipes, detCtx)
    if (fbDet != null) {
      out.matched = true
      out.recipe = fbDet.recipe
      out.recipeId = fbDet.recipeId?.toString()?.trim()
      out.confidence = 1.0d
      out.minConfidence = minC
      out.routerReason = fbDet.routerReason?.toString()
      out.skipRecipePrefetch = Boolean.TRUE.equals(fbDet.skipPrefetch)
      out.matchPass = 'deterministic_after_router'
      out.catalogMd = catalogMd
      out.routerDecision = decision
      out.routerConfidence = conf
      return out
    }
    out.minConfidence = minC
    out.catalogMd = catalogMd
    out.routerDecision = decision
    out.routerConfidence = conf
    out.routerRecipeId = rid
    out.routerRecipeFound = recipe != null
    out.matchPass = 'no_match'
    return out
  }

  /**
   * Stable summary for maintainers / session debug logs (also emitted as SSE {@code intent-recipe-routing}).
   * When {@code cfg} is null, routing/engine flags are recorded as {@code false}.
   */
  private static Map intentRecipeAttachTelemetry(StudioToolOperations ops, Map cfg, Map result, String outcome, Map extra = null) {
    Map tel = new LinkedHashMap()
    boolean routing = false
    boolean engine = false
    if (cfg != null) {
      routing = StudioAiAssistantProjectConfig.intentRecipeRoutingEnabled(cfg)
      engine = StudioAiAssistantProjectConfig.intentRecipeEngineEnabled(cfg)
    }
    tel.put('intentRecipeRoutingEnabled', routing)
    tel.put('intentRecipeEngineEnabled', engine)
    tel.put('outcome', (outcome ?: 'unknown').toString())
    if (extra != null && !extra.isEmpty()) {
      extra.each { k, v ->
        if (v != null) {
          tel.put(k.toString(), v)
        }
      }
    }
    tel.put('intentMatched', 'matched'.equals((outcome ?: '').toString()))
    if (!tel.containsKey('prefetchSteps')) {
      tel.put('prefetchSteps', [])
    }
    if (!tel.containsKey('prefetchEnvelopeTruncated')) {
      tel.put('prefetchEnvelopeTruncated', false)
    }
    if (!tel.containsKey('prefetchRan')) {
      tel.put('prefetchRan', false)
    }
    result.put('intentRecipeRoutingTelemetry', tel)
    return result
  }

  /**
   * Optional pre-tools **intent recipe** routing: **pass 1** match only (deterministic → JSON router → deterministic
   * fallback). When pass 1 misses and {@code allowExpansionRematch}, run LLM intent expansion then **pass 2** rematch on
   * expanded author-visible text. Matched or unmatched outcomes may prepend the expansion wire block once (no separate
   * post-routing expansion prepend).
   * <p>Eligibility matches {@link AuthoringPreviewContext#intentRecipeRouterEligibilitySkipReason}.</p>
   *
   * @return keys: {@code clarificationOnly} (Boolean), {@code userTextForToolsLoop} (String), {@code clarificationUserText} (String body for tools-off clarification when clarificationOnly), {@code intentRecipeRoutingTelemetry} (Map with at least {@code outcome})
   */
  private static Map intentRecipeRoutingAttachMatchedRecipe(
    StudioToolOperations ops,
    Map cfg,
    Map result,
    String userTextAfterGuard,
    Map recipe,
    String rid,
    double conf,
    double minC,
    String routerReason,
    String visible,
    String authorFieldLabelOverride = null,
    boolean skipRecipePrefetch = false,
    String expansionWirePrefix = ''
  ) {
    Map pfb = skipRecipePrefetch ?
      [
        markdown                : '',
        prefetchSteps           : [],
        prefetchEnvelopeTruncated: false,
        initialBindings         : [:]
      ] :
      AuthoringIntentRecipeEngine.runPrefetchBlock(ops, recipe, cfg)
    String prefetch = (pfb.markdown ?: '').toString()
    List pfbSteps = pfb.prefetchSteps instanceof List ? (List) pfb.prefetchSteps : []
    boolean prefetchEnvTrunc = Boolean.TRUE.equals(pfb.prefetchEnvelopeTruncated)
    boolean prefetchRan = prefetch.trim().length() > 0
    Map hotpathMeta = AuthoringIntentRecipeEngine.buildPrefetchHotpathDirective(ops, prefetch)
    String hotpathDirective = (hotpathMeta?.directive ?: '').toString()
    boolean prefetchSkipRedundantGetForListedPath = Boolean.TRUE.equals(hotpathMeta?.duplicateGetContentBanned)
    String authorFieldLabel = (authorFieldLabelOverride ?: extractAuthorFieldLabelPhrase(visible ?: '')).toString()
    Map fieldHot = 'open_page_inquiry'.equals(rid) ?
      [directive: '', resolvedFieldId: '', resolvedFieldLabel: ''] :
      AuthoringIntentRecipeEngine.buildSimpleFieldEditHotpathExtras(prefetch, authorFieldLabel)
    hotpathDirective = hotpathDirective + (fieldHot?.directive ?: '').toString()
    if ('open_page_inquiry'.equals(rid)) {
      if (prefetchSkipRedundantGetForListedPath) {
        hotpathDirective =
          '[Studio — read-only page inquiry: Recipe-engine prefetch already includes successful **GetContent** with full **contentXml** for the anchored path. ' +
          'Answer in prose from that XML. Do **not** call **GetContent** again on this path. Do **not** WriteContent unless the author explicitly asks to edit.\n\n'
      } else {
        hotpathDirective = ''
      }
    }
    String prefetchResolvedFieldId = (fieldHot?.resolvedFieldId ?: '').toString().trim()
    String prefetchResolvedFieldLabel = (fieldHot?.resolvedFieldLabel ?: '').toString().trim()
    Map<String, Map> recipeInitialBindings = pfb.initialBindings instanceof Map ?
      (Map<String, Map>) pfb.initialBindings :
      [:]
    Map<String, Map> recipeCurrentBindings = AuthoringIntentRecipeBindings.deepCopyBindingMap(recipeInitialBindings)
    String prelude =
      AuthoringIntentRecipeCatalog.formatMatchedRecipePrelude(
        recipe,
        rid,
        conf,
        routerReason,
        recipeInitialBindings,
        recipeCurrentBindings
      )
    String orchPrelude = AuthoringIntentRecipeCatalog.matchedUserPrelude(recipe)
    if (orchPrelude) {
      prelude = orchPrelude + '\n\n' + prelude
    }
    Map matchedTelExtra = new LinkedHashMap<>()
    matchedTelExtra.putAll(AuthoringIntentRecipeCatalog.orchestrationTelemetryExtras(recipe))
    matchedTelExtra.putAll([
      recipeId                                     : rid,
      recipeTitle                                  : (recipe?.title?.toString()?.trim() ?: rid),
      confidence                                   : conf,
      minConfidence                                : minC,
      recipeFoundInCatalog                         : true,
      prefetchRan                                  : prefetchRan,
      prefetchSteps                                : pfbSteps,
      prefetchEnvelopeTruncated                    : prefetchEnvTrunc,
      prefetchSkipRedundantGetContentForListedPath : prefetchSkipRedundantGetForListedPath,
      prefetchResolvedFieldId                      : prefetchResolvedFieldId,
      prefetchResolvedFieldLabel                   : prefetchResolvedFieldLabel,
      routerReason                                 : (routerReason ?: '').toString().trim(),
      recipeChatLine                               : AuthoringIntentRecipeCatalog.formatIntentRecipeChatLine(recipe)
    ])
    if ('open_page_inquiry'.equals(rid) && prefetchSkipRedundantGetForListedPath && !prefetchEnvTrunc) {
      matchedTelExtra.toolsLoopDisable = Boolean.TRUE
    }
    String inquiryHint = ''
    String rr = (routerReason ?: '').toString()
    if ('open_page_inquiry'.equals(rid) || rr.contains('open_page_inquiry')) {
      inquiryHint =
        '[Studio — open page inquiry (read-only): Answer what this anchored page is about using prefetch/GetContent XML. ' +
        'Summarize for the author in plain prose. Do **not** WriteContent, update_template, or read CSS/FTL unless they ask to change something.]\n\n'
    }
    String externalHint = ''
    if (rr.contains('external_content') ||
      authorRequestNeedsExternalContentResolution(userTextAfterGuard ?: '') ||
      authorRequestNeedsExternalContentResolution(visible ?: '')) {
      externalHint =
        '[Studio — the author asked to **look up / fetch** text (e.g. song lyrics) and place it in a CMS field. ' +
        'Resolve the full lyrics or requested copy first (model knowledge or FetchHttpUrl). ' +
        'Do **not** write the instruction sentence, song title alone, or “lyrics of …” meta-text as the field value. ' +
        'Then GetContent → WriteContent the full resolved HTML/text on the anchored path.]\n\n'
    }
    String expPrefix = (expansionWirePrefix ?: '').toString()
    result.userTextForToolsLoop = expPrefix + inquiryHint + prefetch + hotpathDirective + externalHint + prelude + (userTextAfterGuard ?: '')
    if (expPrefix.trim()) {
      matchedTelExtra.intentExpansionRematch = Boolean.TRUE
    }
    return intentRecipeAttachTelemetry(ops, cfg, result, 'matched', matchedTelExtra)
  }

  static Map intentRecipeRoutingPrelude(
    String bodyPrompt,
    String userTextAfterGuard,
    String apiKey,
    String model,
    String wireBaseUrl,
    Map toolsLoopSessionBundle,
    StudioToolOperations ops,
    boolean allowExpansionRematch = false
  ) {
    Map result = [
      clarificationOnly     : false,
      userTextForToolsLoop   : (userTextAfterGuard ?: '').toString(),
      clarificationUserText: ''
    ]
    Map cfg = null
    try {
      if (ops == null) {
        return intentRecipeAttachTelemetry(ops, null, result, 'skipped_ops_null')
      }
      cfg = StudioAiAssistantProjectConfig.load(ops)
      String cand = (bodyPrompt ?: '').toString()
      if (!StudioAiAssistantProjectConfig.intentRecipeRoutingEnabled(cfg)) {
        if (cand.trim() && AuthoringPreviewContext.intentRecipeRouterEligibilitySkipReason(cand) == null) {
          log.debug(
            'Intent recipe routing skipped: intentRecipeRouting.enabled is not true in site tools.json — enable under Project Tools → AI Assistant → Tools and MCP → Intent recipe routing.'
          )
        }
        return intentRecipeAttachTelemetry(ops, cfg, result, 'skipped_disabled')
      }
      if (!cand.trim()) {
        return intentRecipeAttachTelemetry(ops, cfg, result, 'skipped_empty_prompt')
      }
      String eligibilitySkip = AuthoringPreviewContext.intentRecipeRouterEligibilitySkipReason(cand)
      if (eligibilitySkip != null) {
        log.info(
          'Intent recipe routing skipped: not an intent-expansion candidate (reason={}) — routing and prefetch do not run. Short prompts: author-visible text after stripping Request anchor / Studio blocks must be ≤320 chars with a CMS signal, OR longer prompts need an http(s) or external host plus visual/reference language.',
          eligibilitySkip
        )
        return intentRecipeAttachTelemetry(ops, cfg, result, 'skipped_eligibility', [eligibilitySkipReason: eligibilitySkip])
      }
      String key = (apiKey ?: '').toString().trim()
      if (!key) {
        log.warn('Intent recipe routing skipped: empty tools-loop API key (cannot call IntentRecipeRouter).')
        return intentRecipeAttachTelemetry(ops, cfg, result, 'skipped_no_api_key')
      }
      if (aiAssistantPipelineCancelEffective()) {
        return intentRecipeAttachTelemetry(ops, cfg, result, 'skipped_cancelled')
      }
      String mdl = (model ?: '').toString().trim()
      if (!mdl) {
        log.warn('Intent recipe routing skipped: empty resolved chat model (cannot call IntentRecipeRouter).')
        return intentRecipeAttachTelemetry(ops, cfg, result, 'skipped_no_model')
      }
      List recipes = AuthoringIntentRecipeCatalog.loadRecipes(ops, cfg)
      int recipeCatalogSize = recipes != null ? recipes.size() : 0
      boolean catalogHasOpenPageInquiry =
        AuthoringIntentRecipeCatalog.findRecipeById(recipes, 'open_page_inquiry') != null
      Map catalogTel = [recipeCatalogSize: recipeCatalogSize, catalogHasOpenPageInquiry: catalogHasOpenPageInquiry]
      if (recipes == null || recipes.isEmpty()) {
        log.warn('Intent recipe routing skipped: recipe catalog is empty after bundled + site custom merge.')
        result.userTextForToolsLoop =
          '[Studio — intent recipe catalog is empty after merge (site override removed all recipes, or custom JSON invalid). Use normal CMS judgement **with strict content-vs-code discipline**:\n' +
          '- When **Current content item repository path** or **Request anchor** is **`/site/.../*.xml`** and the author asks to change **copy, field values, or tone** without naming **FTL**, **template**, or **CSS**: **GetContent** then **WriteContent** (or **update_content** then **WriteContent**) on **that same repository .xml path** — preserve **`<page>` / `<component>`** structure and existing field tag names from the file you read; map labels to element ids via **GetContentTypeFormDefinition** when needed.\n' +
          '- **Do not** call **update_template** for that scenario; **do not** **WriteContent** a **`.ftl`** path with page/component XML bodies; **do not** invent **`/static-assets/styles.css`** or other asset paths unless the author explicitly asked for stylesheet/asset work **or** **GetContent** on the item you edit already referenced that exact path and the task requires editing that file.\n' +
          '- If copy still looks wrong after XML saves, **analyze_template** / **GetContent** on **display-template** is **read-only diagnosis** — explain findings; **do not** patch FTL for a **content-only** goal.\n\n' +
          (userTextAfterGuard ?: '')
        return intentRecipeAttachTelemetry(
          ops,
          cfg,
          result,
          'skipped_no_recipes',
          [recipeCatalogEmpty: true] + catalogTel
        )
      }
      String visible = cand
      try {
        visible = AuthoringPreviewContext.stripStudioInjectedPromptBlocks(cand)
      } catch (Throwable ignored) {
        visible = cand
      }
      visible = (visible ?: '').trim()
      if (!visible) {
        log.warn('Intent recipe routing skipped: author-visible text empty after strip (unexpected after eligibility pass).')
        return intentRecipeAttachTelemetry(ops, cfg, result, 'skipped_visible_empty')
      }
      String currentAuthorVisible = AuthoringPreviewContext.extractAuthorCurrentRequestVisible(cand)?.trim()
      if (!currentAuthorVisible) {
        currentAuthorVisible = visible
      }
      String routerVisible = currentAuthorVisible
      String authorFieldLabelEarly = extractAuthorFieldLabelPhrase(cand)
      if (!authorFieldLabelEarly) {
        authorFieldLabelEarly = extractAuthorFieldLabelPhrase(routerVisible)
      }
      boolean concreteField =
        authorRequestIsConcreteFieldEdit(cand) || authorRequestIsConcreteFieldEdit(routerVisible)
      Closure<Boolean> anchoredSiteXml = {
        Map bindEarly = ops.recipeEngineAuthoringBindings()
        String anchorEarly = (bindEarly?.contentPath ?: '').toString().trim()
        if (!anchorEarly) {
          anchorEarly = AuthoringPreviewContext.extractAnchoredRepositoryPath(cand)
        }
        if (!anchorEarly) {
          anchorEarly = AuthoringPreviewContext.extractAnchoredRepositoryPath(routerVisible)
        }
        return anchorEarly &&
          anchorEarly.toLowerCase(Locale.ROOT).startsWith('/site/') &&
          anchorEarly.toLowerCase(Locale.ROOT).endsWith('.xml')
      }
      Map detCtx = [
        cand                          : cand,
        routerVisible                 : routerVisible,
        ops                           : ops,
        evaluateTranslateIntent       : { -> authorRequestLooksLikeTranslateIntent(cand, routerVisible) },
        evaluateConcreteFieldEdit     : {
          anchoredSiteXml.call() &&
            intentRecipeDeterministicMatchForFieldEdit(cand, routerVisible, authorFieldLabelEarly, concreteField)
        },
        evaluateExternalContentFieldEdit: {
          if (!anchoredSiteXml.call()) {
            return false
          }
          if (!(authorRequestNeedsExternalContentResolution(cand) ||
            authorRequestNeedsExternalContentResolution(routerVisible))) {
            return false
          }
          String label = authorFieldLabelEarly ?: extractAuthorFieldLabelPhrase(cand) ?: extractAuthorFieldLabelPhrase(routerVisible)
          return (label ?: '').trim().length() > 0
        }
      ]
      Map pass1 = intentRecipeRoutingMatchPass(
        recipes,
        cfg,
        detCtx,
        routerVisible,
        key,
        mdl,
        wireBaseUrl,
        toolsLoopSessionBundle,
        authorFieldLabelEarly
      )
      String expansionWirePrefix = ''
      Map activePass = pass1
      if (!Boolean.TRUE.equals(pass1.matched) && allowExpansionRematch) {
        List routerRecipesForExpansion =
          AuthoringIntentRecipeCatalog.filterRecipesEligibleForRouter(recipes, routerVisible)
        String catalogMdForExpansion =
          AuthoringIntentRecipeCatalog.toRouterCatalogMarkdown(routerRecipesForExpansion)
        String expansionText = generateAuthoringIntentExpansionTextForRecipeRematch(
          cand,
          catalogMdForExpansion,
          key,
          mdl,
          wireBaseUrl,
          toolsLoopSessionBundle
        )
        if (!expansionText?.trim()) {
          expansionText = generateAuthoringIntentExpansionText(cand, key, mdl, wireBaseUrl, toolsLoopSessionBundle)
        }
        if (expansionText?.trim()) {
          expansionWirePrefix = formatAuthoringIntentExpansionWirePrefix(expansionText, true)
          String rematchVisible = intentRecipeRematchRouterVisible(expansionText, routerVisible)
          log.info(
            'Intent recipe routing: pass-1 no match — running intent expansion + pass-2 rematch (rematchVisibleChars={})',
            rematchVisible.length()
          )
          Map pass2 = intentRecipeRoutingMatchPass(
            recipes,
            cfg,
            detCtx,
            rematchVisible,
            key,
            mdl,
            wireBaseUrl,
            toolsLoopSessionBundle,
            authorFieldLabelEarly
          )
          if (Boolean.TRUE.equals(pass2.matched)) {
            log.info('Intent recipe routing: pass-2 matched after expansion (matchPass={})', pass2.matchPass)
            activePass = pass2
          } else {
            log.info('Intent recipe routing: pass-2 still no match after expansion')
            activePass = pass2
          }
        }
      }
      if (Boolean.TRUE.equals(activePass.matched)) {
        double minC = activePass.minConfidence instanceof Number ?
          ((Number) activePass.minConfidence).doubleValue() :
          StudioAiAssistantProjectConfig.intentRecipeMinConfidence(cfg)
        log.info(
          'Intent recipe routing matched recipeId={} confidence={} matchPass={} expansionRematch={}',
          activePass.recipeId,
          activePass.confidence,
          activePass.matchPass,
          expansionWirePrefix ? 'yes' : 'no'
        )
        Map matchedRoute = intentRecipeRoutingAttachMatchedRecipe(
          ops,
          cfg,
          result,
          userTextAfterGuard,
          activePass.recipe as Map,
          activePass.recipeId?.toString()?.trim(),
          activePass.confidence instanceof Number ? ((Number) activePass.confidence).doubleValue() : 1.0d,
          minC,
          activePass.routerReason?.toString(),
          routerVisible,
          authorFieldLabelEarly,
          Boolean.TRUE.equals(activePass.skipRecipePrefetch),
          expansionWirePrefix
        )
        if (matchedRoute.intentRecipeRoutingTelemetry instanceof Map) {
          ((Map) matchedRoute.intentRecipeRoutingTelemetry).putAll(catalogTel)
        }
        return matchedRoute
      }
      Map decision = activePass.routerDecision instanceof Map ? (Map) activePass.routerDecision : [:]
      double minC = activePass.minConfidence instanceof Number ?
        ((Number) activePass.minConfidence).doubleValue() :
        StudioAiAssistantProjectConfig.intentRecipeMinConfidence(cfg)
      double conf = activePass.routerConfidence instanceof Number ? ((Number) activePass.routerConfidence).doubleValue() : 0.0d
      String rid = activePass.routerRecipeId?.toString()?.trim() ?: ''
      boolean recipeFound = Boolean.TRUE.equals(activePass.routerRecipeFound)
      String catalogMd = activePass.catalogMd?.toString() ?: ''
      log.info(
        'Intent recipe routing: no confident match after pass-1{} — recipeId={}, confidence={}, minConfidence={}, expansionRematch={}',
        expansionWirePrefix ? ' + pass-2' : '',
        rid ?: '(null)',
        conf,
        minC,
        expansionWirePrefix ? 'attempted' : 'no'
      )
      if (StudioAiAssistantProjectConfig.intentRecipeRequestClarificationOnUnmatched(cfg)) {
        result.clarificationOnly = true
        result.clarificationUserText =
          expansionWirePrefix +
            '## Recipe catalog (titles for disambiguation)\n\n' +
            catalogMd +
            '\n\n## Author message\n\n' +
            routerVisible +
            '\n\n---\n\n[Studio — intent recipe router: no confident match. reason: ' +
            (decision.reason?.toString()?.trim() ?: 'n/a') +
            ']\n'
        return intentRecipeAttachTelemetry(
          ops,
          cfg,
          result,
          'clarification_only',
          [
            recipeId                 : rid,
            confidence               : conf,
            minConfidence            : minC,
            recipeFoundInCatalog     : recipeFound,
            routerReason             : (decision.reason?.toString()?.trim() ?: ''),
            intentExpansionRematch   : expansionWirePrefix ? Boolean.TRUE : Boolean.FALSE
          ]
        )
      }
      String noMatchHint =
        AuthoringPreviewContext.authorVisibleSuggestsOpenPageInquiry(cand) ?
          '[Studio — open page inquiry (read-only): The author asked what **this page** is about. Call **GetContent** on the anchored **`/site/.../*.xml`** path first, then answer from that XML. Do **not** ResearchSiteContent, WriteContent, update_template, or guess CSS/FTL paths.]\n\n' :
          '[Studio — recipe intent router: no confident recipe match; proceed with normal CMS judgement. For **/site/.../*.xml** copy or field-only asks (no explicit FTL/CSS/template wording), use **GetContent**/**WriteContent** on **those XML paths** — not **update_template**, not **WriteContent** on **`.ftl`** with XML bodies, and not guessed **`/static-assets/styles.css`** unless the author asked for stylesheet work.]\n\n'
      result.userTextForToolsLoop = expansionWirePrefix + noMatchHint + (userTextAfterGuard ?: '')
      Map noMatchTel = [
        recipeId               : rid,
        confidence             : conf,
        minConfidence          : minC,
        recipeFoundInCatalog   : recipeFound,
        routerReason           : (decision.reason?.toString()?.trim() ?: ''),
        intentExpansionRematch : expansionWirePrefix ? Boolean.TRUE : Boolean.FALSE
      ]
      noMatchTel.putAll(catalogTel)
      return intentRecipeAttachTelemetry(ops, cfg, result, 'no_match', noMatchTel)
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt()
      return intentRecipeAttachTelemetry(ops, cfg, result, 'skipped_interrupted')
    } catch (Throwable t) {
      log.warn('intentRecipeRoutingPrelude skipped: {}', t.message)
      return intentRecipeAttachTelemetry(ops, cfg, result, 'error', [errorMessage: (t.message ?: t.toString())])
    }
  }

  /**
   * Headless native-tools chat completion (no servlet {@code AiOrchestration}): same Studio {@code tools[]} + execution loop as
   * interactive chat. {@code tools} must be non-empty — callers must not substitute a tools-off completion.
   * <p>{@code userText} is prefixed with the tools-loop user-message policy prefix ({@link ToolPrompts#getLlm_USER_MESSAGE_TOOLS_POLICY_PREFIX()}).</p>
   */
  static String llmHeadlessNativeToolsCompletion(
    String apiKey,
    String model,
    String systemText,
    String userText,
    List tools,
    String agentIdForLogs,
    int maxOutTokens = 8192,
    int readTimeoutMs = 600_000,
    String workerPhasePrefix = 'HeadlessNative',
    String wireBaseUrl = null,
    Map toolsLoopSessionBundle = null
  ) {
    if (tools == null || tools.isEmpty()) {
      throw new IllegalStateException(
        'llmHeadlessNativeToolsCompletion: tools list is null or empty; refusing a tools-off completion.'
      )
    }
    String ut = (userText ?: '').toString()
    ut = maybePrependAuthoringIntentExpansionBlock(ut, ut, apiKey, model, wireBaseUrl, toolsLoopSessionBundle, false)
    String userForTools = ToolPrompts.getLlm_USER_MESSAGE_TOOLS_POLICY_PREFIX() + ut
    Prompt prompt = new Prompt([
      new SystemMessage((systemText ?: '').toString()),
      new UserMessage(userForTools)
    ])
    return executeNativeToolsViaRestClientReturnText(
      apiKey,
      model,
      prompt,
      tools,
      (agentIdForLogs ?: '').toString(),
      null,
      null,
      null,
      wireBaseUrl,
      toolsLoopSessionBundle,
      null
    )
  }

  private static List<Map> deepCloneWireMessages(List<Map> src) {
    if (src == null) {
      return []
    }
    def out = []
    for (def m : src) {
      if (m instanceof Map) {
        out << new LinkedHashMap((Map) m)
      }
    }
    out
  }

  private static Map lastUserWireMessage(List<Map> wire) {
    Map last = null
    for (def m : wire) {
      if (m instanceof Map && 'user'.equals(((Map) m).get('role')?.toString())) {
        last = (Map) m
      }
    }
    last
  }

  /** Flatten Chat Completions–style {@code user} {@code content} (string or content-parts list) for orchestration helpers. */
  private static String flattenWireUserContent(Object content) {
    if (content instanceof CharSequence) {
      return content.toString()
    }
    if (content instanceof List) {
      StringBuilder sb = new StringBuilder()
      for (def part : (List) content) {
        if (part instanceof Map) {
          Map pm = (Map) part
          def t = pm.get('text')
          if (t == null && pm.containsKey('content')) {
            t = pm.get('content')
          }
          if (t instanceof CharSequence && t.toString().trim()) {
            sb.append(t.toString())
          }
        }
      }
      return sb.toString()
    }
    return content != null ? content.toString() : ''
  }

  private static boolean wireToolsIncludeNamedTool(List wireTools, String toolName) {
    if (!(wireTools instanceof List) || wireTools.isEmpty() || !toolName?.trim()) {
      return false
    }
    String want = toolName.trim()
    for (def t : wireTools) {
      if (!(t instanceof Map)) {
        continue
      }
      def fn = ((Map) t).get('function')
      if (fn instanceof Map) {
        String n = (fn.get('name') ?: '').toString()
        if (want.equalsIgnoreCase(n)) {
          return true
        }
      }
    }
    return false
  }

  private static boolean wireToolsIncludeGenerateImage(List wireTools) {
    return wireToolsIncludeNamedTool(wireTools, 'GenerateImage')
  }

  /** Read-only anchored page summary — must never take the modify-page write hotpath. */
  private static boolean intentRecipeIsOpenPageInquiry(Map intentTel) {
    if (!(intentTel instanceof Map)) {
      return false
    }
    String rid = intentTel.recipeId?.toString()?.trim() ?: ''
    if ('open_page_inquiry'.equals(rid)) {
      return true
    }
    return (intentTel.routerReason?.toString() ?: '').contains('open_page_inquiry')
  }

  /**
   * When intent routing matched {@code modify_page_content} and prefetch already embedded full GetContent for the
   * anchor path, round 0 can call {@code WriteContent} directly. Concrete field edits require a resolved
   * {@code prefetchResolvedFieldId} (author label matched to form-definition {@code <title>}).
   */
  private static boolean prefetchHotpathAllowsForcedWriteContent(
    Map intentTel,
    String outcomePhrase,
    String authorVisible = null
  ) {
    if (!(intentTel instanceof Map)) {
      return false
    }
    if (!'matched'.equalsIgnoreCase(intentTel.outcome?.toString())) {
      return false
    }
    if (!Boolean.TRUE.equals(intentTel.prefetchHotpathForceWrite)) {
      return false
    }
    if (!Boolean.TRUE.equals(intentTel.prefetchSkipRedundantGetContentForListedPath)) {
      return false
    }
    if (Boolean.TRUE.equals(intentTel.prefetchEnvelopeTruncated)) {
      return false
    }
    if (authorRequestIsConcreteFieldEdit(authorVisible ?: '')) {
      return (intentTel.prefetchResolvedFieldId?.toString()?.trim() ?: '').length() > 0 &&
        isUsableHotpathOutcomePhrase(outcomePhrase, authorVisible)
    }
    return isUsableHotpathOutcomePhrase(outcomePhrase, authorVisible)
  }

  /** Server write hotpath when prefetch (or bootstrap) resolved field id + full contentXml for anchor path. */
  private static boolean serverConcreteFieldEditHotpathEligible(
    String authorVisible,
    String outcomePhrase,
    String resolvedFieldId,
    String contentXml,
    String contentPath
  ) {
    boolean fieldScoped =
      authorRequestIsConcreteFieldEdit(authorVisible ?: '') ||
        AuthoringPreviewContext.isShortAffirmationContinuingPriorCmsWork(authorVisible ?: '')
    if (!fieldScoped) {
      return false
    }
    if (!(outcomePhrase ?: '').trim()) {
      return false
    }
    if (!isUsableHotpathOutcomePhrase(outcomePhrase, authorVisible)) {
      return false
    }
    if (!(resolvedFieldId ?: '').trim() || !(contentXml ?: '').trim()) {
      return false
    }
    String p = (contentPath ?: '').trim()
    return p && p.toLowerCase(Locale.ROOT).startsWith('/site/') && p.toLowerCase(Locale.ROOT).endsWith('.xml')
  }

  /** {@link FunctionToolCallback#call} may return a {@link Map} or JSON text; hotpath must accept both. */
  private static Map coerceFunctionToolCallbackResultMap(Object raw) {
    if (raw instanceof Map) {
      return (Map) raw
    }
    String s = raw?.toString()?.trim()
    if (!s) {
      return [:]
    }
    try {
      Object parsed = new JsonSlurper().parseText(s)
      return parsed instanceof Map ? (Map) parsed : [:]
    } catch (Throwable ignored) {
      return [:]
    }
  }

  /** Aligns with tools-loop WriteContent success tracking ({@code ok} or {@code result=written}). */
  private static boolean writeContentToolResultSucceeded(Map writeRes) {
    if (!(writeRes instanceof Map) || writeRes.isEmpty()) {
      return false
    }
    if (Boolean.TRUE.equals(writeRes.ok)) {
      return true
    }
    return 'written'.equalsIgnoreCase((writeRes.result ?: '').toString().trim())
  }

  /** User prompt for a single inner completion that materializes lyrics / external copy for a CMS field write. */
  private static String buildExternalContentLookupUserPrompt(String authorVisible, String fieldLabel) {
    String tail = authorVisibleTailForOutcomePhrase(authorVisible)
    String label = (fieldLabel ?: '').trim()
    StringBuilder sb = new StringBuilder()
    sb.append('The author is editing a Crafter CMS content field')
    if (label) {
      sb.append(' ("').append(label).append('")')
    }
    sb.append('.\n\nAuthor request:\n').append(tail ?: authorVisible ?: '').append(
      '\n\nOutput ONLY the final text to store in the field (plain text). ' +
        'For song lyrics, include the complete standard lyrics with stanza breaks (blank lines between verses). ' +
        'No JSON, markdown fences, explanations, or instructions.'
    )
    return sb.toString()
  }

  private static boolean serverExternalContentFieldEditHotpathEligible(
    Map intentTel,
    String authorVisible,
    String fieldId,
    String contentXml,
    String contentPath
  ) {
    if (!authorRequestNeedsExternalContentResolution(authorVisible ?: '')) {
      return false
    }
    if (!(intentTel instanceof Map)) {
      return false
    }
    if (!'matched'.equalsIgnoreCase(intentTel.outcome?.toString())) {
      return false
    }
    if (!Boolean.TRUE.equals(intentTel.serverHotpathExternalContent)) {
      return false
    }
    if (!Boolean.TRUE.equals(intentTel.prefetchSkipRedundantGetContentForListedPath)) {
      return false
    }
    if (Boolean.TRUE.equals(intentTel.prefetchEnvelopeTruncated)) {
      return false
    }
    if (!(fieldId ?: '').trim() || !(contentXml ?: '').trim()) {
      return false
    }
    String p = (contentPath ?: '').trim()
    return p && p.toLowerCase(Locale.ROOT).startsWith('/site/') && p.toLowerCase(Locale.ROOT).endsWith('.xml')
  }

  /**
   * Shared write + preview verification after {@link AuthoringIntentRecipeEngine#patchContentXmlFieldValue}.
   *
   * @return assistant markdown, or {@code null} on failure
   */
  private static String completeServerPrefetchFieldWriteFromPatchedXml(
    StudioToolOperations ops,
    String siteId,
    String normPath,
    String patched,
    String fieldId,
    String outcomePhraseForPreview,
    Map toolsLoopSessionBundle,
    Map<String, FunctionToolCallback> byName,
    String origUser,
    String agentId,
    OutputStream sseOut,
    Map toolTimingCtx = null
  ) {
    Map writeRes
    long writeStartMs = System.currentTimeMillis()
    try {
      writeRes = ops.writeContent(siteId, normPath, patched, 'true')
    } catch (Throwable wex) {
      log.warn(
        'Tools-loop: server prefetch field hotpath writeContent threw path={} agentId={} reason={}',
        normPath,
        agentId,
        wex.message ?: wex.toString()
      )
      markPrefetchHotpathAborted(toolsLoopSessionBundle, wex.message?.toString() ?: 'write_failed', false)
      return null
    }
    if (Boolean.TRUE.equals(writeRes.blockedForFormClientApply)) {
      log.info(
        'Tools-loop: server prefetch field hotpath skipped — WriteContent blocked for form client-apply path={} agentId={}',
        normPath,
        agentId
      )
      return null
    }
    if (!writeContentToolResultSucceeded(writeRes)) {
      log.warn(
        'Tools-loop: server prefetch field hotpath writeContent did not succeed path={} agentId={} message={}',
        normPath,
        agentId,
        (writeRes?.message ?: writeRes?.error ?: 'WriteContent failed')?.toString()
      )
      markPrefetchHotpathAborted(toolsLoopSessionBundle, (writeRes?.message ?: writeRes?.error)?.toString(), false)
      return null
    }
    try {
      AuthoringIntentRecipeBindings.updateCurrentFromWrite(
        ops,
        normPath,
        [content: patched, contentXml: patched, path: normPath, contentPath: normPath]
      )
    } catch (Throwable ignoredHotpathBinding) {
    }
    markTaskCompletionWallMsIfUnset(toolTimingCtx)
    if (sseOut != null) {
      long writeDurMs = Math.max(0L, System.currentTimeMillis() - writeStartMs)
      writeToolProgressSse(
        sseOut,
        'WriteContent',
        'done',
        [path: normPath],
        null,
        writeRes,
        writeDurMs
      )
    }
    Boolean previewFound = null
    String previewUrl = ''
    List<Map> wireForPreview = [[role: 'user', content: origUser ?: '']]
    previewUrl = enginePreviewUrlFromWire(wireForPreview, toolsLoopSessionBundle)
    FunctionToolCallback previewCb = byName?.get('GetPreviewHtml')
    if (previewCb != null && previewUrl?.trim()) {
      try {
        Map prevIn = [url: previewUrl.trim()]
        if (siteId) {
          prevIn.siteId = siteId
        }
        Object prevRaw = previewCb.call(JsonOutput.toJson(prevIn))
        String prevJson = (prevRaw instanceof Map) ?
          JsonOutput.toJson((Map) prevRaw) :
          (prevRaw?.toString() ?: '')
        Map enriched = enrichGetPreviewHtmlToolResult(prevJson, outcomePhraseForPreview, new JsonSlurper())
        previewFound = enriched.previewGoalFound instanceof Boolean ? (Boolean) enriched.previewGoalFound : null
      } catch (Throwable pex) {
        log.warn(
          'Tools-loop: server prefetch field hotpath GetPreviewHtml failed url={} agentId={} reason={}',
          previewUrl,
          agentId,
          pex.message ?: pex.toString()
        )
      }
    }
    if (previewFound == Boolean.TRUE) {
      return synthesizePlanExecutionAfterVerifiedWrite(outcomePhraseForPreview, previewUrl)
    }
    String base = synthesizePlanExecutionAfterVerifiedWrite(outcomePhraseForPreview, previewUrl)
    if (previewFound == Boolean.FALSE) {
      return appendPreviewVerificationWarningIfNeeded(base, previewFound, outcomePhraseForPreview)
    }
    return base
  }

  /**
   * Look up lyrics / external copy with one inner completion, then write + preview — skips the tools-loop LLM rounds.
   */
  private static String tryServerPrefetchExternalContentFieldEditHotpath(
    String origUser,
    String authorVisible,
    Map intentTel,
    Map toolsLoopSessionBundle,
    Map<String, FunctionToolCallback> byName,
    String agentId,
    OutputStream sseOut,
    AtomicBoolean cancelRequested,
    Map toolTimingCtx = null
  ) {
    if (cancelRequested != null && cancelRequested.get()) {
      return null
    }
    String fieldId = (intentTel?.prefetchResolvedFieldId ?: '').toString().trim()
    Map gc = AuthoringIntentRecipeEngine.extractPrefetchSuccessfulGetContent(origUser ?: '')
    String path = (gc?.path ?: '').toString().trim()
    String contentXml = (gc?.contentXml ?: '').toString()
    StudioToolOperations ops = (toolsLoopSessionBundle?.studioOps instanceof StudioToolOperations) ?
      (StudioToolOperations) toolsLoopSessionBundle.studioOps :
      null
    if (ops == null) {
      return null
    }
    if (!fieldId || !contentXml?.trim()) {
      String label = extractAuthorFieldLabelPhrase(origUser ?: authorVisible)
      if (!label) {
        label = extractAuthorFieldLabelPhrase(authorVisible)
      }
      Map cfgBoot = null
      try {
        cfgBoot = StudioAiAssistantProjectConfig.load(ops)
      } catch (Throwable ignoredCfg) {
      }
      if (cfgBoot != null && label) {
        Map boot = AuthoringIntentRecipeEngine.bootstrapConcreteFieldEditPrefetch(ops, cfgBoot, label)
        if (Boolean.TRUE.equals(boot?.applied)) {
          fieldId = (boot.resolvedFieldId ?: fieldId).toString().trim()
          contentXml = (boot.contentXml ?: contentXml).toString()
          path = (boot.contentPath ?: path).toString().trim()
        }
      }
    }
    String promptForIntent = (origUser ?: authorVisible)
    if (!serverExternalContentFieldEditHotpathEligible(intentTel, promptForIntent, fieldId, contentXml, path)) {
      return null
    }
    String apiKey = StudioAiLlmKind.toolsLoopChatApiKeyFromBundle(toolsLoopSessionBundle)
    String model = (toolsLoopSessionBundle?.resolvedChatModel ?: '').toString().trim()
    String wireBaseUrl = StudioAiLlmKind.toolsLoopChatBaseUrlFromBundle(toolsLoopSessionBundle)
    if (!apiKey || !model) {
      log.info('Tools-loop: external content hotpath skipped — missing apiKey or model on session bundle')
      return null
    }
    String fieldLabel = (intentTel?.prefetchResolvedFieldLabel ?: extractAuthorFieldLabelPhrase(authorVisible) ?: '').toString().trim()
    String genPrompt = buildExternalContentLookupUserPrompt(authorVisible, fieldLabel)
    if (sseOut != null) {
      writeToolProgressSse(sseOut, 'GenerateTextNoTools', 'start', [:], null, null, null)
    }
    long genStartMs = System.currentTimeMillis()
    String generatedText = ''
    try {
      generatedText = toolsLoopSimpleCompletionAssistantText(
        apiKey,
        model,
        'You are a writing assistant invoked as a tool inside Crafter Studio. Follow the user text exactly. Output only what was asked.',
        genPrompt,
        2048,
        120_000,
        'GenerateTextNoTools',
        wireBaseUrl,
        toolsLoopSessionBundle
      )
    } catch (Throwable gex) {
      log.warn(
        'Tools-loop: external content hotpath GenerateTextNoTools failed agentId={} reason={}',
        agentId,
        gex.message ?: gex.toString()
      )
      return null
    }
    generatedText = (generatedText ?: '').toString().trim()
    if (sseOut != null) {
      long genDurMs = Math.max(0L, System.currentTimeMillis() - genStartMs)
      writeToolProgressSse(sseOut, 'GenerateTextNoTools', 'done', [:], null, null, genDurMs)
    }
    if (!generatedText || generatedText.length() < 8) {
      log.info('Tools-loop: external content hotpath skipped — inner completion returned empty or too short')
      return null
    }
    if (outcomePhraseLooksLikeInstructionNotContent(generatedText)) {
      log.info('Tools-loop: external content hotpath skipped — inner completion looks like instructions not copy')
      return null
    }
    String normPath = AuthoringPreviewContext.normalizeRepoPath(path)
    if (!normPath) {
      return null
    }
    String siteId = ''
    try {
      siteId = ops.resolveEffectiveSiteId('')
    } catch (Throwable ignoredSite) {
    }
    try {
      Map freshItem = ops.getContent(siteId, normPath) as Map
      String freshXml = (freshItem?.contentXml ?: '').toString()
      if (freshXml?.trim()) {
        contentXml = freshXml
      }
    } catch (Throwable gex) {
      log.warn(
        'Tools-loop: external content hotpath GetContent failed path={} agentId={} reason={}',
        normPath,
        agentId,
        gex.message ?: gex.toString()
      )
    }
    if (!contentXml?.trim() || !StudioToolOperations.looksLikeFullCrafterSiteContentItemDocument(normPath, contentXml)) {
      markPrefetchHotpathAborted(toolsLoopSessionBundle, 'no_content_xml', false)
      return null
    }
    String patched = AuthoringIntentRecipeEngine.patchContentXmlFieldValue(contentXml, fieldId, generatedText)
    if (!patched?.trim() || !StudioToolOperations.looksLikeFullCrafterSiteContentItemDocument(normPath, patched)) {
      markPrefetchHotpathAborted(toolsLoopSessionBundle, 'patch_produced_invalid_document', true)
      return null
    }
    String previewSnippet = generatedText.length() > 200 ? generatedText.substring(0, 197) + '…' : generatedText
    String result = completeServerPrefetchFieldWriteFromPatchedXml(
      ops,
      siteId,
      normPath,
      patched,
      fieldId,
      previewSnippet,
      toolsLoopSessionBundle,
      byName,
      origUser,
      agentId,
      sseOut,
      toolTimingCtx
    )
    if (result != null) {
      log.info(
        'Tools-loop: external content prefetch hotpath completed agentId={} fieldId={}',
        agentId,
        fieldId
      )
    }
    return result
  }

  /**
   * Revert anchored {@code /site/...} item via {@code revert_change} (initial/oldest or one step back only).
   * Content-aware version pick uses tool args from the model after {@code GetContentVersionHistory}, not site-specific snippets here.
   */
  private static String tryServerPrefetchContentAwareRevertHotpath(
    String origUser,
    String authorVisible,
    Map intentTel,
    Map toolsLoopSessionBundle,
    Map<String, FunctionToolCallback> byName,
    String agentId,
    OutputStream sseOut,
    AtomicBoolean cancelRequested,
    Map toolTimingCtx = null
  ) {
    if (cancelRequested != null && cancelRequested.get()) {
      return null
    }
    String visible = (authorVisible ?: '').trim()
    if (!AuthoringPreviewContext.authorVisibleSuggestsRevertIntent(visible)) {
      return null
    }
    String anchor = AuthoringPreviewContext.extractAnchoredRepositoryPath(origUser ?: authorVisible)
    if (!anchor?.trim()) {
      anchor = AuthoringPreviewContext.extractAnchoredRepositoryPath(authorVisible)
    }
    if (!anchor || !anchor.toLowerCase(Locale.ROOT).startsWith('/site/') ||
      !anchor.toLowerCase(Locale.ROOT).endsWith('.xml')) {
      return null
    }
    StudioToolOperations ops = (toolsLoopSessionBundle?.studioOps instanceof StudioToolOperations) ?
      (StudioToolOperations) toolsLoopSessionBundle.studioOps :
      null
    if (ops == null) {
      return null
    }
    boolean revertToInitial = AuthoringPreviewContext.authorVisibleSuggestsRevertToInitialVersion(visible)
    String siteId = ''
    try {
      siteId = ops.resolveEffectiveSiteId('')
    } catch (Throwable ignoredSite) {
    }
    FunctionToolCallback revertCb = byName?.get('revert_change')
    if (revertCb == null) {
      return null
    }
    Map revertArgs = [
      siteId           : siteId,
      path             : anchor,
      revertToInitial  : revertToInitial,
      revertToPrevious : !revertToInitial
    ]
    Map revertRes = coerceFunctionToolCallbackResultMap(revertCb.call(JsonOutput.toJson(revertArgs)))
    if (!Boolean.TRUE.equals(revertRes?.ok)) {
      return null
    }
    String selection = (revertRes?.versionSelection ?: '').toString().trim()
    String phrase = 'the selected Studio version'
    if ('initial'.equals(selection)) {
      phrase = 'the oldest revertible version in history (initial / first-created state)'
    } else if ('content_match'.equals(selection)) {
      phrase = 'the newest history version matching the content you described'
    } else if ('previous'.equals(selection)) {
      phrase = 'the immediate prior revertible version'
    }
    String checkedLine = 'initial'.equals(selection) ?
      '- Oldest revertible version resolved from GetContentVersionHistory' :
      ('content_match'.equals(selection) ?
        '- Version history scanned for matching field copy' :
        '- Immediate prior revertible version resolved from history')
    return """## Done

Reverted **${anchor}** to ${phrase}.

### What we checked
${checkedLine}
- Repository revert completed

[View preview](http://localhost:8080/?crafterSite=${siteId})"""
  }

  /**
   * Image-only author request: call {@code GenerateImage} on the server before the tools-loop LLM
   * so the turn cannot complete with prose-only fake tool JSON.
   */
  private static String tryServerPrefetchGenerateImageHotpath(
    String origUser,
    String authorVisible,
    Map toolsLoopSessionBundle,
    Map<String, FunctionToolCallback> byName,
    String agentId,
    OutputStream sseOut,
    AtomicBoolean cancelRequested,
    Map toolTimingCtx = null
  ) {
    if (cancelRequested != null && cancelRequested.get()) {
      return null
    }
    if (!AuthoringPreviewContext.authorCurrentRequestLooksLikeImageOnlyGenerate(origUser ?: authorVisible)) {
      return null
    }
    FunctionToolCallback genCb = byName?.get('GenerateImage')
    if (genCb == null) {
      return null
    }
    String prompt = AuthoringPreviewContext.extractAuthorCurrentRequestVisible(origUser ?: authorVisible)
    if (!prompt?.trim()) {
      prompt = (authorVisible ?: '').trim()
    }
    String imagePrompt = (prompt ?: '').replaceFirst(
      /(?is)^\s*(?:please\s+)?(?:generate|create|draw|make|render|illustrate)\s+(?:an?\s+)?(?:image|picture|illustration|art|photo)\s+(?:of\s+)?/,
      ''
    ).trim()
    if (!imagePrompt) {
      imagePrompt = prompt
    }
    Map args = [prompt: imagePrompt]
    String tcId = 'hotpath_gen_' + Long.toHexString(System.nanoTime())
    long genStartMs = System.currentTimeMillis()
    writeToolProgressSse(sseOut, 'GenerateImage', 'start', args, null, null, null)
    Map res
    try {
      ChatCompletionsToolWire.nativeToolCallIdBindingSet(tcId)
      res = coerceFunctionToolCallbackResultMap(genCb.call(JsonOutput.toJson(args)))
    } catch (Throwable genEx) {
      long genDurMs = Math.max(0L, System.currentTimeMillis() - genStartMs)
      writeToolProgressSse(sseOut, 'GenerateImage', 'error', args, genEx, null, genDurMs)
      log.warn('Tools-loop: GenerateImage hotpath failed agentId={}', agentId, genEx)
      return null
    } finally {
      ChatCompletionsToolWire.nativeToolCallIdBindingClear()
    }
    long genDurMs = Math.max(0L, System.currentTimeMillis() - genStartMs)
    if (!Boolean.TRUE.equals(res?.ok)) {
      writeToolProgressSse(sseOut, 'GenerateImage', 'warn', args, null, res, genDurMs)
      return null
    }
    String url = ChatCompletionsToolWire.generateImageResultUrlString(res)
    if (!url?.trim()) {
      writeToolProgressSse(sseOut, 'GenerateImage', 'warn', args, null, res, genDurMs)
      return null
    }
    writeToolProgressSse(sseOut, 'GenerateImage', 'done', args, null, res, genDurMs)
    markTaskCompletionWallMsIfUnset(toolTimingCtx)
    Map<String, String> imgById = [(tcId): url]
    String ref = ChatCompletionsToolWire.STUDIO_AI_INLINE_IMAGE_REF_PREFIX + tcId
    String prose = """## Plan Execution
- Generated image from your prompt
- Preview appears in the chat image strip below

![Generated illustration](${ref})"""
    log.info('Tools-loop: GenerateImage hotpath completed agentId={} toolCallId={}', agentId, tcId)
    return ChatCompletionsToolWire.expandInlineImageRefs(prose, imgById, null)
  }

  /**
   * Deterministic single-field edit when intent prefetch already loaded content + resolved field id.
   * Skips the first tools-loop {@code /v1/chat/completions} call (large prompt + tool schemas).
   *
   * @return assistant markdown, or {@code null} when the hotpath does not apply or fails
   */
  private static String tryServerPrefetchSimpleFieldEditHotpath(
    String origUser,
    String authorVisible,
    Map intentTel,
    Map toolsLoopSessionBundle,
    Map<String, FunctionToolCallback> byName,
    String agentId,
    OutputStream sseOut,
    AtomicBoolean cancelRequested,
    Map toolTimingCtx = null
  ) {
    if (cancelRequested != null && cancelRequested.get()) {
      return null
    }
    String promptForOutcome = (origUser ?: authorVisible)
    String visibleForOutcome = authorVisibleFromPromptText(promptForOutcome) ?: (authorVisible ?: '').trim()
    String outcomePhrase = ''
    if (authorRequestNeedsPriorTurnContentResolution(visibleForOutcome)) {
      outcomePhrase = extractPriorTurnAssistantContentForOutcome(promptForOutcome)?.trim()
    }
    if (!outcomePhrase) {
      outcomePhrase = extractAuthoringOutcomePhrase(visibleForOutcome)?.trim()
    }
    if (!outcomePhrase) {
      outcomePhrase = extractAuthoringOutcomePhrase(authorVisible)?.trim()
    }
    String fieldId = (intentTel?.prefetchResolvedFieldId ?: '').toString().trim()
    Map gc = AuthoringIntentRecipeEngine.extractPrefetchSuccessfulGetContent(origUser ?: '')
    String path = (gc?.path ?: '').toString().trim()
    String contentXml = (gc?.contentXml ?: '').toString()
    StudioToolOperations ops = (toolsLoopSessionBundle?.studioOps instanceof StudioToolOperations) ?
      (StudioToolOperations) toolsLoopSessionBundle.studioOps :
      null
    if (ops == null) {
      return null
    }
    if (!fieldId || !contentXml?.trim()) {
      String label = extractAuthorFieldLabelPhrase(origUser ?: authorVisible)
      if (!label) {
        label = extractAuthorFieldLabelPhrase(authorVisible)
      }
      Map cfgBoot = null
      try {
        cfgBoot = StudioAiAssistantProjectConfig.load(ops)
      } catch (Throwable ignoredCfg) {
      }
      if (cfgBoot != null && label) {
        Map boot = AuthoringIntentRecipeEngine.bootstrapConcreteFieldEditPrefetch(ops, cfgBoot, label)
        if (Boolean.TRUE.equals(boot?.applied)) {
          if (!fieldId) {
            fieldId = (boot.resolvedFieldId ?: '').toString().trim()
          }
          if (!contentXml?.trim()) {
            contentXml = (boot.contentXml ?: '').toString()
            path = (boot.contentPath ?: path).toString().trim()
          }
          if (!(intentTel instanceof Map)) {
            intentTel = new LinkedHashMap<>()
          }
          if (!intentTel.prefetchResolvedFieldId) {
            intentTel.put('prefetchResolvedFieldId', fieldId)
          }
          if (!intentTel.prefetchSkipRedundantGetContentForListedPath) {
            intentTel.put('prefetchSkipRedundantGetContentForListedPath', Boolean.TRUE.equals(boot.duplicateGetContentBanned))
          }
          String bootLabel = (boot.resolvedFieldLabel ?: label).toString().trim()
          boolean canMarkMatched = isUsableHotpathOutcomePhrase(outcomePhrase, promptForOutcome) &&
            !outcomePhraseEqualsResolvedFieldLabel(outcomePhrase, bootLabel)
          if (!intentTel.recipeId && canMarkMatched) {
            intentTel.put('recipeId', 'modify_page_content')
            intentTel.put('outcome', 'matched')
          }
        }
      }
    }
    String fieldLabelForGuard = extractAuthorFieldLabelPhrase(origUser ?: authorVisible)
    if (!fieldLabelForGuard) {
      fieldLabelForGuard = extractAuthorFieldLabelPhrase(authorVisible)
    }
    if (outcomePhraseEqualsResolvedFieldLabel(outcomePhrase, fieldLabelForGuard)) {
      log.info(
        'Tools-loop: server prefetch field hotpath skipped — outcome phrase equals field label (placement request, not literal copy)'
      )
      return null
    }
    String promptForIntent = promptForOutcome
    boolean eligible =
      prefetchHotpathAllowsForcedWriteContent(intentTel, outcomePhrase, promptForIntent) ||
        serverConcreteFieldEditHotpathEligible(promptForIntent, outcomePhrase, fieldId, contentXml, path)
    if (!eligible) {
      return null
    }
    if (!isUsableHotpathOutcomePhrase(outcomePhrase, promptForIntent)) {
      log.info(
        'Tools-loop: server prefetch field hotpath skipped — outcome not literal publishable copy (needs expansion or tools lookup)'
      )
      return null
    }
    if (!fieldId || !outcomePhrase || !path) {
      return null
    }
    String normPath = AuthoringPreviewContext.normalizeRepoPath(path)
    if (!normPath) {
      return null
    }
    String siteId = ''
    try {
      siteId = ops.resolveEffectiveSiteId('')
    } catch (Throwable ignoredSite) {
    }
    try {
      Map freshItem = ops.getContent(siteId, normPath) as Map
      String freshXml = (freshItem?.contentXml ?: '').toString()
      if (freshXml?.trim()) {
        contentXml = freshXml
      }
    } catch (Throwable gex) {
      log.warn(
        'Tools-loop: server prefetch field hotpath GetContent failed path={} agentId={} reason={}',
        normPath,
        agentId,
        gex.message ?: gex.toString()
      )
    }
    if (!contentXml?.trim()) {
      markPrefetchHotpathAborted(toolsLoopSessionBundle, 'no_content_xml', false)
      return null
    }
    if (!StudioToolOperations.looksLikeFullCrafterSiteContentItemDocument(normPath, contentXml)) {
      log.warn(
        'Tools-loop: server prefetch field hotpath aborted — on-disk item is not a full <page>/<component> document path={} agentId={}',
        normPath,
        agentId
      )
      markPrefetchHotpathAborted(toolsLoopSessionBundle, 'corrupt_or_partial_item_xml_on_disk', true)
      return null
    }
    String patched = AuthoringIntentRecipeEngine.patchContentXmlFieldValue(contentXml, fieldId, outcomePhrase)
    if (!patched?.trim()) {
      log.info(
        'Tools-loop: server prefetch field hotpath skipped — could not patch field {} in contentXml (field may live in a nested component) agentId={}',
        fieldId,
        agentId
      )
      markPrefetchHotpathAborted(toolsLoopSessionBundle, 'field_not_in_page_xml', false)
      return null
    }
    if (!StudioToolOperations.looksLikeFullCrafterSiteContentItemDocument(normPath, patched)) {
      log.warn(
        'Tools-loop: server prefetch field hotpath aborted — patched body is not a full item document path={} agentId={}',
        normPath,
        agentId
      )
      markPrefetchHotpathAborted(toolsLoopSessionBundle, 'patch_produced_invalid_document', true)
      return null
    }
    return completeServerPrefetchFieldWriteFromPatchedXml(
      ops,
      siteId,
      normPath,
      patched,
      fieldId,
      outcomePhrase,
      toolsLoopSessionBundle,
      byName,
      origUser,
      agentId,
      sseOut,
      toolTimingCtx
    )
  }

  /** Server wrap-up when write + preview phrase verification succeeded — avoids an extra tools-loop LLM round. */
  private static String synthesizePlanExecutionAfterVerifiedWrite(String phrase, String previewUrl) {
    String p = (phrase ?: '').trim()
    StringBuilder sb = new StringBuilder('## Done\n\n')
    if (p) {
      sb.append('Your update **"').append(p).append('**" is saved and shows on the site preview.\n\n')
    } else {
      sb.append('Your change is saved and the preview looks good.\n\n')
    }
    sb.append('### What we checked\n')
    sb.append('- Content saved in the CMS\n')
    if (p) {
      sb.append('- Preview shows the new text\n')
    }
    if (previewUrl?.trim()) {
      sb.append('\n[View preview](').append(previewUrl.trim()).append(')\n')
    }
    return sb.toString()
  }

  /**
   * Short author prompts that ask only for a new bitmap (no CMS write) — used to set {@code tool_choice} to
   * {@code GenerateImage} on tools-loop round 0 so hosts cannot return prose-only “here is the image” hallucinations.
   */
  private static boolean plainTextLooksLikeImageOnlyGenerateRequest(String visibleUserText) {
    return AuthoringPreviewContext.authorCurrentRequestLooksLikeImageOnlyGenerate(visibleUserText ?: '')
  }

  /**
   * First {@code role:user} message in the tools-loop transcript (the author request, usually including any
   * prepended expanded-intent block).
   */
  private static String firstAuthoringUserWirePlainText(List<Map> wire) {
    if (!(wire instanceof List)) {
      return ''
    }
    for (def m : wire) {
      if (!(m instanceof Map)) {
        continue
      }
      if (!'user'.equals(((Map) m).get('role')?.toString())) {
        continue
      }
      String s = flattenWireUserContent(((Map) m).get('content'))?.trim() ?: ''
      if (s) {
        return s
      }
    }
    return ''
  }

  /**
   * After {@code FetchHttpUrl}, re-injects the author’s goal next to the reference payload so shrinking wire
   * history cannot strand “look like X” intent away from the fetched HTML/CSS.
   */
  private static String buildAuthoringIntentAnchorMessageForReferenceFetch(List<Map> wire) {
    String raw = firstAuthoringUserWirePlainText(wire)
    if (!raw?.trim()) {
      return ''
    }
    String visible = ''
    try {
      visible = AuthoringPreviewContext.stripStudioInjectedPromptBlocks(raw)
    } catch (Throwable ignored) {
      visible = raw
    }
    visible = (visible ?: '').trim()
    if (!visible) {
      return ''
    }
    int cap = 3200
    String body = visible.length() <= cap ? visible : (visible.substring(0, cap) + '\n\n[aiassistant: anchor truncated — full author message appeared earlier in this chat]')
    return '''[aiassistant: authoring goal anchor — placed immediately after FetchHttpUrl tool result(s)]
Use the reference response above together with **this** author request (including any expanded intent block below). Apply changes in the **author’s Crafter repository** only: follow paths from **GetContent** (page XML → `display-template`, `head.ftl`, linked CSS under `/static-assets/`). External `stylesheetHrefs` / asset URLs are hints, not guaranteed to exist in the author’s site tree.

---
''' + body
  }

  /**
   * Second LLM pass after tools (QA JSON + optional correction loop). **Permanently disabled** — no JVM/env
   * toggle; keeps latency predictable and avoids extra {@code /v1/chat/completions} cost after tool work.
   */
  private static boolean postToolReviewEnabled() {
    return false
  }

  private static String elideMiddleForReview(String s, int maxChars) {
    def t = (s ?: '').toString()
    if (t.length() <= maxChars) {
      return t
    }
    int head = Math.max(1200, (int) (maxChars * 0.45))
    int tail = Math.max(1200, maxChars - head - 80)
    if (head + tail >= t.length()) {
      return t
    }
    return t.substring(0, head) + '\n\n…[middle elided for review length]…\n\n' + t.substring(t.length() - tail)
  }

  private static Map parsePostToolReviewJsonObject(String assistantText) {
    def raw = (assistantText ?: '').toString().trim()
    if (!raw) {
      return [accomplished: true, reason: 'empty reviewer reply', correctionInstructions: '']
    }
    if (raw.startsWith('```')) {
      raw = raw.replaceFirst(/(?s)^```(?:json)?\s*\n/, '').replaceFirst(/(?s)\n```\s*$/, '').trim()
    }
    try {
      def o = new JsonSlurper().parseText(raw)
      if (o instanceof Map) {
        def m = (Map) o
        def acc = m.get('accomplished')
        boolean ok = true
        if (acc != null) {
          if (acc instanceof Boolean) {
            ok = (Boolean) acc
          } else {
            ok = 'true'.equalsIgnoreCase(acc.toString())
          }
        }
        return [
          accomplished           : ok,
          reason                 : (m.get('reason') ?: '').toString(),
          correctionInstructions : (m.get('correctionInstructions') ?: m.get('correction_instructions') ?: '').toString()
        ]
      }
    } catch (Throwable t) {
      log.warn('parsePostToolReviewJsonObject: {} bodyPrefix=\n{}', t.message, AiHttpProxy.elideForLog(raw, 800))
    }
    [accomplished: true, reason: 'review JSON parse failed', correctionInstructions: '']
  }

  private static Map postToolReview(
    String apiKey,
    String model,
    String originalUserContent,
    String assistantFinalOutput,
    String agentId,
    OutputStream sseOut = null
  ) {
    model = resolveChatModel(model?.toString())
    int cap = 120_000
    try {
      def p = System.getProperty('aiassistant.openai.reviewMaxChars')?.toString()?.trim()
      if (p) {
        cap = Math.max(8192, Integer.parseInt(p))
      }
    } catch (Throwable ignored) {}
    // Groovy `/` on Integer can yield BigDecimal — elide helper requires int maxChars.
    int halfCap = Math.floorDiv((int) cap, 2)
    String ou = elideMiddleForReview(originalUserContent, halfCap)
    String af = elideMiddleForReview(assistantFinalOutput, halfCap)
    def userBlock = """ORIGINAL_AUTHOR_REQUEST:
${ou}

ASSISTANT_FINAL_OUTPUT:
${af}"""
    def reqMap = [
      model   : model,
      messages: [
        [role: 'system', content: ToolPrompts.getLlm_POST_EXECUTION_REVIEW_SYSTEM()],
        [role: 'user', content: userBlock]
      ],
      stream  : false
    ]
    reqMap.putAll(chatCompletionOutputLimitParams(model, 900))
    // Never send temperature on review: GPT‑5/o‑series reject non-default values; default sampling is fine for JSON review.
    def jsonBody =
      chatCompletionsWireBodyApplyNeoTemperaturePolicy(JsonOutput.toJson(reqMap))
    log.debug(
      'Tools-loop wire → POST /v1/chat/completions phase=post_tool_review agentId={} model={} wireJsonChars={} neoWire={}',
      agentId,
      model,
      jsonBody.length(),
      modelNeedsNeoChatCompletionWireParams(model)
    )
    emitSseToolProgressLine(
      sseOut,
      '🛠️🔄 Double-checking the assistant reply…\n',
      'start'
    )
    try {
      String raw = httpPostChatCompletionsReadBody(apiKey, jsonBody, true)
      if (!raw?.trim()) {
        throw new IllegalStateException('Tools-loop post-tool review: empty response body')
      }
      if (raw.trim().startsWith('data:')) {
        throw new IllegalStateException('Tools-loop post-tool review: SSE for stream=false')
      }
      def slurper = new JsonSlurper()
      Object parsed = slurper.parseText(raw)
      if (!(parsed instanceof Map)) {
        throw new IllegalStateException('Tools-loop post-tool review: expected JSON object')
      }
      Map root = parsed as Map
      def errMsg = streamChunkProviderErrorMessage(root)
      if (errMsg) {
        throw new IllegalStateException('Tools-loop post-tool review: ' + errMsg)
      }
      def choices = root.get('choices')
      if (!(choices instanceof List) || choices.isEmpty()) {
        throw new IllegalStateException('Tools-loop post-tool review: missing choices')
      }
      def c0 = choices[0] as Map
      def message = c0.get('message')
      if (!(message instanceof Map)) {
        throw new IllegalStateException('Tools-loop post-tool review: missing message')
      }
      String reviewText = assistantTextFromChoiceMessageMap((Map) message)
      return parsePostToolReviewJsonObject(reviewText)
    } catch (RestClientResponseException rce) {
      String bp = ''
      try {
        bp = rce.responseBodyAsString ?: ''
      } catch (Throwable ignored) {
      }
      log.warn(
        'Tools-loop post-tool review: HTTP {} — skipping reviewer pass (model may reject temperature or other params). bodyPrefix=\n{}',
        rce.statusCode?.value() ?: rce.statusCode,
        AiHttpProxy.elideForLog(bp, 900)
      )
      return [
        accomplished           : true,
        reason                 : 'post-tool review skipped (chat.completions HTTP error)',
        correctionInstructions : ''
      ]
    } catch (Throwable t) {
      // Optional reviewer must never fail the main chat (classloader-specific HTTP wrappers, parse errors, etc.).
      log.warn('Tools-loop post-tool review failed — skipping reviewer pass', t)
      return [
        accomplished           : true,
        reason                 : 'post-tool review skipped (error)',
        correctionInstructions : ''
      ]
    }
  }

  private static String buildPostReviewCorrectionUserMessage(Map rev) {
    def r = (rev?.reason ?: '').toString().trim()
    def c = (rev?.correctionInstructions ?: '').toString().trim()
    return """[Studio — post-execution self-check]
An automated reviewer compared your last reply to the original author request and believes the task may be incomplete.

**Reviewer reason:** ${r ?: '(none)'}

**What you still need to do:**
${c ?: 'Re-read the original request, use tools as needed, and produce a complete answer for the author.'}

Use CMS tools if repository work is still missing. **Do not** stream a new **## Plan** for this follow-up — continue against the **## Plan** and **📋** checklist you already gave the author (run any missing verification tools, then mark those steps). Then write the updated final answer under **## Plan Execution** with the **same** **📋** checklist and final **✅ / ❌ / ⚠️** markers as required by policy."""
  }

  /** Collapses whitespace and normalizes quotes so substring checks survive minor typography / unicode differences. */
  private static String planGateNormalizeForScan(String raw) {
    if (raw == null) {
      return ''
    }
    String s = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
    s = s.replace('\u2019', '\'').replace('\u2018', '\'').replace('\u201c', '"').replace('\u201d', '"')
    s = s.replace('\u00a0', ' ')
    return s.replaceAll(/\s+/, ' ').trim().toLowerCase(Locale.ROOT)
  }

  /**
   * Detects memorized lazy “execute the request / CMS tools …” slop (older assistant builds quoted it in {@code [TOOL-GUARD]}).
   * Used only to strip matching lines from streamed assistant text — the native tool loop does **not** block on plan shape.
   */
  private static boolean containsKnownForbiddenMetaPlan(String t) {
    String n = planGateNormalizeForScan(t)
    if (!n) {
      return false
    }
    if (n.contains('execute the user request using the cms tools described in the studio authoring system message')) {
      return true
    }
    if (n.contains('execute the user request using the cms tools described')) {
      return true
    }
    // Singular “tool” variants models sometimes emit.
    if (n.contains('execute the user request using the cms tool described')) {
      return true
    }
    if (n.contains('using the cms tool described in the studio authoring system message')) {
      return true
    }
    if (n.contains('using the cms tools described in the studio authoring system message')) {
      return true
    }
    if (n.contains('execute the user request') && n.contains('cms tools') && n.contains('system message')) {
      return true
    }
    if (n.contains('execute the user request') && n.contains('cms tool') && n.contains('system message')) {
      return true
    }
    if (n.contains('execute the user request') && n.contains('studio authoring') && n.contains('message')) {
      return true
    }
    if (n.contains('use tools as described') && n.contains('system')) {
      return true
    }
    // Memorized “present generated image as markdown” slop (Studio uses the chat image strip; markdown duplicates/breaks UX).
    if (n.contains('present the generated image as markdown')) {
      return true
    }
    if (n.contains('prepare to present') && n.contains('markdown')) {
      return true
    }
    if (n.contains('present') && n.contains('generated image') && n.contains('as markdown')) {
      return true
    }
    return false
  }

  /**
   * Last-resort cleanup before SSE: removes lazy meta plan lines so authors never see memorized {@code [TOOL-GUARD]}
   * parrot text. Drops a lone {@code Plan} / {@code ## Plan} heading when the immediate next non-empty line is forbidden.
   */
  private static String stripForbiddenMetaPlanFromAssistantText(String raw) {
    if (raw == null) {
      return ''
    }
    String t = raw.toString()
    if (!t.trim()) {
      return t
    }
    List<String> lines = t.split(/\r?\n/, -1).toList()
    List<String> out = []
    int i = 0
    while (i < lines.size()) {
      String line = lines.get(i)
      String trimmed = line.trim()
      if (containsKnownForbiddenMetaPlan(trimmed)) {
        i++
        continue
      }
      String tl = trimmed.toLowerCase(Locale.ROOT)
      boolean planOnly =
        tl == 'plan' ||
          tl == 'plan:' ||
          tl == '**plan**' ||
          (tl =~ /(?i)^#+\s*plan\s*$/) ||
          (tl =~ /(?i)^##\s*plan\s*$/)
      if (planOnly) {
        int j = i + 1
        while (j < lines.size() && !lines.get(j).trim()) {
          j++
        }
        if (j < lines.size() && containsKnownForbiddenMetaPlan(lines.get(j).trim())) {
          i = j + 1
          continue
        }
      }
      out.add(line)
      i++
    }
    String joined = out.join('\n')
    // Drop orphan / truncated markdown image lines (Studio uses the chat image strip).
    joined = joined.replaceAll(/(?m)^\s*!\[[^\]]*]\(\s*$\n?/, '')
    return joined.replaceAll(/(?m)\n{3,}/, '\n\n').trim()
  }

  /**
   * Before appending an assistant {@code message} to {@code wireMessages}, replace any known huge {@code data:image}
   * URLs (same bytes as a prior {@code GenerateImage} tool result) with {@link ChatCompletionsToolWire#STUDIO_AI_INLINE_IMAGE_REF_PREFIX} refs
   * so follow-up {@code POST /v1/chat/completions} requests stay within context limits.
   */
  /** Merge compact-wire + SSE-backlog maps so author-visible sanitization sees every GenerateImage URL keyed by tool_call id. */
  private static Map<String, String> mergedGenerateImageUrlByToolCallId(
    Map<String, String> compactWireUrlByToolCallId,
    Map<String, String> sseBacklogUrlByToolCallId
  ) {
    Map<String, String> out = new LinkedHashMap<>()
    if (compactWireUrlByToolCallId != null && !compactWireUrlByToolCallId.isEmpty()) {
      out.putAll(compactWireUrlByToolCallId)
    }
    if (sseBacklogUrlByToolCallId != null && !sseBacklogUrlByToolCallId.isEmpty()) {
      for (Map.Entry<String, String> e : sseBacklogUrlByToolCallId.entrySet()) {
        String id = e.getKey() != null ? e.getKey().toString().trim() : ''
        String u = e.getValue() != null ? e.getValue().toString().trim() : ''
        if (id && u && !out.containsKey(id)) {
          out.put(id, u)
        }
      }
    }
    return out
  }

  /**
   * Replace raw {@code data:image} payloads that duplicate GenerateImage results with {@link ChatCompletionsToolWire#STUDIO_AI_INLINE_IMAGE_REF_PREFIX} refs
   * so author SSE does not stream huge base64 in markdown (UI loads bytes from {@code studioAiInlineImageUrls} metadata).
   */
  private static String sanitizeAssistantMarkdownReplaceGenerateImageDataUrlsWithRefs(
    String assistantText,
    Map<String, String> urlByToolCallId
  ) {
    if (assistantText == null || assistantText.isEmpty()) {
      return assistantText ?: ''
    }
    if (urlByToolCallId == null || urlByToolCallId.isEmpty()) {
      return assistantText.toString()
    }
    String s = assistantText.toString()
    for (Map.Entry<String, String> e : urlByToolCallId.entrySet()) {
      String id = e.getKey() != null ? e.getKey().toString().trim() : ''
      String url = e.getValue() != null ? e.getValue().toString().trim() : ''
      if (!id || !url || !s.contains(url)) {
        continue
      }
      s = s.replace(url, ChatCompletionsToolWire.STUDIO_AI_INLINE_IMAGE_REF_PREFIX + id)
    }
    return s
  }

  private static void mutateAssistantWireContentElideKnownGenerateImageDataUrls(
    Map msgCopy,
    Map<String, String> generateImageDataUrlByToolCallId
  ) {
    if (!(msgCopy instanceof Map) || generateImageDataUrlByToolCallId == null || generateImageDataUrlByToolCallId.isEmpty()) {
      return
    }
    def c = msgCopy.get('content')
    if (!(c instanceof CharSequence)) {
      return
    }
    String flat = c.toString()
    if (!flat) {
      return
    }
    String s = flat
    boolean changed = false
    for (Map.Entry<String, String> e : generateImageDataUrlByToolCallId.entrySet()) {
      String id = e.key
      String url = e.value
      if (!id || !url || !s.contains(url)) {
        continue
      }
      s = s.replace(url, ChatCompletionsToolWire.STUDIO_AI_INLINE_IMAGE_REF_PREFIX + id)
      changed = true
    }
    if (changed) {
      msgCopy.put('content', s)
    }
  }

  private static void markPrefetchHotpathAborted(Map toolsLoopSessionBundle, String reason, boolean corruptOnDisk = false) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return
    }
    toolsLoopSessionBundle.put('prefetchHotpathWriteAborted', Boolean.TRUE)
    if (reason?.trim()) {
      toolsLoopSessionBundle.put('prefetchHotpathAbortReason', reason.trim())
    }
    if (corruptOnDisk) {
      toolsLoopSessionBundle.put('prefetchHotpathCorruptItemXml', Boolean.TRUE)
    }
  }

  private static String synthesizeCorruptSiteItemXmlMessage(String repoPath) {
    String p = (repoPath ?: '').trim() ?: '(unknown path)'
    return '## Cannot edit this content item\n\n' +
      'The file **`' + p + '`** in the repository is **not** a complete Crafter content item (missing `<page>` / `<component>` root or required item markers). ' +
      'That often happens after an earlier partial AI write.\n\n' +
      '**Fix in Studio:** open **Git** / history for this file and **revert** to the last good version, then retry your edit.\n'
  }

  private static boolean toolWireIndicatesInvalidSiteItemDocument(String toolWireJson) {
    String s = (toolWireJson ?: '').toString()
    return s.contains('field fragment') ||
      s.contains('root <page> or <component>') ||
      s.contains('missing typical Crafter item markers')
  }

  /** Shrinks {@code update_content} tool results on the wire — keeps {@code contentXml} but drops bulky form XML. */
  private static String compactUpdateContentToolWire(String rawJson, int maxChars) {
    String s = (rawJson ?: '').toString()
    if (!s.trim()) {
      return s
    }
    try {
      Object parsed = new JsonSlurper().parseText(s)
      if (!(parsed instanceof Map)) {
        return s.length() <= maxChars ? s : s.substring(0, maxChars)
      }
      Map m = new LinkedHashMap<>((Map) parsed)
      if (m.containsKey('formDefinitionXml')) {
        m.remove('formDefinitionXml')
        m.put('formDefinitionXmlOmittedOnWire', Boolean.TRUE)
      }
      Object cx = m.get('contentXml')
      if (cx instanceof String) {
        String body = ((String) cx).trim()
        int cap = Math.min(24_000, maxChars / 2)
        if (body.length() > cap) {
          m.put('contentXmlChars', body.length())
          m.put(
            'contentXml',
            body.substring(0, cap) + '\n…[contentXml truncated on wire — call GetContent on this path for the full document]'
          )
        }
      }
      String out = JsonOutput.toJson(m)
      if (out.length() <= maxChars) {
        return out
      }
      return out.substring(0, maxChars) +
        '\n…[update_content wire compacted; call GetContent for full contentXml]'
    } catch (Throwable ignored) {
      return s.length() <= maxChars ? s : s.substring(0, maxChars)
    }
  }

  private static String truncateNativeToolWireContent(
    String fnName,
    Object toolOutRaw,
    String toolCallId = null,
    Map<String, String> generateImageDataUrlByToolCallId = null
  ) {
    String s = toolOutRaw != null ? toolOutRaw.toString() : ''
    if ('update_content'.equals((fnName ?: '').toString().trim())) {
      return compactUpdateContentToolWire(s, NATIVE_TOOLS_WIRE_JSON_MAX_CHARS)
    }
    if ('GenerateImage'.equals((fnName ?: '').toString().trim())) {
      if (generateImageDataUrlByToolCallId != null && toolCallId?.toString()?.trim()) {
        String compact = ChatCompletionsToolWire.compactGenerateImageToolWire(s, toolCallId.trim(), generateImageDataUrlByToolCallId)
        if (compact != null) {
          return compact
        }
      }
      if (s.length() <= NATIVE_TOOLS_WIRE_JSON_MAX_CHARS) {
        return s
      }
      int cap = NATIVE_TOOLS_WIRE_JSON_MAX_CHARS
      String head = s.substring(0, cap)
      return head +
        '\n\n[aiassistant: output truncated for chat context limit; tool=GenerateImage originalChars=' + s.length() + ']' +
        '\nHint: payload too large for wire; use a smaller image or save to /static-assets/.]'
    }
    if (s.length() <= NATIVE_TOOLS_WIRE_JSON_MAX_CHARS) {
      return s
    }
    int cap = NATIVE_TOOLS_WIRE_JSON_MAX_CHARS
    String head = s.substring(0, cap)
    String fn = (fnName ?: '').toString()
    return head +
      '\n\n[aiassistant: output truncated for chat context limit; tool=' + fn + ' originalChars=' + s.length() + ']' +
      '\nHint: use a smaller size, a path prefix filter, or GetContent on specific paths.]'
  }

  /**
   * @return map with {@code text} (assistant markdown), {@code previewGoalFound} ({@link Boolean} or null),
   *         {@code previewGoalPhrase} (String)
   */
  static Map runNativeToolLoopToAssistantText(
    String apiKey,
    String model,
    List<Map> wireMessages,
    List wireTools,
    Map<String, FunctionToolCallback> byName,
    String agentId,
    int maxRounds,
    boolean logFirstPostChars,
    OutputStream ssePreToolAssistantText = null,
    AtomicBoolean cancelRequested = null,
    String wireBaseUrl = null,
    Map toolsLoopSessionBundle = null,
    Map<String, String> generateImageDataUrlByToolCallId = null,
    Map toolTimingCtx = null
  ) {
    def slurper = new JsonSlurper()
    String assistantAccum = ''
    boolean finished = false
    boolean previousRoundHadRepoMutation = false
    int prosePlanMissingToolNudges = 0
    int previewVerificationFailedNudges = 0
    Set<String> writeContentPathsThisTurn = new LinkedHashSet<>()
    Boolean lastPreviewContentGoalFound = null
    String authorVisibleForToolsLoop = authorVisibleRequestFromWire(wireMessages) ?: ''
    String frozenAuthorOutcomePhrase = extractAuthoringOutcomePhrase(authorVisibleForToolsLoop)
    String lastPreviewContentGoalPhrase = frozenAuthorOutcomePhrase ?: ''
    int writeContentInvalidDocumentFailures = 0
    String lastInvalidWriteContentPath = ''
    StudioToolOperations ops = (toolsLoopSessionBundle?.studioOps instanceof StudioToolOperations) ?
      (StudioToolOperations) toolsLoopSessionBundle.studioOps :
      null
    for (int round = 0; round < maxRounds; round++) {
      if (cancelRequested != null && cancelRequested.get()) {
        Thread.currentThread().interrupt()
        throw new InterruptedException(AIASSISTANT_PIPELINE_CANCELLED)
      }
      aiAssistantToolWorkerDiagPhase("native_tool_loop_round_${round}_build_request wireMsgCount=${wireMessages.size()}")
      Object toolChoice = 'auto'
      if (round == 0) {
        Map intentTelForce =
          (toolsLoopSessionBundle instanceof Map) ?
            (Map) toolsLoopSessionBundle.get('intentRecipeRoutingTelemetry') :
            null
        String forceTool = intentTelForce?.get('toolsLoopForceTool')?.toString()?.trim() ?: ''
        if (forceTool && wireToolsIncludeNamedTool(wireTools, forceTool)) {
          toolChoice = [type: 'function', function: [name: forceTool]]
          log.info(
            'Tools-loop tools-on: tool_choice forced to {} (intent recipe catalog, round 0) agentId={} recipeId={}',
            forceTool,
            agentId,
            intentTelForce?.get('recipeId') ?: ''
          )
        }
        if (toolChoice == 'auto' && wireToolsIncludeGenerateImage(wireTools)) {
          Map lastUserRound0 = lastUserWireMessage(wireMessages)
          String lastPlain = lastUserRound0 ? ((flattenWireUserContent(lastUserRound0.get('content')) ?: '').trim()) : ''
          String visible = AuthoringPreviewContext.extractAuthorCurrentRequestVisible(lastPlain)
          if (!visible?.trim()) {
            visible = lastPlain
            try {
              visible = (AuthoringPreviewContext.stripStudioInjectedPromptBlocks(lastPlain) ?: '').trim() ?: lastPlain
            } catch (Throwable ignoredStrip) {
            }
          }
          boolean researchTurn =
            AuthoringPreviewContext.authorVisibleSuggestsWebResearch(visible) ||
            AuthoringPreviewContext.authorVisibleSuggestsSiteContentResearch(visible) ||
            AuthoringPreviewContext.authorVisibleSuggestsLlmResearch(visible)
          boolean revertTurn = AuthoringPreviewContext.authorVisibleSuggestsRevertIntent(visible)
          if (revertTurn && wireToolsIncludeNamedTool(wireTools, 'revert_change')) {
            toolChoice = [type: 'function', function: [name: 'revert_change']]
            log.info(
              'Tools-loop tools-on: tool_choice forced to revert_change (revert intent, round 0) agentId={}',
              agentId
            )
          } else if (!researchTurn && plainTextLooksLikeImageOnlyGenerateRequest(visible)) {
            toolChoice = [type: 'function', function: [name: 'GenerateImage']]
            log.info(
              'Tools-loop tools-on: tool_choice forced to GenerateImage (image-only author request, round 0) agentId={}',
              agentId
            )
          }
        }
        if (toolChoice == 'auto' && wireToolsIncludeNamedTool(wireTools, 'WebSearch')) {
          Map intentTelWeb =
            (toolsLoopSessionBundle instanceof Map) ?
              (Map) toolsLoopSessionBundle.get('intentRecipeRoutingTelemetry') :
              null
          String ridWeb = intentTelWeb?.get('recipeId')?.toString()?.trim() ?: ''
          Map lastUserWeb = lastUserWireMessage(wireMessages)
          String lastPlainWeb = lastUserWeb ? ((flattenWireUserContent(lastUserWeb.get('content')) ?: '').trim()) : ''
          String visibleWeb = lastPlainWeb
          try {
            visibleWeb = (AuthoringPreviewContext.stripStudioInjectedPromptBlocks(lastPlainWeb) ?: '').trim() ?: lastPlainWeb
          } catch (Throwable ignoredStripWeb) {
          }
          if ('web_research'.equals(ridWeb) || AuthoringPreviewContext.authorVisibleSuggestsWebResearch(visibleWeb)) {
            toolChoice = [type: 'function', function: [name: 'WebSearch']]
            log.info(
              'Tools-loop tools-on: tool_choice forced to WebSearch (web research, round 0) agentId={} recipeId={}',
              agentId,
              ridWeb ?: '(signal)'
            )
          }
        }
        if (toolChoice == 'auto' && wireToolsIncludeNamedTool(wireTools, 'ResearchSiteContent')) {
          Map intentTelSite =
            (toolsLoopSessionBundle instanceof Map) ?
              (Map) toolsLoopSessionBundle.get('intentRecipeRoutingTelemetry') :
              null
          String ridSite = intentTelSite?.get('recipeId')?.toString()?.trim() ?: ''
          Map lastUserSite = lastUserWireMessage(wireMessages)
          String lastPlainSite = lastUserSite ? ((flattenWireUserContent(lastUserSite.get('content')) ?: '').trim()) : ''
          String visibleSite = lastPlainSite
          try {
            visibleSite = (AuthoringPreviewContext.stripStudioInjectedPromptBlocks(lastPlainSite) ?: '').trim() ?: lastPlainSite
          } catch (Throwable ignoredStripSite) {
          }
          if ('site_content_research'.equals(ridSite) ||
            AuthoringPreviewContext.authorVisibleSuggestsSiteContentResearch(visibleSite)) {
            toolChoice = [type: 'function', function: [name: 'ResearchSiteContent']]
            log.info(
              'Tools-loop tools-on: tool_choice forced to ResearchSiteContent (site content research, round 0) agentId={} recipeId={}',
              agentId,
              ridSite ?: '(signal)'
            )
          }
        }
        if (toolChoice == 'auto' && wireToolsIncludeNamedTool(wireTools, 'GetContent')) {
          Map intentTelInq =
            (toolsLoopSessionBundle instanceof Map) ?
              (Map) toolsLoopSessionBundle.get('intentRecipeRoutingTelemetry') :
              null
          String ridInq = intentTelInq?.get('recipeId')?.toString()?.trim() ?: ''
          String rrInq = intentTelInq?.get('routerReason')?.toString()?.trim() ?: ''
          boolean openPageInquiry =
            'open_page_inquiry'.equals(ridInq) ||
            rrInq.contains('open_page_inquiry') ||
            AuthoringPreviewContext.authorVisibleSuggestsOpenPageInquiry(authorVisibleForToolsLoop)
          boolean prefetchHasAnchorContent =
            Boolean.TRUE.equals(intentTelInq?.prefetchSkipRedundantGetContentForListedPath) &&
            !Boolean.TRUE.equals(intentTelInq?.prefetchEnvelopeTruncated)
          if (openPageInquiry && !prefetchHasAnchorContent) {
            toolChoice = [type: 'function', function: [name: 'GetContent']]
            log.info(
              'Tools-loop tools-on: tool_choice forced to GetContent (open page inquiry, round 0) agentId={} recipeId={}',
              agentId,
              ridInq ?: '(signal)'
            )
          }
        }
        if (toolChoice == 'auto' && wireToolsIncludeNamedTool(wireTools, 'WriteContent')) {
          Map intentTel =
            (toolsLoopSessionBundle instanceof Map) ?
              (Map) toolsLoopSessionBundle.get('intentRecipeRoutingTelemetry') :
              null
          boolean hotpathAborted = Boolean.TRUE.equals(toolsLoopSessionBundle?.prefetchHotpathWriteAborted)
          if (!hotpathAborted &&
            !intentRecipeIsOpenPageInquiry(intentTel) &&
            prefetchHotpathAllowsForcedWriteContent(intentTel, frozenAuthorOutcomePhrase, authorVisibleForToolsLoop)) {
            toolChoice = [type: 'function', function: [name: 'WriteContent']]
            log.info(
              'Tools-loop tools-on: tool_choice forced to WriteContent (prefetch hotpath modify_page_content, round 0) agentId={} resolvedFieldId={}',
              agentId,
              intentTel?.prefetchResolvedFieldId ?: ''
            )
          } else if (hotpathAborted && wireToolsIncludeNamedTool(wireTools, 'GetContent')) {
            toolChoice = [type: 'function', function: [name: 'GetContent']]
            log.info(
              'Tools-loop tools-on: tool_choice forced to GetContent (prefetch hotpath write aborted, round 0) agentId={} reason={}',
              agentId,
              toolsLoopSessionBundle?.prefetchHotpathAbortReason ?: ''
            )
          }
        }
      }
      def reqMap = [
        model: model,
        messages: wireMessages,
        tools: wireTools,
        tool_choice: toolChoice,
        stream: false
      ]
      int effMaxOut = clampMaxOutTokensForToolsLoopWire(model, 16000, toolsLoopSessionBundle)
      reqMap.putAll(chatCompletionOutputLimitParams(model, effMaxOut, toolsLoopSessionBundle))
      int maxWire = StudioAiLlmKind.toolsLoopChatMaxWirePayloadCharsFromBundle(toolsLoopSessionBundle)
      shrinkToolsLoopWirePayloadIfOverBudget(reqMap, wireMessages, wireTools, maxWire)
      def jsonBody = chatCompletionsWireBodyApplyNeoTemperaturePolicy(JsonOutput.toJson(reqMap))
      if (logFirstPostChars && round == 0) {
        log.debug(
          'Tools-loop tools-on RestClient: first POST chars={} agentId={} model={} restReadTimeoutMs={}',
          jsonBody.length(),
          agentId,
          model,
          resolveChatCompletionsRestReadTimeoutMs()
        )
      }
      emitRoundWaitSse(ssePreToolAssistantText, round, model, agentId, jsonBody.length(), previousRoundHadRepoMutation)
      if (cancelRequested != null && cancelRequested.get()) {
        Thread.currentThread().interrupt()
        throw new InterruptedException(AIASSISTANT_PIPELINE_CANCELLED)
      }
      String raw = httpPostChatCompletionsReadBody(apiKey, jsonBody, false, wireBaseUrl)
      if (cancelRequested != null && cancelRequested.get()) {
        Thread.currentThread().interrupt()
        throw new InterruptedException(AIASSISTANT_PIPELINE_CANCELLED)
      }
      if (!raw?.trim()) {
        throw new IllegalStateException('Tools-loop chat: empty response body')
      }
      if (raw.trim().startsWith('data:')) {
        throw new IllegalStateException('Chat host returned SSE for stream=false (native tool loop)')
      }
      Object parsed
      try {
        parsed = slurper.parseText(raw)
      } catch (Throwable je) {
        log.error('Tools-loop tools-on: JSON parse failed bodyPrefix=\n{}', AiHttpProxy.elideForLog(raw, 2500))
        try {
          emitSseToolProgressLine(
            ssePreToolAssistantText,
            '🛠️❌ **Chat host** — **`chat.completions` body was not valid JSON** (fragment for debugging):\n```text\n' +
              escapeTripleBackticksForMarkdownFence(AiHttpProxy.elideForLog(raw, 8000)) +
              '\n```\n',
            'error'
          )
        } catch (Throwable ignoredPreview) {
        }
        throw je
      }
      if (!(parsed instanceof Map)) {
        throw new IllegalStateException('Tools-loop chat: expected JSON object')
      }
      Map root = parsed as Map
      def errMsg = streamChunkProviderErrorMessage(root)
      if (errMsg) {
        try {
          emitSseToolProgressLine(
            ssePreToolAssistantText,
            '🛠️❌ **Chat host** returned an error in **`chat.completions` JSON** (no assistant message to apply):\n```text\n' +
              escapeTripleBackticksForMarkdownFence(errMsg.toString()) +
              '\n```\n',
            'error'
          )
        } catch (Throwable ignoredErrPreview) {
        }
        throw new IllegalStateException('Chat host: ' + errMsg)
      }
      def choices = root.get('choices')
      if (!(choices instanceof List) || choices.isEmpty()) {
        throw new IllegalStateException('Tools-loop chat: missing choices')
      }
      def c0 = choices[0] as Map
      def message = c0.get('message')
      if (!(message instanceof Map)) {
        throw new IllegalStateException('Tools-loop chat: missing message')
      }
      Map msgCopy = new LinkedHashMap((Map) message)
      String assistantApiFlatForDebug = assistantTextFromChoiceMessageMap(msgCopy)
      String assistantPreTool = assistantApiFlatForDebug
      boolean hasTc = choiceMessageHasToolCalls(msgCopy)
      String assistantRawForOrchestration = assistantApiFlatForDebug
      if (hasTc) {
        def tcl0 = msgCopy.get('tool_calls')
        if (tcl0 instanceof List) {
          List tcl = (List) tcl0
          List ordered = PlanOrchestration.reorderToolCallsByPlan(new ArrayList(tcl), assistantRawForOrchestration)
          List runListPrep = ordered != null ? ordered : new ArrayList(tcl)
          List depOrdered = PlanOrchestration.reorderToolCallsReadBeforeWritePreview(runListPrep)
          msgCopy.put('tool_calls', depOrdered)
          if (ordered != null) {
            log.info(
              'Tools-loop tools-on: plan orchestrator reordered {} tool_calls to match plan orchestration block agentId={}',
              ordered.size(),
              agentId
            )
          }
        }
      }
      mutateAssistantContentStripOrchestratorBlock(msgCopy)
      assistantPreTool = assistantTextFromChoiceMessageMap(msgCopy)
      if (ssePreToolAssistantText != null) {
        try {
          if (hasTc) {
            String cleanedPreTool = assistantPreTool?.trim() ? stripForbiddenMetaPlanFromAssistantText(assistantPreTool.trim()) : ''
            String trimmedPlan = (cleanedPreTool ?: '').trim()
            if (trimmedPlan) {
              def chunk = trimmedPlan + '\n\n'
              synchronized (ssePreToolAssistantText) {
                ssePreToolAssistantText.write(
                  ("data: ${JsonOutput.toJson([text: chunk, metadata: [:]])}\n\n").getBytes(StandardCharsets.UTF_8)
                )
                ssePreToolAssistantText.flush()
              }
            } else {
              String fallbackPlan = minimalPlanWhenToolsWithoutProse(round)
              if (fallbackPlan?.trim()) {
                synchronized (ssePreToolAssistantText) {
                  ssePreToolAssistantText.write(
                    ("data: ${JsonOutput.toJson([text: fallbackPlan + '\n\n', metadata: [:]])}\n\n").getBytes(StandardCharsets.UTF_8)
                  )
                  ssePreToolAssistantText.flush()
                }
                log.info(
                  'Tools-loop tools-on: injected minimal ## Plan before tool_calls (empty assistant content) agentId={} round={}',
                  agentId,
                  round
                )
              } else {
                log.info(
                  'Tools-loop tools-on: no assistant text to stream before tool_calls (common for some models); CMS tools still run. agentId={} round={}',
                  agentId,
                  round
                )
              }
            }
          }
          // Prose-only final answers (no tool_calls): stream once at end via writeSseFinalAssistantTextChunks — not here.
        } catch (Throwable te) {
          if (isSseClientDisconnected(te)) {
            log.debug('Tools-loop tools-on: pre-tool SSE skip (response unusable / client gone): {}', te.message)
          } else {
            log.warn('Tools-loop tools-on: failed to stream assistant text before tool calls: {}', te.message)
          }
        }
        emitSseAssistantTurnDebugPreview(ssePreToolAssistantText, assistantApiFlatForDebug, msgCopy, hasTc, round, agentId)
      }
      mutateAssistantWireContentElideKnownGenerateImageDataUrls(msgCopy, generateImageDataUrlByToolCallId)
      String userWireSnapshotForRecovery = firstAuthorVisibleUserFromWire(wireMessages) ?: ''
      wireMessages << msgCopy
      if (hasTc) {
        def runList = msgCopy.get('tool_calls') as List
        boolean repoMutationThisRound = false
        boolean anySuccessfulFetchHttpUrl = false
        boolean roundHadWriteAttempt = false
        boolean roundHadWriteSuccess = false
        boolean roundHadWriteFailure = false
        boolean roundRanGetPreviewHtml = false
        Map previewState = [
          lastPreviewContentGoalFound : lastPreviewContentGoalFound,
          lastPreviewContentGoalPhrase: lastPreviewContentGoalPhrase
        ]
        for (def tcObj : runList) {
          if (cancelRequested != null && cancelRequested.get()) {
            Thread.currentThread().interrupt()
            throw new InterruptedException(AIASSISTANT_PIPELINE_CANCELLED)
          }
          if (!(tcObj instanceof Map)) {
            continue
          }
          def tc = tcObj as Map
          String id = tc.get('id')?.toString()
          def fn = tc.get('function') as Map
          String fnName = fn instanceof Map ? (fn.get('name')?.toString() ?: '') : ''
          String argsStr = fn instanceof Map ? (fn.get('arguments')?.toString() ?: '{}') : '{}'
          if ('WriteContent'.equals(fnName)) {
            argsStr = plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools.normalizeWriteContentToolArgsJson(argsStr)
            if (fn instanceof Map) {
              fn.put('arguments', argsStr)
            }
            try {
              Object argsParsed = slurper.parseText(argsStr ?: '{}')
              if (argsParsed instanceof Map) {
                String wpath = repoPathFromToolArgsMap((Map) argsParsed)
                if (wpath) {
                  String wkey = wpath.toLowerCase(Locale.ROOT)
                  if (writeContentPathsThisTurn.contains(wkey)) {
                    String dupOut = JsonOutput.toJson([
                      ok                          : true,
                      skippedDuplicateWriteThisTurn: true,
                      path                        : wpath,
                      message                     :
                        'WriteContent skipped: this repository path was already written in this chat turn. ' +
                          'Do not repeat the same WriteContent unless correcting a failed preview verification. ' +
                          'Use GetPreviewHtml to verify rendered output, or GetContent if you need the latest file body.'
                    ])
                    wireMessages << [role: 'tool', tool_call_id: id, content: dupOut]
                    continue
                  }
                }
              }
            } catch (Throwable ignoredDup) {
            }
          }
          if (fnName == 'WriteContent' ||
            fnName == 'publish_content' ||
            fnName == 'TranslateContentItem' ||
            fnName == 'TranslateContentBatch' ||
            fnName == 'revert_change') {
            repoMutationThisRound = true
          }
          if ('GetPreviewHtml'.equals(fnName)) {
            roundRanGetPreviewHtml = true
            if (roundHadWriteAttempt && !roundHadWriteSuccess) {
              String skipOut = JsonOutput.toJson([
                ok                       : false,
                skippedBecauseWriteFailed: true,
                message                  :
                  'GetPreviewHtml skipped: WriteContent did not succeed in this tool round. Fix the write (check contentXml and required fields), then call GetPreviewHtml again.'
              ])
              wireMessages << [role: 'tool', tool_call_id: id, content: skipOut]
              continue
            }
          }
          FunctionToolCallback tcb = fnName ? byName.get(fnName) : null
          String toolOut
          aiAssistantToolWorkerDiagPhase(
            "native_tool_loop_round_${round}_repository_tool name=${fnName ?: '?'} argsChars=${(argsStr ?: '').length()}"
          )
          if (tcb == null) {
            toolOut = JsonOutput.toJson([ok: false, error: 'unknown_tool', tool: fnName])
            log.warn('Tools-loop tools-on: unknown tool {} agentId={}', fnName, agentId)
          } else {
            try {
              ChatCompletionsToolWire.nativeToolCallIdBindingSet(id)
              toolOut = tcb.call(argsStr)
            } catch (Throwable tex) {
              log.warn('Tools-loop tools-on: tool {} failed: {}', fnName, tex.message)
              toolOut = JsonOutput.toJson([ok: false, error: tex.message?.toString()])
            } finally {
              ChatCompletionsToolWire.nativeToolCallIdBindingClear()
            }
          }
          aiAssistantToolWorkerDiagPhase(
            "native_tool_loop_round_${round}_repository_tool_done name=${fnName ?: '?'} outChars=${(toolOut ?: '').toString().length()}"
          )
          if (toolOut == null) {
            toolOut = ''
          } else if (toolOut instanceof Map) {
            // Spring AI may return the tool Map directly; JsonSlurper needs JSON, not Map#toString().
            toolOut = JsonOutput.toJson((Map) toolOut)
          } else {
            toolOut = toolOut.toString()
          }
          if ('FetchHttpUrl'.equals(fnName)) {
            try {
              def parsedFetch = slurper.parseText(toolOut.toString())
              if (parsedFetch instanceof Map && Boolean.TRUE.equals(((Map) parsedFetch).get('ok'))) {
                def bod = ((Map) parsedFetch).get('body')
                if (bod != null && bod.toString().trim()) {
                  anySuccessfulFetchHttpUrl = true
                }
              }
            } catch (Throwable ignoredFetchOk) {
            }
          }
          if ('WriteContent'.equals(fnName)) {
            roundHadWriteAttempt = true
            try {
              def parsedW = slurper.parseText(toolOut.toString())
              if (parsedW instanceof Map && !Boolean.TRUE.equals(((Map) parsedW).get('skippedDuplicateWriteThisTurn'))) {
                boolean wOk = Boolean.TRUE.equals(((Map) parsedW).get('ok')) ||
                  'written'.equalsIgnoreCase(((Map) parsedW).get('result')?.toString()?.trim())
                if (wOk) {
                  roundHadWriteSuccess = true
                  markTaskCompletionWallMsIfUnset(toolTimingCtx)
                  Object argsParsed = slurper.parseText(argsStr ?: '{}')
                  if (argsParsed instanceof Map) {
                    Map wArgs = (Map) argsParsed
                    String wpath = repoPathFromToolArgsMap(wArgs)
                    if (wpath) {
                      writeContentPathsThisTurn.add(wpath.toLowerCase(Locale.ROOT))
                      if (ops != null) {
                        try {
                          AuthoringIntentRecipeBindings.updateCurrentFromWrite(ops, wpath, wArgs)
                        } catch (Throwable ignoredBindingWrite) {
                        }
                      }
                    }
                  }
                } else {
                  roundHadWriteFailure = true
                  if (toolWireIndicatesInvalidSiteItemDocument(toolOut.toString())) {
                    writeContentInvalidDocumentFailures++
                    try {
                      Object argsParsed = slurper.parseText(argsStr ?: '{}')
                      if (argsParsed instanceof Map) {
                        String wpath = repoPathFromToolArgsMap((Map) argsParsed)
                        if (wpath) {
                          lastInvalidWriteContentPath = wpath
                        }
                      }
                    } catch (Throwable ignoredInvPath) {
                    }
                  }
                }
              }
            } catch (Throwable ignoredWtrack) {
            }
          }
          if ('GetPreviewHtml'.equals(fnName)) {
            Map enriched = enrichGetPreviewHtmlToolResult(toolOut.toString(), frozenAuthorOutcomePhrase, slurper)
            toolOut = enriched.toolOut?.toString() ?: toolOut
            if (enriched.previewGoalFound instanceof Boolean) {
              lastPreviewContentGoalFound = enriched.previewGoalFound
              previewState.lastPreviewContentGoalFound = enriched.previewGoalFound
            }
            if (enriched.previewGoalPhrase) {
              lastPreviewContentGoalPhrase = enriched.previewGoalPhrase.toString()
              previewState.lastPreviewContentGoalPhrase = enriched.previewGoalPhrase.toString()
            }
            if (lastPreviewContentGoalFound == Boolean.FALSE && frozenAuthorOutcomePhrase?.trim()) {
              log.warn(
                'Tools-loop: GetPreviewHtml missing expected phrase "{}" agentId={} round={}',
                frozenAuthorOutcomePhrase,
                agentId,
                round
              )
            }
          }
          String toolWire = truncateNativeToolWireContent(fnName, toolOut, id, generateImageDataUrlByToolCallId)
          if (toolWire.length() < toolOut.length() && !'GenerateImage'.equals(fnName)) {
            log.warn(
              'Tools-loop native tools: truncated tool wire output tool={} agentId={} beforeChars={} afterChars={}',
              fnName,
              agentId,
              toolOut.length(),
              toolWire.length()
            )
          }
          wireMessages << [role: 'tool', tool_call_id: id, content: toolWire]
        }
        if (previewState.lastPreviewContentGoalFound instanceof Boolean) {
          lastPreviewContentGoalFound = previewState.lastPreviewContentGoalFound
        }
        if (previewState.lastPreviewContentGoalPhrase) {
          lastPreviewContentGoalPhrase = previewState.lastPreviewContentGoalPhrase.toString()
        }
        maybeAppendAutoConfirmationPreviewAfterRound(
          wireMessages,
          byName,
          slurper,
          roundHadWriteSuccess,
          roundRanGetPreviewHtml,
          roundHadWriteFailure,
          frozenAuthorOutcomePhrase,
          toolsLoopSessionBundle,
          round,
          agentId,
          previewState
        )
        if (previewState.lastPreviewContentGoalFound instanceof Boolean) {
          lastPreviewContentGoalFound = previewState.lastPreviewContentGoalFound
        }
        if (previewState.lastPreviewContentGoalPhrase) {
          lastPreviewContentGoalPhrase = previewState.lastPreviewContentGoalPhrase.toString()
        }
        if (writeContentInvalidDocumentFailures >= 3 && !roundHadWriteSuccess) {
          log.warn(
            'Tools-loop: stopping after {} invalid WriteContent attempts (fragment/partial XML) round={} agentId={}',
            writeContentInvalidDocumentFailures,
            round,
            agentId
          )
          assistantAccum = synthesizeCorruptSiteItemXmlMessage(lastInvalidWriteContentPath ?: '/site/website/index.xml')
          finished = true
          break
        }
        if (writeContentInvalidDocumentFailures >= 2 && !roundHadWriteSuccess && round < maxRounds - 1) {
          wireMessages << [
            role   : 'user',
            content:
              '[aiassistant: WriteContent blocked — internal]\n' +
                '**WriteContent** failed repeatedly because **contentXml** was a **fragment**, not a full `<page>` / `<component>` document. ' +
                '**Stop** calling **WriteContent** with invented or partial XML. Call **GetContent** on the anchored path, edit **one** existing field element in that full **contentXml**, then **WriteContent** the **entire** file once.\n'
          ]
        }
        if (anySuccessfulFetchHttpUrl) {
          try {
            String anchor = buildAuthoringIntentAnchorMessageForReferenceFetch(wireMessages)
            if (anchor?.trim()) {
              wireMessages << [role: 'user', content: anchor]
              log.info(
                'Tools-loop tools-on: injected authoring goal anchor after FetchHttpUrl agentId={} round={}',
                agentId,
                round
              )
            }
          } catch (Throwable ignoredAnchor) {
          }
        }
        if (repoMutationThisRound &&
          roundHadWriteSuccess &&
          lastPreviewContentGoalFound == Boolean.TRUE) {
          String previewUrl = enginePreviewUrlFromWire(wireMessages, toolsLoopSessionBundle)
          assistantAccum = synthesizePlanExecutionAfterVerifiedWrite(
            frozenAuthorOutcomePhrase ?: lastPreviewContentGoalPhrase,
            previewUrl
          )
          log.info(
            'Tools-loop: early finish after tool round — write ok, preview phrase verified; skip further LLM rounds round={} agentId={}',
            round,
            agentId
          )
          aiAssistantToolWorkerDiagPhase("native_tool_loop_round_${round}_early_finish_after_tools_verified")
          finished = true
          break
        }
        previousRoundHadRepoMutation = repoMutationThisRound
        continue
      }
      String assistNoToolCalls = assistantTextFromChoiceMessageMap(msgCopy) ?: ''
      boolean assistLooksLikePlanWithoutTools = assistantProsePromisedToolsButOmittedCalls(assistNoToolCalls)
      boolean userNeedsCmsTools = false
      boolean userNeedsImageGenerate = false
      try {
        userNeedsCmsTools =
          AuthoringPreviewContext.authorVisibleSuggestsCmsTooling(userWireSnapshotForRecovery) ||
            AuthoringPreviewContext.anchoredSiteXmlFieldPlacementIntent(userWireSnapshotForRecovery) ||
            AuthoringPreviewContext.authorVisibleSuggestsOpenPageInquiry(userWireSnapshotForRecovery) ||
            AuthoringPreviewContext.isShortAffirmationContinuingPriorCmsWork(userWireSnapshotForRecovery)
        userNeedsImageGenerate =
          AuthoringPreviewContext.authorCurrentRequestLooksLikeImageOnlyGenerate(userWireSnapshotForRecovery)
      } catch (Throwable ignoredRec) {
      }
      boolean assistFakedImageTool = assistantProseFakedGenerateImageWithoutCalls(assistNoToolCalls)
      boolean assistClaimsTurnComplete = assistantProseClaimsTurnCompleteDespitePlanBullets(assistNoToolCalls)
      if (previousRoundHadRepoMutation &&
        assistClaimsTurnComplete &&
        lastPreviewContentGoalFound == Boolean.TRUE) {
        log.info(
          'Tools-loop: early finish — repository mutated, preview phrase verified, assistant wrap-up round={} agentId={}',
          round,
          agentId
        )
        aiAssistantToolWorkerDiagPhase("native_tool_loop_round_${round}_early_finish_preview_verified")
        assistantAccum = assistantTextFromChoiceMessageMap(msgCopy)
        finished = true
        break
      }
      boolean openPageInquiryNoTools =
        AuthoringPreviewContext.authorVisibleSuggestsOpenPageInquiry(userWireSnapshotForRecovery) &&
          assistNoToolCalls?.trim()
      if (prosePlanMissingToolNudges < 2 &&
        round < maxRounds - 1 &&
        ((userNeedsCmsTools && assistLooksLikePlanWithoutTools) ||
          openPageInquiryNoTools ||
          (userNeedsImageGenerate && (assistLooksLikePlanWithoutTools || assistFakedImageTool)))) {
        if (previousRoundHadRepoMutation && assistClaimsTurnComplete) {
          if (lastPreviewContentGoalFound == Boolean.FALSE && lastPreviewContentGoalPhrase?.trim()) {
            if (previewVerificationFailedNudges >= 1) {
              log.info(
                'Tools-loop: preview still missing phrase "{}" after correction nudge — accepting assistant wrap-up round={} agentId={}',
                lastPreviewContentGoalPhrase,
                round,
                agentId
              )
              assistantAccum = assistantTextFromChoiceMessageMap(msgCopy)
              finished = true
              break
            }
            previewVerificationFailedNudges++
            prosePlanMissingToolNudges++
            log.info(
              'Tools-loop: assistant claimed success but preview missing phrase "{}" — injecting verification correction ({}/{}) round={} agentId={}',
              lastPreviewContentGoalPhrase,
              previewVerificationFailedNudges,
              1,
              round,
              agentId
            )
            aiAssistantToolWorkerDiagPhase("native_tool_loop_round_${round}_preview_verification_failed_nudge")
            wireMessages << [
              role   : 'user',
              content:
                '[aiassistant: preview verification failed — internal]\n' +
                  '**GetPreviewHtml** did not show the expected copy **"' + lastPreviewContentGoalPhrase.trim() + '"** in rendered HTML. ' +
                  '**Do not** claim success. Fix the correct content field (from **GetContentTypeFormDefinition** / formDefinitionXml) on the anchored **`/site/.../*.xml`** path with **WriteContent**, then **GetPreviewHtml** again. ' +
                  'If XML is correct but preview is still wrong, call **analyze_template** read-only on the **display-template** `.ftl` to check for hardcoded copy — **do not** patch FTL for a content-only field edit unless the author explicitly asked for template changes. ' +
                  'When done, one final message starting with **## Plan Execution** (not **## Plan** again) with honest **✅ / ⚠️** markers.\n'
            ]
            continue
          }
          log.info(
            'Tools-loop: skip tools-required nudge — repository already mutated and assistant claims completion round={} agentId={}',
            round,
            agentId
          )
        } else {
          prosePlanMissingToolNudges++
          log.info(
            'Tools-loop tools-on: assistant plan-style reply without tool_calls — injecting recovery user nudge ({}/{}) round={} agentId={}',
            prosePlanMissingToolNudges,
            2,
            round,
            agentId
          )
          aiAssistantToolWorkerDiagPhase("native_tool_loop_round_${round}_missing_tool_calls_nudge")
          String recoveryBody = userNeedsImageGenerate ?
            '''[aiassistant: tools-required recovery — internal]
Your last assistant message described **GenerateImage** (plan and/or fenced JSON) but the chat completion had **no `tool_calls`**, so **no image was generated**. **Reply again** for the same author request: emit a **non-empty `tool_calls`** array with **GenerateImage** as the first tool (concrete **prompt** from the author's words). **Do not** print fake `🛠️ GenerateImage` lines or ```json tool payloads in prose — only real **tool_calls**. After the tool returns, a short **## Plan Execution** wrap-up is enough; the bitmap appears in the Studio chat image strip.''' :
            openPageInquiryNoTools ?
            '''[aiassistant: tools-required recovery — internal]
The author asked what **this page** is about; Studio already anchored **contentPath** in the user message. Your last completion had **no `tool_calls`**, so you did not read repository XML. **Reply again**: emit **GetContent** on the anchored **`/site/.../*.xml`** path as the **first** tool (optional **GetPreviewHtml**), then answer from that XML — **do not** claim you lack access to the page.''' :
            '''[aiassistant: tools-required recovery — internal]
Your last assistant message had a **plan-style heading** (## Plan, ## Revised Plan, ## Next Steps, …) and described concrete CMS work, but the chat completion had **no `tool_calls`**, so the server ran **no** tools on that turn. **Reply again** for the same author request: keep or tighten the plan, then emit a **non-empty `tool_calls`** array and execute the next real step. **Match tools to the ask:** for **content** on **`/site/.../*.xml`** (field values, copy, tone) use **GetContent** + **WriteContent** (or **update_content** + **WriteContent**) on **those XML paths** — **not** **update_template** or **WriteContent** on **`.ftl`** unless the author explicitly asked for **template/FreeMarker** changes. **Template/CSS/schema** work: discover paths from **GetContent** on the page/component XML (**display-template**, linked assets) or prior tool results — **never** guess **`/static-assets/styles.css`**. **FetchHttpUrl** only for **http(s)** references the author gave. **Do not** end with prose-only, rhetorical questions, or “would you like a draft” while repository work remains; either call tools or state a **single** blocking error (e.g. missing path) with the exact tool result you saw.'''
          wireMessages << [role: 'user', content: recoveryBody]
          continue
        }
      }
      aiAssistantToolWorkerDiagPhase("native_tool_loop_round_${round}_final_assistant_message_no_more_tools")
      assistantAccum = assistantTextFromChoiceMessageMap(msgCopy)
      finished = true
      break
    }
    if (!finished) {
      throw new IllegalStateException("Tools-loop tools-on: exceeded ${maxRounds} tool rounds without a final assistant message")
    }
    return [
      text              : (assistantAccum ?: ''),
      previewGoalFound  : lastPreviewContentGoalFound,
      previewGoalPhrase : (lastPreviewContentGoalPhrase ?: '')
    ]
  }

  /**
   * Tools-loop native tools without {@link OpenAiChatModel}: sync {@code stream:false} rounds + {@link JsonSlurper}
   * + {@link FunctionToolCallback#call(String)} until the assistant stops calling tools.
   * <p>One chat session with tools enabled: the model should stream a **## Plan** (see system STUDIO POLICY) in the
   * <strong>first assistant message</strong> whenever it also issues tool calls; that assistant {@code content} is
   * forwarded to {@code sseOut} before tools run so authors see plan → tools → final answer like a composer flow.</p>
   * <p>Post-execution review (extra LLM pass + optional correction loop) is <strong>not</strong> run — hardcoded off.</p>
   *
   * @param sseOut when non-null (Studio SSE), assistant text before each tool round is streamed when present
   */
  static String executeNativeToolsViaRestClientReturnText(
    String apiKey,
    String model,
    Prompt toolsLoopPrompt,
    List tools,
    String agentId,
    OutputStream sseOut = null,
    Map toolTimingCtx = null,
    AtomicBoolean cancelRequested = null,
    String wireBaseUrl = null,
    Map toolsLoopSessionBundle = null,
    Map<String, String> generateImageBacklogByToolCallId = null
  ) {
    markPipelineWallStart(toolTimingCtx)
    if (cancelRequested != null && cancelRequested.get()) {
      throw new InterruptedException(AIASSISTANT_PIPELINE_CANCELLED)
    }
    aiAssistantPipelineCancelBindingSet(cancelRequested)
    try {
    if (!apiKey) {
      throw new IllegalStateException('Tools-loop chat API key missing')
    }
    aiAssistantToolWorkerDiagPhase("native_tools_session_prepare agentId=${agentId ?: ''} model=${model ?: ''}")
    List<Map> baseWire = []
    chatCompletionMessagesForApi(toolsLoopPrompt).each { cm ->
      baseWire << wireMessageFromChatCompletionMessage(cm)
    }
    Map lastUserTemplate = lastUserWireMessage(baseWire)
    if (lastUserTemplate == null || !lastUserTemplate.get('content')?.toString()?.trim()) {
      throw new IllegalStateException('Tools-loop tools-on: prompt has no user message')
    }
    def origUser = lastUserTemplate.get('content')?.toString() ?: ''
    Map intentTel =
      (toolsLoopSessionBundle instanceof Map) ? (Map) toolsLoopSessionBundle.get('intentRecipeRoutingTelemetry') : null
    if (intentTel == null && toolsLoopSessionBundle instanceof Map && toolsLoopSessionBundle.intentRecipeRoutingTelemetry instanceof Map) {
      intentTel = (Map) toolsLoopSessionBundle.intentRecipeRoutingTelemetry
    }
    String authorVisible = authorVisibleFromPromptText(origUser)
    List effectiveTools = tools
    effectiveTools = effectiveToolsForIntentRecipe(tools, intentTel, authorVisible, agentId)
    effectiveTools = applyGenerateImageTurnToolPolicy(effectiveTools, intentTel, origUser, agentId)
    if (intentTel instanceof Map && Boolean.TRUE.equals(intentTel.get('generateImageToolUnavailable'))) {
      log.info('Tools-loop: returning imageModel configuration message (GenerateImage not registered) agentId={}', agentId)
      return synthesizeGenerateImageUnavailableMarkdown()
    }
    def wireTools = buildWireToolsFromCallbacks(effectiveTools)
    if (!wireTools) {
      if (intentTel instanceof Map && Boolean.TRUE.equals(intentTel.get('generateImageToolUnavailable'))) {
        return synthesizeGenerateImageUnavailableMarkdown()
      }
      throw new IllegalStateException('CMS tools: empty tool list')
    }
    Map<String, FunctionToolCallback> byName = toolCallbacksByName(effectiveTools)
    String serverHotpathText = tryServerPrefetchGenerateImageHotpath(
      origUser,
      authorVisible,
      toolsLoopSessionBundle,
      byName,
      agentId,
      sseOut,
      cancelRequested,
      toolTimingCtx
    )
    if (serverHotpathText == null) {
      serverHotpathText = tryServerPrefetchContentAwareRevertHotpath(
        origUser,
        authorVisible,
        intentTel,
        toolsLoopSessionBundle,
        byName,
        agentId,
        sseOut,
        cancelRequested,
        toolTimingCtx
      )
    }
    if (serverHotpathText == null) {
      serverHotpathText = tryServerPrefetchSimpleFieldEditHotpath(
        origUser,
        authorVisible,
        intentTel,
        toolsLoopSessionBundle,
        byName,
        agentId,
        sseOut,
        cancelRequested,
        toolTimingCtx
      )
    }
    if (serverHotpathText == null) {
      serverHotpathText = tryServerPrefetchExternalContentFieldEditHotpath(
        origUser,
        authorVisible,
        intentTel,
        toolsLoopSessionBundle,
        byName,
        agentId,
        sseOut,
        cancelRequested,
        toolTimingCtx
      )
    }
    if (serverHotpathText != null) {
      log.info(
        'Tools-loop: server prefetch field edit hotpath completed (skipped native tool-loop LLM) agentId={} fieldId={}',
        agentId,
        intentTel?.prefetchResolvedFieldId ?: ''
      )
      return serverHotpathText
    }
    if (Boolean.TRUE.equals(toolsLoopSessionBundle?.prefetchHotpathCorruptItemXml)) {
      String corruptPath = ''
      try {
        StudioToolOperations opsEarly = (toolsLoopSessionBundle?.studioOps instanceof StudioToolOperations) ?
          (StudioToolOperations) toolsLoopSessionBundle.studioOps :
          null
        corruptPath = opsEarly?.recipeEngineAuthoringBindings()?.contentPath ?: ''
      } catch (Throwable ignoredCp) {
      }
      log.warn(
        'Tools-loop: aborting tools loop — repository item XML is corrupt or partial path={} agentId={}',
        corruptPath,
        agentId
      )
      return synthesizeCorruptSiteItemXmlMessage(corruptPath)
    }
    List<Map> wireMessages = deepCloneWireMessages(baseWire)
    Map wmUser = lastUserWireMessage(wireMessages)
    Map<String, String> cqGenerateImageDataUrlByToolCallId = new LinkedHashMap<>()
    Map loopOut = runNativeToolLoopToAssistantText(
      apiKey,
      model,
      wireMessages,
      wireTools,
      byName,
      agentId,
      40,
      true,
      sseOut,
      cancelRequested,
      wireBaseUrl,
      toolsLoopSessionBundle,
      cqGenerateImageDataUrlByToolCallId,
      toolTimingCtx
    )
    String assistantAccum = (loopOut?.text ?: '').toString()
    Boolean lastPreviewContentGoalFound = loopOut?.previewGoalFound instanceof Boolean ? (Boolean) loopOut.previewGoalFound : null
    String lastPreviewContentGoalPhrase = (loopOut?.previewGoalPhrase ?: '').toString()
    if (postToolReviewEnabled() && (cancelRequested == null || !cancelRequested.get())) {
      try {
        emitSseToolProgressLine(
          sseOut,
          '🛠️🔄 **Post-tool review** … comparing your request to the assistant reply (tools-loop path only; no repository writes).\n',
          'start',
          'summary'
        )
        Map rev = postToolReview(apiKey, model, origUser, assistantAccum, agentId, sseOut)
        emitSseToolProgressLine(
          sseOut,
          '🛠️🔄 ✅ **Post-tool review** finished.\n',
          'done',
          'summary'
        )
        boolean acc = rev?.accomplished != null && Boolean.TRUE.equals(rev.accomplished)
        if (!acc) {
          String corr = (rev?.correctionInstructions ?: '').toString().trim()
          if (corr) {
            emitSseToolProgressLine(
              sseOut,
              '🛠️🔄 **Correction pass** … running follow-up tools from the review (same chat session).\n',
              'start',
              'summary'
            )
            wireMessages << [role: 'user', content: buildPostReviewCorrectionUserMessage(rev)]
            Map loopOut2 = runNativeToolLoopToAssistantText(
              apiKey,
              model,
              wireMessages,
              wireTools,
              byName,
              agentId,
              15,
              false,
              sseOut,
              cancelRequested,
              wireBaseUrl,
              toolsLoopSessionBundle,
              cqGenerateImageDataUrlByToolCallId,
              toolTimingCtx
            )
            assistantAccum = (loopOut2?.text ?: '').toString()
            if (loopOut2?.previewGoalFound instanceof Boolean) {
              lastPreviewContentGoalFound = (Boolean) loopOut2.previewGoalFound
            }
            if (loopOut2?.previewGoalPhrase) {
              lastPreviewContentGoalPhrase = loopOut2.previewGoalPhrase.toString()
            }
          }
        }
      } catch (Throwable tre) {
        log.warn('Tools-loop post-tool review/correction skipped', tre)
        def em = (tre?.message ?: tre?.toString() ?: 'error').toString()
        if (em.length() > 200) {
          em = em.substring(0, 197) + '…'
        }
        emitSseToolProgressLine(
          sseOut,
          '🛠️🔄 ⚠️ **Post-tool review** skipped: ' + em + '\n',
          'warn',
          'summary'
        )
      }
    }
    if (sseOut != null) {
      try {
        def hint = [
          text    : '',
          metadata: [status: 'aiassistant-chat-phase', phase: 'summarizing-results', pipelineStage: 'summary']
        ]
        synchronized (sseOut) {
          sseOut.write(("data: ${JsonOutput.toJson(hint)}\n\n").getBytes(StandardCharsets.UTF_8))
          sseOut.flush()
        }
      } catch (Throwable ignored) {
        // never break return path
      }
    }
    Map<String, String> mergedImgUrls =
      mergedGenerateImageUrlByToolCallId(cqGenerateImageDataUrlByToolCallId, generateImageBacklogByToolCallId)
    String sanitized =
      sanitizeAssistantMarkdownReplaceGenerateImageDataUrlsWithRefs((assistantAccum ?: '').toString(), mergedImgUrls)
    sanitized = promotePlanToPlanExecutionIfNeeded(sanitized)
    sanitized = appendPreviewVerificationWarningIfNeeded(
      sanitized,
      lastPreviewContentGoalFound,
      lastPreviewContentGoalPhrase
    )
    String authorMarkdown =
      ChatCompletionsToolWire.appendMissingInlineImageRefs(
        sanitized,
        cqGenerateImageDataUrlByToolCallId,
        generateImageBacklogByToolCallId
      )
    /** Expand refs for author-visible SSE only here (not in streaming flux mid-chunks). Client preprocess maps huge {@code data:} to short blob refs. */
    return ChatCompletionsToolWire.expandInlineImageRefs(
      authorMarkdown,
      cqGenerateImageDataUrlByToolCallId,
      generateImageBacklogByToolCallId
    )
    } finally {
      aiAssistantPipelineCancelBindingClear()
    }
  }

  /**
   * {@code String} indices are UTF-16 code units — never split between a high and low surrogate when chunking SSE JSON.
   */
  private static int toolsLoopSseTextChunkEndExclusive(String s, int start, int step) {
    int len = s.length()
    if (start >= len) {
      return len
    }
    int end = Math.min(len, start + step)
    if (end >= len) {
      return len
    }
    while (end > start && Character.isHighSurrogate(s.charAt(end - 1))) {
      end--
    }
    return end > start ? end : Math.min(len, start + 1)
  }

  /**
   * Writes the final assistant markdown to Studio SSE. Large {@code data:image/...} expansions can exceed single-line
   * JSON limits in browsers; split into multiple {@code text} frames (the client concatenates).
   */
  private static void writeSseFinalAssistantTextChunks(OutputStream out, String finalText) throws IOException {
    String t = finalText != null ? finalText.toString() : ''
    int step = NATIVE_TOOLS_FINAL_SSE_TEXT_CHUNK_CHARS
    if (!t) {
      out.write(("data: ${JsonOutput.toJson([text: '', metadata: [:]])}\n\n").getBytes(StandardCharsets.UTF_8))
      return
    }
    if (t.length() <= step) {
      out.write(("data: ${JsonOutput.toJson([text: t, metadata: [:]])}\n\n").getBytes(StandardCharsets.UTF_8))
      return
    }
    log.info(
      'Tools-loop native tools: splitting final assistant SSE into {} chunks (totalChars={} chunkChars={})',
      (int) Math.ceil(t.length() / (double) step),
      t.length(),
      step
    )
    for (int i = 0; i < t.length();) {
      int end = toolsLoopSseTextChunkEndExclusive(t, i, step)
      String part = t.substring(i, end)
      out.write(("data: ${JsonOutput.toJson([text: part, metadata: [:]])}\n\n").getBytes(StandardCharsets.UTF_8))
      i = end
    }
  }

  private void writeToolsOnViaRestClientToolLoop(
    OutputStream out,
    String apiKey,
    String model,
    Prompt authoringChatPrompt,
    List tools,
    String agentId,
    Map toolTimingCtx = null,
    AtomicBoolean cancelRequested = null,
    AtomicBoolean terminalEmitted = null,
    String wireBaseUrl = null,
    Map toolsLoopSessionBundle = null,
    Map<String, String> generateImageBacklogByToolCallId = null
  ) {
    aiAssistantToolWorkerDiagPhase("openai_tools_worker_start agentId=${agentId ?: ''} model=${model ?: ''}")
    String text
    try {
      text = executeNativeToolsViaRestClientReturnText(
        apiKey,
        model,
        authoringChatPrompt,
        tools,
        agentId,
        out,
        toolTimingCtx,
        cancelRequested,
        wireBaseUrl,
        toolsLoopSessionBundle,
        generateImageBacklogByToolCallId
      )
    } catch (InterruptedException ie) {
      log.warn(
        'AI Assistant chat stream: Tools-loop tools worker stopped after cancel (client abort / Stop). agentId={} reason={}',
        agentId,
        ie.message
      )
      if (tryClaimToolsTerminalEmit(terminalEmitted)) {
        writeSseErrorFrame(out, new InterruptedException('Request was cancelled or stopped before the assistant finished.'))
      }
      return
    }
    try {
      synchronized (out) {
        // If timeout/recovery already emitted a terminal SSE, do not append more assistant text after it — that
        // ordering confuses the client. Duplicate `completed` remains suppressed by the CAS below.
        boolean terminalAlready = terminalEmitted != null && terminalEmitted.get()
        if (!terminalAlready) {
          String finalChunk = stripForbiddenMetaPlanFromAssistantText((text ?: '').toString())
          writeSseFinalAssistantTextChunks(out, finalChunk)
        }
        if (tryClaimToolsTerminalEmit(terminalEmitted)) {
          def doneMeta = new LinkedHashMap()
          doneMeta.completed = true
          mergeToolPipelineWallMsIntoMetadata(doneMeta, toolTimingCtx)
          out.write(("data: ${JsonOutput.toJson([text: '', metadata: doneMeta])}\n\n").getBytes(StandardCharsets.UTF_8))
        }
        out.flush()
      }
    } catch (Throwable io) {
      if (isSseClientDisconnected(io)) {
        log.warn(
          'AI Assistant chat stream: CLIENT_ABORT — final SSE not written (connection already closed). agentId={} detail={}',
          agentId,
          io.message
        )
      } else {
        throw io
      }
    }
  }

  /**
   * OpenAI + native tools off: explicit {@code stream=true} on the request record (no {@link OpenAiChatModel} merge).
   * Uses {@link RestClient} {@code exchange} + line-wise SSE parsing so token deltas reach Studio (same HTTP path
   * that avoids {@code retrieve().body(String)} truncation). Emits Studio SSE then returns.
   */
  private void writeToolsOffViaChatCompletionEntity(
    OutputStream out,
    String apiKey,
    String model,
    Prompt authoringChatPrompt,
    String agentId,
    String wireBaseUrl = null
  ) {
    if (!apiKey) {
      throw new IllegalStateException('Tools-loop tools-off chat: API key missing')
    }
    if (!model?.toString()?.trim()) {
      throw new IllegalStateException('Tools-loop tools-off chat: model missing')
    }
    def msgs = chatCompletionMessagesForApi(authoringChatPrompt)
    // Groovy cannot resolve `new ChatCompletionRequest(msgs, model, null, true)` reliably: `null` matches
    // both (..., Double, boolean) and (..., List tools, Object toolChoice) → wrong ctor or wrong wire JSON.
    def reqCtor = ChatCompletionRequest.getConstructor(
      java.util.List.class,
      String.class,
      Double.class,
      boolean.class
    )
    def req = reqCtor.newInstance(msgs, model, null, true) as ChatCompletionRequest
    def jsonBody = chatCompletionsWireBodyApplyNeoTemperaturePolicy(ModelOptionsUtils.toJsonString(req))
    try {
      log.debug(
        'Tools-loop tools-off request wire (truncated): {}',
        AiHttpProxy.elideForLog(jsonBody, 1200)
      )
    } catch (Throwable ignored) {}
    log.debug(
      'Tools-loop tools-off: RestClient exchange POST /v1/chat/completions (stream=true; forward upstream SSE) agentId={} model={} messageCount={}',
      agentId,
      model,
      msgs.size()
    )
    try {
      chatCompletionsRestClientBuilder(apiKey, wireBaseUrl)
        .defaultHeader(HttpHeaders.ACCEPT, 'text/event-stream, application/json')
        .build()
        .post()
        .uri('/v1/chat/completions')
        .contentType(MediaType.APPLICATION_JSON)
        .body(jsonBody)
        .exchange { httpReq, resp ->
          def status = resp.getStatusCode()
          def statusText = resp.getStatusText()
          def headers = resp.getHeaders()
          if (!status.is2xxSuccessful()) {
            byte[] bytes
            try {
              def eis = resp.getBody()
              bytes = eis != null ? eis.readAllBytes() : new byte[0]
            } finally {
              try {
                resp.close()
              } catch (Throwable ignored) {}
            }
            def bodyStr = new String(bytes, StandardCharsets.UTF_8)
            log.error('Tools-loop tools-off: HTTP {} body=\n{}', status, AiHttpProxy.elideForLog(bodyStr, 4000))
            throw new RestClientResponseException(
              'Tools-loop chat',
              status.value(),
              statusText,
              headers,
              bytes,
              StandardCharsets.UTF_8
            )
          }
          try {
            def is = resp.getBody()
            copyUpstreamSseChatCompletionsToStudio(is, out, agentId, model)
          } finally {
            try {
              resp.close()
            } catch (Throwable ignored) {}
          }
          ''
        }
    } catch (RestClientResponseException e) {
      String rb = ''
      try {
        rb = e.getResponseBodyAsString(StandardCharsets.UTF_8)
      } catch (Throwable ignored) {}
      log.error('Tools-loop tools-off: HTTP {} body=\n{}', e.statusCode, AiHttpProxy.elideForLog(rb ?: '', 4000))
      Throwable toThrow = preferIllegalStateForInvalidModel(e, jsonBody?.toString())
      if (toThrow instanceof IllegalStateException) {
        throw (IllegalStateException) toThrow
      }
      throw (RestClientResponseException) toThrow
    }
  }

  /**
   * Spring AI response-shape compatibility across versions.
   * `prompt().call()` may return a response-spec object instead of ChatResponse directly.
   */
  private static String extractContentFromCallResult(def callResult) {
    if (callResult == null) return ''
    def hasContent = false
    def hasChatResponse = false
    try {
      hasContent = callResult?.metaClass?.respondsTo(callResult, 'content')?.size() > 0
    } catch (Throwable ignored) {}
    try {
      hasChatResponse = callResult?.metaClass?.respondsTo(callResult, 'chatResponse')?.size() > 0
    } catch (Throwable ignored) {}

    // Prefer chatResponse() first for adapters that need it. On some Studio stacks OpenAiChatModel's
    // chatCompletionEntity path truncates JSON; native tools use executeNativeToolsViaRestClientReturnText instead.
    // IMPORTANT: use only one terminal accessor to avoid duplicate upstream requests.
    if (hasChatResponse) {
      def cr = callResult.chatResponse()
      def txt = cr?.result?.output?.text ?: cr?.result?.output?.content
      return txt != null ? txt.toString() : ''
    }
    if (hasContent) {
      def c = callResult.content()
      return c != null ? c.toString() : ''
    }

    def txt = callResult?.result?.output?.text ?: callResult?.result?.output?.content
    return txt != null ? txt.toString() : ''
  }

  /** Unwrap ExecutionException; user-friendly text when the remote hosted chat API returns 5xx. */
  private static Throwable unwrapThrowable(Throwable t) {
    if (t instanceof java.util.concurrent.ExecutionException && t.cause != null) return t.cause
    return t
  }

  /** OpenAI errors often include a JSON body with {@code error.message} — log / surface it for debugging. */
  private static String extractChatCompletionsHttpErrorBody(Throwable t) {
    Throwable c = unwrapThrowable(t)
    while (c != null) {
      if (c instanceof WebClientResponseException) {
        try {
          return ((WebClientResponseException) c).getResponseBodyAsString(StandardCharsets.UTF_8)
        } catch (Throwable ignored) {
          return ''
        }
      }
      if (c instanceof RestClientResponseException) {
        try {
          return ((RestClientResponseException) c).getResponseBodyAsString(StandardCharsets.UTF_8)
        } catch (Throwable ignored) {
          return ''
        }
      }
      c = c.cause
    }
    return ''
  }

  private static String formatStreamErrorMessage(Throwable t) {
    def root = unwrapThrowable(t)
    def msg = (root?.message ?: root?.toString() ?: 'Unknown error').toString()
    def providerErrorBody = extractChatCompletionsHttpErrorBody(t)
    if (providerErrorBody?.trim() && (msg.contains('api.openai.com') || root instanceof WebClientResponseException || root instanceof RestClientResponseException)) {
      def elided = providerErrorBody.length() > 2000 ? providerErrorBody.substring(0, 2000) + '…' : providerErrorBody
      return 'Chat request failed. HTTP detail: ' + elided
    }
    if (msg.contains('timed out') || msg.contains('Timed out')) {
      return 'The request to the remote chat service timed out. Please try again.'
    }
    return 'Error: ' + msg
  }

  Map chatProxy(
    String agentId,
    String prompt,
    String chatId = null,
    String llm = null,
    String chatModel = null,
    String llmApiKey = null,
    String imageModel = null,
    boolean formEngineClientForward = false,
    String formEngineItemPathRaw = null,
    boolean enableTools = true,
    String imageGenerator = null
  ) {
    try {
      aiAssistantPipelineCancelBindingClear()
      ensureVerboseSpringAiHttpLogging()
      def fullSuppress = false
      def protNorm = null
      if (formEngineClientForward) {
        def n = AuthoringPreviewContext.normalizeRepoPath(formEngineItemPathRaw)
        if (n) {
          protNorm = n
        } else {
          fullSuppress = true
        }
      }
      def springAi = buildSpringAiChatClient(agentId, chatId, llm, chatModel, llmApiKey, null, imageModel, fullSuppress, protNorm, enableTools, imageGenerator)
      if (formEngineClientForward && !StudioAiLlmKind.useToolsLoopChatRestClient(springAi.llm, springAi)) {
        log.warn(
          'Form-engine client-apply: llm is {} (not a tools-loop RestClient row). Use openAI / xAI / deepSeek / llama / genesis (gemini) on this agent for native RestClient tools + best compliance with aiassistantFormFieldUpdates.',
          springAi.llm
        )
      }
      def bodyPrompt = formEngineClientForward ? prependFormEngineClientApplyEnforcement(prompt) : (prompt ?: '').toString()
      def userText = springAi.useTools ? addToolRequiredGuard(bodyPrompt, fullSuppress, protNorm) : bodyPrompt
      if (!formEngineClientForward && StudioAiLlmKind.useToolsLoopChatRestClient(springAi.llm, springAi)) {
        def route = intentRecipeRoutingPrelude(
          bodyPrompt,
          userText,
          StudioAiLlmKind.toolsLoopChatApiKeyFromBundle(springAi),
          (springAi.resolvedChatModel ?: resolveChatModel(chatModel)),
          StudioAiLlmKind.toolsLoopChatBaseUrlFromBundle(springAi),
          springAi,
          springAi.studioOps,
          effectiveAuthoringIntentExpansionRematchEnabled(bodyPrompt)
        )
        if (route?.intentRecipeRoutingTelemetry instanceof Map) {
          springAi.intentRecipeRoutingTelemetry = route.intentRecipeRoutingTelemetry
        }
        applyIntentRecipeRouteEffects(springAi, route)
        if (route.clarificationOnly) {
          String clar = toolsLoopSimpleCompletionAssistantText(
            StudioAiLlmKind.toolsLoopChatApiKeyFromBundle(springAi),
            (springAi.resolvedChatModel ?: resolveChatModel(chatModel)),
            ToolPrompts.getLlm_INTENT_CLARIFICATION_ONLY_SYSTEM(),
            route.clarificationUserText?.toString() ?: '',
            400,
            120_000,
            'IntentRecipeClarification',
            StudioAiLlmKind.toolsLoopChatBaseUrlFromBundle(springAi),
            springAi
          )
          return [ok: true, response: [content: clar, message: clar]]
        }
        userText = route.userTextForToolsLoop?.toString() ?: (springAi.useTools ? userText : bodyPrompt)
      }
      Prompt authoringChatPrompt = null
      def callSpec
      if (StudioAiLlmKind.useToolsLoopChatRestClient(springAi.llm, springAi)) {
        authoringChatPrompt = authoringPrompt(
          userText,
          fullSuppress,
          protNorm,
          springAi.useTools,
          springAi.studioOps,
          StudioAiLlmKind.toolsLoopChatApiKeyFromBundle(springAi)
        )
        logChatCompletionsPayloadApprox(
          agentId,
          (springAi.resolvedChatModel ?: resolveChatModel(chatModel)),
          authoringChatPrompt,
          springAi.tools
        )
        if (springAi.useTools) {
          // Native tools are executed via RestClient (see executeNativeToolsViaRestClientReturnText), not OpenAiChatModel.
          callSpec = null
        } else {
          callSpec = springAi.chatClient.prompt(authoringChatPrompt)
        }
      } else {
        callSpec = springAi.useTools
          ? springAi.chatClient.prompt().user(userText).tools(*springAi.tools)
          : springAi.chatClient.prompt().user(userText)
      }
      String content
      if (StudioAiLlmKind.useToolsLoopChatRestClient(springAi.llm, springAi) && springAi.useTools) {
        content = executeNativeToolsViaRestClientReturnText(
          StudioAiLlmKind.toolsLoopChatApiKeyFromBundle(springAi),
          (springAi.resolvedChatModel ?: resolveChatModel(chatModel)),
          authoringChatPrompt,
          springAi.tools,
          agentId,
          null,
          null,
          null,
          StudioAiLlmKind.toolsLoopChatBaseUrlFromBundle(springAi),
          springAi,
          null
        )
      } else {
        def callResult = callSpec.call()
        content = extractContentFromCallResult(callResult)
      }
      if (content == null) content = ''
      return [ok: true, response: [content: content, message: content]]
    } catch (IllegalStateException ise) {
      throw ise
    } catch (Exception e) {
      def body = extractChatCompletionsHttpErrorBody(e)
      def suffix = body?.trim() ? " Upstream body: ${body.length() > 1500 ? body.substring(0, 1500) + '…' : body}" : ''
      return [ok: false, message: "Spring AI chat failed: ${e.message}${suffix}"]
    }
  }

  /** Expert guidance / SME tools — server progress lines use {@code 🛠️🤓} before the category emoji. */
  private static boolean isExpertGuidanceToolName(String toolName) {
    def n = (toolName ?: '').toString().trim()
    return n == 'QueryExpertGuidance' || n == 'GetCrafterizingPlaybook'
  }

  /**
   * Second emoji after 🛠️ on server tool-progress lines: read 🔍, write/revert/publish/edit ✏️, analysis 📈, other 🔄.
   */
  private static String toolProgressCategoryEmoji(String toolName) {
    def n = (toolName ?: '').toString().trim()
    switch (n) {
      case 'GetContent':
      case 'ListContentDependencyScope':
      case 'ListContentTranslationScope':
      case 'GetContentTypeFormDefinition':
      case 'ListStudioContentTypes':
      case 'GetContentVersionHistory':
      case 'GetPreviewHtml':
      case 'FetchHttpUrl':
      case 'QueryExpertGuidance':
      case 'ListPagesAndComponents':
      case 'ResearchSiteContent':
      case 'WebSearch':
      case 'GetCrafterizingPlaybook':
        return '🔍'
      case 'Tools-loop chat':
        // Waiting on chat.completions between tool rounds — not a repo read; distinct from 🔍 tools.
        return '🔄'
      case 'WriteContent':
      case 'revert_change':
      case 'publish_content':
      case 'GenerateImage':
      case 'update_template':
      case 'update_content':
      case 'update_content_type':
      case 'TranslateContentItem':
      case 'TranslateContentBatch':
      case 'TransformContentSubgraph':
        return '✏️'
      case 'analyze_template':
        return '📈'
      default:
        return '🔄'
    }
  }

  /** Product prefix for injected tool lines: {@code 🛠️} + category (never ⏳). Expert tools add {@code 🤓} after {@code 🛠️}. */
  private static String toolProgressLinePrefix(String toolName) {
    def cat = toolProgressCategoryEmoji(toolName)
    if (isExpertGuidanceToolName(toolName)) {
      return '🛠️🤓' + cat
    }
    return '🛠️' + cat
  }

  /** Short human timing suffix for tool-progress lines (e.g. {@code ·245ms}, {@code ·1.2s}). */
  private static String formatCqDurationSuffix(Long ms) {
    if (ms == null || ms < 0L) {
      return ''
    }
    if (ms < 1000L) {
      return ' ·' + ms + 'ms'
    }
    return ' ·' + String.format(Locale.US, '%.1fs', ms / 1000.0d)
  }

  /**
   * Logs + SSE immediately before each blocking {@code POST /v1/chat/completions} in the native tool loop (same 🛠️ channel).
   */
  private static void emitRoundWaitSse(
    OutputStream o,
    int zeroBasedRound,
    String model,
    String agentId,
    int wireJsonChars,
    boolean previousRoundHadRepoMutation = false
  ) {
    log.debug(
      'Tools-loop wire → POST /v1/chat/completions phase=native_tool_loop round={} agentId={} model={} wireJsonChars={}',
      zeroBasedRound + 1,
      agentId,
      model,
      wireJsonChars
    )
    if (o == null) {
      return
    }
    try {
      String toolName = 'Tools-loop chat'
      String pfx = toolProgressLinePrefix(toolName)
      String line
      if (zeroBasedRound <= 0) {
        line = pfx + ' **Working on your request** …\n'
      } else if (previousRoundHadRepoMutation) {
        line = pfx + ' **Checking the result** …\n'
      } else {
        line = pfx + ' **Continuing** …\n'
      }
      String stage =
        pipelineStageForToolsLoopChatLine(line, 'start', previousRoundHadRepoMutation, zeroBasedRound)
      def event = [
        text    : line,
        metadata: [status: 'tool-progress', tool: toolName, phase: 'start', pipelineStage: stage]
      ]
      synchronized (o) {
        o.write(("data: ${JsonOutput.toJson(event)}\n\n").getBytes(StandardCharsets.UTF_8))
        o.flush()
      }
    } catch (Throwable ignored) {
    }
  }

  /** Mutable holder for plan + tool pipeline wall clock (thread-safe for Reactor + tool threads). */
  private static Map createToolTimingContext() {
    [
      pipelineStartMs   : new AtomicLong(0L),
      taskCompletionMs: new AtomicLong(-1L)
    ]
  }

  private static void markPipelineWallStart(Map timingCtx) {
    if (timingCtx == null) return
    Object ps = timingCtx.pipelineStartMs
    if (ps instanceof AtomicLong) {
      ((AtomicLong) ps).compareAndSet(0L, System.currentTimeMillis())
    }
  }

  /** First successful repository write in the tools loop (not duplicate-write skip). */
  private static void markTaskCompletionWallMsIfUnset(Map timingCtx) {
    if (timingCtx == null) return
    Object tc = timingCtx.taskCompletionMs
    if (tc instanceof AtomicLong) {
      ((AtomicLong) tc).compareAndSet(-1L, System.currentTimeMillis())
    }
  }

  private static double pipelineMsToSec(long ms) {
    if (ms < 0L) {
      return 0.0d
    }
    return Math.round((ms / 100.0d)) / 10.0d
  }

  /**
   * Adds {@code toolPipelineWallMs} and second-based {@code toolPipelineTaskCompletionSec},
   * {@code toolPipelineVerificationSec}, {@code toolPipelineTotalSec} for UI + maintainer logs.
   */
  private static void mergeToolPipelineWallMsIntoMetadata(Map metadata, Map timingCtx) {
    if (metadata == null || timingCtx == null) return
    Object ps = timingCtx.pipelineStartMs
    if (!(ps instanceof AtomicLong)) return
    long start = ((AtomicLong) ps).get()
    if (start <= 0L) return
    long end = System.currentTimeMillis()
    long totalMs = end - start
    if (totalMs < 0L) {
      totalMs = 0L
    }
    long taskEndMs = -1L
    Object tc = timingCtx.taskCompletionMs
    if (tc instanceof AtomicLong) {
      taskEndMs = ((AtomicLong) tc).get()
    }
    long taskMs = totalMs
    long verifyMs = 0L
    if (taskEndMs > start) {
      taskMs = taskEndMs - start
      verifyMs = end - taskEndMs
      if (verifyMs < 0L) {
        verifyMs = 0L
      }
    }
    metadata.toolPipelineWallMs = totalMs
    metadata.toolPipelineTaskCompletionSec = pipelineMsToSec(taskMs)
    metadata.toolPipelineVerificationSec = pipelineMsToSec(verifyMs)
    metadata.toolPipelineTotalSec = pipelineMsToSec(totalMs)
    log.info(
      'AI Assistant pipeline timing: taskCompletionSec={} verificationSec={} totalSec={} (wallMs={})',
      metadata.toolPipelineTaskCompletionSec,
      metadata.toolPipelineVerificationSec,
      metadata.toolPipelineTotalSec,
      totalMs
    )
  }

  /**
   * SSE row for intent-router outcome. When a recipe matched, {@code text} carries a short emoji + title line for the chat UI.
   */
  private void emitIntentRecipeRoutingTelemetrySse(OutputStream o, Map telemetry) {
    if (o == null || telemetry == null || telemetry.isEmpty()) {
      return
    }
    try {
      String outcome = telemetry.outcome?.toString() ?: ''
      String rid = telemetry.recipeId?.toString()?.trim() ?: ''
      String chatLine = ''
      if ('matched'.equals(outcome) && rid) {
        chatLine = telemetry.recipeChatLine?.toString()?.trim() ?: ''
        if (!chatLine) {
          String title = telemetry.recipeTitle?.toString()?.trim() ?: rid
          chatLine = AuthoringIntentRecipeCatalog.formatIntentRecipeChatLine([id: rid, title: title])
        }
      }
      synchronized (o) {
        def ev = [
          text    : chatLine,
          metadata: [
            status               : 'intent-recipe-routing',
            intentRecipeRouting: telemetry
          ]
        ]
        o.write(("data: ${JsonOutput.toJson(ev)}\n\n").getBytes(StandardCharsets.UTF_8))
        o.flush()
      }
    } catch (Throwable ignored) {
      /* best-effort — never break chat stream */
    }
  }

  /**
   * Emits a chat SSE chunk so the UI shows tool progress while OpenAI runs tools (Reactor thread).
   * Each line starts with {@code 🛠️} plus a category emoji ({@code 🔍} read, {@code ✏️} write/revert, {@code 📈} analysis, {@code 🔄} other); expert guidance tools use {@code 🛠️🤓} before the category emoji. Phases add ✅ / ❌ / ⚠️ where applicable.
   * Non-terminal {@code progress} phase: {@code input.progressMessage} is appended after the prefix (e.g. batch translate dispatch list).
   * @param taskDurationMs wall time for this tool invocation (terminal phases only); rendered as a subtle suffix.
   */
  private static void writeToolProgressSse(
    OutputStream o,
    String toolName,
    String phase,
    Map input,
    Throwable err,
    Object toolResult = null,
    Long taskDurationMs = null
  ) {
    if (o == null) return
    try {
      def pfx = toolProgressLinePrefix(toolName)
      def pathFull = (input?.path ?: input?.contentPath ?: input?.templatePath ?: input?.contentType ?: input?.url ?: input?.previewUrl ?: '')?.toString()?.trim() ?: ''
      def path = pathFull
      if (path.length() > 96) {
        path = path.substring(0, 93) + '…'
      }
      String line
      if ('start'.equals(phase)) {
        line = pfx + ' **' + toolName + '**' + (path ? ' (`' + path + '`)' : '') + ' …\n'
      } else if ('progress'.equals(phase)) {
        def body = (input?.progressMessage ?: input?.toolProgressMessage ?: '')?.toString()?.trim() ?: ''
        if (body.length() > 12000) {
          body = body.substring(0, 11800) + '\n… _(truncated)_\n'
        }
        line = body ? (pfx + body + (body.endsWith('\n') ? '' : '\n')) : (pfx + ' **' + toolName + '** …\n')
      } else if ('error'.equals(phase)) {
        def em = (err?.message ?: err?.toString() ?: 'error').toString()
        if (em.length() > 220) {
          em = em.substring(0, 217) + '…'
        }
        line = pfx + ' ❌ **' + toolName + '** failed: ' + em + '\n'
      } else if ('warn'.equals(phase)) {
        def hint = ''
        if (toolResult instanceof Map) {
          def m = (Map) toolResult
          hint = (m.message ?: m.hint ?: m.skippedReason ?: '')?.toString()?.trim() ?: ''
        }
        if (hint.length() > 140) {
          hint = hint.substring(0, 137) + '…'
        }
        if ('TranslateContentItem'.equalsIgnoreCase(toolName ?: '') && path) {
          line =
            pfx +
            ' ⚠️ **TranslateContentItem** (`' +
            path +
            '`) — translation **returned** with warnings' +
            (hint ? ': ' + hint : '.') +
            '\n'
        } else {
          line = pfx + ' ⚠️ **' + toolName + '**' + (path ? ' (`' + path + '`)' : '') + (hint ? ': ' + hint : ' — warning or partial result.') + '\n'
        }
      } else if ('done'.equals(phase)) {
        if ('TranslateContentItem'.equalsIgnoreCase(toolName ?: '') && path) {
          line =
            pfx +
            ' ✅ **TranslateContentItem** (`' +
            path +
            '`) — translation **returned** and saved to the repository.\n'
        } else if ('TranslateContentBatch'.equalsIgnoreCase(toolName ?: '') && toolResult instanceof Map) {
          def tr = (Map) toolResult
          def msg = (tr.message ?: '')?.toString()?.trim()
          line = pfx + ' ✅ **TranslateContentBatch**' + (msg ? ' — ' + msg : ' — finished.') + '\n'
        } else {
          line = pfx + ' ✅ **' + toolName + '**' + (path ? ' (`' + path + '`)' : '') + ' finished.\n'
        }
      } else {
        line = pfx + ' ✅ **' + toolName + '** finished.\n'
      }
      boolean terminal = 'done'.equals(phase) || 'warn'.equals(phase) || 'error'.equals(phase)
      if (terminal) {
        if (line.endsWith('\n')) {
          line = line.substring(0, line.length() - 1)
        }
        line = line + formatCqDurationSuffix(taskDurationMs) + '\n'
      }
      def event = [
        text    : line,
        metadata: [
          status        : 'tool-progress',
          tool          : toolName,
          phase         : phase,
          pipelineStage : pipelineStageForRepoTool(toolName)
        ]
      ]
      if (
        'GenerateImage'.equalsIgnoreCase(toolName ?: '') &&
        ('done'.equals(phase) || 'warn'.equals(phase)) &&
        toolResult instanceof Map
      ) {
        try {
          Map gm = ChatCompletionsToolWire.unwrapGenerateImageToolResultMap((Map) toolResult)
          String tid = ChatCompletionsToolWire.generateImageBacklogToolCallId(gm)?.trim()
          String url = ChatCompletionsToolWire.generateImageResultUrlString(gm)?.trim()
          boolean okHttp = url && (url.startsWith('http://') || url.startsWith('https://'))
          boolean okSmallData =
            url &&
              url.startsWith('data:image') &&
              url.length() <= GENERATE_IMAGE_TOOL_PROGRESS_METADATA_MAX_URL_CHARS
          if (tid && url && (okHttp || okSmallData)) {
            event.metadata.studioAiInlineImageUrls = [(tid): url]
          }
        } catch (Throwable ignoredGenImgMeta) {
        }
      }
      if ('done'.equals(phase)) {
        def tn = toolName?.toString() ?: ''
        if (tn.equalsIgnoreCase('WriteContent')) {
          def repoPath = ''
          if (toolResult instanceof Map) {
            def tr = (Map) toolResult
            repoPath = (tr.path ?: tr.contentPath ?: '')?.toString()?.trim() ?: ''
          }
          if (!repoPath) {
            repoPath = pathFull
          }
          if (repoPath) {
            event.metadata.repoPath = repoPath
          }
        } else if (
          (tn.equalsIgnoreCase('ListContentDependencyScope') || tn.equalsIgnoreCase('ListContentTranslationScope')) &&
          toolResult instanceof Map
        ) {
          def tr = (Map) toolResult
          def rp = tr.root?.toString()?.trim()
          if (rp) {
            event.metadata.repoPath = rp
          }
        } else if (
          (tn.equalsIgnoreCase('TranslateContentItem') ||
            tn.equalsIgnoreCase('TranslateContentBatch') ||
            tn.equalsIgnoreCase('TransformContentSubgraph')) &&
          toolResult instanceof Map
        ) {
          def tr = (Map) toolResult
          def rp = tr.root?.toString()?.trim()
          if (!rp && tr.paths instanceof List && !((List) tr.paths).isEmpty()) {
            rp = ((List) tr.paths).get(0)?.toString()?.trim()
          }
          if (rp) {
            event.metadata.repoPath = rp
          }
        }
      }
      synchronized (o) {
        o.write(("data: ${JsonOutput.toJson(event)}\n\n").getBytes(StandardCharsets.UTF_8))
        o.flush()
      }
    } catch (Throwable ignored) {
      // never break tool execution
    }
  }

  /**
   * When SSE has already started, errors must be sent as an SSE frame — not by switching to JSON
   * (avoids {@code AsyncRequestNotUsableException} on committed async responses).
   */
  /**
   * OpenAI streaming often ends with an assistant delta that has {@code finishReason=stop} (or similar) but
   * <strong>empty</strong> {@code getText()} and no {@code completed} flag in message metadata. If we drop that
   * chunk, the Studio UI never receives {@code metadata.completed=true} and appears to hang until timeout.
   */
  private static String extractChatCompletionsFinishReason(def gen, def message, Map messageMeta, ChatResponse chatResponse) {
    try {
      def crm = chatResponse?.getMetadata()
      def chatMap = crm instanceof Map ? (crm as Map) : [:]
      def genMap = [:]
      try {
        if (gen?.metaClass?.respondsTo(gen, 'getMetadata')) {
          def gm = gen.getMetadata()
          if (gm instanceof Map) {
            genMap = gm as Map
          } else if (gm != null && gm.metaClass?.respondsTo(gm, 'getFinishReason')) {
            def frObj = gm.getFinishReason()
            if (frObj != null && frObj.toString().trim()) return frObj.toString().trim()
          }
        }
      } catch (Throwable ignored) {}
      def msgMap = (messageMeta instanceof Map) ? (messageMeta as Map) : [:]
      def keys = ['finishReason', 'finish_reason', 'reason']
      for (def map : [msgMap, genMap, chatMap]) {
        if (map == null) continue
        for (String k : keys) {
          def v = map[k]
          if (v != null && v.toString().trim()) return v.toString().trim()
        }
      }
    } catch (Throwable ignored) {}
    return ''
  }

  /** Terminal finish reasons from Tools-loop chat streaming (and some reasoning variants). */
  private static boolean finishReasonImpliesStreamDone(String finishReason) {
    if (!finishReason) return false
    def fr = finishReason.trim().toLowerCase()
    return fr == 'stop' ||
      fr == 'length' ||
      fr == 'content_filter' ||
      fr == 'tool_calls' ||
      fr == 'end_turn' ||
      fr.endsWith('_turn')
  }

  /**
   * Browser closed the tab, aborted fetch, or proxy dropped the SSE connection — not an LLM or upstream logic failure by itself.
   */
  private static boolean isSseClientDisconnected(Throwable t) {
    if (t == null) {
      return false
    }
    if (t instanceof ExecutionException && t.getCause() != null) {
      return isSseClientDisconnected(t.getCause())
    }
    Throwable cur = t
    int guard = 0
    while (cur != null && guard++ < 28) {
      String cn = cur.class.name
      String msg = (cur.message ?: '').toString().toLowerCase(Locale.ROOT)
      if (msg.contains('ob is null') || msg.contains('outputbuffer') || msg.contains('output buffer')) {
        return true
      }
      if (cur instanceof IllegalStateException &&
        (msg.contains('getoutputstream') || msg.contains('committed'))) {
        return true
      }
      if (msg.contains('response not usable') || msg.contains('not usable after response')) {
        return true
      }
      if (cn == 'org.springframework.web.context.request.async.AsyncRequestNotUsableException') {
        return true
      }
      if (cn.contains('ClientAbortException')) {
        return true
      }
      if (cn.contains('EofException') && cn.contains('jetty')) {
        return true
      }
      if (cur instanceof IOException) {
        if (msg.contains('broken pipe') || msg.contains('connection reset') || msg.contains('connection aborted')) {
          return true
        }
      }
      cur = cur.cause
    }
    return false
  }

  /**
   * When the author closes the chat stream (Stop / navigates away), Tomcat/Jetty usually breaks the outbound SSE write.
   * The Tools-loop+tools path runs work on a worker thread while the servlet thread waits on {@link Future#get}; probing
   * {@code flush()} between short timeouts detects disconnect so we can cancel tools and stop burning tokens.
   */
  private boolean probeSseClientDisconnected(OutputStream out) {
    if (out == null) {
      return false
    }
    try {
      synchronized (out) {
        out.flush()
      }
      return false
    } catch (Throwable t) {
      if (isSseClientDisconnected(t)) {
        return true
      }
      log.debug('probeSseClientDisconnected: flush raised {}', t.message)
      return false
    }
  }

  /**
   * Author-facing text for terminal SSE errors. {@link RestClientResponseException#getMessage()} is often only
   * {@code RestClientResponseException#getMessage()} is often only the short ctor label (first ctor arg) — authors need HTTP status and the upstream JSON {@code error} body.
   */
  private static String formatSseStreamErrorMessage(Throwable t) {
    if (t == null) {
      return 'Stream error'
    }
    Throwable cur = t
    int walk = 0
    while (cur != null && walk++ < 16) {
      if (cur instanceof IllegalStateException) {
        String m = (cur.message ?: '').toString()
        return m?.trim() ? m : 'Configuration error'
      }
      if (cur instanceof RestClientResponseException) {
        RestClientResponseException r = (RestClientResponseException) cur
        String body = ''
        try {
          body = r.getResponseBodyAsString(StandardCharsets.UTF_8) ?: ''
        } catch (Throwable ignored) {
        }
        int code = r.getStatusCode().value()
        def st = (r.getStatusText() ?: '').toString()
        def elided = AiHttpProxy.elideForLog(body, 1500)
        return "Tools-loop chat HTTP ${code} ${st}: ${elided ?: '(empty body)'}".trim()
      }
      if (cur instanceof WebClientResponseException) {
        WebClientResponseException w = (WebClientResponseException) cur
        String body = ''
        try {
          body = w.getResponseBodyAsString(StandardCharsets.UTF_8) ?: ''
        } catch (Throwable ignored) {
        }
        int code = w.getStatusCode().value()
        def st = (w.getStatusText() ?: '').toString()
        def elided = AiHttpProxy.elideForLog(body, 1500)
        return "Tools-loop chat HTTP ${code} ${st}: ${elided ?: '(empty body)'}".trim()
      }
      cur = cur.cause
    }
    Throwable root = t
    int guard = 0
    while (root?.cause != null && root != root.cause && guard++ < 12) {
      root = root.cause
    }
    return (root?.message ?: t?.message ?: 'Stream error').toString()
  }

  private void writeSseErrorFrame(OutputStream out, Throwable t) {
    if (out == null) return
    if (isSseClientDisconnected(t)) {
      return
    }
    try {
      def msg = formatSseStreamErrorMessage(t)
      if (msg.length() > 1800) {
        msg = msg.substring(0, 1800) + '…'
      }
      def errEvent = [text: '', metadata: [error: true, completed: true, message: msg]]
      synchronized (out) {
        out.write(("data: ${JsonOutput.toJson(errEvent)}\n\n").getBytes(StandardCharsets.UTF_8))
        out.flush()
      }
    } catch (Throwable io) {
      if (isSseClientDisconnected(io)) {
        log.debug('writeSseErrorFrame skipped (response already unusable / client gone): {}', io.message)
      } else {
        log.warn('writeSseErrorFrame failed: {}', io.message)
      }
    }
  }

  /**
   * Exactly-once terminal SSE for the native-tools worker vs servlet recovery paths: compare-and-set so only one
   * thread emits completed/error-with-completed (avoids duplicate terminal events if both race on cancel/timeout).
   */
  private static boolean tryClaimToolsTerminalEmit(AtomicBoolean emittedFlag) {
    return emittedFlag == null || emittedFlag.compareAndSet(false, true)
  }

  private void ensureSseTerminalCompletedIfNeeded(
    OutputStream out,
    Map toolTimingCtx,
    AtomicBoolean emittedFlag,
    String reasonForLog
  ) {
    if (out == null) {
      return
    }
    if (!tryClaimToolsTerminalEmit(emittedFlag)) {
      return
    }
    try {
      log.warn('AI Assistant SSE: forcing terminal completed frame (UI would hang otherwise) — {}', reasonForLog)
      synchronized (out) {
        def doneMeta = new LinkedHashMap()
        doneMeta.completed = true
        mergeToolPipelineWallMsIntoMetadata(doneMeta, toolTimingCtx)
        out.write(("data: ${JsonOutput.toJson([text: '', metadata: doneMeta])}\n\n").getBytes(StandardCharsets.UTF_8))
        out.flush()
      }
    } catch (Throwable t) {
      if (!isSseClientDisconnected(t)) {
        log.warn('ensureSseTerminalCompletedIfNeeded failed: {}', t.message)
      }
    }
  }

  Object chatStreamWithSpringAi(
    String agentId,
    String prompt,
    String chatId = null,
    String llm = null,
    String chatModel = null,
    String llmApiKey = null,
    String imageModel = null,
    boolean formEngineClientForward = false,
    String formEngineItemPathRaw = null,
    boolean enableTools = true,
    String imageGenerator = null
  ) {
    OutputStream out = null
    try {
      ensureVerboseSpringAiHttpLogging()
      response?.setContentType('text/event-stream')
      response?.setHeader('Cache-Control', 'no-cache')
      response?.setHeader('X-Accel-Buffering', 'no')

      out = response?.getOutputStream()
      out.write(': connected\n\n'.getBytes(StandardCharsets.UTF_8))
      out.flush()
      // New prompt / stream: ensure no stale native-tools cancel binding leaked onto this servlet thread.
      aiAssistantPipelineCancelBindingClear()

      def genImgBacklogByToolCallId = new ConcurrentHashMap<String, String>()
      def toolTimingCtx = createToolTimingContext()
      def toolProgressListener = { String tn, String ph, Map inp, Throwable er = null, Object tres = null, Long taskDurMs = null ->
        if ('GenerateImage'.equals(tn) && tres instanceof Map && ('done'.equals(ph) || 'warn'.equals(ph))) {
          try {
            Map gm = ChatCompletionsToolWire.unwrapGenerateImageToolResultMap((Map) tres)
            String url = ChatCompletionsToolWire.generateImageResultUrlString(gm)?.trim()
            if (url) {
              boolean okData = url.startsWith('data:image')
              boolean okHttp = url.startsWith('https://') || url.startsWith('http://')
              if (okData || okHttp) {
                String tid = ChatCompletionsToolWire.generateImageBacklogToolCallId(gm)
                if (tid) {
                  genImgBacklogByToolCallId.put(tid, url)
                } else {
                  log.warn(
                    'GenerateImage tool-progress: image URL present but tool_call_id missing — cannot map for SSE expansion.'
                  )
                }
              } else {
                log.warn(
                  'GenerateImage tool-progress: skip backlog — url is not data:image or http(s) (chars={})',
                  url.length()
                )
              }
            }
          } catch (Throwable genImgEx) {
            log.warn('GenerateImage tool-progress: backlog capture failed: {}', genImgEx.message)
          }
        }
        writeToolProgressSse(out, tn, ph, inp ?: [:], er, tres, taskDurMs)
      }

      def fullSuppress = false
      def protNorm = null
      if (formEngineClientForward) {
        def n = AuthoringPreviewContext.normalizeRepoPath(formEngineItemPathRaw)
        if (n) {
          protNorm = n
        } else {
          fullSuppress = true
        }
      }
      def springAi = buildSpringAiChatClient(agentId, chatId, llm, chatModel, llmApiKey, toolProgressListener, imageModel, fullSuppress, protNorm, enableTools, imageGenerator)
      if (formEngineClientForward && !StudioAiLlmKind.useToolsLoopChatRestClient(springAi.llm, springAi)) {
        log.warn(
          'Form-engine client-apply: llm is {} (not a tools-loop RestClient row). Use openAI / xAI / deepSeek / llama / genesis (gemini) on this agent for native RestClient tools + best compliance with aiassistantFormFieldUpdates.',
          springAi.llm
        )
      }
      def bodyPrompt = formEngineClientForward ? prependFormEngineClientApplyEnforcement(prompt) : (prompt ?: '').toString()
      def userText = springAi.useTools ? addToolRequiredGuard(bodyPrompt, fullSuppress, protNorm) : bodyPrompt
      if (!formEngineClientForward && StudioAiLlmKind.useToolsLoopChatRestClient(springAi.llm, springAi)) {
        def route = intentRecipeRoutingPrelude(
          bodyPrompt,
          userText,
          StudioAiLlmKind.toolsLoopChatApiKeyFromBundle(springAi),
          (springAi.resolvedChatModel ?: resolveChatModel(chatModel)),
          StudioAiLlmKind.toolsLoopChatBaseUrlFromBundle(springAi),
          springAi,
          springAi.studioOps,
          effectiveAuthoringIntentExpansionRematchEnabled(bodyPrompt)
        )
        if (route?.intentRecipeRoutingTelemetry instanceof Map) {
          springAi.intentRecipeRoutingTelemetry = route.intentRecipeRoutingTelemetry
          emitIntentRecipeRoutingTelemetrySse(out, (Map) route.intentRecipeRoutingTelemetry)
        }
        applyIntentRecipeRouteEffects(springAi, route)
        if (route.clarificationOnly) {
          Prompt clarifyPrompt = new Prompt([
            new SystemMessage(ToolPrompts.getLlm_INTENT_CLARIFICATION_ONLY_SYSTEM()),
            new UserMessage(route.clarificationUserText?.toString() ?: '')
          ])
          AtomicBoolean clarTerminal = new AtomicBoolean(false)
          try {
            writeToolsOffViaChatCompletionEntity(
              out,
              StudioAiLlmKind.toolsLoopChatApiKeyFromBundle(springAi),
              (springAi.resolvedChatModel ?: resolveChatModel(chatModel)),
              clarifyPrompt,
              agentId,
              StudioAiLlmKind.toolsLoopChatBaseUrlFromBundle(springAi)
            )
          } finally {
            ensureSseTerminalCompletedIfNeeded(out, toolTimingCtx, clarTerminal, 'intent recipe clarification-only')
          }
          return null
        }
        userText = route.userTextForToolsLoop?.toString() ?: (springAi.useTools ? userText : bodyPrompt)
      }
      def toolRequiredIntent = springAi.useTools && isToolRequiredIntent(bodyPrompt)
      log.debug("chatStreamWithSpringAi start: llm={} agentId={} promptLen={} toolRequiredIntent={} chatIdPresent={} useTools={} enableTools={} formEngineClientForward={} fullSuppressWrites={} protectedFormItemPath={}",
        springAi.llm, agentId, (bodyPrompt ?: '').length(), toolRequiredIntent, (chatId != null && chatId.toString().trim().length() > 0), springAi.useTools, enableTools, formEngineClientForward, fullSuppress, protNorm ?: '')

      // OpenAI + tools off: RestClient + upstream SSE (stream=true), not OpenAiChatModel (merge can break stream).
      // OpenAI + tools on: avoid OpenAiChatModel / OpenAiApi.chatCompletionEntity (truncated JSON on some Studio stacks);
      // use RestClient + stream:false + JsonSlurper tool loop on a worker thread with the same await budget.
      Prompt authoringChatPrompt = null
      def promptSpec
      if (StudioAiLlmKind.useToolsLoopChatRestClient(springAi.llm, springAi)) {
        authoringChatPrompt = authoringPrompt(
          userText,
          fullSuppress,
          protNorm,
          springAi.useTools,
          springAi.studioOps,
          StudioAiLlmKind.toolsLoopChatApiKeyFromBundle(springAi)
        )
        logChatCompletionsPayloadApprox(
          agentId,
          (springAi.resolvedChatModel ?: resolveChatModel(chatModel)),
          authoringChatPrompt,
          springAi.tools
        )
        if (!springAi.useTools) {
          writeToolsOffViaChatCompletionEntity(
            out,
            StudioAiLlmKind.toolsLoopChatApiKeyFromBundle(springAi),
            (springAi.resolvedChatModel ?: resolveChatModel(chatModel)),
            authoringChatPrompt,
            agentId,
            StudioAiLlmKind.toolsLoopChatBaseUrlFromBundle(springAi)
          )
          return null
        }
        promptSpec = springAi.chatClient.prompt(authoringChatPrompt).tools(*springAi.tools)
      } else {
        promptSpec = springAi.useTools
          ? springAi.chatClient.prompt().user(userText).tools(*springAi.tools)
          : springAi.chatClient.prompt().user(userText)
      }
      def toolsLoopBlockingForStudioStream = (StudioAiLlmKind.useToolsLoopChatRestClient(springAi.llm, springAi) && springAi.useTools)

      // OpenAI + native tools: RestClient loop streams **## Plan** (or fallback) before repo tool rows. Sending the
      // workflow hint first makes the client treat 🛠️ as the first chunk and clears main text — authors see tools
      // with no plan above them. Flux/Spring-AI tool paths still get the hint (long gaps before first delta).
      if (toolRequiredIntent && !toolsLoopBlockingForStudioStream) {
        synchronized (out) {
          out.write(
            (
              'data: ' +
                JsonOutput.toJson([
                  text    : '🛠️🔄 **Working on your request** — short pauses between steps are normal. You’ll see progress lines below as each part finishes.\n',
                  metadata: [status: 'tool-workflow-hint']
                ]) +
                '\n\n'
            ).getBytes(StandardCharsets.UTF_8)
          )
          out.flush()
        }
      }

      def modelForLog = (springAi.resolvedChatModel ?: resolveChatModel(chatModel))

      def flux = null
      try {
        // Tool workflows: chatResponse() flux — skipped for Tools-loop+tools (hung upstream SSE on some Studio JVMs).
        if (springAi.useTools && !toolsLoopBlockingForStudioStream) {
          def streamSpec = promptSpec.stream()
          if (streamSpec?.metaClass?.respondsTo(streamSpec, 'chatResponse')) {
            flux = streamSpec.chatResponse()
          }
        }
      } catch (Throwable t) {
        log.warn('chatStreamWithSpringAi: chatResponse flux setup failed: {}', t.message)
      }

      if (flux != null && springAi.useTools && !toolsLoopBlockingForStudioStream) {
        markPipelineWallStart(toolTimingCtx)
        log.debug(
          'chatStreamWithSpringAi stream path: using chatResponse flux (await max {} ms). llm={} model={}',
          CHAT_FLUX_AWAIT_MS,
          springAi.llm,
          modelForLog
        )
        def latch = new CountDownLatch(1)
        def errorRef = new AtomicReference<Throwable>(null)
        def sentCompletedAtomic = new AtomicBoolean(false)
        def sawFirstClientChunk = new AtomicBoolean(false)
        def loggedEmptyAssistantTextDelta = new AtomicBoolean(false)
        /** Merge stream deltas so {@code studio-ai-inline-image://…} is never split mid-token across SSE chunks. */
        StringBuilder fluxAssistRawAcc = new StringBuilder()
        AtomicInteger fluxAssistExpandedSentLen = new AtomicInteger(0)

        def fluxTimed = (flux instanceof Flux) ? ((Flux) flux).timeout(Duration.ofMillis(CHAT_FLUX_AWAIT_MS)) : flux

        log.debug('chatStreamWithSpringAi: subscribing to chatResponse flux (agentId={}, model={})', agentId, modelForLog)
        def fluxDisposable = fluxTimed.subscribe(
          { ChatResponse chatResponse ->
            def gen = chatResponse?.getResult() ?: (chatResponse?.getResults() != null && !chatResponse.getResults().isEmpty() ? chatResponse.getResults().get(0) : null)
            def message = gen?.getOutput()
            def content = message != null ? (message.getText() ?: '') : ''
            def rawMeta = message?.getMetadata()
            def meta = (rawMeta instanceof Map) ? (rawMeta as Map) : [:]
            if (meta == null) meta = [:]
            def completed = (meta?.completed != null) ? meta.completed.asBoolean() : false
            def finishReason = extractChatCompletionsFinishReason(gen, message, meta as Map, chatResponse)
            def streamFinished = completed || finishReasonImpliesStreamDone(finishReason)

            if ((content == null || content.toString().isEmpty()) && !streamFinished) {
              def toolCallNames = []
              try {
                if (message?.metaClass?.respondsTo(message, 'getToolCalls')) {
                  def tcs = message.getToolCalls()
                  if (tcs != null && !tcs.isEmpty()) {
                    tcs.each { tc ->
                      def nm = null
                      try {
                        if (tc?.metaClass?.respondsTo(tc, 'name')) nm = tc.name()
                      } catch (Throwable ignored) {}
                      if (!nm) {
                        try {
                          if (tc?.metaClass?.respondsTo(tc, 'getName')) nm = tc.getName()
                        } catch (Throwable ignored2) {}
                      }
                      toolCallNames << (nm?.toString()?.trim() ?: '?')
                    }
                  }
                }
              } catch (Throwable ignored) {}
              if (toolCallNames && !toolCallNames.isEmpty()) {
                log.debug(
                  'chatStreamWithSpringAi: stream delta empty assistant text but toolCalls={} (agentId={}, model={})',
                  toolCallNames,
                  agentId,
                  modelForLog
                )
              } else if (loggedEmptyAssistantTextDelta.compareAndSet(false, true)) {
                log.debug(
                  'chatStreamWithSpringAi: stream delta with empty assistant text (agentId={}, model={}); some adapters only emit chunks when there is assistant text or completed=true. Some chat models may stream tool/reasoning segments without text first — the browser stays blank until the first text chunk (this is not proof the HTTP request body was invalid).',
                  agentId,
                  modelForLog
                )
              }
              return
            }

            if (sawFirstClientChunk.compareAndSet(false, true)) {
              log.debug('chatStreamWithSpringAi: first SSE chunk from chatResponse flux (agentId={}, model={})', agentId, modelForLog)
            }
            def metaOut = new LinkedHashMap((meta ?: [:]) as Map)
            if (streamFinished) {
              metaOut.completed = true
              mergeToolPipelineWallMsIntoMetadata(metaOut, toolTimingCtx)
            }
            if (finishReason && log.isDebugEnabled()) {
              log.debug('chatStreamWithSpringAi: chunk finishReason={} streamFinished={} (agentId={}, model={})', finishReason, streamFinished, agentId, modelForLog)
            }
            String curRaw = (content != null ? content.toString() : '')
            if (curRaw) {
              String prevRaw = fluxAssistRawAcc.toString()
              if (prevRaw.isEmpty() || curRaw.startsWith(prevRaw)) {
                fluxAssistRawAcc.setLength(0)
                fluxAssistRawAcc.append(curRaw)
              } else {
                fluxAssistRawAcc.append(curRaw)
              }
            }
            String fullRaw = fluxAssistRawAcc.toString()
            if (streamFinished) {
              fullRaw = stripForbiddenMetaPlanFromAssistantText(fullRaw)
              fluxAssistRawAcc.setLength(0)
              fluxAssistRawAcc.append(fullRaw)
            } else {
              fullRaw = stripForbiddenMetaPlanFromAssistantText(fullRaw)
            }
            Map<String, String> fluxMerged =
              genImgBacklogByToolCallId != null && !genImgBacklogByToolCallId.isEmpty()
                ? new LinkedHashMap<String, String>(genImgBacklogByToolCallId)
                : [:]
            String sanitizedFlux =
              sanitizeAssistantMarkdownReplaceGenerateImageDataUrlsWithRefs(fullRaw, fluxMerged)
            String rawForImgExpand = sanitizedFlux
            if (streamFinished && genImgBacklogByToolCallId != null && !genImgBacklogByToolCallId.isEmpty()) {
              rawForImgExpand =
                ChatCompletionsToolWire.appendMissingInlineImageRefs(sanitizedFlux, null, genImgBacklogByToolCallId)
              rawForImgExpand =
                ChatCompletionsToolWire.expandInlineImageRefs(rawForImgExpand, null, genImgBacklogByToolCallId)
            }
            String expandedFull = rawForImgExpand
            int sent = fluxAssistExpandedSentLen.get()
            int expLen = expandedFull.length()
            String fluxChunkText = sent < expLen ? expandedFull.substring(sent) : ''
            fluxAssistExpandedSentLen.set(expLen)
            def event = [text: fluxChunkText, metadata: metaOut]
            if (streamFinished) sentCompletedAtomic.set(true)
            def payload = "data: ${JsonOutput.toJson(event)}\n\n"
            synchronized (out) {
              out.write(payload.getBytes(StandardCharsets.UTF_8))
              out.flush()
            }
          },
          { Throwable err ->
            log.warn('chatStreamWithSpringAi: chatResponse flux onError: {} — agentId={} model={}', err?.message, agentId, modelForLog)
            try {
              def body = extractChatCompletionsHttpErrorBody(err)
              if (body?.trim()) {
                log.error('Tools-loop chat error response body: {}', AiHttpProxy.elideForLog(body, 4000))
              }
            } catch (Throwable ignored) {}
            errorRef.set(err)
            latch.countDown()
          },
          {
            log.debug('chatStreamWithSpringAi: chatResponse flux onComplete (agentId={}, model={}, terminalChunkAlreadySent={})', agentId, modelForLog, sentCompletedAtomic.get())
            synchronized (out) {
              if (!sentCompletedAtomic.get()) {
                def doneFluxMeta = new LinkedHashMap()
                doneFluxMeta.completed = true
                mergeToolPipelineWallMsIntoMetadata(doneFluxMeta, toolTimingCtx)
                def event = [text: '', metadata: doneFluxMeta]
                def payload = "data: ${JsonOutput.toJson(event)}\n\n"
                out.write(payload.getBytes(StandardCharsets.UTF_8))
                out.flush()
              }
            }
            latch.countDown()
          }
        )

        // Poll with short awaits so UI Stop / SSE disconnect disposes the flux promptly instead of
        // blocking for CHAT_FLUX_AWAIT_MS on latch.await alone.
        long fluxDeadline = System.currentTimeMillis() + CHAT_FLUX_AWAIT_MS
        boolean fluxFinishedInTime = false
        while (true) {
          long remaining = fluxDeadline - System.currentTimeMillis()
          if (remaining <= 0L) {
            break
          }
          long slice = Math.min(250L, remaining)
          if (latch.await(slice, TimeUnit.MILLISECONDS)) {
            fluxFinishedInTime = true
            break
          }
          if (probeSseClientDisconnected(out)) {
            log.warn(
              'AI Assistant chat stream: CLIENT_ABORT — author stopped chat or browser closed SSE; disposing chatResponse flux subscription. agentId={} model={}',
              agentId,
              modelForLog
            )
            try {
              fluxDisposable?.dispose()
            } catch (Throwable ignored) {
            }
            return null
          }
        }
        if (!fluxFinishedInTime) {
          log.warn(
            'chatStreamWithSpringAi: chatResponse flux did not complete within {} ms (agentId={}, model={}).',
            CHAT_FLUX_AWAIT_MS,
            agentId,
            modelForLog
          )
          log.debug(
            'AI Assistant: cancelling Reactor subscription to tools-loop POST /v1/chat/completions (agentId={}, model={}); this closes the outbound HTTP connection so the chat host sees a client disconnect for this request.',
            agentId,
            modelForLog
          )
          try {
            fluxDisposable?.dispose()
          } catch (Throwable ignored) {}
          def errAfterCancel = errorRef.get()
          if (errAfterCancel != null) {
            log.error('chatStreamWithSpringAi: flux error after cancel', errAfterCancel)
            writeSseErrorFrame(out, errAfterCancel)
          } else {
            def msg = """Chat stream did not finish within ${(CHAT_FLUX_AWAIT_MS / 1000) as int} seconds (server-side limit); the Studio plugin cancelled the upstream HTTP request to your configured chat host.

If this is unexpected: verify outbound HTTPS from Studio to that host, API key and account status, and the model id (${modelForLog}).
Check Studio logs for Spring AI / WebClient / reactor.netty lines emitted for this request."""
            writeSseErrorFrame(out, new TimeoutException(msg))
          }
          return null
        }

        def fluxErr = errorRef.get()
        if (fluxErr != null) {
          log.error('chatStreamWithSpringAi: chatResponse flux failed', fluxErr)
          writeSseErrorFrame(out, fluxErr)
          return null
        }
      } else {
        log.debug(
          'chatStreamWithSpringAi: no chatResponse flux (useTools={}, toolsLoopBlockingTools={}, llm={}) — using promptSpec.call() fallback',
          springAi.useTools,
          toolsLoopBlockingForStudioStream,
          springAi.llm
        )
        if (toolsLoopBlockingForStudioStream) {
          log.debug(
            'chatStreamWithSpringAi: Tools-loop+tools RestClient loop with {} ms cap (agentId={}, model={})',
            CHAT_FLUX_AWAIT_MS,
            agentId,
            modelForLog
          )
          if (authoringChatPrompt == null) {
            throw new IllegalStateException('Tools-loop tools stream: prompt missing')
          }
          ExecutorService pool = Executors.newSingleThreadExecutor()
          AtomicBoolean cancelRequested = new AtomicBoolean(false)
          AtomicBoolean toolsLoopTerminalEmitted = new AtomicBoolean(false)
          String toolDiagSessionId = 'td-' + java.util.UUID.randomUUID().toString()
          try {
            def fut = pool.submit({
              aiAssistantToolWorkerDiagSessionBind(toolDiagSessionId)
              try {
                writeToolsOnViaRestClientToolLoop(
                  out,
                  StudioAiLlmKind.toolsLoopChatApiKeyFromBundle(springAi),
                  (springAi.resolvedChatModel ?: resolveChatModel(chatModel)),
                  authoringChatPrompt,
                  springAi.tools,
                  agentId,
                  toolTimingCtx,
                  cancelRequested,
                  toolsLoopTerminalEmitted,
                  StudioAiLlmKind.toolsLoopChatBaseUrlFromBundle(springAi),
                  springAi,
                  genImgBacklogByToolCallId
                )
                null
              } finally {
                aiAssistantToolWorkerDiagSessionEnd()
              }
            } as Callable)
            long deadline = System.currentTimeMillis() + CHAT_FLUX_AWAIT_MS
            boolean stoppedByClient = false
            long pipelineWaitStartMs = System.currentTimeMillis()
            long lastSseHeartbeatMs = pipelineWaitStartMs
            try {
              while (!fut.isDone()) {
                long waitMs = Math.min(250L, deadline - System.currentTimeMillis())
                if (waitMs <= 0L) {
                  cancelRequested.set(true)
                  try {
                    fut.cancel(true)
                  } catch (Throwable ignored) {
                  }
                  log.warn(
                    'AI Assistant chat stream: server-side timeout — cancelling Tools-loop tool worker ({}s cap). agentId={} model={}',
                    (CHAT_FLUX_AWAIT_MS / 1000) as int,
                    agentId,
                    modelForLog
                  )
                  def msg = """Tools-loop chat did not finish within ${(CHAT_FLUX_AWAIT_MS / 1000) as int} seconds (server-side limit); the request was cancelled.

If this is unexpected: verify outbound HTTPS from Studio to your configured chat host, API key and account status, and the model id (${modelForLog})."""
                  if (tryClaimToolsTerminalEmit(toolsLoopTerminalEmitted)) {
                    writeSseErrorFrame(out, new TimeoutException(msg))
                  }
                  pool.shutdownNow()
                  return null
                }
                try {
                  fut.get(waitMs, TimeUnit.MILLISECONDS)
                } catch (TimeoutException te) {
                  if (probeSseClientDisconnected(out)) {
                    cancelRequested.set(true)
                    try {
                      fut.cancel(true)
                    } catch (Throwable ignored) {
                    }
                    stoppedByClient = true
                    log.warn(
                      'AI Assistant chat stream: CLIENT_ABORT — author stopped chat or browser closed SSE; cancelling Tools-loop tool worker (interrupt + executor shutdown). agentId={} model={}',
                      agentId,
                      modelForLog
                    )
                    break
                  }
                  long nowHb = System.currentTimeMillis()
                  if (nowHb - lastSseHeartbeatMs >= TOOLS_LOOP_SSE_WAIT_HEARTBEAT_MS) {
                    lastSseHeartbeatMs = nowHb
                    long elapsedSec = (nowHb - pipelineWaitStartMs) / 1000L
                    def workerPhase = aiAssistantToolWorkerDiagPhaseGet(toolDiagSessionId)
                    log.debug(
                      'Studio AI Assistant SSE heartbeat: waiting on Tools-loop+tools worker elapsedSec={} agentId={} model={} workerPhase={}',
                      elapsedSec,
                      agentId,
                      modelForLog,
                      workerPhase ? workerPhase : '(worker phase unset — e.g. not in native tool loop yet)'
                    )
                    emitSsePipelineHeartbeat(
                      out,
                      elapsedSec,
                      TOOLS_LOOP_SSE_WAIT_HEARTBEAT_MS / 1000L,
                      pipelineWaitHintMarkdown(workerPhase)
                    )
                  }
                }
              }
              if (stoppedByClient) {
                pool.shutdownNow()
                try {
                  fut.get(5L, TimeUnit.SECONDS)
                } catch (Throwable ignored) {
                }
                ensureSseTerminalCompletedIfNeeded(out, toolTimingCtx, toolsLoopTerminalEmitted, 'SSE client gone or Stop — worker cancelled')
                log.warn(
                  'AI Assistant chat stream: CLIENT_ABORT — executor shutdownNow() applied after client abort; worker thread interrupted if still running. agentId={}',
                  agentId
                )
                return null
              }
              try {
                fut.get()
              } catch (CancellationException ce) {
                log.warn(
                  'AI Assistant chat stream: Tools-loop tool Future cancelled (timeout or client abort). agentId={} detail={}',
                  agentId,
                  ce.message
                )
                ensureSseTerminalCompletedIfNeeded(out, toolTimingCtx, toolsLoopTerminalEmitted, 'Tools-loop tool Future cancelled')
                return null
              } catch (ExecutionException ee) {
                Throwable c = ee.getCause() != null ? ee.getCause() : ee
                if (c instanceof InterruptedException && AIASSISTANT_PIPELINE_CANCELLED == (c.message ?: '').toString()) {
                  log.warn(
                    'AI Assistant chat stream: Tools-loop tool pipeline exited cooperatively after CLIENT_ABORT cancel flag. agentId={}',
                    agentId
                  )
                  ensureSseTerminalCompletedIfNeeded(out, toolTimingCtx, toolsLoopTerminalEmitted, 'pipeline cancelled cooperatively')
                  return null
                }
                if (isSseClientDisconnected(ee) || isSseClientDisconnected(c)) {
                  log.warn(
                    'AI Assistant chat stream: CLIENT_ABORT during OpenAI tool workflow — {}',
                    c?.message ?: ee.message
                  )
                  ensureSseTerminalCompletedIfNeeded(out, toolTimingCtx, toolsLoopTerminalEmitted, 'client disconnect during tool workflow')
                  return null
                }
                if (c instanceof IllegalStateException) {
                  log.error('chatStreamWithSpringAi: Tools-loop tool worker failed', c)
                  if (tryClaimToolsTerminalEmit(toolsLoopTerminalEmitted)) {
                    writeSseErrorFrame(out, c)
                  }
                  return null
                }
                throw ee
              }
              ensureSseTerminalCompletedIfNeeded(
                out,
                toolTimingCtx,
                toolsLoopTerminalEmitted,
                'worker returned without emitting metadata.completed (recovery)'
              )
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt()
              cancelRequested.set(true)
              try {
                fut.cancel(true)
              } catch (Throwable ignored) {
              }
              log.warn(
                'AI Assistant chat stream: servlet thread interrupted while waiting for Tools-loop tool worker — cancelling. agentId={}',
                agentId
              )
              ensureSseTerminalCompletedIfNeeded(out, toolTimingCtx, toolsLoopTerminalEmitted, 'servlet thread interrupted')
              return null
            }
          } finally {
            try {
              pool.shutdownNow()
            } catch (Throwable ignored) {}
          }
        } else {
          def callResult = promptSpec.call()
          def content = extractContentFromCallResult(callResult)
          def event = [text: (content ?: ''), metadata: [:]]
          synchronized (out) {
            out.write(("data: ${JsonOutput.toJson(event)}\n\n").getBytes(StandardCharsets.UTF_8))
            def doneCallMeta = new LinkedHashMap()
            doneCallMeta.completed = true
            mergeToolPipelineWallMsIntoMetadata(doneCallMeta, toolTimingCtx)
            out.write(("data: ${JsonOutput.toJson([text: '', metadata: doneCallMeta])}\n\n").getBytes(StandardCharsets.UTF_8))
            out.flush()
          }
        }
      }

      return null
    } catch (IllegalStateException ise) {
      if (out != null) {
        try {
          writeSseErrorFrame(out, ise)
        } catch (Throwable ignored) {
        }
        return null
      }
      throw ise
    } catch (Throwable t) {
      if (isSseClientDisconnected(t)) {
        log.warn(
          'AI Assistant chat stream: client aborted connection (UI Stop, fetch AbortError, tab closed, or proxy drop) — server pipeline stopped. detail={}',
          t.message
        )
        return null
      }
      log.error('chatStreamWithSpringAi failed', t)
      // Once getOutputStream() ran for SSE, never fall back to JSON in stream.post — async response may be committed.
      if (out != null) {
        writeSseErrorFrame(out, t)
        return null
      }
      return [message: "Spring AI stream failed: ${t.message}"]
    }
  }

  String getPluginKey() {
    return pluginConfig?.getString('key')
  }

  Object proxyImage(String url) {
    if (!url?.toString()?.trim()) throw new IllegalArgumentException('Missing required parameter: url')
    def httpClient = org.apache.http.impl.client.HttpClients.createDefault()
    def req = new org.apache.http.client.methods.HttpGet(url.toString())
    try (org.apache.http.client.methods.CloseableHttpResponse res = httpClient.execute(req)) {
      def entity = res.getEntity()
      if (entity != null) {
        def inputStream = entity.getContent()
        response.contentType = 'image/png'
        response.outputStream << inputStream
        inputStream.close()
        response.flushBuffer()
      }
      return null
    }
  }
}

