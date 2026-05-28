package plugins.org.craftercms.aiassistant.studio.engine.catalog

import plugins.org.craftercms.aiassistant.studio.engine.context.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.studio.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.studio.engine.util.ParallelToolExecutor
import plugins.org.craftercms.aiassistant.studio.engine.util.ContentSubgraphAggregator
import plugins.org.craftercms.aiassistant.studio.http.AiHttpProxy
import plugins.org.craftercms.aiassistant.studio.engine.turn.AiOrchestration
import plugins.org.craftercms.aiassistant.studio.engine.catalog.StudioAiToolRegistry
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsGetContent
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsWriteContent
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolProgress
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.playbook.CrafterizingPlaybookLoader
import plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.contrib.tool.site.StudioAiUserSiteTools
import plugins.org.craftercms.aiassistant.studio.engine.autonomous.AutonomousAssistantWorker
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.function.FunctionToolCallback
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

import javax.xml.parsers.DocumentBuilderFactory
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Collection
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.List
import java.util.Iterator
import java.util.Locale
import java.util.Set
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Builds Spring AI tool callbacks for {@link plugins.org.craftercms.aiassistant.studio.engine.turn.AiOrchestration}.
 * <p>Core tools are {@link plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiOrchestrationTool} classes under
 * {@code tools.cms}, {@code tools.development}, and {@code tools.general}, composed by
 * {@link plugins.org.craftercms.aiassistant.studio.engine.catalog.StudioAiToolRegistry} (all built-ins implement
 * {@link plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiOrchestrationTool}); this class holds shared transform
 * helpers and catalog filters.</p>
 * <p><strong>Every</strong> {@link FunctionToolCallback} must call {@code .inputSchema(...)} — upstream chat APIs reject
 * bare {@code Map.class} schemas ("object schema missing properties").</p>
 */
class AiOrchestrationTools {
  private static final Logger log = LoggerFactory.getLogger(AiOrchestrationTools.class)

  /** Slashy-string regex cannot use {@code \\s} in all positions; use {@link Pattern} + single-quoted strings for Studio Groovy. */
  private static final Pattern DISPLAY_TEMPLATE_PAGES = Pattern.compile('(?i)/templates/web/pages/([^./\\s]+)\\.ftl')
  private static final Pattern DISPLAY_TEMPLATE_COMPONENTS = Pattern.compile('(?i)/templates/web/components/([^./\\s]+)\\.ftl')
  private static final Pattern DISPLAY_TEMPLATE_FLAT_WEB = Pattern.compile('(?i)^/templates/web/([^./\\s]+)\\.ftl$')

  /**
   * Constructs DocumentBuilderFactory with XXE-hardening flags best-effort.
   * Disables external DTD expansion where supported.
   * Feeds parseXmlDocument with safer DOM parses.
   */
  private static DocumentBuilderFactory newSecureDocumentBuilderFactory() {
    def factory = DocumentBuilderFactory.newInstance()
    try {
      factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
    } catch (Throwable ignored) {}
    try {
      factory.setFeature('http://xml.org/sax/features/external-general-entities', false)
      factory.setFeature('http://xml.org/sax/features/external-parameter-entities', false)
    } catch (Throwable ignored) {}
    factory.setXIncludeAware(false)
    factory.setExpandEntityReferences(false)
    return factory
  }

