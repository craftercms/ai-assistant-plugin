package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal

import org.dom4j.Document
import org.dom4j.DocumentException
import org.dom4j.Element
import org.dom4j.io.SAXReader
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations

import java.io.StringReader
import java.util.Locale

/**
 * Classifies author-visible copy fields from a Studio form definition so plan and write steps
 * populate each field with role-appropriate content (headline vs deck vs section title, etc.).
 */
final class FormDefinitionCopyFieldPlan {

  private static final Set<String> SKIP_FIELD_IDS = Collections.unmodifiableSet([
    'file-name', 'internal-name', 'objectId', 'objectGroupId', 'folder-name',
    'createdDate', 'createdDate_dt', 'lastModifiedDate', 'lastModifiedDate_dt',
    'disabled', 'placeInNav', 'expires_dt', 'savedAsDraft', 'localeCode_s',
    'sourceLocaleCode_s', 'translationId_s', 'translationStatus_s', 'translated_b',
    'deleted', 'system-type', 'userFirstName_s', 'userLastName_s', 'userName_s', 'userEmail_s'
  ] as Set)

  private static final Set<String> COPY_FIELD_TYPES = Collections.unmodifiableSet([
    'input', 'text', 'textarea', 'rte', 'rich-text'
  ] as Set)

  private FormDefinitionCopyFieldPlan() {}

  /**
   * Loads form definition, builds copy-field plan, stores markdown on the session bundle, and returns the plan map.
   */
  static Map wireIntoSession(
    StudioToolOperations ops,
    Map toolsLoopSessionBundle,
    String anchorPath = '',
    String contentTypeId = ''
  ) {
    if (ops == null || !(toolsLoopSessionBundle instanceof Map)) {
      return [:]
    }
    Map bindings = [:]
    try {
      bindings = ops.recipeEngineAuthoringBindings() ?: [:]
    } catch (Throwable ignored) {
    }
    String siteId = (bindings.siteId ?: ops.resolveEffectiveSiteId(null) ?: '').toString().trim()
    String path = (anchorPath ?: bindings.contentPath ?: toolsLoopSessionBundle.contentPath ?: '').toString().trim()
    String ct = (contentTypeId ?: bindings.contentTypeId ?: toolsLoopSessionBundle.contentTypeId ?: '').toString().trim()
    if (!ct && path) {
      ct = resolveContentTypeIdFromPath(ops, siteId, path)
    }
    if (!ct || !siteId) {
      return [:]
    }
    Map plan = build(ops, siteId, ct, path)
    if (!(plan.copyFields instanceof List) || ((List) plan.copyFields).isEmpty()) {
      return [:]
    }
    String md = (plan.markdown ?: '').toString().trim()
    if (md) {
      toolsLoopSessionBundle.toolsLoopCopyFieldPlanMarkdown = md
      toolsLoopSessionBundle.toolsLoopCopyFieldPlanFieldIds = new ArrayList<>((List) plan.copyFieldIds)
      toolsLoopSessionBundle.contentTypeId = ct
      if (path) {
        toolsLoopSessionBundle.contentPath = path
      }
    }
    return plan
  }

  /** Orchestration block for plan-defer and tools-loop user messages. */
  static String formatOrchestrationBlock(Map plan) {
    String md = (plan?.markdown ?: '').toString().trim()
    if (!md) {
      return ''
    }
    return '[Studio — content field plan (from form definition)]\n\n' + md + '\n\n'
  }

  /** Wires plan into session and returns orchestration block, or empty when unavailable. */
  static String wireAndFormatOrchestrationBlock(
    StudioToolOperations ops,
    Map toolsLoopSessionBundle,
    String anchorPath = '',
    String contentTypeId = ''
  ) {
    Map plan = wireIntoSession(ops, toolsLoopSessionBundle, anchorPath, contentTypeId)
    return formatOrchestrationBlock(plan)
  }

