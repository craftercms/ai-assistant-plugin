package plugins.org.craftercms.aiassistant.studio.contrib.llm

import plugins.org.craftercms.aiassistant.studio.spi.llm.StudioAiLlmKind
import org.springframework.ai.openai.api.common.OpenAiApiConstants
import plugins.org.craftercms.aiassistant.studio.secrets.StudioAiAssistantSecretsCatalog
import plugins.org.craftercms.aiassistant.studio.secrets.StudioAiAssistantSecretsContext
import plugins.org.craftercms.aiassistant.studio.secrets.StudioAiAssistantSecretsService

import java.util.Locale

/**
 * API keys, default models, and tools-loop {@link org.springframework.ai.openai.api.Api} base URLs
 * for non-OpenAI {@link StudioAiLlmKind} values. RestClient + {@code Api} append {@code /v1/chat/completions}
 * to the base URL — bases here must <strong>not</strong> include a trailing {@code /v1}.
 */
final class StudioAiProviderCredentials {

  /**
   * Private constructor; not for direct use.
   */
private StudioAiProviderCredentials() {}

  /** Spring {@link Api} + native RestClient tools loop: host-only style base (no trailing {@code /v1}). */
  static String wireLlmRestBaseUrl(String llmNormalized) {
    String n = (llmNormalized ?: '').toString()
    if (StudioAiLlmKind.OPENAI_NATIVE == n) {
      return (OpenAiApiConstants.DEFAULT_BASE_URL ?: 'https://api.openai.com').toString().replaceAll(/\/+$/, '')
    }
    if (StudioAiLlmKind.XAI_NATIVE == n) {
      return firstNonBlank(
        System.getenv('XAI_BASE_URL'),
        System.getProperty('crafter.xai.llmBaseUrl'),
        'https://api.x.ai'
      )
    }
    if (StudioAiLlmKind.DEEPSEEK_NATIVE == n) {
      return firstNonBlank(
        System.getenv('DEEPSEEK_BASE_URL'),
        System.getProperty('crafter.deepseek.llmBaseUrl'),
        'https://api.deepseek.com'
      )
    }
    if (StudioAiLlmKind.LLAMA_NATIVE == n) {
      return firstNonBlank(
        System.getenv('LLAMA_BASE_URL'),
        System.getProperty('crafter.llama.llmBaseUrl'),
        'http://127.0.0.1:11434'
      )
    }
    if (StudioAiLlmKind.GEMINI_NATIVE == n) {
      return firstNonBlank(
        System.getenv('GEMINI_BASE_URL'),
        System.getProperty('crafter.gemini.llmBaseUrl'),
        'https://generativelanguage.googleapis.com/v1beta/openai'
      )
    }
    return (OpenAiApiConstants.DEFAULT_BASE_URL ?: 'https://api.openai.com').toString().replaceAll(/\/+$/, '')
  }

  /**
   * Absolute URL for {@link java.net.HttpURLConnection} simple completions (must match
   * {@link org.springframework.ai.openai.api.Api} path rules for the same provider).
   */
  static String httpChatCompletionsUrl(String llmNormalized) {
    String n = (llmNormalized ?: '').toString()
    String b = wireLlmRestBaseUrl(n).replaceAll(/\/+$/, '')
    if (StudioAiLlmKind.GEMINI_NATIVE == n) {
      return b + '/chat/completions'
    }
    if (b.endsWith('/v1')) {
      return b + '/chat/completions'
    }
    return b + '/v1/chat/completions'
  }

  /**
   * Absolute POST URL for OpenAI Images-compatible {@code /v1/images/generations}.
   * Defaults to the same host family as {@link #wireLlmRestBaseUrl}{@code (OPENAI_NATIVE)}; override with
   * {@code OPENAI_IMAGES_BASE_URL} or JVM {@code crafter.openai.imagesBaseUrl} when using a proxy for the Images API.
   */
  static String httpLlmImagesGenerationsUrl() {
    String b = firstNonBlank(
      System.getenv('OPENAI_IMAGES_BASE_URL'),
      System.getProperty('crafter.openai.imagesBaseUrl'),
      wireLlmRestBaseUrl(StudioAiLlmKind.OPENAI_NATIVE)
    )
    b = b.replaceAll(/\/+$/, '')
    if (b.endsWith('/v1')) {
      return b + '/images/generations'
    }
    return b + '/v1/images/generations'
  }

