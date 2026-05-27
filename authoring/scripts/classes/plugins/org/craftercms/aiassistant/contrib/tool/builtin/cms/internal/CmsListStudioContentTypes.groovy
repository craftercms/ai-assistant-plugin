package plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal

import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal.CmsRepositorySupport
import java.util.Locale
/** CMS tool implementation extracted from StudioToolOperations. */
final class CmsListStudioContentTypes {

  private static final Logger log = LoggerFactory.getLogger(CmsListStudioContentTypes)

  /**
   * Private constructor; not for direct use.
   */
private CmsListStudioContentTypes() {}
  /**
   * Studio relative path for content type listing.
   * @param repoPath Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String studioRelativePathForContentTypeListing(String repoPath) {
    String p = (repoPath ?: '').trim()
    if (!p.startsWith('/')) {
      p = '/' + p
    }
    if (p.startsWith('/site/')) {
      p = p.substring('/site/'.length())
    }
    if (p.toLowerCase(Locale.ROOT).endsWith('.xml')) {
      int s = p.lastIndexOf('/')
      if (s > 0) {
        p = p.substring(0, s)
      } else {
        p = ''
      }
    }
    p = p.replaceAll('/+$', '')
    return p
  }

  /**
   * Maps a Studio ContentType metadata object into a small LinkedHashMap.
   * Copies safe string fields only ; skips null rows.
   * Feeds sorted catalog responses for ListStudioContentTypes.
   */
  private static Map briefContentTypeConfigRow(Object ct) {
    Map m = new LinkedHashMap<>()
    if (ct == null) {
      return m
    }
    try {
      def n = ct.name
      if (n) m.name = n.toString().trim()
    } catch (Throwable ignored) {}
    try {
      def l = ct.label
      if (l) m.label = l.toString().trim()
    } catch (Throwable ignored) {}
    try {
      def u = ct.uri
      if (u) m.uri = u.toString().trim()
    } catch (Throwable ignored) {}
    try {
      def ty = ct.type
      if (ty) m.type = ty.toString().trim()
    } catch (Throwable ignored) {}
    try {
      def f = ct.form
      if (f) m.form = f.toString().trim()
    } catch (Throwable ignored) {}
    try {
      def im = ct.imageThumbnail
      if (im) m.imageThumbnail = im.toString().trim()
    } catch (Throwable ignored) {}
    return m
  }

