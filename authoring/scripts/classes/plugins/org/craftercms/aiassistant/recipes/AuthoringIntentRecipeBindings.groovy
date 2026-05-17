package plugins.org.craftercms.aiassistant.recipes

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.List
import java.util.Locale
import java.util.Map
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Named prefetch artifacts ({@code initial.*} / {@code current.*}) and hint template expansion
 * ({@code {{initial.pageItem}}} / {@code {{current.pageItem}}}).
 */
final class AuthoringIntentRecipeBindings {

  private static final Logger log = LoggerFactory.getLogger(AuthoringIntentRecipeBindings.class)

  private AuthoringIntentRecipeBindings() {}

  static final String REQ_ATTR_INITIAL = 'aiassistant.recipeBindings.initial'
  static final String REQ_ATTR_CURRENT = 'aiassistant.recipeBindings.current'

  private static final Pattern STEP_REF = Pattern.compile('^\\$step(\\d+)\\.(.+)$')
  private static final Pattern LIFECYCLE_REF = Pattern.compile('^\\$(initial|current)\\.([a-zA-Z][a-zA-Z0-9_]*)(?:\\.(.+))?$')
  private static final Pattern NAMED_STEP_REF = Pattern.compile('^\\$([a-zA-Z][a-zA-Z0-9_]+)\\.(.+)$')
  private static final Pattern TEMPLATE_REF = Pattern.compile(
    '\\{\\{\\s*(initial|current)\\.([a-zA-Z][a-zA-Z0-9_]*)(?:\\.([a-zA-Z0-9_.]+))?\\s*\\}\\}'
  )

  static String stepOutputName(Map stepDef) {
    if (!(stepDef instanceof Map)) {
      return ''
    }
    String asName = stepDef.get('as')?.toString()?.trim()
    if (asName) {
      return asName
    }
    return stepDef.get('output')?.toString()?.trim() ?: ''
  }

  /**
   * @param stepDefs parallel to {@code stepSummaries} / raw results (same order as prefetch loop)
   */
  static Map<String, Map> buildInitialBindings(List<Map> stepDefs, List<Map> stepResults) {
    Map<String, Map> initial = new LinkedHashMap<>()
    int n = Math.min(stepDefs?.size() ?: 0, stepResults?.size() ?: 0)
    for (int i = 0; i < n; i++) {
      Map defn = stepDefs.get(i) instanceof Map ? (Map) stepDefs.get(i) : null
      String name = stepOutputName(defn)
      if (!name) {
        continue
      }
      Map res = stepResults.get(i) instanceof Map ? new LinkedHashMap<>((Map) stepResults.get(i)) : [:]
      initial.put(name, res)
    }
    return Collections.unmodifiableMap(initial)
  }

  static Map<String, Map> deepCopyBindingMap(Map<String, Map> src) {
    Map<String, Map> out = new LinkedHashMap<>()
    if (src == null) {
      return out
    }
    for (Map.Entry e : src.entrySet()) {
      String k = e.key?.toString()
      if (!k) {
        continue
      }
      Object v = e.value
      out.put(k, v instanceof Map ? new LinkedHashMap<>((Map) v) : [:])
    }
    out
  }

  static void installTurnState(StudioToolOperations ops, Map<String, Map> initialBindings) {
    if (ops?.request == null) {
      return
    }
    Map<String, Map> initial = initialBindings instanceof Map ? initialBindings : [:]
    Map<String, Map> current = deepCopyBindingMap(initial)
    ops.request.setAttribute(REQ_ATTR_INITIAL, initial)
    ops.request.setAttribute(REQ_ATTR_CURRENT, current)
  }

  static Map<String, Map> initialBindingsFromRequest(StudioToolOperations ops) {
    Object v = ops?.request?.getAttribute(REQ_ATTR_INITIAL)
    return v instanceof Map ? (Map<String, Map>) v : [:]
  }

  static Map<String, Map> currentBindingsFromRequest(StudioToolOperations ops) {
    Object v = ops?.request?.getAttribute(REQ_ATTR_CURRENT)
    return v instanceof Map ? (Map<String, Map>) v : initialBindingsFromRequest(ops)
  }

  static void updateCurrentArtifact(StudioToolOperations ops, String bindingName, Map artifactPatch) {
    if (!ops?.request || !bindingName?.trim() || !(artifactPatch instanceof Map)) {
      return
    }
    Map<String, Map> current = currentBindingsFromRequest(ops)
    if (!(current instanceof LinkedHashMap)) {
      current = deepCopyBindingMap(current)
    }
    Map existing = current.get(bindingName) instanceof Map ? new LinkedHashMap<>((Map) current.get(bindingName)) : [:]
    existing.putAll(artifactPatch)
    current.put(bindingName, existing)
    ops.request.setAttribute(REQ_ATTR_CURRENT, current)
  }

  static void updateCurrentFromWrite(StudioToolOperations ops, String repoPath, Map writeArgs) {
    if (!ops?.request || !repoPath?.trim()) {
      return
    }
    String path = repoPath.trim()
    String xml = writeArgs?.content?.toString() ?: writeArgs?.contentXml?.toString() ?: ''
    if (!xml?.trim()) {
      return
    }
    Map<String, Map> initial = initialBindingsFromRequest(ops)
    if (!initial) {
      return
    }
    for (Map.Entry e : initial.entrySet()) {
      Map art = e.value instanceof Map ? (Map) e.value : null
      if (!art) {
        continue
      }
      String artPath = (art.get('path') ?: art.get('contentPath') ?: '')?.toString()?.trim()
      if (artPath && artPath.equalsIgnoreCase(path)) {
        updateCurrentArtifact(ops, e.key.toString(), [
          path       : path,
          contentPath: path,
          contentXml : xml.trim(),
          source     : 'WriteContent'
        ])
        return
      }
    }
    updateCurrentArtifact(ops, 'lastWrite', [path: path, contentPath: path, contentXml: xml.trim(), source: 'WriteContent'])
  }