  /**
   * Resolves api key from request and plugin context.
   * @param llmNormalized Caller-supplied input.
   * @param fromWidgetOrRequest Caller-supplied input.
   * @param preferredSecretKey Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String resolveApiKey(String llmNormalized, String fromWidgetOrRequest = null, String preferredSecretKey = null) {
    String n = (llmNormalized ?: '').toString()
    String w = (fromWidgetOrRequest ?: '').toString().trim()
    String secretKey = (preferredSecretKey ?: '').toString().trim() ?: StudioAiAssistantSecretsCatalog.secretKeyForLlmKind(n)
    if (StudioAiLlmKind.OPENAI_NATIVE == n) {
      return resolveLlmProviderApiKey(
        'OPENAI_API_KEY',
        'crafter.openai.apiKey',
        'OPENAI_API_KEY',
        secretKey ?: 'openai_api_key',
        w
      )
    }
    if (StudioAiLlmKind.XAI_NATIVE == n) {
      return resolveLlmProviderApiKey(
        'XAI_API_KEY',
        'crafter.xai.apiKey',
        'XAI_API_KEY',
        secretKey ?: 'xai_api_key',
        w
      )
    }
    if (StudioAiLlmKind.DEEPSEEK_NATIVE == n) {
      return resolveLlmProviderApiKey(
        'DEEPSEEK_API_KEY',
        'crafter.deepseek.apiKey',
        'DEEPSEEK_API_KEY',
        secretKey ?: 'deepseek_api_key',
        w
      )
    }
    if (StudioAiLlmKind.LLAMA_NATIVE == n) {
      // Ollama often accepts any non-empty placeholder; still allow env for hosted tools-loop Llama endpoints.
      String k = resolveLlmProviderApiKey(
        'LLAMA_API_KEY',
        'crafter.llama.apiKey',
        'LLAMA_API_KEY',
        secretKey ?: 'llama_api_key',
        w
      )
      return k ?: 'ollama'
    }
    if (StudioAiLlmKind.GEMINI_NATIVE == n) {
      return resolveLlmProviderApiKey(
        'GEMINI_API_KEY',
        'crafter.gemini.apiKey',
        'GOOGLE_API_KEY',
        secretKey ?: 'gemini_api_key',
        w
      ) ?: resolveLlmProviderApiKey(
        'GOOGLE_API_KEY',
        'crafter.google.apiKey',
        'GOOGLE_API_KEY',
        'google_api_key',
        ''
      )
    }
    return ''
  }

  /**
   * Api key resolution source for log.
   * @param llmNormalized Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String apiKeyResolutionSourceForLog(String llmNormalized) {
    String n = (llmNormalized ?: '').toString()
    String secretKey = StudioAiAssistantSecretsCatalog.secretKeyForLlmKind(n)
    if (secretKey && resolveFromSiteSecrets(secretKey)) {
      return "secrets.json(${secretKey})"
    }
    if (StudioAiLlmKind.OPENAI_NATIVE == n) {
      return llmStyleSource('OPENAI_API_KEY', 'crafter.openai.apiKey', 'OPENAI_API_KEY')
    }
    if (StudioAiLlmKind.XAI_NATIVE == n) {
      return llmStyleSource('XAI_API_KEY', 'crafter.xai.apiKey', 'XAI_API_KEY')
    }
    if (StudioAiLlmKind.DEEPSEEK_NATIVE == n) {
      return llmStyleSource('DEEPSEEK_API_KEY', 'crafter.deepseek.apiKey', 'DEEPSEEK_API_KEY')
    }
    if (StudioAiLlmKind.LLAMA_NATIVE == n) {
      return llmStyleSource('LLAMA_API_KEY', 'crafter.llama.apiKey', 'LLAMA_API_KEY')
    }
    if (StudioAiLlmKind.GEMINI_NATIVE == n) {
      if (System.getenv('GEMINI_API_KEY')?.toString()?.trim()) return 'GEMINI_API_KEY(env)'
      if (System.getProperty('crafter.gemini.apiKey')?.trim()) return 'crafter.gemini.apiKey(jvm)'
      if (System.getenv('GOOGLE_API_KEY')?.toString()?.trim()) return 'GOOGLE_API_KEY(env)'
      if (System.getProperty('crafter.google.apiKey')?.trim()) return 'crafter.google.apiKey(jvm)'
      return 'widget-or-request'
    }
    return 'unknown'
  }

  /**
   * True when the resolved key equals the widget value and no server-side env/JVM key was set for that provider
   * (mirrors the OpenAI-only warning logic, extended per provider).
   */
  static boolean isLikelyWidgetOnlyServerKeyMissing(String llmNormalized, String resolvedApiKey, Object widgetRaw) {
    String apiKey = (resolvedApiKey ?: '').toString().trim()
    String w = (widgetRaw ?: '').toString().trim()
    if (!apiKey || apiKey != w) {
      return false
    }
    String n = (llmNormalized ?: '').toString()
    if (StudioAiLlmKind.OPENAI_NATIVE == n) {
      return !resolveFromSiteSecrets('openai_api_key')?.trim() &&
        !System.getenv('OPENAI_API_KEY')?.toString()?.trim() &&
        !System.getProperty('crafter.openai.apiKey')?.trim() &&
        !System.getProperty('OPENAI_API_KEY')?.trim()
    }
    if (StudioAiLlmKind.XAI_NATIVE == n) {
      return !resolveFromSiteSecrets('xai_api_key')?.trim() &&
        !System.getenv('XAI_API_KEY')?.toString()?.trim() &&
        !System.getProperty('crafter.xai.apiKey')?.trim()
    }
    if (StudioAiLlmKind.DEEPSEEK_NATIVE == n) {
      return !resolveFromSiteSecrets('deepseek_api_key')?.trim() &&
        !System.getenv('DEEPSEEK_API_KEY')?.toString()?.trim() &&
        !System.getProperty('crafter.deepseek.apiKey')?.trim()
    }
    if (StudioAiLlmKind.LLAMA_NATIVE == n) {
      return !resolveFromSiteSecrets('llama_api_key')?.trim() &&
        !System.getenv('LLAMA_API_KEY')?.toString()?.trim() &&
        !System.getProperty('crafter.llama.apiKey')?.trim()
    }
    if (StudioAiLlmKind.GEMINI_NATIVE == n) {
      return !resolveFromSiteSecrets('gemini_api_key')?.trim() &&
        !resolveFromSiteSecrets('google_api_key')?.trim() &&
        !System.getenv('GEMINI_API_KEY')?.toString()?.trim() &&
        !System.getenv('GOOGLE_API_KEY')?.toString()?.trim() &&
        !System.getProperty('crafter.gemini.apiKey')?.trim() &&
        !System.getProperty('crafter.google.apiKey')?.trim()
    }
    return false
  }

