package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal

import org.dom4j.Document
import org.dom4j.DocumentException
import org.dom4j.Element
import org.dom4j.io.SAXReader

import java.io.StringReader
import java.util.Locale

/**
 * Validates {@code WriteContent} {@code contentXml} against a Studio {@code form-definition.xml}.
 * Used when the LLM generates new repository items so required fields and collection minSize rules are enforced.
 */
final class FormDefinitionWriteContentValidator {

  private static final Set<String> STRUCTURAL_ELEMENT_NAMES = Collections.unmodifiableSet([
    'content-type', 'display-template', 'no-template-required', 'merge-strategy', 'objectId', 'objectGroupId',
    'file-name', 'folder-name', 'createdDate', 'createdDate_dt', 'lastModifiedDate', 'lastModifiedDate_dt',
    'disabled', 'placeInNav', 'navLabel', 'expires_dt', 'savedAsDraft', 'localeCode_s', 'sourceLocaleCode_s',
    'translationId_s', 'translationStatus_s', 'translated_b', 'deleted', 'system-type', 'userFirstName_s',
    'userLastName_s', 'userName_s', 'userEmail_s'
  ] as Set)

  /** Envelope / system elements allowed at item root but not listed in form field ids. */
  static boolean isStructuralEnvelopeElement(String elementName) {
    return elementName != null && STRUCTURAL_ELEMENT_NAMES.contains(elementName)
  }

  /** Form field ids plus Crafter structural envelope element names. */
  static Set<String> allowedRootElementNames(List<String> formFieldIds) {
    LinkedHashSet<String> allowed = new LinkedHashSet<>(formFieldIds ?: [])
    allowed.addAll(STRUCTURAL_ELEMENT_NAMES)
    return allowed
  }

  private FormDefinitionWriteContentValidator() {}

  /**
   * True when the plan was built from a parsed form definition (safe to enforce on WriteContent).
   */
  static boolean planIsActionable(Map validationPlan) {
    if (!(validationPlan instanceof Map) || validationPlan.isEmpty()) {
      return false
    }
    String ct = (validationPlan.contentTypeId ?: '').toString().trim()
    if (!ct) {
      return false
    }
    List fields = validationPlan.formFieldIds instanceof List ? (List) validationPlan.formFieldIds : []
    return !fields.isEmpty()
  }

  /**
   * Builds a compact validation plan from form-definition XML (safe to put on tools-loop telemetry).
   */
  static Map buildValidationPlan(String formDefinitionXml) {
    Map plan = new LinkedHashMap<>()
    plan.put('contentTypeId', '')
    plan.put('objectType', '')
    plan.put('displayTemplate', '')
    plan.put('mergeStrategy', '')
    plan.put('formFieldIds', [])
    plan.put('requiredFieldIds', [])
    plan.put('minSizeFields', [])

    String xml = (formDefinitionXml ?: '').toString().trim()
    if (!xml) {
      return plan
    }

    try {
      Document doc = CmsRepositorySupport.newHardenedSaxReader().read(new StringReader(xml))
      Element root = doc?.getRootElement()
      if (root == null) {
        return plan
      }

      plan.contentTypeId = textOfFirst(root, 'content-type')
      plan.objectType = textOfFirst(root, 'objectType')
      plan.displayTemplate = propertyValue(root, 'display-template')
      plan.mergeStrategy = propertyValue(root, 'merge-strategy')

      LinkedHashSet<String> fieldIds = new LinkedHashSet<>()
      LinkedHashSet<String> requiredIds = new LinkedHashSet<>()
      List<Map> minSizeFields = new ArrayList<>()

      for (Element section : formSections(root)) {
        Element fieldsEl = section?.element('fields')
        if (fieldsEl != null) {
          collectFieldsFromContainer(fieldsEl, fieldIds, requiredIds, minSizeFields)
        }
      }

      plan.formFieldIds = new ArrayList<>(fieldIds)
      plan.requiredFieldIds = new ArrayList<>(requiredIds)
      plan.minSizeFields = minSizeFields
    } catch (DocumentException ignored) {
    }
    return plan
  }

