package plugins.org.craftercms.aiassistant.tools.cms.support

import org.dom4j.Document
import org.dom4j.DocumentException
import org.dom4j.Element
import org.dom4j.io.SAXReader
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations
import plugins.org.craftercms.aiassistant.tools.cms.support.CmsRepositorySupport
import plugins.org.craftercms.aiassistant.tools.cms.support.CmsStudioPlaceholderImage

import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.LinkedHashSet
import java.util.Locale
import java.util.Set
import java.util.TimeZone
/** Repository write pipeline for {@link plugins.org.craftercms.aiassistant.tools.cms.WriteContentTool}. */
final class CmsWriteContent {

  private static final Logger log = LoggerFactory.getLogger(CmsWriteContent)

  private CmsWriteContent() {}

  private static String sanitizeUtf8BodyForXml10(String input) {
    if (input == null) return null
    StringBuilder sb = new StringBuilder(input.length())
    for (int i = 0; i < input.length(); ) {
      int cp = input.codePointAt(i)
      i += Character.charCount(cp)
      if (cp == 0x9 || cp == 0xA || cp == 0xD) {
        sb.appendCodePoint(cp)
      } else if (cp >= 0x20 && cp <= 0xD7FF) {
        sb.appendCodePoint(cp)
      } else if (cp >= 0xE000 && cp <= 0xFFFD) {
        sb.appendCodePoint(cp)
      } else if (cp >= 0x10000 && cp <= 0x10FFFF) {
        sb.appendCodePoint(cp)
      }
      // else drop: NUL, other C0 controls, lone surrogates, U+FFFE/U+FFFF
    }
    return sb.toString()
  }

  /**
   * Fixes common LLM misspellings in Crafter node-selector {@code <item>} markup before SAX parse.
   * A frequent failure mode is {@code </disableFlattenening>} instead of {@code </disableFlattening>},
   * which makes the entire {@code contentXml} non-well-formed and rejects {@link #writeContent}.
   */
  private static String normalizeCommonLlmXmlTagTypos(String xml) {
    if (xml == null) {
      return null
    }
    String s = xml.toString()
    s = s.replace('</disableFlattenening>', '</disableFlattening>')
    s = s.replace('<disableFlattenening>', '<disableFlattening>')
    s = s.replace('<disableFlattenening/>', '<disableFlattening/>')
    s = s.replace('<disableFlattenening ', '<disableFlattening ')
    return s
  }

  /**
   * Allocates dom4j SAXReader with validation disabled.
   * Turns on disallow-doctype and disables external entities where the parser supports those features.
   * Returns the reader for one-shot parses used by XML diagnostics and write guards.
   */
  private static SAXReader newHardenedSaxReader() {
    SAXReader reader = new SAXReader()
    reader.setValidation(false)
    try {
      reader.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
    } catch (Throwable ignored) {
    }
    try {
      reader.setFeature('http://xml.org/sax/features/external-general-entities', false)
    } catch (Throwable ignored) {
    }
    try {
      reader.setFeature('http://xml.org/sax/features/external-parameter-entities', false)
    } catch (Throwable ignored) {
    }
    reader
  }

  /**
   * Parses {@code xmlUtf8} with dom4j (same family Studio uses in the content pipeline) so we fail fast
   * with a clear tool error instead of a partial Studio pipeline failure after IO.
   * Skipped for non-{@code .xml} paths (e.g. {@code .ftl}) where the body is not XML.
   */
  /** {@code ==~} is full-string match in Groovy; Crafter items often have a license comment before {@code <page>}. */
  private static boolean xmlBodyContainsCrafterItemRootElement(String xmlUtf8) {
    String s = (xmlUtf8 ?: '').toString()
    if (!s.trim()) {
      return false
    }
    return (s =~ /(?is)<(page|component)(\s[^>]*)?>/).find() ||
      (s =~ /(?is)<(page|component)\s*\/>/).find()
  }

  /**
   * True when {@code xmlUtf8} looks like a full Crafter page/component item (same rules as write guard).
   */
  static boolean looksLikeFullCrafterSiteContentItemDocument(String pathLabel, String xmlUtf8) {
    if (!CmsRepositorySupport.isLikelyXmlRepositoryPath(pathLabel)) {
      return true
    }
    String p = (pathLabel ?: '').toString().trim().toLowerCase(Locale.ROOT)
    if (!p.startsWith('/site/')) {
      return true
    }
    String t = (xmlUtf8 ?: '').toString().trim()
    if (!t) {
      return false
    }
    if (!xmlBodyContainsCrafterItemRootElement(t)) {
      return false
    }
    if (!t.contains('<content-type>') && !t.contains('<file-name>') && !t.contains('<merge-strategy>')) {
      return false
    }
    return true
  }

