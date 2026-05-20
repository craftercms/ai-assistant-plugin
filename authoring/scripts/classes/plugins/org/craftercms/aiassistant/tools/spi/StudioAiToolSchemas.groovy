package plugins.org.craftercms.aiassistant.tools.spi

/**
 * OpenAI-compatible JSON Schema strings for built-in tools (must match {@link plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools}).
 */
final class StudioAiToolSchemas {

  private StudioAiToolSchemas() {}

  static final String GET_CONTENT =
    '{"type":"object","properties":{"siteId":{"type":"string","description":"Studio site id"},"path":{"type":"string","description":"Repository path starting with /"},"contentPath":{"type":"string","description":"Same as path; use whichever matches your context (e.g. after update_content)."},"commitId":{"type":"string","description":"Git commit id or ref to read (optional). Omit or use HEAD for current sandbox file; set only when comparing or inspecting history."}},"required":["siteId"]}'

  static final String GET_CONTENT_TYPE =
    '{"type":"object","properties":{"siteId":{"type":"string"},"contentPath":{"type":"string","description":"Repository path to page/component XML (e.g. /site/website/index.xml). Preferred: server reads exact <content-type> from this file — avoids guessing /page/index from filename."},"contentTypeId":{"type":"string","description":"Exact /page/... or /component/... from the item XML <content-type> element only. Never infer from path (index.xml is NOT /page/index)."}},"required":["siteId"]}'

  static final String LIST_STUDIO_CONTENT_TYPES =
    '{"type":"object","properties":{"siteId":{"type":"string","description":"Studio site id"},"searchable":{"type":"boolean","description":"If true, pass searchable=true to Studio getAllContentTypes when listing all types (site-dependent). Default false."},"contentPath":{"type":"string","description":"Optional repository path (e.g. open preview item or target folder). When set, lists content types **allowed** for creating items under that path’s folder (via Studio getAllowedContentTypesForPath)."}},"required":["siteId"]}'

  static final String GET_CONTENT_VERSION_HISTORY =
    '{"type":"object","properties":{"siteId":{"type":"string","description":"Studio site id"},"path":{"type":"string","description":"Repository path starting with /"},"contentPath":{"type":"string","description":"Same as path"}},"required":["siteId"]}'

  static final String GET_PREVIEW_HTML =
    '{"type":"object","properties":{"url":{"type":"string","description":"Absolute http(s) URL of the preview page to fetch (e.g. current preview URL from authoring context)."},"previewUrl":{"type":"string","description":"Alias for url."},"previewToken":{"type":"string","description":"Value of the Studio crafterPreview cookie (often starts with CCE-V1). Omit if the chat request already sent previewToken from the UI."},"siteId":{"type":"string","description":"Optional — when the URL has no crafterSite= query param, it is appended from this value or the active site."}},"required":[]}'

  static final String FETCH_HTTP_URL =
    '{"type":"object","properties":{"url":{"type":"string","description":"Absolute http(s) URL to GET (reference HTML/CSS/JSON/text). Private IPs, loopback, and metadata endpoints are blocked; each redirect target is re-validated."},"maxChars":{"type":"integer","description":"Optional cap on returned body size; still bounded by Studio JVM aiassistant.httpFetch.maxChars (default 400000)."}},"required":["url"]}'

  static final String WEB_SEARCH =
    '{"type":"object","properties":{"query":{"type":"string","description":"Search query (news, current events, or open-web facts)."},"q":{"type":"string","description":"Alias for query."},"maxResults":{"type":"integer","description":"Max results (1–15, default 8)."}},"required":["query"]}'

  static final String SERP_API_WEB_SEARCH =
    '{"type":"object","properties":{"query":{"type":"string","description":"Search query (news, current events, or open-web facts)."},"q":{"type":"string","description":"Alias for query."},"maxResults":{"type":"integer","description":"Max organic results (1–20; site defaults apply)."},"engine":{"type":"string","description":"SerpAPI engine (default google from site tools.json)."},"googleDomain":{"type":"string","description":"Google domain, e.g. google.com."},"gl":{"type":"string","description":"Country code for results, e.g. us."},"hl":{"type":"string","description":"Interface language, e.g. en."},"location":{"type":"string","description":"Geo location string for Google, e.g. United States."},"num":{"type":"integer","description":"Result count override (SerpAPI num)."},"start":{"type":"integer","description":"Pagination offset (SerpAPI start)."},"device":{"type":"string","description":"desktop | tablet | mobile."},"safe":{"type":"string","description":"Safe search: active | off."},"tbm":{"type":"string","description":"Google vertical, e.g. nws for news."},"tbs":{"type":"string","description":"Time filter, e.g. qdr:d for past day."}},"required":["query"]}'

