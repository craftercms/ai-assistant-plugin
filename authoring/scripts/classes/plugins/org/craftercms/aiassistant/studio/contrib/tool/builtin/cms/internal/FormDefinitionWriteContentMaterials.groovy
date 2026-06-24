package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal

import groovy.json.JsonSlurper
import org.dom4j.Document
import org.dom4j.DocumentException
import org.dom4j.Element
import org.dom4j.io.SAXReader
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations

import java.io.StringReader
import java.util.Locale

/**
 * Builds WriteContent authoring materials from a form definition: standard Crafter envelope fields,
 * taxonomy checkbox-group keys from datasource XML, and repeat-group shape hints.
 */
final class FormDefinitionWriteContentMaterials {

  private FormDefinitionWriteContentMaterials() {}

  /**
   * Full materials map for prefetch / GetContentTypeFormDefinition tool responses.
   */
  static Map build(
    StudioToolOperations ops,
    String siteId,
    String formDefinitionXml,
    String suggestedRepoPath,
    Map validationPlan = null
  ) {
    Map plan = validationPlan instanceof Map && validationPlan ?
      validationPlan :
      FormDefinitionWriteContentValidator.buildValidationPlan(formDefinitionXml) as Map
    String objectType = (plan.objectType ?: 'page').toString().trim().toLowerCase(Locale.ROOT)
    boolean websitePage = 'page'.equals(objectType) ||
      (suggestedRepoPath ?: '').toString().contains('/site/website/')

    Map out = new LinkedHashMap<>()
    out.standardEnvelope = standardEnvelopeSpec(objectType, websitePage, suggestedRepoPath)
    out.taxonomyBindings = loadTaxonomyBindings(ops, siteId, formDefinitionXml)
    out.repeatBindings = loadRepeatBindings(formDefinitionXml)
    out.formValidationPlan = plan
    out.authoringMarkdown = formatAuthoringMarkdown(out, plan)
    out
  }

  /**
   * Hotpath lines for repeat groups — field ids come from the form definition, not site conventions.
   */
  static String formatRepeatBindingsHotpathHint(Object repeatBindings) {
    if (!(repeatBindings instanceof List) || ((List) repeatBindings).isEmpty()) {
      return 'Long-form copy belongs in **repeat groups** listed in **writeContentMaterials.repeatBindings** / **GetContentTypeFormDefinition** — not generic `<body>` or unlisted root elements.\n'
    }
    StringBuilder sb = new StringBuilder()
    sb.append('**Repeat groups** (exact ids from form definition — not generic `<body>`):\n')
    for (Object o : (List) repeatBindings) {
      if (!(o instanceof Map)) {
        continue
      }
      Map rb = (Map) o
      String fieldId = (rb.fieldId ?: '').toString().trim()
      List nested = rb.nestedFieldIds instanceof List ? (List) rb.nestedFieldIds : []
      if (!fieldId) {
        continue
      }
      sb.append('- **`').append(fieldId).append('`** minOccurs ')
        .append(rb.minOccurs != null ? rb.minOccurs : 1)
      if (nested) {
        sb.append('; nested: `').append(nested.join('`, `')).append('`')
      }
      if (rb.repeatExampleXml) {
        sb.append(' — example: `').append(rb.repeatExampleXml).append('`')
      }
      sb.append('\n')
    }
    return sb.toString()
  }

  /**
   * When migrating misplaced LLM content into a repeat item, prefer nested {@code *_html} fields from the form.
   */
  static String pickNestedFieldForMigration(List nestedFieldIds) {
    if (!(nestedFieldIds instanceof List) || nestedFieldIds.isEmpty()) {
      return ''
    }
    for (Object o : nestedFieldIds) {
      String id = (o ?: '').toString().trim()
      if (id.endsWith('_html')) {
        return id
      }
    }
    return nestedFieldIds.get(0)?.toString()?.trim() ?: ''
  }