  /**
   * Refuses field fragments or non-item bodies for {@code /site/.../*.xml} writes (common LLM failure mode that
   * passes SAX well-formedness but breaks Engine render with HTTP 500).
   */
  private static void assertCrafterSiteContentItemDocument(String pathLabel, String xmlUtf8) {
    if (!CmsRepositorySupport.isLikelyXmlRepositoryPath(pathLabel)) {
      return
    }
    String p = (pathLabel ?: '').toString().trim().toLowerCase(Locale.ROOT)
    if (!p.startsWith('/site/')) {
      return
    }
    String t = (xmlUtf8 ?: '').toString().trim()
    if (!t) {
      return
    }
    if (!looksLikeFullCrafterSiteContentItemDocument(pathLabel, t)) {
      if (!xmlBodyContainsCrafterItemRootElement(t)) {
        throw new IllegalArgumentException(
          "contentXml for '${pathLabel}' must be a full Crafter content item with root <page> or <component>, not a field fragment or partial snippet. " +
            'Call GetContent (or update_content), edit field values in place, then WriteContent the entire document.'
        )
      }
      throw new IllegalArgumentException(
        "contentXml for '${pathLabel}' is missing typical Crafter item markers (<content-type>, <file-name>, or <merge-strategy>). " +
          'Refusing to write — use ContentExists on the path; if exists=false this is a new item (GetContent on an existing sibling for shape, not this path); if exists=true, GetContent and send the full item XML.'
      )
    }
  }

  /**
   * Rejects empty bodies early with a tool-facing IllegalArgumentException.
   * Parses via hardened SAX reader so malformed XML surfaces before Studio pipeline writes.
   * Wraps parse failures with the repository path label for author-visible errors.
   */
  private static void assertWellFormedUtf8Xml(String pathLabel, String xmlUtf8) {
    if (xmlUtf8 == null || !xmlUtf8.toString().trim()) {
      throw new IllegalArgumentException(
        "contentXml is empty or whitespace-only for path '${pathLabel}'. Refusing to write an empty .xml file " +
          '(Engine/Studio fail with Premature end of file). Re-fetch with GetContent or update_content, then WriteContent the full document.'
      )
    }
    try {
      newHardenedSaxReader().read(new StringReader(xmlUtf8))
    } catch (DocumentException e) {
      throw new IllegalArgumentException("contentXml is not well-formed XML for path '${pathLabel}': ${e.message}", e)
    }
  }
  private static String extractContentTypeIdFromItemXml(String xmlUtf8) {
    if (!xmlUtf8) {
      return null
    }
    def m = (xmlUtf8 =~ /(?is)<(?:[A-Za-z0-9_.-]+:)?content-type\s*>\s*([^<]+?)\s*<\/(?:[A-Za-z0-9_.-]+:)?content-type\s*>/)
    if (m.find()) {
      String s = m.group(1)?.trim()
      return s ? s : null
    }
    null
  }

  /**
   * Walks image-picker property nodes on a dom4j field element.
   * Treats explicit readonly=true as locked.
   * Lets write/update tools refuse edits Studio forms mark immutable.
   */
  private static boolean formFieldImagePickerReadOnly(Element fieldEl) {
    if (fieldEl == null) {
      return false
    }
    try {
      Element props = fieldEl.element('properties')
      if (!props) {
        return false
      }
      for (Iterator it = props.elementIterator('property'); it.hasNext();) {
        Element p = (Element) it.next()
        if ('readonly'.equalsIgnoreCase(p.elementTextTrim('name') ?: '')) {
          return 'true'.equalsIgnoreCase(p.elementTextTrim('value') ?: '')
        }
      }
    } catch (Throwable ignored) {
    }
    false
  }