  static Object resolveArgValue(
    Object v,
    Map studioBindings,
    List<Map> stepResults,
    Map<String, Map> initialNamed,
    Map<String, Map> currentNamed
  ) {
    if (!(v instanceof String)) {
      return v
    }
    String s = ((String) v).trim()
    if ('$siteId'.equals(s)) {
      return studioBindings?.get('siteId') ?: ''
    }
    if ('$contentPath'.equals(s)) {
      return studioBindings?.get('contentPath') ?: ''
    }
    if ('$contentTypeId'.equals(s)) {
      return studioBindings?.get('contentTypeId') ?: ''
    }
    if ('$previewUrl'.equals(s)) {
      return studioBindings?.get('previewUrl') ?: ''
    }
    Matcher life = LIFECYCLE_REF.matcher(s)
    if (life.matches()) {
      String phase = life.group(1)
      String name = life.group(2)
      String path = life.group(3) ?: ''
      Map src = 'current'.equals(phase) ? currentNamed : initialNamed
      return navigateBinding(src, name, path)
    }
    Matcher stepM = STEP_REF.matcher(s)
    if (stepM.matches()) {
      int si = Integer.parseInt(stepM.group(1), 10)
      String path = stepM.group(2)
      if (si < 0 || si >= (stepResults?.size() ?: 0)) {
        return ''
      }
      return navigateMapPath(stepResults.get(si), path)
    }
    Matcher named = NAMED_STEP_REF.matcher(s)
    if (named.matches()) {
      String name = named.group(1)
      if ('initial'.equalsIgnoreCase(name) || 'current'.equalsIgnoreCase(name)) {
        return s
      }
      String path = named.group(2)
      return navigateBinding(initialNamed, name, path)
    }
    return s
  }

  private static Object navigateBinding(Map<String, Map> bindings, String name, String dotPath) {
    if (!bindings || !name) {
      return ''
    }
    Map art = bindings.get(name)
    if (!(art instanceof Map)) {
      return ''
    }
    if (!dotPath?.trim()) {
      return art
    }
    return navigateMapPath(art, dotPath)
  }

  private static Object navigateMapPath(Map root, String dotPath) {
    if (root == null || !dotPath?.trim()) {
      return ''
    }
    Object cur = root
    for (String part : dotPath.split('\\.')) {
      String p = part?.trim()
      if (!p || !(cur instanceof Map)) {
        return cur instanceof Map ? '' : cur
      }
      cur = ((Map) cur).get(p)
      if (cur == null) {
        return ''
      }
    }
    cur
  }

  static String expandHintTemplates(
    String text,
    Map<String, Map> initial,
    Map<String, Map> current,
    int maxCharsPerExpansion
  ) {
    if (!(text instanceof CharSequence) || !text.toString().contains('{{')) {
      return text?.toString() ?: ''
    }
    String cap = text.toString()
    Matcher m = TEMPLATE_REF.matcher(cap)
    StringBuffer sb = new StringBuffer()
    while (m.find()) {
      String phase = m.group(1)
      String name = m.group(2)
      String field = m.group(3)
      Map src = 'current'.equalsIgnoreCase(phase) ? current : initial
      Object val = navigateBinding(src, name, field ?: '')
      String rep = formatExpandedValue(val, maxCharsPerExpansion)
      m.appendReplacement(sb, Matcher.quoteReplacement(rep))
    }
    m.appendTail(sb)
    sb.toString()
  }

  private static String formatExpandedValue(Object val, int maxChars) {
    if (val == null) {
      return '(binding empty)'
    }
    if (val instanceof Map) {
      Map m = (Map) val
      if (m.get('contentXml') != null) {
        return truncateForHint(m.get('contentXml').toString(), maxChars, 'contentXml')
      }
      if (m.get('formDefinitionXml') != null) {
        return truncateForHint(m.get('formDefinitionXml').toString(), maxChars, 'formDefinitionXml')
      }
      String json = groovy.json.JsonOutput.toJson(m)
      return truncateForHint(json, maxChars, 'artifact')
    }
    return truncateForHint(val.toString(), maxChars, 'value')
  }

  private static String truncateForHint(String s, int maxChars, String label) {
    String t = (s ?: '').trim()
    if (!t) {
      return "($label empty)"
    }
    if (maxChars > 0 && t.length() > maxChars) {
      return t.substring(0, Math.max(0, maxChars - 24)) + '…[' + label + ' truncated]'
    }
    return t
  }

  static List<String> declaredBindingNames(Map recipe) {
    List<String> names = new ArrayList<>()
    Object raw = recipe?.get('bindings')
    if (raw instanceof List) {
      for (Object o : (List) raw) {
        if (o instanceof Map) {
          String n = ((Map) o).get('name')?.toString()?.trim()
          if (n && !names.contains(n)) {
            names.add(n)
          }
        } else {
          String n = o?.toString()?.trim()
          if (n && !names.contains(n)) {
            names.add(n)
          }
        }
      }
    }
    for (Map step : AuthoringIntentRecipeCatalog.collectEngineSteps(recipe)) {
      String n = stepOutputName(step)
      if (n && !names.contains(n)) {
        names.add(n)
      }
    }
    return names
  }
}