  /**
   * Compact shape for prefetch JSON envelopes (keys + examples, not full taxonomy XML).
   */
  static Map compactForPrefetchEnvelope(Map materials) {
    if (!(materials instanceof Map) || materials.isEmpty()) {
      return [:]
    }
    Map compact = new LinkedHashMap<>()
    if (materials.standardEnvelope instanceof Map) {
      compact.standardEnvelope = materials.standardEnvelope
    }
    List tax = []
    if (materials.taxonomyBindings instanceof List) {
      for (Object o : (List) materials.taxonomyBindings) {
        if (!(o instanceof Map)) {
          continue
        }
        Map row = new LinkedHashMap<>((Map) o)
        row.remove('taxonomyXmlChars')
        tax.add(row)
      }
    }
    compact.taxonomyBindings = tax
    compact.repeatBindings = materials.repeatBindings instanceof List ?
      new ArrayList<>((List) materials.repeatBindings) : []
    if (materials.formValidationPlan instanceof Map) {
      compact.formValidationPlan = materials.formValidationPlan
    }
    return compact
  }

  private static Map standardEnvelopeSpec(String objectType, boolean websitePage, String suggestedRepoPath) {
    String path = (suggestedRepoPath ?: '').toString().trim()
    String folderSlug = extractFolderSlugFromIndexXmlPath(path)
    String root = 'component'.equals(objectType) ? 'component' : 'page'
    List<String> structural = new ArrayList<>(
      [
        'content-type', 'display-template', 'merge-strategy', 'objectId', 'objectGroupId',
        'internal-name', 'file-name', 'createdDate', 'createdDate_dt', 'lastModifiedDate', 'lastModifiedDate_dt'
      ]
    )
    if (websitePage && path.toLowerCase(Locale.ROOT).endsWith('/index.xml')) {
      structural.add('folder-name')
    }
    Map spec = new LinkedHashMap<>()
    spec.rootElement = root
    spec.requiredStructuralElements = structural
    spec.fileName = path.toLowerCase(Locale.ROOT).endsWith('/index.xml') ? 'index.xml' : ''
    spec.folderName = folderSlug
    spec.suggestedRepoPath = path
    spec.hint =
      'Every new item needs fresh UUID v4 **objectId** and matching **objectGroupId** (typically first 4 hex chars of objectId). ' +
        'Populate **formValidationPlan.requiredFieldIds** and satisfy **minSizeFields** / repeat minOccurs.'
    return spec
  }

  private static List<Map> loadTaxonomyBindings(StudioToolOperations ops, String siteId, String formDefinitionXml) {
    List<Map> bindings = []
    String xml = (formDefinitionXml ?: '').toString().trim()
    if (!xml || !ops || !(siteId ?: '').trim()) {
      return bindings
    }
    try {
      Document formDoc = CmsRepositorySupport.newHardenedSaxReader().read(new StringReader(xml))
      Element formRoot = formDoc?.getRootElement()
      if (formRoot == null) {
        return bindings
      }
      Map<String, Element> dsById = indexDatasources(formRoot)
      for (Element section : formSections(formRoot)) {
        Element fieldsEl = section?.element('fields')
        if (fieldsEl == null) {
          continue
        }
        collectTaxonomyBindingsFromFields(fieldsEl, false, dsById, ops, siteId, bindings)
      }
    } catch (DocumentException ignored) {
    }
    return bindings
  }