  /**
   * Parses XML strings via hardened DOM builders.
   * Throws IllegalArgumentException with short context when malformed.
   * Centralizes parser configuration for authoring transforms.
   */
  private static Document parseXmlDocument(String xml) {
    if (!xml?.trim()) return null
    def factory = newSecureDocumentBuilderFactory()
    return factory.newDocumentBuilder().parse(
      new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
  }

  /**
   * Strips DOM namespaces / prefixes from QName-like strings.
   * Returns lowercase local fragments for comparisons.
   * Avoids brittle equality checks across Xerces variants.
   */
  private static String xmlElementLocalName(String nodeName) {
    if (nodeName == null) return null
    int i = nodeName.indexOf(':')
    return (i >= 0) ? nodeName.substring(i + 1) : nodeName
  }

  /**
   * Depth-first: first element whose local name matches (e.g. {@code content-type} with any namespace prefix).
   */
  private static String findFirstElementTextByLocalName(Node node, String wantedLocal, boolean ignoreCase) {
    if (node == null) return null
    if (node.nodeType == Node.ELEMENT_NODE) {
      def ln = xmlElementLocalName(node.nodeName)
      boolean match = ignoreCase ? wantedLocal.equalsIgnoreCase(ln) : wantedLocal.equals(ln)
      if (match) {
        def t = node.textContent?.trim()
        if (t) return t
      }
      Node ch = node.firstChild
      while (ch != null) {
        def r = findFirstElementTextByLocalName(ch, wantedLocal, ignoreCase)
        if (r) return r
        ch = ch.nextSibling
      }
    }
    return null
  }

  /**
   * Infer {@code /page/foo} or {@code /component/bar} from {@code <display-template>} when present.
   */
  private static String extractContentTypeFromDisplayTemplate(String xml) {
    if (!xml?.trim()) return null
    def m = (xml =~ /(?is)<(?:[\w.-]+:)?display-template\s*>\s*([^<]+?)\s*<\/(?:[\w.-]+:)?display-template\s*>/)
    if (!m.find()) return null
    def t = m.group(1).trim()
    def p = (t =~ DISPLAY_TEMPLATE_PAGES)
    if (p.find()) return "/page/${p.group(1)}"
    def c = (t =~ DISPLAY_TEMPLATE_COMPONENTS)
    if (c.find()) return "/component/${c.group(1)}"
    // Blueprints often use /templates/web/<name>.ftl (e.g. entry.ftl) — infer type from root element.
    def flat = (t =~ DISPLAY_TEMPLATE_FLAT_WEB)
    if (flat.find()) {
      def base = flat.group(1)
      if ((xml =~ /(?is)<\s*page(?:\s|>)/).find()) return "/page/${base}"
      if ((xml =~ /(?is)<\s*component(?:\s|>)/).find()) return "/component/${base}"
    }
    return null
  }

  /**
   * Reads content type id from page/component XML: {@code <content-type>} (any prefix / case), else {@code display-template} path heuristic.
   */
  /** Used by {@link plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentRecipeEngine} to mirror GetContentTypeFormDefinition resolution. */
  static String extractContentTypeIdFromItemXml(String xml) {
    if (!xml?.trim()) return null
    def relaxed = (xml =~ /(?is)<(?:[\w.-]+:)?content-type\s*>\s*([^<]+?)\s*<\/(?:[\w.-]+:)?content-type\s*>/)
    if (relaxed.find()) {
      def v = relaxed.group(1).trim()
      if (v) return v
    }
    try {
      def doc = parseXmlDocument(xml)
      def root = doc?.documentElement
      def fromDom = findFirstElementTextByLocalName(root, 'content-type', true)
      if (fromDom) return fromDom
    } catch (Throwable e) {
      log.debug('extractContentTypeIdFromItemXml DOM pass failed: {}', e.toString())
    }
    def fromTemplate = extractContentTypeFromDisplayTemplate(xml)
    if (fromTemplate) {
      log.debug('extractContentTypeIdFromItemXml: using display-template heuristic -> {}', fromTemplate)
      return fromTemplate
    }
    return null
  }

  /**
   * Sandbox repository path from built-in tool arguments. Prefer {@code path}; also accept {@code contentPath}
   * (same key as {@code update_content} / authoring context) and a few other aliases models send.
   */
  /** Used by {@link plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentRecipeEngine} for GetContent-style path keys. */
  static String repoPathFromToolInput(Map input) {
    if (input == null) return ''
    def s = input.path?.toString()?.trim()
    if (s) return s
    s = input.contentPath?.toString()?.trim()
    if (s) return s
    s = input.repositoryPath?.toString()?.trim()
    if (s) return s
    s = input.repository_path?.toString()?.trim()
    if (s) return s
    s = input.repoPath?.toString()?.trim()
    if (s) return s
    s = input.repo_path?.toString()?.trim()
    if (s) return s
    s = input.filePath?.toString()?.trim()
    if (s) return s
    input.file_path?.toString()?.trim() ?: ''
  }

  /**
   * Repairs common model JSON mistakes for {@link plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.WriteContentTool}
   * (e.g. {@code "contentXml,"} keys, {@code xml}/{@code body} aliases).
   */
  static Map normalizeWriteContentToolArgsMap(Map raw) {
    if (!(raw instanceof Map)) {
      return [:]
    }
    Map out = new LinkedHashMap()
    for (def entry : raw.entrySet()) {
      String k = entry.key?.toString()
      if (!k) {
        continue
      }
      String nk = k.replaceAll(/[,;:\s]+$/, '').trim()
      if (!nk) {
        continue
      }
      if (!out.containsKey(nk)) {
        out.put(nk, entry.value)
      }
    }
    if (!out.contentXml) {
      for (String alias : ['xml', 'body', 'content', 'itemXml', 'item_xml', 'updatedXml', 'updated_xml']) {
        if (out.get(alias) != null) {
          out.contentXml = out.get(alias)
          break
        }
      }
    }
    if (!out.path && out.contentPath) {
      out.path = out.contentPath
    }
    if (!out.contentPath && out.path) {
      out.contentPath = out.path
    }
    return out
  }

  /** Normalizes WriteContent {@code tool_calls} arguments JSON before {@link FunctionToolCallback#call}. */
  static String normalizeWriteContentToolArgsJson(String argsStr) {
    String raw = (argsStr ?: '{}').toString()
    try {
      def parsed = new JsonSlurper().parseText(raw)
      if (parsed instanceof Map) {
        return JsonOutput.toJson(normalizeWriteContentToolArgsMap((Map) parsed))
      }
    } catch (Throwable ignored) {
    }
    return raw
  }

  /** XXE-hardened parse for untrusted form-definition.xml from the repository. */
  private static Document parseFormDefinitionXmlSecure(String formXml) {
    def factory = DocumentBuilderFactory.newInstance()
    try {
      factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
    } catch (Throwable ignored) {}
    try {
      factory.setFeature('http://xml.org/sax/features/external-general-entities', false)
      factory.setFeature('http://xml.org/sax/features/external-parameter-entities', false)
      factory.setFeature('http://apache.org/xml/features/nonvalidating/load-external-dtd', false)
    } catch (Throwable ignored) {}
    factory.setXIncludeAware(false)
    factory.setExpandEntityReferences(false)
    factory.setNamespaceAware(true)
    return factory.newDocumentBuilder().parse(
      new ByteArrayInputStream(formXml.getBytes(StandardCharsets.UTF_8)))
  }

  /**
   * Collects {@code <field><id>...</id>} values from form-definition.xml for a compact hint to the model.
   * <p>Uses JDK {@link DocumentBuilderFactory} — {@code groovy.util.XmlSlurper} is not on Studio plugin script compile classpath.</p>
   */
  static List<String> extractFormFieldIdsFromFormDefinitionXml(String formXml) {
    if (!formXml?.trim()) return []
    try {
      def doc = parseFormDefinitionXmlSecure(formXml)
      def fields = doc.getElementsByTagName('field')
      def ids = new LinkedHashSet<String>()
      for (int i = 0; i < fields.length; i++) {
        def fieldEl = fields.item(i) as Element
        def idNodes = fieldEl.getElementsByTagName('id')
        if (idNodes.length > 0) {
          def id = idNodes.item(0).textContent?.trim()
          if (id) ids.add(id)
        }
      }
      return ids.toList().sort()
    } catch (Throwable t) {
      log.debug('extractFormFieldIdsFromFormDefinitionXml failed: {}', t.toString())
      return []
    }
  }

  /** {@code <field>} {@code <id>} + {@code <title>} pairs for label → id matching (Studio form-definition.xml). */
  static List<Map<String, String>> extractFormFieldTitleIdPairsFromFormDefinitionXml(String formXml) {
    if (!formXml?.trim()) {
      return []
    }
    try {
      def doc = parseFormDefinitionXmlSecure(formXml)
      def fields = doc.getElementsByTagName('field')
      List<Map<String, String>> pairs = new ArrayList<>()
      for (int i = 0; i < fields.length; i++) {
        def fieldEl = fields.item(i) as Element
        String id = ''
        String title = ''
        for (int c = 0; c < fieldEl.childNodes.length; c++) {
          def node = fieldEl.childNodes.item(c)
          if (node?.nodeType != org.w3c.dom.Node.ELEMENT_NODE) {
            continue
          }
          String local = node.localName ?: node.nodeName
          if ('id'.equalsIgnoreCase(local) && !id) {
            id = node.textContent?.trim() ?: ''
          } else if ('title'.equalsIgnoreCase(local) && !title) {
            title = node.textContent?.trim() ?: ''
          }
        }
        if (id) {
          pairs.add([id: id, title: title ?: id] as Map<String, String>)
        }
      }
      return pairs
    } catch (Throwable t) {
      log.debug('extractFormFieldTitleIdPairsFromFormDefinitionXml failed: {}', t.toString())
      return []
    }
  }

  /**
   * Lowercases, trims, collapses whitespace on author-supplied labels.
   * Removes punctuation noise before catalog lookups.
   * Feeds deterministic mapping from UI labels to field ids.
   */
  static String normalizeFormFieldLabelForMatch(String raw) {
    if (!raw?.trim()) {
      return ''
    }
    return raw
      .trim()
      .toLowerCase(Locale.ROOT)
      .replace('\u00a0', ' ')
      .replaceAll(/[^\p{L}\p{N}\s_-]+/, ' ')
      .replaceAll(/\s+/, ' ')
      .trim()
  }

  /**
   * Maps author wording (e.g. "hero title") to a single field {@code id} via form-definition {@code <title>} text.
   * Returns empty when ambiguous or no match.
   */
  static String resolveFieldIdFromFormDefinitionByAuthorLabel(String formXml, String authorLabelPhrase) {
    String want = normalizeFormFieldLabelForMatch(authorLabelPhrase)
    if (!want || !formXml?.trim()) {
      return ''
    }
    List<Map<String, String>> pairs = extractFormFieldTitleIdPairsFromFormDefinitionXml(formXml)
    String exactId = ''
    List<String> fuzzyIds = new ArrayList<>()
    for (Map<String, String> pair : pairs) {
      String id = pair.get('id')?.trim() ?: ''
      String titleNorm = normalizeFormFieldLabelForMatch(pair.get('title'))
      if (!id || !titleNorm) {
        continue
      }
      if (titleNorm == want) {
        exactId = id
        break
      }
      if (titleNorm.contains(want) || want.contains(titleNorm)) {
        fuzzyIds.add(id)
      }
    }
    if (exactId) {
      return exactId
    }
    if (fuzzyIds.size() == 1) {
      return fuzzyIds.get(0)
    }
    return ''
  }

  private static final int TRANSLATE_BATCH_MAX_PATHS = 100

  private static final int TRANSFORM_SUBGRAPH_MAX_CHARS = 280_000

  private static final int TRANSFORM_MAX_OUT_TOKENS = 32_768

  /** Strip optional ``` / ```xml fences from model output. */
  static String stripOptionalMarkdownFences(String raw) {
    if (raw == null) {
      return ''
    }
    String t = raw.toString().trim()
    if (!t.startsWith('```')) {
      return t
    }
    int firstNl = t.indexOf('\n')
    if (firstNl > 0) {
      t = t.substring(firstNl + 1)
    } else {
      t = t.substring(3).trim()
      int n2 = t.indexOf('\n')
      if (n2 >= 0) {
        t = t.substring(n2 + 1)
      }
    }
    if (t.endsWith('```')) {
      t = t.substring(0, t.length() - 3).trim()
    }
    int lastFence = t.lastIndexOf('```')
    if (lastFence >= 0) {
      t = t.substring(0, lastFence).trim()
    }
    return t
  }

  /**
   * Logs everything the subgraph tool uses so operators can see payload weight (slow inner LLM vs slow Studio writes).
   * Bundle XML is logged via {@link AiHttpProxy#elideForLog} (head + tail); instructions up to {@code maxInstrLog} chars.
   */
  private static void logTransformContentSubgraphPayload(
    Map input,
    String siteId,
    String contentPath,
    String instructions,
    boolean writeResults,
    String unlock,
    Integer maxItems,
    Integer maxDepth,
    String llmModel,
    int readTimeoutMs,
    Map built,
    String subgraphXml,
    String sys,
    String userBody,
    int maxInstrLog = 50_000,
    int maxBundleLogPreview = 36_000
  ) {
    try {
      def wire = [
        siteId       : siteId,
        contentPath  : contentPath,
        writeResults : writeResults,
        unlock       : unlock,
        maxItems     : maxItems,
        maxDepth     : maxDepth,
        llmModel     : llmModel,
        readTimeoutMs: readTimeoutMs,
      ]
      log.debug(
        'TransformContentSubgraph DIAG resolvedArgs={}',
        JsonOutput.prettyPrint(JsonOutput.toJson(wire))
      )
      def rawJson = ''
      try {
        rawJson = JsonOutput.toJson(input ?: [:])
      } catch (Throwable je) {
        rawJson = '(input not serializable: ' + (je.message ?: je.toString()) + ')'
      }
      log.debug(
        'TransformContentSubgraph DIAG rawToolInputJsonChars={} rawToolInputPreview=\n{}',
        rawJson.length(),
        AiHttpProxy.elideForLog(rawJson, 12_000)
      )
      def ins = (instructions ?: '').toString()
      log.debug(
        'TransformContentSubgraph DIAG instructionsChars={} instructionsText=\n{}',
        ins.length(),
        ins.length() > maxInstrLog ? (ins.substring(0, maxInstrLog) + '\n… [+' + (ins.length() - maxInstrLog) + ' chars truncated for log]') : ins
      )
      def xml = (subgraphXml ?: '').toString()
      int xlen = xml.length()
      log.debug(
        'TransformContentSubgraph DIAG bundleXmlChars={} documentCount={} root={} truncatedFromWalk={} maxDepthReachedWalk={}',
        xlen,
        built?.documentCount,
        built?.root,
        built?.truncated,
        built?.maxDepthReached
      )
      def plist = built?.paths
      if (plist instanceof List) {
        log.debug('TransformContentSubgraph DIAG paths[{}]=\n{}', ((List) plist).size(), JsonOutput.prettyPrint(JsonOutput.toJson(plist)))
      } else {
        log.debug('TransformContentSubgraph DIAG paths={}', String.valueOf(plist))
      }
      log.debug(
        'TransformContentSubgraph DIAG innerChatCompletionsUserMessage: systemPromptChars={} userMessageTotalChars={} (instructions+headers+bundle)',
        (sys ?: '').length(),
        (userBody ?: '').length()
      )
      log.debug(
        'TransformContentSubgraph DIAG bundleXmlPreview (elide max ~{} chars)=\n{}',
        maxBundleLogPreview,
        xlen == 0 ? '(empty)' : AiHttpProxy.elideForLog(xml, maxBundleLogPreview)
      )
    } catch (Throwable t) {
      log.warn('TransformContentSubgraph DIAG logging failed: {}', t.message)
    }
  }

  /**
   * Recognizes Crafter `<page>` vs `<component>` roots quickly.
   * Uses substring guards safe for partial reads.
   * Inform tooling hints without fully unmarshalling XML.
   */
  private static String cqDetectItemRootKind(String xml) {
    if (!xml?.trim()) {
      return null
    }
    Matcher m = Pattern.compile('(?is)<\\s*(?:[\\w.-]+:)?(page|component)\\b').matcher(xml.trim())
    return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null
  }

  /** Worker-thread results must always be {@link Map}s for batch aggregation (classloader / Groovy edge cases). */
  private static Map coerceTranslateBatchFutureRow(Object row, String pathHint) {
    if (row == null) {
      return [
        path : pathHint,
        error: true,
        message: 'Translate cell returned null (internal pipeline error).',
      ]
    }
    if (row instanceof Map) {
      return (Map) row
    }
    return [
      path : pathHint,
      error: true,
      message: 'Translate cell returned unexpected type: ' + row.getClass().name,
    ]
  }

  /**
   * {@link TranslateContentItem} with {@code maxItems=1}: send **raw** repository XML to the inner model and write the
   * reply directly to {@code contentPath} — no {@code <document>} / subgraph round-trip.
   */
  private static Map runTranslateContentItemRawInner(
    StudioToolOperations ops,
    Map built,
    Map input,
    String siteId,
    String contentPath,
    String instructions,
    String apiKey,
    String llmModel,
    int innerMaxOutTokens,
    int readTimeoutMs,
    boolean writeResults,
    String unlock,
    String normProtected,
    boolean pathProtect,
    String actionTag,
    String diag
  ) {
    String normProt = pathProtect ? AuthoringPreviewContext.normalizeRepoPath(normProtected) : ''
    if (pathProtect && normProt && AuthoringPreviewContext.sameRepoPath(contentPath, normProt)) {
      return [
        error      : true,
        action     : actionTag,
        message    :
          'Skipped: Studio form client-apply item — put field edits in aiassistantFormFieldUpdates or use WriteContent for other paths.',
        siteId     : siteId,
        contentPath: contentPath,
        paths      : [contentPath],
      ]
    }
    Map gotItem
    try {
      gotItem = CmsGetContent.read(ops, siteId, contentPath) as Map
    } catch (Throwable getEx) {
      return [
        error      : true,
        action     : actionTag,
        message    : 'GetContent failed before inner translate: ' + (getEx.message ?: getEx.toString()),
        siteId     : siteId,
        contentPath: contentPath,
      ]
    }
    String itemXml = gotItem?.contentXml?.toString() ?: ''
    if (!itemXml.trim()) {
      return [
        error      : true,
        action     : actionTag,
        message    : 'Empty repository XML for path ' + contentPath,
        siteId     : siteId,
        contentPath: contentPath,
      ]
    }
    if (itemXml.length() > TRANSFORM_SUBGRAPH_MAX_CHARS) {
      return [
        error      : true,
        action     : actionTag,
        message    :
          "Item XML is ${itemXml.length()} characters (limit ${TRANSFORM_SUBGRAPH_MAX_CHARS}). Narrow scope or use GetContent/WriteContent.",
        siteId     : siteId,
        contentPath: contentPath,
      ]
    }
    String origKind = cqDetectItemRootKind(itemXml)
    String sys = ToolPrompts.getTRANSLATE_CONTENT_ITEM_INNER_SYSTEM_RAW()
    String userBody =
      '## Instructions\n' +
      instructions +
      '\n\n## Repository path (informational — server writes here)\n' +
      contentPath +
      '\n\n## Item XML\n' +
      itemXml +
      '\n' +
      ToolPrompts.getTRANSLATE_CONTENT_ITEM_INNER_USER_APPENDIX_RAW()
    logTransformContentSubgraphPayload(
      input,
      siteId,
      contentPath,
      instructions,
      writeResults,
      unlock,
      Integer.valueOf(1),
      Integer.valueOf(0),
      llmModel,
      readTimeoutMs,
      built,
      itemXml,
      sys,
      userBody
    )
    if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
      return [
        error    : true,
        action   : actionTag,
        cancelled: true,
        message  : 'Request was stopped before the inner LLM call.',
        siteId   : siteId,
        paths    : [contentPath],
      ]
    }
    AiOrchestration.aiAssistantToolWorkerDiagPhase("${diag}_await_inner_openai_raw_item chars=${itemXml.length()}")
    long tOpenAi = System.nanoTime()
    String assistantXml =
      AiOrchestration.toolsLoopSimpleCompletionAssistantText(
        apiKey,
        llmModel,
        sys,
        userBody,
        innerMaxOutTokens,
        readTimeoutMs,
        diag
      )
    log.debug(
      '{} DIAG innerRawItem wallMs={} assistantChars={}',
      diag,
      (System.nanoTime() - tOpenAi) / 1_000_000L,
      (assistantXml ?: '').length()
    )
    if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
      return [
        error    : true,
        action   : actionTag,
        cancelled: true,
        message  : 'Request was stopped after the inner LLM returned.',
        siteId   : siteId,
        paths    : [contentPath],
      ]
    }
    String cleaned = stripOptionalMarkdownFences(assistantXml)
    String outItem = ContentSubgraphAggregator.extractLikelySingleItemRootXml(cleaned)
    if (!outItem?.trim()) {
      String trimmed = cleaned.trim()
      if (trimmed.startsWith('<') && (trimmed.contains('<page') || trimmed.contains('<component'))) {
        outItem = trimmed
      }
    }
    if (!outItem?.trim()) {
      return [
        error           : true,
        action          : actionTag,
        message         :
          'Inner model did not return a full <page> or <component> item. Reply with only the transformed item root element (no wrappers).',
        assistantPreview: AiHttpProxy.elideForLog(cleaned, 4000),
        siteId          : siteId,
        contentPath     : contentPath,
      ]
    }
    String newKind = cqDetectItemRootKind(outItem)
    if (origKind && newKind && !origKind.equals(newKind)) {
      return [
        error           : true,
        action          : actionTag,
        message         :
          'Inner model changed root element from <' + origKind + '> to <' + newKind + '> — refusing to write.',
        assistantPreview: AiHttpProxy.elideForLog(outItem, 4000),
        siteId          : siteId,
        contentPath     : contentPath,
      ]
    }
    Map out = [
      action              : actionTag,
      siteId              : siteId,
      root                : contentPath,
      paths               : [contentPath],
      documentCount       : 1,
      writeResults        : writeResults,
      llmModel            : llmModel,
      truncatedFromWalk   : built?.truncated,
      maxDepthReachedWalk : built?.maxDepthReached,
    ]
    if (!writeResults) {
      out.transformedItemPreview = AiHttpProxy.elideForLog(outItem, 12_000)
      out.nextStep = 'Set writeResults:true to persist.'
      return out
    }
    if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
      return [
        error    : true,
        action   : actionTag,
        cancelled: true,
        message  : 'Request was stopped before Studio write.',
        siteId   : siteId,
        paths    : [contentPath],
      ]
    }
    Map w
    try {
      w = CmsWriteContent.write(ops, siteId, contentPath, outItem.trim(), unlock) as Map
    } catch (Throwable writeEx) {
      return [
        error      : true,
        action     : actionTag,
        message    : 'WriteContent failed: ' + (writeEx.message ?: writeEx.toString()),
        siteId     : siteId,
        contentPath: contentPath,
      ]
    }
    boolean ok = w?.ok != false
    out.ok = ok
    out.writtenCount = ok ? Integer.valueOf(1) : Integer.valueOf(0)
    out.declaredRoot = contentPath
    out.results =
      [[path: contentPath, ok: ok, message: (w?.message ?: w?.result ?: 'written').toString()]]
    out.nextStep =
      ok ? 'Verify with GetPreviewHtml when a preview URL is available.' : (w?.message ?: 'write failed').toString()
    return out
  }

  /**
   * Loads subgraph via {@link ContentSubgraphAggregator#build}, one non-streaming inner LLM completion (bundle + instructions only), optional {@link ContentSubgraphAggregator#apply}.
   * Inner completion model when {@code llmModel}/{@code model} omitted: {@link AiOrchestration#transformSubgraphDefaultInnerModel(String)} (smaller model in same family as main chat).
   * @param toolDiagKey prefix for {@link AiOrchestration#aiAssistantToolWorkerDiagPhase} and logs ({@code TransformContentSubgraph} vs {@code TranslateContentItem})
   * @param resultAction {@code action} field in returned maps
   */
  static Map runTransformContentSubgraph(
    StudioToolOperations ops,
    Map rawInput,
    String apiKey,
    String defaultChatModel,
    String normProtected,
    boolean pathProtect,
    String toolDiagKey = 'TransformContentSubgraph',
    String resultAction = 'transform_content_subgraph'
  ) {
    String diag = (toolDiagKey ?: 'TransformContentSubgraph').toString().trim() ?: 'TransformContentSubgraph'
    String actionTag = (resultAction ?: 'transform_content_subgraph').toString().trim() ?: 'transform_content_subgraph'
    def input = (rawInput != null) ? rawInput : [:]
    def siteId = ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim())
    if (!siteId) {
      throw new IllegalArgumentException('Missing required field: siteId')
    }
    def contentPath = input?.contentPath?.toString()?.trim() ?: input?.path?.toString()?.trim()
    if (!contentPath) {
      throw new IllegalArgumentException('Missing required field: contentPath (or path)')
    }
    def instructions = input?.instructions?.toString()?.trim()
    if (!instructions) {
      throw new IllegalArgumentException('Missing required field: instructions')
    }
    boolean writeResults = true
    if (input.containsKey('writeResults')) {
      def wr = input.writeResults
      if (wr instanceof Boolean) {
        writeResults = (Boolean) wr
      } else if (wr != null) {
        writeResults = !('false'.equalsIgnoreCase(wr.toString()) || '0' == wr.toString())
      }
    }
    String unlock = input?.unlock?.toString()?.trim() ?: 'true'
    Integer maxItems = null
    Integer maxDepth = null
    try {
      if (input?.maxItems != null) {
        maxItems =
          (input.maxItems instanceof Number) ? ((Number) input.maxItems).intValue() : Integer.parseInt(
            input.maxItems.toString().trim())
      }
    } catch (Throwable ignored) {
      maxItems = null
    }
    try {
      if (input?.maxDepth != null) {
        maxDepth =
          (input.maxDepth instanceof Number) ? ((Number) input.maxDepth).intValue() : Integer.parseInt(
            input.maxDepth.toString().trim())
      }
    } catch (Throwable ignored) {
      maxDepth = null
    }
    String explicitInnerLlm = input?.llmModel?.toString()?.trim() ?: input?.model?.toString()?.trim()
    String llmModel = explicitInnerLlm ?: AiOrchestration.transformSubgraphDefaultInnerModel(defaultChatModel)
    int innerMaxOutTokens =
      'TranslateContentItem'.equals(diag)
        ? AiOrchestration.resolveTranslateContentItemMaxOutTokens()
        : TRANSFORM_MAX_OUT_TOKENS
    if (!explicitInnerLlm) {
      log.debug(
        '{}: inner llmModel default {} (same-family inner completion; pass llmModel to override). Main chat model: {} innerMaxOutTokens={}',
        diag,
        llmModel,
        (defaultChatModel ?: '').trim() ?: '(unset)',
        innerMaxOutTokens
      )
    }
    int readTimeoutMs = 600_000
    try {
      def rtm = input?.readTimeoutMs
      if (rtm instanceof Number) {
        readTimeoutMs = Math.max(60_000, ((Number) rtm).intValue())
      } else if (rtm != null) {
        readTimeoutMs = Math.max(60_000, Integer.parseInt(rtm.toString().trim()))
      }
    } catch (Throwable ignored) {
      readTimeoutMs = 600_000
    }
    if (!apiKey?.trim()) {
      return [
        error  : true,
        action : actionTag,
        message: 'LLM API key not configured for this agent',
      ]
    }
    AiOrchestration.aiAssistantToolWorkerDiagPhase(
      "${diag}_ContentSubgraphAggregator_build site=${siteId} path=${contentPath}"
    )
    Map built = ContentSubgraphAggregator.build(ops, siteId, contentPath, maxItems, maxDepth) as Map
    if ('TranslateContentItem'.equals(diag)) {
      log.debug(
        '{} DIAG perRepositoryXmlInnerLlm path={} documentCountInBundle={} truncatedWalk={}',
        diag,
        contentPath,
        built?.documentCount,
        built?.truncated
      )
    }
    if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
      return [
        error    : true,
        action   : actionTag,
        cancelled: true,
        message  : 'Request was stopped after the subgraph walk; inner LLM and writes were not run.',
        siteId   : siteId,
        contentPath: contentPath,
      ]
    }
    String subgraphXml = built?.subgraphXml?.toString() ?: ''
    if (!subgraphXml.trim()) {
      throw new IllegalStateException('ContentSubgraphAggregator.build returned empty subgraphXml')
    }
    if (subgraphXml.length() > TRANSFORM_SUBGRAPH_MAX_CHARS) {
      AiOrchestration.aiAssistantToolWorkerDiagPhase(
        "${diag}_exit_bundle_too_large chars=${subgraphXml.length()}"
      )
      return [
        error        : true,
        action       : actionTag,
        message      :
          "Subgraph bundle is ${subgraphXml.length()} characters (limit ${TRANSFORM_SUBGRAPH_MAX_CHARS}). Narrow maxItems/maxDepth or use ListContentDependencyScope + per-path GetContent/WriteContent.",
        documentCount: built?.documentCount,
        paths        : built?.paths,
        truncated    : built?.truncated,
      ]
    }
    if ('TranslateContentItem'.equals(diag) && maxItems != null && maxItems.intValue() == 1) {
      Object dcObj = built?.documentCount
      int docCount = (dcObj instanceof Number) ? ((Number) dcObj).intValue() : 0
      if (docCount == 1) {
        return runTranslateContentItemRawInner(
          ops,
          built,
          input,
          siteId,
          contentPath,
          instructions,
          apiKey,
          llmModel,
          innerMaxOutTokens,
          readTimeoutMs,
          writeResults,
          unlock,
          normProtected,
          pathProtect,
          actionTag,
          diag
        )
      }
    }
    List<String> origPaths = (built?.paths instanceof List) ? new ArrayList<>((List) built.paths) : []
    LinkedHashSet<String> origSet = new LinkedHashSet<>()
    for (String op : origPaths) {
      def n = AuthoringPreviewContext.normalizeRepoPath(op)
      if (n) {
        origSet.add(n)
      }
    }
    String sys =
      'TranslateContentItem'.equals(diag)
        ? ToolPrompts.getTRANSLATE_CONTENT_ITEM_INNER_SYSTEM()
        : ToolPrompts.getTRANSFORM_CONTENT_SUBGRAPH_SYSTEM()
    String userBody = '## Instructions\n' + instructions + '\n\n## Content bundle (XML)\n' + subgraphXml
    if ('TranslateContentItem'.equals(diag)) {
      userBody = userBody + '\n' + ToolPrompts.getTRANSLATE_CONTENT_ITEM_INNER_USER_APPENDIX()
      String pathLock = (contentPath ?: '').toString().trim()
      if (pathLock) {
        userBody =
          userBody +
          '\n## Path lock (server)\n' +
          '- The output `<document path="...">` must use **exactly** this path string: `' +
          pathLock +
          '` (same characters as this single-item request).\n'
      }
    }
    logTransformContentSubgraphPayload(
      input,
      siteId,
      contentPath,
      instructions,
      writeResults,
      unlock,
      maxItems,
      maxDepth,
      llmModel,
      readTimeoutMs,
      built,
      subgraphXml,
      sys,
      userBody
    )
    if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
      return [
        error    : true,
        action   : actionTag,
        cancelled: true,
        message  : 'Request was stopped before the bundled inner LLM call (no inner completion or writes for this transform).',
        siteId   : siteId,
        paths    : built?.paths,
      ]
    }
    AiOrchestration.aiAssistantToolWorkerDiagPhase(
      "${diag}_await_inner_openai_completion model=${llmModel} bundleChars=${subgraphXml.length()}"
    )
    long tOpenAi = System.nanoTime()
    String assistantXml = AiOrchestration.toolsLoopSimpleCompletionAssistantText(
      apiKey,
      llmModel,
      sys,
      userBody,
      innerMaxOutTokens,
      readTimeoutMs,
      diag
    )
    long ms = (System.nanoTime() - tOpenAi) / 1_000_000L
    log.debug(
      '{} DIAG innerSimpleCompletion wallMs={} assistantXmlChars={} maxOutTokens={}',
      diag,
      ms,
      (assistantXml ?: '').length(),
      innerMaxOutTokens
    )
    if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
      return [
        error    : true,
        action   : actionTag,
        cancelled: true,
        message  : 'Request was stopped after the inner LLM returned; bundle was not validated or written.',
        siteId   : siteId,
        paths    : origPaths,
      ]
    }
    AiOrchestration.aiAssistantToolWorkerDiagPhase(
      "${diag}_parsing_validating_bundle assistantXmlChars=${(assistantXml ?: '').length()}"
    )
    String cleaned = stripOptionalMarkdownFences(assistantXml)
    List<Map> newDocs = ContentSubgraphAggregator.parseDocumentsForTests(cleaned)
    if ('TranslateContentItem'.equals(diag) && origPaths.size() == 1) {
      String canonicalPath = origPaths.get(0)?.toString()?.trim()
      if (canonicalPath) {
        int strictDocCountBefore = newDocs.size()
        String coerced =
          ContentSubgraphAggregator.coerceAssistantBundleToSingleExpectedPath(cleaned, canonicalPath)
        if (coerced != null) {
          if (strictDocCountBefore != 1) {
            log.warn(
              '{} DIAG coerced inner assistant bundle to strict single-document shape path={} (strictParsedDocsBefore={})',
              diag,
              canonicalPath,
              strictDocCountBefore
            )
          }
          cleaned = coerced
          newDocs = ContentSubgraphAggregator.parseDocumentsForTests(cleaned)
        }
      }
    }
    LinkedHashSet<String> newSet = new LinkedHashSet<>()
    for (Map d : newDocs) {
      String pth = AuthoringPreviewContext.normalizeRepoPath(d?.path?.toString())
      if (pth) {
        newSet.add(pth)
      }
    }
    if (!origSet.equals(newSet)) {
      AiOrchestration.aiAssistantToolWorkerDiagPhase("${diag}_exit_path_mismatch")
      return [
        error           : true,
        action          : actionTag,
        message         :
          'LLM output `<document path="...">` set does not match the input bundle (need the **same** path string(s) as input, **one** `<document>` each, non-empty CDATA). Common causes: markdown fences, extra/missing `<document>` blocks, or renamed `path=` attributes. **Copy `path=` and `content-type=` from the input `<document>` tags exactly**; return only the `<aiassistant-content-subgraph>` XML tree.',
        expectedPaths   : new ArrayList<>(origSet),
        returnedPaths : new ArrayList<>(newSet),
        assistantPreview: AiHttpProxy.elideForLog(cleaned, 4000),
      ]
    }
    for (String p : origPaths) {
      String normP = AuthoringPreviewContext.normalizeRepoPath(p)
      String bodyCheck = ''
      for (Map d : newDocs) {
        String dp = AuthoringPreviewContext.normalizeRepoPath(d?.path?.toString())
        if (normP && normP == dp) {
          bodyCheck = d?.body?.toString() ?: ''
          break
        }
      }
      if (!bodyCheck.trim()) {
        AiOrchestration.aiAssistantToolWorkerDiagPhase("${diag}_exit_empty_body")
        return [
          error           : true,
          action          : actionTag,
          message         :
            'Empty or missing CDATA inner XML for path ' +
            (p ?: '') +
            '. Return exactly one <document> with the same path= as input and non-empty CDATA containing the full <page> or <component> item (no markdown fences).',
          assistantPreview: AiHttpProxy.elideForLog(cleaned, 4000),
        ]
      }
    }
    Map out = [
      action              : actionTag,
      siteId              : siteId,
      root                : built?.root,
      paths               : origPaths,
      documentCount       : origPaths.size(),
      writeResults        : writeResults,
      llmModel            : llmModel,
      truncatedFromWalk   : built?.truncated,
      maxDepthReachedWalk : built?.maxDepthReached,
    ]
    if (!writeResults) {
      AiOrchestration.aiAssistantToolWorkerDiagPhase("${diag}_preview_only_no_apply")
      out.transformedSubgraphPreview = AiHttpProxy.elideForLog(cleaned, 12_000)
      out.nextStep = 'Set writeResults:true to persist, or use WriteContent per path from the preview bundle.'
      return out
    }
    if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
      return [
        error    : true,
        action   : actionTag,
        cancelled: true,
        message  : 'Request was stopped before Studio writes; validated LLM output was not applied to the repository.',
        siteId   : siteId,
        paths    : origPaths,
      ]
    }
    AiOrchestration.aiAssistantToolWorkerDiagPhase(
      "${diag}_apply_writes_running paths=${origPaths.size()}"
    )
    long tApply = System.nanoTime()
    Map applyRes = ContentSubgraphAggregator.apply(ops, siteId, cleaned, unlock, normProtected, pathProtect) as Map
    long applyMs = (System.nanoTime() - tApply) / 1_000_000L
    log.debug(
      '{} DIAG applyWrites wallMs={} writtenCountApprox={} applyOk={}',
      diag,
      applyMs,
      applyRes?.writtenCount,
      applyRes?.ok
    )
    AiOrchestration.aiAssistantToolWorkerDiagPhase("${diag}_apply_writes_done")
    out.putAll(applyRes)
    out.action = actionTag
    return out
  }

  /**
   * Normalizes translate-batch maps into deduped `/site/...` lists.
   * Honors aliases such as paths/contentPaths/items.
   * Prepares AiOrchestration translate workers.
   */
  private static List<String> collectTranslateBatchPaths(Map input) {
    LinkedHashSet<String> ordered = new LinkedHashSet<>()
    if (input == null) {
      return new ArrayList<>()
    }
    Closure addOne = { Object o ->
      if (o == null) {
        return
      }
      String p = o.toString().trim()
      if (!p) {
        return
      }
      if (!ContentSubgraphAggregator.isSafeSiteContentPath(p)) {
        return
      }
      String n = AuthoringPreviewContext.normalizeRepoPath(p)
      if (n) {
        ordered.add(n)
      }
    }
    if (input.paths instanceof List) {
      for (Object o : (List) input.paths) {
        addOne.call(o)
      }
    }
    if (input.contentPaths instanceof List) {
      for (Object o : (List) input.contentPaths) {
        addOne.call(o)
      }
    }
    if (input.pathChunks instanceof List) {
      for (Object chunk : (List) input.pathChunks) {
        if (chunk instanceof List) {
          for (Object o : (List) chunk) {
            addOne.call(o)
          }
        } else {
          addOne.call(chunk)
        }
      }
    }
    return new ArrayList<>(ordered)
  }

  /**
   * Reads tool/project/JVM knobs governing parallel translate rows.
   * Bounds values between safe minimums and ceilings.
   * Protects Studio CPUs during TranslateContentBatch storms.
   */
  private static int resolveTranslateBatchMaxConcurrency(Map input, StudioToolOperations ops) {
    int d = ops != null ? ops.resolveTranslateBatchDefaultMaxConcurrency() : 25
    try {
      if (input?.maxConcurrency != null) {
        d =
          (input.maxConcurrency instanceof Number)
            ? ((Number) input.maxConcurrency).intValue()
            : Integer.parseInt(input.maxConcurrency.toString().trim())
      }
    } catch (Throwable ignored) {
    }
    return Math.max(1, Math.min(64, d))
  }

  /**
   * Reads per-row Maps for cancellation markers set by SSE Stop handlers.
   * Treats missing keys as active rows.
   * Lets inner completions skip redundant writes quickly.
   */
  private static boolean translateBatchRowCancelled(Map row) {
    return row instanceof Map && Boolean.TRUE.equals(((Map) row).cancelled)
  }

  /**
   * First-pass failures and most warning-shaped outcomes get <strong>one</strong> automatic server retry
   * (see {@link #runTranslateContentBatchParallel}); cancelled rows are not retried.
   */
  private static boolean translateBatchRowNeedsServerRetry(Map row) {
    if (!(row instanceof Map)) {
      return true
    }
    if (translateBatchRowCancelled((Map) row)) {
      return false
    }
    Map m = (Map) row
    if (Boolean.TRUE.equals(m.error)) {
      return true
    }
    return isToolResultWarning(m)
  }

  /**
   * Formats human-readable failure codes from translate-batch rows.
   * Suppresses noisy stack traces for authors.
   * Feeds aggregated summaries returned to orchestration.
   */
  private static String translateBatchRowReason(Map row) {
    if (!(row instanceof Map)) {
      return 'non-map result'
    }
    Map m = (Map) row
    String s = (m.message ?: m.hint ?: m.skippedReason ?: '')?.toString()?.trim() ?: 'unknown'
    return s.length() > 600 ? s.substring(0, 597) + '…' : s
  }

  /**
   * Shrinks verbose attempt structs into telemetry-friendly Maps.
   * Keeps only timings/status/error summaries.
   * Avoids leaking huge prompts back through SSE metadata.
   */
  private static Map translateBatchCompactAttempt(Map row) {
    if (!(row instanceof Map)) {
      return [message: 'non-map']
    }
    Map m = (Map) row
    Map c = new LinkedHashMap<>()
    c.error = Boolean.TRUE.equals(m.error)
    c.cancelled = Boolean.TRUE.equals(m.cancelled)
    c.message = translateBatchRowReason(m)
    if (m.action) {
      c.action = m.action
    }
    if (m.skippedReason) {
      c.skippedReason = m.skippedReason.toString()
    }
    return c
  }

  /**
   * One translate cell (inner {@link #runTransformContentSubgraph} for a single path); used for first pass and server retry pass.
   */
  private static Map runTranslateBatchSinglePathCell(
    StudioToolOperations ops,
    Map inputBase,
    String siteId,
    String pathFinal,
    String instructions,
    String apiKey,
    String defaultChatModel,
    String normProtected,
    boolean pathProtect,
    Semaphore translateGate,
    Closure toolProgressListener
  ) {
    translateGate.acquire()
    try {
      long t0 = System.nanoTime()
      if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
        Map cancelled = [
          path     : pathFinal,
          cancelled: true,
          message  : 'Request was stopped before this path ran.',
        ]
        if (toolProgressListener) {
          try {
            toolProgressListener.call(
              'TranslateContentItem',
              'warn',
              [siteId: siteId, contentPath: pathFinal, path: pathFinal],
              null,
              cancelled,
              (System.nanoTime() - t0) / 1_000_000L
            )
          } catch (Throwable ignored) {}
        }
        return cancelled
      }
      Map single = new LinkedHashMap<>(inputBase)
      single.put('siteId', siteId)
      single.put('maxItems', Integer.valueOf(1))
      single.put('maxDepth', Integer.valueOf(0))
      single.put('contentPath', pathFinal)
      single.put('path', pathFinal)
      single.put('instructions', instructions)
      Map result
      try {
        result =
          runTransformContentSubgraph(
            ops,
            single,
            apiKey,
            defaultChatModel,
            normProtected,
            pathProtect,
            'TranslateContentItem',
            'translate_content_item'
          )
      } catch (Throwable t) {
        result = [
          path : pathFinal,
          error: true,
          message: (t.message ?: t.toString()),
        ]
      }
      long elapsedMs = (System.nanoTime() - t0) / 1_000_000L
      if (toolProgressListener) {
        try {
          boolean warn =
            result instanceof Map &&
              (Boolean.TRUE.equals(((Map) result).error) ||
                Boolean.TRUE.equals(((Map) result).cancelled) ||
                isToolResultWarning(result))
          toolProgressListener.call(
            'TranslateContentItem',
            warn ? 'warn' : 'done',
            [siteId: siteId, contentPath: pathFinal, path: pathFinal],
            null,
            result,
            elapsedMs
          )
        } catch (Throwable ignored) {}
      }
      return (Map) result
    } finally {
      translateGate.release()
    }
  }

  /**
   * Runs {@link #runTransformContentSubgraph} with {@code maxItems=1} per path on a fixed pool — same instructions,
   * parallel inner LLM completions + writes (bounded concurrency). Emits {@code TranslateContentItem} progress per path when a listener is set.
   */
  static Map runTranslateContentBatchParallel(
    StudioToolOperations ops,
    Map rawInput,
    String apiKey,
    String defaultChatModel,
    String normProtected,
    boolean pathProtect,
    Closure toolProgressListener = null
  ) {
    Map input = (rawInput != null) ? new LinkedHashMap<>((Map) rawInput) : new LinkedHashMap<>()
    String siteId = ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim())
    if (!siteId) {
      throw new IllegalArgumentException('Missing required field: siteId')
    }
    String instructions = input?.instructions?.toString()?.trim()
    if (!instructions) {
      throw new IllegalArgumentException('Missing required field: instructions')
    }
    if (!apiKey?.trim()) {
      return [
        error  : true,
        action : 'translate_content_batch',
        message: 'LLM API key not configured for this agent',
      ]
    }
    List<String> paths = collectTranslateBatchPaths(input)
    if (paths.isEmpty()) {
      throw new IllegalArgumentException(
        'Provide non-empty paths: paths or contentPaths (array of /site/.../*.xml), or pathChunks from ListContentDependencyScope'
      )
    }
    if (paths.size() > TRANSLATE_BATCH_MAX_PATHS) {
      return [
        error       : true,
        action      : 'translate_content_batch',
        message     : "At most ${TRANSLATE_BATCH_MAX_PATHS} paths per batch (got ${paths.size()}). Split into multiple batch calls.",
        pathsRequested: paths.size(),
      ]
    }
    int concurrency = resolveTranslateBatchMaxConcurrency(input, ops)
    input.remove('paths')
    input.remove('contentPaths')
    input.remove('pathChunks')
    input.remove('maxConcurrency')

    /** Per-batch cap on concurrent inner LLM+repo work; threads come from {@link ParallelToolExecutor}. */
    Semaphore translateGate = new Semaphore(concurrency)
    List<Future<Map>> trackedFutures = new ArrayList<>()
    try {
      if (toolProgressListener) {
        try {
          StringBuilder sb = new StringBuilder()
          sb.append('**TranslateContentBatch** — **')
            .append(paths.size())
            .append('** content item(s) will be translated ')
          sb.append('(up to **')
            .append(concurrency)
            .append('** inner LLM runs **in parallel**). ')
          sb.append('Paths **listed below** are all handed to the worker pool next; watch for a **TranslateContentItem** line as **each** finishes:\n')
          int idx = 0
          for (String p : paths) {
            idx++
            String shown = p != null ? p.toString() : ''
            if (shown.length() > 220) {
              shown = shown.substring(0, 217) + '…'
            }
            sb.append('[').append(idx).append('] ').append(shown).append('\n')
          }
          toolProgressListener.call(
            'TranslateContentBatch',
            'progress',
            [siteId: siteId, progressMessage: sb.toString(), path: ''],
            null,
            null,
            null
          )
        } catch (Throwable ignored) {}
      }

      for (String path : paths) {
        final String pathFinal = path
        trackedFutures.add(
          ParallelToolExecutor.submit({
            return runTranslateBatchSinglePathCell(
              ops,
              input,
              siteId,
              pathFinal,
              instructions,
              apiKey,
              defaultChatModel,
              normProtected,
              pathProtect,
              translateGate,
              toolProgressListener
            )
          } as Callable<Map>))
      }

      int firstBatchFutureEnd = trackedFutures.size()
      List<Map> firstPass = new ArrayList<>(paths.size())
      for (int fi = 0; fi < firstBatchFutureEnd; fi++) {
        Future<Map> f = trackedFutures.get(fi)
        String pathHint = paths.size() > fi ? paths.get(fi)?.toString() : ''
        Map row
        try {
          row = coerceTranslateBatchFutureRow(f.get(), pathHint)
        } catch (Throwable t) {
          row = [path: pathHint, error: true, message: (t.message ?: t.toString())]
        }
        firstPass.add(row)
      }

      List<Map> initialFailureDetails = new ArrayList<>()
      for (int pi = 0; pi < paths.size(); pi++) {
        String p = paths.get(pi)
        Map r = firstPass.get(pi)
        if (translateBatchRowNeedsServerRetry(r)) {
          initialFailureDetails.add([
            path : p,
            index: pi + 1,
            reason: translateBatchRowReason(r),
          ])
        }
      }

      Map pathToSecond = new LinkedHashMap()
      int retryRecovered = 0
      int retryStillBad = 0
      if (!initialFailureDetails.isEmpty()) {
        log.debug(
          'TranslateContentBatch: first pass done pathCount={} serverRetryCandidates={}',
          paths.size(),
          initialFailureDetails.size()
        )
        if (toolProgressListener) {
          try {
            StringBuilder rb = new StringBuilder()
            rb.append('**TranslateContentBatch** — **server retry (one pass only):** ')
              .append(initialFailureDetails.size())
              .append(
                ' path(s) failed or warned on the first attempt; re-running each **once** with the same instructions. **Do not** call TranslateContentBatch again for the same paths expecting more automatic retries—use TranslateContentItem or GetContent/WriteContent per remaining failure.\n'
              )
            for (Map d : initialFailureDetails) {
              rb.append('- `').append(d.path).append('` — ').append(d.reason).append('\n')
            }
            toolProgressListener.call(
              'TranslateContentBatch',
              'progress',
              [siteId: siteId, progressMessage: rb.toString(), path: ''],
              null,
              null,
              null
            )
          } catch (Throwable ignored) {}
        }
        List<Future<Map>> retryFutures = new ArrayList<>(initialFailureDetails.size())
        for (Map d : initialFailureDetails) {
          final String rp = d.path?.toString()?.trim()
          Future<Map> rf =
            ParallelToolExecutor.submit({
              return runTranslateBatchSinglePathCell(
                ops,
                input,
                siteId,
                rp,
                instructions,
                apiKey,
                defaultChatModel,
                normProtected,
                pathProtect,
                translateGate,
                toolProgressListener
              )
            } as Callable<Map>)
          retryFutures.add(rf)
          trackedFutures.add(rf)
        }
        for (int ri = 0; ri < retryFutures.size(); ri++) {
          Map det = initialFailureDetails.get(ri)
          String detPath = det.path?.toString()
          String pKey = AuthoringPreviewContext.normalizeRepoPath(detPath)
          Map rr
          try {
            rr = coerceTranslateBatchFutureRow(retryFutures.get(ri).get(), detPath)
          } catch (Throwable t) {
            rr = [
              path : det.path,
              error: true,
              message: (t.message ?: t.toString()),
            ]
          }
          if (pKey) {
            pathToSecond.put(pKey, rr)
          }
          if (!translateBatchRowNeedsServerRetry(rr) && !translateBatchRowCancelled(rr)) {
            retryRecovered++
          } else if (!translateBatchRowCancelled(rr)) {
            retryStillBad++
          }
        }
      }

      List<Map> results = new ArrayList<>(paths.size())
      for (int pi = 0; pi < paths.size(); pi++) {
        String p = paths.get(pi)
        Map r1 = coerceTranslateBatchFutureRow(firstPass.get(pi), p)
        String pn = AuthoringPreviewContext.normalizeRepoPath(p)
        if (pn && pathToSecond.containsKey(pn)) {
          Map r2 = pathToSecond.get(pn)
          Map merged = new LinkedHashMap<>(r2 instanceof Map ? r2 : [error: true, message: 'missing retry result'])
          merged.put('firstPass', translateBatchCompactAttempt(r1))
          merged.put('serverRetriedOnce', true)
          boolean recovered =
            !translateBatchRowNeedsServerRetry(merged) && !translateBatchRowCancelled(merged)
          merged.put('recoveredOnServerRetry', recovered)
          if (!recovered && !translateBatchRowCancelled(merged)) {
            merged.put(
              'guidanceAfterFailedRetry',
              'This path still failed after one automatic server retry. Do not call TranslateContentBatch again for the same path set. Use TranslateContentItem or GetContent/WriteContent on this path only, adjust instructions, or skip.'
            )
          }
          results.add(merged)
        } else {
          results.add(r1)
        }
      }

      int ok = 0
      int err = 0
      int cancelled = 0
      for (Map row : results) {
        if (row instanceof Map) {
          if (Boolean.TRUE.equals(row.cancelled)) {
            cancelled++
          } else if (Boolean.TRUE.equals(row.error) || isToolResultWarning(row)) {
            err++
          } else {
            ok++
          }
        }
      }

      boolean allOk = err == 0 && cancelled == 0
      StringBuilder summary = new StringBuilder()
      if (allOk) {
        summary.append("Batch finished: ${ok} path(s) OK.")
        if (!initialFailureDetails.isEmpty()) {
          summary
            .append(' Server retry pass: ')
            .append(retryRecovered)
            .append(' recovered, ')
            .append(retryStillBad)
            .append(' still had issues (see per-path results).')
        }
      } else {
        summary
          .append("Batch finished with issues: ok=${ok}, errors/warnings=${err}, cancelled=${cancelled}.")
        if (!initialFailureDetails.isEmpty()) {
          summary
            .append(' After **one** automatic server retry: ')
            .append(retryRecovered)
            .append(' recovered, ')
            .append(retryStillBad)
            .append(' still failing (per-path `firstPass` / `guidanceAfterFailedRetry`). **Stop** re-calling TranslateContentBatch for the same paths.')
        }
      }

      Map out = new LinkedHashMap<>()
      out.action = 'translate_content_batch'
      out.siteId = siteId
      out.paths = paths
      out.pathCount = paths.size()
      out.maxConcurrency = concurrency
      out.results = results
      out.okCount = ok
      out.errorOrWarnCount = err
      out.cancelledCount = cancelled
      out.ok = allOk
      out.message = summary.toString()
      out.initialFailures = initialFailureDetails
      out.serverRetryAttempted = !initialFailureDetails.isEmpty()
      out.serverRetryRecoveredCount = retryRecovered
      out.serverRetryStillFailingCount = retryStillBad
      return out
    } finally {
      for (Future<Map> f : trackedFutures) {
        if (f != null && !f.isDone()) {
          try {
            f.cancel(true)
          } catch (Throwable ignored) {}
        }
      }
    }
  }

  /** Invoked from {@link plugins.org.craftercms.aiassistant.studio.spi.tool.AbstractStudioAiTool} SPI callbacks. */
  static void logToolInvocationPublic(String toolName, Map input) {
    logToolInvocation(toolName, input)
  }

  /**
   * Debug-traces tool names plus scrubbed JSON arguments.
   * Rate-limited semantics delegated to SLF4J logger.
   * Supports ops investigations without println noise.
   */
  private static void logToolInvocation(String toolName, Map input) {
    try {
      def j = JsonOutput.toJson(input ?: [:])
      log.debug("TOOL INVOKED: {} argsChars={} argsPreview=\n{}", toolName, j.length(), AiHttpProxy.elideForLog(j, 4000))
    } catch (Throwable t) {
      log.debug("TOOL INVOKED: {} (args not serializable: {})", toolName, t.toString())
    }
  }

  /**
   * True when a tool returned without throwing but the payload indicates failure, skip, or partial result (⚠️ in UI).
   */
  static boolean isToolResultWarning(Object result) {
    if (!(result instanceof Map)) {
      return false
    }
    def m = (Map) result
    if (Boolean.TRUE.equals(m.error) || 'true'.equalsIgnoreCase(m.error?.toString())) {
      return true
    }
    if (m.skippedReason) {
      return true
    }
    if (m.containsKey('ok')) {
      def ok = m.ok
      if (ok instanceof Boolean && !((Boolean) ok)) {
        return true
      }
      if (ok != null && 'false'.equalsIgnoreCase(ok.toString())) {
        return true
      }
    }
    return false
  }

  /**
   * Wraps tool execution with optional progress callbacks for SSE UIs.
   * {@code listener} signature: {@code (toolName, phase, inputMap, errorOrNull, toolResultOrNull)} —
   * {@code phase} is {@code start}, {@code done}, {@code warn}, or {@code error}; {@code toolResultOrNull} is set for {@code done}/{@code warn}.
   */
  static Map runWithToolProgress(String toolName, Map rawInput, Closure listener, Closure work) {
    return StudioAiToolProgress.runWithToolProgress(toolName, rawInput, listener, work)
  }

  /**
   * Same as {@link #build} but supplies the standard wire converter closure that delegates to
   * {@link AiOrchestration#toolResultToWireString}. Use from Groovy that must not reference Spring AI's
   * {@code ToolCallResultConverter} type on the Studio script compile classpath (e.g. {@code AutonomousAssistantWorker}).
   */
  static List buildWithDefaultWireConverter(
    StudioToolOperations ops,
    Closure toolProgressListener = null,
    String apiKeyForImages = null,
    String imageModel = null,
    boolean fullSuppressRepoWrites = false,
    String protectedFormItemPath = null,
    List<Map> expertSkillSpecs = null,
    String textModel = null,
    String llmNormalized = null,
    String imageGeneratorParam = null,
    Collection agentEnabledBuiltInTools = null
  ) {
    def converter =
      { Object result, java.lang.reflect.Type rt -> AiOrchestration.toolResultToWireString(result, rt) }
    return build(
      converter,
      ops,
      toolProgressListener,
      apiKeyForImages,
      imageModel,
      fullSuppressRepoWrites,
      protectedFormItemPath,
      expertSkillSpecs,
      textModel,
      llmNormalized,
      imageGeneratorParam,
      agentEnabledBuiltInTools
    )
  }

  /**
   * @param converter Spring AI tool result converter or Groovy closure {@code (Object result, Type returnType) -> String}; passed via {@code invokeMethod} so site Groovy compiles without {@code ToolCallResultConverter} on the script classpath
   * @param ops Studio tool operations
   * @param toolProgressListener optional progress callback for streaming chat (see {@link #runWithToolProgress})
   * @param apiKeyForImages API key for the built-in **image** HTTP wire and for embedding/RAG inner calls when applicable (see {@link StudioAiImageGeneratorFactory})
   * @param imageModel resolved default image model from agent/request for the built-in images wire (e.g. gpt-image-1); optional per-call {@code model} in tool args; ignored for pure {@code script:…} image backends unless the script reads it from context
   * @param fullSuppressRepoWrites when true (form engine + client JSON apply but no item path), omit write/publish/revert tools entirely
   * @param protectedFormItemPath normalized repo path of the open form item — when set (and not full suppress), write/publish/revert stay registered but are rejected only for this path; {@code update_content} for this path steers toward {@code aiassistantFormFieldUpdates}
   * @param expertSkillSpecs normalized maps {@code skillId},{@code name},{@code url},{@code description} from the chat request; when non-empty and an API key is present, {@link StudioAiToolContext} carries embedding state so {@link plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.general.QueryExpertGuidanceTool} may register via {@link StudioAiToolRegistry}
   * @param textModel resolved chat model id for inner completions ({@code TranslateContentItem} / bulk subgraph when enabled) default {@code llmModel}; ignored when no API key
   * @param llmNormalized {@link plugins.org.craftercms.aiassistant.studio.spi.llm.StudioAiLlmKind#normalize} result for the active session (image wire defaults)
   * @param imageGeneratorParam optional {@code wire} (default when blank), {@code none}|{@code off}|{@code disabled}, or {@code script:id} — see site docs
   * <p>Delegates tool registration to {@link StudioAiToolRegistry#buildCoreToolCallbacks} and {@link StudioAiToolRegistry#buildMcpToolCallbacks};
   * each built-in's {@code enabled(StudioAiToolContext)} gate controls presence on the wire (API key, image backend, expert skills, site user-tools registry, etc.).</p>
   * <p>Built-in tool visibility may be constrained by site {@code /scripts/aiassistant/config/tools.json} — see {@link StudioAiAssistantProjectConfig}.
   * Optional <strong>MCP</strong> servers register additional {@code mcp_*} tools when {@code mcpEnabled} is JSON {@code true} in the same file — see {@link StudioAiAssistantProjectConfig#mcpClientEnabled} and {@link plugins.org.craftercms.aiassistant.studio.contrib.tool.mcp.StudioAiMcpClient}.</p>
   */
  static List build(
    Object converter,
    StudioToolOperations ops,
    Closure toolProgressListener = null,
    String apiKeyForImages = null,
    String imageModel = null,
    boolean fullSuppressRepoWrites = false,
    String protectedFormItemPath = null,
    List<Map> expertSkillSpecs = null,
    String textModel = null,
    String llmNormalized = null,
    String imageGeneratorParam = null,
    Collection agentEnabledBuiltInTools = null
  ) {
    def ctx = StudioAiToolContext.fromBuildParams(
      converter,
      ops,
      toolProgressListener,
      apiKeyForImages,
      imageModel,
      fullSuppressRepoWrites,
      protectedFormItemPath,
      expertSkillSpecs,
      textModel,
      llmNormalized,
      imageGeneratorParam,
      agentEnabledBuiltInTools
    )
    def tools = StudioAiToolRegistry.buildCoreToolCallbacks(ctx) as ArrayList
    tools.addAll(StudioAiToolRegistry.buildMcpToolCallbacks(ctx, ops))
    applyToolCatalogFilters(tools, ctx.aiProjectToolCfg)
    applyAgentEnabledBuiltInToolsSubset(tools, agentEnabledBuiltInTools)
    return tools
  }

  /**
   * After site {@code tools.json} policy, optionally restrict to an agent/request whitelist of wire tool names.
   * Include {@code mcp:*} to retain every dynamic {@code mcp_*} tool still present.
   */
  private static void applyAgentEnabledBuiltInToolsSubset(List tools, Collection agentSubset) {
    if (tools == null || tools.isEmpty()) {
      return
    }
    if (!(agentSubset instanceof Collection) || ((Collection) agentSubset).isEmpty()) {
      return
    }
    Set<String> keep = new LinkedHashSet<>()
    boolean mcpAll = false
    for (Object o : (Collection) agentSubset) {
      if (o == null) {
        continue
      }
      String n = o.toString().trim()
      if (!n) {
        continue
      }
      if ('mcp:*'.equals(n)) {
        mcpAll = true
      } else {
        keep.add(n)
      }
    }
    if (keep.isEmpty() && !mcpAll) {
      return
    }
    for (Iterator it = tools.iterator(); it.hasNext();) {
      Object t = it.next()
      if (!(t instanceof FunctionToolCallback)) {
        continue
      }
      String n = ((FunctionToolCallback) t).getToolDefinition().name()
      boolean allow = keep.contains(n)
      if (!allow && 'ListContentDependencyScope'.equals(n) && keep.contains('ListContentTranslationScope')) {
        allow = true
      }
      if (!allow && mcpAll && n != null && n.startsWith('mcp_')) {
        allow = true
      }
      if (!allow) {
        it.remove()
      }
    }
  }

  /**
   * {@code InvokeSiteUserTool} and {@code mcp_*} tools are kept when {@code enabledBuiltInTools} is a whitelist.
   */
  private static boolean isExtensionCatalogToolName(String n) {
    if (n == null) {
      return false
    }
    if ('InvokeSiteUserTool'.equals(n)) {
      return true
    }
    return n.startsWith('mcp_')
  }

  /**
   * {@code enabledBuiltInTools} may still list {@code ListContentTranslationScope} from before the wire rename.
   */
  private static boolean builtInWhitelistAllows(String wireName, Set<String> wl) {
    if (wl.contains(wireName)) {
      return true
    }
    return 'ListContentDependencyScope'.equals(wireName) && wl.contains('ListContentTranslationScope')
  }

  /**
   * Applies {@link StudioAiAssistantProjectConfig} whitelist/blacklist to the tool catalog.
   * When {@code enabledBuiltInTools} is set, it filters <strong>built-in</strong> built-in tool names only;
   * {@code InvokeSiteUserTool} and {@code mcp_*} tools are always retained unless listed in {@code disabledBuiltInTools}.
   */
  private static void applyToolCatalogFilters(List tools, Map projectCfg) {
    if (tools == null || tools.isEmpty()) {
      return
    }
    if (!(projectCfg instanceof Map)) {
      return
    }
    Set<String> wl = StudioAiAssistantProjectConfig.enabledBuiltInWhitelist(projectCfg)
    Set<String> bl = StudioAiAssistantProjectConfig.disabledBuiltInSet(projectCfg)
    if (wl == null && (bl == null || bl.isEmpty())) {
      return
    }
    for (Iterator it = tools.iterator(); it.hasNext();) {
      Object t = it.next()
      if (!(t instanceof FunctionToolCallback)) {
        continue
      }
      String n = ((FunctionToolCallback) t).getToolDefinition().name()
      if (StudioAiAssistantProjectConfig.isToolNameDisabled(n, bl)) {
        it.remove()
        continue
      }
      if (wl != null) {
        if (isExtensionCatalogToolName(n)) {
          continue
        }
        if (!builtInWhitelistAllows(n, wl)) {
          it.remove()
        }
      }
    }
  }

  /**
   * Wire names that would be registered for a tools-loop session on this site (for plan-defer planner context).
   * Derived from {@link #build(StudioToolOperations)} with default session options — not a hardcoded supplemental list.
   * Does not open MCP sessions (dynamic {@code mcp_*} tools are documented separately in the catalog markdown).
   *
   * @param ops Studio tool operations (site sandbox)
   * @param projectCfg site {@code tools.json} policy map (loaded when null)
   */
  /** Wire names from the live tools-loop session bundle when present. */
  private static List<String> wireNamesFromSessionCallbacks(Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return []
    }
    Object tools = toolsLoopSessionBundle.get('tools')
    if (!(tools instanceof List)) {
      return []
    }
    LinkedHashSet<String> names = new LinkedHashSet<>()
    for (def t : (List) tools) {
      if (t instanceof FunctionToolCallback) {
        String n = ((FunctionToolCallback) t).getToolDefinition()?.name()?.trim()
        if (n) {
          names.add(n)
        }
      }
    }
    return new ArrayList<>(names)
  }

  /**
   * Wire names for plan-defer planner context. Prefers the live session tool list when {@code toolsLoopSessionBundle}
   * exposes {@code tools}; otherwise builds from site policy via {@link #build(StudioToolOperations)}.
   */
  static List<String> wireNamesForPlanDeferCatalog(
    StudioToolOperations ops,
    Map projectCfg,
    Map toolsLoopSessionBundle = null
  ) {
    Map cfg = projectCfg instanceof Map ? projectCfg : StudioAiAssistantProjectConfig.load(ops)
    List<String> sessionNames = wireNamesFromSessionCallbacks(toolsLoopSessionBundle)
    if (sessionNames) {
      return filterPlanDeferWireNames(sessionNames, cfg)
    }
    List tools = build(
      { Object result, java.lang.reflect.Type rt -> result },
      ops,
      null,
      null,
      null,
      false,
      null,
      [],
      null,
      null,
      null,
      null
    )
    LinkedHashSet<String> names = new LinkedHashSet<>()
    if (tools) {
      for (def t : tools) {
        if (t instanceof FunctionToolCallback) {
          String n = ((FunctionToolCallback) t).getToolDefinition()?.name()
          if (n?.trim()) {
            names.add(n.trim())
          }
        }
      }
    }
    return filterPlanDeferWireNames(new ArrayList<>(names), cfg)
  }

  /** Applies site built-in whitelist / blacklist to the plan-defer wire name list. */
  private static List<String> filterPlanDeferWireNames(List<String> names, Map projectCfg) {
    if (names == null || names.isEmpty()) {
      return names ?: []
    }
    if (!(projectCfg instanceof Map)) {
      return names
    }
    Set<String> wl = StudioAiAssistantProjectConfig.enabledBuiltInWhitelist(projectCfg)
    Set<String> bl = StudioAiAssistantProjectConfig.disabledBuiltInSet(projectCfg)
    if (wl == null && (bl == null || bl.isEmpty())) {
      return names
    }
    List<String> out = []
    for (String n : names) {
      if (!n?.trim()) {
        continue
      }
      String wire = n.trim()
      if (StudioAiAssistantProjectConfig.isToolNameDisabled(wire, bl)) {
        continue
      }
      if (wl != null) {
        if (isExtensionCatalogToolName(wire)) {
          out.add(wire)
          continue
        }
        if (builtInWhitelistAllows(wire, wl)) {
          out.add(wire)
        }
      } else {
        out.add(wire)
      }
    }
    return out
  }

  /** Max wire names in {@link #planDeferCatalogTelemetry} (full list is still on the planner wire). */
  private static final int PLAN_DEFER_TEL_MAX_WIRE_NAMES = 48

  /** Max site user {@code toolId} values in {@link #planDeferCatalogTelemetry}. */
  private static final int PLAN_DEFER_TEL_MAX_USER_TOOL_IDS = 32

  /**
   * Maintainer / session-debug summary: whether the plan-defer recipe + tools block was built and what it lists.
   * Emitted on SSE {@code intentRecipeRouting} when {@code deferToPlanLoop} is true.
   */
  static Map planDeferCatalogTelemetry(
    StudioToolOperations ops,
    Map projectCfg,
    String catalogBlock,
    Map toolsLoopSessionBundle = null
  ) {
    Map cfg = projectCfg instanceof Map ? projectCfg : StudioAiAssistantProjectConfig.load(ops)
    String block = (catalogBlock ?: '').toString()
    boolean hasMarker = block.contains('[Studio — plan defer: recipe + tool catalog]')
    boolean sent = block.trim().length() > 0 && hasMarker

    List<String> wireNames = wireNamesForPlanDeferCatalog(ops, cfg, toolsLoopSessionBundle) ?: []
    List<Map> userEntries = StudioAiUserSiteTools.loadRegistryEntries(ops, cfg) ?: []
    List<String> userToolIds = []
    for (Map e : userEntries) {
      String id = e?.id?.toString()?.trim()
      if (id) {
        userToolIds.add(id)
      }
    }

    int wireCap = Math.min(wireNames.size(), PLAN_DEFER_TEL_MAX_WIRE_NAMES)
    List<String> wireTel = wireCap > 0 ? new ArrayList<>(wireNames.subList(0, wireCap)) : []
    boolean wireTruncated = wireNames.size() > wireTel.size()

    int idCap = Math.min(userToolIds.size(), PLAN_DEFER_TEL_MAX_USER_TOOL_IDS)
    List<String> idsTel = idCap > 0 ? new ArrayList<>(userToolIds.subList(0, idCap)) : []
    boolean userIdsTruncated = userToolIds.size() > idsTel.size()

    return [
      planDeferCatalogSent              : sent,
      planDeferCatalogChars             : block.length(),
      planDeferCatalogHasMarker         : hasMarker,
      planDeferWiredToolCount           : wireNames.size(),
      planDeferWiredToolNames           : wireTel,
      planDeferWiredToolNamesTruncated  : wireTruncated,
      planDeferSiteUserToolCount        : userToolIds.size(),
      planDeferSiteUserToolIds          : idsTel,
      planDeferSiteUserToolIdsTruncated : userIdsTruncated,
      planDeferInvokeSiteUserToolWired  : wireNames.contains('InvokeSiteUserTool'),
      planDeferMcpClientEnabled         : StudioAiAssistantProjectConfig.mcpClientEnabled(cfg)
    ]
  }

  /** Escapes user/site text for markdown table cells in planner catalogs. */
  private static String markdownTableCell(String raw) {
    String s = (raw ?: '').toString().trim()
    if (!s) {
      return ''
    }
    return s.replace('|', '\\|').replace('`', '\'').replaceAll(/[\r\n]+/, ' ').trim()
  }

  /**
   * Markdown table of wired tools for plan-defer prompts (companion to intent recipe catalog).
   */
  static String formatPlanDeferToolsCatalogMarkdown(
    StudioToolOperations ops,
    Map projectCfg,
    Map toolsLoopSessionBundle = null
  ) {
    Map cfg = projectCfg instanceof Map ? projectCfg : StudioAiAssistantProjectConfig.load(ops)
    List<String> names = wireNamesForPlanDeferCatalog(ops, cfg, toolsLoopSessionBundle)
    StringBuilder sb = new StringBuilder()
    sb.append('## Wired tools (this session)\n\n')
    sb.append(
      'Use exact wire names in **`tool_calls`**. Prefer a **recipe** from the catalog above when it clearly fits the step; use a single tool when one call is enough or no recipe matches.\n\n'
    )
    if (names.isEmpty()) {
      sb.append('(no built-in tools available after site policy)\n')
    } else {
      sb.append('| wire name | notes |\n')
      sb.append('|-----------|-------|\n')
      for (String wire : names) {
        String notes = 'built-in / general'
        if ('InvokeSiteUserTool'.equals(wire)) {
          notes = 'site Groovy tools — pass **toolId** from registry below'
        }
        sb.append('| `').append(wire).append('` | ').append(notes).append(" |\n")
      }
    }
    List<Map> userEntries = StudioAiUserSiteTools.loadRegistryEntries(ops, cfg)
    if (names.contains('InvokeSiteUserTool') && !userEntries.isEmpty()) {
      sb.append('\n### Site user tools (`InvokeSiteUserTool`)\n\n')
      sb.append('| toolId | description |\n')
      sb.append('|--------|-------------|\n')
      for (Map e : userEntries) {
        String id = markdownTableCell(e.id?.toString())
        String desc = markdownTableCell((e.description ?: '').toString())
        if (desc.length() > 160) {
          desc = desc.substring(0, 157) + '…'
        }
        sb.append('| `').append(id).append('` | ').append(desc ?: '—').append(" |\n")
      }
    }
    if (StudioAiAssistantProjectConfig.mcpClientEnabled(cfg)) {
      sb.append('\n**MCP:** enabled — additional dynamic `mcp_*` tools may appear on the wire (not listed here).\n')
    }
    sb.toString()
  }
}

