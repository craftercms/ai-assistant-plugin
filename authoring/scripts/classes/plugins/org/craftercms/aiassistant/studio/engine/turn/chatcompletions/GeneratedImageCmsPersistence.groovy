package plugins.org.craftercms.aiassistant.studio.engine.turn.chatcompletions

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsGetContent
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsWriteContent
import plugins.org.craftercms.aiassistant.studio.engine.catalog.AiOrchestrationTools
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations

import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.Locale
import java.util.Map
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Imports {@link plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.media.GenerateImageTool}
 * output into {@code /static-assets/…} and resolves {@link ChatCompletionsToolWire#STUDIO_AI_INLINE_IMAGE_REF_PREFIX}
 * placeholders in {@code WriteContent} XML — image-picker fields need repository paths, not chat inline refs.
 */
final class GeneratedImageCmsPersistence {

  private static final Logger log = LoggerFactory.getLogger(GeneratedImageCmsPersistence)

  static final String BUNDLE_REPO_PATH_BY_TOOL_CALL_ID = 'generateImageRepoPathByToolCallId'
  static final String BUNDLE_PENDING_REPO_PATHS = 'pendingGeneratedImageRepoPaths'
  static final String BUNDLE_AUTO_IMAGE_PERSIST_ATTEMPTED = 'generatedImageAutoPersistAttempted'
  static final String DEFAULT_CMS_IMAGE_IMPORT_REPO = '/static-assets/item/images/{yyyy}/{mm}/{dd}/'

  private static final Pattern IMAGE_PICKER_FIELD = Pattern.compile(
    '(?is)<([a-zA-Z0-9_-]*_image_s)>([^<]*)</\\1>'
  )

  private static final Pattern INLINE_REF_IN_XML = Pattern.compile(
    '(?i)' + Pattern.quote(ChatCompletionsToolWire.STUDIO_AI_INLINE_IMAGE_REF_PREFIX) + '([^<\\s]+)'
  )

  private GeneratedImageCmsPersistence() {}

  static Map<String, String> repoPathByToolCallId(Map bundle) {
    if (!(bundle instanceof Map)) {
      return [:]
    }
    Object raw = bundle.get(BUNDLE_REPO_PATH_BY_TOOL_CALL_ID)
    if (raw instanceof Map) {
      return (Map<String, String>) raw
    }
    return [:]
  }

  /**
   * After a successful GenerateImage: import bytes to static-assets and record {@code toolCallId → repoPath}.
   *
   * @return repository path, or empty when import skipped
   */
  static String persistAfterGenerateImage(
    StudioToolOperations ops,
    String toolCallId,
    Map<String, String> urlByToolCallId,
    Map bundle
  ) {
    String id = (toolCallId ?: '').trim()
    if (!id || ops == null || urlByToolCallId == null) {
      return ''
    }
    String url = urlByToolCallId.get(id)?.trim()
    if (!url) {
      return ''
    }
    Map<String, String> existing = new LinkedHashMap<>(repoPathByToolCallId(bundle))
    if (existing.get(id)?.trim()) {
      return existing.get(id).trim()
    }
    String siteId = ''
    try {
      siteId = (ops.resolveEffectiveSiteId(null) ?: '').toString().trim()
    } catch (Throwable ignored) {
    }
    if (!siteId) {
      return ''
    }
    try {
      Map imp = ops.importImageFromRemoteUrl(siteId, url, DEFAULT_CMS_IMAGE_IMPORT_REPO)
      String rel = (imp?.relativeUrl ?: '').toString().trim()
      if (!rel) {
        return ''
      }
      String repoPath = rel.startsWith('/') ? rel : ('/' + rel)
      existing.put(id, repoPath)
      if (bundle instanceof Map) {
        bundle.put(BUNDLE_REPO_PATH_BY_TOOL_CALL_ID, existing)
        List<String> pending = bundle.get(BUNDLE_PENDING_REPO_PATHS) instanceof List ?
          new ArrayList<>((List) bundle.get(BUNDLE_PENDING_REPO_PATHS)) :
          new ArrayList<>()
        if (!pending.contains(repoPath)) {
          pending.add(repoPath)
        }
        bundle.put(BUNDLE_PENDING_REPO_PATHS, pending)
      }
      log.info('GenerateImage: imported to CMS static-assets toolCallId={} path={}', id, repoPath)
      return repoPath
    } catch (Throwable t) {
      log.warn('GenerateImage: CMS import failed toolCallId={}: {}', id, t.message ?: t.toString())
      return ''
    }
  }

  /**
   * Adds {@code repositoryPath} and a short CMS hint to compact GenerateImage tool wire JSON.
   */
  static String enrichGenerateImageToolWire(String compactToolWireJson, String repoPath, JsonSlurper slurper = null) {
    if (!compactToolWireJson?.trim() || !repoPath?.trim()) {
      return compactToolWireJson ?: ''
    }
    JsonSlurper parser = slurper != null ? slurper : new JsonSlurper()
    try {
      Object parsed = parser.parseText(compactToolWireJson.toString())
      if (!(parsed instanceof Map)) {
        return compactToolWireJson
      }
      Map m = new LinkedHashMap<>((Map) parsed)
      m.put('repositoryPath', repoPath.trim())
      m.put(
        'cmsImagePickerHint',
        'For image-picker fields in WriteContent contentXml, set the field value to this repositoryPath exactly — ' +
          'do not use studio-ai-inline-image:// in repository XML.'
      )
      return JsonOutput.toJson(m)
    } catch (Throwable ignored) {
      return compactToolWireJson
    }
  }

  /**
   * Replaces {@code studio-ai-inline-image://…} tokens in WriteContent {@code contentXml} with imported repo paths.
   */
  static String resolveWriteContentArgsJson(
    String argsJson,
    Map bundle,
    Map<String, String> urlByToolCallId,
    StudioToolOperations ops,
    JsonSlurper slurper = null
  ) {
    if (!argsJson?.trim()) {
      return argsJson ?: '{}'
    }
    JsonSlurper parser = slurper != null ? slurper : new JsonSlurper()
    Object parsed
    try {
      parsed = parser.parseText(argsJson.toString())
    } catch (Throwable ignored) {
      return argsJson
    }
    if (!(parsed instanceof Map)) {
      return argsJson
    }
    Map args = AiOrchestrationTools.normalizeWriteContentToolArgsMap((Map) parsed)
    String xml = (args.contentXml ?: '').toString()
    if (!xml.contains(ChatCompletionsToolWire.STUDIO_AI_INLINE_IMAGE_REF_PREFIX)) {
      return JsonOutput.toJson(args)
    }
    String resolved = resolveInlineRefsInText(xml, bundle, urlByToolCallId, ops)
    if (!resolved.equals(xml)) {
      args.contentXml = resolved
      log.info('WriteContent: resolved studio-ai-inline-image refs to static-assets paths')
    }
    return JsonOutput.toJson(args)
  }

  static boolean contentXmlContainsRepoPath(String contentXml, String repoPath) {
    if (!contentXml?.trim() || !repoPath?.trim()) {
      return false
    }
    return contentXml.contains(repoPath.trim())
  }

  static void markRepoPathApplied(Map bundle, String repoPath) {
    if (!(bundle instanceof Map) || !repoPath?.trim()) {
      return
    }
    Object pending = bundle.get(BUNDLE_PENDING_REPO_PATHS)
    if (pending instanceof List) {
      List next = new ArrayList<>((List) pending)
      next.removeAll([repoPath.trim()])
      bundle.put(BUNDLE_PENDING_REPO_PATHS, next)
    }
  }

  static boolean hasPendingRepoPaths(Map bundle) {
    if (!(bundle instanceof Map)) {
      return false
    }
    Object pending = bundle.get(BUNDLE_PENDING_REPO_PATHS)
    return pending instanceof List && !((List) pending).isEmpty()
  }

  /**
   * When preview anchors a repository item and a generated image was imported, patch empty or inline-ref
   * {@code *_image_s} fields and save — authors should not need to drag from chat.
   *
   * @return WriteContent-shaped map when a write ran, or null
   */
  static Map tryAutoApplyPendingImageToAnchoredItem(StudioToolOperations ops, Map bundle) {
    if (!(bundle instanceof Map) || ops == null || !hasPendingRepoPaths(bundle)) {
      return null
    }
    if (Boolean.TRUE.equals(bundle.get(BUNDLE_AUTO_IMAGE_PERSIST_ATTEMPTED))) {
      return null
    }
    bundle.put(BUNDLE_AUTO_IMAGE_PERSIST_ATTEMPTED, Boolean.TRUE)
    String path = (bundle.contentPath ?: bundle.anchoredRepositoryPath ?: '').toString().trim()
    if (!path.startsWith('/site/')) {
      return null
    }
    List pending = bundle.get(BUNDLE_PENDING_REPO_PATHS) instanceof List ?
      (List) bundle.get(BUNDLE_PENDING_REPO_PATHS) : []
    String repoPath = pending ? pending[0]?.toString()?.trim() : ''
    if (!repoPath) {
      return null
    }
    String siteId = ''
    try {
      siteId = (ops.resolveEffectiveSiteId(null) ?: '').toString().trim()
    } catch (Throwable ignored) {
    }
    if (!siteId) {
      return null
    }
    try {
      Map read = CmsGetContent.read(ops, siteId, path, null) as Map
      String xml = (read?.contentXml ?: '').toString()
      if (!xml?.trim()) {
        return null
      }
      String patched = applyRepoPathToImagePickerFields(
        xml,
        repoPath,
        imageFieldIdsFromBundle(bundle),
        Boolean.TRUE.equals(bundle.toolsLoopResearchPageRefreshExpectsHeroImage)
      )
      if (patched.equals(xml)) {
        return null
      }
      Map out = CmsWriteContent.write(ops, siteId, path, patched, 'true')
      markRepoPathApplied(bundle, repoPath)
      log.info('GenerateImage: auto-applied imported image to anchored item path={} repoPath={}', path, repoPath)
      return out
    } catch (Throwable t) {
      log.warn('GenerateImage: auto-apply to anchored item failed path={}: {}', path, t.message ?: t.toString())
      return null
    }
  }

  private static boolean imagePickerValueNeedsGeneratedAsset(String value) {
    String v = (value ?: '').trim()
    if (!v) {
      return true
    }
    if (v.toLowerCase(Locale.ROOT).startsWith(ChatCompletionsToolWire.STUDIO_AI_INLINE_IMAGE_REF_PREFIX.toLowerCase(Locale.ROOT))) {
      return true
    }
    return v.startsWith('data:image')
  }

  private static List<String> imageFieldIdsFromBundle(Map bundle) {
    if (!(bundle instanceof Map)) {
      return []
    }
    Object raw = bundle.get('toolsLoopCopyPlanImageFieldIds')
    if (raw instanceof List) {
      List<String> out = []
      for (Object o : (List) raw) {
        String id = o?.toString()?.trim()
        if (id) {
          out.add(id)
        }
      }
      if (!out.isEmpty()) {
        return out
      }
    }
    return []
  }

  private static String applyRepoPathToImagePickerFields(
    String xml,
    String repoPath,
    List<String> fieldIds,
    boolean replaceExisting
  ) {
    if (!xml?.trim() || !repoPath?.trim()) {
      return xml ?: ''
    }
    if (fieldIds instanceof List && !fieldIds.isEmpty()) {
      String out = xml
      boolean changed = false
      for (String fieldId : fieldIds) {
        if (!fieldId?.trim()) {
          continue
        }
        String pattern = '(?is)(<' + Pattern.quote(fieldId.trim()) + '>)([^<]*)(</' + Pattern.quote(fieldId.trim()) + '>)'
        java.util.regex.Matcher m = (out =~ pattern)
        if (!m.find()) {
          continue
        }
        String value = m.group(2) ?: ''
        if (replaceExisting || imagePickerValueNeedsGeneratedAsset(value)) {
          out = out.replaceFirst(pattern, '$1' + Matcher.quoteReplacement(repoPath.trim()) + '$3')
          changed = true
        }
      }
      return changed ? out : xml
    }
    return applyRepoPathToUnsetImagePickerFields(xml, repoPath)
  }

  private static String applyRepoPathToUnsetImagePickerFields(String xml, String repoPath) {
    if (!xml?.trim() || !repoPath?.trim()) {
      return xml ?: ''
    }
    Matcher m = IMAGE_PICKER_FIELD.matcher(xml)
    StringBuffer sb = new StringBuffer()
    boolean changed = false
    while (m.find()) {
      String fieldId = m.group(1)
      String value = m.group(2) ?: ''
      if (imagePickerValueNeedsGeneratedAsset(value)) {
        m.appendReplacement(sb, '<' + fieldId + '>' + Matcher.quoteReplacement(repoPath.trim()) + '</' + fieldId + '>')
        changed = true
      } else {
        m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)))
      }
    }
    if (!changed) {
      return xml
    }
    m.appendTail(sb)
    return sb.toString()
  }

  private static String resolveInlineRefsInText(
    String text,
    Map bundle,
    Map<String, String> urlByToolCallId,
    StudioToolOperations ops
  ) {
    String out = text.toString()
    Matcher m = INLINE_REF_IN_XML.matcher(out)
    StringBuffer sb = new StringBuffer()
    Map<String, String> repoMap = new LinkedHashMap<>(repoPathByToolCallId(bundle))
    while (m.find()) {
      String id = (m.group(1) ?: '').trim()
      String repoPath = repoMap.get(id)?.trim()
      if (!repoPath && id && urlByToolCallId != null) {
        repoPath = persistAfterGenerateImage(ops, id, urlByToolCallId, bundle)
        if (repoPath) {
          repoMap.put(id, repoPath)
        }
      }
      String repl = repoPath ?: m.group(0)
      m.appendReplacement(sb, Matcher.quoteReplacement(repl))
    }
    m.appendTail(sb)
    return sb.toString()
  }
}