  private static void collectTaxonomyBindingsFromFields(
    Element fieldsContainer,
    boolean insideRepeat,
    Map<String, Element> dsById,
    StudioToolOperations ops,
    String siteId,
    List<Map> sink
  ) {
    if (fieldsContainer == null || sink == null) {
      return
    }
    for (Element field : fieldsContainer.elements('field')) {
      if (field == null) {
        continue
      }
      String type = textOfFirst(field, 'type')
      if ('repeat'.equals(type) || 'repeatable-group'.equals(type)) {
        Element nested = field.element('fields')
        if (nested != null) {
          collectTaxonomyBindingsFromFields(nested, true, dsById, ops, siteId, sink)
        }
        continue
      }
      if (insideRepeat || !'checkbox-group'.equals(type)) {
        continue
      }
      String fieldId = textOfFirst(field, 'id')?.trim()
      String dsId = checkboxGroupDatasourceId(field)
      if (!fieldId || !dsId) {
        continue
      }
      Element dsEl = dsById.get(dsId)
      if (dsEl == null || !isTaxonomyBackedDatasource(dsEl)) {
        continue
      }
      String taxPath = resolveTaxonomyRepoPathFromDatasource(dsEl)
      if (!taxPath) {
        continue
      }
      String taxXml = readFirstTaxonomyXml(ops, siteId, taxPath)
      List<Map> keys = parseTaxonomyKeyLabelPairs(taxXml)
      String valueTag = resolveCheckboxValueElementName(dsEl)
      int minSize = minSizeConstraint(field)
      String exampleKey = keys ? (keys.get(0).key ?: '').toString() : 'example-key'
      String exampleLabel = keys ? (keys.get(0).label ?: exampleKey).toString() : 'Example Label'
      Map row = new LinkedHashMap<>()
      row.fieldId = fieldId
      row.datasourceId = dsId
      row.taxonomyPath = taxPath
      row.minSize = minSize
      row.valueElement = valueTag
      row.keys = keys
      row.exampleItemXml =
        "<item><key>${escapeXmlText(exampleKey)}</key><${valueTag}>${escapeXmlText(exampleLabel)}</${valueTag}></item>"
      row.checkboxGroupExampleXml =
        "<${fieldId} item-list=\"true\">${row.exampleItemXml}</${fieldId}>"
      if (taxXml) {
        row.taxonomyXmlChars = taxXml.length()
      }
      sink.add(row)
    }
  }

  private static List<Map> loadRepeatBindings(String formDefinitionXml) {
    List<Map> bindings = []
    String xml = (formDefinitionXml ?: '').toString().trim()
    if (!xml) {
      return bindings
    }
    try {
      Document formDoc = CmsRepositorySupport.newHardenedSaxReader().read(new StringReader(xml))
      Element formRoot = formDoc?.getRootElement()
      if (formRoot == null) {
        return bindings
      }
      for (Element section : formSections(formRoot)) {
        Element fieldsEl = section?.element('fields')
        if (fieldsEl == null) {
          continue
        }
        for (Element field : fieldsEl.elements('field')) {
          if (field == null) {
            continue
          }
          String type = textOfFirst(field, 'type')
          if (!'repeat'.equals(type) && !'repeatable-group'.equals(type)) {
            continue
          }
          String fieldId = textOfFirst(field, 'id')?.trim()
          if (!fieldId) {
            continue
          }
          int minOccurs = minOccursFromRepeat(field)
          List<String> nestedIds = []
          Element nested = field.element('fields')
          if (nested != null) {
            for (Element nf : nested.elements('field')) {
              String nid = textOfFirst(nf, 'id')?.trim()
              if (nid) {
                nestedIds.add(nid)
              }
            }
          }
          Map row = new LinkedHashMap<>()
          row.fieldId = fieldId
          row.minOccurs = minOccurs
          row.nestedFieldIds = nestedIds
          String nestedExample = nestedIds ?
            nestedIds.get(0) :
            'field_id'
          row.exampleItemXml =
            "<item><${nestedExample}>&lt;p&gt;Author body HTML here (escaped or CDATA per site rules)&lt;/p&gt;</${nestedExample}></item>"
          row.repeatExampleXml = "<${fieldId}>${row.exampleItemXml}</${fieldId}>"
          bindings.add(row)
        }
      }
    } catch (DocumentException ignored) {
    }
    return bindings
  }

