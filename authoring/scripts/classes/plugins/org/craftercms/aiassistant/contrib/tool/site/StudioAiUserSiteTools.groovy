package plugins.org.craftercms.aiassistant.contrib.tool.site

import groovy.json.JsonSlurper
import groovy.lang.Binding
import groovy.lang.GroovyShell
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations

import java.util.regex.Pattern

/**
 * Loads and runs site-authored Groovy under {@code config/studio/scripts/aiassistant/user-tools/}
 * (Studio module path prefix {@code /scripts/aiassistant/user-tools/}), gated by {@code registry.json}.
 */
final class StudioAiUserSiteTools {

  private static final Logger LOG = LoggerFactory.getLogger(StudioAiUserSiteTools)

  /** Studio {@code configurationService} path (under {@code config/studio/}). */
  static final String USER_TOOLS_REGISTRY_PATH = '/scripts/aiassistant/user-tools/registry.json'

  static final String USER_TOOLS_DIR_PREFIX = '/scripts/aiassistant/user-tools/'

  private static final Pattern SAFE_TOOL_ID = Pattern.compile('^[a-zA-Z0-9_-]{1,64}$')

  private static final Pattern SAFE_SCRIPT_NAME = Pattern.compile('^[A-Za-z0-9][A-Za-z0-9_.-]*\\.groovy$')

  /** Utility class; no instances. */
  private StudioAiUserSiteTools() {}

  /**
   * Normalized registry rows: {@code id}, {@code script}, {@code description}, optional intent-routing
   * {@code matchHints}, {@code dontMatchHints}, and {@code priority} (same semantics as intent recipes).
   */
  static List<Map> loadRegistryEntries(StudioToolOperations ops, Map projectToolCfg = null) {
    List<Map> out = []
    if (ops == null) {
      return out
    }
    Set<String> disabledUser = StudioAiAssistantProjectConfig.disabledUserToolsSet(projectToolCfg)
    String siteId = ops.resolveEffectiveSiteId('')
    String raw = ops.readStudioConfigurationUtf8(siteId, USER_TOOLS_REGISTRY_PATH)
    if (!raw?.trim()) {
      return out
    }
    Object parsed
    try {
      parsed = new JsonSlurper().parseText(raw.toString().trim())
    } catch (Throwable t) {
      LOG.warn('StudioAiUserSiteTools: registry.json parse failed siteId={}: {}', siteId, t.message)
      return out
    }
    List<Object> rows = []
    if (parsed instanceof List) {
      rows.addAll((List) parsed)
    } else if (parsed instanceof Map) {
      Map pm = (Map) parsed
      Object tools = pm.get('tools')
      if (tools instanceof List) {
        rows.addAll((List) tools)
      } else if (tools == null) {
        Object entries = pm.get('entries')
        if (entries instanceof List) {
          rows.addAll((List) entries)
        }
      }
    }
    for (Object o : rows) {
      if (!(o instanceof Map)) {
        continue
      }
      Map m = (Map) o
      String id = m.id?.toString()?.trim()
      String script = m.script?.toString()?.trim()
      if (!script) {
        script = m.file?.toString()?.trim()
      }
      if (!id || !script) {
        continue
      }
      if (StudioAiAssistantProjectConfig.isUserToolDisabled(id, disabledUser)) {
        continue
      }
      if (!SAFE_TOOL_ID.matcher(id).matches()) {
        LOG.warn('StudioAiUserSiteTools: skipping registry row with invalid id pattern: {}', id)
        continue
      }
      if (!SAFE_SCRIPT_NAME.matcher(script).matches()) {
        LOG.warn('StudioAiUserSiteTools: skipping tool {} — invalid script name: {}', id, script)
        continue
      }
      int priority = 0
      Object pr = m.get('priority')
      if (pr instanceof Number) {
        priority = ((Number) pr).intValue()
      }
      out.add([
        id            : id,
        script        : script,
        description   : (m.description ?: m.desc ?: '')?.toString()?.trim() ?: '',
        matchHints    : hintStrings(m.get('matchHints')),
        dontMatchHints: hintStrings(m.get('dontMatchHints')),
        priority      : priority
      ] as Map)
    }
    out
  }

  /** Normalizes a JSON array of hint strings from registry rows (trimmed, non-empty only). */
  private static List<String> hintStrings(Object raw) {
    if (!(raw instanceof List)) {
      return []
    }
    List<String> out = []
    for (Object o : (List) raw) {
      String n = o?.toString()?.trim()
      if (n) {
        out.add(n)
      }
    }
    out
  }

