package plugins.org.craftercms.aiassistant.engine.prompt

import java.util.concurrent.ConcurrentHashMap

/**
 * Groovy literal defaults passed to {@link ToolPrompts#p(String, String)} — registered on first resolve so Studio
 * tooling can show “built‑in” text without re-parsing {@link ToolPrompts}.
 */
final class ToolPromptsBuiltinDefaults {

  private static final Map<String, String> BUILTINS = new ConcurrentHashMap<>()

  /**
   * Private constructor; not for direct use.
   */
private ToolPromptsBuiltinDefaults() {}

  /**
   * Register.
   * @param key Caller-supplied input.
   * @param defaultText Caller-supplied input.
   */
  static void register(String key, String defaultText) {
    if (key == null || defaultText == null) {
      return
    }
    BUILTINS.putIfAbsent(key, defaultText)
  }

  /**
   * Returns builtin.
   * @param key Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String getBuiltin(String key) {
    if (key == null) {
      return null
    }
    return BUILTINS.get(key)
  }
}