  private static String formatAuthoringMarkdown(Map materials, Map plan) {
    StringBuilder sb = new StringBuilder()
    sb.append('### WriteContent materials (from form definition + site taxonomy)\n\n')
    Map envelope = materials.standardEnvelope instanceof Map ? (Map) materials.standardEnvelope : [:]
    if (envelope) {
      sb.append('**Standard Crafter envelope (every new item):** `<')
        .append(envelope.rootElement ?: 'page').append('>` root with ')
      List structural = envelope.requiredStructuralElements instanceof List ?
        (List) envelope.requiredStructuralElements : []
      if (structural) {
        sb.append('`').append(structural.join('`, `')).append('`.')
      }
      sb.append('\n')
      if (envelope.folderName) {
        sb.append('**folder-name:** `').append(envelope.folderName).append('` (must match URL slug folder).\n')
      }
      if (envelope.fileName) {
        sb.append('**file-name:** `').append(envelope.fileName).append('`.\n')
      }
      if (envelope.suggestedRepoPath) {
        sb.append('**WriteContent path:** `').append(envelope.suggestedRepoPath).append('`.\n')
      }
    }
    List required = plan?.requiredFieldIds instanceof List ? (List) plan.requiredFieldIds : []
    if (required) {
      sb.append('**Required form fields:** `').append(required.join('`, `')).append('`.\n')
    }
    List minSize = plan?.minSizeFields instanceof List ? (List) plan.minSizeFields : []
    if (minSize) {
      for (Object o : minSize) {
        if (!(o instanceof Map)) {
          continue
        }
        Map spec = (Map) o
        sb.append('**`').append(spec.fieldId).append('`:** minSize/minOccurs ')
          .append(spec.minSize).append('.\n')
      }
    }
    List tax = materials.taxonomyBindings instanceof List ? (List) materials.taxonomyBindings : []
    if (tax) {
      sb.append('\n**Taxonomy checkbox-groups** (use only keys from site taxonomy — copy element shape exactly):\n')
      for (Object o : tax) {
        if (!(o instanceof Map)) {
          continue
        }
        Map tb = (Map) o
        sb.append('- **`').append(tb.fieldId).append('`** (datasource `')
          .append(tb.datasourceId).append('`, taxonomy `').append(tb.taxonomyPath).append('`')
        int tbMin = tb.minSize instanceof Number ? ((Number) tb.minSize).intValue() : 0
        if (tbMin > 0) {
          sb.append(', min ').append(tbMin)
        }
        sb.append('): keys ')
        List keys = tb.keys instanceof List ? (List) tb.keys : []
        if (keys) {
          List labels = []
          int cap = Math.min(keys.size(), 12)
          for (int i = 0; i < cap; i++) {
            Object k = keys.get(i)
            if (k instanceof Map) {
              labels.add((k.key ?: '').toString())
            }
          }
          sb.append('`').append(labels.join('`, `')).append('`')
          if (keys.size() > cap) {
            sb.append(' …')
          }
        }
        sb.append('\n  Example: `').append(tb.checkboxGroupExampleXml).append('`\n')
      }
    }
    List repeats = materials.repeatBindings instanceof List ? (List) materials.repeatBindings : []
    if (repeats) {
      sb.append('\n**Repeat groups** (field ids from form definition — not flat `<body>` or unlisted elements):\n')
      for (Object o : repeats) {
        if (!(o instanceof Map)) {
          continue
        }
        Map rb = (Map) o
        sb.append('- **`').append(rb.fieldId).append('`**')
        int rbMin = rb.minOccurs instanceof Number ? ((Number) rb.minOccurs).intValue() : 0
        if (rbMin > 0) {
          sb.append(' minOccurs ').append(rbMin)
        }
        sb.append(', nested: `')
          .append((rb.nestedFieldIds instanceof List ? (List) rb.nestedFieldIds : []).join('`, `'))
          .append('`\n  Example: `').append(rb.repeatExampleXml).append('`\n')
      }
    }
    sb.append('\n')
    return sb.toString()
  }

