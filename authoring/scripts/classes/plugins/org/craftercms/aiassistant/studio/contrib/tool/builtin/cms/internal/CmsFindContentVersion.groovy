package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal

import plugins.org.craftercms.aiassistant.studio.engine.context.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations

import java.util.Locale

/**
 * Read-only search across Studio version history for a repository content item.
 */
final class CmsFindContentVersion {

  private CmsFindContentVersion() {}

  /**
   * Newest revertible history entry whose XML (optionally one field) contains every snippet (case-insensitive).
   */
  static String matchNewestByContentContains(
    StudioToolOperations ops,
    String siteId,
    String path,
    List<String> mustContainSnippets,
    String fieldId = null,
    int maxScan = 40
  ) {
    Map result = find(ops, siteId, path, [
      contentContains: mustContainSnippets,
      contentFieldId : fieldId,
      maxScan        : maxScan,
      maxMatches     : 1
    ])
    List matches = (result?.matches ?: []) as List
    if (matches.isEmpty()) {
      return null
    }
    return matches[0]?.versionNumber?.toString()?.trim() ?: null
  }

  /**
   * @return map with {@code matches} (newest first), {@code scannedCount}, {@code selection}
   */
  static Map find(StudioToolOperations ops, String siteId, String path, Map input) {
    ops.runWithStudioSecurity {
      siteId = ops.resolveEffectiveSiteId(siteId)
      String normalized = CmsRepositorySupport.normalizeLeadingSlash(path, 'path')
      List history = CmsContentVersionHistory.list(ops, siteId, normalized)
      if (history == null || history.isEmpty()) {
        return baseResult(siteId, normalized, 'none', [], 0, 'No version history for path')
      }

      String explicitVersion = (input?.version ?: input?.versionNumber ?: '').toString().trim()
      boolean revertToInitial = AuthoringPreviewContext.isTruthy(input?.revertToInitial) ||
        AuthoringPreviewContext.isTruthy(input?.revertToOldest) ||
        AuthoringPreviewContext.isTruthy(input?.revertToFirst)
      boolean revertToPrevious = AuthoringPreviewContext.isTruthy(input?.revertToPrevious)

      if (explicitVersion) {
        Map row = historyRow(history, explicitVersion)
        if (row == null) {
          return baseResult(siteId, normalized, 'explicit', [], 0, "versionNumber '${explicitVersion}' not found in history")
        }
        return withMatches(
          siteId, normalized, 'explicit', [enrichMatch(ops, siteId, normalized, row, input)],
          1, null
        )
      }
      if (revertToInitial) {
        String oldest = CmsRevertChange.oldestVersion(ops, siteId, normalized)
        Map row = historyRow(history, oldest)
        return withMatches(
          siteId, normalized, 'initial',
          row ? [enrichMatch(ops, siteId, normalized, row, input)] : [],
          history.size(), null
        )
      }
      if (revertToPrevious && isSearchEmpty(input)) {
        String prev = CmsRevertChange.previousVersion(ops, siteId, normalized)
        Map row = historyRow(history, prev)
        return withMatches(
          siteId, normalized, 'previous',
          row ? [enrichMatch(ops, siteId, normalized, row, input)] : [],
          history.size(), null
        )
      }

      List<String> contentContains = stringList(input?.contentContains ?: input?.mustContain)
      String contentFieldId = (input?.contentFieldId ?: input?.fieldId ?: '').toString().trim()
      List<String> fieldValueContains = stringList(input?.fieldValueContains)
      String fieldValueFieldId = (input?.fieldValueFieldId ?: input?.valueFieldId ?: '').toString().trim()
      String imagePathContains = (input?.imagePathContains ?: input?.imageContains ?: '').toString().trim()

      if (contentContains.isEmpty() && fieldValueContains.isEmpty() && !imagePathContains) {
        throw new IllegalArgumentException(
          'Missing search criteria: pass contentContains (phrases), fieldValueContains + fieldValueFieldId, imagePathContains, version, revertToInitial, or revertToPrevious'
        )
      }
      if (!fieldValueContains.isEmpty() && !fieldValueFieldId) {
        throw new IllegalArgumentException('fieldValueContains requires fieldValueFieldId (element id from GetContentTypeFormDefinition)')
      }

      int maxScan = parsePositiveInt(input?.maxScan, 40, 200)
      int maxMatches = parsePositiveInt(input?.maxMatches, 1, 10)
      int limit = Math.min(history.size(), Math.max(1, maxScan))
      List<Map> matches = []
      for (int i = 0; i < limit; i++) {
        Map e = history[i] as Map
        if (e == null || e.revertible == false) {
          continue
        }
        String vn = e.versionNumber?.toString()?.trim()
        if (!vn) {
          continue
        }
        String xml = readXmlQuiet(ops, siteId, normalized, vn)
        if (!xml?.trim()) {
          continue
        }
        if (!matchesContentCriteria(xml, contentContains, contentFieldId)) {
          continue
        }
        if (!matchesFieldValueCriteria(xml, fieldValueContains, fieldValueFieldId)) {
          continue
        }
        if (imagePathContains && !matchesImagePathCriteria(xml, imagePathContains)) {
          continue
        }
        matches.add(enrichMatch(ops, siteId, normalized, e, input, xml))
        if (matches.size() >= maxMatches) {
          break
        }
      }

      String selection = contentContains ? 'content_match' :
        (fieldValueContains ? 'field_value_match' : 'image_path_match')
      String message = matches.isEmpty() ?
        'No matching version found in scanned history' : null
      return withMatches(siteId, normalized, selection, matches, limit, message)
    }
  }