  /**
   * Inspects `<constraints>/<constraint>` entries for required=true.
   * Handles alternate Crafter casing (`Required`).
   * Feeds validation summaries before attempting destructive writes.
   */
  private static boolean formFieldHasRequiredConstraint(Element fieldEl) {
    if (fieldEl == null) {
      return false
    }
    Element constraints = fieldEl.element('constraints')
    if (!constraints) {
      return false
    }
    for (Iterator it = constraints.elementIterator('constraint'); it.hasNext();) {
      Element c = (Element) it.next()
      if (!'required'.equalsIgnoreCase(c.elementTextTrim('name') ?: '')) {
        continue
      }
      String v = c.elementText('value')
      if (v == null) {
        v = ''
      }
      v = v.trim()
      if ('true'.equalsIgnoreCase(v)) {
        return true
      }
      if (v.contains('true')) {
        return true
      }
    }
    false
  }

  /**
   * Reads checkbox-group properties mirroring Studio form semantics.
   * Honors explicit readonly toggles.
   * Pairs with datasource inspection for taxonomy-backed widgets.
   */
  private static boolean formFieldCheckboxGroupReadOnly(Element fieldEl) {
    if (fieldEl == null) {
      return false
    }
    try {
      Element props = fieldEl.element('properties')
      if (!props) {
        return false
      }
      for (Iterator it = props.elementIterator('property'); it.hasNext();) {
        Element p = (Element) it.next()
        if ('readonly'.equalsIgnoreCase(p.elementTextTrim('name') ?: '')) {
          return 'true'.equalsIgnoreCase(p.elementTextTrim('value') ?: '')
        }
      }
    } catch (Throwable ignored) {
    }
    false
  }

  /** Minimum selections from {@code <constraint><name>minSize</name>…} (grouped checkboxes). */
  private static int checkboxGroupMinSizeConstraint(Element fieldEl) {
    if (fieldEl == null) {
      return 0
    }
    Element constraints = fieldEl.element('constraints')
    if (!constraints) {
      return 0
    }
    for (Iterator it = constraints.elementIterator('constraint'); it.hasNext();) {
      Element c = (Element) it.next()
      if (!'minSize'.equalsIgnoreCase(c.elementTextTrim('name') ?: '')) {
        continue
      }
      String v = c.elementTextTrim('value')
      if (!v) {
        return 0
      }
      try {
        int n = Integer.parseInt(v.trim())
        return n > 0 ? n : 0
      } catch (Throwable ignored) {
        return 0
      }
    }
    0
  }

  /**
   * Selections required so Studio validation passes: {@code max(minSize, required ? 1 : 0)}.
   */
  private static int checkboxGroupNeededSelectionCount(Element fieldEl) {
    if (fieldEl == null) {
      return 0
    }
    int minSz = checkboxGroupMinSizeConstraint(fieldEl)
    int req = formFieldHasRequiredConstraint(fieldEl) ? 1 : 0
    Math.max(minSz, req)
  }

  /**
   * Pulls the itemManager datasource reference from checkbox-group properties.
   * Returns trimmed id or empty string when absent.
   * Used to locate backing taxonomy datasource metadata.
   */
  private static String checkboxGroupDatasourceId(Element fieldEl) {
    if (fieldEl == null) {
      return null
    }
    Element props = fieldEl.element('properties')
    if (!props) {
      return null
    }
    for (Iterator it = props.elementIterator('property'); it.hasNext();) {
      Element p = (Element) it.next()
      if (!'datasource'.equalsIgnoreCase(p.elementTextTrim('name') ?: '')) {
        continue
      }
      String raw = p.elementText('value')
      if (raw == null) {
        raw = ''
      }
      raw = raw.trim()
      if (!raw) {
        return null
      }
      raw = raw.replace('[', '').replace(']', '').replace('"', '').replace("'", '').trim()
      return raw ?: null
    }
    null
  }

  /**
   * Scans `<datasources>/<datasource>` children under the form root.
   * Matches dom4j ids case-insensitively.
   * Returns first hit or null when the datasource does not exist.
   */
  private static Element findFormDatasourceById(Element formRoot, String datasourceId) {
    if (formRoot == null || !datasourceId) {
      return null
    }
    Element dss = formRoot.element('datasources')
    if (!dss) {
      return null
    }
    for (Iterator it = dss.elementIterator('datasource'); it.hasNext();) {
      Element ds = (Element) it.next()
      if (datasourceId.equals(ds.elementTextTrim('id'))) {
        return ds
      }
    }
    null
  }