  /**
   * Missing api key message.
   * @param llmNormalized Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String missingApiKeyMessage(String llmNormalized) {
    String n = (llmNormalized ?: '').toString()
    if (StudioAiLlmKind.XAI_NATIVE == n) {
      return 'LLM is xAI but no API key was found. Set XAI_API_KEY or JVM crafter.xai.apiKey on Studio. For local testing only, optional agent <llmApiKey> in ui.xml (see docs/using-and-extending/llm-configuration.md).'
    }
    if (StudioAiLlmKind.DEEPSEEK_NATIVE == n) {
      return 'LLM is DeepSeek but no API key was found. Set DEEPSEEK_API_KEY or JVM crafter.deepseek.apiKey on Studio. For local testing only, optional agent <llmApiKey> in ui.xml.'
    }
    if (StudioAiLlmKind.LLAMA_NATIVE == n) {
      return 'LLM is llama (tools-loop host) but no key was resolved. Set LLAMA_API_KEY / crafter.llama.apiKey for hosted endpoints, or rely on the Ollama default placeholder when the server does not require a secret.'
    }
    if (StudioAiLlmKind.GEMINI_NATIVE == n) {
      return 'LLM is gemini (Google tools-loop endpoint) but no API key was found. Set GEMINI_API_KEY or GOOGLE_API_KEY (or JVM crafter.gemini.apiKey / crafter.google.apiKey).'
    }
    return 'No API key was found for this LLM provider.'
  }

  /**
   * Chat model id for {@code /v1/chat/completions}. When {@code fromRequestOrAgent} is blank, uses JVM defaults per provider.
   */
  static String resolveChatModelId(String llmNormalized, String fromRequestOrAgent) {
    String n = (llmNormalized ?: '').toString()
    String raw = (fromRequestOrAgent ?: '').toString().trim()
    if (StudioAiLlmKind.OPENAI_NATIVE == n) {
      if (!raw) {
        raw = (System.getProperty('crafter.openai.model') ?: '').toString().trim()
      }
      if (!raw) {
        throw new IllegalStateException(
          'The chat model is not configured properly. Set the agent LLM / llmModel in Studio (for example ui.xml), pass llmModel on the chat request, or set JVM property crafter.openai.model to a valid model id for this agent.'
        )
      }
      return plugins.org.craftercms.aiassistant.studio.engine.turn.AiOrchestration.llmCanonicalizeApiModelToken(raw)
    }
    if (!raw) {
      if (StudioAiLlmKind.XAI_NATIVE == n) {
        raw = (System.getProperty('crafter.xai.model') ?: 'grok-2-latest').toString().trim()
      } else if (StudioAiLlmKind.DEEPSEEK_NATIVE == n) {
        raw = (System.getProperty('crafter.deepseek.model') ?: 'deepseek-chat').toString().trim()
      } else if (StudioAiLlmKind.LLAMA_NATIVE == n) {
        raw = (System.getProperty('crafter.llama.model') ?: 'llama3.2').toString().trim()
      } else if (StudioAiLlmKind.GEMINI_NATIVE == n) {
        raw = (System.getProperty('crafter.gemini.model') ?: 'gemini-2.0-flash').toString().trim()
      }
    }
    if (!raw) {
      throw new IllegalStateException(
        "The chat model is not configured for llm='${n}'. Set <llmModel> on the agent or pass llmModel on the request (or JVM crafter.*.model for this provider)."
      )
    }
    return plugins.org.craftercms.aiassistant.studio.engine.turn.AiOrchestration.llmCanonicalizeApiModelToken(raw)
  }

