import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.engine.context.SiteProjectContext
import plugins.org.craftercms.aiassistant.contrib.imagegen.StudioAiScriptImageGenLoader
import plugins.org.craftercms.aiassistant.engine.prompt.ToolPromptsOverrideCatalog
import plugins.org.craftercms.aiassistant.studio.secrets.StudioAiAssistantSecretsService
import plugins.org.craftercms.aiassistant.contrib.tool.site.StudioAiUserSiteTools
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations

/**
 * Site sandbox index for AI Assistant scripted user tools, image generators, and script LLMs.
 * Query: {@code siteId} (required).
 */
def idxLog = LoggerFactory.getLogger('plugins.org.craftercms.aiassistant.scripts.index.get')

/** Closure (not a separate method) so limits stay in script scope — top-level methods cannot see run() locals. */
def safeUtf8ByteLength = { String s ->
  final int maxCharsForExactBytes = 400_000
  if (s == null || s.isEmpty()) {
    return 0
  }
  if (s.length() > maxCharsForExactBytes) {
    return -1
  }
  return s.getBytes('UTF-8').length
}

String siteId = (params?.siteId ?: request.getParameter('siteId'))?.toString()?.trim()
if (!siteId) {
  response.status = 400
  return [ok: false, message: 'Missing siteId']
}

def ops = new StudioToolOperations(request, applicationContext, params)

String registryRel = StudioAiUserSiteTools.USER_TOOLS_REGISTRY_PATH
String registryTextRaw = ops.readStudioConfigurationUtf8(siteId, registryRel) ?: ''
final int maxRegistryTextChars = 262_144
boolean registryTextTruncated = registryTextRaw.length() > maxRegistryTextChars
String registryText = registryTextTruncated ? registryTextRaw.substring(0, maxRegistryTextChars) : registryTextRaw

List<Map> toolRows = StudioAiUserSiteTools.loadRegistryEntries(ops)
List<Map> toolsOut = []
for (Map e : toolRows) {
  try {
    String id = e.id?.toString()
    String script = e.script?.toString()
    String desc = e.description?.toString() ?: ''
    String scriptPath = "${StudioAiUserSiteTools.USER_TOOLS_DIR_PREFIX}${script}"
    String src = ops.readStudioConfigurationUtf8(siteId, scriptPath) ?: ''
    toolsOut.add([
      id            : id,
      script        : script,
      description   : desc,
      matchHints    : e.matchHints instanceof List ? e.matchHints : [],
      dontMatchHints: e.dontMatchHints instanceof List ? e.dontMatchHints : [],
      priority      : e.priority instanceof Number ? ((Number) e.priority).intValue() : 0,
      studioPath    : scriptPath,
      hasSource     : (src?.trim() ? true : false),
      byteLength    : safeUtf8ByteLength(src.toString())
    ] as Map)
  } catch (Throwable t) {
    idxLog.warn('scripts index: skip tool row siteId={} id={}: {}', siteId, e?.id, t.message)
  }
}

List<String> imageIds = []
try {
  imageIds = ops.listStudioSandboxChildFolderNames(siteId, "${StudioAiScriptImageGenLoader.IMAGEGEN_DIR_PREFIX}".toString())
} catch (Throwable ignored) {
}
List<Map> imageOut = []
for (String gid : imageIds) {
  if (!gid) {
    continue
  }
  try {
    String rel = "${StudioAiScriptImageGenLoader.IMAGEGEN_DIR_PREFIX}${gid}/generate.groovy"
    String src = ops.readStudioConfigurationUtf8(siteId, rel) ?: ''
    imageOut.add([
      id         : gid,
      studioPath : rel,
      hasSource  : (src?.trim() ? true : false),
      byteLength : safeUtf8ByteLength(src.toString())
    ] as Map)
  } catch (Throwable t) {
    idxLog.warn('scripts index: skip imageGen siteId={} id={}: {}', siteId, gid, t.message)
  }
}

