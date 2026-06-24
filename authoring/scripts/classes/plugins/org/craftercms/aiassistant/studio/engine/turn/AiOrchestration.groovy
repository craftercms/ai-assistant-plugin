package plugins.org.craftercms.aiassistant.studio.engine.turn

import plugins.org.craftercms.aiassistant.studio.spi.llm.StudioAiLlmRuntime
import plugins.org.craftercms.aiassistant.studio.engine.context.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.studio.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.studio.config.StudioAiPlatformSettings
import plugins.org.craftercms.aiassistant.studio.http.AiHttpProxy
import plugins.org.craftercms.aiassistant.studio.spi.llm.StudioAiLlmKind
import plugins.org.craftercms.aiassistant.studio.engine.catalog.StudioAiLlmRuntimeFactory
import plugins.org.craftercms.aiassistant.studio.contrib.llm.StudioAiProviderCredentials
import plugins.org.craftercms.aiassistant.studio.spi.llm.StudioAiRuntimeBuildRequest
import plugins.org.craftercms.aiassistant.studio.engine.turn.plan.PlanOrchestration
import plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.engine.rag.PluginRagVectorRegistry
import plugins.org.craftercms.aiassistant.studio.engine.routing.Router
import plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentRecipeBindings
import plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentRecipeCatalog
import plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentRecipeEngine
import plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentRoutingEngine
import plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentRecipePlanCompiler
import plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringTurnGoal
import plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentCard
import plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentExecutionPlan
import plugins.org.craftercms.aiassistant.studio.engine.turn.chatcompletions.ChatCompletionsToolWire
import plugins.org.craftercms.aiassistant.studio.engine.turn.chatcompletions.GeneratedImageCmsPersistence
import plugins.org.craftercms.aiassistant.studio.engine.catalog.AiOrchestrationTools
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolMaintainerObservability
import plugins.org.craftercms.aiassistant.studio.contrib.llm.vendor.anthropic.AnthropicSpringAiLlmRuntime
import plugins.org.craftercms.aiassistant.studio.contrib.llm.vendor.anthropic.StudioAiAnthropicSimpleCompletion
import plugins.org.craftercms.aiassistant.studio.contrib.llm.wire.openaispec.OpenAiSpecSpringAiLlmRuntime
import plugins.org.craftercms.aiassistant.studio.contrib.llm.script.StudioAiScriptLlmContainerRuntime
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.http.OutboundHttpPolicy
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.FormDefinitionCopyFieldPlan
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.integrations.SerpApiWebSearchProjectSettings
import plugins.org.craftercms.aiassistant.studio.sandbox.StudioAiSandboxHttp