  private static Map<String, Element> indexDatasources(Element formRoot) {
    Map<String, Element> out = new LinkedHashMap<>()
    Element dss = formRoot?.element('datasources')
    if (dss == null) {
      return out
    }
    for (Element ds : dss.elements('datasource')) {
      String id = textOfFirst(ds, 'id')?.trim()
      if (id) {
        out.put(id, ds)
      }
    }
    return out
  }

  private static List<Element> formSections(Element formRoot) {
    Element sectionsWrapper = formRoot?.element('sections')
    if (sectionsWrapper != null) {
      return sectionsWrapper.elements('section') ?: []
    }
    return formRoot?.elements('section') ?: []
  }

  private static String readFirstTaxonomyXml(StudioToolOperations ops, String siteId, String taxPath) {
    for (String cand : expandSiteTaxonomyPathCandidates(taxPath)) {
      try {
        Map res = CmsGetContent.read(ops, siteId, cand) as Map
        String xml = (res?.contentXml ?: '').toString().trim()
        if (xml) {
          return xml
        }
      } catch (Throwable ignored) {
      }
    }
    return ''
  }

  private static List<String> expandSiteTaxonomyPathCandidates(String rawPath) {
    if (!rawPath?.trim()) {
      return []
    }
    String p = rawPath.trim()
    if (!p.startsWith('/')) {
      p = "/${p}"
    }
    LinkedHashSet<String> out = new LinkedHashSet<>()
    if (p.startsWith('/site/')) {
      out.add(p)
    } else {
      out.add("/site${p}")
    }
    p = out.iterator().next()
    if (!p.toLowerCase(Locale.ROOT).endsWith('.xml')) {
      out.add(p.endsWith('/') ? "${p}index.xml" : "${p}/index.xml")
      out.add("${p}.xml")
    }
    return new ArrayList<>(out)
  }

  private static List<Map> parseTaxonomyKeyLabelPairs(String taxonomyXml) {
    List<Map> pairs = []
    if (!taxonomyXml?.trim()) {
      return pairs
    }
    try {
      Document doc = CmsRepositorySupport.newHardenedSaxReader().read(new StringReader(taxonomyXml))
      Element items = findFirstDescendantByLocalName(doc.getRootElement(), 'items')
      if (items == null) {
        return pairs
      }
      for (Element item : items.elements('item')) {
        String key = textOfFirst(item, 'key')
        if (!key) {
          continue
        }
        String label = textOfFirst(item, 'value')
        if (!label) {
          label = textOfFirst(item, 'value_smv')
        }
        if (!label) {
          label = textOfFirst(item, 'value_s')
        }
        if (!label) {
          label = key
        }
        pairs.add([key: key, label: label])
      }
    } catch (DocumentException ignored) {
    }
    return pairs
  }

  private static Element findFirstDescendantByLocalName(Element root, String local) {
    if (root == null || !local) {
      return null
    }
    if (local == root.getQName().getName()) {
      return root
    }
    for (Element child : root.elements()) {
      Element found = findFirstDescendantByLocalName(child, local)
      if (found != null) {
        return found
      }
    }
    return null
  }

  private static boolean isTaxonomyBackedDatasource(Element dsEl) {
    String t = (textOfFirst(dsEl, 'type') ?: '').toLowerCase(Locale.ROOT)
    return t.contains('taxonomy')
  }

  private static String resolveTaxonomyRepoPathFromDatasource(Element dsEl) {
    for (String k : ['componentPath', 'repoPath', 'rootPath', 'baseRepositoryPath']) {
      String v = formDatasourcePropertyTrim(dsEl, k)
      if (v) {
        return v
      }
    }
    return ''
  }

