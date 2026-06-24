package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal

import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations

import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Set

/**
 * Field-level diff between two repository refs for one content item path.
 */
final class CmsCompareContentVersions {

  private CmsCompareContentVersions() {}

  static Map compare(
    StudioToolOperations ops,
    String siteId,
    String path,
    String baseCommitRef,
    String compareCommitRef,
    List<String> fieldIds = null,
    int maxFieldValueChars = 500
  ) {
    ops.runWithStudioSecurity {
      siteId = ops.resolveEffectiveSiteId(siteId)
      String normalized = CmsRepositorySupport.normalizeLeadingSlash(path, 'path')
      String baseRef = normalizeRef(baseCommitRef)
      String compareRef = normalizeRef(compareCommitRef)
      if (!compareRef) {
        throw new IllegalArgumentException('Missing required field: compareCommitRef (or compareRef / version / commitId)')
      }

      String baseXml = readXml(ops, siteId, normalized, baseRef)
      String compareXml = readXml(ops, siteId, normalized, compareRef)

      Set<String> fieldIdSet = new LinkedHashSet<>()
      if (fieldIds) {
        for (def id : fieldIds) {
          String t = (id ?: '').toString().trim()
          if (t) {
            fieldIdSet.add(t)
          }
        }
      }
      if (fieldIdSet.isEmpty()) {
        fieldIdSet.addAll(CmsContentVersionXml.listComparableFieldIds(baseXml))
        fieldIdSet.addAll(CmsContentVersionXml.listComparableFieldIds(compareXml))
      }

      Map<String, String> baseValues = CmsContentVersionXml.fieldValuesById(baseXml, fieldIdSet)
      Map<String, String> compareValues = CmsContentVersionXml.fieldValuesById(compareXml, fieldIdSet)

      List<Map> changedFields = []
      int unchangedCount = 0
      for (String fieldId : fieldIdSet) {
        String left = baseValues.get(fieldId) ?: ''
        String right = compareValues.get(fieldId) ?: ''
        if (CmsContentVersionXml.fieldValuesEqual(left, right)) {
          unchangedCount++
          continue
        }
        String changeType = 'modified'
        if (!left?.trim() && right?.trim()) {
          changeType = 'added'
        } else if (left?.trim() && !right?.trim()) {
          changeType = 'removed'
        }
        changedFields.add([
          fieldId    : fieldId,
          changeType : changeType,
          baseValue  : CmsContentVersionXml.truncateForTool(left, maxFieldValueChars),
          compareValue: CmsContentVersionXml.truncateForTool(right, maxFieldValueChars)
        ])
      }

      return [
        action            : 'compare_content_versions',
        siteId            : siteId,
        path              : normalized,
        baseCommitRef     : baseRef,
        compareCommitRef  : compareRef,
        identical         : changedFields.isEmpty(),
        changedFieldCount : changedFields.size(),
        unchangedFieldCount: unchangedCount,
        changedFields     : changedFields
      ]
    }
  }

  private static String normalizeRef(String ref) {
    String r = (ref ?: '').toString().trim()
    if (!r || r.equalsIgnoreCase('HEAD') || r.equalsIgnoreCase('current')) {
      return CmsRepositorySupport.CONTENT_REF_HEAD
    }
    return r
  }

  private static String readXml(StudioToolOperations ops, String siteId, String path, String ref) {
    Map item = CmsGetContent.read(ops, siteId, path, ref) as Map
    String xml = (item?.contentXml ?: '').toString()
    if (!xml?.trim()) {
      throw new IllegalStateException("No content returned for ref '${ref}'")
    }
    return xml
  }
}