  /**
   * @return {@code [ok:true]} or {@code [ok:false, errors:[...]]}
   */
  static Map validate(String contentXml, Map validationPlan, String repoPath) {
    Map plan = validationPlan instanceof Map ? validationPlan : [:]
    String xml = (contentXml ?: '').toString().trim()
    if (!xml) {
      return fail(['contentXml is empty.'])
    }

    Document doc
    try {
      doc = CmsRepositorySupport.newHardenedSaxReader().read(new StringReader(xml))
    } catch (DocumentException e) {
      return fail(["contentXml is not well-formed XML: ${e.message}"])
    }

    Element root = doc?.getRootElement()
    if (root == null) {
      return fail(['contentXml has no root element.'])
    }

    List<String> errors = []
    String path = (repoPath ?: '').toString().trim()

    String expectedType = (plan.contentTypeId ?: '').toString().trim()
    String actualType = textOfFirst(root, 'content-type')
    if (expectedType && actualType && !expectedType.equalsIgnoreCase(actualType)) {
      errors.add("`<content-type>` is `${actualType}` but form definition expects `${expectedType}`.")
    } else if (expectedType && !actualType) {
      errors.add("Missing `<content-type>` — form definition expects `${expectedType}`.")
    }

    String expectedTemplate = (plan.displayTemplate ?: '').toString().trim()
    String actualTemplate = textOfFirst(root, 'display-template')
    if (expectedTemplate && actualTemplate && !expectedTemplate.equalsIgnoreCase(actualTemplate)) {
      errors.add("`<display-template>` is `${actualTemplate}` but form definition expects `${expectedTemplate}`.")
    } else if (expectedTemplate && !actualTemplate) {
      errors.add("Missing `<display-template>` — form definition expects `${expectedTemplate}`.")
    }

    String expectedMerge = (plan.mergeStrategy ?: '').toString().trim()
    String actualMerge = textOfFirst(root, 'merge-strategy')
    if (expectedMerge && actualMerge && !expectedMerge.equalsIgnoreCase(actualMerge)) {
      errors.add("`<merge-strategy>` is `${actualMerge}` but form definition expects `${expectedMerge}`.")
    } else if (expectedMerge && !actualMerge) {
      errors.add("Missing `<merge-strategy>` — form definition expects `${expectedMerge}`.")
    }

    if (!textOfFirst(root, 'objectId')) {
      errors.add('Missing `<objectId>` — assign a fresh UUID v4.')
    }

    if (!textOfFirst(root, 'objectGroupId')) {
      errors.add('Missing `<objectGroupId>` — assign a fresh short id (typically 4 hex chars).')
    }

    if (path.toLowerCase(Locale.ROOT).endsWith('/index.xml')) {
      String fileName = textOfFirst(root, 'file-name')
      if (!fileName) {
        errors.add('Missing `<file-name>` — for folder pages use `index` or `index.xml`.')
      } else if (!'index'.equalsIgnoreCase(fileName) && !'index.xml'.equalsIgnoreCase(fileName)) {
        errors.add("`<file-name>` is `${fileName}` but folder page paths require `index` or `index.xml`.")
      }
    }

    List<String> allFields = stringList(plan.formFieldIds)
    List<String> required = stringList(plan.requiredFieldIds)
    for (String fieldId : required) {
      if (!fieldHasContent(root, fieldId)) {
        errors.add("Missing or empty required field `<${fieldId}>` (form-definition constraint required=true).")
      }
    }

    Object minObj = plan.minSizeFields
    if (minObj instanceof List) {
      for (Object o : (List) minObj) {
        if (!(o instanceof Map)) {
          continue
        }
        Map spec = (Map) o
        String fieldId = (spec.fieldId ?: '').toString().trim()
        int minSize = spec.minSize instanceof Number ? ((Number) spec.minSize).intValue() : 0
        String fieldType = (spec.fieldType ?: '').toString().trim().toLowerCase(Locale.ROOT)
        if (!fieldId || minSize <= 0) {
          continue
        }
        int count = 'repeat'.equals(fieldType) || 'repeatable-group'.equals(fieldType) ?
          repeatGroupItemCountWithContent(root, fieldId) :
          collectionItemCount(root, fieldId)
        if (count < minSize) {
          errors.add(
            "Field `<${fieldId}>` needs at least ${minSize} item(s) with content (form-definition minSize/minOccurs=${minSize}); found ${count}."
          )
        }
      }
    }

    validateUnknownRootElements(root, allFields, errors)
    validateRepoPathSlugAlignment(root, path, errors)

    if (!errors.isEmpty()) {
      return [
        ok              : false,
        errors          : Collections.unmodifiableList(errors),
        formFieldIds    : allFields,
        requiredFieldIds: required
      ]
    }

    return [
      ok              : true,
      formFieldIds    : allFields,
      requiredFieldIds: required
    ]
  }

