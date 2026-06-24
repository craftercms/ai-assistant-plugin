package plugins.org.craftercms.aiassistant.studio.engine.turn

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.springframework.ai.tool.function.FunctionToolCallback
import plugins.org.craftercms.aiassistant.studio.engine.policy.ToolsLoopWirePolicy
import plugins.org.craftercms.aiassistant.studio.engine.policy.ToolsLoopWirePolicyRegistry
import plugins.org.craftercms.aiassistant.studio.sandbox.StudioAiSandboxClock

import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.List
import java.util.Map
import java.util.Set
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * When the model describes tool use in assistant prose (e.g. fenced {@code ```json}) but omits API
 * {@code tool_calls}, synthesize wire invocations from the session {@code byName} catalog only —
 * built-in, {@code InvokeSiteUserTool}, and {@code mcp_*} entries use the same policy registry and
 * execution path as real tool_calls.
 */
final class ProseDeclaredToolCalls {

  /** Utility class; no instances. */
  private ProseDeclaredToolCalls() {}

  /** Matches fenced JSON blocks in assistant markdown. */
  private static final Pattern FENCED_JSON =
    Pattern.compile('(?s)```(?:json)?\\s*([\\s\\S]*?)```')

  /** e.g. {@code functions.WriteContent({ ... })} — opening brace; args extracted via {@link #extractBalancedJsonObject}. */
  private static final Pattern FUNCTIONS_DOT_START =
    Pattern.compile('(?m)(?:^\\s*\\d+\\.\\s*)?functions\\.(\\w+)\\s*\\(\\s*\\{')

  /**
   * @param prose assistant message text (no API {@code tool_calls})
   * @param byName wired tools for this session
   * @return Chat Completions-style {@code tool_calls} entries to execute
   */
  static List<Map> synthesizeFromAssistantProse(String prose, Map<String, FunctionToolCallback> byName) {
    if (!prose?.trim() || !(byName instanceof Map) || byName.isEmpty()) {
      return []
    }

    List<Map> out = []
    Set<String> dedupe = new LinkedHashSet<>()
    JsonSlurper slurper = new JsonSlurper()
    Matcher matcher = FENCED_JSON.matcher(prose)

    while (matcher.find()) {
      String block = matcher.group(1)?.trim()
      if (!block) {
        continue
      }
      List<Map> batch = resolveProseToolBatchFromJsonBlock(block, byName, slurper)
      if (!batch.isEmpty()) {
        for (Map inv : batch) {
          appendProseInvocation(out, dedupe, inv, byName)
        }
        continue
      }
      Map inv = resolveInvocationFromJsonBlock(block, prose, matcher.start(), byName, slurper)
      appendProseInvocation(out, dedupe, inv, byName)
    }

    Matcher fnMatcher = FUNCTIONS_DOT_START.matcher(prose)
    while (fnMatcher.find()) {
      String wireName = fnMatcher.group(1)?.toString()?.trim() ?: ''
      if (!wireName || !byName.containsKey(wireName)) {
        continue
      }
      int openBrace = fnMatcher.end() - 1
      String argsJson = extractBalancedJsonObject(prose, openBrace)
      if (!argsJson) {
        continue
      }
      try {
        slurper.parseText(argsJson)
      } catch (Throwable ignored) {
        continue
      }
      appendProseInvocation(out, dedupe, [wireName: wireName, arguments: argsJson], byName)
    }

    return out
  }

  /**
   * Returns the JSON object substring starting at {@code openBraceIndex}, using brace depth outside strings.
   */
  private static String extractBalancedJsonObject(String text, int openBraceIndex) {
    if (!text || openBraceIndex < 0 || openBraceIndex >= text.length()) {
      return null
    }
    if (text.charAt(openBraceIndex) != (char) '{') {
      return null
    }
    int depth = 0
    boolean inString = false
    boolean escape = false
    for (int i = openBraceIndex; i < text.length(); i++) {
      char c = text.charAt(i)
      if (inString) {
        if (escape) {
          escape = false
        } else if (c == (char) '\\') {
          escape = true
        } else if (c == (char) '"') {
          inString = false
        }
        continue
      }
      if (c == (char) '"') {
        inString = true
        continue
      }
      if (c == (char) '{') {
        depth++
      } else if (c == (char) '}') {
        depth--
        if (depth == 0) {
          return text.substring(openBraceIndex, i + 1)
        }
      }
    }
    return null
  }

  /**
   * Append prose invocation.
   */
  private static void appendProseInvocation(
    List<Map> out,
    Set<String> dedupe,
    Map inv,
    Map<String, FunctionToolCallback> byName
  ) {
    if (inv == null) {
      return
    }
    String wireName = inv.wireName?.toString()?.trim() ?: ''
    String args = inv.arguments?.toString() ?: '{}'
    if (!wireName || !byName.containsKey(wireName)) {
      return
    }
    String key = wireName + '\0' + args
    if (!dedupe.add(key)) {
      return
    }
    out.add(buildToolCallMap(wireName, args))
  }

  /**
   * OpenAI-style {@code tool_uses} / {@code tool_calls} arrays inside fenced JSON
   * (e.g. {@code recipient_name: functions.ContentExists}).
   */
  private static List<Map> resolveProseToolBatchFromJsonBlock(
    String block,
    Map<String, FunctionToolCallback> byName,
    JsonSlurper slurper
  ) {
    Object parsed
    try {
      parsed = slurper.parseText(block)
    } catch (Throwable ignored) {
      return []
    }
    if (!(parsed instanceof Map)) {
      return []
    }
    Object batch = ((Map) parsed).tool_uses ?: ((Map) parsed).tool_calls
    if (!(batch instanceof List)) {
      return []
    }
    List<Map> out = []
    for (Object entryObj : (List) batch) {
      if (!(entryObj instanceof Map)) {
        continue
      }
      Map entry = (Map) entryObj
      String wireName = proseWireNameFromBatchEntry(entry)
      if (!wireName || !byName.containsKey(wireName)) {
        continue
      }
      Object params = entry.parameters instanceof Map ? entry.parameters :
        (entry.arguments instanceof Map ? entry.arguments : [:])
      Map args = new LinkedHashMap<>((Map) params)
      out.add([wireName: wireName, arguments: JsonOutput.toJson(args)])
    }
    return out
  }

  /**
   * Prose wire name from batch entry.
   * @param entry Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String proseWireNameFromBatchEntry(Map entry) {
    String recipient = (entry.recipient_name ?: entry.recipientName ?: '').toString().trim()
    if (recipient) {
      int dot = recipient.lastIndexOf('.')
      String tail = dot >= 0 ? recipient.substring(dot + 1) : recipient
      if (tail) {
        return tail
      }
    }
    String explicit = (entry.tool ?: entry.name ?: '').toString().trim()
    return explicit
  }

  /**
   * Parses one fenced JSON object into a wire name + arguments, or returns null if unmatched.
   */
  private static Map resolveInvocationFromJsonBlock(
    String block,
    String prose,
    int blockStart,
    Map<String, FunctionToolCallback> byName,
    JsonSlurper slurper
  ) {
    Object parsed
    try {
      parsed = slurper.parseText(block)
    } catch (Throwable ignored) {
      return null
    }
    if (!(parsed instanceof Map)) {
      return null
    }
    Map args = new LinkedHashMap<>((Map) parsed)

    Map dispatch = resolveProseJsonDispatch(args, byName)
    if (dispatch != null) {
      return dispatch
    }

    String explicitWire = args.tool?.toString()?.trim() ?: args.name?.toString()?.trim()
    if (explicitWire && byName.containsKey(explicitWire)) {
      args.remove('tool')
      args.remove('name')
      return [wireName: explicitWire, arguments: JsonOutput.toJson(args)]
    }

    String ctx = contextAround(prose, blockStart, 500)
    String wire = firstWireNameInText(ctx, byName)
    if (wire && !args.isEmpty()) {
      return [wireName: wire, arguments: JsonOutput.toJson(args)]
    }

    return null
  }

  /**
   * Match fenced JSON to a wired tool via {@link ToolsLoopWirePolicyRegistry#policyFor(String)} dispatch keys
   * (e.g. {@code toolId} on {@code InvokeSiteUserTool}).
   */
  private static Map resolveProseJsonDispatch(Map args, Map<String, FunctionToolCallback> byName) {
    for (String wireName : wireNamesByLengthDesc(byName)) {
      if (!byName.containsKey(wireName)) {
        continue
      }
      ToolsLoopWirePolicy pol = ToolsLoopWirePolicyRegistry.policyFor(wireName)
      String dispatchKey = pol.proseJsonDispatchKey?.trim()
      if (!dispatchKey) {
        continue
      }
      String val = args.get(dispatchKey)?.toString()?.trim()
      if (!val) {
        continue
      }
      return [wireName: wireName, arguments: JsonOutput.toJson(args)]
    }
    return null
  }

  /** Substring of assistant prose around a fenced block for heuristic wire-name detection. */
  private static String contextAround(String prose, int index, int radius) {
    int start = Math.max(0, index - radius)
    int end = Math.min(prose.length(), index + radius)
    return prose.substring(start, end)
  }

  /** First registered wire name (longest match first) appearing in {@code text}. */
  private static String firstWireNameInText(String text, Map<String, FunctionToolCallback> byName) {
    if (!text?.trim()) {
      return null
    }
    for (String wire : wireNamesByLengthDesc(byName)) {
      if (text.contains(wire)) {
        return wire
      }
    }
    return null
  }

  /** Wire names sorted descending by length so {@code InvokeSiteUserTool} wins over shorter substrings. */
  private static List<String> wireNamesByLengthDesc(Map<String, FunctionToolCallback> byName) {
    List<String> names = new ArrayList<>(byName.keySet())
    names.sort { a, b -> Integer.compare(b.length(), a.length()) }
    return names
  }

  /**
   * Builds a synthetic Chat Completions {@code tool_calls} entry for orchestration execution.
   * @param wireName registered function name
   * @param argsJson JSON object string for {@code function.arguments}
   */
  static Map buildToolCallMap(String wireName, String argsJson) {
    String id = 'prose_' + wireName + '_' + StudioAiSandboxClock.uniqueHexSuffix()
    return [
      id      : id,
      type    : 'function',
      function: [
        name     : wireName,
        arguments: argsJson ?: '{}'
      ]
    ]
  }
}