  /**
   * Finds `<property><name>...</name><value>...</value>` pairs on a datasource element.
   * Matches requested property names ignoring case.
   * Returns trimmed text values for taxonomy/repo path extraction.
   */
  private static String formDatasourcePropertyTrim(Element dsEl, String propName) {
    if (dsEl == null || !propName) {
      return null
    }
    Element props = dsEl.element('properties')
    if (!props) {
      return null
    }
    for (Iterator it = props.elementIterator('property'); it.hasNext();) {
      Element p = (Element) it.next()
      if (propName.equalsIgnoreCase(p.elementTextTrim('name') ?: '')) {
        String v = p.elementTextTrim('value')
        return v ?: null
      }
    }
    null
  }

  /**
   * Checks datasource type markers (`taxonomy`,`keywords`).
   * Reads booleans like `taxonomy`/`keywords` props when present.
   * Determines whether checkbox-group updates must honor taxonomy XML.
   */
  private static boolean isTaxonomyBackedDatasource(Element dsEl) {
    if (dsEl == null) {
      return false
    }
    String t = (dsEl.elementTextTrim('type') ?: '').toLowerCase(Locale.ROOT)
    t.contains('taxonomy')
  }

  /**
   * Child element name for each selected checkbox value (Engine / GraphQL convention), from datasource {@code dataType}.
   */
  private static String checkboxValueElementNameForDataType(String dataTypeRaw) {
    if (!dataTypeRaw) {
      return 'value_smv'
    }
    String dt = dataTypeRaw.trim().toLowerCase(Locale.ROOT)
    if ('string'.equals(dt)) {
      return 'value_smv'
    }
    if ('value'.equals(dt)) {
      return 'value'
    }
    if ('float'.equals(dt) || 'double'.equals(dt)) {
      return 'value_fmv'
    }
    if ('integer'.equals(dt) || 'int'.equals(dt) || 'long'.equals(dt)) {
      return 'value_imv'
    }
    if ('html'.equals(dt)) {
      return 'value_htmlmv'
    }
    if ('date'.equals(dt) || 'datetime'.equals(dt)) {
      return 'value_dtmv'
    }
    'value_smv'
  }

  /**
   * Reads repository path hints from datasource properties.
   * Normalizes `/site`-relative conventions.
   * Feeds expandSiteTaxonomyPathCandidates with the author's configured folder.
   */
  private static String resolveTaxonomyRepoPathFromDatasource(Element dsEl) {
    if (dsEl == null) {
      return null
    }
    String[] keys = ['componentPath', 'repoPath', 'rootPath', 'baseRepositoryPath'] as String[]
    for (String k : keys) {
      String v = formDatasourcePropertyTrim(dsEl, k)
      if (v?.trim()) {
        return v.trim()
      }
    }
    null
  }

  /**
   * Generates plausible `/site/...` variants for taxonomy XML paths.
   * Adds `/site`-prefixed copies when authors omit the prefix.
   * Lets lookups survive slight Studio mis-configurations.
   */
  private static List<String> expandSiteTaxonomyPathCandidates(String rawPath) {
    if (!rawPath?.trim()) {
      return []
    }
    String p = rawPath.trim()
    if (!p.startsWith('/')) {
      p = "/${p}"
    }
    if (!p.startsWith('/site/')) {
      return []
    }
    LinkedHashSet<String> out = new LinkedHashSet<>()
    out.add(p)
    if (!p.toLowerCase(Locale.ROOT).endsWith('.xml')) {
      out.add(p.endsWith('/') ? "${p}index.xml" : "${p}/index.xml")
      out.add("${p}.xml")
    }
    out.toList()
  }

  /**
   * Depth-first searches Element children ignoring namespaces.
   * Matches dom4j local names case-sensitively.
   * Returns first matching element or null.
   */
  private static Element findFirstDescendantByLocalName(Element root, String local) {
    if (root == null || !local) {
      return null
    }
    if (local == root.getQName().getName()) {
      return root
    }
    for (Iterator it = root.elementIterator(); it.hasNext();) {
      Element child = (Element) it.next()
      Element found = findFirstDescendantByLocalName(child, local)
      if (found != null) {
        return found
      }
    }
    null
  }