  /**
   * Lists matching items for the model or author.
   * @param ops Caller-supplied input.
   * @param siteId Studio or repository context for this call.
   * @param searchable Caller-supplied input.
   * @param contentPath Studio or repository context for this call.
   * @return Map payload for tools or orchestration.
   */
  static Map list(StudioToolOperations ops, String siteId, boolean searchable, String contentPath) {
    ops.runWithStudioSecurity {
      siteId = ops.resolveEffectiveSiteId(siteId)
      if (ops.contentTypeServiceBean == null) {
        return [
          ok           : false,
          siteId       : siteId,
          contentTypes : [],
          message      :
            'Studio ContentTypeService bean not found (expected cstudioContentTypeService). Use GetContentTypeFormDefinition with contentPath or exact contentTypeId.',
          hint         :
            'If this persists, ask the platform team to confirm the ContentTypeService Spring bean id for this Studio line.'
        ]
      }
      String mode = 'all'
      List raw = []
      String rel = ''
      String cp = (contentPath ?: '').toString().trim()
      if (cp) {
        rel = studioRelativePathForContentTypeListing(cp)
        try {
          if (ops.contentTypeServiceBean.metaClass.respondsTo(ops.contentTypeServiceBean, 'getAllowedContentTypesForPath', String, String)) {
            def allowed = ops.contentTypeServiceBean.getAllowedContentTypesForPath(siteId, rel)
            raw = (allowed instanceof List) ? (List) allowed : []
            mode = 'allowedForPath'
          }
        } catch (Throwable t) {
          log.warn('listStudioContentTypes getAllowedContentTypesForPath site={} rel={}: {}', siteId, rel, t.message)
          raw = []
          mode = 'allowedForPath_error'
        }
        if (raw.isEmpty()) {
          try {
            def all = ops.contentTypeServiceBean.getAllContentTypes(siteId, searchable)
            raw = (all instanceof List) ? (List) all : []
            mode = 'all_fallback_no_allowed'
          } catch (Throwable t2) {
            throw new IllegalStateException("listStudioContentTypes getAllContentTypes failed: ${t2.message}", t2)
          }
        }
      } else {
        try {
          def all = ops.contentTypeServiceBean.getAllContentTypes(siteId, searchable)
          raw = (all instanceof List) ? (List) all : []
          mode = 'all'
        } catch (Throwable t) {
          throw new IllegalStateException("listStudioContentTypes getAllContentTypes failed: ${t.message}", t)
        }
      }
      def rows = []
      for (Object ct : raw) {
        def row = briefContentTypeConfigRow(ct)
        if (!row.isEmpty()) {
          rows.add(row)
        }
      }
      // `/page/...` before `/component/...` so page kinds are easier to scan; no hard-coded content-type keywords here.
      rows.sort { Map a, Map b ->
        String na = (a.name ?: '').toString()
        String nb = (b.name ?: '').toString()
        int pa = na.startsWith('/page/') ? 0 : (na.startsWith('/component/') ? 1 : 2)
        int pb = nb.startsWith('/page/') ? 0 : (nb.startsWith('/component/') ? 1 : 2)
        int c = pa <=> pb
        if (c != 0) {
          return c
        }
        return na <=> nb
      }
      return [
        ok           : true,
        siteId       : siteId,
        mode         : mode,
        searchable   : searchable,
        contentPath  : cp,
        relativePath : rel,
        count        : rows.size(),
        contentTypes : rows,
        hint         :
          '**Response `mode`:** **`all`** = full-site catalog (**`contentPath` was omitted** — **preferred** first call so authors see every type). **`allowedForPath`** / **`all_fallback_no_allowed`** = **`contentPath` was set**; Studio scoped types to that path’s parent folder (subset or fallback to all). **Do not** default the **first** **ListStudioContentTypes** call to **Current preview** `contentPath` unless you **only** need folder-scoped types — hub **`index.xml`** paths often confuse the subset vs the full catalog. After listing, you may **paste a short markdown table** of **`label`** + **`name`** in chat so the author sees what Studio offers. Each row has **name** (repository content-type id) and **label** (Studio UI). **Exact match only** (see system **Exact catalog match beats guessing**): normalize the author’s **type phrase** and each row’s **`label`**, **`name`**, and **`name`** tail after the final **`/`** (trim, Unicode lowercase, collapse spaces, **`/`** → space in **`label`** and phrase; **`-`**/**`_`** → space in **`name`** / tail). Use **`contentTypeId` = that row’s `name`** **only** when **exactly one** row **equals** the phrase — **do not** pick a catch-all type (e.g. **/page/page_generic**) when that single match exists. **Real sites:** a **section hub** `…/foo/index.xml` may still show `<content-type>` **/page/page_generic** (or another shell) while **child** `…/foo/<slug>/index.xml` items use a **narrower** `/page/…` — do **not** treat the hub’s type as the **new child** type when they differ. For **new** items, call **GetContentTypeFormDefinition** with the **resolved** **`contentTypeId`**; do **not** pass **contentPath** of that hub `index.xml` when creating a **different** type. For XML field shape, **one** **GetContent** on an **existing sibling** of the **same** `name` type. **`/site/components/…`** items are **`/component/…`** — use **GetContent**’s **`contentTypeIdFromXml`** for the next form-def on **that** file, not **`/page/page_generic`**. **Do not** call **GetContentTypeFormDefinition** for many unrelated **`/component/...`** types when the task is **one** new item. After **GetContentTypeFormDefinition** for a **create** target, **do not** call **ListPagesAndComponents** at large **size** — use **one** **GetContent** on a **sibling** of the **same** type instead. Do not use **ListPagesAndComponents** to discover content types.'
      ]
    }
  }

}