  /** Short reminder injected before WriteContent when a copy plan is on the session bundle. */
  static String formatPreWriteReminder(Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return ''
    }
    String md = (toolsLoopSessionBundle.toolsLoopCopyFieldPlanMarkdown ?: '').toString().trim()
    if (!md) {
      return ''
    }
    List ids = toolsLoopSessionBundle.toolsLoopCopyFieldPlanFieldIds instanceof List ?
      (List) toolsLoopSessionBundle.toolsLoopCopyFieldPlanFieldIds : []
    StringBuilder sb = new StringBuilder()
    sb.append('[Studio — content field plan: apply before WriteContent]\n')
    sb.append(
      'The author asked to update page copy. Populate **every** copy field below with **distinct**, role-appropriate content — '
    )
    sb.append('not the same headline pasted into title, hero headline, and hero body.\n\n')
    if (!ids.isEmpty()) {
      sb.append('**Copy fields to update:** `').append(ids.join('`, `')).append('`\n\n')
    }
    sb.append(md).append('\n')
    return sb.toString()
  }

  static Map build(StudioToolOperations ops, String siteId, String contentTypeId, String contentPath = '') {
    Map empty = [contentTypeId: '', contentPath: '', copyFields: [], copyFieldIds: [], markdown: '']
    if (ops == null || !(siteId ?: '').trim() || !(contentTypeId ?: '').trim()) {
      return empty
    }
    try {
      Map formOut = CmsGetContentTypeFormDefinition.load(ops, siteId, contentTypeId) as Map
      String xml = (formOut?.formDefinitionXml ?: '').toString()
      if (!xml.trim()) {
        return empty
      }
      return buildFromFormDefinitionXml(contentTypeId.trim(), contentPath.trim(), xml)
    } catch (Throwable ignored) {
      return empty
    }
  }

  static Map buildFromFormDefinitionXml(String contentTypeId, String contentPath, String formDefinitionXml) {
    Map empty = [
      contentTypeId : (contentTypeId ?: '').trim(),
      contentPath   : (contentPath ?: '').trim(),
      copyFields    : [],
      copyFieldIds  : [],
      markdown      : ''
    ]
    String xml = (formDefinitionXml ?: '').trim()
    if (!xml) {
      return empty
    }
    try {
      Document doc = CmsRepositorySupport.newHardenedSaxReader().read(new StringReader(xml))
      Element root = doc?.getRootElement()
      if (root == null) {
        return empty
      }
      List<Map> copyFields = []
      List<Map> componentRefs = []
      for (Element section : formSections(root)) {
        String sectionTitle = textOfFirst(section, 'title')?.trim() ?: ''
        Element fieldsEl = section?.element('fields')
        if (fieldsEl != null) {
          collectCopyFields(fieldsEl, sectionTitle, false, '', copyFields, componentRefs)
        }
      }
      if (copyFields.isEmpty() && componentRefs.isEmpty()) {
        return empty
      }
      List<String> ids = copyFields.collect { (it.fieldId ?: '').toString().trim() }.findAll { it }
      Map out = new LinkedHashMap<>(empty)
      out.contentTypeId = (contentTypeId ?: textOfFirst(root, 'content-type') ?: '').trim()
      out.copyFields = copyFields
      out.copyFieldIds = ids
      out.markdown = formatMarkdown(out, componentRefs)
      return out
    } catch (DocumentException ignored) {
      return empty
    }
  }

  private static String resolveContentTypeIdFromPath(StudioToolOperations ops, String siteId, String contentPath) {
    String path = (contentPath ?: '').trim()
    if (!path || !siteId) {
      return ''
    }
    try {
      Map res = CmsGetContent.read(ops, siteId, path) as Map
      return (res.contentTypeIdFromXml ?: '').toString().trim()
    } catch (Throwable ignored) {
      return ''
    }
  }

  private static void collectCopyFields(
    Element fieldsContainer,
    String sectionTitle,
    boolean insideRepeat,
    String repeatPrefix,
    List<Map> copySink,
    List<Map> componentSink
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
        Element nested = field.element('fields')
        if (nested != null && repeatId) {
          collectCopyFields(nested, sectionTitle, true, repeatId, copySink, componentSink)
        }
        continue
      }
      if ('node-selector'.equals(type)) {
        String fieldId = textOfFirst(field, 'id')?.trim()
        if (fieldId && !insideRepeat) {
          componentSink.add([
            fieldId      : fieldId,
            fieldTitle   : textOfFirst(field, 'title')?.trim() ?: fieldId,
            sectionTitle : sectionTitle
          ])
        }
        continue
      }
      String fieldId = textOfFirst(field, 'id')?.trim()
      if (!fieldId || SKIP_FIELD_IDS.contains(fieldId)) {
        continue
      }
      if ('image-picker'.equals(type) || fieldId.endsWith('_image_s')) {
        copySink.add(buildFieldEntry(fieldId, type, field, sectionTitle, insideRepeat, repeatPrefix, 'image-asset'))
        continue
      }
      if (!COPY_FIELD_TYPES.contains(type) && !fieldId.endsWith('_t') && !fieldId.endsWith('_html')) {
        continue
      }
      String role = inferCopyRole(fieldId, type, textOfFirst(field, 'title'))
      if (!role || 'skip'.equals(role)) {
        continue
      }
      copySink.add(buildFieldEntry(fieldId, type, field, sectionTitle, insideRepeat, repeatPrefix, role))
    }
  }

  private static Map buildFieldEntry(
    String fieldId,
    String fieldType,
    Element field,
    String sectionTitle,
    boolean insideRepeat,
    String repeatPrefix,
    String role
  ) {
    String qualifiedId = insideRepeat && repeatPrefix ?
      "${repeatPrefix}.${fieldId}" : fieldId
    return [
      fieldId       : qualifiedId,
      fieldType     : (fieldType ?: '').trim(),
      fieldTitle    : textOfFirst(field, 'title')?.trim() ?: fieldId,
      sectionTitle  : (sectionTitle ?: '').trim(),
      copyRole      : role,
      required      : fieldIsRequired(field),
      writeGuidance : guidanceForRole(role)
    ]
  }

  static String inferCopyRole(String fieldId, String fieldType, String fieldTitle) {
    String id = (fieldId ?: '').toLowerCase(Locale.ROOT)
    String title = (fieldTitle ?: '').toLowerCase(Locale.ROOT)

    if ('image-asset'.equals(fieldType)) {
      return 'image-asset'
    }
    if (id.contains('seo') && (id.endsWith('_t') || id.endsWith('_s'))) {
      return 'seo-metadata'
    }
    if (id == 'title_t' || (title == 'title' && !id.contains('hero') && !id.contains('feature'))) {
      return 'page-title'
    }
    if (id == 'navlabel' || id.contains('navlabel')) {
      return 'navigation-label'
    }
    if (id.endsWith('_title_html')) {
      return id.contains('hero') ? 'hero-headline' : 'section-headline-html'
    }
    if (id.endsWith('_text_html') || id.endsWith('_body_html') || id.endsWith('_description_html')) {
      if (id.contains('hero')) {
        return 'hero-deck'
      }
      if (id.contains('seo')) {
        return 'seo-body'
      }
      return 'body-copy'
    }
    if (id.endsWith('_title_t') || (title.contains('title') && !id.contains('internal'))) {
      return 'section-title'
    }
    if ('rte'.equals(fieldType) || id.endsWith('_html')) {
      return 'rich-text'
    }
    if (id.endsWith('_t')) {
      return 'short-text'
    }
    return 'copy-field'
  }

  static String guidanceForRole(String role) {
    switch ((role ?: '').trim()) {
      case 'page-title':
        return 'Plain-text page title. Use the core headline without editorial prefixes ("Breaking news:", "Latest:", etc.).'
      case 'hero-headline':
        return 'Primary hero headline (rich HTML, often an `<h1>`). The main news line only — no "Breaking news" prefix.'
      case 'hero-deck':
        return 'Hero supporting copy (rich HTML). One or two sentences expanding on the headline with context — do **not** repeat the headline verbatim.'
      case 'section-headline-html':
        return 'Section headline (rich HTML). A display heading for this block — not necessarily the full article headline.'
      case 'section-title':
        return 'Short section title (plain text). A topical label for the block, not the full article headline.'
      case 'body-copy':
        return 'Body or description (rich HTML). Supporting paragraphs drawn from the source material.'
      case 'seo-metadata':
        return 'SEO field (plain text). Factual summary for search — concise, no clickbait prefixes.'
      case 'seo-body':
        return 'SEO description (rich HTML). Short factual summary, distinct from hero copy.'
      case 'navigation-label':
        return 'Navigation label (plain text). Short menu text — keep concise.'
      case 'image-asset':
        return 'Image path (`*_image_s`). Use **GenerateImage** repository path or existing asset — not inline chat URLs.'
      case 'rich-text':
        return 'Rich text. Role-appropriate HTML for this field label — distinct from other copy fields on the item.'
      case 'short-text':
        return 'Short plain text. Match the field label purpose — do not paste the full headline if this is a label or caption.'
      default:
        return 'Author-visible copy. Match the field label and section purpose; do not duplicate the same string used in other fields.'
    }
  }

  private static String formatMarkdown(Map plan, List<Map> componentRefs) {
    StringBuilder sb = new StringBuilder()
    sb.append('When updating this content item, populate **each** copy field with **distinct** content matched to its **role**.\n\n')
    sb.append('**Rules:**\n')
    sb.append('- Put the **headline** in `title_t` and/or `hero_title_html` — **without** prefixes like "Breaking news:".\n')
    sb.append('- Put **supporting context** in `hero_text_html` and body fields — not a repeat of the headline.\n')
    sb.append('- Do **not** leave author-visible copy fields unchanged when the author asked to update page content.\n\n')
    if (plan.contentTypeId) {
      sb.append('**Content type:** `').append(plan.contentTypeId).append('`')
      if (plan.contentPath) {
        sb.append(' · **Path:** `').append(plan.contentPath).append('`')
      }
      sb.append('\n\n')
    }
    sb.append('| Field | Type | Role | Required | Write guidance |\n')
    sb.append('|-------|------|------|----------|----------------|\n')
    List<Map> fields = plan.copyFields instanceof List ? (List) plan.copyFields : []
    for (Map f : fields) {
      sb.append('| `').append(f.fieldId).append('` | ')
        .append(f.fieldType ?: '').append(' | ')
        .append(f.copyRole ?: '').append(' | ')
        .append(Boolean.TRUE.equals(f.required) ? 'yes' : 'no').append(' | ')
        .append(escapeTableCell(f.writeGuidance ?: '')).append(' |\n')
    }
    if (componentRefs) {
      sb.append('\n**Component references** (separate content items — call **GetContent** on each path when updating full-page copy):\n')
      for (Map cr : componentRefs) {
        sb.append('- `').append(cr.fieldId).append('` — ')
          .append(cr.fieldTitle ?: cr.fieldId)
        if (cr.sectionTitle) {
          sb.append(' (').append(cr.sectionTitle).append(')')
        }
        sb.append('\n')
      }
    }
    sb.append('\n')
    return sb.toString()
  }

  private static String escapeTableCell(String s) {
    return (s ?: '').replace('|', '\\|').replace('\n', ' ')
  }

  private static boolean fieldIsRequired(Element field) {
    Element constraints = field?.element('constraints')
    if (constraints == null) {
      return false
    }
    for (Element c : constraints.elements('constraint')) {
      if ('required'.equals(textOfFirst(c, 'name'))) {
        String v = textOfFirst(c, 'value')?.trim()?.toLowerCase(Locale.ROOT)
        return 'true'.equals(v)
      }
    }
    return false
  }

  private static List<Element> formSections(Element formRoot) {
    Element sectionsWrapper = formRoot?.element('sections')
    if (sectionsWrapper != null) {
      return sectionsWrapper.elements('section') ?: []
    }
    return formRoot?.elements('section') ?: []
  }

  private static String textOfFirst(Element parent, String childName) {
    if (parent == null || !childName) {
      return ''
    }
    Element child = parent.element(childName)
    return child?.text ?: ''
  }
}