  /**
   * Parses simple taxonomy / KVP list XML: first {@code <items>} block with {@code <item><key/>…<value/>…} children.
   */
  private static List<Map> parseTaxonomyKeyLabelPairs(String taxonomyXml) {
    List<Map> pairs = []
    if (!taxonomyXml?.trim()) {
      return pairs
    }
    Document doc
    try {
      doc = newHardenedSaxReader().read(new StringReader(taxonomyXml.toString()))
    } catch (Throwable t) {
      return pairs
    }
    Element items = findFirstDescendantByLocalName(doc.getRootElement(), 'items')
    if (items == null) {
      return pairs
    }
    for (Iterator it = items.elementIterator('item'); it.hasNext();) {
      Element item = (Element) it.next()
      String key = item.elementTextTrim('key')
      if (!key) {
        continue
      }
      String label = item.elementTextTrim('value')
      if (!label) {
        label =
          item.elementTextTrim('value_s') ?:
            item.elementTextTrim('value_smv') ?:
              item.elementTextTrim('label') ?:
                key
      }
      pairs.add([key: key, label: label])
    }
    pairs
  }

  /**
   * Collects `<value>` tokens already stored under a checkbox-group fragment.
   * Handles `<item>` wrappers Crafter emits.
   * Prevents duplicate taxonomy inserts during merges.
   */
  private static Set<String> existingCheckboxGroupKeys(Element fieldRoot) {
    Set<String> keys = new LinkedHashSet<>()
    if (fieldRoot == null) {
      return keys
    }
    for (Iterator it = fieldRoot.elementIterator('item'); it.hasNext();) {
      Element row = (Element) it.next()
      String k = row.elementTextTrim('key')
      if (k) {
        keys.add(k)
      }
    }
    keys
  }

  /**
   * Collects top-level {@code checkbox-group} fields that need at least one taxonomy-backed selection for save validation.
   */
  private static void collectTopLevelCheckboxGroupTaxonomyFillTargets(Element el, boolean insideRepeat, List<Map> sink) {
    if (el == null || sink == null) {
      return
    }
    boolean isField = 'field'.equals(el.getQName().getName())
    if (!isField) {
      el.elements().each { collectTopLevelCheckboxGroupTaxonomyFillTargets(it, insideRepeat, sink) }
      return
    }
    String t = el.elementTextTrim('type')
    if ('repeat'.equals(t)) {
      Element fields = el.element('fields')
      if (fields != null) {
        fields.elements().each { collectTopLevelCheckboxGroupTaxonomyFillTargets(it, true, sink) }
      }
      return
    }
    if (!insideRepeat && 'checkbox-group'.equals(t) && !formFieldCheckboxGroupReadOnly(el)) {
      int needed = checkboxGroupNeededSelectionCount(el)
      if (needed > 0) {
        String fid = el.elementTextTrim('id')
        String dsId = checkboxGroupDatasourceId(el)
        if (fid && dsId) {
          sink.add([id: fid, needed: needed, datasourceId: dsId])
        }
      }
    }
    el.elements().each { collectTopLevelCheckboxGroupTaxonomyFillTargets(it, insideRepeat, sink) }
  }

  /**
   * Best-effort read of a {@code /site/...} XML file for taxonomy / KVP lists (never throws).
   */
  private static String tryReadSiteContentUtf8(StudioToolOperations ops, String siteId, String repoPath, String ref) {
    if (!siteId || !repoPath?.trim()) {
      return null
    }
    try {
      String normalized = CmsRepositorySupport.normalizeLeadingSlash(repoPath, 'path')
      def r = (ref ?: CmsRepositorySupport.CONTENT_REF_HEAD).toString().trim() ?: CmsRepositorySupport.CONTENT_REF_HEAD
      def optional = ops.contentServiceBean.getContentByCommitId(siteId, normalized, r)
      if (optional == null || !optional.isPresent()) {
        return null
      }
      def resource = optional.get()
      String xml = CmsRepositorySupport.slurpInputStreamUtf8(resource.getInputStream())
      return xml?.trim() ? xml.toString() : null
    } catch (Throwable t) {
      log.debug('tryReadSiteContentUtf8 failed siteId={} path={}: {}', siteId, repoPath, t.message)
      return null
    }
  }