  private static String resolveCheckboxValueElementName(Element dsEl) {
    String raw = formDatasourcePropertyTrim(dsEl, 'dataType')
    if (raw?.trim()?.startsWith('[')) {
      try {
        Object parsed = new JsonSlurper().parseText(raw)
        if (parsed instanceof List) {
          for (Object o : (List) parsed) {
            if (!(o instanceof Map)) {
              continue
            }
            Map row = (Map) o
            if (Boolean.TRUE.equals(row.selected) || 'true'.equalsIgnoreCase(row.selected?.toString())) {
              return checkboxValueElementNameForDataType((row.value ?: '').toString())
            }
          }
        }
      } catch (Throwable ignored) {
      }
    }
    return checkboxValueElementNameForDataType(raw)
  }

  private static String checkboxValueElementNameForDataType(String dataTypeRaw) {
    String dt = (dataTypeRaw ?: '').trim().toLowerCase(Locale.ROOT)
    if (!dt) {
      return 'value_smv'
    }
    if ('string'.equals(dt) || 'value_s'.equals(dt)) {
      return 'value_smv'
    }
    if ('value'.equals(dt)) {
      return 'value'
    }
    if ('float'.equals(dt) || 'double'.equals(dt) || 'value_f'.equals(dt)) {
      return 'value_fmv'
    }
    if ('integer'.equals(dt) || 'int'.equals(dt) || 'long'.equals(dt) || 'value_i'.equals(dt)) {
      return 'value_imv'
    }
    if ('html'.equals(dt) || 'value_html'.equals(dt)) {
      return 'value_htmlmv'
    }
    if ('date'.equals(dt) || 'datetime'.equals(dt) || 'value_dt'.equals(dt)) {
      return 'value_dtmv'
    }
    return 'value_smv'
  }

  private static String checkboxGroupDatasourceId(Element fieldEl) {
    Element props = fieldEl?.element('properties')
    if (props == null) {
      return ''
    }
    for (Element p : props.elements('property')) {
      if ('datasource'.equalsIgnoreCase(textOfFirst(p, 'name'))) {
        return textOfFirst(p, 'value')?.trim() ?: ''
      }
    }
    return ''
  }

  private static int minSizeConstraint(Element field) {
    Element constraints = field?.element('constraints')
    if (constraints == null) {
      return 0
    }
    for (Element c : constraints.elements('constraint')) {
      if ('minsize'.equalsIgnoreCase(textOfFirst(c, 'name')?.trim())) {
        String v = textOfFirst(c, 'value')?.trim()
        if (v?.isInteger()) {
          return v.toInteger()
        }
      }
    }
    return 0
  }

  private static int minOccursFromRepeat(Element field) {
    String mo = textOfFirst(field, 'minOccurs')?.trim()
    if (!mo) {
      Element props = field?.element('properties')
      if (props != null) {
        for (Element prop : props.elements('property')) {
          if ('minOccurs'.equals(textOfFirst(prop, 'name')?.trim())) {
            mo = textOfFirst(prop, 'value')?.trim()
            break
          }
        }
      }
    }
    return mo?.isInteger() ? mo.toInteger() : 0
  }

  private static String formDatasourcePropertyTrim(Element dsEl, String propName) {
    Element props = dsEl?.element('properties')
    if (props == null) {
      return ''
    }
    for (Element prop : props.elements('property')) {
      if (propName.equals(textOfFirst(prop, 'name')?.trim())) {
        return textOfFirst(prop, 'value')?.trim() ?: ''
      }
    }
    return ''
  }

  private static String extractFolderSlugFromIndexXmlPath(String path) {
    String p = (path ?: '').trim()
    if (!p.endsWith('/index.xml')) {
      return ''
    }
    p = p.substring(0, p.length() - '/index.xml'.length())
    int slash = p.lastIndexOf('/')
    return slash >= 0 ? p.substring(slash + 1) : p
  }

  private static String textOfFirst(Element parent, String localName) {
    if (parent == null || !localName) {
      return ''
    }
    Element el = parent.element(localName)
    return el?.getText()?.trim() ?: ''
  }

  private static String escapeXmlText(String s) {
    if (s == null) {
      return ''
    }
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
  }
}
