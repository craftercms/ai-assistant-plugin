package plugins.org.craftercms.aiassistant.studio.contrib.agents

/** Stable agent id fields in {@code config/studio/ai-assistant/agents.json}. */
final class AgentCatalogIds {

  /**
   * Private constructor; not for direct use.
   */
private AgentCatalogIds() {}

  /**
   * Loads id from configuration or input.
   * @param entry Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String readId(Map entry) {
    if (!(entry instanceof Map)) {
      return ''
    }
    return (entry.agentId ?: '').toString().trim()
  }
}
