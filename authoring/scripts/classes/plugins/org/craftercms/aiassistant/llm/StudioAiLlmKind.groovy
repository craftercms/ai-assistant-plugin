package plugins.org.craftercms.aiassistant.llm

import java.util.Locale
import java.util.Map
import java.util.regex.Pattern

/**
 * Normalized LLM <strong>transport</strong> identifiers for the Studio AI Assistant plugin (this codebase).
 * <p>
 * Missing, blank, or unrecognized {@code llm} values are rejected by {@link #normalize(String)} with
 * {@link IllegalArgumentException} (HTTP 400 on stream/chat). Authors must configure a provider ({@link #OPENAI_NATIVE},
 * Claude, script LLM, etc.) — there is no remote hosted default.
 * The built-in <strong>{@link #OPENAI_NATIVE}</strong> row is the <strong>OpenAI vendor</strong>; <strong>xAI</strong>,
 * <strong>deepSeek</strong>, <strong>llama</strong>, and <strong>gemini</strong> are <strong>other vendors</strong> that share the same
 * <strong>tools-loop</strong> {@code /v1/chat/completions} <strong>RestClient</strong> path in {@code AiOrchestration}.
 * <strong>Claude</strong> uses Spring AI Anthropic with Spring-managed tool execution.
 * Site-authored backends use {@link #SCRIPT_LLM_PREFIX} via Groovy under {@code /scripts/aiassistant/llm/{id}/}.
 * </p>
 */
final class StudioAiLlmKind {

  private StudioAiLlmKind() {}

  /** Bundle: API key for the tools-loop chat host (any vendor). */
  static final String BUNDLE_TOOLS_LOOP_CHAT_API_KEY = 'toolsLoopChatApiKey'

  /** Bundle: when {@code true}, tools-loop / simple-completion JSON uses {@code max_completion_tokens} instead of {@code max_tokens} (script/vendor choice). */
  static final String BUNDLE_TOOLS_LOOP_CHAT_PREFER_MAX_COMPLETION_TOKENS = 'toolsLoopChatPreferMaxCompletionTokens'

  /** Bundle: optional positive int — caps completion budget for tools-loop + simple-completion toward this wire host. */
  static final String BUNDLE_TOOLS_LOOP_CHAT_MAX_COMPLETION_OUT_TOKENS = 'toolsLoopChatMaxCompletionOutTokens'

  /**
   * Bundle: optional non-negative int — when {@code > 0}, serialized tools-loop request JSON (messages + tools) is shrunk in-place
   * before each POST until under this char budget (helps TPM / payload limits on strict hosts). {@code 0} = disabled.
   */
  static final String BUNDLE_TOOLS_LOOP_CHAT_MAX_WIRE_PAYLOAD_CHARS = 'toolsLoopChatMaxWirePayloadChars'

  /** Bundle: host-only base URL for tools-loop chat (no trailing {@code /v1}). */
  static final String BUNDLE_TOOLS_LOOP_CHAT_BASE_URL = 'toolsLoopChatBaseUrl'

  /** Bundle: {@code nativeToolTransport} value selecting the tools-loop RestClient path. */
  static final String NATIVE_TRANSPORT_TOOLS_LOOP_WIRE = 'toolsLoopWire'

  /** Spring AI OpenAI ChatModel + RestClient native-tool loop (CMS tools on the wire). */
  static final String OPENAI_NATIVE = 'openAI'

  /** OpenAI vendor + other LLMs on the same tools-loop REST surface; see {@link StudioAiProviderCredentials}. */
  static final String XAI_NATIVE = 'xAI'

  static final String DEEPSEEK_NATIVE = 'deepSeek'
  static final String LLAMA_NATIVE = 'llama'
  /** Google Generative Language tools-loop endpoint; {@code genesis} is an accepted alias in {@link #normalize}. */
  static final String GEMINI_NATIVE = 'gemini'

  /** Spring AI Anthropic (Claude); tools via Spring {@code ChatClient}, not the OpenAI RestClient loop. */
  static final String CLAUDE_NATIVE = 'claude'

  /**
   * Normalized id for site Groovy LLM under {@code /scripts/aiassistant/llm/{id}/}. Agent {@code <llm>} uses
   * {@code script:yourId} → normalized {@code scriptLlm:yourId}.
   */
  static final String SCRIPT_LLM_PREFIX = 'scriptLlm:'

  private static final Pattern SAFE_SCRIPT_LLM_ID = Pattern.compile('^[a-z0-9_-]{1,64}$')

  /**
   * Detects normalized kinds produced from {@code script:…} agent configuration.
   * Treats null/blank tokens as empty strings before prefix comparison.
   * Used to branch script-hosted Groovy LLM bundles vs built-in transports.
   */
  static boolean isScriptHostedLlm(String normalizedKind) {
    return (normalizedKind ?: '').toString().startsWith(SCRIPT_LLM_PREFIX)
  }

