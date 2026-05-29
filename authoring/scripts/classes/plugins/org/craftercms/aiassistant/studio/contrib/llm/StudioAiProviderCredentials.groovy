package plugins.org.craftercms.aiassistant.studio.contrib.llm

import plugins.org.craftercms.aiassistant.studio.config.StudioAiCrafterEnv
import plugins.org.craftercms.aiassistant.studio.config.StudioAiPlatformSettings
import plugins.org.craftercms.aiassistant.studio.spi.llm.StudioAiLlmKind
import org.springframework.ai.openai.api.common.OpenAiApiConstants
import plugins.org.craftercms.aiassistant.studio.secrets.StudioAiAssistantSecretsCatalog
import plugins.org.craftercms.aiassistant.studio.secrets.StudioAiAssistantSecretsContext
import plugins.org.craftercms.aiassistant.studio.secrets.StudioAiAssistantSecretsService

/**
 * API keys, default models, and tools-loop {@link org.springframework.ai.openai.api.Api} base URLs
 * for non-OpenAI {@link StudioAiLlmKind} values. RestClient + {@code Api} append {@code /v1/chat/completions}
 * to the base URL — bases here must <strong>not</strong> include a trailing {@code /v1}.
 */
final class StudioAiProviderCredentials {

  private StudioAiProviderCredentials() {}

  /** Spring {@link Api} + native RestClient tools loop: host-only style base (no trailing {@code /v1}). */
  static String wireLlmRestBaseUrl(String llmNormalized) {
    String n = (llmNormalized ?: '').toString()
    if (StudioAiLlmKind.OPENAI_NATIVE == n) {
      return (OpenAiApiConstants.DEFAULT_BASE_URL ?: 'https://api.openai.com').toString().replaceAll(/\/+$/, '')
    }
    if (StudioAiLlmKind.XAI_NATIVE == n) {
      return firstNonBlank(
        StudioAiCrafterEnv.get('crafter_xai_base_url'),
        StudioAiPlatformSettings.property('crafter.xai.llmBaseUrl', ''),
        'https://api.x.ai'
      )
    }
    if (StudioAiLlmKind.DEEPSEEK_NATIVE == n) {
      return firstNonBlank(
        StudioAiCrafterEnv.get('crafter_deepseek_base_url'),
        StudioAiPlatformSettings.property('crafter.deepseek.llmBaseUrl', ''),
        'https://api.deepseek.com'
      )
    }
    if (StudioAiLlmKind.LLAMA_NATIVE == n) {
      return firstNonBlank(
        StudioAiCrafterEnv.get('crafter_llama_base_url'),
        StudioAiPlatformSettings.property('crafter.llama.llmBaseUrl', ''),
        'http://127.0.0.1:11434'
      )
    }
    if (StudioAiLlmKind.GEMINI_NATIVE == n) {
      return firstNonBlank(
        StudioAiCrafterEnv.get('crafter_gemini_base_url'),
        StudioAiPlatformSettings.property('crafter.gemini.llmBaseUrl', ''),
        'https://generativelanguage.googleapis.com/v1beta/openai'
      )
    }
    return (OpenAiApiConstants.DEFAULT_BASE_URL ?: 'https://api.openai.com').toString().replaceAll(/\/+$/, '')
  }

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

  static String httpLlmImagesGenerationsUrl() {
    String b = firstNonBlank(
      StudioAiCrafterEnv.get('crafter_openai_images_base_url'),
      StudioAiPlatformSettings.property('crafter.openai.imagesBaseUrl', ''),
      wireLlmRestBaseUrl(StudioAiLlmKind.OPENAI_NATIVE)
    )
    b = b.replaceAll(/\/+$/, '')
    if (b.endsWith('/v1')) {
      return b + '/images/generations'
    }
    return b + '/v1/images/generations'
  }

  static String resolveApiKey(String llmNormalized, String fromWidgetOrRequest = null, String preferredSecretKey = null) {
    String n = (llmNormalized ?: '').toString()
    String w = (fromWidgetOrRequest ?: '').toString().trim()
    String secretKey = (preferredSecretKey ?: '').toString().trim() ?: StudioAiAssistantSecretsCatalog.secretKeyForLlmKind(n)
    if (StudioAiLlmKind.OPENAI_NATIVE == n) {
      return resolveLlmProviderApiKey(secretKey ?: 'openai_api_key', 'crafter.openai.apiKey', w)
    }
    if (StudioAiLlmKind.XAI_NATIVE == n) {
      return resolveLlmProviderApiKey(secretKey ?: 'xai_api_key', 'crafter.xai.apiKey', w)
    }
    if (StudioAiLlmKind.DEEPSEEK_NATIVE == n) {
      return resolveLlmProviderApiKey(secretKey ?: 'deepseek_api_key', 'crafter.deepseek.apiKey', w)
    }
    if (StudioAiLlmKind.LLAMA_NATIVE == n) {
      String k = resolveLlmProviderApiKey(secretKey ?: 'llama_api_key', 'crafter.llama.apiKey', w)
      return k ?: 'ollama'
    }
    if (StudioAiLlmKind.GEMINI_NATIVE == n) {
      return resolveLlmProviderApiKey(secretKey ?: 'gemini_api_key', 'crafter.gemini.apiKey', w) ?:
        resolveLlmProviderApiKey('google_api_key', 'crafter.google.apiKey', '')
    }
    return ''
  }