  private static List<Element> formSections(Element formRoot) {
    Element sectionsWrapper = formRoot?.element('sections')
    if (sectionsWrapper != null) {
      return sectionsWrapper.elements('section') ?: []
    }
    return formRoot?.elements('section') ?: []
  }

  private static void collectFieldsFromContainer(
    Element fieldsContainer,
    LinkedHashSet<String> fieldIds,
    LinkedHashSet<String> requiredIds,
    List<Map> minSizeFields
  ) {
    if (fieldsContainer == null) {
      return
    }
    for (Element field : fieldsContainer.elements('field')) {
      if (field == null) {
        continue
      }
      String type = textOfFirst(field, 'type')
      if ('repeat'.equals(type) || 'repeatable-group'.equals(type)) {
        String repeatId = textOfFirst(field, 'id')?.trim()
        if (repeatId && !'file-name'.equals(repeatId)) {
          fieldIds.add(repeatId)
          if (fieldIsRequired(field)) {
            requiredIds.add(repeatId)
          }
          int minOccurs = minOccursFromRepeat(field)
          if (minOccurs > 0) {
            minSizeFields.add([fieldId: repeatId, minSize: minOccurs, fieldType: type ?: 'repeat'])
          } else if (fieldIsRequired(field)) {
            minSizeFields.add([fieldId: repeatId, minSize: 1, fieldType: type ?: 'repeat'])
          }
        }
        continue
      }
      String fieldId = textOfFirst(field, 'id')?.trim()
      if (!fieldId || 'file-name'.equals(fieldId)) {
        continue
      }
      fieldIds.add(fieldId)
      if (fieldIsRequired(field)) {
        requiredIds.add(fieldId)
      }
      int minSize = minSizeConstraint(field)
      if (minSize > 0) {
        minSizeFields.add([fieldId: fieldId, minSize: minSize, fieldType: type ?: ''])
      }
    }
  }

  private static void validateUnknownRootElements(Element root, List<String> formFieldIds, List<String> errors) {
    LinkedHashSet<String> allowed = new LinkedHashSet<>(formFieldIds ?: [])
    allowed.addAll(STRUCTURAL_ELEMENT_NAMES)
    List<String> unknown = []
    for (Element child : root.elements()) {
      String name = child?.name
      if (name && !allowed.contains(name)) {
        unknown.add(name)
      }
    }
    if (!unknown.isEmpty()) {
      errors.add(
        "Unknown element(s) not in form definition: `${unknown.join('`, `')}` — use exact field ids from GetContentTypeFormDefinition / writeContentMaterials (repeat nested ids are listed under repeatBindings), not generic HTML names like `<title>` or `<body>`."
      )
    }
  }

  /**
   * Removes root-level elements not in the form definition or structural envelope.
   * @return names of removed elements (for logging)
   */
  static List<String> stripUnknownRootElements(Element root, List<String> formFieldIds) {
    List<String> removed = []
    if (root == null) {
      return removed
    }
    LinkedHashSet<String> allowed = new LinkedHashSet<>(formFieldIds ?: [])
    allowed.addAll(STRUCTURAL_ELEMENT_NAMES)
    List<Element> toRemove = []
    for (Element child : root.elements()) {
      String name = child?.name
      if (name && !allowed.contains(name)) {
        toRemove.add(child)
        removed.add(name)
      }
    }
    for (Element child : toRemove) {
      root.remove(child)
    }
    return removed
  }

