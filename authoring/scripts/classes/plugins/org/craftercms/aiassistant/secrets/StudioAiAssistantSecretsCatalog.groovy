package plugins.org.craftercms.aiassistant.secrets

import plugins.org.craftercms.aiassistant.llm.StudioAiLlmKind

/**
 * Well-known secret keys for vendor LLM credentials and related defaults.
 * New sites seed {@code secrets.json} with {@code ${env:VAR}} defaults per slot; authors may change any row (e.g. {@code ${enc:…}}).
 */
final class StudioAiAssistantSecretsCatalog {

  private StudioAiAssistantSecretsCatalog() {}

  static final String SECRETS_JSON_PATH = '/scripts/aiassistant/config/secrets.json'

  /**
   * One row per built-in LLM provider ({@link StudioAiLlmKind}). {@code defaultEnvVar} becomes
   * {@code ${env:…}} in {@code secrets.json} until the site admin changes the entry.
   */
  private static final List<Map> KNOWN_SLOTS = [
    [
      key          : 'openai_api_key',
      label        : 'OpenAI',
      provider     : StudioAiLlmKind.OPENAI_NATIVE,
      defaultEnvVar: 'OPENAI_API_KEY',
      llmKinds     : [StudioAiLlmKind.OPENAI_NATIVE]
    ],
    [
      key          : 'anthropic_api_key',
      label        : 'Claude (Anthropic)',
      provider     : StudioAiLlmKind.CLAUDE_NATIVE,
      defaultEnvVar: 'ANTHROPIC_API_KEY',
      llmKinds     : [StudioAiLlmKind.CLAUDE_NATIVE]
    ],
    [
      key          : 'xai_api_key',
      label        : 'xAI',
      provider     : StudioAiLlmKind.XAI_NATIVE,
      defaultEnvVar: 'XAI_API_KEY',
      llmKinds     : [StudioAiLlmKind.XAI_NATIVE]
    ],
    [
      key          : 'deepseek_api_key',
      label        : 'DeepSeek',
      provider     : StudioAiLlmKind.DEEPSEEK_NATIVE,
      defaultEnvVar: 'DEEPSEEK_API_KEY',
      llmKinds     : [StudioAiLlmKind.DEEPSEEK_NATIVE]
    ],
    [
      key          : 'llama_api_key',
      label        : 'Llama (Ollama-compatible)',
      provider     : StudioAiLlmKind.LLAMA_NATIVE,
      defaultEnvVar: 'LLAMA_API_KEY',
      llmKinds     : [StudioAiLlmKind.LLAMA_NATIVE]
    ],
    [
      key          : 'gemini_api_key',
      label        : 'Gemini (Google)',
      provider     : StudioAiLlmKind.GEMINI_NATIVE,
      defaultEnvVar: 'GEMINI_API_KEY',
      llmKinds     : [StudioAiLlmKind.GEMINI_NATIVE]
    ],
  ]

  /** Optional integration API keys (not LLM vendors); shown in Secrets admin alongside provider rows. */
  private static final List<Map> INTEGRATION_SLOTS = [
    [
      key          : 'serpapi_api_key',
      label        : 'SerpAPI (web search)',
      defaultEnvVar: 'SERPAPI_API_KEY',
      optional     : true
    ]
  ]

  static List<Map> knownSlots() {
    List<Map> out = []
    for (Map slot : KNOWN_SLOTS) {
      out.add(new LinkedHashMap<>(slot))
    }
    for (Map slot : INTEGRATION_SLOTS) {
      out.add(new LinkedHashMap<>(slot))
    }
    return Collections.unmodifiableList(out)
  }

  static Map knownSlotByKey(String key) {
    String k = (key ?: '').toString().trim()
    if (!k) {
      return null
    }
    for (Map slot : KNOWN_SLOTS) {
      if (k == slot.key?.toString()) {
        return new LinkedHashMap<>(slot)
      }
    }
    for (Map slot : INTEGRATION_SLOTS) {
      if (k == slot.key?.toString()) {
        return new LinkedHashMap<>(slot)
      }
    }
    return null
  }

  static boolean isKnownKey(String key) {
    return knownSlotByKey(key) != null
  }

  /** Secret key for a normalized built-in LLM kind, or empty when not mapped. */
  static String secretKeyForLlmKind(String llmNormalized) {
    String n = (llmNormalized ?: '').toString()
    if (StudioAiLlmKind.isAnthropicClaude(n)) {
      return 'anthropic_api_key'
    }
    for (Map slot : KNOWN_SLOTS) {
      Object kinds = slot.llmKinds
      if (kinds instanceof List && ((List) kinds).contains(n)) {
        return slot.key?.toString() ?: ''
      }
    }
    return ''
  }

  static String defaultValueExpressionForKey(String key) {
    Map slot = knownSlotByKey(key)
    if (slot == null) {
      return ''
    }
    String env = slot.defaultEnvVar?.toString()?.trim() ?: ''
    return env ? "\${env:${env}}" : ''
  }

  /** Default {@code secrets.json} document when the file is missing. */
  static Map defaultSecretsDocument() {
    List<Map> entries = []
    for (Map slot : knownSlots()) {
      String key = slot.key?.toString()?.trim()
      if (!key) {
        continue
      }
      entries.add([
        key  : key,
        value: defaultValueExpressionForKey(key)
      ])
    }
    return [version: 1, secrets: entries]
  }
}