  /**
   * Resolves anthropic api key from request and plugin context.
   * @param fromWidgetOrRequest Caller-supplied input.
   * @param preferredSecretKey Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String resolveAnthropicApiKey(String fromWidgetOrRequest = null, String preferredSecretKey = null) {
    String secretKey = (preferredSecretKey ?: '').toString().trim() ?: 'anthropic_api_key'
    resolveLlmProviderApiKey(
      'ANTHROPIC_API_KEY',
      'crafter.anthropic.apiKey',
      'ANTHROPIC_API_KEY',
      secretKey,
      (fromWidgetOrRequest ?: '').toString().trim()
    )
  }

  /**
   * Anthropic api key source for log.
   * @return Text result, or empty or null when unavailable.
   */
  static String anthropicApiKeySourceForLog() {
    if (resolveFromSiteSecrets('anthropic_api_key')) {
      return 'secrets.json(anthropic_api_key)'
    }
    return llmStyleSource('ANTHROPIC_API_KEY', 'crafter.anthropic.apiKey', 'ANTHROPIC_API_KEY')
  }

  /** Resolves Anthropic chat model id from agent config or JVM default. */
  static String resolveAnthropicChatModel(String fromRequestOrAgent) {
    String raw = (fromRequestOrAgent ?: '').toString().trim()
    if (!raw) {
      raw = (System.getProperty('crafter.anthropic.model') ?: 'claude-3-5-sonnet-20241022').toString().trim()
    }
    if (!raw) {
      throw new IllegalStateException('The Claude model is not configured. Set agent llmModel or JVM crafter.anthropic.model.')
    }
    return plugins.org.craftercms.aiassistant.studio.engine.turn.AiOrchestration.llmCanonicalizeApiModelToken(raw)
  }

  /** Returns the first non-blank string, trimming trailing slashes from URLs. */
  private static String firstNonBlank(String... vals) {
    for (String v : vals) {
      if (v != null && v.toString().trim()) {
        return v.toString().trim().replaceAll(/\/+$/, '')
      }
    }
    return ''
  }

  /**
   * Resolution order: site {@code secrets.json} entry, process env, JVM properties, then optional widget/request (testing).
   */
  private static String resolveLlmProviderApiKey(
    String envName,
    String jvmPrimary,
    String jvmAlt,
    String secretKey,
    String widget
  ) {
    String fromSecrets = resolveFromSiteSecrets(secretKey)
    if (fromSecrets?.trim()) {
      return fromSecrets.trim()
    }
    def e = System.getenv(envName)
    if (e?.toString()?.trim()) {
      return e.toString().trim()
    }
    def p = System.getProperty(jvmPrimary)
    if (p?.trim()) {
      return p.trim()
    }
    p = System.getProperty(jvmAlt)
    if (p?.trim()) {
      return p.trim()
    }
    return (widget ?: '').toString().trim()
  }

  /** Reads a secret key from site {@code secrets.json} when request context is bound. */
  private static String resolveFromSiteSecrets(String secretKey) {
    String key = (secretKey ?: '').toString().trim()
    if (!key) {
      return ''
    }
    String siteId = StudioAiAssistantSecretsContext.currentSiteId()
    Object ctx = StudioAiAssistantSecretsContext.currentApplicationContext()
    if (!siteId) {
      return ''
    }
    return StudioAiAssistantSecretsService.resolveSecretKey(siteId, ctx, key)
  }

  /** Describes where an API key was resolved from (for maintainer logs). */
  private static String llmStyleSource(String envName, String jvmPrimary, String jvmAlt) {
    if (System.getenv(envName)?.toString()?.trim()) return "${envName}(env)"
    if (System.getProperty(jvmPrimary)?.trim()) return "${jvmPrimary}(jvm)"
    if (System.getProperty(jvmAlt)?.trim()) return "${jvmAlt}(jvm)"
    return 'widget-or-request'
  }
}
