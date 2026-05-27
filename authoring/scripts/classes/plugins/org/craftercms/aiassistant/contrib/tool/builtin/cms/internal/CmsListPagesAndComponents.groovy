package plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal

import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.opensearch.client.opensearch.core.SearchRequest
/** CMS tool implementation extracted from StudioToolOperations. */
final class CmsListPagesAndComponents {

  private static final Logger log = LoggerFactory.getLogger(CmsListPagesAndComponents)

  /**
   * Private constructor; not for direct use.
   */
private CmsListPagesAndComponents() {}
  /**
   * Lists matching items for the model or author.
   * @param ops Caller-supplied input.
   * @param siteId Studio or repository context for this call.
   * @param size Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  static Map list(StudioToolOperations ops, String siteId, int size = 1000) {
    def effectiveSite = ops.resolveEffectiveSiteId(siteId)
    if (!effectiveSite?.trim()) {
      return [
        error       : true,
        message     : 'No siteId could be resolved. The chat client must send siteId in the plugin request (query or JSON body) matching the open site.',
        siteId      : '',
        items       : []
      ]
    }

    def req = SearchRequest.of { r ->
      r.query { q ->
        q.bool { b ->
          b.should { s -> s.prefix { p -> p.field('content-type').value('/page') } }
          b.should { s -> s.prefix { p -> p.field('content-type').value('/component') } }
        }
      }.from(0).size(size)
    }

    def authoringSearchService = null
    try {
      authoringSearchService = ops.applicationContext?.get('authoringSearchService')
    } catch (Throwable ignored) {}
    if (authoringSearchService == null) {
      log.warn('listPagesAndComponents: authoringSearchService bean not found')
      return [
        error  : true,
        message: 'Search service is not available in this Studio context. Use GetContent with a known repository path instead.',
        siteId : effectiveSite,
        items  : []
      ]
    }

    try {
      return ops.runWithStudioSecurity {
        def result = authoringSearchService.search(effectiveSite, req, Map)
        if (!result) return [siteId: effectiveSite, items: []]
        def items = result.hits().hits()*.source()
        [siteId: effectiveSite, items: items]
      }
    } catch (Throwable t) {
      def msg = t.message ?: t.toString()
      log.warn('listPagesAndComponents OpenSearch failed for site {}: {}', effectiveSite, msg)
      return [
        error  : true,
        message: """OpenSearch is not reachable from Studio (${t.class.simpleName}: ${msg}). ListPagesAndComponents requires OpenSearch (same as Studio search) to be running and configured for authoring—start the search stack or fix connection settings. Until then, use GetContent with a full path (e.g. /site/website/...) if the user knows it.""",
        siteId : effectiveSite,
        items  : []
      ]
    }
  }

}
