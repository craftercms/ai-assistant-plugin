package plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting

import groovy.json.JsonSlurper

/**
 * Parses the JSON-only reply from the authoring **intent router** LLM completion.
 *
 * <p>The router must emit {@code mode}, {@code turnGoal}, and optional {@code successCriteria} in addition to
 * recipe/tool selection fields. {@link #extractJsonPayload} tolerates leading prose (e.g. {@code ## Plan}) so
 * polluted replies still parse when a JSON object is present. Offline parity tests:
 * {@code scripts/test/functional/router-json-offline.mjs}.</p>
 */
final class AuthoringIntentRecipeRouter {

  /** Utility class; not for instantiation. */
  private AuthoringIntentRecipeRouter() {}

  /**
   * Parses raw router LLM text into a normalized decision map.
   *
   * @param raw model reply (may include markdown fences or prose before the JSON object)
   * @return map with keys:
   *         {@code mode} ({@code chat_only} | {@code recipe} | {@code tool} | {@code plan}),
   *         {@code recipeId} (String or null), {@code toolName} (String or null),
   *         {@code confidence} (double 0..1), {@code reason} (String),
   *         {@code turnGoal} (String or null), {@code successCriteria} (String or null).
   *         On parse failure returns {@code mode: plan} with {@code reason} describing the error.
   */
  static Map parseRouterJson(String raw) {
    String t = extractJsonPayload((raw ?: '').toString())
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
      String turnGoal = m.get('turnGoal')?.toString()?.trim()
      if (!turnGoal || 'null'.equalsIgnoreCase(turnGoal)) {
        turnGoal = null
      }
      String successCriteria = m.get('successCriteria')?.toString()?.trim()
      if (!successCriteria || 'null'.equalsIgnoreCase(successCriteria)) {
        successCriteria = null
      }

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

      [
        mode            : mode,
        recipeId        : rid,
        toolName        : toolName,
        confidence      : conf,
        reason          : reason,
        turnGoal        : turnGoal,
        successCriteria : successCriteria
      ]
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

  /**
   * Maps legacy router mode strings to {@code chat_only} | {@code recipe} | {@code tool} | {@code plan}.
   *
   * @param raw mode value from router JSON
   * @return normalized mode, or empty when unrecognized
   */
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

  /**
   * Parses router {@code confidence} from JSON (number or string).
   *
   * @param c confidence field from router JSON
   * @return clamped value in [0, 1], or 0 on failure
   */
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

  /**
   * Clamps router confidence to [0, 1].
   *
   * @param conf raw confidence
   * @return clamped confidence
   */
  private static double clampConfidence(double conf) {
    if (conf < 0.0d) {
      return 0.0d
    }
    if (conf > 1.0d) {
      return 1.0d
    }
    return conf
  }

  /**
   * Strips markdown fences and leading prose so router replies that start with {@code ## Plan} still parse
   * when a JSON object follows.
   *
   * @param raw full model reply
   * @return JSON object substring suitable for {@link JsonSlurper#parseText}, or trimmed input
   */
  static String extractJsonPayload(String raw) {
    String t = stripFences((raw ?: '').toString().trim())
    if (!t) {
      return ''
    }
    if (t.startsWith('{')) {
      return t
    }
    int start = t.indexOf('{')
    int end = t.lastIndexOf('}')
    if (start >= 0 && end > start) {
      return t.substring(start, end + 1).trim()
    }
    return t
  }

  /**
   * Removes optional markdown {@code ```} fences from the router model JSON reply before parsing.
   *
   * @param s trimmed raw text
   * @return text without surrounding code fence
   */
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
