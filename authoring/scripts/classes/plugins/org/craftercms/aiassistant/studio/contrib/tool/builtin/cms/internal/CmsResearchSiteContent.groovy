package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal

import plugins.org.craftercms.aiassistant.studio.config.StudioAiPlatformSettings
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.opensearch.client.opensearch._types.query_dsl.TextQueryType
import org.opensearch.client.opensearch.core.SearchRequest
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsGetContent
import java.util.Locale
/** CMS tool implementation extracted from StudioToolOperations. */
final class CmsResearchSiteContent {

  private static final Logger log = LoggerFactory.getLogger(CmsResearchSiteContent)

  /**
   * Private constructor; not for direct use.
   */
private CmsResearchSiteContent() {}
  /**
   * Globally enabled.
   * @return True when the check succeeds.
   */
  static boolean globallyEnabled() {
    StudioAiPlatformSettings.propertyBoolean('aiassistant.siteContentResearch.enabled', true)
  }

  /**
   * Coerces requested integers against JVM/system caps.
   * Guarantees minimum/maximum sane ranges.
   * Protects Studio OpenSearch from runaway hits.
   */
  private static int maxSearchHits(Integer requested) {
    int defMax = StudioAiPlatformSettings.propertyInt('aiassistant.siteContentResearch.maxSearchHits', 12, 1, 30)
    int r = (requested != null) ? requested.intValue() : defMax
    return Math.min(30, Math.max(1, r))
  }

  /**
   * Mirrors fetch concurrency limiting for repository hydration.
   * Balances throughput vs Studio CPU.
   * Feeds ResearchSiteContent batch loops.
   */
  private static int maxFetchItems(Integer requested) {
    int defMax = StudioAiPlatformSettings.propertyInt('aiassistant.siteContentResearch.maxFetchItems', 5, 0, 10)
    int r = (requested != null) ? requested.intValue() : defMax
    return Math.min(10, Math.max(0, r))
  }

  /**
   * Reads excerpt length knobs from plugin configuration.
   * Provides deterministic defaults.
   * Keeps SSE payloads bounded while preserving readability.
   */
  private static int excerptChars() {
    return StudioAiPlatformSettings.propertyInt('aiassistant.siteContentResearch.excerptChars', 1800, 200, 8000)
  }

  /**
   * Filters `/site/system`, descriptors, binaries via substring checks.
   * Uses content-type hints when supplied.
   * Avoids indexing noise during research summaries.
   */
  private static boolean skipPath(String path, String contentType) {
    String p = (path ?: '').toString().trim()
    String ct = (contentType ?: '').toString().trim()
    if (!p || !p.endsWith('.xml')) {
      return true
    }
    if (p.toLowerCase(Locale.ROOT).endsWith('level.xml')) {
      return true
    }
    if ('/page/redirect'.equals(ct)) {
      return true
    }
    return false
  }

  /**
   * Plain-text excerpt from a {@code /site/.../*.xml} body for research answers (strips markup in {@code *_html} / {@code *_t} fields).
   */
  static String plainTextExcerptFromSiteContentXml(String xmlUtf8, int maxChars) {
    if (!xmlUtf8?.toString()?.trim() || maxChars <= 0) {
      return ''
    }
    String s = xmlUtf8.toString()
    s = s.replaceAll('(?s)<!\\[CDATA\\[(.*?)\\]\\]>', '$1')
    StringBuilder buf = new StringBuilder()
    def m = (s =~ /(?is)<([a-zA-Z0-9_.-]+(?:_html|_t))>(.*?)<\/\1>/)
    while (m.find()) {
      String inner = m.group(2)?.toString()?.replaceAll('<[^>]+>', ' ')?.replaceAll('\\s+', ' ')?.trim()
      if (!inner) {
        continue
      }
      if (buf.length() > 0) {
        buf.append('\n')
      }
      buf.append(inner)
      if (buf.length() >= maxChars * 2) {
        break
      }
    }
    String out = buf.toString().trim()
    if (!out) {
      out = s.replaceAll('<[^>]+>', ' ').replaceAll('\\s+', ' ').trim()
    }
    if (out.length() > maxChars) {
      return out.substring(0, maxChars) + '…'
    }
    out
  }