  /**
   * Fills missing selections for required / {@code minSize} top-level {@code checkbox-group} fields whose datasource
   * type references taxonomy (e.g. {@code simple-taxonomy}), using keys/labels from the datasource’s site XML list.
   */
  private static String applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded(StudioToolOperations ops, String siteId, String normalizedRepoPath, String xmlUtf8) {
    if (!xmlUtf8 || !normalizedRepoPath?.startsWith('/site/')) {
      return xmlUtf8
    }
    if (!normalizedRepoPath.toLowerCase(Locale.ROOT).endsWith('.xml')) {
      return xmlUtf8
    }
    String ct = extractContentTypeIdFromItemXml(xmlUtf8)
    if (!ct?.trim()) {
      log.debug('applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded: no content-type in path={}', normalizedRepoPath)
      return xmlUtf8
    }
    ct = ct.trim()
    if (!ct.startsWith('/')) {
      ct = "/${ct}"
    }
    String cfgPath = "/content-types${ct}/form-definition.xml"
    String formXml
    try {
      formXml = ops.configurationServiceBean.getConfigurationAsString(siteId, 'studio', cfgPath, '')
    } catch (Throwable t) {
      log.debug('applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded: could not load form {}: {}', cfgPath, t.message)
      return xmlUtf8
    }
    if (!formXml?.trim()) {
      return xmlUtf8
    }
    Document formDoc
    Document itemDoc
    try {
      formDoc = newHardenedSaxReader().read(new StringReader(formXml.toString()))
    } catch (Throwable t) {
      log.debug('applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded: form parse failed {}: {}', cfgPath, t.message)
      return xmlUtf8
    }
    try {
      itemDoc = newHardenedSaxReader().read(new StringReader(xmlUtf8.toString()))
    } catch (Throwable t) {
      log.debug('applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded: item parse failed path={}: {}', normalizedRepoPath, t.message)
      return xmlUtf8
    }
    Element formRoot = formDoc.getRootElement()
    List<Map> targets = []
    collectTopLevelCheckboxGroupTaxonomyFillTargets(formRoot, false, targets)
    if (targets.isEmpty()) {
      return xmlUtf8
    }
    Element root = itemDoc.getRootElement()
    if (root == null) {
      return xmlUtf8
    }
    boolean anyCheckboxFill = false
    for (Map spec : targets) {
      String fieldId = spec.id?.toString()?.trim()
      int needed = (spec.needed instanceof Number) ? ((Number) spec.needed).intValue() : 0
      String dsId = spec.datasourceId?.toString()?.trim()
      if (!fieldId || needed <= 0 || !dsId) {
        continue
      }
      Element dsEl = findFormDatasourceById(formRoot, dsId)
      if (dsEl == null || !isTaxonomyBackedDatasource(dsEl)) {
        continue
      }
      String taxPath = resolveTaxonomyRepoPathFromDatasource(dsEl)
      if (!taxPath) {
        log.debug(
          'applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded: no component/repo path on taxonomy datasource id={} field={}',
          dsId, fieldId
        )
        continue
      }
      String taxXml = null
      for (String cand : expandSiteTaxonomyPathCandidates(taxPath)) {
        taxXml = tryReadSiteContentUtf8(ops, siteId, cand, CmsRepositorySupport.CONTENT_REF_HEAD)
        if (taxXml) {
          break
        }
      }
      if (!taxXml) {
        log.debug(
          'applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded: could not read taxonomy XML for field={} ds={} pathHint={}',
          fieldId, dsId, taxPath
        )
        continue
      }
      List<Map> pairs = parseTaxonomyKeyLabelPairs(taxXml)
      if (pairs.isEmpty()) {
        continue
      }
      String dataTypeProp = formDatasourcePropertyTrim(dsEl, 'dataType')
      String valueTag = checkboxValueElementNameForDataType(dataTypeProp)

      Element fieldRoot = findDirectChildByLocalName(root, fieldId)
      Set<String> haveKeys = fieldRoot != null ? existingCheckboxGroupKeys(fieldRoot) : new LinkedHashSet<>()
      int deficit = needed - haveKeys.size()
      if (deficit <= 0) {
        continue
      }
      if (fieldRoot == null) {
        fieldRoot = root.addElement(fieldId)
        fieldRoot.addAttribute('item-list', 'true')
      } else {
        String il = fieldRoot.attributeValue('item-list')
        if (il == null || !'true'.equalsIgnoreCase(il)) {
          fieldRoot.addAttribute('item-list', 'true')
        }
      }
      int added = 0
      for (Map pair : pairs) {
        if (added >= deficit) {
          break
        }
        String k = pair.get('key')?.toString()
        if (!k) {
          continue
        }
        if (haveKeys.contains(k)) {
          continue
        }
        Element row = fieldRoot.addElement('item')
        row.addElement('key').setText(k)
        String lab = pair.get('label') != null ? pair.get('label').toString() : k
        row.addElement(valueTag).setText(lab)
        haveKeys.add(k)
        added++
      }
      if (added > 0) {
        anyCheckboxFill = true
        log.info(
          'applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded: added {} checkbox item(s) for field={} (needed≈{}) siteId={} path={}',
          added, fieldId, needed, siteId, normalizedRepoPath
        )
      }
    }
    anyCheckboxFill ? itemDoc.asXML() : xmlUtf8
  }