  /**
   * Strips {@link #SCRIPT_LLM_PREFIX} when present and lowercases the remainder for filesystem lookups.
   * Returns empty string when the token is not script-hosted.
   * Keeps ids aligned with `/scripts/aiassistant/llm/{id}/` folder naming rules.
   */
  static String scriptLlmIdFromNormalized(String normalizedKind) {
    String s = (normalizedKind ?: '').toString()
    if (!s.startsWith(SCRIPT_LLM_PREFIX)) {
      return ''
    }
    return s.substring(SCRIPT_LLM_PREFIX.length()).trim().toLowerCase(Locale.US)
  }

  /**
   * Enumerates first-party vendor constants wired to the OpenAI-compatible RestClient loop.
   * Compares normalized strings exactly (already lowercase canonical ids).
   * Avoids probing script bundles—call {@link #useToolsLoopChatRestClient} for those merges.
   */
  static boolean useToolsLoopChatRestClientBuiltInKinds(String normalizedKind) {
    String n = (normalizedKind ?: '').toString()
    return OPENAI_NATIVE == n || XAI_NATIVE == n || DEEPSEEK_NATIVE == n || LLAMA_NATIVE == n || GEMINI_NATIVE == n
  }

  /**
   * Reads {@link #BUNDLE_TOOLS_LOOP_CHAT_API_KEY} from Spring AI session bundles when scripts embed secrets.
   * Returns trimmed text or empty string for absent keys.
   * Feeds credential resolution without logging the raw secret.
   */
  static String toolsLoopChatApiKeyFromBundle(Map bundle) {
    if (bundle == null) {
      return ''
    }
    return (bundle.get(BUNDLE_TOOLS_LOOP_CHAT_API_KEY) ?: '').toString().trim()
  }

  /**
   * Extracts {@link #BUNDLE_TOOLS_LOOP_CHAT_BASE_URL} for script LLM sessions that reuse the RestClient stack.
   * Trims whitespace so trailing slashes remain author-controlled downstream.
   * Returns empty string when undefined to let callers fall back to hosted defaults.
   */
  static String toolsLoopChatBaseUrlFromBundle(Map bundle) {
    if (bundle == null) {
      return ''
    }
    return (bundle.get(BUNDLE_TOOLS_LOOP_CHAT_BASE_URL) ?: '').toString().trim()
  }

  /**
   * Coerces {@link #BUNDLE_TOOLS_LOOP_CHAT_PREFER_MAX_COMPLETION_TOKENS} flags from script maps.
   * Accepts Boolean boxes or textual true/false tokens.
   * Drives serializer differences for vendors that reject legacy max_tokens fields.
   */
  static boolean toolsLoopChatPreferMaxCompletionTokensFromBundle(Map bundle) {
    if (bundle == null) {
      return false
    }
    Object v = bundle.get(BUNDLE_TOOLS_LOOP_CHAT_PREFER_MAX_COMPLETION_TOKENS)
    if (v == null) {
      return false
    }
    if (v instanceof Boolean) {
      return ((Boolean) v).booleanValue()
    }
    return Boolean.parseBoolean(v.toString().trim())
  }

  /**
   * Parses optional completion output caps for tools-loop POST bodies.
   * Accepts Numbers or numeric strings, ignoring non-positive values.
   * Swallows parse errors to keep script bundles permissive during experiments.
   */
  static Integer toolsLoopChatMaxCompletionOutTokensFromBundle(Map bundle) {
    if (bundle == null) {
      return null
    }
    Object v = bundle.get(BUNDLE_TOOLS_LOOP_CHAT_MAX_COMPLETION_OUT_TOKENS)
    if (v == null) {
      return null
    }
    try {
      if (v instanceof Number) {
        int n = ((Number) v).intValue()
        return n > 0 ? n : null
      }
      int n = Integer.parseInt(v.toString().trim())
      return n > 0 ? n : null
    } catch (Throwable ignored) {
      return null
    }
  }

  /**
   * Reads whole-request JSON shrinking budgets before each tools-loop POST.
   * Coerces negatives to zero meaning “disabled”.
   * Protects strict chat hosts from oversized message+tool schemas in one shot.
   */
  static int toolsLoopChatMaxWirePayloadCharsFromBundle(Map bundle) {
    if (bundle == null) {
      return 0
    }
    Object v = bundle.get(BUNDLE_TOOLS_LOOP_CHAT_MAX_WIRE_PAYLOAD_CHARS)
    if (v == null) {
      return 0
    }
    try {
      if (v instanceof Number) {
        return Math.max(0, ((Number) v).intValue())
      }
      return Math.max(0, Integer.parseInt(v.toString().trim()))
    } catch (Throwable ignored) {
      return 0
    }
  }

  /**
   * Compares {@code nativeToolTransport} hints case-insensitively against {@link #NATIVE_TRANSPORT_TOOLS_LOOP_WIRE}.
   * Treats blank tokens as false.
   * Lets script bundles opt into RestClient wiring without renaming normalized llm ids.
   */
  static boolean nativeToolTransportIsToolsLoopWire(String transportToken) {
    String t = (transportToken ?: '').toString().trim()
    if (!t) {
      return false
    }
    return NATIVE_TRANSPORT_TOOLS_LOOP_WIRE.equalsIgnoreCase(t)
  }