  static String apiKeyResolutionSourceForLog(String llmNormalized) {
    String n = (llmNormalized ?: '').toString()
    String secretKey = StudioAiAssistantSecretsCatalog.secretKeyForLlmKind(n)
    if (secretKey && resolveFromSiteSecrets(secretKey)) {
      return "secrets.json(${secretKey})"
    }
    if (StudioAiLlmKind.OPENAI_NATIVE == n) {
      return llmStyleSource('openai_api_key', 'crafter.openai.apiKey')
    }
    if (StudioAiLlmKind.XAI_NATIVE == n) {
      return llmStyleSource('xai_api_key', 'crafter.xai.apiKey')
    }
    if (StudioAiLlmKind.DEEPSEEK_NATIVE == n) {
      return llmStyleSource('deepseek_api_key', 'crafter.deepseek.apiKey')
    }
    if (StudioAiLlmKind.LLAMA_NATIVE == n) {
      return llmStyleSource('llama_api_key', 'crafter.llama.apiKey')
    }
    if (StudioAiLlmKind.GEMINI_NATIVE == n) {
      String s = llmStyleSource('gemini_api_key', 'crafter.gemini.apiKey')
      if (s != 'widget-or-request') {
        return s
      }
      return llmStyleSource('google_api_key', 'crafter.google.apiKey')
    }
    return 'unknown'
  }

  static boolean isLikelyWidgetOnlyServerKeyMissing(String llmNormalized, String resolvedApiKey, Object widgetRaw) {
    String apiKey = (resolvedApiKey ?: '').toString().trim()
    String w = (widgetRaw ?: '').toString().trim()
    if (!apiKey || apiKey != w) {
      return false
    }
    String n = (llmNormalized ?: '').toString()
    String secretKey = StudioAiAssistantSecretsCatalog.secretKeyForLlmKind(n)
    if (!secretKey) {
      return true
    }
    return !resolveFromSiteSecrets(secretKey)?.trim() &&
      !StudioAiCrafterEnv.get(StudioAiCrafterEnv.envNameForSecretKey(secretKey))?.trim() &&
      !StudioAiPlatformSettings.property(platformPropertyForSecretKey(secretKey), '')?.trim()
  }

  static String missingApiKeyMessage(String llmNormalized) {
    String n = (llmNormalized ?: '').toString()
    String envHint = StudioAiCrafterEnv.envNameForSecretKey(StudioAiAssistantSecretsCatalog.secretKeyForLlmKind(n) ?: '')
    if (StudioAiLlmKind.XAI_NATIVE == n) {
      return "LLM is xAI but no API key was found. Set host env ${envHint} or crafter.xai.apiKey in platform-settings.json. For local testing only, optional agent <llmApiKey> in ui.xml."
    }
    if (StudioAiLlmKind.DEEPSEEK_NATIVE == n) {
      return "LLM is DeepSeek but no API key was found. Set host env ${envHint} or crafter.deepseek.apiKey in platform-settings.json."
    }
    if (StudioAiLlmKind.LLAMA_NATIVE == n) {
      return "LLM is llama (tools-loop host) but no key was resolved. Set host env ${envHint} or crafter.llama.apiKey for hosted endpoints, or rely on the Ollama default placeholder when the server does not require a secret."
    }
    if (StudioAiLlmKind.GEMINI_NATIVE == n) {
      return 'LLM is gemini (Google tools-loop endpoint) but no API key was found. Set host env crafter_gemini_api_key / crafter_google_api_key or crafter.gemini.apiKey / crafter.google.apiKey in platform-settings.json.'
    }
    return "No API key was found for this LLM provider. Set host env ${envHint} or configure secrets.json."
  }

