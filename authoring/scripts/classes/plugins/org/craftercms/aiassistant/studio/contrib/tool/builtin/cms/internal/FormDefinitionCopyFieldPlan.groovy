package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
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
      toolsLoopSessionBundle.toolsLoopCopyFieldPlanFields = new ArrayList<>((List) plan.copyFields)
      toolsLoopSessionBundle.contentTypeId = ct
      if (path) {
        toolsLoopSessionBundle.contentPath = path
      }
      List<String> imageFieldIds = fieldIdsForWritePolicy((List) plan.copyFields, 'image-path')
      if (!imageFieldIds.isEmpty()) {
        toolsLoopSessionBundle.toolsLoopCopyPlanHasImageAssetFields = Boolean.TRUE
        toolsLoopSessionBundle.toolsLoopCopyPlanImageFieldIds = new ArrayList<>(imageFieldIds)
      }
    }
    return plan
  }

  /** Image-asset field ids from the session copy field plan ({@code image-path} write policy). */
  static List<String> imageAssetFieldIdsFromBundle(Map toolsLoopSessionBundle) {
    return fieldIdsForWritePolicy(copyFieldsFromBundle(toolsLoopSessionBundle), 'image-path')
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
    String excerpt = (toolsLoopSessionBundle.toolsLoopRetrievedSourceExcerpt ?: '').toString().trim()
    String srcUrl = (toolsLoopSessionBundle.toolsLoopRetrievedSourceUrl ?: '').toString().trim()
    String srcPageTitle = (toolsLoopSessionBundle.toolsLoopRetrievedSourcePageTitle ?: '').toString().trim()
    if (!md && !excerpt) {
      return ''
    }
    List<Map> copyFields = copyFieldsFromBundle(toolsLoopSessionBundle)
    StringBuilder sb = new StringBuilder()
    sb.append('[Studio — content field plan: apply before WriteContent]\n')
    sb.append(formatResearchOriginalCopyRules(copyFields, srcPageTitle)).append('\n')
    if (excerpt) {
      sb.append(
        'Use the **retrieved excerpt** below for **facts only** (events, names, dates, quotes) — not as paste-ready page copy.\n\n'
      )
      if (srcUrl) {
        sb.append('**Source:** `').append(srcUrl).append('`\n\n')
      }
      sb.append('**Retrieved excerpt (research — do not paste as headline):**\n```\n').append(excerpt).append('\n```\n\n')
    } else {
      sb.append(
        'Populate **every** copy field in the plan with **distinct**, role-appropriate **original** copy for this site.\n\n'
      )
    }
    if (md) {
      sb.append(md).append('\n')
    }
    return sb.toString()
  }

  static List<Map> copyFieldsFromBundle(Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return []
    }
    Object raw = toolsLoopSessionBundle.toolsLoopCopyFieldPlanFields
    if (!(raw instanceof List)) {
      return []
    }
    List<Map> out = []
    for (Object o : (List) raw) {
      if (o instanceof Map) {
        out.add((Map) o)
      }
    }
    return out
  }

  static List<String> fieldIdsForRole(List<Map> copyFields, String role) {
    return fieldIdsForWritePolicy(copyFields, role)
  }

  static List<String> fieldIdsForRoles(List<Map> copyFields, List<String> roles) {
    return fieldIdsForWritePolicies(copyFields, roles)
  }

  /** Rules so fetched pages inform facts, not verbatim headlines. Policies come from the form-definition plan. */
  static String formatResearchOriginalCopyRules(List<Map> copyFields, String sourcePageTitle = '') {
    StringBuilder sb = new StringBuilder()
    sb.append('**Original copy (required):**\n')
    sb.append('- Retrieved web pages are **research** — extract facts; **do not** lift the source page title, site nav, or intro boilerplate into your fields.\n')
    sb.append('- Fields marked **original-headline** need **newsroom-quality** headlines — a concrete story angle (who/what changed), **not** the author\'s assignment ("Latest updates on…") or filler like "Insights and Implications".\n')
    if (sourcePageTitle?.trim()) {
      sb.append('- **Do not use as any original headline:** `').append(sourcePageTitle.trim()).append('`\n')
    }
    List<String> headlineIds = fieldIdsForWritePolicy(copyFields, 'original-headline')
    if (!headlineIds.isEmpty()) {
      sb.append('- Original-headline fields: `').append(headlineIds.join('`, `')).append('` — see **Purpose** column for each.\n')
    }
    List<String> deckIds = fieldIdsForWritePolicies(copyFields, ['supporting-copy', 'rich-copy'])
    if (!deckIds.isEmpty()) {
      sb.append('- Supporting/body fields: `').append(deckIds.join('`, `')).append('` — facts and context; never repeat a headline verbatim.\n')
    }
    List<String> imageIds = fieldIdsForWritePolicy(copyFields, 'image-path')
    if (!imageIds.isEmpty()) {
      List<String> requiredImages = []
      List<String> optionalImages = []
      for (String id : imageIds) {
        Map meta = copyFieldMeta(copyFields, id)
        if (Boolean.TRUE.equals(meta.required)) {
          requiredImages.add(id)
        } else {
          optionalImages.add(id)
        }
      }
      if (!requiredImages.isEmpty()) {
        sb.append('- Required image fields: `').append(requiredImages.join('`, `')).append(
          '` — **GenerateImage** then **repositoryPath**; never invent paths.\n'
        )
      }
      if (!optionalImages.isEmpty()) {
        sb.append('- Optional image fields: `').append(optionalImages.join('`, `')).append(
          '` — omit unless the author asked for new art; invalid paths are ignored and the existing image is kept.\n'
        )
      }
    }
    return sb.toString().trim()
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
      if ('image-picker'.equals(type)) {
        copySink.add(buildFieldEntry(fieldId, type, field, sectionTitle, insideRepeat, repeatPrefix))
        continue
      }
      if (!COPY_FIELD_TYPES.contains(type)) {
        continue
      }
      copySink.add(buildFieldEntry(fieldId, type, field, sectionTitle, insideRepeat, repeatPrefix))
    }
  }

  private static Map buildFieldEntry(
    String fieldId,
    String fieldType,
    Element field,
    String sectionTitle,
    boolean insideRepeat,
    String repeatPrefix
  ) {
    String qualifiedId = insideRepeat && repeatPrefix ?
      "${repeatPrefix}.${fieldId}" : fieldId
    String purpose = buildFieldPurpose(sectionTitle, field)
    String writePolicy = inferWritePolicy(fieldType, purpose, sectionTitle, textOfFirst(field, 'title'))
    return [
      fieldId       : qualifiedId,
      fieldType     : (fieldType ?: '').trim(),
      fieldTitle    : textOfFirst(field, 'title')?.trim() ?: fieldId,
      fieldDescription : textOfFirst(field, 'description')?.trim() ?: '',
      fieldHelp     : textOfFirst(field, 'help')?.trim() ?: '',
      sectionTitle  : (sectionTitle ?: '').trim(),
      purpose       : purpose,
      writePolicy   : writePolicy,
      copyRole      : writePolicy,
      required      : fieldIsRequired(field),
      writeGuidance : writeGuidanceForPolicy(writePolicy, purpose)
    ]
  }

  /** Human-readable purpose from form-definition metadata (not field-id heuristics). */
  static String buildFieldPurpose(String sectionTitle, Element field) {
    if (field == null) {
      return ''
    }
    String label = textOfFirst(field, 'title')?.trim() ?: textOfFirst(field, 'id')?.trim() ?: ''
    String description = textOfFirst(field, 'description')?.trim() ?: ''
    String help = textOfFirst(field, 'help')?.trim() ?: ''
    String type = textOfFirst(field, 'type')?.trim() ?: ''
    List<String> parts = []
    if ((sectionTitle ?: '').trim()) {
      parts.add('Section: ' + sectionTitle.trim())
    }
    if (label) {
      parts.add('Label: ' + label)
    }
    if (description) {
      parts.add(description)
    }
    if (help) {
      parts.add(help)
    }
    if (!description && !help) {
      parts.add(typeHintForFieldType(type))
    }
    return parts.join(' — ').trim()
  }

  private static String typeHintForFieldType(String fieldType) {
    switch ((fieldType ?: '').trim()) {
      case 'image-picker':
        return 'Image asset for this slot — set a repository path from GenerateImage or an existing upload.'
      case 'rte':
      case 'rich-text':
        return 'Rich HTML for this field.'
      case 'textarea':
        return 'Multi-line plain text for this field.'
      case 'input':
      case 'text':
        return 'Plain text for this field.'
      default:
        return 'Author-visible content for this field.'
    }
  }

  /**
   * Write policy from field type + form title/description/help/section — never from field id patterns.
   */
  static String inferWritePolicy(String fieldType, String purposeText, String sectionTitle = '', String fieldTitle = '') {
    String type = (fieldType ?: '').trim().toLowerCase(Locale.ROOT)
    String hay = purposeHaystack(purposeText, sectionTitle, fieldTitle)

    if ('image-picker'.equals(type)) {
      return 'image-path'
    }
    if (hay.contains('nav label') || hay.contains('navigation label') ||
      (hay.contains('navigation') && hay.contains('label')) || hay.contains('menu label')) {
      return 'navigation'
    }
    if (hay.contains('seo') || hay.contains('meta description') || hay.contains('search engine')) {
      return 'seo-metadata'
    }
    if (isSupportingCopyPurpose(hay)) {
      return 'supporting-copy'
    }
    if (isOriginalHeadlinePurpose(hay)) {
      return 'original-headline'
    }
    if (isSectionLabelPurpose(hay)) {
      return 'section-label'
    }
    if ('rte'.equals(type) || 'rich-text'.equals(type)) {
      return 'rich-copy'
    }
    if ('input'.equals(type) || 'text'.equals(type) || 'textarea'.equals(type)) {
      return 'short-copy'
    }
    return 'author-copy'
  }

  private static String purposeHaystack(String purposeText, String sectionTitle, String fieldTitle) {
    return ((purposeText ?: '') + ' ' + (sectionTitle ?: '') + ' ' + (fieldTitle ?: ''))
      .toLowerCase(Locale.ROOT)
      .replaceAll('\\s+', ' ')
      .trim()
  }

  private static boolean isOriginalHeadlinePurpose(String hay) {
    if (!hay) {
      return false
    }
    if (hay.contains('nav ') || hay.contains('navigation') || hay.contains('menu ')) {
      return false
    }
    if (hay.contains('not the article') || hay.contains('not the page headline') ||
      hay.contains('not the headline') || hay.contains('block label') ||
      hay.contains('section label') || hay.contains('grid label')) {
      return false
    }
    if (hay.contains('supporting') || hay.contains('subhead') || hay.contains('deck') ||
      (hay.contains('intro') && hay.contains('expand'))) {
      return false
    }
    if (hay.contains('page title') || hay.contains('main headline') || hay.contains('primary headline')) {
      return true
    }
    if (hay.contains('browser title') || hay.contains('listing title')) {
      return true
    }
    if (hay.contains('(h1)') || (hay.contains('primary ') && hay.contains('headline'))) {
      return true
    }
    if (hay.contains('label:')) {
      if (hay.contains('nav ')) {
        return false
      }
      if (hay.contains('short label') || hay.contains('section label') || hay.contains('grid label')) {
        return false
      }
      if (hay.contains('headline') || hay.contains(' h1')) {
        if (!hay.contains('supporting')) {
          return true
        }
      }
      if (hay.contains(' title') && !hay.contains('section title')) {
        if (hay.contains('section: page propert')) {
          return true
        }
        if (!hay.contains('features') && !hay.contains('above the')) {
          return true
        }
      }
    }
    return false
  }

  private static boolean isSectionLabelPurpose(String hay) {
    if (!hay) {
      return false
    }
    return hay.contains('section label') || hay.contains('block label') ||
      hay.contains('grid label') || hay.contains('not the article') ||
      hay.contains('not the page headline') || hay.contains('not the headline') ||
      (hay.contains('short label') && hay.contains('above'))
  }

  private static boolean isSupportingCopyPurpose(String hay) {
    if (!hay) {
      return false
    }
    if (hay.contains('supporting') || hay.contains('subhead') || hay.contains('deck') ||
      hay.contains('lead paragraph') || hay.contains('summary')) {
      return true
    }
    if (hay.contains('label:') && (hay.contains('headline') || hay.contains(' h1'))) {
      return false
    }
    return hay.contains('intro') ||
      (hay.contains('description') && !hay.contains('meta description')) ||
      (hay.contains('expand') && hay.contains('headline'))
  }

  static String writeGuidanceForPolicy(String writePolicy, String purpose = '') {
    String p = (purpose ?: '').trim()
    String purposeSuffix = p ? " Purpose: ${p}" : ''
    switch ((writePolicy ?: '').trim()) {
      case 'original-headline':
        return 'Write a **newsroom-quality** visitor-facing headline — a specific angle on the story (who/what changed), **not** the author\'s task wording, generic "Latest updates on…" labels, or the fetched source page title.' + purposeSuffix
      case 'section-label':
        return 'Short label for this block — topical, distinct from the main page headline.' + purposeSuffix
      case 'supporting-copy':
        return 'Supporting copy using **facts** from research — do not repeat the main headline or paste source intro verbatim.' + purposeSuffix
      case 'seo-metadata':
        return 'Concise SEO text — factual, no clickbait prefixes.' + purposeSuffix
      case 'navigation':
        return 'Short navigation/menu label.' + purposeSuffix
      case 'image-path':
        return '**GenerateImage** then set **repositoryPath** — never invent `/static-assets/…` paths or external URLs.' + purposeSuffix
      case 'rich-copy':
        return 'Distinct rich HTML matched to this field\'s purpose — not duplicated from other fields.' + purposeSuffix
      case 'short-copy':
        return 'Plain text matched to this field\'s purpose — distinct from other fields.' + purposeSuffix
      default:
        return 'Author-visible copy matched to this field\'s stated purpose.' + purposeSuffix
    }
  }

  static List<String> fieldIdsForWritePolicy(List<Map> copyFields, String writePolicy) {
    String policy = (writePolicy ?: '').trim()
    if (!policy || !(copyFields instanceof List)) {
      return []
    }
    List<String> ids = []
    for (Map f : copyFields) {
      String p = (f.writePolicy ?: f.copyRole ?: '').toString().trim()
      if (policy.equals(p)) {
        String id = (f.fieldId ?: '').toString().trim()
        if (id) {
          ids.add(id)
        }
      }
    }
    return ids
  }

  static List<String> fieldIdsForWritePolicies(List<Map> copyFields, List<String> policies) {
    List<String> out = []
    if (!(policies instanceof List) || !(copyFields instanceof List)) {
      return out
    }
    for (String policy : policies) {
      for (String id : fieldIdsForWritePolicy(copyFields, policy)) {
        if (!out.contains(id)) {
          out.add(id)
        }
      }
    }
    return out
  }

  private static String formatMarkdown(Map plan, List<Map> componentRefs) {
    StringBuilder sb = new StringBuilder()
    sb.append('When updating this content item, populate **each** copy field with **distinct**, **original** content matched to its **Purpose** (from the form definition).\n\n')
    List<Map> fields = plan.copyFields instanceof List ? (List) plan.copyFields : []
    sb.append(formatResearchOriginalCopyRules(fields, '')).append('\n\n')
    sb.append('- Do **not** leave author-visible copy fields unchanged when the author asked to update page content.\n\n')
    if (plan.contentTypeId) {
      sb.append('**Content type:** `').append(plan.contentTypeId).append('`')
      if (plan.contentPath) {
        sb.append(' · **Path:** `').append(plan.contentPath).append('`')
      }
      sb.append('\n\n')
    }
    sb.append('| Type | Field | Purpose | Required |\n')
    sb.append('|------|-------|---------|----------|\n')
    for (Map f : fields) {
      sb.append('| ').append(escapeTableCell(f.fieldType ?: '')).append(' | ')
        .append('`').append(f.fieldId).append('` | ')
        .append(escapeTableCell(f.purpose ?: f.writeGuidance ?: '')).append(' | ')
        .append(Boolean.TRUE.equals(f.required) ? 'yes' : 'no').append(' |\n')
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

  /**
   * Blocks WriteContent when a research turn pastes the fetched page title into headline roles
   * or sets image-asset roles to invalid repository paths. Field ids come from the session copy plan only.
   */
  static Map gateWriteContent(
    Map toolsLoopSessionBundle,
    StudioToolOperations ops,
    String argsStr,
    JsonSlurper slurper = null
  ) {
    if (!(toolsLoopSessionBundle instanceof Map) || !ops || !argsStr?.trim()) {
      return [proceed: Boolean.TRUE]
    }
    if (!Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopExternalLookupRequired)) {
      return [proceed: Boolean.TRUE]
    }
    List<Map> copyFields = copyFieldsFromBundle(toolsLoopSessionBundle)
    if (copyFields.isEmpty()) {
      return [proceed: Boolean.TRUE]
    }
    JsonSlurper parser = slurper != null ? slurper : new JsonSlurper()
    Map args
    try {
      Object parsed = parser.parseText(argsStr)
      if (!(parsed instanceof Map)) {
        return [proceed: Boolean.TRUE]
      }
      args = (Map) parsed
    } catch (Throwable ignored) {
      return [proceed: Boolean.TRUE]
    }
    String contentXml = (args.contentXml ?: '').toString()
    if (!contentXml.trim()) {
      return [proceed: Boolean.TRUE]
    }
    String siteId = (args.siteId ?: '').toString().trim()
    if (!siteId) {
      siteId = ops.resolveEffectiveSiteId('')
    }

    String forbiddenTitle = (toolsLoopSessionBundle.toolsLoopRetrievedSourcePageTitle ?: '').toString().trim()
    List<String> headlineIds = fieldIdsForWritePolicy(copyFields, 'original-headline')
    for (String fieldId : headlineIds) {
      String value = plainTextFromContentXml(contentXml, fieldId)
      if (!value) {
        continue
      }
      if (forbiddenTitle && isVerbatimForbiddenHeadline(value, forbiddenTitle)) {
        Map fieldMeta = copyFieldMeta(copyFields, fieldId)
        String purposeHint = (fieldMeta.purpose ?: fieldMeta.writePolicy ?: 'original-headline').toString()
        return blockedCopyPlanGate(
          "WriteContent **blocked** — field `${fieldId}` matches the fetched source page title. ${purposeHint}",
          'Synthesize a page concept → WriteContent with distinct original copy per the content field plan **Purpose** column.'
        )
      }
      if (isTaskOrientedHeadline(value, toolsLoopSessionBundle)) {
        Map fieldMeta = copyFieldMeta(copyFields, fieldId)
        String purposeHint = (fieldMeta.purpose ?: fieldMeta.writePolicy ?: 'original-headline').toString()
        return blockedCopyPlanGate(
          "WriteContent **blocked** — field `${fieldId}` reads like the author's assignment, not a visitor-facing headline. ${purposeHint}",
          'Write a specific news angle (who did what, what changed) — not "Latest updates on…" or other task/meta labels.'
        )
      }
    }

    List<String> imageIds = fieldIdsForWritePolicy(copyFields, 'image-path')
    boolean generateOk = Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopGenerateImageOkThisTurn)
    for (String fieldId : imageIds) {
      String path = rawTextFromContentXml(contentXml, fieldId)?.trim()
      if (!path) {
        continue
      }
      if (!CmsWriteContent.isUsableImagePickerRepoPath(ops, siteId, path)) {
        Map fieldMeta = copyFieldMeta(copyFields, fieldId)
        boolean required = Boolean.TRUE.equals(fieldMeta.required)
        if (!required) {
          // Optional image-picker: reconcileExistingItemWithBaseline skips invalid overlays and
          // preserves the sandbox baseline — do not block copy-only topical updates.
          continue
        }
        String nextStep = generateOk ?
          'Set image-asset fields to the **repositoryPath** returned by **GenerateImage** — not an invented `/static-assets/…` path.' :
          '**GenerateImage** first, then set image-asset fields to the returned **repositoryPath**.'
        return blockedCopyPlanGate(
          "WriteContent **blocked** — required field `${fieldId}` has an invalid or non-existent image repository path." +
            (generateOk ? ' Use the path from **GenerateImage**.' : ' Call **GenerateImage** before writing image fields.'),
          nextStep
        )
      }
    }
    return [proceed: Boolean.TRUE]
  }

  /**
   * User-role nudge after {@link #gateWriteContent} rejects a write — steers the next round to fix headlines and retry.
   */
  static String formatWriteContentGateRecoveryNudge(Map toolsLoopSessionBundle, Map gateToolResult = null) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return ''
    }
    String gateMsg = (gateToolResult?.message ?: '').toString().trim()
    String nextStep = (gateToolResult?.nextStep ?: '').toString().trim()
    List<String> headlineIds = fieldIdsForWritePolicy(copyFieldsFromBundle(toolsLoopSessionBundle), 'original-headline')
    String fieldsLine = headlineIds.isEmpty() ? '' :
      "\n**Original-headline fields:** `" + headlineIds.join('`, `') + '`.'
    StringBuilder sb = new StringBuilder()
    sb.append('[Studio — WriteContent blocked: fix headlines and retry]\n')
    if (gateMsg) {
      sb.append(gateMsg).append('\n')
    }
    sb.append(
      'The repository was **not** updated. Do **not** call **GenerateImage** or run more search until **WriteContent** succeeds.\n'
    )
    sb.append(
      'Rewrite **original-headline** fields as a **specific news angle** from your retrieved sources ' +
        '(who did what, what changed) — e.g. "Trump Backs 2026 World Cup Security Plan", not "Latest World Cup Updates" or the author\'s assignment.\n'
    )
    sb.append(fieldsLine).append('\n')
    if (nextStep) {
      sb.append('**Next:** ').append(nextStep).append('\n')
    }
    sb.append('Call **WriteContent** with the **full** prefetched page XML and corrected copy.\n')
    return sb.toString().trim()
  }

  private static Map blockedCopyPlanGate(String message, String nextStep) {
    return [
      proceed : Boolean.FALSE,
      toolOut : JsonOutput.toJson([
        ok      : false,
        skipped : true,
        message : message,
        nextStep: nextStep
      ])
    ]
  }

  private static Map copyFieldMeta(List<Map> copyFields, String fieldId) {
    for (Map f : copyFields) {
      if (fieldId.equals((f.fieldId ?: '').toString())) {
        return f
      }
    }
    return [:]
  }

  /**
   * True when headline copy paraphrases the author's task ("Latest updates on…") instead of a reader-facing angle.
   */
  private static boolean isTaskOrientedHeadline(String fieldValue, Map toolsLoopSessionBundle) {
    String value = normalizeComparableText(fieldValue)
    if (!value || value.length() < 8) {
      return false
    }
    String authorRequest = authorRequestComparableText(toolsLoopSessionBundle)
    String turnGoal = normalizeComparableText(
      (toolsLoopSessionBundle?.intentRecipeRoutingTelemetry instanceof Map ?
        ((Map) toolsLoopSessionBundle.intentRecipeRoutingTelemetry).turnGoal : '')?.toString() ?: ''
    )
    if (matchesTaskOrientedHeadlinePattern(value)) {
      return true
    }
    if (authorRequest && taskParaphraseOverlap(value, authorRequest)) {
      return true
    }
    if (turnGoal && taskParaphraseOverlap(value, turnGoal)) {
      return true
    }
    return false
  }

  private static String authorRequestComparableText(Map toolsLoopSessionBundle) {
    String av = (toolsLoopSessionBundle?.authorIntentCardAuthorVisible ?: '').toString().trim()
    if (!av && toolsLoopSessionBundle?.intentRecipeRoutingTelemetry instanceof Map) {
      av = ((Map) toolsLoopSessionBundle.intentRecipeRoutingTelemetry).authorRequestText?.toString()?.trim() ?: ''
    }
    return normalizeComparableText(av)
  }

  private static boolean matchesTaskOrientedHeadlinePattern(String normalizedHeadline) {
    if (!normalizedHeadline) {
      return false
    }
    if (normalizedHeadline ==~ /^(latest\s+)?(updates?|developments?|news)\s+(on|about|in|regarding)\s+.+/) {
      return true
    }
    if (normalizedHeadline ==~ /^(update|updates)\s+(on|about|regarding)\s+.+/) {
      return true
    }
    if (normalizedHeadline ==~ /^about\s+(the\s+)?(latest|recent|current)\s+.+/) {
      return true
    }
    if (normalizedHeadline ==~ /^latest\s+.+\s+(updates?|developments?|news)$/) {
      return true
    }
    if (normalizedHeadline ==~ /^insights\s+and\s+implications$/) {
      return true
    }
    if (normalizedHeadline ==~ /^(key\s+)?(insights|developments|updates)\s+(on|about|in|regarding)\s+.+/) {
      return true
    }
    return false
  }

  private static boolean taskParaphraseOverlap(String headline, String authorText) {
    if (!headline || !authorText || headline.length() < 12) {
      return false
    }
    if (authorText.length() >= 18) {
      String slice = authorText.substring(0, Math.min(48, authorText.length()))
      if (headline.contains(slice)) {
        return true
      }
    }
    Set<String> metaWords = ['latest', 'update', 'updates', 'development', 'developments', 'about',
      'regarding', 'content', 'homepage', 'home', 'page', 'current', 'recent', 'news'] as Set
    List<String> tokens = headline.split(/\s+/).findAll { it?.length() > 2 }
    if (tokens.isEmpty()) {
      return false
    }
    int inAuthor = 0
    int metaInHeadline = 0
    for (String t : tokens) {
      if (authorText.contains(t)) {
        inAuthor++
      }
      if (metaWords.contains(t)) {
        metaInHeadline++
      }
    }
    double overlap = inAuthor / (double) tokens.size()
    return overlap >= 0.65 && metaInHeadline >= 2
  }

  private static boolean isVerbatimForbiddenHeadline(String fieldValue, String forbidden) {
    String a = normalizeComparableText(fieldValue)
    String b = normalizeComparableText(forbidden)
    if (!a || !b) {
      return false
    }
    if (a.equals(b)) {
      return true
    }
    if (a.startsWith(b) && a.length() <= b.length() + 20) {
      return true
    }
    if (b.startsWith(a) && a.length() >= Math.max(12, (int) (b.length() * 0.85))) {
      return true
    }
    return false
  }

  private static String normalizeComparableText(String s) {
    return (s ?: '')
      .replaceAll('(?is)<[^>]+>', ' ')
      .replaceAll('\\s+', ' ')
      .trim()
      .toLowerCase(Locale.ROOT)
  }

  private static String plainTextFromContentXml(String xml, String fieldId) {
    String raw = rawTextFromContentXml(xml, fieldId)
    if (!raw) {
      return ''
    }
    return raw.replaceAll('(?is)<[^>]+>', ' ').replaceAll('\\s+', ' ').trim()
  }

  private static String rawTextFromContentXml(String xml, String fieldId) {
    if (!xml?.trim() || !fieldId?.trim()) {
      return ''
    }
    String id = java.util.regex.Pattern.quote(fieldId.trim())
    def m = (xml =~ /(?is)<${id}>([\s\S]*?)<\/${id}>/)
    if (!m.find()) {
      return ''
    }
    String inner = m.group(1)?.toString() ?: ''
    def cdata = (inner =~ /(?is)<!\[CDATA\[([\s\S]*?)\]\]>/)
    if (cdata.find()) {
      return cdata.group(1)?.toString() ?: ''
    }
    return inner.trim()
  }

  static final String BUNDLE_PREVIEW_VERIFICATION_PHRASES = 'toolsLoopPreviewVerificationPhrases'

  /**
   * After a successful WriteContent, record plain-text snippets from written XML for preview verification.
   */
  static void recordWrittenCopyForPreviewVerification(Map toolsLoopSessionBundle, String contentXml) {
    if (!(toolsLoopSessionBundle instanceof Map) || !contentXml?.trim()) {
      return
    }
    List<Map> copyFields = copyFieldsFromBundle(toolsLoopSessionBundle)
    List<String> fieldIds = copyFields ?
      fieldIdsForWritePolicies(copyFields, [
        'original-headline', 'supporting-copy', 'section-label', 'short-copy', 'rich-copy', 'seo-metadata'
      ]) :
      []
    if (fieldIds.isEmpty()) {
      fieldIds = discoverCopyFieldIdsInContentXml(contentXml)
    }
    List<Map> phrases = []
    for (String fieldId : fieldIds) {
      String text = plainTextFromContentXml(contentXml, fieldId)
      if (!text || text.trim().length() < 8) {
        continue
      }
      String snippet = previewVerificationSnippet(text)
      if (!snippet || snippet.length() < 8) {
        continue
      }
      Map fieldMeta = copyFields ? copyFieldMeta(copyFields, fieldId) : [:]
      phrases.add([
        fieldId     : fieldId,
        text        : text.trim(),
        snippet     : snippet,
        writePolicy : (fieldMeta.writePolicy ?: fieldMeta.copyRole ?: '').toString()
      ])
    }
    toolsLoopSessionBundle.put(BUNDLE_PREVIEW_VERIFICATION_PHRASES, phrases)
  }

  /**
   * Detect FreeMarker / template failures in preview HTML.
   */
  static String detectPreviewRenderingError(String html) {
    String h = (html ?: '').toString()
    if (!h.trim()) {
      return ''
    }
    if ((h =~ /(?is)FreeMarker\s+template\s+error/).find()) {
      def m = (h =~ /(?is)FreeMarker\s+template\s+error[^\n<]*/)
      return m.find() ? m.group(0).trim() : 'FreeMarker template error'
    }
    if ((h =~ /(?is)freemarker\.core\.[A-Za-z]+Exception/).find()) {
      def m = (h =~ /(?is)freemarker\.core\.[A-Za-z]+Exception[^\n<]*/)
      return m.find() ? m.group(0).trim() : 'FreeMarker exception'
    }
    if ((h =~ /(?is)\[in\s+template\s+"[^"]+"\s+at\s+line/).find()) {
      def m = (h =~ /(?is)\[in\s+template\s+"[^"]+"\s+at\s+line[^\n<]*/)
      return m.find() ? m.group(0).trim() : 'Template error'
    }
    if ((h =~ /(?is)Unable\s+to\s+retrieve\s+component\s+template/).find()) {
      return 'Unable to retrieve component template'
    }
    return ''
  }

  /**
   * Verify preview plain text against snippets recorded from successful WriteContent this turn.
   */
  static Map verifyPreviewAgainstWrittenCopy(String previewPlainText, Map toolsLoopSessionBundle) {
    Map noopOk = [found: Boolean.TRUE, reason: 'no_written_copy_recorded', detail: '', checkedPhrase: '', phrasesChecked: []]
    if (!previewPlainText?.trim()) {
      return [found: null, reason: 'empty_preview', detail: 'Preview HTML had no extractable text', checkedPhrase: '', phrasesChecked: []]
    }
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return noopOk
    }
    Object raw = toolsLoopSessionBundle.get(BUNDLE_PREVIEW_VERIFICATION_PHRASES)
    if (!(raw instanceof List) || ((List) raw).isEmpty()) {
      return noopOk
    }
    String plain = normalizeComparableText(previewPlainText)
    List<Map> phrases = (List) raw
    List<String> checked = []
    List<Map> headlineHits = []
    List<Map> otherHits = []
    for (Map entry : phrases) {
      if (!(entry instanceof Map)) {
        continue
      }
      String snippet = (entry.snippet ?: '').toString().trim()
      String fieldId = (entry.fieldId ?: '').toString().trim()
      String policy = (entry.writePolicy ?: '').toString().trim()
      if (!snippet) {
        continue
      }
      checked.add(snippet)
      boolean hit = plainPreviewContainsSnippet(plain, snippet)
      if (hit) {
        if ('original-headline'.equals(policy) || 'section-label'.equals(policy)) {
          headlineHits.add(entry)
        } else {
          otherHits.add(entry)
        }
      }
    }
    if (!headlineHits.isEmpty() || (!otherHits.isEmpty() && phrases.every { !'original-headline'.equals((it.writePolicy ?: '').toString()) })) {
      Map winner = headlineHits ? headlineHits[0] : otherHits[0]
      return [
        found          : Boolean.TRUE,
        reason         : 'written_copy_found',
        detail         : (winner.fieldId ?: '').toString(),
        checkedPhrase  : (winner.snippet ?: winner.text ?: '').toString(),
        phrasesChecked : checked
      ]
    }
    Map firstHeadline = phrases.find {
      'original-headline'.equals((it.writePolicy ?: '').toString()) ||
        'section-label'.equals((it.writePolicy ?: '').toString())
    } as Map
    Map fallback = firstHeadline ?: (phrases[0] as Map)
    String missingSnippet = (fallback?.snippet ?: fallback?.text ?: '').toString()
    return [
      found          : Boolean.FALSE,
      reason         : 'written_copy_not_found',
      detail         : (fallback?.fieldId ?: '').toString(),
      checkedPhrase  : missingSnippet,
      phrasesChecked : checked,
      warning        :
        'Preview HTML does not show the copy saved in this turn' +
          (missingSnippet ? ' (expected something like: "' + abbreviateForWarning(missingSnippet) + '")' : '') +
          '. Open preview in Studio and confirm before publishing.'
    ]
  }

  private static List<String> discoverCopyFieldIdsInContentXml(String contentXml) {
    List<String> ids = []
    if (!contentXml?.trim()) {
      return ids
    }
    def matcher = (contentXml =~ /(?is)<([a-zA-Z0-9_-]+(?:_(?:t|html|s)))(?:\s[^>]*)?>([\s\S]*?)<\/\1>/)
    while (matcher.find()) {
      String id = matcher.group(1)?.toString()?.trim()
      if (!id || id == 'file-name' || id == 'internal-name') {
        continue
      }
      if (id.endsWith('_s') && !id.endsWith('_html')) {
        continue
      }
      if (!id.endsWith('_t') && !id.endsWith('_html')) {
        continue
      }
      String inner = matcher.group(2)?.toString() ?: ''
      String text = inner.replaceAll('(?is)<!\\[CDATA\\[([\\s\\S]*?)\\]\\]>', '$1')
        .replaceAll('(?is)<[^>]+>', ' ').replaceAll('\\s+', ' ').trim()
      if (text.length() >= 8 && !ids.contains(id)) {
        ids.add(id)
      }
    }
    return ids
  }

  private static String previewVerificationSnippet(String text) {
    String normalized = normalizeComparableText(text)
    if (!normalized) {
      return ''
    }
    if (normalized.length() <= 64) {
      return normalized
    }
    int cut = 64
    int space = normalized.lastIndexOf(' ', cut)
    if (space >= 24) {
      cut = space
    }
    return normalized.substring(0, cut).trim()
  }

  private static boolean plainPreviewContainsSnippet(String normalizedPlain, String snippet) {
    String s = (snippet ?: '').trim()
    if (!s || !normalizedPlain) {
      return false
    }
    if (normalizedPlain.contains(s)) {
      return true
    }
    if (s.length() > 24) {
      String shorter = s.substring(0, Math.min(32, s.length())).trim()
      return shorter.length() >= 12 && normalizedPlain.contains(shorter)
    }
    return false
  }

  private static String abbreviateForWarning(String s) {
    String t = (s ?: '').trim()
    if (t.length() <= 100) {
      return t
    }
    return t.substring(0, 97) + '…'
  }
}
