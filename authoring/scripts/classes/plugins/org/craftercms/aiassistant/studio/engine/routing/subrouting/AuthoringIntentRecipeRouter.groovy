package plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting

import groovy.json.JsonSlurper

/**
 * Parses the JSON-only reply from the authoring **intent router** completion.
 */
final class AuthoringIntentRecipeRouter {

  /**
   * Private constructor; not for direct use.
   */
  private AuthoringIntentRecipeRouter() {}

  /**
   * @return map with keys:
   *         {@code mode} ({@code chat_only} | {@code recipe} | {@code tool} | {@code plan}),
   *         {@code recipeId} (String or null), {@code toolName} (String or null),
   *         {@code confidence} (double 0..1), {@code reason} (String)
   */
  static Map parseRouterJson(String raw) {
    String t = stripFences((raw ?: '').toString().trim())
    if (!t) {
      return [mode: 'plan', recipeId: null, toolName: null, confidence: 0.0d, reason: 'empty router reply']
    }

    try {
      Object o = new JsonSlurper().parseText(t)
      if (!(o instanceof Map)) {
        return [mode: 'plan', recipeId: null, toolName: null, confidence: 0.0d, reason: 'router reply not a JSON object']
      }

      Map m = (Map) o
      String mode = normalizeMode(m.get('mode'))
      String rid = m.get('recipeId')?.toString()?.trim()
      if (!rid || 'null'.equalsIgnoreCase(rid)) {
        rid = null
      }
      String toolName = m.get('toolName')?.toString()?.trim()
      if (!toolName || 'null'.equalsIgnoreCase(toolName)) {
        toolName = null
      }

      double conf = parseConfidence(m.get('confidence'))
      String reason = m.get('reason')?.toString()?.trim() ?: ''

      if (!mode) {
        mode = 'plan'
      }
      if ('recipe'.equals(mode) && !rid) {
        mode = 'plan'
        reason = reason ?: 'mode recipe but recipeId null'
      }
      if ('tool'.equals(mode) && !toolName) {
        mode = 'plan'
        reason = reason ?: 'mode tool but toolName null'
      }

      [mode: mode, recipeId: rid, toolName: toolName, confidence: conf, reason: reason]
    } catch (Throwable t2) {
      return [
        mode      : 'plan',
        recipeId  : null,
        toolName  : null,
        confidence: 0.0d,
        reason    : 'parse error: ' + (t2.message ?: t2.toString())
      ]
    }
  }

  /** Maps legacy router mode strings to {@code chat_only} | {@code recipe} | {@code tool} | {@code plan}. */
  private static String normalizeMode(Object raw) {
    String m = (raw ?: '').toString().trim().toLowerCase()
    if (!m) {
      return ''
    }
    if (m == 'chat' || m == 'chat-only' || m == 'no_tools' || m == 'no_tools') {
      return 'chat_only'
    }
    if (m == 'recipes' || m == 'workflow') {
      return 'recipe'
    }
    if (m == 'tools' || m == 'single_tool') {
      return 'tool'
    }
    if (m == 'plan' || m == 'orchestrate' || m == 'multi_step') {
      return 'plan'
    }
    if (m == 'chat_only' || m == 'recipe' || m == 'tool' || m == 'plan') {
      return m
    }
    return ''
  }

  /** Parses router {@code confidence} from JSON (number or string); returns 0 on failure. */
  private static double parseConfidence(Object c) {
    try {
      if (c instanceof Number) {
        return clampConfidence(((Number) c).doubleValue())
      }
      if (c != null) {
        return clampConfidence(Double.parseDouble(c.toString().trim()))
      }
    } catch (Throwable ignored) {
    }
    return 0.0d
  }

  /** Clamps router confidence to [0, 1]. */
  private static double clampConfidence(double conf) {
    if (conf < 0.0d) {
      return 0.0d
    }
    if (conf > 1.0d) {
      return 1.0d
    }
    return conf
  }

  /** Removes optional markdown ``` fences from the router model JSON reply before parsing. */
  private static String stripFences(String s) {
    String t = s.trim()
    if (!t.startsWith('```')) {
      return t
    }

    int nl = t.indexOf('\n')
    if (nl > 0) {
      t = t.substring(nl + 1)
    }
    if (t.endsWith('```')) {
      t = t.substring(0, t.length() - 3).trim()
    }

    t
  }
}