  static String resolveChatModelId(String llmNormalized, String fromRequestOrAgent) {
    String n = (llmNormalized ?: '').toString()
    String raw = (fromRequestOrAgent ?: '').toString().trim()
    if (StudioAiLlmKind.OPENAI_NATIVE == n) {
      if (!raw) {
        raw = StudioAiPlatformSettings.property('crafter.openai.model', '').trim()
      }
      if (!raw) {
        throw new IllegalStateException(
          'The chat model is not configured properly. Set the agent LLM / llmModel in Studio, pass llmModel on the chat request, or set crafter.openai.model in platform-settings.json.'
        )
      }
      return plugins.org.craftercms.aiassistant.studio.engine.turn.AiOrchestration.llmCanonicalizeApiModelToken(raw)
    }
    if (!raw) {
      if (StudioAiLlmKind.XAI_NATIVE == n) {
        raw = StudioAiPlatformSettings.property('crafter.xai.model', 'grok-2-latest').trim()
      } else if (StudioAiLlmKind.DEEPSEEK_NATIVE == n) {
        raw = StudioAiPlatformSettings.property('crafter.deepseek.model', 'deepseek-chat').trim()
      } else if (StudioAiLlmKind.LLAMA_NATIVE == n) {
        raw = StudioAiPlatformSettings.property('crafter.llama.model', 'llama3.2').trim()
      } else if (StudioAiLlmKind.GEMINI_NATIVE == n) {
        raw = StudioAiPlatformSettings.property('crafter.gemini.model', 'gemini-2.0-flash').trim()
      }
    }
    if (!raw) {
      throw new IllegalStateException(
        "The chat model is not configured for llm='${n}'. Set <llmModel> on the agent or pass llmModel on the request (or crafter.*.model in platform-settings.json)."
      )
    }
    return plugins.org.craftercms.aiassistant.studio.engine.turn.AiOrchestration.llmCanonicalizeApiModelToken(raw)
  }

  static String resolveAnthropicApiKey(String fromWidgetOrRequest = null, String preferredSecretKey = null) {
    String secretKey = (preferredSecretKey ?: '').toString().trim() ?: 'anthropic_api_key'
    resolveLlmProviderApiKey(secretKey, 'crafter.anthropic.apiKey', (fromWidgetOrRequest ?: '').toString().trim())
  }

  static String anthropicApiKeySourceForLog() {
    if (resolveFromSiteSecrets('anthropic_api_key')) {
      return 'secrets.json(anthropic_api_key)'
    }
    return llmStyleSource('anthropic_api_key', 'crafter.anthropic.apiKey')
  }

  static String resolveAnthropicChatModel(String fromRequestOrAgent) {
    String raw = (fromRequestOrAgent ?: '').toString().trim()
    if (!raw) {
      raw = StudioAiPlatformSettings.property('crafter.anthropic.model', 'claude-3-5-sonnet-20241022').trim()
    }
    if (!raw) {
      throw new IllegalStateException('The Claude model is not configured. Set agent llmModel or crafter.anthropic.model in platform-settings.json.')
    }
    return plugins.org.craftercms.aiassistant.studio.engine.turn.AiOrchestration.llmCanonicalizeApiModelToken(raw)
  }

  private static String firstNonBlank(String... vals) {
    for (String v : vals) {
      if (v != null && v.toString().trim()) {
        return v.toString().trim().replaceAll(/\/+$/, '')
      }
    }
    return ''
  }

  /**
   * Resolution order: site {@code secrets.json}, host {@code crafter_*} env, {@code platform-settings.json}, widget.
   */
  private static String resolveLlmProviderApiKey(String secretKey, String platformPropertyKey, String widget) {
    String fromSecrets = resolveFromSiteSecrets(secretKey)
    if (fromSecrets?.trim()) {
      return fromSecrets.trim()
    }
    String crafterEnv = StudioAiCrafterEnv.envNameForSecretKey(secretKey)
    String fromEnv = StudioAiCrafterEnv.get(crafterEnv)
    if (fromEnv?.trim()) {
      return fromEnv.trim()
    }
    String pk = (platformPropertyKey ?: '').toString().trim()
    if (pk) {
      String fromPlatform = StudioAiPlatformSettings.property(pk, '')?.trim()
      if (fromPlatform) {
        return fromPlatform
      }
    }
    return (widget ?: '').toString().trim()
  }

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

  private static String llmStyleSource(String secretKey, String platformPropertyKey) {
    String crafterEnv = StudioAiCrafterEnv.envNameForSecretKey(secretKey)
    if (StudioAiCrafterEnv.get(crafterEnv)?.trim()) {
      return "${crafterEnv}(env)"
    }
    if (StudioAiPlatformSettings.property(platformPropertyKey, '')?.trim()) {
      return "${platformPropertyKey}(platform-settings)"
    }
    return 'widget-or-request'
  }

  private static String platformPropertyForSecretKey(String secretKey) {
    String k = (secretKey ?: '').toString().trim()
    switch (k) {
      case 'openai_api_key': return 'crafter.openai.apiKey'
      case 'anthropic_api_key': return 'crafter.anthropic.apiKey'
      case 'xai_api_key': return 'crafter.xai.apiKey'
      case 'deepseek_api_key': return 'crafter.deepseek.apiKey'
      case 'llama_api_key': return 'crafter.llama.apiKey'
      case 'gemini_api_key': return 'crafter.gemini.apiKey'
      case 'google_api_key': return 'crafter.google.apiKey'
      default: return ''
    }
  }
}