  private static void validateRepoPathSlugAlignment(Element root, String path, List<String> errors) {
    String normalized = (path ?: '').toString().trim()
    if (!normalized.toLowerCase(Locale.ROOT).endsWith('/index.xml')) {
      return
    }
    if ('/site/website/index.xml'.equalsIgnoreCase(normalized)) {
      return
    }

    String folderFromPath = extractFolderSlugFromIndexXmlPath(normalized)
    if (!folderFromPath) {
      return
    }

    String folderName = textOfFirst(root, 'folder-name')
    String internalName = textOfFirst(root, 'internal-name')
    String subject = textOfFirst(root, 'subject_t')
    String titleT = textOfFirst(root, 'title_t')

    if (!folderName) {
      errors.add('Missing `<folder-name>` — website pages require folder-name matching the URL slug folder.')
    } else if (!folderFromPath.equalsIgnoreCase(slugifyForRepo(folderName))) {
      errors.add("Repository path folder `${folderFromPath}` does not match `<folder-name>` `${folderName}`.")
    }

    String titleSlug = slugifyForRepo(internalName ?: subject ?: titleT)
    if (titleSlug && !folderFromPath.equalsIgnoreCase(titleSlug)) {
      errors.add(
        "Repository path folder `${folderFromPath}` does not match title slug `${titleSlug}` from internal-name/subject/title fields."
      )
    }

    if (folderFromPath.matches(/(?i)draft-\d{8}-\d{4}/) && titleSlug && !titleSlug.startsWith('draft-')) {
      errors.add(
        "Path uses generic draft timestamp folder `${folderFromPath}` — use a title-based slug such as `${titleSlug}`."
      )
    }
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

  private static String slugifyForRepo(String title) {
    String s = (title ?: '').toString().trim().toLowerCase(Locale.ROOT)
    if (!s) {
      return ''
    }
    s = s.replaceAll(/[^a-z0-9]+/, '-').replaceAll(/-+/, '-').replaceAll(/^-|-$/, '')
    if (s.length() > 80) {
      s = s.substring(0, 80).replaceAll(/-+$/, '')
    }
    return s
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

  private static int repeatGroupItemCountWithContent(Element root, String fieldId) {
    Element fieldEl = root?.element(fieldId)
    if (fieldEl == null) {
      return 0
    }
    int count = 0
    for (Element item : fieldEl.elements('item')) {
      boolean hasContent = false
      for (Element child : item.elements()) {
        String text = child?.getText()?.trim()
        if (text) {
          hasContent = true
          break
        }
      }
      if (hasContent) {
        count++
      }
    }
    return count
  }

  private static List<String> fail(List<String> errors) {
    return [
      ok    : false,
      errors: Collections.unmodifiableList(errors ?: [])
    ]
  }

  private static List<String> stringList(Object o) {
    List<String> out = new ArrayList<>()
    if (!(o instanceof List)) {
      return out
    }
    for (Object item : (List) o) {
      String s = item?.toString()?.trim()
      if (s) {
        out.add(s)
      }
    }
    return out
  }

  private static boolean fieldIsRequired(Element field) {
    Element constraints = field?.element('constraints')
    if (constraints == null) {
      return false
    }
    for (Element c : constraints.elements('constraint')) {
      String name = textOfFirst(c, 'name')?.trim()?.toLowerCase(Locale.ROOT)
      String value = textOfFirst(c, 'value')?.trim()?.toLowerCase(Locale.ROOT)
      if ('required'.equals(name) && ('true'.equals(value) || '1'.equals(value))) {
        return true
      }
    }
    return false
  }

  private static int minSizeConstraint(Element field) {
    Element constraints = field?.element('constraints')
    if (constraints == null) {
      return 0
    }
    for (Element c : constraints.elements('constraint')) {
      String name = textOfFirst(c, 'name')?.trim()?.toLowerCase(Locale.ROOT)
      if (!'minsize'.equals(name)) {
        continue
      }
      String value = textOfFirst(c, 'value')?.trim()
      if (value?.isInteger()) {
        return value.toInteger()
      }
    }
    return 0
  }

  private static String propertyValue(Element formRoot, String propName) {
    Element props = formRoot?.element('properties')
    if (props == null) {
      return ''
    }
    for (Element prop : props.elements('property')) {
      String name = textOfFirst(prop, 'name')?.trim()
      if (propName.equals(name)) {
        return textOfFirst(prop, 'value')?.trim() ?: ''
      }
    }
    return ''
  }

  private static String textOfFirst(Element parent, String localName) {
    if (parent == null || !localName) {
      return ''
    }
    Element el = parent.element(localName)
    return el?.getText()?.trim() ?: ''
  }

  private static boolean fieldHasContent(Element root, String fieldId) {
    Element fieldEl = root?.element(fieldId)
    if (fieldEl == null) {
      return false
    }
    if (!fieldEl.elements('item').isEmpty()) {
      return repeatGroupItemCountWithContent(root, fieldId) > 0 || collectionItemCount(root, fieldId) > 0
    }
    String text = fieldEl.getText()?.trim()
    return text != null && text.length() > 0
  }

  private static int collectionItemCount(Element root, String fieldId) {
    Element fieldEl = root?.element(fieldId)
    if (fieldEl == null) {
      return 0
    }
    List items = fieldEl.elements('item')
    if (!items.isEmpty()) {
      return items.size()
    }
    String text = fieldEl.getText()?.trim()
    return text ? 1 : 0
  }
}
