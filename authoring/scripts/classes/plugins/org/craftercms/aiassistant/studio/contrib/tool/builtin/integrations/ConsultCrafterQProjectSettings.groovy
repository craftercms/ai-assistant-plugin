package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.integrations

import plugins.org.craftercms.aiassistant.studio.config.StudioAiAssistantProjectConfig

/**
 * Site {@code tools.json} block for {@link ConsultCrafterQTool} ({@code builtInToolSettings.ConsultCrafterQ}).
 */
final class ConsultCrafterQProjectSettings {

  static final String WIRE = 'ConsultCrafterQ'
  static final String DEFAULT_API_BASE = 'https://api.crafterq.ai'

  /**
   * Private constructor; not for direct use.
   */
private ConsultCrafterQProjectSettings() {}

  /**
   * Api base url.
   * @param cfg Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String apiBaseUrl(Map cfg) {
    Object raw = StudioAiAssistantProjectConfig.builtInToolSettingsForWire(cfg, WIRE).get('apiBaseUrl')
    String base = raw?.toString()?.trim()
    base ?: DEFAULT_API_BASE
  }

  /** Optional override for {@code X-CrafterQ-Chat-User}; normally minted via {@code GET …/chat_config}. */
  static String chatUser(Map cfg) {
    Map block = StudioAiAssistantProjectConfig.builtInToolSettingsForWire(cfg, WIRE)
    String s = (block.get('chatUser') ?: block.get('chatUserJwt') ?: '').toString().trim()
    s
  }
}