  /**
   * Research.
   * @param ops Caller-supplied input.
   * @param siteId Studio or repository context for this call.
   * @param query Caller-supplied input.
   * @param maxSearchHitsOpt Caller-supplied input.
   * @param maxFetchItemsOpt Caller-supplied input.
   * @param pathPrefixOpt Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  static Map research(StudioToolOperations ops, String siteId, String query, Integer maxSearchHitsOpt, Integer maxFetchItemsOpt, String pathPrefixOpt) {
    if (!globallyEnabled()) {
      return [
        ok      : false,
        tool    : 'ResearchSiteContent',
        message : 'Site content research is disabled (JVM aiassistant.siteContentResearch.enabled=false).'
      ]
    }
    String q = (query ?: '').toString().trim()
    if (!q) {
      throw new IllegalArgumentException('Missing required field: query')
    }
    def effectiveSite = ops.resolveEffectiveSiteId(siteId)
    if (!effectiveSite?.trim()) {
      return [
        ok      : false,
        tool    : 'ResearchSiteContent',
        siteId  : '',
        query   : q,
        message : 'No siteId could be resolved. Pass siteId matching the open Crafter site.'
      ]
    }
    String pathPrefix = (pathPrefixOpt ?: '/site/').toString().trim()
    if (!pathPrefix.startsWith('/')) {
      pathPrefix = '/' + pathPrefix
    }
    int searchSize = maxSearchHits(maxSearchHitsOpt)
    int fetchMax = maxFetchItems(maxFetchItemsOpt)
    int excerptMax = excerptChars()

    def authoringSearchService = null
    try {
      authoringSearchService = ops.applicationContext?.get('authoringSearchService')
    } catch (Throwable ignored) {}
    if (authoringSearchService == null) {
      return [
        ok                 : false,
        tool               : 'ResearchSiteContent',
        siteId             : effectiveSite,
        query              : q,
        searchAvailable    : false,
        message            :
          'Authoring search service is not available. Use GetContent when the author supplies a repository path.',
        hint               : 'Ensure OpenSearch authoring index is running (same as Studio sidebar search).'
      ]
    }

    def req = SearchRequest.of { r ->
      r.query { qb ->
        qb.bool { b ->
          b.must { m ->
            m.multiMatch { mm ->
              mm.query(q)
              mm.fields(
                'title_t^3',
                'internal-name^2',
                'body_html',
                'description_html',
                'navLabel',
                'seoDescription_t'
              )
              mm.type(TextQueryType.BestFields)
              mm.fuzziness('AUTO')
            }
          }
          b.filter { f ->
            f.prefix { p ->
              p.field('localId').value(pathPrefix)
            }
          }
          b.should { s -> s.prefix { p -> p.field('content-type').value('/page') } }
          b.should { s -> s.prefix { p -> p.field('content-type').value('/component') } }
          b.minimumShouldMatch('1')
          b.mustNot { mn ->
            mn.term { t ->
              t.field('disabled')
              t.value { v -> v.booleanValue(true) }
            }
          }
        }
      }.from(0).size(searchSize)
    }

    try {
      return ops.runWithStudioSecurity {
        def result = authoringSearchService.search(effectiveSite, req, Map)
        List hitsOut = []
        int fetched = 0
        int searchHitCount = 0
        if (result?.hits()?.hits() != null) {
          searchHitCount = result.hits().hits().size()
          for (def hit : result.hits().hits()) {
            Map src = (hit?.source() instanceof Map) ? (Map) hit.source() : [:]
            String path = src.get('localId')?.toString()?.trim() ?: ''
            String ctype = src.get('content-type')?.toString()?.trim() ?: ''
            if (skipPath(path, ctype)) {
              continue
            }
            String internalName = src.get('internal-name')?.toString()?.trim() ?: ''
            String title =
              src.get('title_t')?.toString()?.trim() ?:
                internalName ?:
                src.get('navLabel')?.toString()?.trim() ?: ''
            Double scoreVal = null
            try {
              if (hit.score() != null) {
                scoreVal = hit.score()
              }
            } catch (Throwable ignoredScore) {}
            Map row = [
              path        : path,
              contentType : ctype,
              title       : title,
              score       : scoreVal
            ]
            if (internalName) {
              row['internal-name'] = internalName
            }
            String indexSnippet = ''
            for (String fk : ['body_html', 'description_html', 'seoDescription_t', 'title_t']) {
              String fv = src.get(fk)?.toString()?.trim()
              if (fv) {
                indexSnippet = fv.replaceAll('<[^>]+>', ' ').replaceAll('\\s+', ' ').trim()
                if (indexSnippet.length() > 320) {
                  indexSnippet = indexSnippet.substring(0, 317) + '…'
                }
                break
              }
            }
            if (indexSnippet) {
              row.indexSnippet = indexSnippet
            }
            if (fetchMax > 0 && fetched < fetchMax && path) {
              try {
                Map gc = CmsGetContent.read(ops, effectiveSite, path)
                String xml = (gc?.contentXml ?: '').toString()
                if (xml?.trim()) {
                  row.contentExcerpt = plainTextExcerptFromSiteContentXml(xml, excerptMax)
                  row.contentXmlChars = xml.length()
                  fetched++
                }
              } catch (Throwable tFetch) {
                row.fetchError = (tFetch.message ?: tFetch.toString()).toString()
                log.debug('researchSiteContent GetContent failed path={}: {}', path, tFetch.message)
              }
            }
            hitsOut << row
          }
        }
        [
          ok              : true,
          tool            : 'ResearchSiteContent',
          siteId          : effectiveSite,
          query           : q,
          pathPrefix      : pathPrefix,
          searchAvailable : true,
          searchHitCount  : searchHitCount,
          fetchedCount    : fetched,
          hits            : hitsOut,
          hint            :
            'Hits are from the authoring search index; contentExcerpt is from GetContent on the top matches. Cite repository paths in your answer; call GetContent again for full XML when editing.'
        ]
      }
    } catch (Throwable t) {
      def msg = t.message ?: t.toString()
      log.warn('researchSiteContent OpenSearch failed site {} query={}: {}', effectiveSite, q, msg)
      return [
        ok              : false,
        tool            : 'ResearchSiteContent',
        siteId          : effectiveSite,
        query           : q,
        searchAvailable : false,
        message         :
          "OpenSearch is not reachable from Studio (${t.class.simpleName}: ${msg}). Start the authoring search stack or use GetContent on a known path.",
        hits            : []
      ]
    }
  }

}