  static final String RESEARCH_SITE_CONTENT =
    '{"type":"object","properties":{"siteId":{"type":"string","description":"Studio site id"},"query":{"type":"string","description":"Keywords to search indexed site pages and components (authoring OpenSearch)."},"q":{"type":"string","description":"Alias for query."},"maxSearchHits":{"type":"integer","description":"Max OpenSearch hits (1–30, default JVM aiassistant.siteContentResearch.maxSearchHits)."},"maxFetchItems":{"type":"integer","description":"How many top hits to load full XML excerpts for (0–10, default aiassistant.siteContentResearch.maxFetchItems)."},"pathPrefix":{"type":"string","description":"Optional localId prefix filter (default /site/)."}},"required":["siteId","query"]}'

  static final String WRITE_CONTENT =
    '{"type":"object","properties":{"siteId":{"type":"string"},"path":{"type":"string","description":"Repository path starting with /"},"contentPath":{"type":"string","description":"Same as path; use either."},"contentXml":{"type":"string","description":"Complete file body. For /site/.../*.xml items: full <page> or <component> document preserving existing field element names from the content type; never replace with an unrelated XML schema."},"unlock":{"type":"string","description":"true or false"}},"required":["siteId","contentXml"]}'

  static final String LIST_PAGES =
    '{"type":"object","properties":{"siteId":{"type":"string"},"size":{"type":"integer","description":"max items, default 1000"}},"required":["siteId"]}'

  static final String LIST_CONTENT_DEPENDENCY_SCOPE =
    '{"type":"object","properties":{"siteId":{"type":"string"},"contentPath":{"type":"string","description":"Root page or component XML under /site/... ending in .xml"},"path":{"type":"string","description":"Alias for contentPath"},"chunkSize":{"type":"integer","description":"Paths per batch for GetContent/WriteContent rounds (default 1 — one item at a time; max 50)"},"maxItems":{"type":"integer","description":"Optional max items in scope (default 300, cap 2000)"},"maxDepth":{"type":"integer","description":"Optional max reference depth from root (default 40, cap 100)"}},"required":["siteId"]}'

  static final String CRAFTERIZING_PLAYBOOK =
    '{"type":"object","properties":{"topic":{"type":"string","description":"Optional focus keyword for future use; full playbook is returned regardless."}}}'

  static final String CMS_LOOSE =
    '{"type":"object","properties":{"siteId":{"type":"string"},"site_id":{"type":"string"},"path":{"type":"string"},"contentPath":{"type":"string"},"paths":{"type":"array","items":{"type":"string"},"description":"Repository paths for publishScope=paths (one deploy)"},"contentPaths":{"type":"array","items":{"type":"string"},"description":"Alias for paths"},"publishScope":{"type":"string","description":"item | paths | bulk | all"},"publishMode":{"type":"string","description":"Alias for publishScope"},"publishEntireSite":{"type":"boolean"},"publishAll":{"type":"boolean"},"publishEverything":{"type":"boolean"},"bulkRootPath":{"type":"string","description":"Subtree root for publishScope=bulk (default /site)"},"bulkPath":{"type":"string","description":"Alias for bulkRootPath"},"submissionComment":{"type":"string"},"comment":{"type":"string","description":"Alias for submissionComment"},"templatePath":{"type":"string"},"contentType":{"type":"string"},"contentTypeId":{"type":"string"},"instructions":{"type":"string"},"date":{"type":"string"},"publishingTarget":{"type":"string"},"revertType":{"type":"string"},"version":{"type":"string","description":"Studio ItemVersion versionNumber from GetContentVersionHistory"},"revertToPrevious":{"type":"boolean","description":"If true, revert to the immediate prior revertible version (no version string needed)"},"revertToInitial":{"type":"boolean","description":"If true, revert to the oldest revertible version in history (e.g. initial commit)"},"contentContains":{"type":"array","items":{"type":"string"},"description":"Distinct phrases; server picks newest history version whose body contains all of them"},"contentFieldId":{"type":"string","description":"Optional element id from GetContentTypeFormDefinition when scoping contentContains to one field"}}}'
}