  private static boolean isSearchEmpty(Map input) {
    return stringList(input?.contentContains ?: input?.mustContain).isEmpty() &&
      stringList(input?.fieldValueContains).isEmpty() &&
      !(input?.imagePathContains ?: input?.imageContains)?.toString()?.trim()
  }

  private static boolean matchesContentCriteria(String xml, List<String> snippets, String fieldId) {
    if (snippets.isEmpty()) {
      return true
    }
    String plain = fieldId ?
      CmsContentVersionXml.extractFieldRoughPlainText(xml, fieldId) :
      CmsContentVersionXml.roughPlainTextFromWholeXml(xml)
    if (!plain) {
      return false
    }
    for (String snip : snippets) {
      if (!CmsContentVersionXml.plainTextContainsIgnoreCase(plain, snip)) {
        return false
      }
    }
    return true
  }

  private static boolean matchesFieldValueCriteria(String xml, List<String> snippets, String fieldId) {
    if (snippets.isEmpty()) {
      return true
    }
    String raw = CmsContentVersionXml.extractFieldRawText(xml, fieldId)
    if (!raw) {
      return false
    }
    String hay = raw.toLowerCase(Locale.ROOT)
    for (String snip : snippets) {
      if (!hay.contains(snip.trim().toLowerCase(Locale.ROOT))) {
        return false
      }
    }
    return true
  }

  private static boolean matchesImagePathCriteria(String xml, String pathNeedle) {
    if (!pathNeedle?.trim()) {
      return true
    }
    String needle = pathNeedle.trim().toLowerCase(Locale.ROOT)
    for (String fieldId : CmsContentVersionXml.imagePickerFieldIds(xml)) {
      String raw = CmsContentVersionXml.extractFieldRawText(xml, fieldId)
      if (raw && raw.toLowerCase(Locale.ROOT).contains(needle)) {
        return true
      }
    }
    return xml.toLowerCase(Locale.ROOT).contains(needle)
  }

  private static Map enrichMatch(
    StudioToolOperations ops,
    String siteId,
    String path,
    Map historyRow,
    Map input,
    String xml = null
  ) {
    String vn = historyRow.versionNumber?.toString()?.trim()
    String body = xml ?: readXmlQuiet(ops, siteId, path, vn)
    boolean includePreview = AuthoringPreviewContext.isTruthy(input?.includeFieldPreview) ||
      AuthoringPreviewContext.isTruthy(input?.includePreview)
    Map out = new LinkedHashMap<>(historyRow)
    out.versionNumber = vn
    if (!includePreview || !body?.trim()) {
      return out
    }
    String previewField = (input?.previewFieldId ?: input?.contentFieldId ?: input?.fieldValueFieldId ?: '').toString().trim()
    if (previewField) {
      out.previewFieldId = previewField
      out.previewValue = CmsContentVersionXml.truncateForTool(
        CmsContentVersionXml.extractFieldRawText(body, previewField),
        parsePositiveInt(input?.maxPreviewChars, 500, 4000)
      )
    } else {
      out.previewPlainText = CmsContentVersionXml.truncateForTool(
        CmsContentVersionXml.roughPlainTextFromWholeXml(body),
        parsePositiveInt(input?.maxPreviewChars, 400, 4000)
      )
    }
    return out
  }

  private static String readXmlQuiet(StudioToolOperations ops, String siteId, String path, String versionNumber) {
    try {
      Map item = CmsGetContent.read(ops, siteId, path, versionNumber) as Map
      return (item?.contentXml ?: '').toString()
    } catch (Throwable ignored) {
      return ''
    }
  }

  private static Map historyRow(List history, String versionNumber) {
    String want = (versionNumber ?: '').trim()
    for (def e : history) {
      if (e?.versionNumber?.toString()?.trim() == want) {
        return e as Map
      }
    }
    return null
  }

  private static List<String> stringList(Object raw) {
    List<String> out = []
    if (raw instanceof Collection) {
      for (def item : raw) {
        String t = (item ?: '').toString().trim()
        if (t) {
          out.add(t)
        }
      }
    } else {
      String one = (raw ?: '').toString().trim()
      if (one) {
        out.add(one)
      }
    }
    return out
  }

  private static int parsePositiveInt(Object raw, int defaultValue, int cap) {
    try {
      int n = raw == null ? defaultValue : Integer.parseInt(raw.toString().trim())
      if (n < 1) {
        return defaultValue
      }
      return Math.min(n, cap)
    } catch (Throwable ignored) {
      return defaultValue
    }
  }

  private static Map baseResult(
    String siteId,
    String path,
    String selection,
    List matches,
    int scannedCount,
    String message
  ) {
    Map out = [
      action       : 'find_content_version',
      siteId       : siteId,
      path         : path,
      selection    : selection,
      matchCount   : matches.size(),
      scannedCount : scannedCount,
      matches      : matches
    ]
    if (message) {
      out.message = message
    }
    return out
  }

  private static Map withMatches(
    String siteId,
    String path,
    String selection,
    List matches,
    int scannedCount,
    String message
  ) {
    Map out = baseResult(siteId, path, selection, matches, scannedCount, message)
    if (!matches.isEmpty()) {
      out.versionNumber = matches[0].versionNumber
    }
    return out
  }
}