  /**
   * Tools-loop RestClient native tool loop (not Anthropic). When {@code springAiBundle} is the map from
   * {@code StudioAiLlmRuntime#buildSessionBundle}, script-hosted sessions may set {@code nativeToolTransport} to
   * {@link #NATIVE_TRANSPORT_TOOLS_LOOP_WIRE} or supply {@link #BUNDLE_TOOLS_LOOP_CHAT_BASE_URL} + {@code resolvedChatModel}
   * to opt into the same path.
   * Optional wire tuning (vendor-agnostic): {@link #BUNDLE_TOOLS_LOOP_CHAT_PREFER_MAX_COMPLETION_TOKENS},
   * {@link #BUNDLE_TOOLS_LOOP_CHAT_MAX_COMPLETION_OUT_TOKENS}, {@link #BUNDLE_TOOLS_LOOP_CHAT_MAX_WIRE_PAYLOAD_CHARS}.
   */
  static boolean useToolsLoopChatRestClient(String normalizedKind, Map springAiBundle = null) {
    if (springAiBundle != null) {
      String t = springAiBundle.get('nativeToolTransport')?.toString()?.trim()
      if (nativeToolTransportIsToolsLoopWire(t)) {
        return true
      }
      if (isScriptHostedLlm((normalizedKind ?: '').toString())) {
        String w = toolsLoopChatBaseUrlFromBundle(springAiBundle)
        String rm = springAiBundle.get('resolvedChatModel')?.toString()?.trim()
        if (w && rm) {
          return true
        }
      }
    }
    return useToolsLoopChatRestClientBuiltInKinds(normalizedKind)
  }

  /**
   * Detects Claude transports via bundle hints or canonical normalized ids.
   * Checks {@code nativeToolTransport=anthropic} before falling back to {@link #CLAUDE_NATIVE}.
   * Keeps Anthropic Spring AI adapters separate from tools-loop RestClient code paths.
   */
  static boolean isAnthropicClaude(String normalizedKind, Map springAiBundle = null) {
    if (springAiBundle != null) {
      String t = springAiBundle.get('nativeToolTransport')?.toString()?.trim()
      if (t && 'anthropic'.equalsIgnoreCase(t)) {
        return true
      }
    }
    return CLAUDE_NATIVE == (normalizedKind ?: '').toString()
  }

  /**
   * Answers whether autonomous runners may attach native Spring tool callbacks without interactive chat.
   * Returns true for built-in RestClient vendors or script-hosted Groovy llms advertising compatible bundles.
   * Keeps incompatible transports from registering tools that cannot execute headlessly.
   */
  static boolean supportsAutonomousNativeTools(String normalizedKind) {
    return useToolsLoopChatRestClientBuiltInKinds(normalizedKind) || isScriptHostedLlm(normalizedKind)
  }

  /**
   * Maps agent / POST {@code llm} strings to a normalized kind. Empty or blank throws {@link IllegalArgumentException}.
   * Unrecognized values and invalid {@code script:…} ids throw with the same unsupported-llm message.
   */
  static String normalize(String raw) {
    String trimmed = (raw ?: '').toString().trim()
    if (!trimmed) {
      throw new IllegalArgumentException(
        'Missing or blank llm: set llm on the agent in config/studio/ai-assistant/agents.json (Project Tools → Agents) or include llm on the stream/chat POST body, e.g. openAI, claude, deepSeek, gemini, xAI, llama, script:myid.'
      )
    }
    String s = trimmed.toLowerCase(Locale.US)
    if (s.startsWith('script:')) {
      String id = s.substring('script:'.length()).trim()
      if (SAFE_SCRIPT_LLM_ID.matcher(id).matches()) {
        return SCRIPT_LLM_PREFIX + id
      }
      throw new IllegalArgumentException(
        "Invalid script LLM id in llm='${trimmed}': use script:<id> with id matching [a-z0-9_-]{1,64}."
      )
    }
    if (s == 'openai' || s == 'open-ai') {
      return OPENAI_NATIVE
    }
    if (s == 'xai' || s == 'x-ai' || s == 'grok') {
      return XAI_NATIVE
    }
    if (s == 'deepseek' || s == 'deep-seek') {
      return DEEPSEEK_NATIVE
    }
    if (s == 'llama' || s == 'ollama' || s == 'meta-llama' || s == 'meta_llama') {
      return LLAMA_NATIVE
    }
    if (s == 'gemini' || s == 'genesis' || s == 'google' || s == 'google-genai' || s == 'google_genai') {
      return GEMINI_NATIVE
    }
    if (s == 'claude' || s == 'anthropic') {
      return CLAUDE_NATIVE
    }
    throw new IllegalArgumentException(
      "Unrecognized llm='${trimmed}'. Supported: openAI, xAI, deepSeek, llama, gemini, genesis, claude, script:<id>."
    )
  }
}