// Spring AI 1.1.x: no spring-ai-core on Maven Central — use split modules (was 1.0.0-M6 spring-ai-core).
@Grab(group='org.springframework.ai', module='spring-ai-model', version='1.1.7', initClass=false)
@Grab(group='org.springframework.ai', module='spring-ai-client-chat', version='1.1.7', initClass=false)
@Grab(group='org.springframework.ai', module='spring-ai-openai', version='1.1.7', initClass=false)
@Grab(group='org.springframework.ai', module='spring-ai-anthropic', version='1.1.7', initClass=false)
@Grab(group='io.projectreactor', module='reactor-core', version='3.6.6', initClass=false)

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage
import org.springframework.ai.openai.api.common.OpenAiApiConstants
import org.springframework.ai.tool.function.FunctionToolCallback
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
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
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.Locale
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
 * ({@link OpenAiSpecSpringAiLlmRuntime}, {@link AnthropicSpringAiLlmRuntime}, {@link StudioAiScriptLlmContainerRuntime} for
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
  /** Cap for tools-loop {@code /v1/chat/completions} JSON body size (any tools-loop vendor). */
  private static final int NATIVE_TOOLS_WIRE_JSON_MAX_CHARS = 36_000
  /** Max rendered HTML chars kept on the tools-loop wire per GetPreviewHtml result. */
  private static final int GET_PREVIEW_HTML_WIRE_MAX_HTML_CHARS = 10_000
  /** After this many successful preview fetches in one turn, stop further verification rounds. */
  private static final int GET_PREVIEW_HTML_MAX_FETCHES_PER_TURN = 2

  /** Consecutive tool-only LLM rounds before injecting a stall-guard user message. */
  private static final int TOOLS_LOOP_STALL_GUARD_FIRST_ROUND = 10

  /** Consecutive tool-only rounds before forcing a final author-visible message (below maxRounds). */
  private static final int TOOLS_LOOP_STALL_FORCE_FINISH_ROUND = 18

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

  /**
   * Installs (or clears when null) the per-thread AtomicBoolean orchestration uses for Stop/disconnect.
   * Delegates to ThreadLocal storage shared with AiOrchestrationTools callbacks.
   * Swallows Throwable so cancellation wiring never breaks chat startup.
   */
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

  /**
   * Removes the cancellability ThreadLocal after a tools-loop completes.
   * Prevents later unrelated worker threads from inheriting stale cancel flags.
   * Matches AiOrchestrationTools.runWithToolProgress lifecycle expectations.
   */
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

  /**
   * Clears the worker-thread session id bound for SSE heartbeat diagnostics.
   * Runs when native-tools workers tear down.
   * Avoids leaking ids across pooled executor threads.
   */
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

  /**
   * Deletes diagnostic phase strings keyed by streaming session id.
   * Invoked after flux completion so heartbeat maps stay bounded.
   * Pairs with servlet-thread reads while awaiting Futures.
   */
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
    if (p.contains('TranslateContentItem_simple_completion_HttpURLConnection_POST') ||
      p.contains('TranslateContentItem_simple_completion_RestClient_POST_/v1/chat/completions')) {
      return 'Sending an automated content edit…'
    }
    if (p.contains('TransformContentSubgraph_simple_completion_awaiting_chat_upstream_response_body')) {
      return 'Receiving updates for linked pages…'
    }
    if (p.contains('TransformContentSubgraph_simple_completion_HttpURLConnection_POST') ||
      p.contains('TransformContentSubgraph_simple_completion_RestClient_POST_/v1/chat/completions')) {
      return 'Sending a bundled content update…'
    }
    if (p.contains('simple_completion_awaiting_chat_upstream_response_body')) {
      return 'Waiting on a background content edit…'
    }
    if (p.contains('simple_completion_HttpURLConnection_POST') ||
      p.contains('simple_completion_RestClient_POST_/v1/chat/completions')) {
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
    if (p.contains('repository_tool') && p.contains('GenerateImage') && !p.contains('repository_tool_done')) {
      return 'Generating your image…'
    }
    if (p.contains('native_tool_loop_round') && p.contains('repository_tool') && !p.contains('repository_tool_done')) {
      return 'Updating your site…'
    }
    if (p.contains('native_tool_loop_round') && p.contains('_build_request')) {
      return 'Preparing the next step…'
    }
    return 'Organizing the next step…'
  }

  /**
   * Reads JVM property aiassistant.chatFluxAwaitMs when numeric between bounds.
   * Defaults to a ten-minute ceiling aligned with Studio UI SSE timeouts.
   * Feeds reactor/blocking awaits for Spring AI responses.
   */
  private static long resolveChatFluxAwaitMs() {
    try {
      def p = StudioAiPlatformSettings.property('aiassistant.chatFluxAwaitMs', '')
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
      def p = StudioAiPlatformSettings.property('aiassistant.openai.sseWaitHeartbeatMs', '')
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
  private static int resolveChatCompletionsRestReadTimeoutMs(Map toolsLoopSessionBundle = null) {
    if (toolsLoopSessionBundle instanceof Map) {
      Object refineMs = toolsLoopSessionBundle.get('intentRefineReadTimeoutMs')
      if (refineMs instanceof Number) {
        int n = ((Number) refineMs).intValue()
        if (n < 60_000) {
          return 60_000
        }
        if (n > 1_260_000) {
          return 1_260_000
        }
        return n
      }
    }
    try {
      def p = StudioAiPlatformSettings.property('aiassistant.openai.restReadTimeoutMs', '')
      if (p != null && p.toString().trim()) {
        int n = Integer.parseInt(p.toString().trim())
        if (n >= 60_000 && n <= 1_260_000) {
          return n
        }
      }
    } catch (Throwable ignored) {}
    return (int) Math.min(1_260_000L, CHAT_FLUX_AWAIT_MS + 30_000L)
  }

  /**
   * TCP connect timeout for {@link RestClient} {@code /v1/chat/completions} (ms). Default 30s; override
   * {@code aiassistant.openai.restConnectTimeoutMs} (5_000–120_000).
   */
  private static int resolveChatCompletionsRestConnectTimeoutMs() {
    try {
      def p = StudioAiPlatformSettings.property('aiassistant.openai.restConnectTimeoutMs', '')
      if (p != null && p.toString().trim()) {
        int n = Integer.parseInt(p.toString().trim())
        if (n >= 5_000 && n <= 120_000) {
          return n
        }
      }
    } catch (Throwable ignored) {}
    return 30_000
  }

  /**
   * Builds SimpleClientHttpRequestFactory honoring aiassistant.openai.restReadTimeoutMs defaults.
   * Adds sane connect timeouts so hung upstream chats surface quickly.
   * Shared by RestClient native-tools transports.
   */
  private static SimpleClientHttpRequestFactory chatCompletionsRestRequestFactory(Map toolsLoopSessionBundle = null) {
    def rf = new SimpleClientHttpRequestFactory()
    rf.setReadTimeout(resolveChatCompletionsRestReadTimeoutMs(toolsLoopSessionBundle))
    rf.setConnectTimeout(resolveChatCompletionsRestConnectTimeoutMs())
    return rf
  }

  /**
   * Chat completions rest client builder.
   * @return RestClient.Builder result.
   */
  private static RestClient.Builder chatCompletionsRestClientBuilder(
    String apiKey,
    String wireBaseUrl = null,
    Map toolsLoopSessionBundle = null
  ) {
    String base = (wireBaseUrl ?: '').toString().trim()
    if (!base) {
      base = (OpenAiApiConstants.DEFAULT_BASE_URL ?: 'https://api.openai.com').toString().trim()
    }
    base = base.replaceAll(/\/+$/, '')
    RestClient.builder()
      .baseUrl(base)
      .defaultHeader(HttpHeaders.AUTHORIZATION, 'Bearer ' + apiKey)
      .requestFactory(chatCompletionsRestRequestFactory(toolsLoopSessionBundle))
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
   * Set {@code aiassistant.springAiHttpDebug=true} in {@code platform-settings.json} to enable once per JVM.
   */
  private static final AtomicBoolean springAiVerboseHttpLoggingArmed = new AtomicBoolean(false)

  /**
   * Reflectively lowers Spring AI HTTP client logger levels once per JVM.
   * Guards duplicate initialization via AtomicBoolean latch.
   * Helps operators trace RestClient failures during native-tools loops.
   */
  private static void ensureVerboseSpringAiHttpLogging() {
    if (!StudioAiPlatformSettings.propertyBoolean('aiassistant.springAiHttpDebug', false)) {
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

  /**
   * Scans collapsed lowercase prompts for phrases implying mandatory CMS/tool usage.
   * Used before injecting guardrails into model-facing text.
   * Keeps suppression logic deterministic without another LLM hop.
   */
  private static boolean isToolRequiredIntent(String prompt) {
    def p = (prompt ?: '').toLowerCase()
    if (!p) return false
    def patterns = [
      /.*\bupdate\b.*/,
      /.*\bmodify\b.*/,
      /.*\bchange\b.*/,
      /.*\bedit\b.*/,
      /.*\bcreate\b.*/,
      /.*\bdraft\b.*/,
      /.*\bwrite\b.*/,
      /.*\brewrite\b.*/,
      /.*\bresearch\b.*/,
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
      /.*\bmake\b.*/,
      /.*\bsave\b.*/
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

**Content-changing requests** (translate, localize, rephrase, rewrite, fix grammar, shorten, expand, fill, update, change tone, write copy, etc.) mean **field values and item XML** — not FreeMarker templates, scripts, or other **code** unless the author explicitly asked for those. If the author updates **this page** / **the page** in preview **without** naming a single block, they mean **the page file and every referenced content item** linked via **node-selector** fields on that page — not the page item alone; apply or output updates for each repository path that holds visible copy.
1) **Do the work** in the target language or style. **End your reply** with a Markdown **```json** fenced block containing ONLY valid JSON of the form: {"aiassistantFormFieldUpdates":{"field_id":"new value",...}} using **exact** field element names from the form definition / XML in the prompt. List **every** field you changed. HTML/RTE fields: string values may include markup.
2) **Forbidden:** Generic CrafterCMS tutorials ("Access the Content Item", "Translation Configuration", "add a language", "click Save", workflow documentation), MCP/plugin commands, or refusing to translate when you can output the target language. A short intro sentence is OK; the **JSON block is mandatory** for these requests.

**Pure Q&A** (no edits to the open item): answer normally and **omit** the JSON block.

---

''' + tail
  }

  /**
   * Prepends or appends compact English reminders when prompts imply tooling obligations.
   * Honors fullSuppressRepoWrites to gate repository mutation wording separately.
   * Leaves benign prompts untouched via early exits.
   */
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
Follow **Plan when warranted** in the system message. **Simple** (**one** tool): one or two sentences, then **`tool_calls`** — **skip** **## Plan**. **Complex** (**more than one** tool): **## Plan** with **📋** steps from **tools + matched recipe** phases (concrete outcomes); then **`tool_calls`**. Refresh the **same** **📋** lines with **✅** / **❌** / **⚠️** / **⬜** only. Prefix narrated tool use with **🛠️**. Do **not** fake server-style tool log lines.
Do **not** paste full FreeMarker (`.ftl`) bodies or large XML dumps into the author's chat — summarize outcomes; they edit in the form.
If target path/id is unclear and the user message does not include **Studio authoring context** with a current repository path, call discovery tools first.
Your **final** reply after tools must state **success or problems** using **✅** / **❌** / **⚠️**, include a **clear business-friendly** recap under **## Plan Execution** (not **## Plan** again) that mirrors the **📋** checklist — **open that section** with one short line that **core work is done** and the bullets are **recap / verification**, then **ask what's next**.
For **content XML** (pages/components): preserve `<page>`/`<component>` and field tags from the current file and content type (`formFieldIds` / GetContentTypeFormDefinition). For GetContentTypeFormDefinition use **contentPath** or copy **contentTypeId** from `<content-type>` — never infer content type from filename. For **page-wide** translate/tone/rewrite, include **all referenced component** items, not the page file only (**ListContentDependencyScope** then per-path tools).
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
Follow **Plan when warranted** in the system message. **Simple** (**one** tool): one or two sentences, then **`tool_calls`** — **skip** **## Plan**. **Complex** (**more than one** tool): **## Plan** with **📋** steps from **tools + matched recipe** phases when present (concrete outcomes only) before tools. Then **follow that plan**; after each tool refresh the **same** **📋** lines with **✅** / **❌** / **⚠️** / **⬜** only. Prefix narrated tool use with **🛠️**. Do **not** fake server-style tool log lines.
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
Follow **Plan when warranted** in the system message. **Simple** (**one** tool): one or two sentences, then **`tool_calls`** — **skip** **## Plan**. **Complex** (**more than one** tool): **## Plan** with **📋** steps from **tools + matched recipe** phases when present (concrete outcomes only) before tools. Then **follow that plan**; after each tool refresh the **same** **📋** lines with **✅** / **❌** / **⚠️** / **⬜** only — keep the step list stable. Prefix narrated tool use with **🛠️**. Do **not** fake server-style tool log lines.
Do **not** paste full FreeMarker (`.ftl`) bodies or large XML dumps into the author's chat — summarize what was saved; they edit files in Studio.
If target path/id is unclear and the user message does not include **Studio authoring context** with a current repository path, call discovery tools first.
After **update_content**, **update_template**, or **update_content_type** returns, you must still call **WriteContent** with the full file — those tools do not save.
When the author only asked to **update content** — **field values and item XML / static-assets**, not template or schema **file edits** — use **update_content** → **WriteContent** on the **content item** path — **do not** call **update_template** or **update_content_type** to fix those tasks. You **may** use **analyze_template** or **GetContent** on `.ftl` **read-only** to diagnose; if the issue is **in the template**, **tell the author** — do not patch FTL without explicit consent to change templates. For **page-wide** translate/rewrite, call **ListContentDependencyScope** then **GetContent**/**update_content** and **WriteContent** for the **page** and **each referenced component** unless the author limited scope.
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
   * OpenAI-style key resolution (embeddings, legacy paths). Prefer {@link StudioAiProviderCredentials}.
   */
  static String resolveApiKey(String fromWidgetOrRequest = null) {
    return StudioAiProviderCredentials.resolveApiKey(StudioAiLlmKind.OPENAI_NATIVE, fromWidgetOrRequest)
  }

  /**
   * Vendor-aware API key for orchestration callers. When {@code llmNormalized} is omitted, defaults to OpenAI resolution
   * (expert-skill embeddings and legacy image paths).
   */
  static String resolveLlmApiKey(String fromWidgetOrRequest = null, String llmNormalized = null, String preferredSecretKey = null) {
    String kind = (llmNormalized ?: StudioAiLlmKind.OPENAI_NATIVE).toString()
    if (StudioAiLlmKind.isAnthropicClaude(kind)) {
      return StudioAiProviderCredentials.resolveAnthropicApiKey(fromWidgetOrRequest, preferredSecretKey)
    }
    if (StudioAiLlmKind.useToolsLoopChatRestClientBuiltInKinds(kind) || StudioAiLlmKind.isScriptHostedLlm(kind)) {
      return StudioAiProviderCredentials.resolveApiKey(kind, fromWidgetOrRequest, preferredSecretKey)
    }
    return resolveApiKey(fromWidgetOrRequest)
  }

  /**
   * For logs only: which path {@link #resolveApiKey(String)} took (mirrors resolution order; no secret material).
   */
  static String apiKeyResolutionSource() {
    return StudioAiProviderCredentials.apiKeyResolutionSourceForLog(StudioAiLlmKind.OPENAI_NATIVE)
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

  /**
   * Walks Spring AI Prompt ChatMessages producing Maps compatible with Chat Completions JSON.
   * Preserves roles (system/user/assistant/tool) expected by RestClient adapters.
   * Feeds shrink/truncate helpers before oversized POST bodies.
   */
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

  /**
   * Parses vendor JSON bodies heuristically for unknown-model / permission strings.
   * Uses substring guards across chat-completions response bodies from the LLM.
   * Lets orchestration normalize models instead of failing opaque 400s.
   */
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
      'Set the agent chat model to an id your host and API key support (for example in config/studio/ai-assistant/agents.json), pass llmModel on the chat request, or set JVM crafter.openai.model when using the default bundled chat host row.'
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

  /**
   * Trims requested chat model ids from widgets or POST bodies.
   * Aliases neo-* tokens when gateway vendors rename SKUs.
   * Returns empty string when callers should fall back to agent defaults.
   */
  static String resolveChatModel(String fromRequest) {
    String base = (fromRequest ?: '').toString().trim() ?: StudioAiPlatformSettings.property('crafter.openai.model', '').trim()
    if (!base) {
      throw new IllegalStateException(
        'The chat model is not configured properly. Set the agent LLM / llmModel in Project Tools → Agents (config/studio/ai-assistant/agents.json), pass llmModel on the chat request, or set crafter.openai.model in config/studio/scripts/aiassistant/config/platform-settings.json when using the default bundled chat host configuration.'
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
      def p = StudioAiPlatformSettings.property('aiassistant.translateContentItemMaxOutTokens', '')?.trim()
      if (p) {
        int v = Integer.parseInt(p)
        return Math.max(1024, Math.min(32_768, v))
      }
    } catch (Throwable ignored) {
    }
    return 8192
  }

  /**
   * When bundled inner tools omit {@code llmModel}, pick a **smaller** model in the **same** model family as
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
   * OpenAI Images API model id (e.g. {@code gpt-image-1}). Source: agent {@code imageModel} in agents.json or POST {@code imageModel} only.
   * Canonicalized via {@link #normalizeImagesApiModelId(String)}.
   */
  static String resolveImageModel(String fromRequest) {
    String base = (fromRequest ?: '').toString().trim()
    if (!base) {
      throw new IllegalStateException(
        'The GenerateImage model is not configured properly. Set imageModel on the agent in config/studio/ai-assistant/agents.json or pass imageModel on the chat request JSON body.'
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

  /** Per-request enabled agent skills from the client (see {@code aiassistant.expertSkills} request attribute). */
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
   * Builds the Spring AI chat client + tools via {@link StudioAiLlmRuntime} ({@link OpenAiSpecSpringAiLlmRuntime}, Claude, script hosts).
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
    String imageGeneratorParam = null,
    String llmSecretKeyFromAgent = null
  ) {
    def converter = { Object result, java.lang.reflect.Type returnType -> toolResultToWireString(result, returnType) }
    /** Spring AI tool callbacks run on Reactor/HTTP-client threads; copy servlet SecurityContext for Studio permission checks. */
    def securityContextForTools = StudioToolOperations.captureSecurityContextCopy()
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
      llmSecretKeyFromAgent: llmSecretKeyFromAgent,
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

  /**
   * Pulls nested maps from tools-loop session bundles describing recipe router budgets.
   * Returns immutable-ish defaults when bundle lacks recipe metadata.
   * Feeds AuthoringIntentRecipeRouter guards without Groovy casts leaking outward.
   */
  private static Map intentRecipeProjectConfigFromToolsLoopBundle(Map toolsLoopSessionBundle) {
    if (toolsLoopSessionBundle?.studioOps instanceof StudioToolOperations) {
      try {
        return StudioAiAssistantProjectConfig.load((StudioToolOperations) toolsLoopSessionBundle.studioOps)
      } catch (Throwable ignored) {
      }
    }
    return Collections.emptyMap()
  }

  /**
   * Tools-loop authoring <strong>system</strong> text only — same assembly as {@link #authoringPrompt} uses for
   * {@link SystemMessage}, without servlet {@code request}. Used by the autonomous worker (and keeps stream + headless aligned).
   *
   * @param expertSkillSpecsNormalized maps with {@code skillId}, {@code name}, {@code url}, {@code description}
   *        (e.g. {@link plugins.org.craftercms.aiassistant.studio.engine.rag.ExpertSkillVectorRegistry#normalizeRequestExpertSkills}); may be null or empty
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
    Map projectCfg = studioOps != null ? StudioAiAssistantProjectConfig.load(studioOps) : Collections.emptyMap()
    core = PluginRagVectorRegistry.adjustAuthoringCore(core, site, utEarly, studioOps, llmApiKey, toolSchemasOnApi, projectCfg)
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
   * Includes active site id when available. When {@code toolSchemasOnApi} is false, system text matches LLM requests
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

  /**
   * Extracts finish_reason deltas from streamed ChatCompletion chunk maps.
   * Handles both snake_case and camelCase vendor quirks.
   * Signals stop/tool_calls transitions to native-tools iterators.
   */
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

  /**
   * Pulls provider error payloads from streamed chunk roots.
   * Looks under error/message/refusal mirrors depending on vendor JSON.
   * Surfaces actionable strings to SSE clients without dumping entire payloads.
   */
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
   * Reads upstream LLM {@code text/event-stream} chat.completions chunks and forwards assistant deltas as Studio SSE.
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

  /**
   * Maps Spring AI ChatCompletionMessage records into LinkedHashMaps RestClient expects.
   * Copies tool_calls/content/refusal metadata faithfully.
   * Keeps JSON serializers deterministic across vendors.
   */
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

  /**
   * Serializes Spring FunctionToolCallback metadata into Chat Completions tool slots.
   * Includes sanitized JSON schemas when callbacks expose structured inputs.
   * Produces stable ordering so replay/debug traces remain readable.
   */
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

  /**
   * Indexes callbacks by wire-safe tool names for O(1) dispatch.
   * Throws when duplicates appear to prevent ambiguous executions.
   * Feeds parallel Anthropic / tools-loop bridging layers.
   */
  private static Map<String, FunctionToolCallback> toolCallbacksByName(List tools) {
    Map<String, FunctionToolCallback> m = new LinkedHashMap<>()
    if (!tools) {
      return m
    }
    tools.each { t ->
      if (t instanceof FunctionToolCallback) {
        def td = t.getToolDefinition()
        if (td?.name()) {
          String wireName = td.name()
          if (m.containsKey(wireName)) {
            throw new IllegalStateException("Duplicate tool name registered for tools loop: ${wireName}")
          }
          m.put(wireName, (FunctionToolCallback) t)
        }
      }
    }
    m
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

  /**
   * Author visible from prompt text.
   * @param promptText Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String authorVisibleFromPromptText(String promptText) {
    String scrubbed = AuthoringPreviewContext.stripStudioOrchestrationPrefixBlocks((promptText ?: '').toString())
    String current = AuthoringPreviewContext.extractAuthorCurrentRequestVisible(scrubbed ?: '')
    if (current?.trim()) {
      return current.trim()
    }
    String clientBlock = AuthoringPreviewContext.extractOrchestrationClientAuthorBlock(scrubbed ?: '')
    if (clientBlock?.trim()) {
      clientBlock = AuthoringPreviewContext.stripStudioOrchestrationPrefixBlocks(clientBlock)?.trim() ?: clientBlock.trim()
      return clientBlock
    }
    String flat = (scrubbed ?: '').toString()
    try {
      flat = AuthoringPreviewContext.stripStudioInjectedPromptBlocks(flat)
    } catch (Throwable ignored) {
    }
    return (flat ?: '').trim()
  }

  /** Matched {@code generate_image} recipe — chat-only bitmap; never unlock CMS tools via bypass. */
  private static boolean isGenerateImageRecipeMatchedTurn(Map intentTel) {
    return intentTel instanceof Map &&
      'matched'.equals(intentTel.get('outcome')?.toString()) &&
      'generate_image'.equals(intentTel.get('recipeId')?.toString()?.trim())
  }

  /**
   * Clean author request for policy checks — prefer routing bundle slice over turn-goal-prefixed wire text.
   */
  private static String resolveToolsLoopAuthorVisible(Map toolsLoopSessionBundle, List wireMessages) {
    if (toolsLoopSessionBundle instanceof Map) {
      String fromBundle = toolsLoopSessionBundle.authorIntentCardAuthorVisible?.toString()?.trim()
      if (fromBundle) {
        return fromBundle
      }
      String fromTel = ''
      if (toolsLoopSessionBundle.intentRecipeRoutingTelemetry instanceof Map) {
        fromTel = ((Map) toolsLoopSessionBundle.intentRecipeRoutingTelemetry).authorRequestText?.toString()?.trim() ?: ''
      }
      if (fromTel) {
        return fromTel
      }
    }
    return authorVisibleFromPromptText(authorVisibleRequestFromWire(wireMessages) ?: '')
  }

  /**
   * Applies {@code orchestration.toolsLoopAllowlist} from matched-recipe telemetry when the author
   * did not trigger {@code toolsLoopAllowlistBypassIfAuthorMentions}.
   */
  private static List effectiveToolsForIntentRecipe(
    List tools,
    Map intentTel,
    String authorVisible,
    String agentId,
    StudioToolOperations ops = null,
    Map projectCfg = null
  ) {
    if (!(intentTel instanceof Map)) {
      return tools
    }
    if (isGenerateImageRecipeMatchedTurn(intentTel)) {
      Set<String> genOnlyNames = ['GenerateImage'] as Set
      List genOnly = filterToolCallbacksAllowlist(tools, genOnlyNames)
      intentTel.put('toolsLoopAllowlist', ['GenerateImage'])
      if (genOnly == null || genOnly.isEmpty()) {
        intentTel.put('generateImageToolUnavailable', Boolean.TRUE)
        log.warn(
          'Tools-loop: generate_image recipe matched but GenerateImage is not registered agentId={}',
          agentId
        )
        return []
      }
      log.info(
        'Tools-loop: generate_image recipe matched — GenerateImage only ({} of {} tools) agentId={}',
        genOnly.size(),
        tools?.size() ?: 0,
        agentId
      )
      return genOnly
    }
    boolean chatOnlyGenerateImage = isGenerateImageChatOnlyRecipeTurn(intentTel, authorVisible)
    if (chatOnlyGenerateImage) {
      List genOnly = filterToolCallbacksAllowlist(tools, ['GenerateImage'] as Set)
      intentTel.put('toolsLoopAllowlist', ['GenerateImage'])
      if (genOnly == null || genOnly.isEmpty()) {
        intentTel.put('generateImageToolUnavailable', Boolean.TRUE)
        log.warn(
          'Tools-loop: chat-only generate_image but GenerateImage is not registered agentId={}',
          agentId
        )
        return []
      }
      log.info(
        'Tools-loop: chat-only generate_image — GenerateImage only ({} of {} tools) agentId={}',
        genOnly.size(),
        tools?.size() ?: 0,
        agentId
      )
      return genOnly
    }
    String outcome = intentTel.get('outcome')?.toString() ?: ''
    boolean routerTool = 'router_tool'.equals(outcome) || 'tool'.equals(intentTel.routingMode?.toString())
    if (!'matched'.equals(outcome) && !routerTool) {
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
    allowNames = SerpApiWebSearchProjectSettings.rewriteAllowlistForSerpApi(allowNames, ops, projectCfg)
    if (allowNames != null && !allowNames.isEmpty()) {
      intentTel.put('toolsLoopAllowlist', new ArrayList<>(allowNames))
    }
    if (allowNames.contains('GeneratePlaceholderImage') &&
      !allowNames.contains('GenerateImage') &&
      AuthoringPreviewContext.authorVisibleSuggestsIntentRecipeGenerateImage(authorVisible ?: '')) {
      List genOnly = filterToolCallbacksAllowlist(tools, ['GenerateImage'] as Set)
      if (genOnly != null && !genOnly.isEmpty()) {
        log.info(
          'Tools-loop: router chose GeneratePlaceholderImage but author requested generated art — using GenerateImage agentId={}',
          agentId
        )
        allowNames.remove('GeneratePlaceholderImage')
        allowNames.add('GenerateImage')
        intentTel.put('toolsLoopAllowlist', new ArrayList<>(allowNames))
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

  /**
   * Filter tool callbacks exclude names.
   * @param tools Caller-supplied input.
   * @param excludeNames Caller-supplied input.
   * @return List payload for tools or orchestration.
   */
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

  /**
   * Drops wire tools listed on the matched recipe ({@code toolsLoopExcludeTools} in intent recipe JSON).
   * @param tools Spring {@code FunctionToolCallback} list for the session
   * @param intentTel {@code intentRecipeRoutingTelemetry} map from routing
   * @return filtered tool list (unchanged when no excludes)
   */
  private static List applyRecipeToolsLoopExcludes(List tools, Map intentTel) {
    if (!(intentTel instanceof Map)) {
      return tools
    }
    Object ex = intentTel.get('toolsLoopExcludeTools')
    if (!(ex instanceof List) || ((List) ex).isEmpty()) {
      return tools
    }
    Set<String> excludeNames = new LinkedHashSet<>()
    for (Object o : (List) ex) {
      String n = o?.toString()?.trim()
      if (n) {
        excludeNames.add(n)
      }
    }
    if (excludeNames.isEmpty()) {
      return tools
    }
    return filterToolCallbacksExcludeNames(tools, excludeNames)
  }

  /**
   * Synthesize generate image unavailable markdown.
   * @return Text result, or empty or null when unavailable.
   */
  private static String synthesizeGenerateImageUnavailableMarkdown() {
    return '''## Image generation unavailable

Studio matched **Generate image (bitmap)** for this turn, but the **GenerateImage** tool is not available in this session.

**Check:**
- **Project Tools → AI Assistant → Agents** — save the chat agent with an **Image model** (`imageModel` in `config/studio/ai-assistant/agents.json`), or
- **LLM / images API key** — configure the key your Studio site uses for the agent’s LLM kind (e.g. `OPENAI_API_KEY` when the agent uses `openAI`).

When the built-in images wire is enabled, set **imageModel** on the agent or pass it on the chat request — there is no server default. Retry the same prompt after keys and catalog are in place.'''
  }

  /**
   * Synthesize forced tool unavailable markdown.
   * @return Text result, or empty or null when unavailable.
   */
  private static String synthesizeForcedToolUnavailableMarkdown(
    String forceTool,
    String recipeTitle,
    String recipeId,
    String reason
  ) {
    String ft = (forceTool ?: '').trim() ?: '(unknown)'
    String title = (recipeTitle ?: '').trim() ?: recipeId?.trim() ?: 'Intent recipe'
    String rid = (recipeId ?: '').trim()
    StringBuilder sb = new StringBuilder('## Recipe tool unavailable\n\n')
    sb.append('Studio matched **').append(title).append('**')
    if (rid) {
      sb.append(' (`').append(rid).append('`)')
    }
    sb.append(', which requires **').append(ft).append('** as the first tool call, but that tool is **not available** in this session.\n\n')
    if (reason?.trim()) {
      sb.append(reason.trim()).append('\n\n')
    }
    sb.append('**Fix:** Enable the tool (and any required secret) under **Project Tools → AI Assistant → Tools and MCP**, ')
    sb.append('or change **toolsLoopForceTool** on the site intent recipe to a tool that is actually enabled.')
    return sb.toString()
  }

  /**
   * Applies intent-routing side effects on the tools-loop session: disable tools, allowlists, caps, turn goal,
   * and matched-recipe execution plan bindings.
   *
   * @param springAi orchestration session map ({@code useTools}, {@code tools}, {@code authorTurnGoal}, …)
   * @param route result from {@link plugins.org.craftercms.aiassistant.studio.engine.routing.Router#route}
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
    if (!(tel instanceof Map)) {
      return
    }
    if (route?.authorTurnGoal != null) {
      springAi.authorTurnGoal = route.authorTurnGoal.toString()
    }
    if (route?.authorTurnSuccessCriteria != null) {
      springAi.authorTurnSuccessCriteria = route.authorTurnSuccessCriteria.toString()
    }
    String routingMode = tel.routingMode?.toString()?.trim()?.toLowerCase() ?: ''
    if ('chat_only'.equals(routingMode) || Boolean.TRUE.equals(tel.toolsLoopDisable)) {
      springAi.useTools = false
      log.info('Tools-loop: intent router chat_only — tools off agentId={}', springAi.agentId ?: '')
      return
    }
    Object routerAllow = tel.toolsLoopAllowlist
    if (('tool'.equals(routingMode) || 'router_tool'.equals(tel.outcome?.toString())) &&
      routerAllow instanceof List && !((List) routerAllow).isEmpty() &&
      springAi.tools instanceof List) {
      Set<String> allowNames = new LinkedHashSet<>()
      for (Object o : (List) routerAllow) {
        String n = o?.toString()?.trim()
        if (n) {
          allowNames.add(n)
        }
      }
      List toolsBefore = (List) springAi.tools
      List filtered = filterToolCallbacksAllowlist(toolsBefore, allowNames)
      if (filtered != null && !filtered.isEmpty()) {
        springAi.tools = filtered
        log.info(
          'Tools-loop: intent router tool mode allowlist ({} of {} tools) agentId={}',
          filtered.size(),
          toolsBefore?.size() ?: 0,
          springAi.agentId ?: ''
        )
      }
    }
    if (!'matched'.equals(tel.get('outcome')?.toString())) {
      return
    }
    Object fetchCap = tel.get('toolsLoopFetchHttpUrlWireMaxChars')
    if (fetchCap instanceof Number && ((Number) fetchCap).intValue() > 256) {
      springAi.toolsLoopFetchHttpUrlWireMaxChars = Math.min(((Number) fetchCap).intValue(), 24_000)
    }
    if (Boolean.TRUE.equals(tel.get('toolsLoopWebResearchOnly'))) {
      int maxWire = StudioAiLlmKind.toolsLoopChatMaxWirePayloadCharsFromBundle(springAi)
      if (maxWire <= 0) {
        springAi.toolsLoopChatMaxWirePayloadChars = 320_000
        log.info(
          'Tools-loop: web-research-only recipe {} — toolsLoopChatMaxWirePayloadChars=320000 agentId={}',
          tel.get('recipeId')?.toString()?.trim() ?: '(unknown)',
          springAi.agentId ?: ''
        )
      }
    }
    Object maxFetchObj = tel.get('toolsLoopMaxFetchHttpUrlCalls')
    if (maxFetchObj instanceof Number && ((Number) maxFetchObj).intValue() > 0) {
      springAi.toolsLoopMaxFetchHttpUrlCalls = Math.min(((Number) maxFetchObj).intValue(), 10)
    }
    if (route?.recipeExecutionPlan instanceof Map) {
      springAi.recipeExecutionPlan = route.recipeExecutionPlan
      springAi.recipeConfirmationStepsExecuted = Boolean.FALSE
    }
    if (route?.createFromChatDraftPrefill instanceof Map) {
      springAi.createFromChatDraftPrefill = route.createFromChatDraftPrefill
    }
    if (Boolean.TRUE.equals(tel.get('toolsLoopDisable'))) {
      springAi.useTools = false
      log.info(
        'Tools-loop: recipe {} toolsLoopDisable — tools off for this turn agentId={}',
        tel.get('recipeId')?.toString()?.trim() ?: '(unknown)',
        springAi.agentId ?: ''
      )
      return
    }
  }

  /**
   * Filter tool callbacks allowlist.
   * @param tools Caller-supplied input.
   * @param allowNames Caller-supplied input.
   * @return List payload for tools or orchestration.
   */
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

  /**
   * True when internal ai assistant wire user message.
   * @param flat Caller-supplied input.
   * @return True when the check succeeds.
   */
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

  /**
   * Author visible request from wire.
   * @param wireMessages Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
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
   * Author line for outcome-phrase parsing — not recipe prefetch or Studio metadata blocks.
   */
  private static String authorVisibleTailForOutcomePhrase(String authorVisible) {
    if (!authorVisible?.trim()) {
      return ''
    }
    String v = authorVisible.trim()
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

  /**
   * Extracts author field label from line from repository XML or related text.
   * @param line Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
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

  /** True when the author already named a concrete field-level edit. */
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

  /**
   * Author request needs prior turn content resolution.
   * @param authorVisible Caller-supplied input.
   * @return True when the check succeeds.
   */
  private static boolean authorRequestNeedsPriorTurnContentResolution(String authorVisible) {
    String tail = authorVisibleTailForOutcomePhrase(authorVisible)
    String scan = [tail, (authorVisible ?: '').toString()].findAll { it?.trim() }.join('\n')
    return AuthoringPreviewContext.authorVisibleSuggestsPriorTurnContent(scan)
  }

  /**
   * Outcome phrase equals resolved field label.
   * @param outcomePhrase Caller-supplied input.
   * @param fieldLabel Caller-supplied input.
   * @return True when the check succeeds.
   */
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
    if ((low =~ /(?is)\b(be\s+)?about\s+(the\s+)?(latest|recent|current)\b/).find()) {
      return true
    }
    if ((low =~ /(?is)\b(latest|recent)\s+(updates?|developments?|news)\b/).find()) {
      return true
    }
    if ((low =~ /(?is)\b(regarding|updates?\s+or\s+new)\b/).find() && low.length() > 40) {
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
    if (AuthoringPreviewContext.authorCurrentRequestLooksLikeImageOnlyGenerate((visible ?: '').trim() ?: probe)) {
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
    if (AuthoringIntentExecutionPlan.requiresExternalLookup(authorVisible ?: '')) {
      return ''
    }
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

  /**
   * Normalizes and validates outcome phrase; throws when required values are missing.
   * @param s Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String normalizeOutcomePhrase(String s) {
    if (!s?.trim()) {
      return ''
    }
    return s.trim().replaceAll(/[.!?]+\s*$/, '').trim()
  }

  /**
   * Html to rough plain text.
   * @param html Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
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

  /**
   * Plain text contains phrase.
   * @param haystackPlain Caller-supplied input.
   * @param phrase Caller-supplied input.
   * @return True when the check succeeds.
   */
  private static boolean plainTextContainsPhrase(String haystackPlain, String phrase) {
    if (!phrase?.trim() || !haystackPlain) {
      return false
    }
    return haystackPlain.toLowerCase(Locale.ROOT).contains(phrase.trim().toLowerCase(Locale.ROOT))
  }

  /**
   * Repo path from tool args map.
   * @param args Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String repoPathFromToolArgsMap(Map args) {
    if (!(args instanceof Map)) {
      return ''
    }
    return plugins.org.craftercms.aiassistant.studio.engine.catalog.AiOrchestrationTools.repoPathFromToolInput(args) ?: ''
  }

  /**
   * When {@code createFromChatDraft} prefetch built authoritative post XML, replace model {@code contentXml}
   * for the suggested path so draft body / author bio are not regenerated or copied from sibling metadata.
   */
  private static boolean createFromChatDraftRepoPathsMatch(String prefillPath, String writePath) {
    String a = plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsRepositorySupport
      .normalizeLeadingSlash((prefillPath ?: '').toString().trim(), 'path')
    String b = plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsRepositorySupport
      .normalizeLeadingSlash((writePath ?: '').toString().trim(), 'path')
    if (!a || !b) {
      return false
    }
    return a.equalsIgnoreCase(b)
  }

  /** Prefetch-suggested repository path for create-from-chat-draft (prefill map or routing telemetry). */
  private static String createFromChatDraftSuggestedPath(Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return ''
    }
    Map prefill = toolsLoopSessionBundle.createFromChatDraftPrefill instanceof Map ?
      (Map) toolsLoopSessionBundle.createFromChatDraftPrefill :
      null
    String fromPrefill = (prefill?.path ?: '').toString().trim()
    if (fromPrefill) {
      return fromPrefill
    }
    Map tel = toolsLoopSessionBundle.intentRecipeRoutingTelemetry instanceof Map ?
      (Map) toolsLoopSessionBundle.intentRecipeRoutingTelemetry :
      null
    return (tel?.toolsLoopSuggestedNewItemPath ?: '').toString().trim()
  }

  /** Nudges ContentExists toward the prefetch-suggested path when the model omitted path args. */
  private static String applyCreateFromChatDraftPrefillToContentExistsArgs(
    String argsStr,
    Map toolsLoopSessionBundle,
    JsonSlurper slurper
  ) {
    String prefillPath = createFromChatDraftSuggestedPath(toolsLoopSessionBundle)
    if (!prefillPath) {
      return argsStr
    }
    try {
      Object parsed = slurper.parseText(argsStr ?: '{}')
      if (!(parsed instanceof Map)) {
        return argsStr
      }
      Map args = (Map) parsed
      String existing = repoPathFromToolArgsMap(args)
      if (existing?.trim()) {
        return argsStr
      }
      Map out = new LinkedHashMap<>(args)
      out.put('path', prefillPath)
      return JsonOutput.toJson(out)
    } catch (Throwable ignored) {
      return argsStr
    }
  }

  /** Nudges WriteContent toward the prefetch-suggested path when the model omitted path args. */
  private static String applyCreateFromChatDraftPrefillToWriteContentArgs(
    String argsStr,
    Map toolsLoopSessionBundle,
    JsonSlurper slurper
  ) {
    String prefillPath = createFromChatDraftSuggestedPath(toolsLoopSessionBundle)
    if (!prefillPath) {
      return argsStr
    }
    try {
      Object parsed = slurper.parseText(argsStr ?: '{}')
      if (!(parsed instanceof Map)) {
        return argsStr
      }
      Map args = (Map) parsed
      String wpath = repoPathFromToolArgsMap(args)
      if (wpath?.trim()) {
        return argsStr
      }
      Map out = new LinkedHashMap<>(args)
      out.put('path', prefillPath)
      return JsonOutput.toJson(out)
    } catch (Throwable ignored) {
      return argsStr
    }
  }

  /**
   * Site id from wire messages.
   * @param wireMessages Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String siteIdFromWireMessages(List<Map> wireMessages) {
    String user = firstAuthorVisibleUserFromWire(wireMessages) ?: ''
    def working = (user =~ /Working CMS site id:\s*"([^"]+)"/)
    if (working.find()) {
      return working.group(1)?.trim() ?: ''
    }
    def m = (user =~ /Current CrafterCMS site id:\s*"([^"]+)"/)
    if (m.find()) {
      return m.group(1)?.trim() ?: ''
    }
    return ''
  }

  /**
   * Engine preview url from wire.
   * @param wireMessages Caller-supplied input.
   * @param toolsLoopSessionBundle Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
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

  /**
   * Engine preview URL for a repository path (falls back to anchor preview URL from wire).
   */
  private static String previewUrlForRepoPath(
    String repoPath,
    Map toolsLoopSessionBundle,
    List<Map> wireMessages
  ) {
    String path = (repoPath ?: '').trim()
    if (path) {
      try {
        def ops = toolsLoopSessionBundle?.get('studioOps')
        if (ops instanceof StudioToolOperations) {
          String siteId = ops.resolveEffectiveSiteId(null)
          String url = AuthoringPreviewContext.buildEnginePreviewAbsoluteUrl(ops.request, siteId, path)
          if (url?.trim()) {
            return url.trim()
          }
        }
      } catch (Throwable ignored) {
      }
    }
    return enginePreviewUrlFromWire(wireMessages, toolsLoopSessionBundle)
  }

  /**
   * Minimal plan when tools without prose.
   * @param zeroBasedRound Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String minimalPlanWhenToolsWithoutProse(int zeroBasedRound) {
    if (zeroBasedRound > 0) {
      return ''
    }
    return 'Applying your request with the appropriate tools.\n'
  }

  /**
   * Applies preview verification on GetPreviewHtml tool JSON: template errors and written copy snippets (not author prompt).
   */
  private static Map enrichGetPreviewHtmlToolResult(
    String toolOut,
    String frozenAuthorOutcomePhrase,
    JsonSlurper slurper,
    Map toolsLoopSessionBundle = null
  ) {
    Boolean found = null
    String phrase = ''
    String verificationReason = ''
    String verificationDetail = ''
    String outJson = toolOut ?: ''
    try {
      def parsedPrev = slurper.parseText(outJson)
      if (!(parsedPrev instanceof Map)) {
        return [
          toolOut               : outJson,
          previewGoalFound      : found,
          previewGoalPhrase     : phrase ?: frozenAuthorOutcomePhrase ?: '',
          previewVerifyReason   : verificationReason,
          previewVerifyDetail   : verificationDetail
        ]
      }
      Map prevMap = (Map) parsedPrev
      boolean httpOk = Boolean.TRUE.equals(prevMap.get('ok'))
      if (toolsLoopSessionBundle instanceof Map) {
        toolsLoopSessionBundle.toolsLoopPreviewHttpOk = httpOk
        if (prevMap.get('statusCode') != null) {
          toolsLoopSessionBundle.toolsLoopPreviewHttpStatus = prevMap.get('statusCode')
        }
      }
      if (!httpOk) {
        int status = 0
        try {
          if (prevMap.get('statusCode') instanceof Number) {
            status = ((Number) prevMap.get('statusCode')).intValue()
          }
        } catch (Throwable ignoredStatus) {
        }
        found = Boolean.FALSE
        verificationReason = status >= 500 ? 'http_server_error' : 'http_error'
        verificationDetail = (prevMap.get('message') ?: (status > 0 ? "HTTP ${status}" : 'Preview fetch failed')).toString()
        prevMap.put('contentGoalFoundInPreviewHtml', false)
        prevMap.put(
          'verificationWarning',
          'Preview fetch failed' +
            (status > 0 ? " (HTTP ${status})" : '') +
            '. The page may be broken — call **GetContent**, fix the full item XML, **WriteContent**, then **GetPreviewHtml** again before finishing.'
        )
        outJson = JsonOutput.toJson(prevMap)
      } else {
        def html = prevMap.get('html')
        if (html != null && html.toString().trim()) {
          String htmlStr = html.toString()
          String plain = htmlToRoughPlainText(htmlStr)
          String renderError = FormDefinitionCopyFieldPlan.detectPreviewRenderingError(htmlStr)
          if (renderError) {
            found = Boolean.FALSE
            verificationReason = 'rendering_error'
            verificationDetail = renderError
            prevMap.put('previewRenderingError', renderError)
            prevMap.put('contentGoalFoundInPreviewHtml', false)
            prevMap.put(
              'verificationWarning',
              'Preview HTML contains a rendering error: ' + renderError + '. Fix the template or content before publishing.'
            )
            outJson = JsonOutput.toJson(prevMap)
          } else {
            Map verify = FormDefinitionCopyFieldPlan.verifyPreviewAgainstWrittenCopy(plain, toolsLoopSessionBundle)
            if (verify.reason == 'no_written_copy_recorded' &&
              frozenAuthorOutcomePhrase?.trim() &&
              !outcomePhraseLooksLikeInstructionNotContent(frozenAuthorOutcomePhrase)) {
              boolean hit = plainTextContainsPhrase(plain, frozenAuthorOutcomePhrase)
              verify = [
                found          : hit ? Boolean.TRUE : Boolean.FALSE,
                reason         : hit ? 'literal_outcome_found' : 'literal_outcome_not_found',
                detail         : '',
                checkedPhrase  : frozenAuthorOutcomePhrase.trim(),
                phrasesChecked : [frozenAuthorOutcomePhrase.trim()],
                warning        : hit ? '' :
                  'Preview HTML does not contain the expected literal change "' +
                    abbreviatePreviewPhraseForWarning(frozenAuthorOutcomePhrase) +
                    '". Open preview in Studio and confirm before publishing.'
              ]
            }
            found = verify.found instanceof Boolean ? (Boolean) verify.found : null
            verificationReason = (verify.reason ?: '').toString()
            verificationDetail = (verify.detail ?: '').toString()
            phrase = (verify.checkedPhrase ?: '').toString()
            List phrasesChecked = verify.phrasesChecked instanceof List ? (List) verify.phrasesChecked : []
            if (phrasesChecked) {
              prevMap.put('writtenCopyPhrasesChecked', phrasesChecked)
            }
            if (phrase) {
              prevMap.put('contentGoalPhrase', phrase)
            }
            if (found != null) {
              prevMap.put('contentGoalFoundInPreviewHtml', found)
            }
            if (Boolean.FALSE.equals(found)) {
              prevMap.put(
                'verificationWarning',
                (verify.warning ?: '').toString() ?:
                  'Preview HTML does not show the copy that was saved. Open preview in Studio and confirm before publishing.'
              )
            } else if (found == Boolean.TRUE && verificationReason == 'written_copy_found') {
              prevMap.put(
                'verificationNote',
                'Preview HTML includes saved copy from this turn.'
              )
            } else if (found == Boolean.TRUE) {
              prevMap.put(
                'verificationNote',
                'Preview HTML fetched successfully; no written-copy phrase check was required for this turn.'
              )
            }
            outJson = JsonOutput.toJson(prevMap)
          }
        }
      }
    } catch (Throwable ignored) {
    }
    if (toolsLoopSessionBundle instanceof Map) {
      if (found instanceof Boolean) {
        toolsLoopSessionBundle.toolsLoopPreviewVerificationFound = found
      }
      toolsLoopSessionBundle.toolsLoopPreviewVerificationReason = verificationReason
      toolsLoopSessionBundle.toolsLoopPreviewVerificationDetail = verificationDetail
      if (phrase) {
        toolsLoopSessionBundle.toolsLoopPreviewVerificationPhrase = phrase
      }
    }
    return [
      toolOut               : outJson,
      previewGoalFound      : found,
      previewGoalPhrase     : phrase ?: frozenAuthorOutcomePhrase ?: '',
      previewVerifyReason   : verificationReason,
      previewVerifyDetail   : verificationDetail
    ]
  }

  /**
   * Maybe append auto confirmation preview after round.
   */
  private static boolean maybeAppendAutoConfirmationPreviewAfterRound(
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
    Map previewState,
    String successfulWriteRepoPath = '',
    Set<String> previewHtmlUrlsThisTurn = null,
    int successfulPreviewFetchesThisTurn = 0
  ) {
    if (!roundHadWriteSuccess || roundRanGetPreviewHtml || roundHadWriteFailure) {
      return false
    }
    String url = previewUrlForRepoPath(successfulWriteRepoPath, toolsLoopSessionBundle, wireMessages)
    if (!url) {
      return false
    }
    String urlKey = normalizePreviewUrlKey(url)
    if (previewHtmlUrlsThisTurn != null && urlKey && previewHtmlUrlsThisTurn.contains(urlKey)) {
      return false
    }
    if (successfulPreviewFetchesThisTurn >= GET_PREVIEW_HTML_MAX_FETCHES_PER_TURN) {
      return false
    }
    FunctionToolCallback tcb = byName?.get('GetPreviewHtml')
    if (tcb == null) {
      return false
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
      return false
    }
    if (toolOut instanceof Map) {
      toolOut = JsonOutput.toJson((Map) toolOut)
    } else {
      toolOut = toolOut?.toString() ?: ''
    }
    Map enriched = enrichGetPreviewHtmlToolResult(toolOut, frozenAuthorOutcomePhrase, slurper, toolsLoopSessionBundle)
    toolOut = enriched.toolOut?.toString() ?: ''
    if (enriched.previewGoalFound instanceof Boolean) {
      previewState.lastPreviewContentGoalFound = enriched.previewGoalFound
    }
    if (enriched.previewGoalPhrase) {
      previewState.lastPreviewContentGoalPhrase = enriched.previewGoalPhrase.toString()
    }
    if (previewHtmlUrlsThisTurn != null && urlKey) {
      previewHtmlUrlsThisTurn.add(urlKey)
    }
    String wire = truncateNativeToolWireContent('GetPreviewHtml', toolOut, 'aiassistant-auto-preview', [:], toolsLoopSessionBundle)
    wireMessages << [
      role   : 'user',
      content:
        '[aiassistant: confirmation preview after successful WriteContent — internal]\n' +
          'Studio ran **GetPreviewHtml** automatically after a successful write. Use this result for verification; do not repeat GetPreviewHtml unless you changed content again.\n\n' +
          wire
    ]
    log.info(
      'Tools-loop: auto GetPreviewHtml after successful WriteContent round={} agentId={} phraseFound={} httpOk={}',
      round,
      agentId,
      enriched.previewGoalFound,
      toolsLoopSessionBundle?.toolsLoopPreviewHttpOk
    )
    return Boolean.TRUE.equals(toolsLoopSessionBundle?.toolsLoopPreviewHttpOk)
  }

  /** True when a successful write may end the loop with an honest “Done” (preview HTTP ok and verification not failed). */
  private static boolean toolsLoopPreviewVerificationAllowsEarlyFinish(
    int successfulPreviewFetchesThisTurn,
    Boolean lastPreviewContentGoalFound,
    Map toolsLoopSessionBundle,
    boolean roundHadWriteSuccess
  ) {
    if (!roundHadWriteSuccess) {
      return false
    }
    if (successfulPreviewFetchesThisTurn < 1) {
      return false
    }
    if (toolsLoopSessionBundle instanceof Map) {
      if (toolsLoopSessionBundle.toolsLoopPreviewHttpOk == Boolean.FALSE) {
        return false
      }
      String reason = (toolsLoopSessionBundle.toolsLoopPreviewVerificationReason ?: '').toString().trim()
      if (reason in ['http_error', 'http_server_error', 'rendering_error', 'written_copy_not_found', 'literal_outcome_not_found']) {
        return false
      }
    }
    if (lastPreviewContentGoalFound == Boolean.FALSE) {
      return false
    }
    return true
  }

  /** Server wrap-up when write succeeded but preview is not verified — never claim “looks good”. */
  private static String synthesizePlanExecutionAfterWritePendingPreview(String previewUrl, Map toolsLoopSessionBundle) {
    StringBuilder sb = new StringBuilder('## Not done yet\n\n')
    sb.append('Content was saved, but the **site preview is not healthy** yet.\n\n')
    String reason = (toolsLoopSessionBundle?.toolsLoopPreviewVerificationReason ?: '').toString().trim()
    if (reason == 'http_server_error' || reason == 'http_error') {
      sb.append('Preview returned an HTTP error (often **500** when content XML is incomplete). ')
    } else if (reason == 'rendering_error') {
      sb.append('Preview shows a **template rendering error**. ')
    } else {
      sb.append('Preview verification did not pass. ')
    }
    sb.append('Call **GetContent**, restore required fields from **GetContentTypeFormDefinition**, **WriteContent** the full item, then **GetPreviewHtml** before finishing.\n\n')
    if (previewUrl?.trim()) {
      sb.append('[Open preview](').append(previewUrl.trim()).append(')\n')
    }
    return sb.toString()
  }

  /**
   * Promote plan to plan execution if needed.
   * @param text Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
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

  /**
   * When {@code generate_image} ran but no inline image reached the chat strip, correct optimistic LLM summaries.
   */
  private static String appendGenerateImageDeliveryWarningIfNeeded(
    String assistantText,
    Map toolsLoopSessionBundle,
    Map<String, String> mergedGenerateImageUrls
  ) {
    Map tel = (toolsLoopSessionBundle instanceof Map && toolsLoopSessionBundle.intentRecipeRoutingTelemetry instanceof Map) ?
      (Map) toolsLoopSessionBundle.intentRecipeRoutingTelemetry :
      [:]
    if (!'generate_image'.equals(tel.get('recipeId')?.toString()?.trim())) {
      return assistantText ?: ''
    }
    if (mergedGenerateImageUrls != null && !mergedGenerateImageUrls.isEmpty()) {
      return assistantText ?: ''
    }
    String base = (assistantText ?: '').toString()
    if (base.contains('Image generation did not complete') || base.contains('chat image strip is empty')) {
      return base
    }
    return base +
      '\n\n❌ **Image generation did not complete.** The GenerateImage tool did not return a preview URL for the chat image strip. Check studio.log for `CompatibleImageGenerator` (revision `sandbox-http-v2`) and the provider response.\n'
  }

  /**
   * Append preview verification warning if needed.
   * @return Text result, or empty or null when unavailable.
   */
  private static String abbreviatePreviewPhraseForWarning(String s) {
    String t = (s ?: '').trim()
    if (t.length() <= 100) {
      return t
    }
    return t.substring(0, 97) + '…'
  }

  private static String appendPreviewVerificationWarningIfNeeded(
    String assistantText,
    Boolean previewGoalFound,
    String previewGoalPhrase,
    Map toolsLoopSessionBundle = null
  ) {
    if (previewGoalFound != Boolean.FALSE) {
      if (toolsLoopSessionBundle instanceof Map &&
        toolsLoopSessionBundle.toolsLoopPreviewHttpOk == Boolean.FALSE) {
        String status = (toolsLoopSessionBundle.toolsLoopPreviewHttpStatus ?: '').toString().trim()
        String warn =
          '\n\n⚠️ **Preview check:** Preview fetch failed' +
            (status ? " (HTTP ${status})" : '') +
            ' — the page may be broken. Call **GetContent**, fix the full item XML, **WriteContent**, then **GetPreviewHtml** again.\n'
        String base = (assistantText ?: '').toString()
        return base.contains('Preview check:') ? base : base + warn
      }
      return assistantText ?: ''
    }
    String reason = (toolsLoopSessionBundle?.toolsLoopPreviewVerificationReason ?: '').toString().trim()
    String detail = (toolsLoopSessionBundle?.toolsLoopPreviewVerificationDetail ?: '').toString().trim()
    String warn = ''
    if (reason == 'rendering_error') {
      warn =
        '\n\n⚠️ **Preview check:** The preview page has a **rendering error**' +
          (detail ? ' (`' + detail + '`)' : '') +
          '. Fix the template or content before publishing.\n'
    } else if (reason in ['http_error', 'http_server_error']) {
      warn =
        '\n\n⚠️ **Preview check:** Preview fetch failed' +
          (detail ? ' (' + detail + ')' : '') +
          ' — repair content XML and re-fetch preview before finishing.\n'
    } else if (reason == 'written_copy_not_found' || reason == 'literal_outcome_not_found') {
      String snippet = (previewGoalPhrase ?: toolsLoopSessionBundle?.toolsLoopPreviewVerificationPhrase ?: '').toString().trim()
      warn =
        '\n\n⚠️ **Preview check:** Preview HTML does **not** show the copy that was saved' +
          (snippet ? ' (e.g. "' + abbreviatePreviewPhraseForWarning(snippet) + '")' : '') +
          '. Open preview in Studio and confirm before publishing.\n'
    } else if (previewGoalPhrase?.trim()) {
      String phraseForWarn = abbreviatePreviewPhraseForWarning(previewGoalPhrase)
      warn =
        '\n\n⚠️ **Preview check:** Preview HTML did **not** contain **"' +
          phraseForWarn +
          '"**. Open preview in Studio and confirm before publishing.\n'
    } else {
      return assistantText ?: ''
    }
    String base = (assistantText ?: '').toString()
    if (base.contains('Preview check:')) {
      return base
    }
    return base + warn
  }

  /**
   * Choice message has tool calls.
   * @param msg Caller-supplied input.
   * @return True when the check succeeds.
   */
  private static boolean choiceMessageHasToolCalls(Map msg) {
    if (!(msg instanceof Map)) {
      return false
    }
    def tc = msg.get('tool_calls')
    return tc instanceof List && !((List) tc).isEmpty()
  }

  /**
   * Assistant text from choice message map.
   * @param msg Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
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

  /**
   * Truncates truncate tools loop wire tool top level descriptions to a safe maximum length for prompts or logs.
   * @param wireTools Caller-supplied input.
   * @param maxLen Caller-supplied input.
   */
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

  /**
   * Shrink tools loop json schema description strings.
   * @param node Caller-supplied input.
   * @param maxLen Caller-supplied input.
   */
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

  /**
   * Clear tools loop json schema description strings.
   * @param node Caller-supplied input.
   */
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

  /**
   * Cap tools loop wire message contents.
   * @param wireMessages Caller-supplied input.
   * @param maxPerMessage Caller-supplied input.
   */
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
   * and the upstream LLM returns 400. Parse wire JSON and drop {@code temperature} when {@link #modelNeedsNeoChatCompletionWireParams} applies.
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

  /**
   * Chat completion json strip temperature for neo model.
   * @param model Caller-supplied input.
   * @param jsonBody Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
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

  /** {@code main} | {@code verification} | {@code summary} — echoed on tool-progress SSE for UI grouping. */
  private static String pipelineStageForRepoTool(String toolName) {
    return plugins.org.craftercms.aiassistant.studio.engine.policy.ToolsLoopWirePolicyRegistry.pipelineStageForWire(toolName)
  }

  /**
   * Pipeline stage for tools loop chat line.
   * @return Text result, or empty or null when unavailable.
   */
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
   * On HTTP 429 or TCP connect timeout, sleeps with backoff and retries up to two additional attempts.
   */
  private static String httpPostChatCompletionsReadBody(
    String apiKey,
    String jsonBody,
    boolean logFailuresAsWarn = false,
    String wireBaseUrl = null,
    Map toolsLoopSessionBundle = null
  ) {
    jsonBody = chatCompletionsWireBodyApplyNeoTemperaturePolicy(jsonBody)
    final int maxTries = 3
    for (int attempt = 1; attempt <= maxTries; attempt++) {
      try {
        return httpPostChatCompletionsReadBodyOnce(apiKey, jsonBody, logFailuresAsWarn, wireBaseUrl, toolsLoopSessionBundle)
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
        toolsLoopChatBackoffSleep(ms)
      } catch (ResourceAccessException rae) {
        if (!isChatCompletionsConnectTimeout(rae) || attempt >= maxTries) {
          throw rae
        }
        long ms = 2_000L * attempt
        log.warn(
          'Tools-loop chat connect timed out to {}; backing off {} ms then retry {}/{} (check outbound HTTPS / JVM proxy)',
          resolveSyncChatCompletionsUrl(wireBaseUrl),
          ms,
          attempt + 1,
          maxTries
        )
        toolsLoopChatBackoffSleep(ms)
      }
    }
    throw new IllegalStateException('Tools-loop chat: retries exhausted')
  }

  /**
   * Tools loop chat backoff sleep.
   * @param ms Caller-supplied input.
   */
  private static void toolsLoopChatBackoffSleep(long ms) {
    try {
      Thread.sleep(Math.max(0L, ms))
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt()
      throw ie
    }
  }

  /** True when {@link ResourceAccessException} wraps a connect-phase {@link SocketTimeoutException}. */
  private static boolean isChatCompletionsConnectTimeout(Throwable t) {
    Throwable cur = t
    int walk = 0
    while (cur != null && walk++ < 12) {
      if (cur instanceof SocketTimeoutException) {
        String m = (cur.message ?: '').toString().toLowerCase(Locale.ROOT)
        return m.contains('connect')
      }
      cur = cur.cause
    }
    return false
  }

  /**
   * Http post chat completions read body once.
   * @return Text result, or empty or null when unavailable.
   */
  private static String httpPostChatCompletionsReadBodyOnce(
    String apiKey,
    String jsonBody,
    boolean logFailuresAsWarn,
    String wireBaseUrl,
    Map toolsLoopSessionBundle = null
  ) {
    aiAssistantToolWorkerDiagPhase("native_tools_RestClient_POST_/v1/chat/completions stream=false jsonChars=${(jsonBody ?: '').toString().length()}")
    chatCompletionsRestClientBuilder(apiKey, wireBaseUrl, toolsLoopSessionBundle)
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
    if (StudioAiLlmKind.shouldUseAnthropicSimpleCompletion(toolsLoopSessionBundle, model, wireBaseUrl)) {
      aiAssistantToolWorkerDiagPhase(
        phasePfx +
          "simple_completion_Anthropic_POST_/v1/messages model=${model} userMsgChars=${(userText ?: '').toString().length()} readTimeoutMs=${readTimeoutMs}"
      )
      return StudioAiAnthropicSimpleCompletion.assistantText(
        apiKey,
        model,
        systemText,
        userText,
        effMaxOut,
        readTimeoutMs,
        workerPhasePrefix
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
        "simple_completion_RestClient_POST_/v1/chat/completions model=${model} wireJsonChars=${jsonBody.length()} userMsgChars=${(userText ?: '').toString().length()} readTimeoutMs=${readTimeoutMs}"
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
    int readTimeoutEffective = (int) Math.max(60_000, readTimeoutMs as int)
    try {
      def ex = StudioAiSandboxHttp.postBytes(
        URI.create(urlStr),
        jsonBody.getBytes(StandardCharsets.UTF_8),
        MediaType.APPLICATION_JSON_VALUE,
        [
          authorization   : apiKey,
          connectTimeoutMs: 30_000,
          readTimeoutMs   : readTimeoutEffective,
          maxRedirects    : 0,
          ssrfCheck       : false
        ]
      )
      if (ex.errorMessage && !ex.bodyText) {
        throw new IllegalStateException("Tools-loop chat I/O: ${ex.errorMessage}")
      }
      if (aiAssistantPipelineCancelEffective()) {
        aiAssistantToolWorkerDiagPhase(phasePfx + 'simple_completion_skipped_after_request_body_pipeline_cancelled')
        throw new InterruptedException(AIASSISTANT_PIPELINE_CANCELLED)
      }
      aiAssistantToolWorkerDiagPhase(
        phasePfx +
          "simple_completion_awaiting_chat_upstream_response_body model=${model} readTimeoutMs=${readTimeoutEffective}"
      )
      int code = ex.statusCode
      String raw = ex.bodyText ?: ''
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
    } catch (InterruptedException ie) {
      throw ie
    } catch (Throwable t) {
      if (t instanceof IllegalStateException) {
        throw t
      }
      throw new IllegalStateException("Tools-loop simple completion failed: ${t.message ?: t.toString()}", t)
    }
    } finally {
      if (countTranslateItemInflight) {
        AIASSISTANT_TRANSLATE_ITEM_INNER_INFLIGHT.decrementAndGet()
      }
    }
  }

  /**
   * Bounded native-tool loop for intent-routing refine steps (see {@link AuthoringIntentRefineWithTools}).
   * Separate from JVM {@link AuthoringIntentRoutingEngine} prefetch — LLM chooses tools from the refine allowlist.
   *
   * @param maxToolRounds cap on tool rounds (from {@link StudioAiAssistantProjectConfig#intentRecipeRefineMaxToolRounds})
   * @param workerPhasePrefix log label for this refine phase
   * @return map with {@code text} (assistant string), {@code refineToolsRan}, and {@code maxToolRounds}
   */
  static Map runAuthoringIntentRefineNativeToolLoop(
    String apiKey,
    String model,
    String systemText,
    String userText,
    List toolCallbacks,
    String agentId,
    int maxToolRounds,
    String wireBaseUrl,
    Map toolsLoopSessionBundle,
    String workerPhasePrefix
  ) {
    if (aiAssistantPipelineCancelEffective()) {
      throw new InterruptedException(AIASSISTANT_PIPELINE_CANCELLED)
    }
    List<Map> wireMessages = [
      [role: 'system', content: (systemText ?: '').toString()],
      [role: 'user', content: (userText ?: '').toString()]
    ]
    List wireTools = buildWireToolsFromCallbacks(toolCallbacks)
    if (!wireTools) {
      return [text: '', refineToolsRan: false, maxToolRounds: Math.max(0, maxToolRounds)]
    }
    Map<String, FunctionToolCallback> byName = toolCallbacksByName(toolCallbacks)
    int rounds = Math.max(0, maxToolRounds)
    if (rounds <= 0) {
      return [text: '', refineToolsRan: false, maxToolRounds: rounds]
    }
    log.info(
      'AuthoringIntentRefineWithTools: starting native tool loop phase={} agentId={} model={} tools={} maxRounds={}',
      workerPhasePrefix,
      agentId,
      model,
      wireTools.size(),
      rounds
    )
    Map loopOut = runNativeToolLoopToAssistantText(
      apiKey,
      model,
      wireMessages,
      wireTools,
      byName,
      agentId,
      rounds,
      false,
      null,
      null,
      wireBaseUrl,
      toolsLoopSessionBundle,
      null,
      null
    )
    return [
      text           : (loopOut?.text ?: '').toString(),
      refineToolsRan : Boolean.TRUE.equals(loopOut?.toolsRan),
      maxToolRounds  : rounds
    ]
  }

  /** Copies {@code refineToolsTelemetry} from the session bundle into intent-recipe SSE telemetry when present. */
  private static void putRefineToolsTelemetryIfPresent(Map tel, Map toolsLoopSessionBundle) {
    if (!(tel instanceof Map) || !(toolsLoopSessionBundle instanceof Map)) {
      return
    }
    Object rt = toolsLoopSessionBundle.refineToolsTelemetry
    if (rt instanceof Map && !((Map) rt).isEmpty()) {
      tel.put('refineToolsTelemetry', rt)
      tel.put('refineToolsRan', Boolean.TRUE)
    }
  }

  /** Bridge for {@link plugins.org.craftercms.aiassistant.studio.engine.routing.Router}. */
  static String extractAuthorFieldLabelPhraseForRouting(String authorVisible) {
    return extractAuthorFieldLabelPhrase(authorVisible)
  }

  /** Bridge for {@link plugins.org.craftercms.aiassistant.studio.engine.routing.Router}. */
  static boolean authorNeedsExternalContentForRouting(String authorVisible) {
    return authorRequestNeedsExternalContentResolution(authorVisible)
  }

  /** Bridge for {@link plugins.org.craftercms.aiassistant.studio.engine.routing.Router}. */
  static void putRefineToolsTelemetryIfPresentForRouting(Map tel, Map toolsLoopSessionBundle) {
    putRefineToolsTelemetryIfPresent(tel, toolsLoopSessionBundle)
  }

  /**
   * Intent router must reply with JSON only — refine tools would return prose and break
   * {@link AuthoringIntentRecipeRouter#parseRouterJson}.
   *
   * @return raw assistant text from a simple completion (no tool calls)
   */
  private static String intentRouterJsonCompletionOnly(
    String apiKey,
    String model,
    String systemText,
    String userText,
    String wireBaseUrl,
    Map toolsLoopSessionBundle
  ) {
    return toolsLoopSimpleCompletionAssistantText(
      apiKey,
      model,
      systemText,
      userText,
      512,
      120_000,
      'IntentRecipeRouter',
      wireBaseUrl,
      toolsLoopSessionBundle
    )
  }

  /**
   * Intent refine / router LLM call: {@link AuthoringIntentRefineWithTools} when enabled, else simple completion.
   */
  private static String authoringIntentRefineCompletionOrSimple(
    String apiKey,
    String model,
    String systemText,
    String userText,
    int maxOutTokens,
    int readTimeoutMs,
    String workerPhasePrefix,
    String wireBaseUrl,
    Map toolsLoopSessionBundle,
    Map cfg
  ) {
    String refined = AuthoringIntentRefineWithTools.completion(
      apiKey,
      model,
      systemText,
      userText,
      maxOutTokens,
      readTimeoutMs,
      workerPhasePrefix,
      wireBaseUrl,
      toolsLoopSessionBundle,
      cfg
    )
    if (refined) {
      return refined
    }
    return toolsLoopSimpleCompletionAssistantText(
      apiKey,
      model,
      systemText,
      userText,
      maxOutTokens,
      readTimeoutMs,
      workerPhasePrefix,
      wireBaseUrl,
      toolsLoopSessionBundle
    )
  }

  /**
   * Pre-tools intent routing — delegates to {@link plugins.org.craftercms.aiassistant.studio.engine.routing.Router#route}.
   */
  static Map intentRecipeRoutingPrelude(
    String bodyPrompt,
    String userTextAfterGuard,
    String apiKey,
    String model,
    String wireBaseUrl,
    Map toolsLoopSessionBundle,
    StudioToolOperations ops,
    Closure recipePrefetchProgressListener = null
  ) {
    return Router.route(
      bodyPrompt,
      userTextAfterGuard,
      apiKey,
      model,
      wireBaseUrl,
      toolsLoopSessionBundle,
      ops,
      recipePrefetchProgressListener,
      { Map projectCfg, String systemPrompt, String userMessage ->
        return intentRouterJsonCompletionOnly(
          apiKey,
          model,
          systemPrompt,
          userMessage,
          wireBaseUrl,
          toolsLoopSessionBundle
        )
      }
    )
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

  /**
   * Deep clone wire messages.
   * @param src Caller-supplied input.
   * @return List<Map> result.
   */
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

  /**
   * Last user wire message.
   * @param wire Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  private static Map lastUserWireMessage(List<Map> wire) {
    Map last = null
    for (def m : wire) {
      if (m instanceof Map && 'user'.equals(((Map) m).get('role')?.toString())) {
        last = (Map) m
      }
    }
    last
  }

  /**
   * When routing deferred to the plan loop, match **## Plan** steps to catalog recipes and stash hints on the session bundle.
   */
  private static void maybeLogPlanStepDeterministicRecipeMatches(
    List recipes,
    Map toolsLoopSessionBundle,
    String assistantContent
  ) {
    if (!(toolsLoopSessionBundle instanceof Map) || recipes == null || recipes.isEmpty()) {
      return
    }
    Map tel = (Map) toolsLoopSessionBundle.get('intentRecipeRoutingTelemetry')
    if (!(tel instanceof Map) || !Boolean.TRUE.equals(tel.get('deferToPlanLoop'))) {
      return
    }
    List<Map> steps = PlanOrchestration.parseOrchestrationSteps((assistantContent ?: '').toString())
    if (steps.isEmpty()) {
      return
    }
    String wireCand = toolsLoopSessionBundle.intentRecipeRoutingWireCand?.toString() ?: ''
    Map ctx = [cand: wireCand]
    List<Map> hits = AuthoringIntentRecipeCatalog.matchRecipesForPlanSteps(recipes, ctx, steps)
    if (hits.isEmpty()) {
      return
    }
    log.info(
      'Intent recipe routing: plan-step recipe hints (deferToPlanLoop): {}',
      hits.collect { "${it.stepId ?: '?'}:${it.recipeId}" }.join(', ')
    )
    toolsLoopSessionBundle.planStepRecipeMatches = hits
    String stepHints = AuthoringIntentRecipeCatalog.formatPlanStepRecipeHintsWire(hits)
    if (stepHints?.trim()) {
      toolsLoopSessionBundle.planStepRecipeHintsWire = stepHints
    }
  }

  /** One-shot prepend of per-step recipe hints after **## Plan** is parsed on defer-to-plan turns. */
  private static void prependPlanStepRecipeHintsToWireMessages(List<Map> wire, Map toolsLoopSessionBundle) {
    if (!(wire instanceof List) || !(toolsLoopSessionBundle instanceof Map)) {
      return
    }
    String hints = toolsLoopSessionBundle.planStepRecipeHintsWire?.toString()?.trim()
    if (!hints) {
      return
    }
    Map u = lastUserWireMessage(wire)
    if (!(u instanceof Map)) {
      return
    }
    Object content = u.get('content')
    if (content instanceof CharSequence) {
      u.put('content', hints + content.toString())
    } else if (content instanceof List) {
      List parts = new ArrayList((List) content)
      parts.add(0, [type: 'text', text: hints])
      u.put('content', parts)
    }
    toolsLoopSessionBundle.remove('planStepRecipeHintsWire')
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

  /**
   * Wire tools include named tool.
   * @param wireTools Caller-supplied input.
   * @param toolName Caller-supplied input.
   * @return True when the check succeeds.
   */
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

  /** {@code generate_image} chat-only turn: short author message with inline image ref (expanded at SSE emit). */
  private static String synthesizeGenerateImageChatOnlyComplete(
    String authorRequest,
    Map<String, String> imageUrlByToolCallId
  ) {
    String firstId = ''
    if (imageUrlByToolCallId instanceof Map && !imageUrlByToolCallId.isEmpty()) {
      firstId = imageUrlByToolCallId.keySet().iterator().next()?.toString()?.trim() ?: ''
    }
    String ref = firstId ? ChatCompletionsToolWire.STUDIO_AI_INLINE_IMAGE_REF_PREFIX + firstId : ''
    String req = (authorRequest ?: '').replaceAll(/\s+/, ' ').trim()
    StringBuilder sb = new StringBuilder()
    if (req) {
      sb.append('Here is your image: **').append(req.length() > 120 ? req.substring(0, 117) + '…' : req).append('**.\n\n')
    } else {
      sb.append('Here is your generated image.\n\n')
    }
    if (ref) {
      sb.append('![Generated image](').append(ref).append(')\n')
    }
    return sb.toString()
  }

  /**
   * True when this turn is chat-only image generation (no CMS write unless the author asked).
   * Works when {@code generate_image} matched, or when routing failed but the author request is image-only.
   */
  private static boolean isGenerateImageChatOnlyRecipeTurn(Map intentTel, String authorVisible) {
    if (isGenerateImageRecipeMatchedTurn(intentTel)) {
      return true
    }
    String av = authorVisibleFromPromptText(authorVisible ?: '')
    if (!av?.trim()) {
      return false
    }
    String anchor = ''
    if (intentTel instanceof Map) {
      anchor = intentTel.get('anchorPath')?.toString()?.trim() ?:
        intentTel.get('anchoredRepositoryPath')?.toString()?.trim() ?: ''
    }
    if (AuthoringPreviewContext.chatOnlyGenerateImageAuthorRequest(av, anchor)) {
      return true
    }
    if (intentTel instanceof Map && 'generate_image'.equals(intentTel.get('recipeId')?.toString()?.trim())) {
      return AuthoringPreviewContext.authorCurrentRequestLooksLikeImageOnlyGenerate(av) &&
        !AuthoringPreviewContext.authorGenerateImageRequiresPageContextFirst(anchor, av)
    }
    return false
  }

  /** True when {@code fnName} is blocked by recipe allowlist or chat-only generate_image policy. */
  private static boolean toolsLoopRecipeAllowlistBlocksTool(Map intentTel, String fnName, String authorVisible) {
    if (!(intentTel instanceof Map) || !fnName?.trim()) {
      return false
    }
    if (isGenerateImageRecipeMatchedTurn(intentTel) ||
      isGenerateImageChatOnlyRecipeTurn(intentTel, authorVisible)) {
      return !'GenerateImage'.equals(fnName.trim())
    }
    String outcome = intentTel.get('outcome')?.toString() ?: ''
    boolean routerTool = 'router_tool'.equals(outcome) || 'tool'.equals(intentTel.routingMode?.toString())
    if (!'matched'.equals(outcome) && !routerTool) {
      return false
    }
    Object allowObj = intentTel.get('toolsLoopAllowlist')
    if (!(allowObj instanceof List) || ((List) allowObj).isEmpty()) {
      return false
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
      return false
    }
    Set<String> allowNames = new LinkedHashSet<>()
    for (Object o : (List) allowObj) {
      String n = o?.toString()?.trim()
      if (n) {
        allowNames.add(n)
      }
    }
    return !allowNames.isEmpty() && !allowNames.contains(fnName.trim())
  }

  private static boolean skipPostToolReviewForGenerateImageRecipe(Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return false
    }
    Map tel = toolsLoopSessionBundle.intentRecipeRoutingTelemetry instanceof Map ?
      (Map) toolsLoopSessionBundle.intentRecipeRoutingTelemetry :
      null
    return tel != null && 'generate_image'.equals(tel.get('recipeId')?.toString()?.trim())
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
   * Enabled when turn success criteria or a multi-step turn goal is set on the session bundle,
   * unless {@code aiassistant.openai.postToolReviewEnabled} overrides (true/false).
   */
  private static boolean postToolReviewEnabled(Map toolsLoopSessionBundle = null) {
    try {
      def p = StudioAiPlatformSettings.property('aiassistant.openai.postToolReviewEnabled', '')?.trim()
      if (p) {
        if ('false'.equalsIgnoreCase(p) || '0'.equals(p)) {
          return false
        }
        if ('true'.equalsIgnoreCase(p) || '1'.equals(p)) {
          return true
        }
      }
    } catch (Throwable ignored) {}
    if (toolsLoopSessionBundle instanceof Map) {
      String criteria = toolsLoopSessionBundle.authorTurnSuccessCriteria?.toString()?.trim()
      if (criteria) {
        return true
      }
      String goal = toolsLoopSessionBundle.authorTurnGoal?.toString()?.trim()
      String authorVisible = toolsLoopSessionBundle.authorIntentCardAuthorVisible?.toString()?.trim() ?: ''
      if (goal && AuthoringIntentCard.looksMultiStepGoal(goal, authorVisible)) {
        return true
      }
    }
    return false
  }

  /**
   * Elide middle for review.
   * @param s Caller-supplied input.
   * @param maxChars Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
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

  /**
   * Parse post tool review json object.
   * @param assistantText Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
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

  /**
   * Post tool review.
   * @return Map payload for tools or orchestration.
   */
  private static Map postToolReview(
    String apiKey,
    String model,
    String originalUserContent,
    String assistantFinalOutput,
    String agentId,
    OutputStream sseOut = null,
    String turnSuccessCriteria = '',
    String turnGoal = '',
    Map toolsLoopSessionBundle = null
  ) {
    model = resolveChatModel(model?.toString())
    int cap = 120_000
    try {
      def p = StudioAiPlatformSettings.property('aiassistant.openai.reviewMaxChars', '')?.trim()
      if (p) {
        cap = Math.max(8192, Integer.parseInt(p))
      }
    } catch (Throwable ignored) {}
    // Groovy `/` on Integer can yield BigDecimal — elide helper requires int maxChars.
    int halfCap = Math.floorDiv((int) cap, 2)
    String ou = elideMiddleForReview(originalUserContent, halfCap)
    String af = elideMiddleForReview(assistantFinalOutput, halfCap)
    String criteriaBlock = (turnSuccessCriteria ?: '').trim() ?
      ('TURN_SUCCESS_CRITERIA (required bar when judging accomplished):\n' + turnSuccessCriteria.trim() + '\n\n') :
      ''
    String goalBlock = (turnGoal ?: '').trim() ?
      ('TURN_GOAL:\n' + turnGoal.trim() + '\n\n') :
      ''
    String groundingBlock = ''
    if (toolsLoopSessionBundle instanceof Map &&
      Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopExternalLookupRequired)) {
      boolean searchOk = Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopSearchOkThisTurn)
      boolean fetchOk = Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopFetchOkThisTurn)
      boolean usableFact = Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopUsableExternalFact)
      String lastFact = toolsLoopSessionBundle.toolsLoopLastSalientFact?.toString()?.trim() ?: ''
      String retrievedLen = ''
      String excerpt = (toolsLoopSessionBundle.toolsLoopRetrievedSourceExcerpt ?: '').toString().trim()
      if (excerpt) {
        retrievedLen = 'Retrieved source excerpt chars: ' + excerpt.length() + '.\n'
      }
      groundingBlock =
        'TOOL_CHAIN_GROUNDING (this turn required external lookup before writes):\n' +
          'WebSearch ran: ' + searchOk + '; FetchHttpUrl with body read: ' + fetchOk +
          '; retrieved source ready for copy: ' + usableFact + '.\n' +
          retrievedLen +
          (lastFact ? ('Last salient preview: ' + lastFact + '\n') : '') +
          'If writes used live facts but no substantive retrieved source excerpt was available, set accomplished to false.\n' +
          'If page copy is generic marketing filler without specific facts from the retrieved source, set accomplished to false.\n' +
          'If headline-role copy matches the fetched source page title or search result title verbatim, set accomplished to false.\n' +
          'If image-asset fields use invented `/static-assets/…` paths without a successful **GenerateImage** **repositoryPath**, set accomplished to false.\n' +
          'If this turn matched **modify_page_content** (or required **WriteContent**) but **WriteContent** did not succeed, set accomplished to false — describing an update in prose or markdown JSON is not a repository write.\n\n'
    }
    def userBlock = """ORIGINAL_AUTHOR_REQUEST:
${ou}

${goalBlock}${criteriaBlock}${groundingBlock}ASSISTANT_FINAL_OUTPUT:
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
      String raw = httpPostChatCompletionsReadBody(
        apiKey,
        jsonBody,
        true,
        StudioAiLlmKind.toolsLoopChatBaseUrlFromBundle(toolsLoopSessionBundle),
        toolsLoopSessionBundle
      )
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

  /**
   * Builds post review correction user message for tool or orchestration output.
   * @param rev Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String buildPostReviewCorrectionUserMessage(Map rev) {
    def r = (rev?.reason ?: '').toString().trim()
    def c = (rev?.correctionInstructions ?: '').toString().trim()
    return """[Studio — post-execution self-check]
An automated reviewer compared your last reply to the original author request and believes the task may be incomplete.

**Reviewer reason:** ${r ?: '(none)'}

**What you still need to do:**
${c ?: 'Re-read the original request, use tools as needed, and produce a complete answer for the author.'}

Use tools if repository work is still missing. **Do not** stream a new **## Plan** for this follow-up — continue against the **## Plan** and **📋** checklist you already gave the author (run any missing verification tools, then mark those steps). Then write the updated final answer under **## Plan Execution** with the **same** **📋** checklist and final **✅ / ❌ / ⚠️** markers as required by policy."""
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

  /** Server-injected / model-parrot status line — show at most once per tools-loop turn. */
  private static boolean isToolsLoopStatusFillerProse(String raw) {
    String n = planGateNormalizeForScan((raw ?: '').toString().trim())
    return n == 'applying your request with the appropriate tools.' ||
      n == 'applying your request with the appropriate tools'
  }

  private static boolean toolsLoopStatusFillerAlreadyEmitted(Map toolsLoopSessionBundle) {
    return toolsLoopSessionBundle instanceof Map &&
      Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopStatusFillerEmitted)
  }

  private static void markToolsLoopStatusFillerEmitted(Map toolsLoopSessionBundle) {
    if (toolsLoopSessionBundle instanceof Map) {
      toolsLoopSessionBundle.toolsLoopStatusFillerEmitted = Boolean.TRUE
    }
  }

  /**
   * Detects memorized lazy “execute the request / tools …” slop models sometimes quote in {@code [TOOL-GUARD]}.
   * Used only to strip matching lines from streamed assistant text — the native tool loop does **not** block on plan shape.
   */
  private static boolean containsKnownForbiddenMetaPlan(String t) {
    String n = planGateNormalizeForScan(t)
    if (!n) {
      return false
    }
    if (isToolsLoopStatusFillerProse(n)) {
      return true
    }
    if (n.contains('execute the user request using the tools described in the studio authoring system message')) {
      return true
    }
    if (n.contains('execute the user request using the tools described')) {
      return true
    }
    // Singular “tool” variants models sometimes emit.
    if (n.contains('execute the user request using the tool described')) {
      return true
    }
    if (n.contains('using the tool described in the studio authoring system message')) {
      return true
    }
    if (n.contains('using the tools described in the studio authoring system message')) {
      return true
    }
    if (n.contains('execute the user request') && n.contains('tools') && n.contains('system message')) {
      return true
    }
    if (n.contains('execute the user request') && n.contains('tool') && n.contains('system message')) {
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
   * Detects model prose that *describes* tool use ({@code tool_calls} wording or registered tool wire names)
   * without emitting executable tool calls.
   */
  private static boolean looksLikePseudoToolCallNarration(String assistantText, Map<String, FunctionToolCallback> byName) {
    String text = (assistantText ?: '').toString()
    if (!text.trim()) {
      return false
    }
    String lower = text.toLowerCase(Locale.ROOT)
    boolean mentionsToolCalls = lower.contains('tool_calls') || lower.contains('tool calls')
    if (!(byName instanceof Map) || byName.isEmpty()) {
      return mentionsToolCalls
    }
    boolean mentionsKnownTool = false
    for (String wireName : byName.keySet()) {
      String w = (wireName ?: '').toString().trim()
      if (!w) {
        continue
      }
      String quoted = Pattern.quote(w)
      if ((text =~ /(?s)(?:`$quoted`|\b$quoted\b)/).find()) {
        mentionsKnownTool = true
        break
      }
    }
    return mentionsToolCalls || mentionsKnownTool
  }

  /** Author-safe fallback when no tool actually ran but assistant prose listed tool calls. */
  private static String pseudoToolNarrationFallbackMessage() {
    return (
      "I outlined a tool plan, but no tools were actually executed in this turn.\n\n" +
        "If you want me to proceed, reply with **\"proceed\"** and I will run the required tools and report concrete results."
      )
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

  /**
   * Mutate assistant wire content elide known generate image data urls.
   */
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

  /**
   * Synthesize corrupt site item xml message.
   * @param repoPath Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String synthesizeCorruptSiteItemXmlMessage(String repoPath) {
    String p = (repoPath ?: '').trim() ?: '(unknown path)'
    return '## Cannot edit this content item\n\n' +
      'The file **`' + p + '`** in the repository is **not** a complete Crafter content item (missing `<page>` / `<component>` root or required item markers). ' +
      'That often happens after an earlier partial AI write.\n\n' +
      '**Fix in Studio:** open **Git** / history for this file and **revert** to the last good version, then retry your edit.\n'
  }

  private static String synthesizeFormDefinitionWriteRejectionMessage(String repoPath) {
    String p = (repoPath ?: '').trim() ?: '(unknown path)'
    return '## Could not save content\n\n' +
      'Repeated **WriteContent** attempts for **`' + p + '`** were rejected because the generated XML included **field ids not in the content type form definition** ' +
      '(for example invented elements like `orderDefault_f`).\n\n' +
      '**Next step:** retry your request — the assistant should **GetContent** on that path, change **only** existing field elements from the **content field plan**, and **WriteContent** once.\n'
  }

  /**
   * String list from recipe telemetry.
   * @param tel Caller-supplied input.
   * @param key Caller-supplied input.
   * @return List<String> result.
   */
  private static List<String> stringListFromRecipeTelemetry(Map tel, String key) {
    if (!(tel instanceof Map) || !(key?.trim())) {
      return Collections.emptyList()
    }
    Object raw = tel.get(key)
    if (!(raw instanceof List)) {
      return Collections.emptyList()
    }
    List<String> out = []
    for (Object o : (List) raw) {
      String s = o?.toString()?.trim()
      if (s) {
        out.add(s)
      }
    }
    return out
  }

  private static Map<String, Boolean> freshRequiredToolSuccessMap(List<String> requiredWireNames) {
    Map<String, Boolean> out = new LinkedHashMap<>()
    if (!(requiredWireNames instanceof List)) {
      return out
    }
    for (String n : requiredWireNames) {
      if (n?.trim()) {
        out.put(n.trim(), Boolean.FALSE)
      }
    }
    return out
  }

  /** Session bundle survives correction-pass sub-loops; local loop flags do not. */
  private static boolean generateImageAlreadySucceededForTurn(Map toolsLoopSessionBundle) {
    return toolsLoopSessionBundle instanceof Map &&
      Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopGenerateImageOkThisTurn)
  }

  /** Repository paths written this chat turn — shared across main loop and correction pass. */
  private static Set<String> persistedWriteContentRepoPaths(Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return new LinkedHashSet<>()
    }
    Object raw = toolsLoopSessionBundle.get('toolsLoopWriteContentRepoPaths')
    Set<String> paths = new LinkedHashSet<>()
    if (raw instanceof Collection) {
      for (Object o : (Collection) raw) {
        String p = o?.toString()?.trim()?.toLowerCase(Locale.ROOT)
        if (p) {
          paths.add(p)
        }
      }
    }
    toolsLoopSessionBundle.put('toolsLoopWriteContentRepoPaths', paths)
    return paths
  }

  private static void seedRequiredToolSuccessFromSessionBundle(
    Map toolsLoopSessionBundle,
    Map<String, Boolean> requiredToolSuccess
  ) {
    if (!(toolsLoopSessionBundle instanceof Map) || !(requiredToolSuccess instanceof Map)) {
      return
    }
    if (Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopWriteContentOkThisTurn) &&
      requiredToolSuccess.containsKey('WriteContent')) {
      requiredToolSuccess.put('WriteContent', Boolean.TRUE)
    }
    if (Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopGenerateImageOkThisTurn) &&
      requiredToolSuccess.containsKey('GenerateImage')) {
      requiredToolSuccess.put('GenerateImage', Boolean.TRUE)
    }
  }

  /**
   * Research-backed page refresh is done when copy saved and (when expected) hero image generated.
   * Used to skip redundant correction passes that would re-run slow GenerateImage calls.
   */
  private static boolean toolsLoopResearchPageRefreshSubstantiallyComplete(Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return false
    }
    if (!Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopWriteContentOkThisTurn)) {
      return false
    }
    if (!Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopResearchPageRefreshExpectsHeroImage)) {
      return true
    }
    if (toolsLoopSessionBundle.toolsLoopPreviewHttpOk == Boolean.FALSE) {
      return false
    }
    if (toolsLoopSessionBundle.toolsLoopPreviewVerificationFound == Boolean.FALSE) {
      return false
    }
    return Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopGenerateImageOkThisTurn)
  }

  private static void injectPendingWriteContentRecoveryNudge(List<Map> wireMessages, Map toolsLoopSessionBundle) {
    if (!(wireMessages instanceof List) || !(toolsLoopSessionBundle instanceof Map)) {
      return
    }
    String nudge = (toolsLoopSessionBundle.remove('toolsLoopPendingWriteContentRecoveryNudge') ?: '').toString().trim()
    if (!nudge) {
      return
    }
    wireMessages << [role: 'user', content: nudge]
  }

  /**
   * Tools loop required tools still pending.
   * @return True when the check succeeds.
   */
  private static boolean toolsLoopRequiredToolsStillPending(Map<String, Boolean> requiredToolSuccess) {
    if (!(requiredToolSuccess instanceof Map) || requiredToolSuccess.isEmpty()) {
      return false
    }
    for (Boolean ok : requiredToolSuccess.values()) {
      if (!Boolean.TRUE.equals(ok)) {
        return true
      }
    }
    return false
  }

  /**
   * Force {@code FetchHttpUrl} when search succeeded but no substantive retrieved body yet (research-grounded writes).
   */
  private static boolean toolsLoopShouldForceFetchHttpUrlToolChoice(Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return false
    }
    if (!Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopExternalLookupRequired)) {
      return false
    }
    if (AuthoringResearchGrounding.hasSubstantiveRetrievedSource(toolsLoopSessionBundle)) {
      return false
    }
    return Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopSearchOkThisTurn)
  }

  /**
   * Force {@code WriteContent} only when it is required and every other required tool has already succeeded.
   * Defer when external lookup is required but retrieved source text is not on the session yet.
   */
  private static boolean toolsLoopShouldForceWriteContentToolChoice(
    Map<String, Boolean> requiredToolSuccess,
    Map intentTelLoop,
    Map toolsLoopSessionBundle = null
  ) {
    if (createFromChatDraftWriteVerificationActive(intentTelLoop)) {
      if (!requiredToolSuccess.containsKey('WriteContent') ||
        Boolean.TRUE.equals(requiredToolSuccess.get('WriteContent'))) {
        return false
      }
      if (requiredToolSuccess.containsKey('ContentExists')) {
        return Boolean.TRUE.equals(requiredToolSuccess.get('ContentExists'))
      }
      return false
    }
    if (!(requiredToolSuccess instanceof Map) || !requiredToolSuccess.containsKey('WriteContent')) {
      return false
    }
    if (Boolean.TRUE.equals(requiredToolSuccess.get('WriteContent'))) {
      return false
    }
    if (toolsLoopSessionBundle instanceof Map &&
      Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopExternalLookupRequired) &&
      !AuthoringResearchGrounding.hasSubstantiveRetrievedSource(toolsLoopSessionBundle)) {
      return false
    }
    for (Map.Entry entry : requiredToolSuccess.entrySet()) {
      String name = entry.key?.toString()?.trim() ?: ''
      if ('WriteContent'.equals(name)) {
        continue
      }
      // Hero image is sequenced after copy write — do not defer WriteContent force for pending GenerateImage.
      if ('GenerateImage'.equals(name)) {
        continue
      }
      if (!Boolean.TRUE.equals(entry.value)) {
        return false
      }
    }
    return true
  }

  /**
   * Research page refresh: copy must persist before hero image generation.
   */
  private static boolean shouldBlockGenerateImageUntilCopyWrite(Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return false
    }
    if (!Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopResearchPageRefreshExpectsHeroImage)) {
      return false
    }
    return !Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopWriteContentOkThisTurn)
  }

  /**
   * Force {@code GenerateImage} after copy {@code WriteContent} on research-backed page refreshes with image-asset fields.
   */
  private static boolean toolsLoopShouldForceGenerateImageToolChoice(
    Map<String, Boolean> requiredToolSuccess,
    Map toolsLoopSessionBundle = null
  ) {
    if (!(requiredToolSuccess instanceof Map) || !(toolsLoopSessionBundle instanceof Map)) {
      return false
    }
    if (!requiredToolSuccess.containsKey('GenerateImage')) {
      return false
    }
    if (Boolean.TRUE.equals(requiredToolSuccess.get('GenerateImage'))) {
      return false
    }
    if (generateImageAlreadySucceededForTurn(toolsLoopSessionBundle)) {
      return false
    }
    if (!Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopResearchPageRefreshExpectsHeroImage)) {
      return false
    }
    if (requiredToolSuccess.containsKey('WriteContent') &&
      !Boolean.TRUE.equals(requiredToolSuccess.get('WriteContent'))) {
      return false
    }
    if (!AuthoringResearchGrounding.hasSubstantiveRetrievedSource(toolsLoopSessionBundle)) {
      return false
    }
    return true
  }

  private static void augmentRequiredToolsForResearchPageRefresh(
    Map toolsLoopSessionBundle,
    Map<String, Boolean> requiredToolSuccess,
    String authorVisible
  ) {
    if (!(toolsLoopSessionBundle instanceof Map) || !(requiredToolSuccess instanceof Map)) {
      return
    }
    AuthoringResearchGrounding.refreshResearchHeroImageExpectation(toolsLoopSessionBundle, authorVisible ?: '')
    if (!Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopResearchPageRefreshExpectsHeroImage)) {
      return
    }
    if (generateImageAlreadySucceededForTurn(toolsLoopSessionBundle)) {
      if (requiredToolSuccess.containsKey('GenerateImage')) {
        requiredToolSuccess.put('GenerateImage', Boolean.TRUE)
      }
      return
    }
    if (!requiredToolSuccess.containsKey('GenerateImage')) {
      requiredToolSuccess.put('GenerateImage', Boolean.FALSE)
    }
  }

  /**
   * Create from chat draft tools loop fast path.
   * @param intentTelLoop Caller-supplied input.
   * @return True when the check succeeds.
   */
  private static boolean createFromChatDraftToolsLoopFastPath(Map intentTelLoop) {
    return intentTelLoop instanceof Map &&
      Boolean.TRUE.equals(intentTelLoop.get('toolsLoopFastPath'))
  }

  /**
   * {@code new_content_item} prefetch supplement active (form discovery + path hints before write).
   */
  private static boolean newContentItemPrefetchSupplementActive(Map intentTelLoop) {
    if (!(intentTelLoop instanceof Map)) {
      return false
    }
    String supplement = intentTelLoop.get('toolsLoopPrefetchSupplement')?.toString()?.trim() ?: ''
    if ('newContentItem'.equals(supplement) || 'createFromChatDraft'.equals(supplement)) {
      return true
    }
    String rid = intentTelLoop.get('recipeId')?.toString()?.trim() ?: ''
    if ('new_content_item'.equals(rid)) {
      return true
    }
    return 'new_content_item_from_chat_draft'.equals(rid) &&
      createFromChatDraftWriteVerificationActive(intentTelLoop)
  }

  private static boolean writeContentXmlLooksLikePlaceholder(String contentXml) {
    return plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.NewContentItemFastPathToolFilter
      .contentXmlLooksLikePlaceholder(contentXml)
  }

  private static Map newContentItemFormValidationPlan(Map intentTelLoop) {
    if (!(intentTelLoop instanceof Map)) {
      return [:]
    }
    Object plan = intentTelLoop.get('toolsLoopFormDefinitionValidationPlan')
    return plan instanceof Map ? (Map) plan : [:]
  }

  private static String formatFormValidationRejectionMessage(Map validation, Map validationPlan) {
    List<String> errors = []
    Object errObj = validation?.get('errors')
    if (errObj instanceof List) {
      for (Object o : (List) errObj) {
        String e = o?.toString()?.trim()
        if (e) {
          errors.add(e)
        }
      }
    }
    List<String> required = []
    Object reqObj = validation?.get('requiredFieldIds') ?: validationPlan?.get('requiredFieldIds')
    if (reqObj instanceof List) {
      for (Object o : (List) reqObj) {
        String r = o?.toString()?.trim()
        if (r) {
          required.add(r)
        }
      }
    }
    StringBuilder msg = new StringBuilder(
      'WriteContent **rejected** — **contentXml** does not satisfy the form definition'
    )
    if (errors) {
      msg.append(': ').append(errors.join(' '))
    }
    msg.append('.')
    if (required) {
      msg.append(' **Required fields:** `').append(required.join('`, `')).append('`.')
    }
    if (plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.FormDefinitionWriteContentValidator
      .planIsActionable(validationPlan)) {
      msg.append(
        ' Use the **formValidationPlan** / prefetch **requiredFieldIds** already on this turn — populate every required element in **contentXml**, then **WriteContent** again. Do **not** call **GetContentTypeFormDefinition** again for the same **contentTypeId**.'
      )
    } else {
      msg.append(
        ' Call **GetContentTypeFormDefinition** for the resolved **contentTypeId** (and nested types), populate every required field and minSize collection, then **WriteContent** again.'
      )
    }
    return msg.toString()
  }

  private static String formatCreateFromChatDraftWriteRejectionNudge(
    Map intentTelLoop,
    String repoPath,
    int invalidFailures,
    int repeatedRejections
  ) {
    if (!(intentTelLoop instanceof Map) || !createFromChatDraftWriteVerificationActive(intentTelLoop)) {
      return ''
    }
    if (invalidFailures < 1) {
      return ''
    }
    Map validationPlan = newContentItemFormValidationPlan(intentTelLoop)
    List<String> required = []
    Object reqObj = validationPlan?.get('requiredFieldIds')
    if (reqObj instanceof List) {
      for (Object o : (List) reqObj) {
        String r = o?.toString()?.trim()
        if (r) {
          required.add(r)
        }
      }
    }
    String path = (repoPath ?: intentTelLoop.get('toolsLoopSuggestedNewItemPath') ?: '').toString().trim()
    StringBuilder sb = new StringBuilder()
    sb.append('[aiassistant: create-from-chat-draft WriteContent — internal]\n')
    sb.append('**WriteContent** failed form validation')
    if (path) {
      sb.append(' for `').append(path).append('`')
    }
    sb.append('. ')
    if (required) {
      sb.append('Include **every** required field in **contentXml**: `')
        .append(required.join('`, `'))
        .append('`. ')
    }
    sb.append(
      'Map title/body from **[Prior conversation]** (last **Assistant** reply). ' +
        'Do **not** call **GetContentTypeFormDefinition** again — use prefetch **formValidationPlan**. ' +
        'Retry **WriteContent** once with a complete `<page>` document.'
    )
    if (repeatedRejections >= 2) {
      sb.append(' Same rejection repeated — double-check missing fields (e.g. `topic_s`, `date_dt`, `metaDescription`).')
    }
    sb.append('\n')
    return sb.toString()
  }

  /**
   * Block placeholder {@code contentXml} on new-item create flows.
   */
  /**
   * Recipe writeVerification repairs apply only when creating a new repository item.
   */
  private static boolean writeContentPathIsNewItem(StudioToolOperations ops, String siteId, String repoPath) {
    if (!ops || !(repoPath ?: '').toString().trim()) {
      return true
    }
    return !plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsContentExists
      .existsAtPath(ops, siteId, repoPath)
  }

  private static Map gateNewContentItemWriteContent(
    String argsStr,
    Map intentTelLoop,
    JsonSlurper slurper,
    StudioToolOperations ops
  ) {
    if (!newContentItemPrefetchSupplementActive(intentTelLoop)) {
      return [proceed: true, argsStr: argsStr]
    }
    try {
      Object parsed = slurper.parseText(argsStr ?: '{}')
      if (!(parsed instanceof Map)) {
        return [proceed: true, argsStr: argsStr]
      }
      Map args = (Map) parsed
      String contentXml = args.get('contentXml')?.toString()
      if (writeContentXmlLooksLikePlaceholder(contentXml)) {
        Map validationPlan = newContentItemFormValidationPlan(intentTelLoop)
        List requiredHint = validationPlan.requiredFieldIds instanceof List ?
          (List) validationPlan.requiredFieldIds : []
        String requiredSuffix = requiredHint ?
          " Required fields: `${requiredHint.join('`, `')}`." : ''
        return [
          proceed : false,
          toolOut : JsonOutput.toJson([
            ok      : false,
            message :
              'WriteContent **rejected** — **contentXml** must be a **full** Crafter `<page>` or `<component>` document with real field values, not a placeholder (`NEW_CONTENT_XML_HERE`) or prose/JSON blob.' +
                requiredSuffix +
                ' Build from **GetContentTypeFormDefinition** (parent + nested types) and **Project authoring context**, then **WriteContent** again.',
            requiredFieldIds: requiredHint,
            nextStep: 'Build complete contentXml from form definitions + project authoring context + author copy; retry WriteContent via tool_calls.'
          ])
        ]
      }
      Map validationPlan = newContentItemFormValidationPlan(intentTelLoop)
      if (plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.FormDefinitionWriteContentValidator
        .planIsActionable(validationPlan)) {
        String siteId = (args.get('siteId') ?: '').toString()
        String repoPath = (args.get('path') ?: args.get('contentPath') ?: '').toString().trim()
        if (repoPath.contains('{') || repoPath.contains('}')) {
          return [
            proceed : false,
            toolOut : JsonOutput.toJson([
              ok      : false,
              message :
                "WriteContent **rejected** — path `${repoPath}` contains unresolved placeholders (`{slug}`, `{year}`, etc.). " +
                  'Use the **concrete suggested path** from prefetch (e.g. `/site/website/articles/2026/05/my-slug/index.xml`).',
              nextStep: 'Retry WriteContent with the resolved repository path and complete contentXml.'
            ])
          ]
        }
        if (ops && contentXml?.trim() && repoPath) {
          String siteIdForRepair = (args.get('siteId') ?: '').toString().trim()
          if (!siteIdForRepair) {
            siteIdForRepair = ops.resolveEffectiveSiteId('')
          }
          if (createFromChatDraftWriteVerificationActive(intentTelLoop) &&
            writeContentPathIsNewItem(ops, siteIdForRepair, repoPath)) {
            Map verificationConfig = createFromChatDraftWriteVerificationConfig(intentTelLoop)
            String recipeRepaired = plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.ToolsLoopWriteVerification
              .repairContentXmlForWrite(ops, siteIdForRepair, repoPath, contentXml, verificationConfig)
            if (recipeRepaired?.trim() && !recipeRepaired.equals(contentXml)) {
              contentXml = recipeRepaired
              args.put('contentXml', contentXml)
              argsStr = JsonOutput.toJson(args)
            }
          }
          String enriched = plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsWriteContent
            .enrichContentXmlBeforeFormValidation(ops, siteIdForRepair, repoPath, contentXml)
          if (enriched && !enriched.equals(contentXml)) {
            contentXml = enriched
            args.put('contentXml', contentXml)
            argsStr = JsonOutput.toJson(args)
          }
        }
        Map validation = plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.FormDefinitionWriteContentValidator
          .validate(contentXml, validationPlan, repoPath) as Map
        if (!Boolean.TRUE.equals(validation?.get('ok'))) {
          return [
            proceed : false,
            toolOut : JsonOutput.toJson([
              ok               : false,
              message          : formatFormValidationRejectionMessage(validation, validationPlan),
              errors           : validation?.get('errors'),
              requiredFieldIds : validation?.get('requiredFieldIds') ?: validationPlan?.get('requiredFieldIds'),
              formFieldIds     : validation?.get('formFieldIds') ?: validationPlan?.get('formFieldIds'),
              nextStep         : 'Populate every requiredFieldIds entry and minSize collections; retry WriteContent via tool_calls.'
            ])
          ]
        }
      }
    } catch (Throwable gateEx) {
      log.warn('Tools-loop: new-content-item WriteContent gate error: {}', gateEx.message)
      return [
        proceed : false,
        toolOut : JsonOutput.toJson([
          ok      : false,
          message : "WriteContent **rejected** — could not validate contentXml: ${gateEx.message}",
          nextStep: 'Call GetContentTypeFormDefinition, build complete contentXml, retry WriteContent.'
        ])
      ]
    }
    return [proceed: true, argsStr: argsStr]
  }

  /**
   * Pre-enrich every {@code WriteContent} payload (existing and new items) so invented root elements
   * are stripped and baseline merge runs before the tool executes.
   */
  private static Map gateWriteContentPreEnrich(
    String argsStr,
    StudioToolOperations ops,
    JsonSlurper slurper
  ) {
    if (!ops) {
      return [proceed: true, argsStr: argsStr]
    }
    try {
      Object parsed = slurper.parseText(argsStr ?: '{}')
      if (!(parsed instanceof Map)) {
        return [proceed: true, argsStr: argsStr]
      }
      Map args = (Map) parsed
      String contentXml = args.get('contentXml')?.toString()
      String repoPath = repoPathFromToolArgsMap(args)
      if (!contentXml?.trim() || !repoPath?.trim()) {
        return [proceed: true, argsStr: argsStr]
      }
      String siteId = (args.get('siteId') ?: '').toString().trim()
      if (!siteId) {
        siteId = ops.resolveEffectiveSiteId('')
      }
      String enriched = plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsWriteContent
        .enrichContentXmlBeforeFormValidation(ops, siteId, repoPath, contentXml)
      if (enriched?.trim() && !enriched.equals(contentXml)) {
        args.put('contentXml', enriched)
        return [proceed: true, argsStr: JsonOutput.toJson(args)]
      }
    } catch (Throwable t) {
      log.debug('Tools-loop: WriteContent pre-enrich skipped: {}', t.message)
    }
    return [proceed: true, argsStr: argsStr]
  }

  private static boolean toolsLoopSkipDiscoveryUntilWriteContent(
    String fnName,
    Map intentTelLoop,
    Map<String, Boolean> requiredToolSuccess
  ) {
    boolean newItemFast = newContentItemPrefetchSupplementActive(intentTelLoop) &&
      (Boolean.TRUE.equals(intentTelLoop?.get('toolsLoopFastPath')) ||
        Boolean.TRUE.equals(intentTelLoop?.get('toolsLoopFormDefsPrefetched')))
    if (!createFromChatDraftWriteVerificationActive(intentTelLoop) && !newItemFast) {
      return false
    }
    if (createFromChatDraftWriteVerificationActive(intentTelLoop) &&
      !createFromChatDraftToolsLoopFastPath(intentTelLoop) &&
      !Boolean.TRUE.equals(intentTelLoop?.get('toolsLoopFormDefsPrefetched'))) {
      return false
    }
    if (!toolsLoopRequiredToolsStillPending(requiredToolSuccess)) {
      return false
    }
    if (!requiredToolSuccess.containsKey('WriteContent') ||
      Boolean.TRUE.equals(requiredToolSuccess.get('WriteContent'))) {
      return false
    }
    String n = (fnName ?: '').trim()
    if ('GetContentTypeFormDefinition'.equals(n)) {
      return Boolean.TRUE.equals(intentTelLoop?.get('toolsLoopFormDefsPrefetched'))
    }
    return [
      'ListStudioContentTypes',
      'GetContentTypeFormDefinition',
      'GetContent',
      'ContentExists',
      'ResearchSiteContent',
      'ListPagesAndComponents'
    ].contains(n)
  }

  /**
   * Human-readable scope for tool-progress lines (repository path, content type id, search query, etc.).
   */
  private static String toolProgressContextLabel(String toolName, Map input) {
    Map inp = input instanceof Map ? input : [:]
    String tn = (toolName ?: '').toString().trim()
    if ('GetContentTypeFormDefinition'.equalsIgnoreCase(tn)) {
      String contentTypeId = (inp.contentTypeId ?: '').toString().trim()
      String contentPath = (inp.contentPath ?: '').toString().trim()
      if (contentTypeId && contentPath) {
        return contentTypeId + ' · ' + contentPath
      }
      if (contentTypeId) {
        return contentTypeId
      }
      if (contentPath) {
        return contentPath
      }
    }
    String path = (inp.path ?: inp.contentPath ?: inp.contentTypeId ?: inp.templatePath ?: inp.contentType ?: inp.url ?: inp.previewUrl ?: '')
      ?.toString()?.trim() ?: ''
    return path
  }

  /**
   * Tool progress input from args json.
   * @param argsStr Caller-supplied input.
   * @param slurper Caller-supplied input.
   * @param toolName Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  private static Map toolProgressInputFromArgsJson(String argsStr, JsonSlurper slurper, String toolName = null) {
    try {
      Object parsed = slurper.parseText(argsStr ?: '{}')
      if (parsed instanceof Map) {
        Map inp = new LinkedHashMap<>((Map) parsed)
        String label = toolProgressContextLabel((toolName ?: '').toString(), inp)
        if (label && !inp.path) {
          inp.path = label
        }
        String repoPath = repoPathFromToolArgsMap((Map) parsed)
        if (repoPath && !inp.contentPath && !inp.path) {
          inp.path = repoPath
        }
        return inp
      }
    } catch (Throwable ignored) {
    }
    return [:]
  }

  /**
   * Create from chat draft write verification active.
   * @param intentTelLoop Caller-supplied input.
   * @return True when the check succeeds.
   */
  private static boolean createFromChatDraftWriteVerificationActive(Map intentTelLoop) {
    if (!(intentTelLoop instanceof Map)) {
      return false
    }
    String verificationId = intentTelLoop.get('toolsLoopWriteVerification')?.toString()?.trim() ?: ''
    if (plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.ToolsLoopWriteVerification.isActiveVerificationId(verificationId)) {
      return true
    }
    String supplement = intentTelLoop.get('toolsLoopPrefetchSupplement')?.toString()?.trim() ?: ''
    return 'createFromChatDraft'.equals(supplement)
  }

  /**
   * Create from chat draft write verification config.
   * @param intentTelLoop Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  private static Map createFromChatDraftWriteVerificationConfig(Map intentTelLoop) {
    if (!(intentTelLoop instanceof Map)) {
      return [:]
    }
    Object cfg = intentTelLoop.get('toolsLoopWriteVerificationConfig')
    Map out = cfg instanceof Map ? new LinkedHashMap<>((Map) cfg) : [:]
    String priorLabel = (intentTelLoop.get('toolsLoopPriorAuthorLabel') ?: '').toString().trim()
    if (priorLabel) {
      out.put('_prefetchPriorAuthorLabel', priorLabel)
    }
    String draftTitle = (intentTelLoop.get('toolsLoopDraftTitleFromPrior') ?: '').toString().trim()
    if (draftTitle) {
      out.put('_prefetchDraftTitle', draftTitle)
    }
    Object derived = intentTelLoop.get('toolsLoopPriorDerivedRootFieldValues')
    if (derived instanceof Map && !((Map) derived).isEmpty()) {
      out.put('_prefetchPriorDerivedRootFieldValues', new LinkedHashMap<>((Map) derived))
    }
    Object nodeCands = intentTelLoop.get('toolsLoopNodeSelectorCandidates')
    if (nodeCands instanceof List && !((List) nodeCands).isEmpty()) {
      out.put('_prefetchNodeSelectorCandidates', new ArrayList<>((List) nodeCands))
    }
    String siblingPath = (intentTelLoop.get('toolsLoopSiblingTemplatePath') ?: '').toString().trim()
    if (siblingPath) {
      out.put('_siblingRepositoryPath', siblingPath)
    }
    return out
  }

  /**
   * Recipe {@code toolsLoopWriteVerification}: repair UUIDs/dates/images, validate completeness, block bad writes.
   * @return {@code proceed:true, argsStr} or {@code proceed:false, toolOut}
   */
  private static Map gateCreateFromChatDraftWriteContent(
    String argsStr,
    Map intentTelLoop,
    StudioToolOperations ops,
    JsonSlurper slurper
  ) {
    if (!createFromChatDraftWriteVerificationActive(intentTelLoop) || ops == null) {
      return [proceed: true, argsStr: argsStr]
    }
    Map verificationConfig = createFromChatDraftWriteVerificationConfig(intentTelLoop)
    if (verificationConfig.isEmpty()) {
      return [
        proceed : false,
        toolOut : JsonOutput.toJson([
          ok                      : false,
          writeVerificationFailed : true,
          message                 :
            'WriteContent **rejected** — create-from-chat-draft requires a non-empty **writeVerification** map on the matched intent recipe. ' +
              'Deploy site `intent-recipes.json` (see plugin docs example) and reload the AI Assistant plugin.',
          nextStep                :
            'Sync site intent-recipes for this workflow, ensure `writeVerification` is present, reload plugin, then retry WriteContent.'
        ])
      ]
    }
    try {
      Object parsed = slurper.parseText(argsStr ?: '{}')
      if (!(parsed instanceof Map)) {
        return [proceed: true, argsStr: argsStr]
      }
      Map args = (Map) parsed
      String path = repoPathFromToolArgsMap(args)
      String contentXml = args.get('contentXml')?.toString()
      if (!path?.trim() || !contentXml?.trim()) {
        return [proceed: true, argsStr: argsStr]
      }
      Map validationPlan = newContentItemFormValidationPlan(intentTelLoop)
      if (plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.FormDefinitionWriteContentValidator
        .planIsActionable(validationPlan)) {
        String formSiteId = args.get('siteId')?.toString()?.trim()
        if (!formSiteId) {
          formSiteId = ops.resolveEffectiveSiteId('')
        }
        if (ops && contentXml?.trim() && path?.trim()) {
          if (writeContentPathIsNewItem(ops, formSiteId, path)) {
            String recipeRepaired = plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.ToolsLoopWriteVerification
              .repairContentXmlForWrite(ops, formSiteId, path, contentXml, verificationConfig)
            if (recipeRepaired?.trim() && !recipeRepaired.equals(contentXml)) {
              contentXml = recipeRepaired
              args.put('contentXml', contentXml)
              argsStr = JsonOutput.toJson(args)
            }
          }
          String enriched = plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsWriteContent
            .enrichContentXmlBeforeFormValidation(ops, formSiteId, path, contentXml)
          if (enriched && !enriched.equals(contentXml)) {
            contentXml = enriched
            args.put('contentXml', contentXml)
            argsStr = JsonOutput.toJson(args)
          }
        }
        Map formValidation = plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.FormDefinitionWriteContentValidator
          .validate(contentXml, validationPlan, path) as Map
        if (!Boolean.TRUE.equals(formValidation?.get('ok'))) {
          return [
            proceed : false,
            toolOut : JsonOutput.toJson([
              ok               : false,
              message          : formatFormValidationRejectionMessage(formValidation, validationPlan),
              errors           : formValidation?.get('errors'),
              requiredFieldIds : formValidation?.get('requiredFieldIds') ?: validationPlan?.get('requiredFieldIds'),
              nextStep         : 'Populate every requiredFieldIds entry and minSize collections; retry WriteContent via tool_calls.'
            ])
          ]
        }
      }
      String siteId = args.get('siteId')?.toString()?.trim()
      if (!siteId) {
        siteId = ops.resolveEffectiveSiteId('')
      }
      Map prep = plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.ToolsLoopWriteVerification.verifyAndPrepare(
        ops,
        siteId,
        path,
        contentXml,
        verificationConfig
      ) as Map
      if (Boolean.TRUE.equals(prep.get('ok'))) {
        String repaired = (prep.get('contentXml') ?: '').toString()
        if (repaired && !repaired.equals(contentXml)) {
          args.put('contentXml', repaired)
          return [proceed: true, argsStr: JsonOutput.toJson(args)]
        }
        return [proceed: true, argsStr: argsStr]
      }
      List<String> errors = []
      Object errObj = prep.get('errors')
      if (errObj instanceof List) {
        for (Object o : (List) errObj) {
          String e = o?.toString()?.trim()
          if (e) {
            errors.add(e)
          }
        }
      }
      if (errors.isEmpty()) {
        errors.add('WriteContent rejected: incomplete contentXml for create-from-chat-draft (write verification).')
      }
      StringBuilder msg = new StringBuilder()
      msg.append('WriteContent **rejected** — fix every issue below, then call **WriteContent** again with corrected **contentXml**. ')
      msg.append('Title and body must still come from **[Prior conversation]** verbatim (server does not rewrite draft text).\n')
      errors.eachWithIndex { String line, int i ->
        msg.append('\n').append(i + 1).append('. ').append(line)
      }
      Object repairs = prep.get('repairs')
      if (repairs instanceof List && !((List) repairs).isEmpty()) {
        msg.append('\n\n_Server applied repairs before validation but the document was still incomplete._')
      }
      return [
        proceed : false,
        toolOut : JsonOutput.toJson([
          ok                      : false,
          writeVerificationFailed : true,
          path                    : path,
          message                 : msg.toString(),
          errors                  : errors,
          nextStep                :
            'Fix contentXml per recipe writeVerification errors and project authoring context, then re-call WriteContent — do not finish in prose only.'
        ])
      ]
    } catch (Throwable t) {
      log.error('toolsLoop write verification threw (blocking write): {}', t.message, t)
      return [
        proceed : false,
        toolOut : JsonOutput.toJson([
          ok                      : false,
          writeVerificationFailed : true,
          message                 :
            'WriteContent rejected: server write verification failed unexpectedly. ' +
              'Reload the AI Assistant plugin and ensure site intent-recipes writeVerification is configured.',
          error                   : t.message ?: t.toString()
        ])
      ]
    }
  }

  /**
   * When create-from-chat-draft sees {@code exists:true}, the model must not treat discovery as done.
   */
  private static String augmentContentExistsWireForCreateFromChatDraft(
    String toolWire,
    String toolOutRaw,
    Map intentTelLoop,
    JsonSlurper slurper
  ) {
    if (!(intentTelLoop instanceof Map)) {
      return toolWire ?: ''
    }
    String rid = intentTelLoop.get('recipeId')?.toString()?.trim() ?: ''
    String supplement = intentTelLoop.get('toolsLoopPrefetchSupplement')?.toString()?.trim() ?: ''
    boolean createDraftFlow = 'createFromChatDraft'.equals(supplement) ||
      createFromChatDraftWriteVerificationActive(intentTelLoop)
    if (!createDraftFlow) {
      return toolWire ?: ''
    }
    try {
      def parsed = slurper.parseText((toolOutRaw ?: '').toString())
      if (parsed instanceof Map && Boolean.TRUE.equals(((Map) parsed).get('exists'))) {
        StringBuilder extra = new StringBuilder()
        extra.append('\n\n[Studio — create-from-draft: **exists=true** only means this path is already in git. ')
        extra.append('Pick a **different** slug/path for a new post, or stop if the author asked to update that file. ')
        extra.append('Discovery is **not** complete — you **must** still call **WriteContent** with full **contentXml** ')
        extra.append('for the new path you choose.')
        String checkedPath = ((Map) parsed).get('path')?.toString()?.trim() ?: ''
        String suggested = (intentTelLoop?.toolsLoopSuggestedNewItemPath ?: '').toString().trim()
        if (checkedPath && suggested && checkedPath.equalsIgnoreCase(suggested)) {
          String alt = plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsRepositorySupport
            .suggestAlternateRepositoryPath(suggested)
          if (alt && !alt.equalsIgnoreCase(suggested)) {
            extra.append(' Suggested alternate: `').append(alt).append('`.')
          }
        }
        extra.append(']\n')
        return (toolWire ?: '') + extra.toString()
      }
    } catch (Throwable ignoredExistsAugment) {
    }
    return toolWire ?: ''
  }

  /**
   * Repo path on tools loop banned list.
   * @param path Studio or repository context for this call.
   * @param bannedPaths Caller-supplied input.
   * @return True when the check succeeds.
   */
  private static boolean repoPathOnToolsLoopBannedList(String path, List<String> bannedPaths) {
    String p = (path ?: '').toString().trim()
    if (!p || !(bannedPaths instanceof List)) {
      return false
    }
    for (String banned : bannedPaths) {
      if (p.equalsIgnoreCase((banned ?: '').toString().trim())) {
        return true
      }
    }
    return false
  }

  /**
   * Builds tools loop stall guard user message for tool or orchestration output.
   * @param toolsLoopSessionBundle Caller-supplied input.
   * @param consecutiveToolOnlyRounds Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String buildToolsLoopStallGuardUserMessage(Map toolsLoopSessionBundle, int consecutiveToolOnlyRounds) {
    Map tel = (toolsLoopSessionBundle instanceof Map && toolsLoopSessionBundle.intentRecipeRoutingTelemetry instanceof Map) ?
      (Map) toolsLoopSessionBundle.intentRecipeRoutingTelemetry :
      null
    StringBuilder sb = new StringBuilder()
    sb.append('[aiassistant: tools-loop stall guard — internal]\n')
    sb.append('You have completed **').append(consecutiveToolOnlyRounds).append('** tool rounds without a **final** author-visible answer (no closing prose / **## Plan Execution**).\n')
    sb.append('**Stop** open-ended discovery. Either finish the author’s outcome in **this** turn (no more **tool_calls**), or call **only** the minimum tools still required.\n')
    String requiredHint = AuthoringIntentRecipeCatalog.formatToolsLoopRequiredToolsStallHint(tel)
    if (requiredHint?.trim()) {
      sb.append(requiredHint)
    }
    if (toolsLoopSessionBundle instanceof Map &&
      !Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopWriteContentOkThisTurn)) {
      sb.append(
        '\n**WriteContent** has not saved yet — call it with full page XML and fixed **original-headline** copy. ' +
          'Do not search again or generate images until the write succeeds.\n'
      )
    }
    sb.append('Respond with **## Plan Execution** when repository work is done or explain the blocker in plain language.\n')
    sb.toString()
  }

  /**
   * Synthesize tools loop stall exceeded message.
   * @return Text result, or empty or null when unavailable.
   */
  private static String synthesizeToolsLoopStallExceededMessage(
    int lastRound,
    int maxRounds,
    Map toolsLoopSessionBundle,
    int consecutiveToolOnlyRounds
  ) {
    Map tel = (toolsLoopSessionBundle instanceof Map && toolsLoopSessionBundle.intentRecipeRoutingTelemetry instanceof Map) ?
      (Map) toolsLoopSessionBundle.intentRecipeRoutingTelemetry :
      null
    List<String> required = stringListFromRecipeTelemetry(tel, 'toolsLoopRequireSuccessfulTools')
    if (!required.isEmpty()) {
      return AuthoringIntentRecipeCatalog.formatToolsLoopRequiredToolsMissedMessage(tel) +
        '\n_Round ' + (lastRound + 1) + ' of ' + maxRounds + ' (stall limit)._ \n'
    }
    StringBuilder sb = new StringBuilder()
    sb.append('## Plan Execution\n\n')
    sb.append('⚠️ **Stopped:** the assistant used **').append(consecutiveToolOnlyRounds)
      .append('** consecutive tool rounds without finishing (limit **').append(maxRounds).append('**).\n\n')
    sb.append('The model kept calling tools without producing a final answer. Retry with a narrower request, or check server logs for repeated blocked/skipped tool results.\n\n')
    sb.append('_Round ').append(lastRound + 1).append(' of ').append(maxRounds).append('._\n')
    sb.toString()
  }

  /**
   * Tool wire indicates invalid site item document.
   * @param toolWireJson Caller-supplied input.
   * @return True when the check succeeds.
   */
  private static boolean toolWireIndicatesInvalidSiteItemDocument(String toolWireJson) {
    String s = (toolWireJson ?: '').toString()
    return s.contains('field fragment') ||
      s.contains('root <page> or <component>') ||
      s.contains('missing typical Crafter item markers') ||
      s.contains('non-compliant with form definition') ||
      s.contains('Unknown element(s) not in form definition')
  }

  /**
   * Stable signature for repeated form-definition WriteContent rejections (stall guard).
   */
  private static String writeContentFormRejectionSignature(String toolWireJson) {
    String s = (toolWireJson ?: '').toString()
    if (!s.contains('non-compliant with form definition') &&
      !s.contains('Unknown element(s) not in form definition')) {
      return ''
    }
    int idx = s.indexOf('Unknown element(s) not in form definition:')
    if (idx >= 0) {
      return s.substring(idx, Math.min(idx + 120, s.length())).trim()
    }
    idx = s.indexOf('WriteContent rejected')
    if (idx >= 0) {
      return s.substring(idx, Math.min(idx + 120, s.length())).trim()
    }
    return s.length() > 120 ? s.substring(0, 120) : s
  }

  /**
   * Shrinks {@code GetPreviewHtml} tool JSON on the wire: caps {@code html}, keeps a plain-text excerpt.
   */
  private static String compactGetPreviewHtmlToolWire(String s, int maxHtmlChars) {
    if (!s?.trim() || maxHtmlChars < 512) {
      return s ?: ''
    }
    try {
      def parsed = new JsonSlurper().parseText(s)
      if (!(parsed instanceof Map)) {
        return s.length() <= NATIVE_TOOLS_WIRE_JSON_MAX_CHARS ? s : s.substring(0, NATIVE_TOOLS_WIRE_JSON_MAX_CHARS)
      }
      Map m = new LinkedHashMap<>((Map) parsed)
      Object htmlObj = m.get('html')
      String html = htmlObj != null ? htmlObj.toString() : ''
      if (html.length() > maxHtmlChars) {
        String plain = htmlToRoughPlainText(html)
        int excerptCap = Math.min(2500, maxHtmlChars)
        String excerpt = plain.length() > excerptCap ? plain.substring(0, excerptCap) + '…' : plain
        m.put('htmlOriginalChars', html.length())
        m.put('htmlPlainTextExcerpt', excerpt)
        m.put('htmlOmittedOnWire', Boolean.TRUE)
        m.remove('html')
      }
      String out = JsonOutput.toJson(m)
      return out.length() <= NATIVE_TOOLS_WIRE_JSON_MAX_CHARS ?
        out :
        out.substring(0, NATIVE_TOOLS_WIRE_JSON_MAX_CHARS) +
          '\n…[GetPreviewHtml JSON truncated on wire]'
    } catch (Throwable ignored) {
      return s.length() <= NATIVE_TOOLS_WIRE_JSON_MAX_CHARS ? s : s.substring(0, NATIVE_TOOLS_WIRE_JSON_MAX_CHARS)
    }
  }

  /** Normalizes preview URLs for duplicate-fetch detection within one turn. */
  private static String normalizePreviewUrlKey(String url) {
    String u = (url ?: '').trim()
    if (!u) {
      return ''
    }
    return u.replaceAll('/+$', '').toLowerCase(Locale.ROOT)
  }

  /** Preview URL from GetPreviewHtml tool args. */
  private static String previewUrlFromToolArgsJson(String argsStr, JsonSlurper slurper) {
    try {
      Object parsed = slurper.parseText(argsStr ?: '{}')
      if (parsed instanceof Map) {
        Map args = (Map) parsed
        return (args.url ?: args.previewUrl ?: '').toString().trim()
      }
    } catch (Throwable ignored) {
    }
    return ''
  }

  /**
   * Re-compacts older GetPreviewHtml payloads still on the wire (tool rows and auto-preview user injections).
   */
  private static void recompactPreviewHtmlOnWire(List<Map> wireMessages) {
    if (!(wireMessages instanceof List)) {
      return
    }
    for (int i = 0; i < wireMessages.size(); i++) {
      def row = wireMessages.get(i)
      if (!(row instanceof Map)) {
        continue
      }
      Map m = (Map) row
      String role = m.get('role')?.toString() ?: ''
      String content = m.get('content')?.toString() ?: ''
      if (!content || (!content.contains('"html"') && !content.contains('htmlPlainTextExcerpt'))) {
        continue
      }
      if (!'tool'.equals(role) && !content.contains('GetPreviewHtml')) {
        continue
      }
      String compact = compactGetPreviewHtmlToolWire(content, GET_PREVIEW_HTML_WIRE_MAX_HTML_CHARS)
      if (!compact.equals(content)) {
        Map next = new LinkedHashMap<>(m)
        next.put('content', compact)
        wireMessages.set(i, next)
      }
    }
  }

  /**
   * Skip redundant GetContent / GetContentTypeFormDefinition when intent prefetch already loaded them.
   */
  private static boolean toolsLoopSkipRedundantPrefetchDiscovery(String fnName, Map intentTelLoop) {
    if (!(intentTelLoop instanceof Map)) {
      return false
    }
    if (!Boolean.TRUE.equals(intentTelLoop.get('prefetchRan'))) {
      return false
    }
    if (!Boolean.TRUE.equals(intentTelLoop.get('toolsLoopFormDefsPrefetched'))) {
      return false
    }
    String recipeId = intentTelLoop.get('recipeId')?.toString()?.trim() ?: ''
    String supplement = intentTelLoop.get('toolsLoopPrefetchSupplement')?.toString()?.trim() ?: ''
    boolean prefetchDiscoveryRecipe =
      'modify_page_content'.equals(recipeId) ||
        'new_content_item_from_chat_draft'.equals(recipeId) ||
        'createFromChatDraft'.equals(supplement) ||
        'newContentItem'.equals(supplement)
    if (!prefetchDiscoveryRecipe) {
      return false
    }
    return ['GetContent', 'GetContentTypeFormDefinition'].contains((fnName ?: '').trim())
  }

  /**
   * Only skip prefetch-redundant reads when the tool targets the same path or content type prefetch loaded.
   */
  private static boolean toolArgsMatchPrefetchedPathOrType(
    String fnName,
    String argsStr,
    Map toolsLoopSessionBundle,
    JsonSlurper slurper
  ) {
    String tool = (fnName ?: '').trim()
    if (!['GetContent', 'GetContentTypeFormDefinition'].contains(tool)) {
      return false
    }
    Object parsed
    try {
      parsed = slurper.parseText(argsStr ?: '{}')
    } catch (Throwable ignored) {
      return false
    }
    if (!(parsed instanceof Map)) {
      return false
    }
    Map args = (Map) parsed
    String anchorPath = ''
    String anchorType = ''
    if (toolsLoopSessionBundle instanceof Map) {
      anchorPath = (toolsLoopSessionBundle.contentPath ?: '').toString().trim()
      anchorType = (toolsLoopSessionBundle.contentTypeId ?: '').toString().trim()
    }
    if ('GetContent'.equals(tool)) {
      String reqPath = repoPathFromToolArgsMap(args)
      if (!reqPath || !anchorPath) {
        return false
      }
      return AuthoringPreviewContext.sameRepoPath(reqPath, anchorPath)
    }
    String reqType = (args.contentTypeId ?: args.contentType ?: '').toString().trim()
    if (!reqType) {
      return false
    }
    if (toolsLoopSessionBundle instanceof Map) {
      Map tel = toolsLoopSessionBundle.intentRecipeRoutingTelemetry instanceof Map ?
        (Map) toolsLoopSessionBundle.intentRecipeRoutingTelemetry :
        null
      String prefetchType = (tel?.toolsLoopPrefetchResolvedContentTypeId ?: '').toString().trim()
      if (prefetchType && reqType.equalsIgnoreCase(prefetchType)) {
        return true
      }
    }
    if (anchorType && reqType.equalsIgnoreCase(anchorType)) {
      return true
    }
    return false
  }

  private static Set<String> toolsLoopFormDefinitionFetchedTypes(Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return new LinkedHashSet<>()
    }
    Object raw = toolsLoopSessionBundle.get('toolsLoopFormDefinitionFetchedTypes')
    if (raw instanceof Set) {
      return (Set<String>) raw
    }
    Set<String> created = new LinkedHashSet<>()
    toolsLoopSessionBundle.put('toolsLoopFormDefinitionFetchedTypes', created)
    return created
  }

  private static boolean toolsLoopFormDefinitionTypeAlreadyFetched(
    Map toolsLoopSessionBundle,
    Map intentTelLoop,
    String contentTypeId
  ) {
    String type = (contentTypeId ?: '').toString().trim()
    if (!type && intentTelLoop instanceof Map) {
      type = (intentTelLoop.toolsLoopPrefetchResolvedContentTypeId ?: '').toString().trim()
    }
    if (!type) {
      return false
    }
    Set<String> fetched = toolsLoopFormDefinitionFetchedTypes(toolsLoopSessionBundle)
    return fetched.contains(type.toLowerCase(Locale.ROOT))
  }

  private static void markToolsLoopFormDefinitionFetched(Map toolsLoopSessionBundle, String contentTypeId) {
    String type = (contentTypeId ?: '').toString().trim()
    if (!type || !(toolsLoopSessionBundle instanceof Map)) {
      return
    }
    toolsLoopFormDefinitionFetchedTypes(toolsLoopSessionBundle).add(type.toLowerCase(Locale.ROOT))
  }

  private static String contentTypeIdFromFormDefinitionToolArgs(String argsStr, JsonSlurper slurper) {
    try {
      Object parsed = slurper.parseText(argsStr ?: '{}')
      if (parsed instanceof Map) {
        return (parsed.contentTypeId ?: parsed.contentType ?: '').toString().trim()
      }
    } catch (Throwable ignored) {
    }
    return ''
  }

  private static boolean toolsLoopSkipRepeatedFormDefinitionFetch(
    String fnName,
    String argsStr,
    Map toolsLoopSessionBundle,
    Map intentTelLoop,
    JsonSlurper slurper
  ) {
    if (!'GetContentTypeFormDefinition'.equals((fnName ?: '').trim())) {
      return false
    }
    String reqType = contentTypeIdFromFormDefinitionToolArgs(argsStr, slurper)
    if (!reqType) {
      return false
    }
    if (toolsLoopFormDefinitionTypeAlreadyFetched(toolsLoopSessionBundle, intentTelLoop, reqType)) {
      return true
    }
    if (Boolean.TRUE.equals(intentTelLoop?.get('toolsLoopFormDefsPrefetched'))) {
      String prefetchType = (intentTelLoop?.toolsLoopPrefetchResolvedContentTypeId ?: '').toString().trim()
      if (prefetchType && reqType.equalsIgnoreCase(prefetchType)) {
        return true
      }
    }
    return false
  }

  private static String compactGetContentTypeFormDefinitionToolWire(String rawJson, int maxChars) {
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
        Object xmlObj = m.get('formDefinitionXml')
        int xmlLen = xmlObj != null ? xmlObj.toString().length() : 0
        m.remove('formDefinitionXml')
        m.put('formDefinitionXmlOmittedOnWire', Boolean.TRUE)
        if (xmlLen > 0) {
          m.put('formDefinitionXmlChars', xmlLen)
        }
      }
      if (!m.containsKey('formValidationPlan') && m.containsKey('requiredFieldIds')) {
        m.put('formValidationPlan', [
          requiredFieldIds: m.get('requiredFieldIds'),
          formFieldIds    : m.get('formFieldIds')
        ])
      }
      String out = JsonOutput.toJson(m)
      if (out.length() <= maxChars) {
        return out
      }
      return out.substring(0, maxChars) +
        '\n…[GetContentTypeFormDefinition compacted on wire — use requiredFieldIds / formValidationPlan]'
    } catch (Throwable ignored) {
      return s.length() <= maxChars ? s : s.substring(0, maxChars)
    }
  }

  private static void compactHistoricalFormDefinitionToolWireMessages(List wireMessages) {
    if (!(wireMessages instanceof List)) {
      return
    }
    for (int i = 0; i < wireMessages.size(); i++) {
      Object row = wireMessages.get(i)
      if (!(row instanceof Map)) {
        continue
      }
      Map m = (Map) row
      if (!'tool'.equalsIgnoreCase((m.get('role') ?: '').toString().trim())) {
        continue
      }
      def c = m.get('content')
      if (!(c instanceof CharSequence)) {
        continue
      }
      String s = c.toString()
      if (!s.contains('formDefinitionXml') && !s.contains('GetContentTypeFormDefinition')) {
        continue
      }
      String compact = compactGetContentTypeFormDefinitionToolWire(s, NATIVE_TOOLS_WIRE_JSON_MAX_CHARS)
      if (compact && !compact.equals(s)) {
        m.put('content', compact)
        wireMessages.set(i, m)
      }
    }
  }

  /** Clears per-turn preview verification after a successful write so stale preview results are not reused. */
  private static int invalidateTurnPreviewVerification(
    Map previewState,
    Set<String> previewHtmlUrlsThisTurn,
    Map toolsLoopSessionBundle
  ) {
    if (previewState instanceof Map) {
      previewState.lastPreviewContentGoalFound = null
      previewState.lastPreviewContentGoalPhrase = null
    }
    if (previewHtmlUrlsThisTurn != null) {
      previewHtmlUrlsThisTurn.clear()
    }
    if (toolsLoopSessionBundle instanceof Map) {
      toolsLoopSessionBundle.remove('toolsLoopPreviewVerificationFound')
      toolsLoopSessionBundle.remove('toolsLoopPreviewVerificationReason')
      toolsLoopSessionBundle.remove('toolsLoopPreviewVerificationDetail')
      toolsLoopSessionBundle.remove('toolsLoopPreviewHttpOk')
      toolsLoopSessionBundle.remove('toolsLoopPreviewHttpStatus')
    }
    return 0
  }

  private static String firstPersistedWriteContentRepoPath(Map toolsLoopSessionBundle) {
    Object raw = toolsLoopSessionBundle?.toolsLoopWriteContentRepoPaths
    if (raw instanceof Collection && !((Collection) raw).isEmpty()) {
      return ((Collection) raw).iterator().next()?.toString() ?: ''
    }
    return (toolsLoopSessionBundle?.contentPath ?: '').toString()
  }

  /**
   * Shrinks {@code FetchHttpUrl} tool JSON on the wire: caps {@code body}, drops bulky {@code stylesheetHrefs}.
   */
  private static String compactFetchHttpUrlToolWire(String s, int maxBodyChars) {
    if (!s?.trim() || maxBodyChars < 512) {
      return s ?: ''
    }

    try {
      def parsed = new JsonSlurper().parseText(s)
      if (!(parsed instanceof Map)) {
        return s.length() <= maxBodyChars ? s : s.substring(0, maxBodyChars)
      }

      Map m = new LinkedHashMap<>((Map) parsed)
      Object bodyObj = m.get('body')
      String body = bodyObj != null ? bodyObj.toString() : ''

      if (body.length() > maxBodyChars) {
        m.put('bodyOriginalChars', body.length())
        m.put(
          'body',
          body.substring(0, maxBodyChars) +
            '\n…[FetchHttpUrl body truncated on wire — use plainTextExcerpt below or fetch a narrower page]'
        )
        m.put('bodyTruncatedOnWire', Boolean.TRUE)
      }

      String plainExcerpt = plugins.org.craftercms.aiassistant.studio.engine.turn.AuthoringFetchedPageFacts
        .plainTextExcerpt(body, Math.min(maxBodyChars, 12_000))
      if (plainExcerpt) {
        m.put('plainTextExcerpt', plainExcerpt)
      }

      if (m.containsKey('stylesheetHrefs')) {
        m.remove('stylesheetHrefs')
        m.put('stylesheetHrefsOmittedOnWire', Boolean.TRUE)
      }

      String out = JsonOutput.toJson(m)
      return out.length() <= NATIVE_TOOLS_WIRE_JSON_MAX_CHARS ?
        out :
        out.substring(0, NATIVE_TOOLS_WIRE_JSON_MAX_CHARS) +
          '\n…[FetchHttpUrl JSON truncated on wire]'
    } catch (Throwable ignored) {
      return s.length() <= maxBodyChars ? s : s.substring(0, maxBodyChars)
    }
  }

  /** Reads {@code toolsLoopMaxFetchHttpUrlCalls} from the session bundle or intent-routing telemetry. */
  private static int toolsLoopMaxFetchHttpUrlCallsFromBundle(Map toolsLoopSessionBundle) {
    if (toolsLoopSessionBundle instanceof Map) {
      Object direct = toolsLoopSessionBundle.get('toolsLoopMaxFetchHttpUrlCalls')
      if (direct instanceof Number) {
        int n = ((Number) direct).intValue()
        if (n > 0) {
          return Math.min(n, 10)
        }
      }
      Object tel = toolsLoopSessionBundle.get('intentRecipeRoutingTelemetry')
      if (tel instanceof Map) {
        Object fromTel = ((Map) tel).get('toolsLoopMaxFetchHttpUrlCalls')
        if (fromTel instanceof Number) {
          int n = ((Number) fromTel).intValue()
          if (n > 0) {
            return Math.min(n, 10)
          }
        }
      }
    }
    if (toolsLoopSessionBundle instanceof Map &&
      Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopExternalLookupRequired)) {
      return 2
    }
    return 0
  }

  /** API key / model / wire URL for confirmation {@code llmRefine} steps (from the active tools-loop session bundle). */
  private static Map buildRecipeConfirmationLlmContext(Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return [:]
    }
    String apiKey = StudioAiLlmKind.toolsLoopChatApiKeyFromBundle(toolsLoopSessionBundle)
    String model = toolsLoopSessionBundle.resolvedChatModel?.toString()?.trim() ?:
      toolsLoopSessionBundle.chatModel?.toString()?.trim() ?: ''
    return [
      apiKey                  : apiKey,
      model                   : model,
      wireBaseUrl             : StudioAiLlmKind.toolsLoopChatBaseUrlFromBundle(toolsLoopSessionBundle),
      toolsLoopSessionBundle  : toolsLoopSessionBundle
    ] as Map
  }

  /** When confirmation includes {@code llmRefine} + outbound tools, end the tools loop with server-authored markdown. */
  private static boolean recipeConfirmationShouldFinalizeAuthorVisible(Map plan) {
    if (!(plan instanceof Map)) {
      return false
    }
    List<Map> ces = plan.confirmationEngineSteps instanceof List ? (List<Map>) plan.confirmationEngineSteps : []
    boolean hasRefine = ces.any { Map es -> es?.get('llmRefine')?.toString()?.trim() }
    boolean hasTool = ces.any { Map es -> es?.get('tool')?.toString()?.trim() }
    return hasRefine && hasTool
  }

  /**
   * When confirmation will {@linkplain #recipeConfirmationShouldFinalizeAuthorVisible finalize} author-visible
   * markdown, do not stream intermediate outbound prose before tools finish — only short ## Plan / 📋 lines.
   */
  private static boolean shouldStreamPreToolAssistantSseForToolsLoop(
    String cleanedPreTool,
    Map toolsLoopSessionBundle,
    int round
  ) {
    if (!(cleanedPreTool?.trim())) {
      return false
    }
    Map plan = toolsLoopSessionBundle?.recipeExecutionPlan instanceof Map ?
      (Map) toolsLoopSessionBundle.recipeExecutionPlan :
      null
    if (!recipeConfirmationShouldFinalizeAuthorVisible(plan)) {
      return true
    }
    String t = cleanedPreTool.trim()
    if (t.contains('## Plan') || t.contains('📋')) {
      return round <= 2
    }
    return false
  }

  /**
   * Last non empty assistant wire text.
   * @param wireMessages Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String lastNonEmptyAssistantWireText(List wireMessages) {
    if (!(wireMessages instanceof List)) {
      return ''
    }
    for (int i = wireMessages.size() - 1; i >= 0; i--) {
      Object row = wireMessages.get(i)
      if (!(row instanceof Map)) {
        continue
      }
      Map msg = (Map) row
      if (!'assistant'.equals(msg.get('role')?.toString())) {
        continue
      }
      String t = assistantTextFromChoiceMessageMap(msg)?.trim()
      if (t) {
        return t
      }
    }
    return ''
  }

  /**
   * Whether assistant prose is only a short tools-loop status line (not a draft to refine).
   */
  private static boolean toolsLoopAssistantProseTooThinForConfirmation(String text) {
    String t = (text ?: '').toString().trim()
    if (!t) {
      return true
    }
    if (t.length() < 120) {
      return true
    }
    if ((t =~ /(?i)^Applying your request with the appropriate tools\.?\s*$/).matches()) {
      return true
    }
    return false
  }

  /**
   * Collects {@code FetchHttpUrl} bodies from {@code role:tool} wire messages for confirmation {@code llmRefine}.
   */
  private static List<String> fetchHttpUrlBodiesFromWire(List wireMessages, int maxTotalChars) {
    List<String> out = new ArrayList<>()
    if (!(wireMessages instanceof List) || maxTotalChars < 256) {
      return out
    }
    int used = 0
    JsonSlurper slurper = new JsonSlurper()
    for (Object rowObj : wireMessages) {
      if (!(rowObj instanceof Map)) {
        continue
      }
      Map row = (Map) rowObj
      if (!'tool'.equals(row.get('role')?.toString())) {
        continue
      }
      String content = flattenWireUserContent(row.get('content'))?.trim() ?: ''
      if (!content || !content.contains('"body"')) {
        continue
      }
      try {
        Object parsed = slurper.parseText(content)
        if (!(parsed instanceof Map)) {
          continue
        }
        Map m = (Map) parsed
        String url = (m.get('url') ?: '').toString().trim()
        String body = (m.get('body') ?: '').toString().trim()
        if (!body) {
          continue
        }
        int room = maxTotalChars - used
        if (room < 256) {
          break
        }
        if (body.length() > room) {
          body = body.substring(0, room) + '\n…[FetchHttpUrl body truncated for confirmation refine]'
        }
        String block = url ? "**URL:** ${url}\n\n${body}" : body
        out.add(block)
        used += body.length()
      } catch (Throwable ignored) {
      }
    }
    return out
  }

  /**
   * Source markdown for confirmation {@code llmRefine}: author request + fetched reference(s) + any substantive
   * assistant prose. Tool-only turns often end with a one-line status — refining that alone invents off-topic drafts.
   */
  private static String buildRecipeConfirmationSourceMarkdown(List wireMessages, String roundAssistantText) {
    StringBuilder sb = new StringBuilder()
    String authorRaw = firstAuthoringUserWirePlainText(wireMessages)
    String author = ''
    try {
      author = AuthoringPreviewContext.stripStudioInjectedPromptBlocks(authorRaw)?.trim() ?: ''
    } catch (Throwable ignored) {
      author = (authorRaw ?: '').trim()
    }
    if (author) {
      sb.append('## Author request\n\n').append(author).append('\n\n')
    }
    List<String> fetches = fetchHttpUrlBodiesFromWire(wireMessages, 20_000)
    for (int i = 0; i < fetches.size(); i++) {
      sb.append('## Reference material ').append(i + 1).append('\n\n').append(fetches.get(i)).append('\n\n')
    }
    String round = (roundAssistantText ?: '').toString().trim()
    String assist = !toolsLoopAssistantProseTooThinForConfirmation(round) ?
      round :
      ''
    if (!assist) {
      String last = lastNonEmptyAssistantWireText(wireMessages)
      if (!toolsLoopAssistantProseTooThinForConfirmation(last)) {
        assist = last
      }
    }
    if (assist) {
      sb.append('## Assistant notes\n\n').append(assist).append('\n\n')
    }
    String built = sb.toString().trim()
    if (built) {
      return built
    }
    return (round ?: lastNonEmptyAssistantWireText(wireMessages) ?: '').trim()
  }

  /**
   * Assistant markdown passed to confirmation {@code llmRefine}: author request, {@code FetchHttpUrl} bodies, and
   * substantive assistant prose (not a thin tools-loop status line).
   */
  private static String resolveMarkdownForRecipeConfirmation(List wireMessages, String roundAssistantText) {
    return buildRecipeConfirmationSourceMarkdown(wireMessages, roundAssistantText)
  }

  /** Author-visible markdown after JVM confirmation (structured payload preview + per-tool status). */
  private static String buildRecipeConfirmationAuthorMarkdown(Map block, Map plan, Map recipe = null) {
    if (!(block instanceof Map)) {
      return ''
    }
    Map payload = block.confirmationPayload instanceof Map ? (Map) block.confirmationPayload : [:]
    String preview = (block.refinedAssistantMarkdown ?: '').toString().trim()
    Map llmRefineStep = (block.steps instanceof List) ?
      ((List) block.steps).find { it instanceof Map && 'llmRefine'.equals(((Map) it).get('step')?.toString()) } :
      null
    StringBuilder sb = new StringBuilder()
    if (payload instanceof Map && !payload.isEmpty()) {
      List<String> keyOrder = []
      if (llmRefineStep instanceof Map && llmRefineStep.outputKeys instanceof List) {
        keyOrder = (List<String>) llmRefineStep.outputKeys
      } else {
        keyOrder = new ArrayList<>(payload.keySet())
      }
      sb.append(
        plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentRecipeLlmRefiner
          .buildAuthorPreviewMarkdown(payload, keyOrder)
      )
    } else if (preview) {
      sb.append(preview)
    }
    List steps = block.steps instanceof List ? (List) block.steps : []
    for (Object stepObj : steps) {
      if (!(stepObj instanceof Map)) {
        continue
      }
      Map cq = (Map) stepObj
      if (!'ConsultCrafterQ'.equals(cq.get('tool')?.toString()) || !Boolean.TRUE.equals(cq.get('ok'))) {
        continue
      }
      String fb = (cq.get('feedbackMarkdown') ?: cq.get('answer'))?.toString()?.trim()
      if (!fb) {
        continue
      }
      if (!fb.startsWith('##')) {
        fb = plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.integrations.CrafterQConsultFeedbackFormatter
          .chatSectionMarkdown(fb)
      }
      if (fb) {
        sb.append('\n\n---\n\n').append(fb)
      }
    }
    for (Object stepObj : steps) {
      if (!(stepObj instanceof Map)) {
        continue
      }
      Map step = (Map) stepObj
      String tool = step.get('tool')?.toString()?.trim()
      if (!tool) {
        continue
      }
      sb.append('\n\n---\n\n')
      if (Boolean.TRUE.equals(step.get('ok'))) {
        sb.append('✅ **').append(tool).append('** completed.')
        String ch = step.get('channel')?.toString()?.trim()
        if (ch) {
          sb.append(' (`').append(ch).append('`)')
        }
      } else {
        sb.append('❌ **').append(tool).append('** did not succeed')
        String err = (step.get('error') ?: step.get('message'))?.toString()?.trim()
        if (err) {
          sb.append(': ').append(err)
        }
        sb.append('.')
      }
    }
    if (sb.length() == 0 && Boolean.FALSE.equals(block.ok)) {
      sb.append('⚠️ **Confirmation steps finished with errors** — check Studio logs.\n')
    }
    Map payloadForFollowUp = block.confirmationPayload instanceof Map ? (Map) block.confirmationPayload : [:]
    String draftForFollowUp = (payloadForFollowUp.draft ?: '').toString().trim()
    if (draftForFollowUp) {
      String draftHeading = plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.PriorConversationDraftExtract
        .followUpHeadingForPayloadKey(llmRefineStep instanceof Map ? (Map) llmRefineStep : [:], 'draft')
      if (!draftHeading) {
        List<String> configured = plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.PriorConversationDraftExtract
          .confirmationFollowUpSectionHeadings(recipe, [:])
        draftHeading = configured && !configured.isEmpty() ? configured[0] : 'draft'
      }
      sb.append('\n\n---\n\n## ').append(draftHeading).append('\n\n').append(draftForFollowUp).append('\n')
    } else {
      String sourceMd = (block.confirmationSourceMarkdown ?: '').toString().trim()
      if (sourceMd) {
        appendRecipeTurnSectionsForFollowUpChat(sb, sourceMd, recipe)
      }
    }
    return sb.toString().trim()
  }

  /**
   * Keeps recipe-configured {@code ##} sections in the author-visible message after confirmation — needed for
   * follow-up turns that create repository items from prior chat prose.
   */
  private static void appendRecipeTurnSectionsForFollowUpChat(StringBuilder sb, String sourceMd, Map recipe = null) {
    List<String> headings = plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.PriorConversationDraftExtract
      .confirmationFollowUpSectionHeadings(recipe, [:])
    boolean any = false
    for (String heading : headings) {
      String body = plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.RecipeMarkdownSections
        .extractSection(sourceMd, heading)?.trim()
      if (!body) {
        continue
      }
      if (!any) {
        sb.append('\n\n---\n\n')
        any = true
      }
      sb.append('## ').append(heading).append('\n\n').append(body).append('\n')
    }
  }

  /**
   * Applies recipe confirmation telemetry to repository content or orchestration state.
   * @param toolsLoopSessionBundle Caller-supplied input.
   * @param block Caller-supplied input.
   */
  private static void applyRecipeConfirmationTelemetry(Map toolsLoopSessionBundle, Map block) {
    Map tel = toolsLoopSessionBundle?.intentRecipeRoutingTelemetry instanceof Map ?
      (Map) toolsLoopSessionBundle.intentRecipeRoutingTelemetry :
      null
    if (!(tel instanceof Map)) {
      return
    }
    tel.confirmationServerStepsExecuted = Boolean.TRUE
    tel.confirmationServerStepsOk = Boolean.TRUE.equals(block?.ok)
    tel.confirmationServerStepSummaries = block?.steps instanceof List ? block.steps : []
    Map refineStep = (block?.steps instanceof List) ?
      ((List) block.steps).find { it instanceof Map && 'llmRefine'.equals(((Map) it).get('step')?.toString()) } :
      null
    if (refineStep instanceof Map && !Boolean.TRUE.equals(refineStep.skipped)) {
      tel.confirmationLlmRefined = Boolean.TRUE
      tel.confirmationPitchRefined = Boolean.TRUE
    }
  }

  /**
   * Runs matched-recipe {@code phases.confirmation} {@code engineSteps} on the JVM after Action-phase chat work.
   *
   * @return map {@code ran}, optional {@code finalizeAuthorText} (end tools loop without another LLM round),
   *         optional {@code wireInjectMarkdown} when the loop should continue
   */
  private static Map runMatchedRecipeConfirmationIfNeeded(
    List wireMessages,
    Map toolsLoopSessionBundle,
    String agentId,
    int round,
    String lastAssistantMarkdown,
    OutputStream sseOut = null
  ) {
    Map none = [ran: false, finalizeAuthorText: null, wireInjectMarkdown: null]
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return none
    }
    if (Boolean.TRUE.equals(toolsLoopSessionBundle.recipeConfirmationStepsExecuted)) {
      return none
    }
    Map plan = toolsLoopSessionBundle.recipeExecutionPlan instanceof Map ?
      (Map) toolsLoopSessionBundle.recipeExecutionPlan :
      null
    if (!AuthoringIntentRecipePlanCompiler.hasConfirmationServerSteps(plan)) {
      return none
    }
    Map tel = toolsLoopSessionBundle.intentRecipeRoutingTelemetry instanceof Map ?
      (Map) toolsLoopSessionBundle.intentRecipeRoutingTelemetry :
      null
    if (tel == null || !'matched'.equals(tel.get('outcome')?.toString())) {
      return none
    }
    String recipeId = tel.get('recipeId')?.toString()?.trim()
    if (!recipeId) {
      return none
    }
    StudioToolOperations ops = toolsLoopSessionBundle.studioOps instanceof StudioToolOperations ?
      (StudioToolOperations) toolsLoopSessionBundle.studioOps :
      null
    if (ops == null) {
      return none
    }
    Map cfg = intentRecipeProjectConfigFromToolsLoopBundle(toolsLoopSessionBundle)
    if (cfg == null || !StudioAiAssistantProjectConfig.intentRecipeEngineEnabled(cfg)) {
      return none
    }
    List recipes = AuthoringIntentRecipeCatalog.loadRecipes(ops, cfg)
    Map recipe = AuthoringIntentRecipeCatalog.findRecipeById(recipes, recipeId)
    if (recipe == null) {
      return none
    }
    long t0 = System.currentTimeMillis()
    Closure confirmationProgressListener = null
    if (sseOut != null) {
      confirmationProgressListener = { String tn, String ph, Map inp, Throwable er, Object tres, Long dur ->
        writeToolProgressSse(sseOut, tn, ph, inp instanceof Map ? inp : [:], er, tres, dur, ops)
      }
    }
    Map confirmationLlmContext = buildRecipeConfirmationLlmContext(toolsLoopSessionBundle)
    Map block = AuthoringIntentRecipeEngine.runConfirmationStepsBlock(
      ops,
      recipe,
      cfg,
      lastAssistantMarkdown,
      confirmationLlmContext,
      confirmationProgressListener
    )
    if (block instanceof Map) {
      block.confirmationSourceMarkdown = (lastAssistantMarkdown ?: '').toString().trim()
    }
    long elapsed = System.currentTimeMillis() - t0
    writeToolProgressSse(sseOut, 'Recipe confirmation', 'done', [:], null, block, elapsed, ops)
    toolsLoopSessionBundle.recipeConfirmationStepsExecuted = Boolean.TRUE
    applyRecipeConfirmationTelemetry(toolsLoopSessionBundle, block)
    String md = (block.markdown ?: '').toString().trim()
    boolean confOk = Boolean.TRUE.equals(block.ok)
    if (confOk) {
      log.info(
        'Tools-loop: executed recipe confirmation server steps ok=true agentId={} recipeId={} round={}',
        agentId,
        recipeId,
        round
      )
    } else {
      log.error(
        'Tools-loop: executed recipe confirmation server steps ok=false agentId={} recipeId={} round={} steps={}',
        agentId,
        recipeId,
        round,
        block.steps
      )
    }
    String finalizeAuthorText = buildRecipeConfirmationAuthorMarkdown(block, plan, recipe)
    Map out = [
      ran                : true,
      finalizeAuthorText : finalizeAuthorText,
      wireInjectMarkdown : md
    ]
    if (recipeConfirmationShouldFinalizeAuthorVisible(plan) && finalizeAuthorText?.trim()) {
      out.finalizeWithoutLlmRound = Boolean.TRUE
    }
    if (!md?.trim() && !finalizeAuthorText?.trim()) {
      return none
    }
    return out
  }

  /**
   * Runs matched-recipe confirmation when the model finishes without further {@code tool_calls}.
   * Returns {@code true} when the tools loop should continue (legacy wire-inject path).
   */
  private static boolean maybeExecuteMatchedRecipeConfirmationSteps(
    List wireMessages,
    Map toolsLoopSessionBundle,
    String agentId,
    int round,
    String lastAssistantMarkdown,
    OutputStream sseOut = null
  ) {
    Map conf = runMatchedRecipeConfirmationIfNeeded(
      wireMessages,
      toolsLoopSessionBundle,
      agentId,
      round,
      lastAssistantMarkdown,
      sseOut
    )
    if (!Boolean.TRUE.equals(conf.ran)) {
      return false
    }
    if (Boolean.TRUE.equals(conf.finalizeWithoutLlmRound) && conf.finalizeAuthorText?.toString()?.trim()) {
      return false
    }
    String md = (conf.wireInjectMarkdown ?: '').toString().trim()
    if (!md) {
      return false
    }
    wireMessages << [
      role   : 'user',
      content:
        md +
          '\nIncorporate these confirmation results in **## Plan Execution** (✅/❌/⚠️). Confirmation tools were executed by Studio — do not call them again via **tool_calls**.\n'
    ]
    return true
  }

  /**
   * Fetches http url from tool args json for tool use.
   * @param argsStr Caller-supplied input.
   * @param slurper Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String fetchHttpUrlFromToolArgsJson(String argsStr, JsonSlurper slurper) {
    try {
      Object parsed = slurper.parseText((argsStr ?: '{}').toString())
      if (parsed instanceof Map) {
        String u = (parsed.get('url') ?: parsed.get('href') ?: '').toString().trim()
        if (u) {
          return u
        }
      }
    } catch (Throwable ignored) {
    }
    return ''
  }

  /** Reads {@code toolsLoopFetchHttpUrlWireMaxChars} from the session bundle or intent-routing telemetry. */
  private static int fetchHttpUrlWireMaxCharsFromBundle(Map toolsLoopSessionBundle) {
    if (toolsLoopSessionBundle instanceof Map) {
      Object direct = toolsLoopSessionBundle.get('toolsLoopFetchHttpUrlWireMaxChars')
      if (direct instanceof Number) {
        int n = ((Number) direct).intValue()
        if (n > 256) {
          return Math.min(n, 24_000)
        }
      }
      Object tel = toolsLoopSessionBundle.get('intentRecipeRoutingTelemetry')
      if (tel instanceof Map) {
        Object fromTel = ((Map) tel).get('toolsLoopFetchHttpUrlWireMaxChars')
        if (fromTel instanceof Number) {
          int n = ((Number) fromTel).intValue()
          if (n > 256) {
            return Math.min(n, 24_000)
          }
        }
      }
    }
    return 0
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

  /**
   * Caps tool result JSON on the chat wire using {@link plugins.org.craftercms.aiassistant.studio.engine.policy.ToolsLoopWirePolicyRegistry}.
   * @param fnName wire tool name
   * @param toolOutRaw tool callback return value (string or map already serialized)
   * @param toolCallId native tool_call id (for GenerateImage inline refs)
   * @param generateImageDataUrlByToolCallId backlog map for image ref compaction
   * @param toolsLoopSessionBundle session bundle (optional) for per-recipe {@code FetchHttpUrl} wire caps
   */
  private static String truncateNativeToolWireContent(
    String fnName,
    Object toolOutRaw,
    String toolCallId = null,
    Map<String, String> generateImageDataUrlByToolCallId = null,
    Map toolsLoopSessionBundle = null
  ) {
    String s = toolOutRaw != null ? toolOutRaw.toString() : ''
    def pol = plugins.org.craftercms.aiassistant.studio.engine.policy.ToolsLoopWirePolicyRegistry.policyFor(fnName)
    if (pol.wireOutputMode == plugins.org.craftercms.aiassistant.studio.engine.policy.ToolsLoopWirePolicy.WIRE_COMPACT_UPDATE_CONTENT) {
      return compactUpdateContentToolWire(s, NATIVE_TOOLS_WIRE_JSON_MAX_CHARS)
    }
    if (pol.wireOutputMode == plugins.org.craftercms.aiassistant.studio.engine.policy.ToolsLoopWirePolicy.WIRE_COMPACT_GENERATE_IMAGE) {
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
        '\n\n[aiassistant: output truncated for chat context limit; tool=' + fnName + ' originalChars=' + s.length() + ']' +
        '\nHint: payload too large for wire; use a smaller image or save to /static-assets/.]'
    }
    if (pol.wireOutputMode == plugins.org.craftercms.aiassistant.studio.engine.policy.ToolsLoopWirePolicy.WIRE_COMPACT_FETCH_HTTP) {
      int bodyCap = fetchHttpUrlWireMaxCharsFromBundle(toolsLoopSessionBundle)
      if (bodyCap <= 0) {
        bodyCap = AuthoringIntentRecipeCatalog.DEFAULT_WEB_RESEARCH_FETCH_HTTP_WIRE_MAX_CHARS
      }
      return compactFetchHttpUrlToolWire(s, bodyCap)
    }
    if (pol.wireOutputMode == plugins.org.craftercms.aiassistant.studio.engine.policy.ToolsLoopWirePolicy.WIRE_COMPACT_PREVIEW_HTML) {
      return compactGetPreviewHtmlToolWire(s, GET_PREVIEW_HTML_WIRE_MAX_HTML_CHARS)
    }
    if (pol.wireOutputMode == plugins.org.craftercms.aiassistant.studio.engine.policy.ToolsLoopWirePolicy.WIRE_COMPACT_FORM_DEFINITION) {
      return compactGetContentTypeFormDefinitionToolWire(s, NATIVE_TOOLS_WIRE_JSON_MAX_CHARS)
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
    ToolsLoopTurnArtifacts.clear(toolsLoopSessionBundle)
    ToolsLoopTurnArtifacts.seedFromRoutingPrefetch(toolsLoopSessionBundle, slurper)
    emitPendingStepBridgeArtifacts(ssePreToolAssistantText, toolsLoopSessionBundle)
    String assistantAccum = ''
    boolean finished = false
    boolean toolsRan = false
    boolean previousRoundHadRepoMutation = false
    Set<String> writeContentPathsThisTurn = persistedWriteContentRepoPaths(toolsLoopSessionBundle)
    Set<String> previewHtmlUrlsThisTurn = new LinkedHashSet<>()
    int successfulPreviewFetchesThisTurn = 0
    boolean generateImageSucceededThisTurn = generateImageAlreadySucceededForTurn(toolsLoopSessionBundle)
    boolean generateImageWrapUpInjectedThisTurn = false
    Boolean lastPreviewContentGoalFound = null
    String authorVisibleForToolsLoop = resolveToolsLoopAuthorVisible(toolsLoopSessionBundle, wireMessages) ?: ''
    String frozenAuthorOutcomePhrase = extractAuthoringOutcomePhrase(authorVisibleForToolsLoop)
    String lastPreviewContentGoalPhrase = frozenAuthorOutcomePhrase ?: ''
    int writeContentInvalidDocumentFailures = 0
    String lastWriteContentFormRejectionSignature = ''
    int writeContentRepeatedFormRejectionCount = 0
    String lastInvalidWriteContentPath = ''
    int maxFetchHttpUrlCallsThisTurn = toolsLoopMaxFetchHttpUrlCallsFromBundle(toolsLoopSessionBundle)
    int fetchHttpUrlCallsThisTurn = 0
    Set<String> fetchedHttpUrlsThisTurn = new LinkedHashSet<>()
    int consecutiveToolOnlyRounds = 0
    int toolsLoopStallGuardInjectCount = 0
    int toolsLoopBannedRepoPathGuardHits = 0
    int toolsLoopRequiredToolsNoFinishBlocks = 0
    Map intentTelLoop =
      (toolsLoopSessionBundle instanceof Map && toolsLoopSessionBundle.intentRecipeRoutingTelemetry instanceof Map) ?
        (Map) toolsLoopSessionBundle.intentRecipeRoutingTelemetry :
        null
    List<String> toolsLoopBannedRepoPaths = stringListFromRecipeTelemetry(intentTelLoop, 'toolsLoopBannedRepoPaths')
    List<String> toolsLoopRequiredWireNames = stringListFromRecipeTelemetry(intentTelLoop, 'toolsLoopRequireSuccessfulTools')
    Map<String, Boolean> requiredToolSuccess = freshRequiredToolSuccessMap(toolsLoopRequiredWireNames)
    augmentRequiredToolsForResearchPageRefresh(toolsLoopSessionBundle, requiredToolSuccess, authorVisibleForToolsLoop)
    seedRequiredToolSuccessFromSessionBundle(toolsLoopSessionBundle, requiredToolSuccess)
    if (intentTelLoop instanceof Map && Boolean.TRUE.equals(intentTelLoop.get('toolsLoopFormDefsPrefetched'))) {
      markToolsLoopFormDefinitionFetched(
        toolsLoopSessionBundle,
        (intentTelLoop.get('toolsLoopPrefetchResolvedContentTypeId') ?: '').toString()
      )
    }
    StudioToolOperations ops = (toolsLoopSessionBundle?.studioOps instanceof StudioToolOperations) ?
      (StudioToolOperations) toolsLoopSessionBundle.studioOps :
      null
    for (int round = 0; round < maxRounds; round++) {
      if (cancelRequested != null && cancelRequested.get()) {
        Thread.currentThread().interrupt()
        throw new InterruptedException(AIASSISTANT_PIPELINE_CANCELLED)
      }
      compactHistoricalFormDefinitionToolWireMessages(wireMessages)
      if (round == 0) {
        // Execution plan stays on the LLM wire only — do not emit author plan cards in chat UI.
      }
      aiAssistantToolWorkerDiagPhase("native_tool_loop_round_${round}_build_request wireMsgCount=${wireMessages.size()}")
      List effectiveWireTools = wireTools
      Object toolChoice = 'auto'
      if (round == 0) {
        Map intentTelForce =
          (toolsLoopSessionBundle instanceof Map) ?
            (Map) toolsLoopSessionBundle.get('intentRecipeRoutingTelemetry') :
            null
        String forceTool = intentTelForce?.get('toolsLoopForceTool')?.toString()?.trim() ?: ''
        if (forceTool && wireToolsIncludeNamedTool(effectiveWireTools, forceTool)) {
          boolean deferWriteForce =
            'WriteContent'.equals(forceTool) &&
              !toolsLoopShouldForceWriteContentToolChoice(requiredToolSuccess, intentTelForce, toolsLoopSessionBundle)
          if (!deferWriteForce) {
            toolChoice = [type: 'function', function: [name: forceTool]]
            log.info(
              'Tools-loop tools-on: tool_choice forced to {} (intent recipe catalog, round 0) agentId={} recipeId={}',
              forceTool,
              agentId,
              intentTelForce?.get('recipeId') ?: ''
            )
          }
        }
      } else if (wireToolsIncludeNamedTool(effectiveWireTools, 'FetchHttpUrl') &&
        toolsLoopShouldForceFetchHttpUrlToolChoice(toolsLoopSessionBundle)) {
        toolChoice = [type: 'function', function: [name: 'FetchHttpUrl']]
        log.info(
          'Tools-loop: tool_choice forced to FetchHttpUrl (research grounding — fetch before write) agentId={} round={}',
          agentId,
          round
        )
      } else if (wireToolsIncludeNamedTool(effectiveWireTools, 'GenerateImage') &&
        toolsLoopShouldForceGenerateImageToolChoice(requiredToolSuccess, toolsLoopSessionBundle)) {
        toolChoice = [type: 'function', function: [name: 'GenerateImage']]
        log.info(
          'Tools-loop: tool_choice forced to GenerateImage (research page refresh hero image) agentId={} round={}',
          agentId,
          round
        )
      } else if (wireToolsIncludeNamedTool(effectiveWireTools, 'WriteContent') &&
        toolsLoopShouldForceWriteContentToolChoice(requiredToolSuccess, intentTelLoop, toolsLoopSessionBundle)) {
        toolChoice = [type: 'function', function: [name: 'WriteContent']]
        log.info(
          'Tools-loop: tool_choice forced to WriteContent (required tool still pending) agentId={} round={} recipeId={}',
          agentId,
          round,
          intentTelLoop?.get('recipeId') ?: ''
        )
      }
      def reqMap = [
        model: model,
        messages: wireMessages,
        stream: false
      ]
      if (effectiveWireTools) {
        reqMap.tools = effectiveWireTools
        reqMap.tool_choice = toolChoice
      }
      int toolsLoopMaxOut = 16000
      if (toolsLoopSessionBundle instanceof Map && toolsLoopSessionBundle.intentRefineMaxOutTokens instanceof Number) {
        int refineOut = ((Number) toolsLoopSessionBundle.intentRefineMaxOutTokens).intValue()
        if (refineOut > 0) {
          toolsLoopMaxOut = refineOut
        }
      }
      int effMaxOut = clampMaxOutTokensForToolsLoopWire(model, toolsLoopMaxOut, toolsLoopSessionBundle)
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
          resolveChatCompletionsRestReadTimeoutMs(toolsLoopSessionBundle)
        )
      }
      emitRoundWaitSse(ssePreToolAssistantText, round, model, agentId, jsonBody.length(), previousRoundHadRepoMutation)
      if (cancelRequested != null && cancelRequested.get()) {
        Thread.currentThread().interrupt()
        throw new InterruptedException(AIASSISTANT_PIPELINE_CANCELLED)
      }
      String raw = httpPostChatCompletionsReadBody(apiKey, jsonBody, false, wireBaseUrl, toolsLoopSessionBundle)
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
      boolean hasApiToolCalls = choiceMessageHasToolCalls(msgCopy)
      String assistantRawForOrchestration = assistantApiFlatForDebug
      List runList = null
      if (hasApiToolCalls) {
        def tcl0 = msgCopy.get('tool_calls')
        if (tcl0 instanceof List) {
          List tcl = (List) tcl0
          List ordered = PlanOrchestration.reorderToolCallsByPlan(new ArrayList(tcl), assistantRawForOrchestration)
          List runListPrep = ordered != null ? ordered : new ArrayList(tcl)
          List depOrdered = PlanOrchestration.reorderToolCallsReadBeforeWritePreview(runListPrep)
          msgCopy.put('tool_calls', depOrdered)
          runList = depOrdered
          if (ordered != null) {
            log.info(
              'Tools-loop tools-on: plan orchestrator reordered {} tool_calls to match plan orchestration block agentId={}',
              ordered.size(),
              agentId
            )
          }
          if (round == 0 && ops != null && toolsLoopSessionBundle instanceof Map) {
            Map stepCfg = StudioAiAssistantProjectConfig.load(ops)
            List stepRecipes = AuthoringIntentRecipeCatalog.loadRecipes(ops, stepCfg)
            maybeLogPlanStepDeterministicRecipeMatches(
              stepRecipes,
              (Map) toolsLoopSessionBundle,
              assistantRawForOrchestration
            )
            prependPlanStepRecipeHintsToWireMessages(wireMessages, (Map) toolsLoopSessionBundle)
          }
        }
      }
      mutateAssistantContentStripOrchestratorBlock(msgCopy)
      assistantPreTool = assistantTextFromChoiceMessageMap(msgCopy)
      if (!runList) {
        List proseCalls =
          ProseDeclaredToolCalls.synthesizeFromAssistantProse(assistantPreTool ?: '', byName)
        if (proseCalls && !proseCalls.isEmpty()) {
          runList = proseCalls
          msgCopy.put('tool_calls', new ArrayList(proseCalls))
          log.info(
            'Tools-loop: executing {} prose-declared tool(s) (no API tool_calls) agentId={} tools={}',
            proseCalls.size(),
            agentId,
            proseCalls.collect { tc ->
              def fn = ((Map) tc)?.get('function')
              fn instanceof Map ? fn.get('name')?.toString() : null
            }.findAll { it?.trim() }.join(', ')
          )
        }
      }
      boolean fastPathPlaceholderWriteDropped = false
      int fastPathDiscoveryDropped = 0
      if (runList instanceof List && !runList.isEmpty()) {
        Map filterResult = plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.NewContentItemFastPathToolFilter
          .filterRunList(runList, intentTelLoop, slurper) as Map
        List filtered = filterResult.filtered instanceof List ? (List) filterResult.filtered : runList
        fastPathPlaceholderWriteDropped = Boolean.TRUE.equals(filterResult.placeholderWriteDropped)
        fastPathDiscoveryDropped = filterResult.discoveryDropped instanceof Number ?
          ((Number) filterResult.discoveryDropped).intValue() : 0
        if (fastPathDiscoveryDropped > 0 || fastPathPlaceholderWriteDropped) {
          runList = filtered
          if (runList.isEmpty()) {
            msgCopy.remove('tool_calls')
          } else {
            msgCopy.put('tool_calls', new ArrayList(runList))
          }
          log.info(
            'Tools-loop: new-content-item fast path filtered tool_calls agentId={} round={} discoveryDropped={} placeholderWriteDropped={} remaining={}',
            agentId,
            round,
            fastPathDiscoveryDropped,
            fastPathPlaceholderWriteDropped,
            runList.size()
          )
        }
      }
      boolean willRunTools = runList instanceof List && !runList.isEmpty()
      if (willRunTools && round > 0) {
        if (toolsLoopRunListHasExecutableTools(
          runList,
          fetchHttpUrlCallsThisTurn,
          maxFetchHttpUrlCallsThisTurn,
          fetchedHttpUrlsThisTurn,
          slurper
        )) {
          emitPendingToolsSse(ssePreToolAssistantText, runList, round, previousRoundHadRepoMutation)
        } else {
          emitToolsLoopModelTurnSse(ssePreToolAssistantText, round, previousRoundHadRepoMutation)
        }
      }
      if (ssePreToolAssistantText != null) {
        try {
          if (willRunTools) {
            String cleanedPreTool = assistantPreTool?.trim() ? stripForbiddenMetaPlanFromAssistantText(assistantPreTool.trim()) : ''
            String trimmedPlan = (cleanedPreTool ?: '').trim()
            if (trimmedPlan && shouldStreamPreToolAssistantSseForToolsLoop(trimmedPlan, toolsLoopSessionBundle, round)) {
              if (!isToolsLoopStatusFillerProse(trimmedPlan) || !toolsLoopStatusFillerAlreadyEmitted(toolsLoopSessionBundle)) {
                if (isToolsLoopStatusFillerProse(trimmedPlan)) {
                  markToolsLoopStatusFillerEmitted(toolsLoopSessionBundle)
                }
                def chunk = trimmedPlan + '\n\n'
                synchronized (ssePreToolAssistantText) {
                  ssePreToolAssistantText.write(
                    ("data: ${JsonOutput.toJson([text: chunk, metadata: [:]])}\n\n").getBytes(StandardCharsets.UTF_8)
                  )
                  ssePreToolAssistantText.flush()
                }
              } else {
                log.debug(
                  'Tools-loop tools-on: suppressed duplicate status filler before tool_calls agentId={} round={}',
                  agentId,
                  round
                )
              }
            } else if (trimmedPlan) {
              log.debug(
                'Tools-loop tools-on: suppressed pre-tool assistant SSE (confirmation-finalize recipe) agentId={} round={} chars={}',
                agentId,
                round,
                trimmedPlan.length()
              )
            } else {
              String fallbackPlan = minimalPlanWhenToolsWithoutProse(round)
              if (fallbackPlan?.trim() && !toolsLoopStatusFillerAlreadyEmitted(toolsLoopSessionBundle)) {
                markToolsLoopStatusFillerEmitted(toolsLoopSessionBundle)
                synchronized (ssePreToolAssistantText) {
                  ssePreToolAssistantText.write(
                    ("data: ${JsonOutput.toJson([text: fallbackPlan + '\n\n', metadata: [:]])}\n\n").getBytes(StandardCharsets.UTF_8)
                  )
                  ssePreToolAssistantText.flush()
                }
                log.info(
                  'Tools-loop tools-on: injected minimal prose before tool_calls (empty assistant content) agentId={} round={}',
                  agentId,
                  round
                )
              } else if (fallbackPlan?.trim()) {
                log.debug(
                  'Tools-loop tools-on: skipped duplicate minimal prose before tool_calls agentId={} round={}',
                  agentId,
                  round
                )
              } else {
                log.info(
                  'Tools-loop tools-on: no assistant text to stream before tool_calls (common for some models); tools still run. agentId={} round={}',
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
        emitSseAssistantTurnDebugPreview(ssePreToolAssistantText, assistantApiFlatForDebug, msgCopy, willRunTools, round, agentId)
      }
      mutateAssistantWireContentElideKnownGenerateImageDataUrls(msgCopy, generateImageDataUrlByToolCallId)
      wireMessages << msgCopy
      if (!willRunTools &&
        plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.NewContentItemFastPathToolFilter
          .isFastPath(intentTelLoop) &&
        toolsLoopRequiredToolsStillPending(requiredToolSuccess) &&
        (fastPathPlaceholderWriteDropped || fastPathDiscoveryDropped > 0)) {
        wireMessages << [
          role   : 'user',
          content: plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.NewContentItemFastPathToolFilter
            .buildPlaceholderWriteNudge(intentTelLoop)
        ]
        log.info(
          'Tools-loop: injected new-content-item fast path WriteContent nudge (placeholder plan JSON dropped) agentId={} round={}',
          agentId,
          round
        )
        continue
      }
      if (willRunTools) {
        boolean repoMutationThisRound = false
        boolean anySuccessfulFetchHttpUrl = false
        boolean roundHadWriteAttempt = false
        boolean roundHadWriteSuccess = false
        String roundSuccessfulWriteRepoPath = ''
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
          if (toolsLoopRecipeAllowlistBlocksTool(intentTelLoop, fnName, authorVisibleForToolsLoop)) {
            String blockedOut = JsonOutput.toJson([
              ok     : true,
              skipped: true,
              tool   : fnName,
              message:
                "${fnName} skipped: recipe toolsLoopAllowlist is active for this turn — use only: " +
                  (intentTelLoop?.toolsLoopAllowlist instanceof List ?
                    ((List) intentTelLoop.toolsLoopAllowlist).join(', ') : 'GenerateImage') +
                  '. Chat-only generate_image turns must not read or write repository XML.'
            ])
            wireMessages << [role: 'tool', tool_call_id: id, content: blockedOut]
            continue
          }
          String fetchHttpUrlThisCall = ''
          toolsRan = true
          def toolPol =
            plugins.org.craftercms.aiassistant.studio.engine.policy.ToolsLoopWirePolicyRegistry.policyFor(fnName)
          if ('ContentExists'.equals(fnName)) {
            String argsBefore = argsStr
            argsStr = applyCreateFromChatDraftPrefillToContentExistsArgs(argsStr, toolsLoopSessionBundle, slurper)
            if (!argsBefore.equals(argsStr) && fn instanceof Map) {
              fn.put('arguments', argsStr)
              log.info(
                'Tools-loop: ContentExists args prefilled from create-from-chat-draft prefetch path agentId={}',
                agentId
              )
            }
          }
          if ('write_content'.equals(toolPol.normalizeArgsId)) {
            argsStr = plugins.org.craftercms.aiassistant.studio.engine.catalog.AiOrchestrationTools.normalizeWriteContentToolArgsJson(argsStr)
            argsStr = applyCreateFromChatDraftPrefillToWriteContentArgs(argsStr, toolsLoopSessionBundle, slurper)
            argsStr = GeneratedImageCmsPersistence.resolveWriteContentArgsJson(
              argsStr,
              toolsLoopSessionBundle,
              generateImageDataUrlByToolCallId,
              ops,
              slurper
            )
            if (fn instanceof Map) {
              fn.put('arguments', argsStr)
            }
          }
          if (ops != null && fnName) {
            String argsBeforeSite = argsStr
            argsStr = AuthoringPreviewContext.ensureToolArgsSiteId(argsStr, fnName, ops, slurper)
            if (!argsBeforeSite.equals(argsStr) && fn instanceof Map) {
              fn.put('arguments', argsStr)
            }
          }
          if (toolsLoopBannedRepoPaths && !toolsLoopBannedRepoPaths.isEmpty() &&
            ('WriteContent'.equals(fnName) || 'GetContent'.equals(fnName) || 'update_content'.equals(fnName))) {
            try {
              Object argsParsedGuard = slurper.parseText(argsStr ?: '{}')
              if (argsParsedGuard instanceof Map) {
                String guardPath = repoPathFromToolArgsMap((Map) argsParsedGuard)
                if (guardPath && repoPathOnToolsLoopBannedList(guardPath, toolsLoopBannedRepoPaths)) {
                  String suggested = (intentTelLoop?.toolsLoopSuggestedNewItemPath ?: '').toString().trim()
                  String guardOut = JsonOutput.toJson([
                    ok                    : false,
                    skippedBannedRepoPath : true,
                    path                  : guardPath,
                    message               :
                      "${fnName} blocked: path is on the recipe **toolsLoopBannedRepoPaths** list for this turn. " +
                        (suggested ? "Use **toolsLoopSuggestedNewItemPath** `${suggested}` from prefetch. " : '') +
                        'Mirror **toolsLoopSiblingTemplatePath** / **quickCreatePath** from prefetch — not the open preview anchor.'
                  ])
                  wireMessages << [role: 'tool', tool_call_id: id, content: guardOut]
                  toolsLoopBannedRepoPathGuardHits++
                  continue
                }
              }
            } catch (Throwable ignoredBannedPathGuard) {
            }
          }
          if (toolPol.duplicateWritePathGuard) {
            try {
              Object argsParsed = slurper.parseText(argsStr ?: '{}')
              if (argsParsed instanceof Map) {
                String wpath = repoPathFromToolArgsMap((Map) argsParsed)
                if (wpath) {
                  String wkey = wpath.toLowerCase(Locale.ROOT)
                  boolean allowImageFollowUpWrite = false
                  if (writeContentPathsThisTurn.contains(wkey) && generateImageSucceededThisTurn) {
                    String candidateXml = ((Map) argsParsed).get('contentXml')?.toString() ?: ''
                    Object pending = toolsLoopSessionBundle?.get(GeneratedImageCmsPersistence.BUNDLE_PENDING_REPO_PATHS)
                    allowImageFollowUpWrite = candidateXml && pending instanceof List &&
                      ((List) pending).any { Object p ->
                        String rp = p?.toString()?.trim()
                        rp && GeneratedImageCmsPersistence.contentXmlContainsRepoPath(candidateXml, rp)
                      }
                  }
                  if (writeContentPathsThisTurn.contains(wkey) && !allowImageFollowUpWrite) {
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
          if (toolPol.duplicateGenerateImageThisTurnGuard &&
            (generateImageSucceededThisTurn || generateImageAlreadySucceededForTurn(toolsLoopSessionBundle))) {
            String skipOut = JsonOutput.toJson([
              ok                                   : true,
              skippedDuplicateGenerateImageThisTurn: true,
              tool                                 : 'GenerateImage',
              message                              :
                'GenerateImage skipped: one generated bitmap already completed this chat turn. ' +
                  'The image is in the Studio chat strip and was applied to the anchored page when applicable — ' +
                  'reply in short prose only. Do not call GenerateImage again unless the author explicitly asks for a different image.'
            ])
            wireMessages << [role: 'tool', tool_call_id: id, content: skipOut]
            continue
          }
          if (toolsLoopSkipRedundantPrefetchDiscovery(fnName, intentTelLoop) &&
            writeContentInvalidDocumentFailures == 0 &&
            writeContentRepeatedFormRejectionCount == 0 &&
            toolArgsMatchPrefetchedPathOrType(fnName, argsStr, toolsLoopSessionBundle, slurper)) {
            Map skipInp = toolProgressInputFromArgsJson(argsStr, slurper, fnName)
            long skipT0 = System.currentTimeMillis()
            writeToolProgressSse(ssePreToolAssistantText, fnName, 'start', skipInp, null, null, null, ops)
            String skipOut = JsonOutput.toJson([
              ok                         : true,
              skippedRedundantPrefetch   : true,
              tool                       : fnName,
              message                    :
                "${fnName} skipped: intent-recipe prefetch already loaded this page XML and form definition for this turn. " +
                  'Use the prefetch results — proceed to research (if needed), then **WriteContent**.'
            ])
            writeToolProgressSse(
              ssePreToolAssistantText,
              fnName,
              'warn',
              skipInp,
              null,
              slurper.parseText(skipOut),
              System.currentTimeMillis() - skipT0,
              ops
            )
            wireMessages << [role: 'tool', tool_call_id: id, content: skipOut]
            continue
          }
          if (toolsLoopSkipRepeatedFormDefinitionFetch(fnName, argsStr, toolsLoopSessionBundle, intentTelLoop, slurper)) {
            Map skipInp = toolProgressInputFromArgsJson(argsStr, slurper, fnName)
            long skipT0 = System.currentTimeMillis()
            writeToolProgressSse(ssePreToolAssistantText, fnName, 'start', skipInp, null, null, null, ops)
            String reqType = contentTypeIdFromFormDefinitionToolArgs(argsStr, slurper)
            Map validationPlan = newContentItemFormValidationPlan(intentTelLoop)
            List requiredHint = validationPlan.requiredFieldIds instanceof List ?
              (List) validationPlan.requiredFieldIds : []
            String skipOut = JsonOutput.toJson([
              ok                              : true,
              skippedDuplicateFormDefinition  : true,
              tool                            : fnName,
              contentTypeId                   : reqType,
              requiredFieldIds                : requiredHint,
              message                         :
                "GetContentTypeFormDefinition skipped: form definition for `${reqType}` was already loaded this turn (prefetch or prior tool call). " +
                  'Use **requiredFieldIds** / **formValidationPlan** from prefetch — fix **contentXml** and call **WriteContent** again.'
            ])
            writeToolProgressSse(
              ssePreToolAssistantText,
              fnName,
              'warn',
              skipInp,
              null,
              slurper.parseText(skipOut),
              System.currentTimeMillis() - skipT0,
              ops
            )
            wireMessages << [role: 'tool', tool_call_id: id, content: skipOut]
            continue
          }
          if (toolsLoopSkipDiscoveryUntilWriteContent(fnName, intentTelLoop, requiredToolSuccess)) {
            Map skipInp = toolProgressInputFromArgsJson(argsStr, slurper, fnName)
            long skipT0 = System.currentTimeMillis()
            writeToolProgressSse(ssePreToolAssistantText, fnName, 'start', skipInp, null, null, null, ops)
            Map validationPlan = newContentItemFormValidationPlan(intentTelLoop)
            List requiredHint = validationPlan.requiredFieldIds instanceof List ?
              (List) validationPlan.requiredFieldIds : []
            String requiredSuffix = requiredHint ?
              " Required fields for WriteContent: `${requiredHint.join('`, `')}`." : ''
            String skipOut = JsonOutput.toJson([
              ok                              : true,
              skippedUntilWriteContentPending : true,
              tool                            : fnName,
              message                         :
                "${fnName} skipped: recipe-engine prefetch already includes catalog, form definition, taxonomy keys, and suggested path. " +
                  (newContentItemPrefetchSupplementActive(intentTelLoop) ?
                    'Emit **native tool_calls** with **WriteContent** only — **full** contentXml inline (no ## Plan JSON placeholders).' + requiredSuffix :
                    'Use the prefetch JSON bindings — do not reload. Call **ContentExists** on your new path, then **WriteContent** once with full **contentXml** from **[Prior conversation]**.')
            ])
            writeToolProgressSse(
              ssePreToolAssistantText,
              fnName,
              'warn',
              skipInp,
              null,
              slurper.parseText(skipOut),
              System.currentTimeMillis() - skipT0,
              ops
            )
            wireMessages << [role: 'tool', tool_call_id: id, content: skipOut]
            continue
          }
          if (toolPol.repositoryMutation) {
            repoMutationThisRound = true
          }
          if (toolPol.skipWhenPriorWriteFailedInRound) {
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
          if ('GetPreviewHtml'.equals(fnName)) {
            String previewUrlThisCall = previewUrlFromToolArgsJson(argsStr, slurper)
            String previewUrlKey = normalizePreviewUrlKey(previewUrlThisCall)
            if (previewUrlKey && previewHtmlUrlsThisTurn.contains(previewUrlKey)) {
              String dupOut = JsonOutput.toJson([
                ok                              : true,
                skippedDuplicatePreviewThisTurn : true,
                url                             : previewUrlThisCall,
                message                         :
                  'GetPreviewHtml skipped: this preview URL was already fetched in this chat turn. ' +
                    'Use the prior preview result — reply to the author or call WriteContent only if content still needs correction.'
              ])
              wireMessages << [role: 'tool', tool_call_id: id, content: dupOut]
              continue
            }
            if (successfulPreviewFetchesThisTurn >= GET_PREVIEW_HTML_MAX_FETCHES_PER_TURN) {
              String limitOut = JsonOutput.toJson([
                ok                        : true,
                skippedPreviewFetchLimit  : true,
                maxPreviewFetchesPerTurn  : GET_PREVIEW_HTML_MAX_FETCHES_PER_TURN,
                message                   :
                  'GetPreviewHtml limit reached for this turn (' + GET_PREVIEW_HTML_MAX_FETCHES_PER_TURN +
                    '). Preview was already verified — finish with a short author summary; do not fetch preview again.'
              ])
              wireMessages << [role: 'tool', tool_call_id: id, content: limitOut]
              log.info(
                'Tools-loop: GetPreviewHtml capped at {} for turn agentId={} round={}',
                GET_PREVIEW_HTML_MAX_FETCHES_PER_TURN,
                agentId,
                round
              )
              continue
            }
          }
          if ('FetchHttpUrl'.equals(fnName)) {
            String fetchUrl = fetchHttpUrlFromToolArgsJson(argsStr, slurper)
            fetchHttpUrlThisCall = fetchUrl ? fetchUrl.trim() : ''
            Map intentTelFetch =
              (toolsLoopSessionBundle instanceof Map) ?
                (Map) toolsLoopSessionBundle.get('intentRecipeRoutingTelemetry') :
                null
            if (intentTelFetch instanceof Map &&
              Boolean.TRUE.equals(intentTelFetch.get('toolsLoopAuthorUrlExclusiveActive'))) {
              List<String> authorOnly = []
              Object au = intentTelFetch.get('toolsLoopAuthorProvidedUrls')
              if (au instanceof List) {
                for (Object o : (List) au) {
                  String s = o?.toString()?.trim()
                  if (s) {
                    authorOnly.add(s)
                  }
                }
              }
              if (fetchHttpUrlThisCall &&
                !AuthoringIntentRecipeCatalog.authorProvidedHttpUrlMatches(fetchHttpUrlThisCall, authorOnly)) {
                String blockedOut = JsonOutput.toJson([
                  ok      : false,
                  skipped : true,
                  url     : fetchUrl,
                  message :
                    'FetchHttpUrl blocked: this recipe turn is author-URL-only. Fetch only the http(s) link(s) the author pasted in Current request — do not fetch Serp results or other URLs.'
                ])
                wireMessages << [role: 'tool', tool_call_id: id, content: blockedOut]
                continue
              }
            }
            if (maxFetchHttpUrlCallsThisTurn > 0) {
              if (fetchHttpUrlThisCall && fetchedHttpUrlsThisTurn.contains(fetchHttpUrlThisCall)) {
                String dupOut = JsonOutput.toJson([
                  ok                       : false,
                  skippedDuplicateFetchThisTurn: true,
                  url                      : fetchUrl,
                  message                  :
                    'FetchHttpUrl skipped: this URL was already fetched in this chat turn. Use the prior tool result or pick a different citation from WebSearch.'
                ])
                wireMessages << [role: 'tool', tool_call_id: id, content: dupOut]
                continue
              }
              if (fetchHttpUrlCallsThisTurn >= maxFetchHttpUrlCallsThisTurn) {
                String limitOut = JsonOutput.toJson([
                  ok                      : false,
                  skippedFetchLimit       : true,
                  maxFetchHttpUrlCalls    : maxFetchHttpUrlCallsThisTurn,
                  message                 :
                    'FetchHttpUrl limit reached for this turn (' + maxFetchHttpUrlCallsThisTurn +
                      '). Use prior fetch results and search snippets — do not call FetchHttpUrl again.'
                ])
                wireMessages << [role: 'tool', tool_call_id: id, content: limitOut]
                log.info(
                  'Tools-loop: FetchHttpUrl capped at {} for web-research recipe agentId={} round={}',
                  maxFetchHttpUrlCallsThisTurn,
                  agentId,
                  round
                )
                continue
              }
              fetchHttpUrlCallsThisTurn++
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
              if ('WriteContent'.equals(fnName)) {
                Map researchGate = AuthoringResearchGrounding.gateWriteContent(toolsLoopSessionBundle)
                if (Boolean.FALSE.equals(researchGate.proceed)) {
                  Map wcInp = toolProgressInputFromArgsJson(argsStr, slurper)
                  long wcT0 = System.currentTimeMillis()
                  writeToolProgressSse(ssePreToolAssistantText, 'WriteContent', 'start', wcInp, null, null, null, ops)
                  toolOut = researchGate.toolOut?.toString() ?: ''
                  roundHadWriteAttempt = true
                  roundHadWriteFailure = true
                  Object researchGateParsed = null
                  try {
                    researchGateParsed = slurper.parseText(toolOut)
                  } catch (Throwable ignoredResearchGate) {
                  }
                  writeToolProgressSse(
                    ssePreToolAssistantText,
                    'WriteContent',
                    'warn',
                    wcInp,
                    null,
                    researchGateParsed,
                    System.currentTimeMillis() - wcT0,
                    ops
                  )
                  log.warn(
                    'Tools-loop: WriteContent blocked by research grounding gate agentId={} round={}',
                    agentId,
                    round
                  )
                } else {
                Map copyPlanGate = FormDefinitionCopyFieldPlan.gateWriteContent(toolsLoopSessionBundle, ops, argsStr, slurper)
                if (Boolean.FALSE.equals(copyPlanGate.proceed)) {
                  Map wcInp = toolProgressInputFromArgsJson(argsStr, slurper)
                  long wcT0 = System.currentTimeMillis()
                  writeToolProgressSse(ssePreToolAssistantText, 'WriteContent', 'start', wcInp, null, null, null, ops)
                  toolOut = copyPlanGate.toolOut?.toString() ?: ''
                  roundHadWriteAttempt = true
                  roundHadWriteFailure = true
                  Object copyPlanGateParsed = null
                  try {
                    copyPlanGateParsed = slurper.parseText(toolOut)
                  } catch (Throwable ignoredCopyPlanGate) {
                  }
                  writeToolProgressSse(
                    ssePreToolAssistantText,
                    'WriteContent',
                    'warn',
                    wcInp,
                    null,
                    copyPlanGateParsed,
                    System.currentTimeMillis() - wcT0,
                    ops
                  )
                  log.warn(
                    'Tools-loop: WriteContent blocked by copy-field-plan gate agentId={} round={}',
                    agentId,
                    round
                  )
                  String headlineNudge = FormDefinitionCopyFieldPlan.formatWriteContentGateRecoveryNudge(
                    toolsLoopSessionBundle,
                    copyPlanGateParsed instanceof Map ? (Map) copyPlanGateParsed : null
                  )
                  if (headlineNudge?.trim()) {
                    toolsLoopSessionBundle.toolsLoopPendingWriteContentRecoveryNudge = headlineNudge
                  }
                } else {
                Map siblingGate = gateNewContentItemWriteContent(argsStr, intentTelLoop, slurper, ops)
                if (Boolean.FALSE.equals(siblingGate.proceed)) {
                  Map wcInp = toolProgressInputFromArgsJson(argsStr, slurper)
                  long wcT0 = System.currentTimeMillis()
                  writeToolProgressSse(ssePreToolAssistantText, 'WriteContent', 'start', wcInp, null, null, null, ops)
                  toolOut = siblingGate.toolOut?.toString() ?: ''
                  roundHadWriteAttempt = true
                  roundHadWriteFailure = true
                  Object sibGateParsed = null
                  try {
                    sibGateParsed = slurper.parseText(toolOut)
                  } catch (Throwable ignoredSibGateParse) {
                  }
                  writeToolProgressSse(
                    ssePreToolAssistantText,
                    'WriteContent',
                    'warn',
                    wcInp,
                    null,
                    sibGateParsed,
                    System.currentTimeMillis() - wcT0,
                    ops
                  )
                  log.warn(
                    'Tools-loop: WriteContent blocked by new-content-item placeholder gate agentId={} round={}',
                    agentId,
                    round
                  )
                } else {
                argsStr = (siblingGate.argsStr ?: argsStr).toString()
                Map gate = gateCreateFromChatDraftWriteContent(argsStr, intentTelLoop, ops, slurper)
                if (Boolean.FALSE.equals(gate.proceed)) {
                  Map wcInp = toolProgressInputFromArgsJson(argsStr, slurper)
                  long wcT0 = System.currentTimeMillis()
                  writeToolProgressSse(ssePreToolAssistantText, 'WriteContent', 'start', wcInp, null, null, null, ops)
                  toolOut = gate.toolOut?.toString() ?: ''
                  roundHadWriteAttempt = true
                  roundHadWriteFailure = true
                  Object gateParsed = null
                  try {
                    gateParsed = slurper.parseText(toolOut)
                  } catch (Throwable ignoredGateParse) {
                  }
                  writeToolProgressSse(
                    ssePreToolAssistantText,
                    'WriteContent',
                    'warn',
                    wcInp,
                    null,
                    gateParsed,
                    System.currentTimeMillis() - wcT0,
                    ops
                  )
                  log.warn(
                    'Tools-loop: WriteContent blocked by createFromChatDraft write verification agentId={} round={}',
                    agentId,
                    round
                  )
                } else {
                  argsStr = (gate.argsStr ?: argsStr).toString()
                  Map preEnrich = gateWriteContentPreEnrich(argsStr, ops, slurper)
                  argsStr = (preEnrich.argsStr ?: argsStr).toString()
                  if (fn instanceof Map) {
                    fn.put('arguments', argsStr)
                  }
                  toolOut = ChatCompletionsToolWire.runWithNativeToolCallId(id) {
                    tcb.call(argsStr)
                  }
                }
                }
                }
                }
              } else if ('GenerateImage'.equals(fnName) && shouldBlockGenerateImageUntilCopyWrite(toolsLoopSessionBundle)) {
                Map giInp = toolProgressInputFromArgsJson(argsStr, slurper, fnName)
                long giT0 = System.currentTimeMillis()
                writeToolProgressSse(ssePreToolAssistantText, 'GenerateImage', 'start', giInp, null, null, null, ops)
                toolOut = JsonOutput.toJson([
                  ok                         : false,
                  skippedUntilWriteContent   : true,
                  tool                       : 'GenerateImage',
                  message                    :
                    'GenerateImage **blocked** — save page copy with **WriteContent** first. ' +
                      'Use a **newsroom-quality** headline (specific story angle), not the author\'s task wording or "Latest updates on…". ' +
                      'Then call **GenerateImage** for the hero image.'
                ])
                writeToolProgressSse(
                  ssePreToolAssistantText,
                  'GenerateImage',
                  'warn',
                  giInp,
                  null,
                  slurper.parseText(toolOut.toString()),
                  System.currentTimeMillis() - giT0,
                  ops
                )
                log.warn(
                  'Tools-loop: GenerateImage blocked until WriteContent succeeds (research page refresh) agentId={} round={}',
                  agentId,
                  round
                )
              } else {
                toolOut = ChatCompletionsToolWire.runWithNativeToolCallId(id) {
                  tcb.call(argsStr)
                }
              }
            } catch (Throwable tex) {
              log.warn('Tools-loop tools-on: tool {} failed: {}', fnName, tex.message)
              toolOut = JsonOutput.toJson([ok: false, error: tex.message?.toString()])
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
                  if (fetchHttpUrlThisCall && maxFetchHttpUrlCallsThisTurn > 0) {
                    fetchedHttpUrlsThisTurn.add(fetchHttpUrlThisCall)
                  }
                }
              }
            } catch (Throwable ignoredFetchOk) {
            }
          }
          if (toolPol.duplicateGenerateImageThisTurnGuard && 'GenerateImage'.equals(fnName)) {
            try {
              def parsedGi = slurper.parseText(toolOut.toString())
              if (parsedGi instanceof Map &&
                !Boolean.TRUE.equals(((Map) parsedGi).get('skippedDuplicateGenerateImageThisTurn'))) {
                boolean giOk = Boolean.TRUE.equals(((Map) parsedGi).get('ok'))
                Map gm = ChatCompletionsToolWire.unwrapGenerateImageToolResultMap((Map) parsedGi)
                String giUrl = ChatCompletionsToolWire.generateImageResultUrlString(gm)?.trim()
                boolean hasRenderableImage = (giUrl?.length() > 0) || gm.get('b64_json') != null
                if (giOk && hasRenderableImage) {
                  generateImageSucceededThisTurn = true
                  markTaskCompletionWallMsIfUnset(toolTimingCtx)
                }
              }
            } catch (Throwable ignoredGenImgDupTrack) {
            }
          }
          if (toolPol.duplicateWritePathGuard) {
            roundHadWriteAttempt = true
            try {
              def parsedW = slurper.parseText(toolOut.toString())
              if (parsedW instanceof Map && !Boolean.TRUE.equals(((Map) parsedW).get('skippedDuplicateWriteThisTurn'))) {
                boolean wOk = Boolean.TRUE.equals(((Map) parsedW).get('ok')) ||
                  'written'.equalsIgnoreCase(((Map) parsedW).get('result')?.toString()?.trim())
                if (wOk) {
                  roundHadWriteSuccess = true
                  lastPreviewContentGoalFound = null
                  successfulPreviewFetchesThisTurn =
                    invalidateTurnPreviewVerification(previewState, previewHtmlUrlsThisTurn, toolsLoopSessionBundle)
                  if (toolsLoopSessionBundle instanceof Map) {
                    toolsLoopSessionBundle.toolsLoopWriteContentOkThisTurn = Boolean.TRUE
                  }
                  markTaskCompletionWallMsIfUnset(toolTimingCtx)
                  Object argsParsed = slurper.parseText(argsStr ?: '{}')
                  if (argsParsed instanceof Map) {
                    Map wArgs = (Map) argsParsed
                    String wpath = repoPathFromToolArgsMap(wArgs)
                    if (wpath) {
                      writeContentPathsThisTurn.add(wpath.toLowerCase(Locale.ROOT))
                      roundSuccessfulWriteRepoPath = wpath
                      String wxml = (wArgs.contentXml ?: '').toString()
                      Object pending = toolsLoopSessionBundle?.get(GeneratedImageCmsPersistence.BUNDLE_PENDING_REPO_PATHS)
                      if (pending instanceof List) {
                        for (Object p : (List) pending) {
                          String rp = p?.toString()?.trim()
                          if (rp && GeneratedImageCmsPersistence.contentXmlContainsRepoPath(wxml, rp)) {
                            GeneratedImageCmsPersistence.markRepoPathApplied(toolsLoopSessionBundle, rp)
                          }
                        }
                      }
                      if (ops != null) {
                        try {
                          AuthoringIntentRecipeBindings.updateCurrentFromWrite(ops, wpath, wArgs)
                        } catch (Throwable ignoredBindingWrite) {
                        }
                      }
                      if (wxml?.trim()) {
                        FormDefinitionCopyFieldPlan.recordWrittenCopyForPreviewVerification(toolsLoopSessionBundle, wxml)
                      }
                    }
                  }
                } else {
                  roundHadWriteFailure = true
                  if (toolWireIndicatesInvalidSiteItemDocument(toolOut.toString())) {
                    writeContentInvalidDocumentFailures++
                    String formSig = writeContentFormRejectionSignature(toolOut.toString())
                    if (formSig) {
                      if (formSig.equals(lastWriteContentFormRejectionSignature)) {
                        writeContentRepeatedFormRejectionCount++
                      } else {
                        lastWriteContentFormRejectionSignature = formSig
                        writeContentRepeatedFormRejectionCount = 1
                      }
                    }
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
          if (toolPol.enrichPreviewHtmlResult) {
            Map enriched = enrichGetPreviewHtmlToolResult(toolOut.toString(), frozenAuthorOutcomePhrase, slurper, toolsLoopSessionBundle)
            toolOut = enriched.toolOut?.toString() ?: toolOut
            if (enriched.previewGoalFound instanceof Boolean) {
              lastPreviewContentGoalFound = enriched.previewGoalFound
              previewState.lastPreviewContentGoalFound = enriched.previewGoalFound
            }
            if (enriched.previewGoalPhrase) {
              lastPreviewContentGoalPhrase = enriched.previewGoalPhrase.toString()
              previewState.lastPreviewContentGoalPhrase = enriched.previewGoalPhrase.toString()
            }
            try {
              def parsedPrevOk = slurper.parseText(toolOut.toString())
              if (parsedPrevOk instanceof Map && Boolean.TRUE.equals(((Map) parsedPrevOk).get('ok'))) {
                successfulPreviewFetchesThisTurn++
                String previewUrlRecorded = previewUrlFromToolArgsJson(argsStr, slurper)
                String previewKey = normalizePreviewUrlKey(previewUrlRecorded)
                if (previewKey) {
                  previewHtmlUrlsThisTurn.add(previewKey)
                }
              }
            } catch (Throwable ignoredPrevCount) {
            }
            if (lastPreviewContentGoalFound == Boolean.FALSE) {
              String prevReason = (toolsLoopSessionBundle?.toolsLoopPreviewVerificationReason ?: '').toString()
              String prevPhrase = (lastPreviewContentGoalPhrase ?: toolsLoopSessionBundle?.toolsLoopPreviewVerificationPhrase ?: '').toString()
              log.warn(
                'Tools-loop: GetPreviewHtml verification failed reason={} phrase="{}" agentId={} round={}',
                prevReason ?: 'unknown',
                prevPhrase,
                agentId,
                round
              )
            }
          }
          String toolWire = truncateNativeToolWireContent(
            fnName,
            toolOut,
            id,
            generateImageDataUrlByToolCallId,
            toolsLoopSessionBundle
          )
          if ('GenerateImage'.equals(fnName) && generateImageDataUrlByToolCallId != null && ops != null &&
            !isGenerateImageChatOnlyRecipeTurn(intentTelLoop, authorVisibleForToolsLoop)) {
            String repoPath = GeneratedImageCmsPersistence.persistAfterGenerateImage(
              ops,
              id,
              generateImageDataUrlByToolCallId,
              toolsLoopSessionBundle
            )
            if (repoPath) {
              toolWire = GeneratedImageCmsPersistence.enrichGenerateImageToolWire(toolWire, repoPath, slurper)
              Map autoWrite = GeneratedImageCmsPersistence.tryAutoApplyPendingImageToAnchoredItem(ops, toolsLoopSessionBundle)
              if (autoWrite instanceof Map && Boolean.TRUE.equals(autoWrite.get('ok'))) {
                String autoPath = (autoWrite.path ?: autoWrite.contentPath ?: toolsLoopSessionBundle?.contentPath ?: '').toString().trim()
                if (autoPath) {
                  writeContentPathsThisTurn.add(autoPath.toLowerCase(Locale.ROOT))
                  roundSuccessfulWriteRepoPath = autoPath
                  repoMutationThisRound = true
                  roundHadWriteSuccess = true
                  lastPreviewContentGoalFound = null
                  successfulPreviewFetchesThisTurn =
                    invalidateTurnPreviewVerification(previewState, previewHtmlUrlsThisTurn, toolsLoopSessionBundle)
                  if (requiredToolSuccess.containsKey('WriteContent')) {
                    requiredToolSuccess.put('WriteContent', Boolean.TRUE)
                  }
                  markTaskCompletionWallMsIfUnset(toolTimingCtx)
                }
                ToolsLoopTurnArtifacts.record(
                  toolsLoopSessionBundle,
                  'WriteContent',
                  JsonOutput.toJson(autoWrite),
                  null,
                  slurper
                )
              }
            }
          }
          if ('ContentExists'.equals(fnName)) {
            toolWire = augmentContentExistsWireForCreateFromChatDraft(toolWire, toolOut.toString(), intentTelLoop, slurper)
          }
          ToolsLoopTurnArtifacts.record(toolsLoopSessionBundle, fnName, toolOut.toString(), argsStr, slurper)
          if ('GetContentTypeFormDefinition'.equals(fnName)) {
            markToolsLoopFormDefinitionFetched(
              toolsLoopSessionBundle,
              contentTypeIdFromFormDefinitionToolArgs(argsStr, slurper)
            )
          }
          AuthoringResearchGrounding.recordTool(toolsLoopSessionBundle, fnName, toolOut.toString(), slurper)
          emitStepBridgeForLatestArtifact(ssePreToolAssistantText, toolsLoopSessionBundle)
          if (toolWire.length() < toolOut.length() &&
            toolPol.wireOutputMode != plugins.org.craftercms.aiassistant.studio.engine.policy.ToolsLoopWirePolicy.WIRE_COMPACT_GENERATE_IMAGE &&
            toolPol.wireOutputMode != plugins.org.craftercms.aiassistant.studio.engine.policy.ToolsLoopWirePolicy.WIRE_COMPACT_FETCH_HTTP &&
            toolPol.wireOutputMode != plugins.org.craftercms.aiassistant.studio.engine.policy.ToolsLoopWirePolicy.WIRE_COMPACT_PREVIEW_HTML &&
            toolPol.wireOutputMode != plugins.org.craftercms.aiassistant.studio.engine.policy.ToolsLoopWirePolicy.WIRE_COMPACT_FORM_DEFINITION) {
            log.warn(
              'Tools-loop native tools: truncated tool wire output tool={} agentId={} beforeChars={} afterChars={}',
              fnName,
              agentId,
              toolOut.length(),
              toolWire.length()
            )
          }
          wireMessages << [role: 'tool', tool_call_id: id, content: toolWire]
          if ('GenerateImage'.equals(fnName) && generateImageSucceededThisTurn &&
            isGenerateImageChatOnlyRecipeTurn(intentTelLoop, authorVisibleForToolsLoop)) {
            assistantAccum = synthesizeGenerateImageChatOnlyComplete(
              authorVisibleForToolsLoop,
              generateImageDataUrlByToolCallId
            )
            log.info(
              'Tools-loop: immediate finish after GenerateImage (generate_image chat-only) round={} agentId={}',
              round,
              agentId
            )
            aiAssistantToolWorkerDiagPhase("native_tool_loop_round_${round}_immediate_finish_generate_image")
            finished = true
            break
          }
          if (requiredToolSuccess.containsKey(fnName)) {
            try {
              def parsedReq = slurper.parseText(toolOut.toString())
              boolean reqOk = parsedReq instanceof Map &&
                (Boolean.TRUE.equals(((Map) parsedReq).get('ok')) ||
                  'written'.equalsIgnoreCase(((Map) parsedReq).get('result')?.toString()?.trim()))
              if ('GenerateImage'.equals(fnName) && generateImageSucceededThisTurn) {
                reqOk = true
              }
              if (reqOk && !Boolean.TRUE.equals(((Map) parsedReq).get('skippedBannedRepoPath')) &&
                !Boolean.TRUE.equals(((Map) parsedReq).get('skippedDuplicateWriteThisTurn')) &&
                !Boolean.TRUE.equals(((Map) parsedReq).get('skippedDuplicateGenerateImageThisTurn')) &&
                !Boolean.TRUE.equals(((Map) parsedReq).get('skippedUntilWriteContent'))) {
                requiredToolSuccess.put(fnName, Boolean.TRUE)
              }
            } catch (Throwable ignoredReqTrack) {
            }
          }
        }
        injectPendingWriteContentRecoveryNudge(wireMessages, toolsLoopSessionBundle)
        if (finished) {
          break
        }
        if (toolsLoopBannedRepoPathGuardHits >= 4 && toolsLoopStallGuardInjectCount < 3) {
          String guardMsg = AuthoringIntentRecipeCatalog.formatToolsLoopRequiredToolsGuardMessage(intentTelLoop)
          if (guardMsg?.trim()) {
            wireMessages << [role: 'user', content: guardMsg]
            toolsLoopStallGuardInjectCount++
            log.warn(
              'Tools-loop: injected banned-repo-path guard after {} blocked tool calls agentId={} round={}',
              toolsLoopBannedRepoPathGuardHits,
              agentId,
              round
            )
          }
        }
        if (previewState.lastPreviewContentGoalFound instanceof Boolean) {
          lastPreviewContentGoalFound = previewState.lastPreviewContentGoalFound
        }
        if (previewState.lastPreviewContentGoalPhrase) {
          lastPreviewContentGoalPhrase = previewState.lastPreviewContentGoalPhrase.toString()
        }
        if (generateImageSucceededThisTurn && !generateImageWrapUpInjectedThisTurn) {
          boolean writeContentPending = requiredToolSuccess.containsKey('WriteContent') &&
            !Boolean.TRUE.equals(requiredToolSuccess.get('WriteContent'))
          if (writeContentPending) {
            Map<String, String> repoMap = GeneratedImageCmsPersistence.repoPathByToolCallId(toolsLoopSessionBundle)
            String repoHint = ''
            if (!repoMap.isEmpty()) {
              repoHint =
                '\n**GenerateImage repository path(s):** `' + repoMap.values().join('`, `') +
                  '`. Set image-picker fields to one of these paths inside **WriteContent**.'
            }
            String copyNudge = FormDefinitionCopyFieldPlan.formatPreWriteReminder(toolsLoopSessionBundle)
            wireMessages << [
              role   : 'user',
              content:
                '[Studio — WriteContent required before you finish]\n' +
                  '**GenerateImage** finished but the repository page was **not** updated. ' +
                  'You **must** call **WriteContent** now with the **full** page XML from prefetch/GetContent, ' +
                  'populating copy fields per the **[Studio — content field plan]**.' +
                  repoHint +
                  (copyNudge?.trim() ? '\n\n' + copyNudge.trim() : '')
            ]
            generateImageWrapUpInjectedThisTurn = true
            log.info(
              'Tools-loop: injected WriteContent-required nudge after GenerateImage agentId={} round={}',
              agentId,
              round
            )
          } else if (!toolsLoopRequiredToolsStillPending(requiredToolSuccess) &&
            !isGenerateImageChatOnlyRecipeTurn(intentTelLoop, authorVisibleForToolsLoop)) {
          String repoHint = ''
          Map<String, String> repoMap = GeneratedImageCmsPersistence.repoPathByToolCallId(toolsLoopSessionBundle)
          if (!repoMap.isEmpty()) {
            repoHint = ' Imported CMS path(s): ' + repoMap.values().join(', ') +
              '. When the turn goal requires an image on the page, call **WriteContent** with that path in the image-picker field — not studio-ai-inline-image://.'
          }
          wireMessages << [
            role   : 'user',
            content:
              'GenerateImage already finished for this chat turn.' + repoHint +
                ' Do not call GenerateImage again unless the author explicitly requests another image.'
          ]
          generateImageWrapUpInjectedThisTurn = true
          log.info(
            'Tools-loop: injected GenerateImage wrap-up after successful bitmap agentId={} round={}',
            agentId,
            round
          )
          }
        }
        boolean autoPreviewHttpOk = maybeAppendAutoConfirmationPreviewAfterRound(
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
          previewState,
          roundSuccessfulWriteRepoPath,
          previewHtmlUrlsThisTurn,
          successfulPreviewFetchesThisTurn
        )
        if (autoPreviewHttpOk) {
          successfulPreviewFetchesThisTurn++
        }
        if (Boolean.TRUE.equals(previewState.lastPreviewContentGoalFound)) {
          lastPreviewContentGoalFound = Boolean.TRUE
        }
        if (previewState.lastPreviewContentGoalFound instanceof Boolean) {
          lastPreviewContentGoalFound = previewState.lastPreviewContentGoalFound
        }
        if (previewState.lastPreviewContentGoalPhrase) {
          lastPreviewContentGoalPhrase = previewState.lastPreviewContentGoalPhrase.toString()
        }
        if (writeContentInvalidDocumentFailures >= 3 && !roundHadWriteSuccess) {
          log.warn(
            'Tools-loop: stopping after {} invalid WriteContent attempts (fragment/partial XML or form rejection) round={} agentId={}',
            writeContentInvalidDocumentFailures,
            round,
            agentId
          )
          assistantAccum = writeContentRepeatedFormRejectionCount >= 2 ?
            synthesizeFormDefinitionWriteRejectionMessage(lastInvalidWriteContentPath ?: '/site/website/index.xml') :
            synthesizeCorruptSiteItemXmlMessage(lastInvalidWriteContentPath ?: '/site/website/index.xml')
          finished = true
          break
        }
        if (writeContentRepeatedFormRejectionCount >= 2 && !roundHadWriteSuccess && round < maxRounds - 1) {
          wireMessages << [
            role   : 'user',
            content:
              '[aiassistant: WriteContent blocked — internal]\n' +
                '**WriteContent** failed repeatedly with the **same form-definition rejection** (invented field ids such as `orderDefault_f`). ' +
                'Call **GetContent** on the anchored path, change **only** existing field elements listed in the **content field plan** in that full document, then **WriteContent** once — do not invent new root elements.\n'
          ]
        }
        String createDraftNudge = formatCreateFromChatDraftWriteRejectionNudge(
          intentTelLoop,
          lastInvalidWriteContentPath,
          writeContentInvalidDocumentFailures,
          writeContentRepeatedFormRejectionCount
        )
        if (createDraftNudge?.trim() && !roundHadWriteSuccess && round < maxRounds - 1 && !finished) {
          wireMessages << [role: 'user', content: createDraftNudge]
        }
        if (writeContentInvalidDocumentFailures >= 2 && writeContentRepeatedFormRejectionCount < 2 &&
          !roundHadWriteSuccess && round < maxRounds - 1) {
          wireMessages << [
            role   : 'user',
            content:
              '[aiassistant: WriteContent blocked — internal]\n' +
                '**WriteContent** failed repeatedly because **contentXml** was a **fragment**, not a full `<page>` / `<component>` document. ' +
                '**Stop** calling **WriteContent** with invented or partial XML. Call **GetContent** on the anchored path, edit **one** existing field element in that full **contentXml**, then **WriteContent** the **entire** file once.\n'
          ]
        }
        String turnGoalReminder = AuthoringTurnGoal.formatMidLoopReminder(toolsLoopSessionBundle)
        if (turnGoalReminder?.trim() && round < maxRounds - 1 && !finished) {
          wireMessages << [role: 'user', content: turnGoalReminder]
        }
        String researchNudge = AuthoringResearchGrounding.formatPostRoundNudge(
          toolsLoopSessionBundle,
          roundHadWriteAttempt,
          anySuccessfulFetchHttpUrl
        )
        if (researchNudge?.trim() && round < maxRounds - 1 && !finished) {
          wireMessages << [role: 'user', content: researchNudge]
          log.info(
            'Tools-loop: injected research grounding nudge (search without fetch before write={}) agentId={} round={}',
            roundHadWriteAttempt,
            agentId,
            round
          )
        }
        String copyFieldNudge = FormDefinitionCopyFieldPlan.formatPreWriteReminder(toolsLoopSessionBundle)
        if (copyFieldNudge?.trim() && (roundHadWriteAttempt || anySuccessfulFetchHttpUrl) &&
          round < maxRounds - 1 && !finished) {
          wireMessages << [role: 'user', content: copyFieldNudge]
        }
        String synthesisNudge = AuthoringResearchGrounding.formatSynthesisBeforeWriteNudge(toolsLoopSessionBundle)
        if (synthesisNudge?.trim() && anySuccessfulFetchHttpUrl && round < maxRounds - 1 && !finished) {
          wireMessages << [role: 'user', content: synthesisNudge]
        }
        String heroImageNudge = AuthoringResearchGrounding.formatHeroImageAfterCopyNudge(toolsLoopSessionBundle)
        if (heroImageNudge?.trim() && round < maxRounds - 1 && !finished) {
          wireMessages << [role: 'user', content: heroImageNudge]
        }
        String artifactsBlock = ToolsLoopTurnArtifacts.formatInjectionBlock(toolsLoopSessionBundle)
        if (artifactsBlock?.trim() && round < maxRounds - 1 && !finished) {
          wireMessages << [role: 'user', content: artifactsBlock]
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
        if (generateImageSucceededThisTurn &&
          isGenerateImageChatOnlyRecipeTurn(intentTelLoop, authorVisibleForToolsLoop)) {
          assistantAccum = synthesizeGenerateImageChatOnlyComplete(
            authorVisibleForToolsLoop,
            generateImageDataUrlByToolCallId
          )
          log.info(
            'Tools-loop: early finish after GenerateImage (generate_image chat-only) round={} agentId={}',
            round,
            agentId
          )
          aiAssistantToolWorkerDiagPhase("native_tool_loop_round_${round}_early_finish_generate_image")
          finished = true
          break
        }
        if (repoMutationThisRound &&
          roundHadWriteSuccess &&
          toolsLoopPreviewVerificationAllowsEarlyFinish(
            successfulPreviewFetchesThisTurn,
            lastPreviewContentGoalFound,
            toolsLoopSessionBundle,
            true
          ) &&
          !toolsLoopRequiredToolsStillPending(requiredToolSuccess)) {
          String previewUrl = previewUrlForRepoPath(roundSuccessfulWriteRepoPath, toolsLoopSessionBundle, wireMessages)
          assistantAccum = synthesizePlanExecutionAfterVerifiedWrite(
            frozenAuthorOutcomePhrase ?: lastPreviewContentGoalPhrase,
            previewUrl
          )
          log.info(
            'Tools-loop: early finish after tool round — write ok, preview verified (fetches={} phraseFound={}); skip further LLM rounds round={} agentId={}',
            successfulPreviewFetchesThisTurn,
            lastPreviewContentGoalFound,
            round,
            agentId
          )
          aiAssistantToolWorkerDiagPhase("native_tool_loop_round_${round}_early_finish_after_tools_verified")
          finished = true
          break
        }
        if (!finished &&
          !writeContentPathsThisTurn.isEmpty() &&
          successfulPreviewFetchesThisTurn >= GET_PREVIEW_HTML_MAX_FETCHES_PER_TURN &&
          toolsLoopPreviewVerificationAllowsEarlyFinish(
            successfulPreviewFetchesThisTurn,
            lastPreviewContentGoalFound,
            toolsLoopSessionBundle,
            true
          )) {
          String previewUrl = previewUrlForRepoPath(
            roundSuccessfulWriteRepoPath ?: writeContentPathsThisTurn.iterator().next(),
            toolsLoopSessionBundle,
            wireMessages
          )
          assistantAccum = synthesizePlanExecutionAfterVerifiedWrite(
            frozenAuthorOutcomePhrase ?: lastPreviewContentGoalPhrase,
            previewUrl
          )
          log.info(
            'Tools-loop: early finish after preview fetch cap ({}); write paths={} round={} agentId={}',
            GET_PREVIEW_HTML_MAX_FETCHES_PER_TURN,
            writeContentPathsThisTurn.size(),
            round,
            agentId
          )
          finished = true
          break
        }
        recompactPreviewHtmlOnWire(wireMessages)
        previousRoundHadRepoMutation =
          createFromChatDraftWriteVerificationActive(intentTelLoop) ?
            roundHadWriteSuccess :
            repoMutationThisRound
        consecutiveToolOnlyRounds++
        if (consecutiveToolOnlyRounds >= TOOLS_LOOP_STALL_FORCE_FINISH_ROUND) {
          log.warn(
            'Tools-loop: force finish after {} consecutive tool-only rounds (maxRounds={}) agentId={}',
            consecutiveToolOnlyRounds,
            maxRounds,
            agentId
          )
          assistantAccum = synthesizeToolsLoopStallExceededMessage(
            round,
            maxRounds,
            toolsLoopSessionBundle,
            consecutiveToolOnlyRounds
          )
          finished = true
          break
        }
        if (consecutiveToolOnlyRounds >= TOOLS_LOOP_STALL_GUARD_FIRST_ROUND && toolsLoopStallGuardInjectCount < 3) {
          wireMessages << [
            role   : 'user',
            content: buildToolsLoopStallGuardUserMessage(toolsLoopSessionBundle, consecutiveToolOnlyRounds)
          ]
          toolsLoopStallGuardInjectCount++
          log.info(
            'Tools-loop: injected stall guard after {} consecutive tool-only rounds agentId={} round={}',
            consecutiveToolOnlyRounds,
            agentId,
            round
          )
        }
        continue
      }
      consecutiveToolOnlyRounds = 0
      if (toolsLoopRequiredToolsStillPending(requiredToolSuccess)) {
        if (round < maxRounds - 1) {
          toolsLoopRequiredToolsNoFinishBlocks++
          String guardMsg = AuthoringIntentRecipeCatalog.formatToolsLoopRequiredToolsGuardMessage(intentTelLoop)
          if (guardMsg?.trim()) {
            wireMessages << [role: 'user', content: guardMsg]
          }
          log.warn(
            'Tools-loop: blocked prose-only finish (required tools pending) agentId={} round={} blocks={} required={}',
            agentId,
            round,
            toolsLoopRequiredToolsNoFinishBlocks,
            toolsLoopRequiredWireNames
          )
          continue
        }
        assistantAccum = AuthoringIntentRecipeCatalog.formatToolsLoopRequiredToolsMissedMessage(intentTelLoop)
        finished = true
        break
      }
      boolean assistClaimsTurnComplete =
        assistantProseClaimsTurnCompleteDespitePlanBullets(assistantTextFromChoiceMessageMap(msgCopy) ?: '')
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
      String markdownForConf = resolveMarkdownForRecipeConfirmation(
        wireMessages,
        assistantTextFromChoiceMessageMap(msgCopy) ?: ''
      )
      Map recipeConf = runMatchedRecipeConfirmationIfNeeded(
        wireMessages,
        toolsLoopSessionBundle,
        agentId,
        round,
        markdownForConf,
        ssePreToolAssistantText
      )
      if (Boolean.TRUE.equals(recipeConf.ran)) {
        if (Boolean.TRUE.equals(recipeConf.finalizeWithoutLlmRound) &&
          recipeConf.finalizeAuthorText?.toString()?.trim()) {
          assistantAccum = recipeConf.finalizeAuthorText.toString()
          aiAssistantToolWorkerDiagPhase("native_tool_loop_round_${round}_recipe_confirmation_finalized")
          finished = true
          break
        }
        String injectMd = (recipeConf.wireInjectMarkdown ?: '').toString().trim()
        if (injectMd) {
          wireMessages << [
            role   : 'user',
            content:
              injectMd +
                '\nIncorporate these confirmation results in **## Plan Execution** (✅/❌/⚠️). Confirmation tools were executed by Studio — do not call them again via **tool_calls**.\n'
          ]
          aiAssistantToolWorkerDiagPhase("native_tool_loop_round_${round}_recipe_confirmation_steps_injected")
          continue
        }
      }
      aiAssistantToolWorkerDiagPhase("native_tool_loop_round_${round}_final_assistant_message_no_more_tools")
      assistantAccum = assistantTextFromChoiceMessageMap(msgCopy)
      boolean writeContentStillRequired = requiredToolSuccess.containsKey('WriteContent') &&
        !Boolean.TRUE.equals(requiredToolSuccess.get('WriteContent'))
      boolean pseudoNarration = looksLikePseudoToolCallNarration(assistantAccum, byName)
      if ((!toolsRan || writeContentStillRequired) && pseudoNarration) {
        log.warn(
          'Tools-loop: detected pseudo tool-call narration (toolsRan={} writeContentRequired={}) agentId={} round={}',
          toolsRan,
          writeContentStillRequired,
          agentId,
          round
        )
        if (toolsLoopSessionBundle instanceof Map) {
          toolsLoopSessionBundle.pseudoToolNarrationBlocked = Boolean.TRUE
        }
        if (writeContentStillRequired && round < maxRounds - 1) {
          String guardMsg = AuthoringIntentRecipeCatalog.formatToolsLoopRequiredToolsGuardMessage(intentTelLoop)
          if (guardMsg?.trim()) {
            wireMessages << [role: 'user', content: guardMsg]
          }
          String copyNudge = FormDefinitionCopyFieldPlan.formatPreWriteReminder(toolsLoopSessionBundle)
          if (copyNudge?.trim()) {
            wireMessages << [role: 'user', content: copyNudge]
          }
          continue
        }
        assistantAccum = writeContentStillRequired ?
          AuthoringIntentRecipeCatalog.formatToolsLoopRequiredToolsMissedMessage(intentTelLoop) :
          pseudoToolNarrationFallbackMessage()
      } else if (!toolsRan && pseudoNarration) {
        log.warn(
          'Tools-loop: detected pseudo tool-call narration without executed tools; replacing final assistant text with fallback agentId={} round={}',
          agentId,
          round
        )
        if (toolsLoopSessionBundle instanceof Map) {
          toolsLoopSessionBundle.pseudoToolNarrationBlocked = Boolean.TRUE
        }
        assistantAccum = pseudoToolNarrationFallbackMessage()
      }
      finished = true
      break
    }
    if (!finished) {
      log.warn(
        'Tools-loop: exceeded {} tool rounds without a final assistant message ({} consecutive tool-only); synthesizing author-visible stop agentId={}',
        maxRounds,
        consecutiveToolOnlyRounds,
        agentId
      )
      assistantAccum = synthesizeToolsLoopStallExceededMessage(
        maxRounds - 1,
        maxRounds,
        toolsLoopSessionBundle,
        consecutiveToolOnlyRounds > 0 ? consecutiveToolOnlyRounds : maxRounds
      )
    }
    if (!(Boolean.TRUE.equals(toolsLoopSessionBundle?.recipeConfirmationStepsExecuted))) {
      String lateMarkdown = resolveMarkdownForRecipeConfirmation(
        wireMessages,
        (assistantAccum ?: '').toString()
      )
      Map lateConf = runMatchedRecipeConfirmationIfNeeded(
        wireMessages,
        toolsLoopSessionBundle,
        agentId,
        maxRounds,
        lateMarkdown,
        ssePreToolAssistantText
      )
      if (Boolean.TRUE.equals(lateConf.ran) && lateConf.finalizeAuthorText?.toString()?.trim()) {
        assistantAccum = lateConf.finalizeAuthorText.toString()
        log.info(
          'Tools-loop: recipe confirmation flushed at loop end (author markdown) agentId={}',
          agentId
        )
      }
    }
    return [
      text              : (assistantAccum ?: ''),
      previewGoalFound  : lastPreviewContentGoalFound,
      previewGoalPhrase : (lastPreviewContentGoalPhrase ?: ''),
      toolsRan          : toolsRan
    ]
  }

  /**
   * Tools-loop native tools without {@link OpenAiChatModel}: sync {@code stream:false} rounds + {@link JsonSlurper}
   * + {@link FunctionToolCallback#call(String)} until the assistant stops calling tools.
   * <p>One chat session with tools enabled: the model should stream a **## Plan** (see {@link ToolPrompts#getLlm_AUTHORING_PLAN_WHEN_WARRANTED()}) in the
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
    StudioToolOperations loopOps =
      (toolsLoopSessionBundle?.studioOps instanceof StudioToolOperations) ?
        (StudioToolOperations) toolsLoopSessionBundle.studioOps :
        null
    Map loopToolCfg = [:]
    if (loopOps != null) {
      try {
        loopToolCfg = StudioAiAssistantProjectConfig.load(loopOps)
      } catch (Throwable ignoredCfg) {
      }
    }
    if (intentTel instanceof Map && loopOps != null) {
      String forceTool = intentTel.get('toolsLoopForceTool')?.toString()?.trim() ?: ''
      String rewrittenForce =
        SerpApiWebSearchProjectSettings.rewriteWebSearchWireName(forceTool, loopOps, loopToolCfg)
      if (forceTool && rewrittenForce && !forceTool.equals(rewrittenForce)) {
        intentTel.put('toolsLoopForceTool', rewrittenForce)
        log.info(
          'Tools-loop: toolsLoopForceTool WebSearch → SerpApiWebSearch (SerpAPI key resolved) agentId={} recipeId={}',
          agentId,
          intentTel.get('recipeId') ?: ''
        )
      }
    }
    List effectiveTools = tools
    effectiveTools = effectiveToolsForIntentRecipe(tools, intentTel, authorVisible, agentId, loopOps, loopToolCfg)
    effectiveTools = applyRecipeToolsLoopExcludes(effectiveTools, intentTel)
    if (intentTel instanceof Map && isGenerateImageRecipeMatchedTurn(intentTel)) {
      if (!wireToolsIncludeNamedTool(buildWireToolsFromCallbacks(effectiveTools), 'GenerateImage')) {
        intentTel.put('generateImageToolUnavailable', Boolean.TRUE)
      } else if (!intentTel.get('toolsLoopForceTool')?.toString()?.trim()) {
        intentTel.put('toolsLoopForceTool', 'GenerateImage')
        log.info(
          'Tools-loop: generate_image recipe matched — forcing first tool GenerateImage agentId={}',
          agentId
        )
      }
    } else if (intentTel instanceof Map && 'generate_image'.equals(intentTel.get('recipeId')?.toString()?.trim())) {
      if (!wireToolsIncludeNamedTool(buildWireToolsFromCallbacks(effectiveTools), 'GenerateImage')) {
        intentTel.put('generateImageToolUnavailable', Boolean.TRUE)
      } else if (isGenerateImageChatOnlyRecipeTurn(intentTel, authorVisible) &&
        !intentTel.get('toolsLoopForceTool')?.toString()?.trim()) {
        intentTel.put('toolsLoopForceTool', 'GenerateImage')
        log.info(
          'Tools-loop: generate_image chat-only — forcing first tool GenerateImage agentId={}',
          agentId
        )
      }
    }
    if (intentTel instanceof Map && Boolean.TRUE.equals(intentTel.get('generateImageToolUnavailable'))) {
      log.info('Tools-loop: returning imageModel configuration message (GenerateImage not registered) agentId={}', agentId)
      return synthesizeGenerateImageUnavailableMarkdown()
    }
    def wireTools = buildWireToolsFromCallbacks(effectiveTools)
    if (intentTel instanceof Map) {
      String forceTool = intentTel.get('toolsLoopForceTool')?.toString()?.trim() ?: ''
      if (forceTool && !AuthoringIntentRecipeCatalog.isToolRegisteredOnWire(wireTools, forceTool)) {
        String reason = AuthoringIntentRecipeCatalog.explainToolsLoopForceToolUnavailable(forceTool, loopToolCfg, loopOps)
        String reasonForLog = (reason ?: '').replaceAll('\\*', '')
        intentTel.put('toolsLoopForceToolUnavailable', Boolean.TRUE)
        log.warn(
          'Tools-loop: recipe {} requires toolsLoopForceTool {} but it is not on the wire — {} agentId={}',
          intentTel.get('recipeId')?.toString()?.trim() ?: '(unknown)',
          forceTool,
          reasonForLog ?: '(no explanation)',
          agentId
        )
        return synthesizeForcedToolUnavailableMarkdown(
          forceTool,
          intentTel.get('recipeTitle')?.toString(),
          intentTel.get('recipeId')?.toString(),
          reason
        )
      }
    }
    if (!wireTools) {
      if (intentTel instanceof Map && Boolean.TRUE.equals(intentTel.get('generateImageToolUnavailable'))) {
        return synthesizeGenerateImageUnavailableMarkdown()
      }
      throw new IllegalStateException('tools: empty tool list')
    }
    Map<String, FunctionToolCallback> byName = toolCallbacksByName(effectiveTools)
    List<Map> wireMessages = deepCloneWireMessages(baseWire)
    AuthoringTurnGoal.appendToSystemWireMessage(wireMessages, toolsLoopSessionBundle)
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
    List<String> requiredWriteToolsForReview = stringListFromRecipeTelemetry(intentTel, 'toolsLoopRequireSuccessfulTools')
    boolean writeContentRequiredButMissing =
      !Boolean.TRUE.equals(toolsLoopSessionBundle?.toolsLoopWriteContentOkThisTurn) &&
        (
          requiredWriteToolsForReview.contains('WriteContent') ||
            'modify_page_content'.equals(intentTel?.recipeId?.toString()?.trim()) ||
            AuthoringPreviewContext.authorVisibleSuggestsAnchoredPageContentModificationForAuthorText(origUser, origUser)
        )
    boolean skipPostToolReviewForPendingPageWrite =
      !writeContentRequiredButMissing &&
      !Boolean.TRUE.equals(toolsLoopSessionBundle?.toolsLoopWriteContentOkThisTurn) &&
      AuthoringResearchGrounding.hasSubstantiveRetrievedSource(toolsLoopSessionBundle) &&
      (
        'modify_page_content'.equals(intentTel?.recipeId?.toString()?.trim()) ||
        requiredWriteToolsForReview.contains('WriteContent') ||
        AuthoringPreviewContext.authorVisibleSuggestsAnchoredPageContentModificationForAuthorText(origUser, origUser)
      )
    if (postToolReviewEnabled(toolsLoopSessionBundle) &&
      !skipPostToolReviewForGenerateImageRecipe(toolsLoopSessionBundle) &&
      !skipPostToolReviewForPendingPageWrite &&
      (cancelRequested == null || !cancelRequested.get())) {
      try {
        emitSseToolProgressLine(
          sseOut,
          '🛠️🔄 **Post-tool review** … comparing your request to the assistant reply (tools-loop path only; no repository writes).\n',
          'start',
          'summary'
        )
        Map rev = postToolReview(
          apiKey,
          model,
          origUser,
          assistantAccum,
          agentId,
          sseOut,
          toolsLoopSessionBundle?.authorTurnSuccessCriteria?.toString()?.trim() ?: '',
          toolsLoopSessionBundle?.authorTurnGoal?.toString()?.trim() ?: '',
          toolsLoopSessionBundle
        )
        emitSseToolProgressLine(
          sseOut,
          '🛠️🔄 ✅ **Post-tool review** finished.\n',
          'done',
          'summary'
        )
        boolean acc = rev?.accomplished != null && Boolean.TRUE.equals(rev.accomplished)
        List<String> requiredWriteTools = stringListFromRecipeTelemetry(intentTel, 'toolsLoopRequireSuccessfulTools')
        if (requiredWriteTools.contains('WriteContent') &&
          !Boolean.TRUE.equals(toolsLoopSessionBundle?.toolsLoopWriteContentOkThisTurn)) {
          acc = false
          if (!(rev?.correctionInstructions ?: '').toString().trim()) {
            rev = [
              accomplished           : Boolean.FALSE,
              reason                   : 'WriteContent did not succeed — page copy was not saved.',
              correctionInstructions   :
                'Call **WriteContent** with the **full** prefetched page XML, updated copy per the content field plan, ' +
                  'and the **GenerateImage** repository path on image-picker fields. Do not finish with prose-only tool JSON.'
            ]
          }
        }
        if (!acc && toolsLoopResearchPageRefreshSubstantiallyComplete(toolsLoopSessionBundle)) {
          log.info(
            'Tools-loop post-tool review: research page refresh write+image ok — skipping correction pass agentId={}',
            agentId
          )
          acc = true
        }
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
    sanitized = appendGenerateImageDeliveryWarningIfNeeded(
      sanitized,
      toolsLoopSessionBundle,
      mergedImgUrls
    )
    sanitized = appendPreviewVerificationWarningIfNeeded(
      sanitized,
      lastPreviewContentGoalFound,
      lastPreviewContentGoalPhrase,
      toolsLoopSessionBundle
    )
    if (Boolean.TRUE.equals(toolsLoopSessionBundle?.toolsLoopWriteContentOkThisTurn) &&
      toolsLoopSessionBundle?.toolsLoopPreviewHttpOk == Boolean.FALSE &&
      !(sanitized ?: '').contains('## Not done yet')) {
      String previewUrl = previewUrlForRepoPath(
        firstPersistedWriteContentRepoPath(toolsLoopSessionBundle),
        toolsLoopSessionBundle,
        baseWire
      )
      sanitized = synthesizePlanExecutionAfterWritePendingPreview(previewUrl, toolsLoopSessionBundle)
    }
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
  private static void writeSseFinalAssistantTextChunks(
    OutputStream out,
    String finalText,
    Map sseMetadata = null
  ) throws IOException {
    String t = finalText != null ? finalText.toString() : ''
    Map meta = sseMetadata instanceof Map ? new LinkedHashMap(sseMetadata) : [:]
    int step = NATIVE_TOOLS_FINAL_SSE_TEXT_CHUNK_CHARS
    if (!t) {
      out.write(("data: ${JsonOutput.toJson([text: '', metadata: meta])}\n\n").getBytes(StandardCharsets.UTF_8))
      return
    }
    if (t.length() <= step) {
      out.write(("data: ${JsonOutput.toJson([text: t, metadata: meta])}\n\n").getBytes(StandardCharsets.UTF_8))
      return
    }
    log.info(
      'Tools-loop native tools: splitting final assistant SSE into {} chunks (totalChars={} chunkChars={})',
      (int) Math.ceil(t.length() / (double) step),
      t.length(),
      step
    )
    boolean first = true
    for (int i = 0; i < t.length();) {
      int end = toolsLoopSseTextChunkEndExclusive(t, i, step)
      String part = t.substring(i, end)
      Map chunkMeta = first ? meta : [:]
      first = false
      out.write(("data: ${JsonOutput.toJson([text: part, metadata: chunkMeta])}\n\n").getBytes(StandardCharsets.UTF_8))
      i = end
    }
  }

  /**
   * Write tools on via rest client tool loop.
   */
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
          Map<String, String> mergedImg =
            mergedGenerateImageUrlByToolCallId([:], generateImageBacklogByToolCallId)
          StudioToolOperations opsForImg =
            (toolsLoopSessionBundle?.studioOps instanceof StudioToolOperations) ?
              (StudioToolOperations) toolsLoopSessionBundle.studioOps :
              null
          Map<String, String> metaUrls = compactGenerateImageUrlsForAuthoringChat(mergedImg, opsForImg)
          String authorMarkdown
          if (!metaUrls.isEmpty()) {
            writeSseStudioAiInlineImageUrlsMetadata(out, metaUrls)
            authorMarkdown =
              sanitizeAssistantMarkdownReplaceGenerateImageDataUrlsWithRefs(finalChunk, mergedImg)
            authorMarkdown =
              ChatCompletionsToolWire.appendMissingInlineImageRefs(authorMarkdown, mergedImg, null)
          } else {
            // Import/metadata unavailable: keep execute path expanded data: URLs for the client image strip.
            authorMarkdown = finalChunk
          }
          Map plan = toolsLoopSessionBundle?.recipeExecutionPlan instanceof Map ?
            (Map) toolsLoopSessionBundle.recipeExecutionPlan :
            null
          Map finalSseMeta = [:]
          if (recipeConfirmationShouldFinalizeAuthorVisible(plan)) {
            finalSseMeta.replaceAssistantBody = Boolean.TRUE
          }
          writeSseFinalAssistantTextChunks(out, authorMarkdown, finalSseMeta)
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
   * Tools-loop path with native tools off: explicit {@code stream=true} on the request record (no {@link OpenAiChatModel} merge).
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
    def wireMessages = chatMessagesWireShape(authoringChatPrompt)
    // Map + JsonOutput (not ChatCompletionRequest / reflection): sandbox blocks Class.getConstructor.
    def reqMap = [
      model   : model,
      messages: wireMessages,
      stream  : true
    ]
    def jsonBody = chatCompletionsWireBodyApplyNeoTemperaturePolicy(JsonOutput.toJson(reqMap))
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
      wireMessages.size()
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

  /** Upstream chat errors often include a JSON body with {@code error.message} — log / surface it for debugging. */
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

  /**
   * Format stream error message.
   * @param t Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
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
    String imageGenerator = null,
    String llmSecretKey = null
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
      def springAi = buildSpringAiChatClient(agentId, chatId, llm, chatModel, llmApiKey, null, imageModel, fullSuppress, protNorm, enableTools, imageGenerator, llmSecretKey)
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
          springAi.studioOps
        )
        if (route?.intentRecipeRoutingTelemetry instanceof Map) {
          springAi.intentRecipeRoutingTelemetry = route.intentRecipeRoutingTelemetry
        }
        if (route?.intentRecipeRoutingWireCand != null) {
          springAi.intentRecipeRoutingWireCand = route.intentRecipeRoutingWireCand.toString()
        }
        applyIntentRecipeRouteEffects(springAi, route)
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
          ? springAi.chatClient.prompt().user(userText).toolCallbacks(*springAi.tools)
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
    return plugins.org.craftercms.aiassistant.studio.engine.policy.ToolsLoopWirePolicyRegistry.isExpertGuidanceWire(toolName)
  }

  /**
   * Second emoji after 🛠️ on server tool-progress lines — from {@link plugins.org.craftercms.aiassistant.studio.engine.policy.ToolsLoopWirePolicyRegistry}.
   */
  private static String toolProgressCategoryEmoji(String toolName) {
    if ('Tools-loop chat'.equals((toolName ?: '').toString().trim())) {
      return '🔄'
    }
    return plugins.org.craftercms.aiassistant.studio.engine.policy.ToolsLoopWirePolicyRegistry.progressCategoryEmoji(toolName)
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
   * Logs + SSE immediately before each blocking {@code POST /v1/chat/completions} in the native tool loop.
   * Round 0 emits "Working on your request"; follow-up rounds emit nothing here — the per-round tool-list
   * announcement is emitted *after* the response arrives via {@link #emitPendingToolsSse}.
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
    if (o == null || zeroBasedRound > 0) {
      return
    }
    try {
      String toolName = 'Tools-loop chat'
      String pfx = toolProgressLinePrefix(toolName)
      String line = pfx + ' **Working on your request** …\n'
      String stage =
        pipelineStageForToolsLoopChatLine(line, 'start', false, 0)
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

  /**
   * True when at least one tool in the model's {@code tool_calls} list will run (not capped/skipped by loop guards).
   */
  private static boolean toolsLoopRunListHasExecutableTools(
    List runList,
    int fetchHttpUrlCallsThisTurn,
    int maxFetchHttpUrlCallsThisTurn,
    Set<String> fetchedHttpUrlsThisTurn,
    JsonSlurper slurper
  ) {
    if (!(runList instanceof List) || runList.isEmpty()) {
      return false
    }
    for (Object tcObj : runList) {
      if (!(tcObj instanceof Map)) {
        continue
      }
      Map tc = (Map) tcObj
      def fn = tc.get('function')
      String fnName = fn instanceof Map ? (fn.get('name')?.toString() ?: '') : ''
      if (!fnName) {
        continue
      }
      if (!'FetchHttpUrl'.equals(fnName)) {
        return true
      }
      if (maxFetchHttpUrlCallsThisTurn > 0 && fetchHttpUrlCallsThisTurn >= maxFetchHttpUrlCallsThisTurn) {
        continue
      }
      String argsStr = fn instanceof Map ? (fn.get('arguments')?.toString() ?: '{}') : '{}'
      String url = fetchHttpUrlFromToolArgsJson(argsStr, slurper)?.trim()
      if (url && fetchedHttpUrlsThisTurn instanceof Set && fetchedHttpUrlsThisTurn.contains(url)) {
        continue
      }
      return true
    }
    return false
  }

  /**
   * Model returned {@code tool_calls} that the loop will skip (e.g. duplicate FetchHttpUrl) — still waiting on chat.
   */
  private static void emitToolsLoopModelTurnSse(
    OutputStream o,
    int zeroBasedRound,
    boolean previousRoundHadRepoMutation
  ) {
    if (o == null) {
      return
    }
    try {
      String toolName = 'Tools-loop chat'
      String pfx = toolProgressLinePrefix(toolName)
      String label = previousRoundHadRepoMutation ? '**Checking the result** …' : '**Continuing model turn** …'
      String line = pfx + ' ' + label + '\n'
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

  /**
   * Emitted after the LLM response arrives and before tools run — announces what tools are about to execute.
   * This replaces the generic "Continuing …" banner with meaningful intent (e.g. "Writing content …",
   * "Checking the result …", "Reading page content …").
   */
  private static void emitPendingToolsSse(
    OutputStream o,
    List runList,
    int zeroBasedRound,
    boolean previousRoundHadRepoMutation
  ) {
    if (o == null || !(runList instanceof List) || runList.isEmpty()) {
      return
    }
    try {
      List<String> names = []
      for (Object tc : runList) {
        if (!(tc instanceof Map)) continue
        def fn = ((Map) tc).get('function')
        String n = fn instanceof Map ? fn.get('name')?.toString()?.trim() : null
        if (n && !names.contains(n)) {
          names << n
        }
      }
      if (names.isEmpty()) {
        return
      }
      String label
      if (previousRoundHadRepoMutation) {
        label = '**Checking the result** …'
      } else if (names.any { it == 'WriteContent' || it == 'update_content' }) {
        label = '**Writing content** …'
      } else if (names.any { it == 'GetContent' }) {
        label = '**Reading content** …'
      } else if (names.any { it == 'GetPreviewHtml' }) {
        label = '**Verifying the preview** …'
      } else if (names.any { it == 'FetchHttpUrl' }) {
        label = '**Fetching source** …'
      } else if (names.any { it == 'WebSearch' || it == 'SerpApiWebSearch' }) {
        label = '**Searching the web** …'
      } else if (names.any { it == 'SlackPostMessage' }) {
        label = '**Posting to Slack** …'
      } else if (names.size() == 1) {
        label = "**${names[0]}** …"
      } else {
        label = "**${names[0]}** + ${names.size() - 1} more …"
      }
      String toolName = 'Tools-loop chat'
      String pfx = toolProgressLinePrefix(toolName)
      String line = pfx + ' ' + label + '\n'
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

  /**
   * Mark pipeline wall start.
   * @param timingCtx Caller-supplied input.
   */
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

  /**
   * Pipeline ms to sec.
   * @param ms Caller-supplied input.
   * @return double result.
   */
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
  /**
   * Shrinks {@code data:image} URLs for {@code studioAiInlineImageUrls} SSE metadata (28k cap). Huge payloads are
   * imported under {@code /static-assets/ai-assistant/chat-generated/…} so the chat strip can load a repo path.
   */
  private static Map<String, String> compactGenerateImageUrlsForAuthoringChat(
    Map<String, String> urlByToolCallId,
    StudioToolOperations ops
  ) {
    if (urlByToolCallId == null || urlByToolCallId.isEmpty()) {
      return [:]
    }
    Map<String, String> out = new LinkedHashMap<>()
    String siteId = ''
    try {
      siteId = ops != null ? (ops.resolveEffectiveSiteId(null) ?: '').toString().trim() : ''
    } catch (Throwable ignoredSite) {
    }
    for (Map.Entry<String, String> e : urlByToolCallId.entrySet()) {
      String id = e.getKey() != null ? e.getKey().toString().trim() : ''
      String url = e.getValue() != null ? e.getValue().toString().trim() : ''
      if (!id || !url) {
        continue
      }
      if (url.length() <= GENERATE_IMAGE_TOOL_PROGRESS_METADATA_MAX_URL_CHARS) {
        out.put(id, url)
        continue
      }
      if (url.startsWith('data:image') && ops != null && siteId) {
        try {
          Map imp = ops.importImageFromRemoteUrl(
            siteId,
            url,
            '/static-assets/ai-assistant/chat-generated/{yyyy}/{mm}/{dd}/'
          )
          String rel = (imp?.relativeUrl ?: '').toString().trim()
          if (rel) {
            out.put(id, rel.startsWith('/') ? rel : ('/' + rel))
          }
        } catch (Throwable impEx) {
          log.warn(
            'GenerateImage: could not import oversized data URL to static-assets for chat preview (toolCallId={}): {}',
            id,
            impEx.message ?: impEx.toString()
          )
        }
      } else if (url.startsWith('http://') || url.startsWith('https://')) {
        out.put(id, url)
      }
    }
    return out
  }

  /**
   * Write sse studio ai inline image urls metadata.
   * @param o Caller-supplied input.
   */
  private static void writeSseStudioAiInlineImageUrlsMetadata(OutputStream o, Map<String, String> urlByToolCallId) {
    if (o == null || urlByToolCallId == null || urlByToolCallId.isEmpty()) {
      return
    }
    try {
      def ev = [text: '', metadata: [studioAiInlineImageUrls: new LinkedHashMap<>(urlByToolCallId)]]
      synchronized (o) {
        o.write(("data: ${JsonOutput.toJson(ev)}\n\n").getBytes(StandardCharsets.UTF_8))
        o.flush()
      }
    } catch (Throwable ignored) {
    }
  }

  /**
   * Merges tool pipeline wall ms into metadata without dropping prior conversation context.
   * @param metadata Caller-supplied input.
   * @param timingCtx Caller-supplied input.
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
   * SSE row for server-side prompt assembly (metadata-only preview context, URLs, clock, char deltas).
   */
  private void emitPromptAssemblyTelemetrySse(OutputStream o, Map telemetry) {
    if (o == null || telemetry == null || telemetry.isEmpty()) {
      return
    }
    try {
      synchronized (o) {
        def ev = [
          text    : '',
          metadata: [
            status         : 'prompt-assembly',
            promptAssembly: telemetry
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
      String intentCard = telemetry.intentCardMarkdown?.toString()?.trim() ?: ''
      if (intentCard) {
        chatLine = intentCard
      } else if ('matched'.equals(outcome) && rid) {
        chatLine = telemetry.recipeChatLine?.toString()?.trim() ?: ''
        if (!chatLine) {
          String title = telemetry.recipeTitle?.toString()?.trim() ?: rid
          String catalogSiteId = telemetry.siteId?.toString()?.trim() ?: ''
          chatLine = AuthoringIntentRecipeCatalog.formatIntentRecipeChatLine([id: rid, title: title], catalogSiteId)
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
   * Author-visible card: one salient fact line when a tool step adds concrete context for the next step.
   */
  private static void emitStepBridgeCardSse(OutputStream o, String kind, String markdown) {
    if (o == null || !markdown?.trim()) {
      return
    }
    try {
      String card = markdown.trim()
      synchronized (o) {
        def ev = [
          text    : card,
          metadata: [
            status    : 'step-bridge-card',
            stepBridge: [
              kind    : (kind ?: '').toString(),
              markdown: card
            ]
          ]
        ]
        o.write(("data: ${JsonOutput.toJson(ev)}\n\n").getBytes(StandardCharsets.UTF_8))
        o.flush()
      }
    } catch (Throwable ignored) {
      /* best-effort — never break chat stream */
    }
  }

  private static void emitStepBridgeForArtifact(OutputStream o, Map toolsLoopSessionBundle, Map artifact) {
    if (o == null || !(toolsLoopSessionBundle instanceof Map) || !(artifact instanceof Map)) {
      return
    }
    String card = AuthoringStepBridgeCard.formatSalientContextCard(artifact)
    if (!card?.trim()) {
      return
    }
    String dedupeKey = card.trim()
    Set keys = toolsLoopSessionBundle.toolsLoopStepBridgeEmittedKeys instanceof Set ?
      (Set) toolsLoopSessionBundle.toolsLoopStepBridgeEmittedKeys :
      new LinkedHashSet<>()
    if (keys.contains(dedupeKey)) {
      return
    }
    keys.add(dedupeKey)
    toolsLoopSessionBundle.toolsLoopStepBridgeEmittedKeys = keys
    emitStepBridgeCardSse(o, AuthoringStepBridgeCard.KIND_SALIENT_CONTEXT, card)
  }

  private static void emitStepBridgeForLatestArtifact(OutputStream o, Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return
    }
    List<Map> list = ToolsLoopTurnArtifacts.allArtifacts(toolsLoopSessionBundle)
    if (list.isEmpty()) {
      return
    }
    emitStepBridgeForArtifact(o, toolsLoopSessionBundle, list.get(list.size() - 1))
  }

  private static void emitPendingStepBridgeArtifacts(OutputStream o, Map toolsLoopSessionBundle) {
    if (o == null || !(toolsLoopSessionBundle instanceof Map)) {
      return
    }
    for (Map art : ToolsLoopTurnArtifacts.allArtifacts(toolsLoopSessionBundle)) {
      emitStepBridgeForArtifact(o, toolsLoopSessionBundle, art)
    }
    int n = ToolsLoopTurnArtifacts.allArtifacts(toolsLoopSessionBundle).size()
    if (n > 0) {
      toolsLoopSessionBundle.toolsLoopStepBridgeArtifactEmitted = n
    }
  }

  /**
   * Emits a chat SSE chunk so the UI shows tool progress while the LLM runs tools (Reactor thread).
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
    Long taskDurationMs = null,
    StudioToolOperations ops = null
  ) {
    if (o == null) return
    try {
      def pfx = toolProgressLinePrefix(toolName)
      def pathFull = toolProgressContextLabel(toolName, input instanceof Map ? (Map) input : [:])
      if ('SerpApiWebSearch'.equalsIgnoreCase(toolName ?: '')) {
        String serpQ = (input?.query ?: input?.q ?: '')?.toString()?.trim() ?: ''
        if (!serpQ && toolResult instanceof Map) {
          serpQ = ((Map) toolResult).query?.toString()?.trim() ?: ((Map) toolResult).querySent?.toString()?.trim() ?: ''
        }
        if (serpQ) {
          pathFull = 'search: ' + serpQ
        }
      }
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
        boolean stylesheetProtected =
          'WriteContent'.equalsIgnoreCase(toolName ?: '') &&
            plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsStylesheetWriteGuard
              .isAuthorProtectedMessage(em)
        if (stylesheetProtected) {
          em = plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsStylesheetWriteGuard
            .authorVisibleFromGuardMessage(em)
        }
        int emMax = stylesheetProtected ? 560 : 220
        if (em.length() > emMax) {
          em = em.substring(0, emMax - 3) + '…'
        }
        if (stylesheetProtected) {
          line = pfx + ' ⚠️ **' + toolName + '** blocked (LLM response too small): ' + em + '\n'
        } else {
          line = pfx + ' ❌ **' + toolName + '** failed: ' + em + '\n'
        }
      } else if ('warn'.equals(phase)) {
        def hint = ''
        int hintMax = 140
        if (toolResult instanceof Map) {
          def m = (Map) toolResult
          hint = (m.message ?: m.hint ?: m.skippedReason ?: '')?.toString()?.trim() ?: ''
          if ('SerpApiWebSearch'.equalsIgnoreCase(toolName ?: '')) {
            hintMax = 420
          }
        }
        if ('SerpApiWebSearch'.equalsIgnoreCase(toolName ?: '') && toolResult instanceof Map) {
          String sent = ((Map) toolResult).querySent?.toString()?.trim() ?: ''
          String orig = ((Map) toolResult).query?.toString()?.trim() ?: ''
          if (sent && orig && !sent.equalsIgnoreCase(orig)) {
            hint = (hint ? hint + ' ' : '') + '(sent: `' + (sent.length() > 80 ? sent.substring(0, 77) + '…' : sent) + '`)'
          }
        }
        if (hint.length() > hintMax) {
          hint = hint.substring(0, hintMax - 3) + '…'
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
      if ('GenerateImage'.equalsIgnoreCase(toolName ?: '') && ('start'.equals(phase) || 'done'.equals(phase) || 'warn'.equals(phase))) {
        String imgPrompt = (input?.prompt ?: input?.imagePrompt ?: '')?.toString()?.trim() ?: ''
        if (imgPrompt.length() > 600) {
          imgPrompt = imgPrompt.substring(0, 597) + '…'
        }
        if (imgPrompt) {
          event.metadata.generateImagePrompt = imgPrompt
        }
      }
      if (
        'GenerateImage'.equalsIgnoreCase(toolName ?: '') &&
        ('done'.equals(phase) || 'warn'.equals(phase)) &&
        toolResult instanceof Map
      ) {
        try {
          Map gm = ChatCompletionsToolWire.unwrapGenerateImageToolResultMap((Map) toolResult)
          String tid = ChatCompletionsToolWire.generateImageBacklogToolCallId(gm)?.trim()
          String url = ChatCompletionsToolWire.generateImageResultUrlString(gm)?.trim()
          if (tid && url) {
            Map<String, String> compacted =
              compactGenerateImageUrlsForAuthoringChat([(tid): url] as Map<String, String>, ops)
            if (!compacted.isEmpty()) {
              event.metadata.studioAiInlineImageUrls = compacted
            }
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
      try {
        Map maintainerObs = StudioAiToolMaintainerObservability.collect(
          toolName,
          phase,
          input instanceof Map ? (Map) input : [:],
          toolResult,
          err
        )
        if (maintainerObs && !maintainerObs.isEmpty()) {
          event.metadata.maintainerObservability = maintainerObs
        }
      } catch (Throwable ignoredMaintainerObs) {
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
   * LLM streaming often ends with an assistant delta that has {@code finishReason=stop} (or similar) but
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
  private static String formatChatCompletionsResourceAccessMessage(ResourceAccessException rae) {
    if (isChatCompletionsConnectTimeout(rae)) {
      return """Could not open a TCP connection to the chat API (connect timed out after ${resolveChatCompletionsRestConnectTimeoutMs() / 1000}s).

The tools loop failed on the LLM round after earlier tools (e.g. web search) may have succeeded. From the Studio JVM host, verify outbound HTTPS to your configured chat base URL. If logs show SocksSocketImpl, check JVM proxy settings. Optional: -Daiassistant.openai.restConnectTimeoutMs=60000"""
    }
    String detail = (rae.message ?: '').toString().trim()
    return detail ?
      "Chat API I/O error: ${detail}" :
      'Chat API I/O error (ResourceAccessException)'
  }

  /**
   * Format sse stream error message.
   * @param t Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
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
      if (cur instanceof ResourceAccessException) {
        return formatChatCompletionsResourceAccessMessage((ResourceAccessException) cur)
      }
      if (cur instanceof SocketTimeoutException) {
        String m = (cur.message ?: '').toString()
        if (m.toLowerCase(Locale.ROOT).contains('connect')) {
          return """Could not open a TCP connection to the chat API (connect timed out after ${resolveChatCompletionsRestConnectTimeoutMs() / 1000}s).

SerpApi and other tools may still work when only the chat host is blocked. From the Studio JVM host, verify outbound HTTPS to your configured chat base URL (often https://api.openai.com). If the stack trace shows SocksSocketImpl, check JVM proxy settings (-DsocksProxyHost / HTTP_PROXY). Optional: -Daiassistant.openai.restConnectTimeoutMs=60000"""
        }
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

  /**
   * Write sse error frame.
   * @param out Mutable map receiving tool diagnostics or output fields.
   * @param t Caller-supplied input.
   */
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

  /**
   * Ensure sse terminal completed if needed.
   */
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
    String imageGenerator = null,
    String llmSecretKey = null
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
      try {
        def paTel = request?.getAttribute('aiassistant.promptAssemblyTelemetry')
        if (paTel instanceof Map && !((Map) paTel).isEmpty()) {
          emitPromptAssemblyTelemetrySse(out, (Map) paTel)
        }
      } catch (Throwable ignoredPa) {
        /* best-effort */
      }
      // New prompt / stream: ensure no stale native-tools cancel binding leaked onto this servlet thread.
      aiAssistantPipelineCancelBindingClear()

      def genImgBacklogByToolCallId = new ConcurrentHashMap<String, String>()
      def toolTimingCtx = createToolTimingContext()
      StudioToolOperations chatStudioOps = null
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
        writeToolProgressSse(out, tn, ph, inp ?: [:], er, tres, taskDurMs, chatStudioOps)
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
      def springAi = buildSpringAiChatClient(agentId, chatId, llm, chatModel, llmApiKey, toolProgressListener, imageModel, fullSuppress, protNorm, enableTools, imageGenerator, llmSecretKey)
      if (springAi.studioOps instanceof StudioToolOperations) {
        chatStudioOps = (StudioToolOperations) springAi.studioOps
      }
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
          toolProgressListener
        )
        if (route?.intentRecipeRoutingTelemetry instanceof Map) {
          springAi.intentRecipeRoutingTelemetry = route.intentRecipeRoutingTelemetry
          emitIntentRecipeRoutingTelemetrySse(out, (Map) route.intentRecipeRoutingTelemetry)
        }
        if (route?.intentRecipeRoutingWireCand != null) {
          springAi.intentRecipeRoutingWireCand = route.intentRecipeRoutingWireCand.toString()
        }
        applyIntentRecipeRouteEffects(springAi, route)
        userText = route.userTextForToolsLoop?.toString() ?: (springAi.useTools ? userText : bodyPrompt)
      }
      def toolRequiredIntent = springAi.useTools && isToolRequiredIntent(bodyPrompt)
      log.debug("chatStreamWithSpringAi start: llm={} agentId={} promptLen={} toolRequiredIntent={} chatIdPresent={} useTools={} enableTools={} formEngineClientForward={} fullSuppressWrites={} protectedFormItemPath={}",
        springAi.llm, agentId, (bodyPrompt ?: '').length(), toolRequiredIntent, (chatId != null && chatId.toString().trim().length() > 0), springAi.useTools, enableTools, formEngineClientForward, fullSuppress, protNorm ?: '')

      // Tools off: RestClient + upstream SSE (stream=true), not OpenAiChatModel (merge can break stream).
      // Tools on: avoid OpenAiChatModel / OpenAiApi.chatCompletionEntity (truncated JSON on some Studio stacks);
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
        promptSpec = springAi.chatClient.prompt(authoringChatPrompt).toolCallbacks(*springAi.tools)
      } else {
        promptSpec = springAi.useTools
          ? springAi.chatClient.prompt().user(userText).toolCallbacks(*springAi.tools)
          : springAi.chatClient.prompt().user(userText)
      }
      def toolsLoopBlockingForStudioStream = (StudioAiLlmKind.useToolsLoopChatRestClient(springAi.llm, springAi) && springAi.useTools)

      // Native tools on: RestClient loop streams **## Plan** (or fallback) before repo tool rows. Sending the
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
          def toolSecurityCtx = StudioToolOperations.captureSecurityContextCopy()
          if (toolSecurityCtx != null && springAi.studioOps instanceof StudioToolOperations) {
            springAi.studioOps =
              ((StudioToolOperations) springAi.studioOps).withCapturedSecurityContext(toolSecurityCtx)
          }
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
                    'AI Assistant chat stream: CLIENT_ABORT during LLM tool workflow — {}',
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
                if (c instanceof ResourceAccessException || isChatCompletionsConnectTimeout(c)) {
                  log.error('chatStreamWithSpringAi: Tools-loop chat upstream I/O failed', c)
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
    String urlStr = (url ?: '').toString().trim()
    if (!urlStr) {
      throw new IllegalArgumentException('Missing required parameter: url')
    }
    if (!OutboundHttpPolicy.globallyEnabled()) {
      throw new IllegalStateException('Outbound HTTP is disabled (aiassistant.httpFetch.enabled=false).')
    }
    URI uri
    try {
      uri = URI.create(urlStr)
    } catch (Throwable t) {
      throw new IllegalArgumentException("Invalid url: ${t.message}")
    }
    String policyErr = OutboundHttpPolicy.ssrfErrorForUri(uri)
    if (policyErr) {
      throw new IllegalArgumentException(policyErr)
    }
    String scheme = uri.scheme?.toLowerCase(Locale.ROOT)
    if (scheme != 'https' && scheme != 'http') {
      throw new IllegalArgumentException('Only http(s) image URLs are supported')
    }
    try {
      def ex = StudioAiSandboxHttp.getBytes(uri, [
        connectTimeoutMs: 15_000,
        readTimeoutMs   : 120_000,
        maxRedirects    : 0,
        accept          : 'image/*,*/*;q=0.8',
        ssrfCheck       : true
      ])
      if (ex.errorMessage && (ex.bodyBytes == null || ex.bodyBytes.length == 0)) {
        throw new IllegalStateException("Failed to download image: ${ex.errorMessage}")
      }
      int status = ex.statusCode
      if (status >= 300 && status < 400) {
        throw new IllegalArgumentException('Redirected image URLs are not allowed')
      }
      if (status < 200 || status >= 300) {
        throw new IllegalStateException("Failed to download image: HTTP ${status}")
      }
      String contentType = (ex.contentType ?: '').split(';')[0]?.trim() ?: ''
      if (!contentType.toLowerCase(Locale.ROOT).startsWith('image/')) {
        throw new IllegalStateException("Unexpected content type: ${contentType ?: '(missing)'}")
      }
      byte[] body = ex.bodyBytes
      if (body == null || body.length == 0) {
        throw new IllegalStateException('Failed to download image: empty response body')
      }
      response.setHeader('X-Content-Type-Options', 'nosniff')
      response.contentType = contentType
      response.outputStream.write(body)
      response.flushBuffer()
      return null
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw e
    } catch (Throwable t) {
      throw new IllegalStateException("Failed to download image: ${t.message ?: t.toString()}", t)
    }
  }
}