  /**
   * Collects required {@code image-picker} fields not nested under a {@code repeat} (same flat root shape as page/component XML).
   */
  private static void collectTopLevelRequiredImagePickers(Element el, boolean insideRepeat, List<Map> sink) {
    if (el == null || sink == null) {
      return
    }
    boolean isField = 'field'.equals(el.getQName().getName())
    if (!isField) {
      el.elements().each { collectTopLevelRequiredImagePickers(it, insideRepeat, sink) }
      return
    }
    String t = el.elementTextTrim('type')
    if ('repeat'.equals(t)) {
      Element fields = el.element('fields')
      if (fields != null) {
        fields.elements().each { collectTopLevelRequiredImagePickers(it, true, sink) }
      }
      return
    }
    if (!insideRepeat && 'image-picker'.equals(t) && formFieldHasRequiredConstraint(el) && !formFieldImagePickerReadOnly(el)) {
      String fid = el.elementTextTrim('id')
      if (fid) {
        sink.add([id: fid])
      }
    }
    el.elements().each { collectTopLevelRequiredImagePickers(it, insideRepeat, sink) }
  }

  /**
   * Iterates immediate Element children for a matching local name.
   * Skips mixed-content noise beyond elements.
   * Supports surgical DOM edits without full XPath engines.
   */
  private static Element findDirectChildByLocalName(Element root, String localName) {
    if (root == null || !localName) {
      return null
    }
    for (Iterator it = root.elementIterator(); it.hasNext();) {
      Element e = (Element) it.next()
      if (localName == e.getQName().getName()) {
        return e
      }
    }
    null
  }