List<String> llmIds = []
try {
  llmIds = ops.listStudioSandboxChildFolderNames(siteId, '/scripts/aiassistant/llm/')
} catch (Throwable ignored2) {
}
List<Map> llmOut = []
List<String> llmEntryFiles = ['runtime.groovy', 'llm.groovy']
for (String lid : llmIds) {
  if (!lid) {
    continue
  }
  try {
    String body = null
    String used = null
    for (String fn : llmEntryFiles) {
      String rel = "/scripts/aiassistant/llm/${lid}/${fn}"
      String b = ops.readStudioConfigurationUtf8(siteId, rel)
      if (b?.trim()) {
        body = b.toString()
        used = rel
        break
      }
    }
    llmOut.add([
      id         : lid,
      studioPath : used,
      hasSource  : (body?.trim() ? true : false),
      byteLength : body != null ? safeUtf8ByteLength(body.toString()) : 0
    ] as Map)
  } catch (Throwable t) {
    idxLog.warn('scripts index: skip llm siteId={} id={}: {}', siteId, lid, t.message)
  }
}

Map projectContextRow = [
  studioPath : SiteProjectContext.STUDIO_MODULE_REL,
  hasContent : false,
  byteLength : 0
] as Map
try {
  String pcSrc = ops.readStudioConfigurationUtf8(siteId, SiteProjectContext.STUDIO_MODULE_REL) ?: ''
  boolean pcPresent = SiteProjectContext.meaningfulBodyOrNull(pcSrc) != null
  projectContextRow = [
    studioPath : SiteProjectContext.STUDIO_MODULE_REL,
    hasContent : pcPresent,
    byteLength : pcPresent ? safeUtf8ByteLength(pcSrc.toString()) : 0
  ] as Map
} catch (Throwable t) {
  idxLog.warn('scripts index: project context siteId={}: {}', siteId, t.message)
}

List<Map> promptRows = []
for (String pk : ToolPromptsOverrideCatalog.KEYS) {
  if (!pk) {
    continue
  }
  try {
    String rel = "/scripts/aiassistant/prompts/${pk}.md"
    String src = ops.readStudioConfigurationUtf8(siteId, rel) ?: ''
    promptRows.add([
      key        : pk,
      studioPath : rel,
      hasOverride: (src?.trim() ? true : false),
      byteLength : safeUtf8ByteLength(src.toString())
    ] as Map)
  } catch (Throwable t) {
    idxLog.warn('scripts index: skip prompt override siteId={} key={}: {}', siteId, pk, t.message)
  }
}

List<Map> knownSecrets = []
List<Map> customSecrets = []
String secretsStudioPath = ''
String secretsError = null
boolean secretsSeeded = false
try {
  secretsSeeded = StudioAiAssistantSecretsService.ensureDefaultSecretsFileIfMissing(ops)
  Map secretsAdmin = StudioAiAssistantSecretsService.adminIndex(ops)
  if (secretsAdmin instanceof Map) {
    knownSecrets = secretsAdmin.knownSecrets instanceof List ? (List<Map>) secretsAdmin.knownSecrets : []
    customSecrets = secretsAdmin.customSecrets instanceof List ? (List<Map>) secretsAdmin.customSecrets : []
    secretsStudioPath = secretsAdmin.studioPath?.toString() ?: ''
  }
} catch (Throwable t) {
  secretsError = t.message ?: t.toString()
  idxLog.warn('scripts index: secrets admin index failed siteId={}: {}', siteId, secretsError)
}

response.setContentType('application/json')
return [
  ok                   : true,
  siteId               : siteId,
  registryStudioPath   : registryRel,
  registryText         : registryText,
  registryTextTruncated: registryTextTruncated,
  tools                : toolsOut,
  imageGenerators      : imageOut,
  llmScripts           : llmOut,
  projectContext       : projectContextRow,
  toolPromptOverrides  : promptRows,
  secretsStudioPath    : secretsStudioPath,
  knownSecrets         : knownSecrets,
  customSecrets        : customSecrets,
  secretsError         : secretsError,
  secretsSeeded        : secretsSeeded
]
