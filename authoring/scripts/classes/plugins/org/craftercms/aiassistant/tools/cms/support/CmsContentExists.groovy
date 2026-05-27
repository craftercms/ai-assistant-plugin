package plugins.org.craftercms.aiassistant.tools.cms.support

import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

/**
 * Repository path existence probe via Studio v1 {@code cstudioContentService.contentExists}
 * (and {@code shallowContentExists} when present).
 */
final class CmsContentExists {

  private CmsContentExists() {}

  /**
   * @param paths optional list; when empty, uses {@code singlePath}
   */
  static Map probe(StudioToolOperations ops, String siteId, String singlePath, List<String> paths = null) {
    ops.runWithStudioSecurity {
      siteId = ops.resolveEffectiveSiteId(siteId)
      List<String> toCheck = []
      if (paths != null && !paths.isEmpty()) {
        for (Object p : paths) {
          String norm = normalizeOptionalPath(p)
          if (norm) {
            toCheck.add(norm)
          }
        }
      }
      String one = normalizeOptionalPath(singlePath)
      if (one && !toCheck.contains(one)) {
        toCheck.add(0, one)
      }
      if (toCheck.isEmpty()) {
        throw new IllegalArgumentException('Missing required field: path (or contentPath), or non-empty paths[]')
      }

      List<Map> results = []
      for (String path : toCheck) {
        boolean exists = contentExistsAtPath(ops, siteId, path)
        results.add([
          path   : path,
          exists : exists,
          hint   : exists
            ? 'Repository file exists — safe to call GetContent on this path.'
            : 'Path is not in the repository yet. Do not treat GetContent failure here as a template read — pick an existing path from ResearchSiteContent or ListPagesAndComponents, or WriteContent to create this path.'
        ])
      }

      if (results.size() == 1) {
        Map row = results[0]
        return [
          ok     : true,
          siteId : siteId,
          path   : row.path,
          exists : row.exists,
          hint   : row.hint
        ]
      }

      return [
        ok      : true,
        siteId  : siteId,
        results : results
      ]
    }
  }

  private static String normalizeOptionalPath(Object value) {
    String normalized = (value ?: '').toString().trim()
    if (!normalized) {
      return null
    }
    if (!normalized.startsWith('/')) {
      normalized = "/${normalized}"
    }
    return normalized
  }

  private static boolean contentExistsAtPath(StudioToolOperations ops, String siteId, String path) {
    Object v1 = ops?.cstudioContentServiceBean
    if (v1 == null) {
      throw new IllegalStateException('cstudioContentService unavailable')
    }
    try {
      if (v1.metaClass.respondsTo(v1, 'contentExists', String, String)) {
        Object exists = v1.contentExists(siteId, path)
        return exists instanceof Boolean ? ((Boolean) exists).booleanValue() : Boolean.TRUE.equals(exists)
      }
      if (v1.metaClass.respondsTo(v1, 'shallowContentExists', String, String)) {
        Object exists = v1.shallowContentExists(siteId, path)
        return exists instanceof Boolean ? ((Boolean) exists).booleanValue() : Boolean.TRUE.equals(exists)
      }
      throw new IllegalStateException('cstudioContentService does not expose contentExists(siteId, path)')
    } catch (IllegalStateException e) {
      throw e
    } catch (Throwable t) {
      throw new IllegalStateException(
        "contentExists failed for site '${siteId}' path '${path}': ${t.message ?: t.toString()}",
        t
      )
    }
  }
}