  /**
   * Runs the Groovy source for {@code toolId} when listed in {@code registry.json}.
   * Binding: {@code studio} ({@link StudioToolOperations}, including {@link StudioToolOperations#fetchHttpUrl}),
   * {@code args} ({@link Map}), {@code toolId}, {@code siteId}, {@code log}.
   *
   * @return a {@link Map} suitable for Spring AI tool results (include {@code ok} boolean when possible)
   */
  static Map invokeRegisteredTool(StudioToolOperations ops, String toolId, Map args) {
    if (ops == null) {
      return [ok: false, error: true, message: 'studio operations unavailable'] as Map
    }
    String id = (toolId ?: '').toString().trim()
    if (!id || !SAFE_TOOL_ID.matcher(id).matches()) {
      return [ok: false, error: true, message: "Invalid toolId (use alphanumeric, '_', '-', max 64)."] as Map
    }
    Map cfg = StudioAiAssistantProjectConfig.load(ops)
    Set<String> disabledUser = StudioAiAssistantProjectConfig.disabledUserToolsSet(cfg)
    if (StudioAiAssistantProjectConfig.isUserToolDisabled(id, disabledUser)) {
      return [
        ok     : false,
        error  : true,
        message: "Site user tool '${id}' is disabled in tools.json (disabledUserTools)."
      ] as Map
    }
    List<Map> entries = loadRegistryEntries(ops, cfg)
    Map entry = null
    for (Map e : entries) {
      String rowId = e.id?.toString()?.trim()
      if (rowId && id.equals(rowId)) {
        entry = e
        break
      }
    }
    if (entry == null) {
      List<String> known = entries.collect { it.id?.toString() }.findAll { it }
      return [
        ok     : false,
        error  : true,
        message: "Unknown site user tool '${id}'. Registered ids: ${known.join(', ') ?: '(none — check registry.json)'}"
      ] as Map
    }
    String scriptName = entry.script?.toString()?.trim()
    if (!SAFE_SCRIPT_NAME.matcher(scriptName).matches()) {
      return [ok: false, error: true, message: "Invalid script name in registry for '${id}'."] as Map
    }
    String siteId = ops.resolveEffectiveSiteId('')
    String scriptPath = "${USER_TOOLS_DIR_PREFIX}${scriptName}"
    String src = ops.readStudioConfigurationUtf8(siteId, scriptPath)
    if (!src?.trim()) {
      return [
        ok     : false,
        error  : true,
        message: "No Groovy source at ${scriptPath} for tool '${id}' (empty or missing)."
      ] as Map
    }
    Map argMap = (args != null) ? new LinkedHashMap<>((Map) args) : new LinkedHashMap<>()
    Binding binding = new Binding()
    binding.setVariable('studio', ops)
    binding.setVariable('args', argMap)
    binding.setVariable('toolId', id)
    binding.setVariable('siteId', siteId)
    binding.setVariable('log', LOG)
    ClassLoader parent = null
    try {
      Object ctx = ops.studioApplicationContext()
      parent = ctx?.getClassLoader()
    } catch (Throwable ignored) {
      parent = Thread.currentThread().getContextClassLoader()
    }
    if (parent == null) {
      parent = StudioAiUserSiteTools.class.getClassLoader()
    }
    Object evaluated
    try {
      evaluated = new GroovyShell(parent, binding).evaluate(src.toString())
    } catch (Throwable t) {
      LOG.warn("StudioAiUserSiteTools: script error toolId={} path={}: {}", id, scriptPath, t.toString())
      String msg = t.message ?: t.toString()
      if (msg.length() > 2000) {
        msg = msg.substring(0, 2000) + '…'
      }
      return [
        ok     : false,
        error  : true,
        toolId : id,
        script : scriptPath,
        message: "User tool script failed: ${msg}"
      ] as Map
    }
    if (evaluated instanceof Map) {
      Map result = new LinkedHashMap<>((Map) evaluated)
      if (result.ok == null && result.error == null) {
        result.ok = true
      }
      result.toolId = id
      result.siteUserTool = true
      return result
    }
    return [
      ok           : true,
      toolId       : id,
      siteUserTool : true,
      result       : evaluated,
      resultType   : evaluated?.getClass()?.name
    ] as Map
  }
}