  /**
   * Fills missing, empty, or 1×1-stub required top-level {@code image-picker} elements with an XB-style
   * {@code data:image/png;base64,...} placeholder (generated in-process — no repository path).
   */
  private static String applyRequiredImagePickerDataUrlDefaultsIfNeeded(StudioToolOperations ops, String siteId, String normalizedRepoPath, String xmlUtf8) {
    if (!xmlUtf8 || !normalizedRepoPath?.startsWith('/site/')) {
      return xmlUtf8
    }
    if (!normalizedRepoPath.toLowerCase(Locale.ROOT).endsWith('.xml')) {
      return xmlUtf8
    }
    String ct = extractContentTypeIdFromItemXml(xmlUtf8)
    if (!ct?.trim()) {
      log.debug('applyRequiredImagePickerDataUrlDefaultsIfNeeded: no content-type in path={}', normalizedRepoPath)
      return xmlUtf8
    }
    ct = ct.trim()
    if (!ct.startsWith('/')) {
      ct = "/${ct}"
    }
    String cfgPath = "/content-types${ct}/form-definition.xml"
    String formXml
    try {
      formXml = ops.configurationServiceBean.getConfigurationAsString(siteId, 'studio', cfgPath, '')
    } catch (Throwable t) {
      log.debug('applyRequiredImagePickerDataUrlDefaultsIfNeeded: could not load form {}: {}', cfgPath, t.message)
      return xmlUtf8
    }
    if (!formXml?.trim()) {
      return xmlUtf8
    }
    Document formDoc
    Document itemDoc
    try {
      formDoc = newHardenedSaxReader().read(new StringReader(formXml.toString()))
    } catch (Throwable t) {
      log.debug('applyRequiredImagePickerDataUrlDefaultsIfNeeded: form parse failed {}: {}', cfgPath, t.message)
      return xmlUtf8
    }
    try {
      itemDoc = newHardenedSaxReader().read(new StringReader(xmlUtf8.toString()))
    } catch (Throwable t) {
      log.debug('applyRequiredImagePickerDataUrlDefaultsIfNeeded: item parse failed path={}: {}', normalizedRepoPath, t.message)
      return xmlUtf8
    }
    Element formRoot = formDoc.getRootElement()
    List<Map> targets = []
    collectTopLevelRequiredImagePickers(formRoot, false, targets)
    if (targets.isEmpty()) {
      return xmlUtf8
    }
    Element root = itemDoc.getRootElement()
    if (root == null) {
      return xmlUtf8
    }
    String dataUrl = CmsStudioPlaceholderImage.defaultRequiredEmptyImagePickerDataUrl()
    int filled = 0
    for (Map spec : targets) {
      String id = spec.id?.toString()?.trim()
      if (!id) {
        continue
      }
      Element child = findDirectChildByLocalName(root, id)
      if (child != null) {
        String existing = child.getTextTrim()
        if (existing && !CmsStudioPlaceholderImage.isMinimalStubDataUrl(existing)) {
          continue
        }
        child.setText(dataUrl)
        filled++
      } else {
        root.addElement(id).setText(dataUrl)
        filled++
      }
    }
    if (filled == 0) {
      return xmlUtf8
    }
    log.info(
      'applyRequiredImagePickerDataUrlDefaultsIfNeeded: set {} required image-picker field(s) to sample placeholder siteId={} path={} contentType={}',
      filled, siteId, normalizedRepoPath, ct
    )
    itemDoc.asXML()
  }
  static Map write(StudioToolOperations ops, String siteId, String path, String contentXml, String unlock = 'true') {
    ops.runWithStudioSecurity {
      siteId = ops.resolveEffectiveSiteId(siteId)
      def normalized = CmsRepositorySupport.normalizeLeadingSlash(path, 'path')
      if (!contentXml?.toString()?.trim()) throw new IllegalArgumentException('Missing required field: contentXml')
      String rawBody = contentXml.toString()
      String safeBody = sanitizeUtf8BodyForXml10(rawBody)
      if (!safeBody.equals(rawBody)) {
        log.warn(
          'writeContent: removed illegal XML 1.0 character(s) (e.g. U+0000) from tool body before Studio parse: siteId={} path={}',
          siteId, normalized
        )
      }
      if (CmsRepositorySupport.isLikelyXmlRepositoryPath(normalized)) {
        String typoFixed = normalizeCommonLlmXmlTagTypos(safeBody)
        if (!typoFixed.equals(safeBody)) {
          log.warn(
            'writeContent: normalized common LLM XML tag typo(s) before well-formed check: siteId={} path={}',
            siteId, normalized
          )
          safeBody = typoFixed
        }
        try {
          String withDataUrl = applyRequiredImagePickerDataUrlDefaultsIfNeeded(ops, siteId, normalized, safeBody)
          if (withDataUrl != null && !withDataUrl.equals(safeBody)) {
            log.info('writeContent: applied XB-style data URL for required empty image-picker(s) siteId={} path={}', siteId, normalized)
            safeBody = withDataUrl
          }
        } catch (Throwable t) {
          log.warn('writeContent: applyRequiredImagePickerDataUrlDefaultsIfNeeded skipped (non-fatal): {}', t.message)
        }
        try {
          String withCb = applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded(ops, siteId, normalized, safeBody)
          if (withCb != null && !withCb.equals(safeBody)) {
            log.info('writeContent: applied taxonomy defaults for required checkbox-group field(s) siteId={} path={}', siteId, normalized)
            safeBody = withCb
          }
        } catch (Throwable t) {
          log.warn('writeContent: applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded skipped (non-fatal): {}', t.message)
        }
      }
      if (!safeBody.trim()) {
        throw new IllegalArgumentException(
          'contentXml became empty after removing illegal XML 1.0 characters (e.g. U+0000 and other disallowed controls). ' +
            'Refusing to write an empty file. Re-send a full UTF-8 document with real element markup (use GetContent / update_content as the base).'
        )
      }
      if (CmsRepositorySupport.isLikelyXmlRepositoryPath(normalized)) {
        assertCrafterSiteContentItemDocument(normalized, safeBody)
        assertWellFormedUtf8Xml(normalized, safeBody)
      }
      byte[] bytes = safeBody.getBytes(StandardCharsets.UTF_8)
      boolean unlockAfterWrite = !(unlock != null && unlock.toString().equalsIgnoreCase('false'))
      def result = ops.writeRepositoryFile(siteId, normalized, bytes, unlockAfterWrite)
      result.unlock = unlockAfterWrite
      result
    }
  }

}
