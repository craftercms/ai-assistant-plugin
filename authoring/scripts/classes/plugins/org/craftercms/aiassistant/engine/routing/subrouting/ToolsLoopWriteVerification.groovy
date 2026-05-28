package plugins.org.craftercms.aiassistant.engine.routing.subrouting

import org.dom4j.Document
import org.dom4j.DocumentException
import org.dom4j.Element
import org.dom4j.io.SAXReader
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal.CmsContentExists
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal.CmsGetContent
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal.CmsRepositorySupport
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal.CmsStudioPlaceholderImage

import java.io.StringReader
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

/**
 * Config-driven gate for {@code toolsLoopWriteVerification} (e.g. {@code createFromChatDraft}).
 * All content-type field ids and body checks come from the recipe {@code writeVerification} map only.
 * Taxonomy topics/tags are assigned by the LLM from prefetch + draft — not validated or repaired here.
 */
final class ToolsLoopWriteVerification {

  private static final Logger log = LoggerFactory.getLogger(ToolsLoopWriteVerification)

  private static final Pattern UUID_V4 = Pattern.compile(
    '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
    Pattern.CASE_INSENSITIVE
  )
  /** Crafter objectIds / repository filenames (UUID-shaped; not always RFC 4122 version-4). */
  private static final Pattern CRAFT_OBJECT_ID = Pattern.compile(
    '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
    Pattern.CASE_INSENSITIVE
  )
  private static final Pattern PLACEHOLDER_GROUP = Pattern.compile(
    '^(?:1234|0000|uuid|test)$',
    Pattern.CASE_INSENSITIVE
  )
  private static final int MAX_CUSTOM_DATA_URL_CHARS = 4096

  /**
   * Private constructor; not for direct use.
   */
private ToolsLoopWriteVerification() {}

  /**
   * True when active verification id.
   * @param id Caller-supplied input.
   * @return True when the check succeeds.
   */
  static boolean isActiveVerificationId(String id) {
    return 'createFromChatDraft'.equals((id ?: '').toString().trim())
  }

  /**
   * @param verificationConfig recipe {@code writeVerification} map — site declares all field ids and checks
   */
  static Map verifyAndPrepare(
    StudioToolOperations ops,
    String siteId,
    String repoPath,
    String contentXml,
    Map verificationConfig
  ) {
    WriteVerificationPlan plan = WriteVerificationPlan.from(verificationConfig)
    String normalizedPath = CmsRepositorySupport.normalizeLeadingSlash(repoPath, 'path')
    String xml = (contentXml ?: '').toString()
    if (!xml.trim()) {
      return fail(['contentXml is empty — build a full document before WriteContent.'])
    }

    String xmlForParse = repairUnclosedContentHtmlElements(xml)
    if (!xmlForParse.equals(xml)) {
      xml = xmlForParse
    }

    Document doc
    try {
      doc = CmsRepositorySupport.newHardenedSaxReader().read(new StringReader(xmlForParse))
    } catch (DocumentException e) {
      return fail(["contentXml is not well-formed XML: ${e.message}"])
    }

    Element root = doc?.getRootElement()
    if (root == null) {
      return fail(['contentXml has no root element.'])
    }

    List<String> repairs = []
    Element inline = plan.inlineCollectionFieldId ?
      findFirstInlineComponent(root, plan.inlineCollectionFieldId) :
      null

    if (plan.repairRootObjectIds) {
      repairRootObjectIds(root, repairs)
    }
    if (plan.repairInlineObjectIds && inline != null) {
      repairInlineObjectIds(root, inline, repairs)
    }
    if (plan.repairRootDates && !plan.dateFieldIds.isEmpty()) {
      repairRootDates(root, plan.dateFieldIds, repairs)
    }
    if (plan.repairOversizedImagePickerDataUrls) {
      repairOversizedImagePickerDataUrls(root, repairs)
    }
    applyDeriveRootFieldsFromBody(root, inline, plan, repairs)
    applyPrefetchPriorDerivedRootFields(root, verificationConfig, repairs)
    repairNodeSelectorFromPrefetch(root, plan, verificationConfig, repairs)
    repairImagePickerCopiedFromSibling(ops, siteId, root, plan, verificationConfig, repairs)
    repairFileNameMatchesPath(root, normalizedPath, repairs)
    if (plan.repairHeadlineFromPageTitle) {
      repairHeadlineFromPageTitle(root, plan.headlineSourceFieldId, plan.headlineTargetFieldId, repairs)
    }

    List<String> errors = []
    validateFileNameMatchesPath(root, normalizedPath, errors)

    if (plan.repairRootObjectIds || plan.requireValidRootObjectIds) {
      validateRootObjectIds(root, errors)
    }
    if (plan.inlineCollectionFieldId && plan.requireDistinctInlineObjectIds) {
      validateInlineObjectIds(root, inline, plan.inlineCollectionFieldId, errors)
    }

    if (plan.requireRootDates && !plan.dateFieldIds.isEmpty()) {
      validateRootDates(root, plan.dateFieldIds, errors)
    }

    if (plan.minBodyTextChars > 0 && plan.bodyTextFieldId) {
      Element bodyHost = inline ?: root
      validateBodyTextLength(bodyHost, plan.bodyTextFieldId, plan.minBodyTextChars, errors)
    }

    for (String fieldId : plan.requiredRootFields) {
      if (!textTrim(root, fieldId)) {
        errors.add("Missing required root field <${fieldId}> (see writeVerification.requiredRootFields and project context).")
      }
    }

    for (Map nodeSel : plan.nodeSelectorFields) {
      validateNodeSelectorField(ops, siteId, root, nodeSel, verificationConfig, errors)
    }

    for (Map imgSpec : plan.imagePickerFields) {
      validateImagePickerField(ops, siteId, root, imgSpec, verificationConfig, errors)
    }

    if (!errors.isEmpty()) {
      return [
        ok     : false,
        errors : Collections.unmodifiableList(errors),
        repairs: repairs
      ]
    }

    Map result = [
      ok        : true,
      contentXml: doc.asXML(),
      repairs   : repairs
    ]
    if (!repairs.isEmpty()) {
      log.info('toolsLoop write verification: applied {} repair(s) path={}', repairs.size(), normalizedPath)
    }
    return result
  }

  private static final class WriteVerificationPlan {
    boolean repairRootObjectIds = true
    boolean requireValidRootObjectIds = true
    boolean repairInlineObjectIds = false
    boolean requireDistinctInlineObjectIds = false
    String inlineCollectionFieldId = ''
    boolean repairRootDates = false
    boolean requireRootDates = false
    List<String> dateFieldIds = []
    boolean repairOversizedImagePickerDataUrls = true
    int minBodyTextChars = 0
    String bodyTextFieldId = ''
    List<String> requiredRootFields = []
    List<Map> nodeSelectorFields = []
    List<Map> deriveRootFieldsFromBody = []
    List<Map> imagePickerFields = []
    boolean repairHeadlineFromPageTitle = false
    String headlineSourceFieldId = ''
    String headlineTargetFieldId = ''

    /**
     * From.
     * @param cfg Caller-supplied input.
     * @return WriteVerificationPlan result.
     */
    static WriteVerificationPlan from(Map cfg) {
      Map c = cfg instanceof Map ? cfg : [:]
      WriteVerificationPlan p = new WriteVerificationPlan()
      p.repairRootObjectIds = boolConfig(c, 'repairRootObjectIds', true)
      p.requireValidRootObjectIds = boolConfig(c, 'requireValidRootObjectIds', true)
      p.repairOversizedImagePickerDataUrls = boolConfig(c, 'repairOversizedImagePickerDataUrls', true)

      String inlineColl = (c.inlineComponent instanceof Map ?
        ((Map) c.inlineComponent).collectionFieldId :
        c.inlineCollectionFieldId) ?: ''
      p.inlineCollectionFieldId = inlineColl.toString().trim()
      if (p.inlineCollectionFieldId) {
        p.repairInlineObjectIds = boolConfig(c, 'repairInlineObjectIds', true)
        p.requireDistinctInlineObjectIds = boolConfig(c, 'requireDistinctInlineObjectIds', true)
      }

      p.dateFieldIds = stringList(c.dateFieldIds)
      if (p.dateFieldIds.isEmpty() && c.dateFields instanceof List) {
        p.dateFieldIds = stringList(c.dateFields)
      }
      p.repairRootDates = boolConfig(c, 'repairRootDates', false)
      p.requireRootDates = boolConfig(c, 'requireRootDates', false) ||
        boolConfig(c, 'requirePostLevelDates', false)

      p.minBodyTextChars = intConfig(c, 'minBodyTextChars', 0)
      p.bodyTextFieldId = (c.bodyTextFieldId ?: '').toString().trim()
      p.requiredRootFields = stringList(c.requiredRootFields)
      p.nodeSelectorFields = normalizeNodeSelectorFields(c)
      p.deriveRootFieldsFromBody = normalizeDeriveRootFields(c)

      p.imagePickerFields = normalizeImagePickerFields(c)
      p.repairHeadlineFromPageTitle = boolConfig(c, 'repairHeadlineFromPageTitle', false)
      if (p.repairHeadlineFromPageTitle) {
        p.headlineSourceFieldId = (c.headlineSourceFieldId ?: 'pageTitle_s').toString().trim()
        p.headlineTargetFieldId = (c.headlineTargetFieldId ?: 'headline_s').toString().trim()
        if (!p.headlineSourceFieldId || !p.headlineTargetFieldId) {
          p.repairHeadlineFromPageTitle = false
        }
      }
      return p
    }

    /**
     * Normalizes and validates node selector fields; throws when required values are missing.
     * @param cfg Caller-supplied input.
     * @return List<Map> result.
     */
    private static List<Map> normalizeNodeSelectorFields(Map cfg) {
      List<Map> out = []
      if (!(cfg.nodeSelectorFields instanceof List)) {
        return out
      }
      for (Object entry : (List) cfg.nodeSelectorFields) {
        if (!(entry instanceof Map)) {
          continue
        }
        Map m = (Map) entry
        String fieldId = (m.fieldId ?: '').toString().trim()
        if (!fieldId) {
          continue
        }
        out.add([
          fieldId                            : fieldId,
          minItems                           : Math.max(0, intConfig(m, 'minItems', 0)),
          requireExistingPath                : Boolean.TRUE.equals(m.requireExistingPath),
          requireUuidStyleRepositoryFilename : Boolean.TRUE.equals(m.requireUuidStyleRepositoryFilename),
          forbiddenPathSubstrings            : stringList(m.forbiddenPathSubstrings)
        ])
      }
      return out
    }

    /**
     * Normalizes and validates derive root fields; throws when required values are missing.
     * @param cfg Caller-supplied input.
     * @return List<Map> result.
     */
    private static List<Map> normalizeDeriveRootFields(Map cfg) {
      List<Map> out = []
      if (!(cfg.deriveRootFieldsFromBody instanceof List)) {
        return out
      }
      for (Object entry : (List) cfg.deriveRootFieldsFromBody) {
        if (!(entry instanceof Map)) {
          continue
        }
        Map m = (Map) entry
        String fieldId = (m.fieldId ?: '').toString().trim()
        String bodyFieldId = (m.bodyTextFieldId ?: '').toString().trim()
        if (!fieldId || !bodyFieldId) {
          continue
        }
        out.add([
          fieldId      : fieldId,
          bodyTextFieldId: bodyFieldId,
          maxLength    : Math.max(1, intConfig(m, 'maxLength', 250)),
          strategy     : (m.strategy ?: 'firstSentence').toString().trim()
        ])
      }
      return out
    }

    /**
     * Normalizes and validates image picker fields; throws when required values are missing.
     * @param cfg Caller-supplied input.
     * @return List<Map> result.
     */
    private static List<Map> normalizeImagePickerFields(Map cfg) {
      List<Map> out = []
      if (!(cfg.imagePickerFields instanceof List)) {
        return out
      }
      for (Object entry : (List) cfg.imagePickerFields) {
        if (!(entry instanceof Map)) {
          continue
        }
        Map m = (Map) entry
        String fieldId = (m.fieldId ?: '').toString().trim()
        if (!fieldId) {
          continue
        }
        String siblingField = (m.forbidSameValueAsSiblingField ?: m.siblingFieldId ?: fieldId).toString().trim()
        out.add([
          fieldId                               : fieldId,
          forbidSameValueAsSiblingField         : siblingField,
          repairWithStudioPlaceholderWhenForbidden: Boolean.TRUE.equals(m.repairWithStudioPlaceholderWhenForbidden) ||
            Boolean.TRUE.equals(m.repairWithPlaceholderWhenForbidden)
        ])
      }
      return out
    }
  }

  /**
   * Fail.
   * @param errors Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  private static Map fail(List<String> errors) {
    return [
      ok    : false,
      errors: Collections.unmodifiableList(new ArrayList<>(errors))
    ]
  }

  /**
   * Bool config.
   * @param cfg Caller-supplied input.
   * @param key Caller-supplied input.
   * @param defaultVal Caller-supplied input.
   * @return True when the check succeeds.
   */
  private static boolean boolConfig(Map cfg, String key, boolean defaultVal) {
    Object v = cfg?.get(key)
    return v instanceof Boolean ? (Boolean) v : defaultVal
  }

  /**
   * Int config.
   * @param cfg Caller-supplied input.
   * @param key Caller-supplied input.
   * @param defaultVal Caller-supplied input.
   * @return int result.
   */
  private static int intConfig(Map cfg, String key, int defaultVal) {
    Object v = cfg?.get(key)
    if (v instanceof Number) {
      return Math.max(0, ((Number) v).intValue())
    }
    return defaultVal
  }

  /**
   * Applies prefetch prior derived root fields to repository content or orchestration state.
   */
  private static void applyPrefetchPriorDerivedRootFields(
    Element root,
    Map verificationConfig,
    List<String> repairs
  ) {
    Object raw = verificationConfig?._prefetchPriorDerivedRootFieldValues
    if (!(raw instanceof Map)) {
      return
    }
    for (Map.Entry e : ((Map) raw).entrySet()) {
      String fieldId = (e.key ?: '').toString().trim()
      String value = (e.value ?: '').toString().trim()
      if (!fieldId || !value || textTrim(root, fieldId)) {
        continue
      }
      setChildText(root, fieldId, value)
      repairs.add("Filled <${fieldId}> from prefetch priorDerivedRootFieldValues")
    }
  }

  /**
   * Repair node selector from prefetch.
   */
  private static void repairNodeSelectorFromPrefetch(
    Element root,
    WriteVerificationPlan plan,
    Map verificationConfig,
    List<String> repairs
  ) {
    Object raw = verificationConfig?._prefetchNodeSelectorCandidates
    String priorLabel = (verificationConfig?._prefetchPriorAuthorLabel ?: '').toString().trim()
    if (!(raw instanceof List) || !priorLabel) {
      return
    }
    String chosenPath = pickNodeSelectorPathFromCandidates((List) raw, priorLabel)
    if (!chosenPath) {
      return
    }
    for (Map spec : plan.nodeSelectorFields) {
      String fieldId = (spec.fieldId ?: '').toString().trim()
      if (!fieldId) {
        continue
      }
      Element field = root.element(fieldId)
      if (field == null) {
        field = root.addElement(fieldId)
        field.addAttribute('item-list', 'true')
      }
      List<Element> items = field.elements('item')
      Element item = items.isEmpty() ? field.addElement('item') : items[0]
      String currentKey = textTrim(item, 'key')
      String currentBase = currentKey ?
        currentKey.replaceAll(/.*\//, '').replaceAll(/(?i)\.xml$/, '') :
        ''
      if (currentKey && !currentKey.contains('{') &&
        (isCrafterStyleObjectId(currentBase) || prefetchNodeSelectorPaths(verificationConfig).contains(currentKey))) {
        continue
      }
      setChildText(item, 'key', chosenPath)
      setChildText(item, 'include', chosenPath)
      String label = labelForNodeSelectorPath((List) raw, chosenPath)
      if (label) {
        setChildText(item, 'value', label)
      }
      ensureChildText(item, 'disableFlattening', 'false')
      repairs.add("Set `${fieldId}` to prefetch path matching prior author label")
    }
  }

  /**
   * Pick node selector path from candidates.
   * @param candidates Caller-supplied input.
   * @param priorLabel Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String pickNodeSelectorPathFromCandidates(List candidates, String priorLabel) {
    String needle = priorLabel.toLowerCase(Locale.ROOT)
    String firstToken = needle.split(/\s+/)[0]?.trim() ?: ''
    Map best = null
    int bestScore = -1
    for (Object o : candidates) {
      if (!(o instanceof Map)) {
        continue
      }
      Map row = (Map) o
      String path = (row.path ?: '').toString().trim()
      if (!path) {
        continue
      }
      String label = (
        row.get('internal-name') ?: row.displayName ?: row.get('internalName') ?: ''
      ).toString().toLowerCase(Locale.ROOT)
      int score = 0
      if (label.contains(needle)) {
        score += 10
      } else if (firstToken && label.contains(firstToken)) {
        score += 6
      } else if (firstToken && path.toLowerCase(Locale.ROOT).contains(firstToken)) {
        score += 2
      }
      if (score > bestScore) {
        bestScore = score
        best = row
      }
    }
    return bestScore > 0 ? (best.path ?: '').toString().trim() : ''
  }

  /**
   * Label for node selector path.
   * @param candidates Caller-supplied input.
   * @param path Studio or repository context for this call.
   * @return Text result, or empty or null when unavailable.
   */
  private static String labelForNodeSelectorPath(List candidates, String path) {
    for (Object o : candidates) {
      if (!(o instanceof Map)) {
        continue
      }
      Map row = (Map) o
      if (path.equalsIgnoreCase((row.path ?: '').toString().trim())) {
        return (row.get('internal-name') ?: row.displayName ?: '').toString().trim()
      }
    }
    return ''
  }

  /**
   * Repair image picker copied from sibling.
   */
  private static void repairImagePickerCopiedFromSibling(
    StudioToolOperations ops,
    String siteId,
    Element root,
    WriteVerificationPlan plan,
    Map verificationConfig,
    List<String> repairs
  ) {
    if (plan.imagePickerFields.isEmpty()) {
      return
    }
    String siblingPath = (verificationConfig?._siblingRepositoryPath ?: '').toString().trim()
    if (!siblingPath || ops == null || !siteId) {
      return
    }
    Map siblingValues = siblingFieldValues(ops, siteId, siblingPath, plan.imagePickerFields)
    if (siblingValues.isEmpty()) {
      return
    }
    for (Map spec : plan.imagePickerFields) {
      String fieldId = (spec.fieldId ?: '').toString().trim()
      String siblingField = (spec.forbidSameValueAsSiblingField ?: fieldId).toString().trim()
      String siblingVal = (siblingValues.get(siblingField) ?: '').toString().trim()
      if (!fieldId || !siblingVal) {
        continue
      }
      String current = textTrim(root, fieldId)
      if (!current || !current.equals(siblingVal)) {
        continue
      }
      if (!Boolean.TRUE.equals(spec.repairWithStudioPlaceholderWhenForbidden)) {
        continue
      }
      setChildText(root, fieldId, CmsStudioPlaceholderImage.defaultRequiredEmptyImagePickerDataUrl())
      repairs.add("Replaced `${fieldId}` copied from sibling with Studio sample placeholder")
    }
  }

  private static Map<String, String> siblingFieldValues(
    StudioToolOperations ops,
    String siteId,
    String siblingPath,
    List<Map> imagePickerFields
  ) {
    Map<String, String> out = [:]
    try {
      Map res = CmsGetContent.read(ops, siteId, siblingPath) as Map
      String xml = (res?.contentXml ?: '').toString()
      if (!xml.trim()) {
        return out
      }
      Document doc = CmsRepositorySupport.newHardenedSaxReader().read(new StringReader(xml))
      Element sibRoot = doc?.getRootElement()
      if (sibRoot == null) {
        return out
      }
      Set<String> fieldIds = new LinkedHashSet<>()
      for (Map spec : imagePickerFields) {
        String fieldId = (spec.fieldId ?: '').toString().trim()
        String siblingField = (spec.forbidSameValueAsSiblingField ?: fieldId).toString().trim()
        if (fieldId) {
          fieldIds.add(fieldId)
        }
        if (siblingField) {
          fieldIds.add(siblingField)
        }
      }
      for (String fieldId : fieldIds) {
        String v = textTrim(sibRoot, fieldId)
        if (v) {
          out.put(fieldId, v)
        }
      }
    } catch (Throwable ignored) {
    }
    return out
  }

  /**
   * Validate image picker field.
   */
  private static void validateImagePickerField(
    StudioToolOperations ops,
    String siteId,
    Element root,
    Map spec,
    Map verificationConfig,
    List<String> errors
  ) {
    String fieldId = (spec.fieldId ?: '').toString().trim()
    if (!fieldId) {
      return
    }
    String current = textTrim(root, fieldId)
    if (!current) {
      return
    }
    String siblingPath = (verificationConfig?._siblingRepositoryPath ?: '').toString().trim()
    if (!siblingPath || ops == null || !siteId) {
      return
    }
    Map siblingValues = siblingFieldValues(ops, siteId, siblingPath, [spec])
    String siblingField = (spec.forbidSameValueAsSiblingField ?: fieldId).toString().trim()
    String siblingVal = (siblingValues.get(siblingField) ?: '').toString().trim()
    if (siblingVal && current.equals(siblingVal)) {
      errors.add(
        "`<${fieldId}>` must not reuse the sibling item's image — use the Studio sample placeholder or author-provided art."
      )
    }
  }

  /**
   * Applies derive root fields from body to repository content or orchestration state.
   */
  private static void applyDeriveRootFieldsFromBody(
    Element root,
    Element inline,
    WriteVerificationPlan plan,
    List<String> repairs
  ) {
    if (!plan.deriveRootFieldsFromBody) {
      return
    }
    Element bodyHost = inline ?: root
    for (Map spec : plan.deriveRootFieldsFromBody) {
      String fieldId = (spec.fieldId ?: '').toString()
      if (!fieldId || textTrim(root, fieldId)) {
        continue
      }
      String bodyFieldId = (spec.bodyTextFieldId ?: '').toString()
      String plain = plainTextFromField(bodyHost, bodyFieldId)
      if (!plain) {
        continue
      }
      int maxLen = spec.maxLength instanceof Number ? ((Number) spec.maxLength).intValue() : 250
      String derived = 'firstSentence'.equalsIgnoreCase((spec.strategy ?: '').toString()) ?
        firstSentence(plain, maxLen) :
        plain.length() > maxLen ? plain.substring(0, Math.max(0, maxLen - 3)).trim() + '...' : plain
      if (derived) {
        setChildText(root, fieldId, derived)
        repairs.add("Filled <${fieldId}> from inline ${bodyFieldId} (writeVerification.deriveRootFieldsFromBody)")
      }
    }
  }

  /**
   * Prefetch node selector paths.
   * @param verificationConfig Caller-supplied input.
   * @return Set<String> result.
   */
  private static Set<String> prefetchNodeSelectorPaths(Map verificationConfig) {
    Set<String> paths = new LinkedHashSet<>()
    Object raw = verificationConfig?._prefetchNodeSelectorCandidates
    if (!(raw instanceof List)) {
      return paths
    }
    for (Object o : (List) raw) {
      if (o instanceof Map) {
        String p = (o.path ?: '').toString().trim()
        if (p) {
          paths.add(p)
        }
      }
    }
    return paths
  }

  /**
   * Validate node selector field.
   */
  private static void validateNodeSelectorField(
    StudioToolOperations ops,
    String siteId,
    Element root,
    Map spec,
    Map verificationConfig,
    List<String> errors
  ) {
    String fieldId = (spec.fieldId ?: '').toString()
    int minItems = spec.minItems instanceof Number ? ((Number) spec.minItems).intValue() : 0
    Element field = root.element(fieldId)
    List<Element> items = field != null ? field.elements('item') : []
    if (minItems > 0 && items.size() < minItems) {
      errors.add("`${fieldId}` needs at least ${minItems} referenced item(s) — use a path from prefetch, not a invented slug.")
    }
    List<String> forbidden = spec.forbiddenPathSubstrings instanceof List ?
      stringList(spec.forbiddenPathSubstrings) :
      []
    boolean requireExists = Boolean.TRUE.equals(spec.requireExistingPath)
    for (Element item : items) {
      String key = textTrim(item, 'key')
      String include = textTrim(item, 'include')
      String path = key ?: include
      if (!path) {
        errors.add("`${fieldId}` item needs <key> and <include> with the same repository path ending in .xml.")
        continue
      }
      if (key && include && !key.equals(include)) {
        errors.add("`${fieldId}` <key> and <include> must be identical.")
      }
      if (!path.toLowerCase(Locale.ROOT).endsWith('.xml')) {
        errors.add("`${fieldId}` path must end with .xml — copy a real path from prefetch.")
      }
      if (Boolean.TRUE.equals(spec.requireUuidStyleRepositoryFilename)) {
        String baseName = path.replaceAll(/.*\//, '').replaceAll(/(?i)\.xml$/, '')
        Set<String> prefetchPaths = prefetchNodeSelectorPaths(verificationConfig)
        if (!isCrafterStyleObjectId(baseName) && !prefetchPaths.contains(path)) {
          errors.add(
            "`${fieldId}` path `${path}` must use a real repository path from prefetch — " +
              'do not invent human-readable slug file-names.'
          )
        }
      }
      String pathLower = path.toLowerCase(Locale.ROOT)
      for (String bad : forbidden) {
        if (bad && pathLower.contains(bad.toLowerCase(Locale.ROOT))) {
          errors.add("`${fieldId}` path `${path}` is not allowed (forbidden fragment `${bad}`) — pick a path from prefetch bindings.")
          break
        }
      }
      if (requireExists && ops != null && siteId) {
        try {
          Map exists = CmsContentExists.probe(ops, siteId, path, null) as Map
          if (!Boolean.TRUE.equals(exists?.get('exists'))) {
            errors.add("`${fieldId}` path `${path}` does not exist in the repository — use a path from prefetch.")
          }
        } catch (Throwable ignored) {
          errors.add("Could not verify `${fieldId}` path `${path}`.")
        }
      }
    }
  }

  /**
   * String list.
   * @param raw Caller-supplied input.
   * @return List<String> result.
   */
  private static List<String> stringList(Object raw) {
    if (!(raw instanceof List)) {
      return []
    }
    List<String> out = []
    for (Object o : (List) raw) {
      String s = (o ?: '').toString().trim()
      if (s) {
        out.add(s)
      }
    }
    return out
  }

  /**
   * Repair root object ids.
   * @param root Caller-supplied input.
   * @param repairs Caller-supplied input.
   */
  private static void repairRootObjectIds(Element root, List<String> repairs) {
    String rootId = textTrim(root, 'objectId')
    String rootGroup = textTrim(root, 'objectGroupId')
    boolean rootBad = !isValidUuidV4(rootId) || PLACEHOLDER_GROUP.matcher(rootGroup ?: '').matches()
    if (rootBad) {
      String newA = UUID.randomUUID().toString().toLowerCase(Locale.ROOT)
      setChildText(root, 'objectId', newA)
      setChildText(root, 'objectGroupId', objectGroupFromUuid(newA))
      repairs.add('Assigned new root objectId/objectGroupId')
      rootId = newA
    } else if (!rootGroup || PLACEHOLDER_GROUP.matcher(rootGroup).matches() ||
      !rootGroup.equalsIgnoreCase(objectGroupFromUuid(rootId))) {
      setChildText(root, 'objectGroupId', objectGroupFromUuid(rootId))
      repairs.add('Corrected root objectGroupId from objectId')
    }
  }

  /**
   * Repair inline object ids.
   * @param root Caller-supplied input.
   * @param inline Caller-supplied input.
   * @param repairs Caller-supplied input.
   */
  private static void repairInlineObjectIds(Element root, Element inline, List<String> repairs) {
    String rootId = textTrim(root, 'objectId')
    String inlineId = textTrim(inline, 'objectId')
    boolean inlineBad = !isValidUuidV4(inlineId) || inlineId.equalsIgnoreCase(rootId)
    if (inlineBad) {
      String newB = UUID.randomUUID().toString().toLowerCase(Locale.ROOT)
      while (newB.equalsIgnoreCase(rootId)) {
        newB = UUID.randomUUID().toString().toLowerCase(Locale.ROOT)
      }
      applyInlineObjectId(inline, newB)
      repairs.add('Assigned distinct inline objectId/objectGroupId')
      return
    }
    String ig = textTrim(inline, 'objectGroupId')
    if (!ig || PLACEHOLDER_GROUP.matcher(ig).matches() ||
      !ig.equalsIgnoreCase(objectGroupFromUuid(inlineId))) {
      setChildText(inline, 'objectGroupId', objectGroupFromUuid(inlineId))
      repairs.add('Corrected inline objectGroupId from objectId')
    }
    syncInlineKeys(inline, inlineId)
  }

  /**
   * Applies inline object id to repository content or orchestration state.
   * @param inline Caller-supplied input.
   * @param uuidB Caller-supplied input.
   */
  private static void applyInlineObjectId(Element inline, String uuidB) {
    setChildText(inline, 'objectId', uuidB)
    setChildText(inline, 'objectGroupId', objectGroupFromUuid(uuidB))
    syncInlineKeys(inline, uuidB)
  }

  /**
   * Sync inline keys.
   * @param inline Caller-supplied input.
   * @param uuidB Caller-supplied input.
   */
  private static void syncInlineKeys(Element inline, String uuidB) {
    Element parentItem = inline.parent
    if (parentItem != null && 'item'.equals(parentItem.name)) {
      setChildText(parentItem, 'key', uuidB)
    }
    inline.addAttribute('id', uuidB)
    setChildText(inline, 'file-name', "${uuidB}.xml")
  }

  /**
   * Repair root dates.
   * @param root Caller-supplied input.
   * @param dateFieldIds Caller-supplied input.
   * @param repairs Caller-supplied input.
   */
  private static void repairRootDates(Element root, List<String> dateFieldIds, List<String> repairs) {
    if (hasAllDateFields(root, dateFieldIds)) {
      return
    }
    String now = isoNow()
    for (String fieldId : dateFieldIds) {
      if (!textTrim(root, fieldId)) {
        String val = fieldId.endsWith('_dt') ? nowDt(now) : now
        ensureChildText(root, fieldId, val)
      }
    }
    repairs.add('Added missing root date fields from writeVerification.dateFieldIds')
  }

  /**
   * Repair oversized image picker data urls.
   * @param root Caller-supplied input.
   * @param repairs Caller-supplied input.
   */
  private static void repairOversizedImagePickerDataUrls(Element root, List<String> repairs) {
    for (Iterator it = root.elementIterator(); it.hasNext();) {
      Element child = (Element) it.next()
      String name = child.name
      if (!name?.endsWith('_s')) {
        continue
      }
      String v = child.getTextTrim()
      if (!v || !v.toLowerCase(Locale.ROOT).startsWith('data:image')) {
        continue
      }
      if (v.length() > MAX_CUSTOM_DATA_URL_CHARS &&
        !v.equals(CmsStudioPlaceholderImage.defaultRequiredEmptyImagePickerDataUrl())) {
        child.setText(CmsStudioPlaceholderImage.defaultRequiredEmptyImagePickerDataUrl())
        repairs.add("Replaced oversized ${name} data URL with Studio sample placeholder")
      }
    }
  }

  /**
   * Repair unclosed content html elements.
   * @param xml Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String repairUnclosedContentHtmlElements(String xml) {
    String s = (xml ?: '').toString()
    if (!s.contains('<content_html>')) {
      return s
    }
    int opens = s.split('<content_html>', -1).length - 1
    int closes = s.split('</content_html>', -1).length - 1
    if (closes >= opens) {
      return s
    }
    int lastOpen = s.lastIndexOf('<content_html>')
    if (lastOpen < 0) {
      return s
    }
    int insertAt = s.indexOf('</component>', lastOpen)
    if (insertAt < 0) {
      return s
    }
    int missing = opens - closes
    StringBuilder closers = new StringBuilder()
    for (int i = 0; i < missing; i++) {
      closers.append('</content_html>')
    }
    return s.substring(0, insertAt) + closers.toString() + s.substring(insertAt)
  }

  /**
   * Repair file name matches path.
   * @param root Caller-supplied input.
   * @param path Studio or repository context for this call.
   * @param repairs Caller-supplied input.
   */
  private static void repairFileNameMatchesPath(Element root, String path, List<String> repairs) {
    String fn = textTrim(root, 'file-name')
    if (!path?.trim()) {
      return
    }
    String base = path.contains('/') ? path.substring(path.lastIndexOf('/') + 1) : path
    if (!base) {
      return
    }
    if (!fn || !base.equalsIgnoreCase(fn)) {
      setChildText(root, 'file-name', base)
      repairs.add('Aligned <file-name> with WriteContent path basename')
    }
  }

  /**
   * Repair headline from page title.
   * @param root Caller-supplied input.
   * @param repairs Caller-supplied input.
   */
  private static void repairHeadlineFromPageTitle(
    Element root,
    String sourceFieldId,
    String targetFieldId,
    List<String> repairs
  ) {
    if (textTrim(root, targetFieldId)) {
      return
    }
    String source = textTrim(root, sourceFieldId)
    if (!source) {
      return
    }
    setChildText(root, targetFieldId, source)
    repairs.add("Filled <${targetFieldId}> from <${sourceFieldId}> (writeVerification.repairHeadlineFromPageTitle)")
  }

  /**
   * Validate file name matches path.
   * @param root Caller-supplied input.
   * @param path Studio or repository context for this call.
   * @param errors Caller-supplied input.
   */
  private static void validateFileNameMatchesPath(Element root, String path, List<String> errors) {
    String fn = textTrim(root, 'file-name')
    if (!fn) {
      errors.add('Missing root <file-name>.')
      return
    }
    String base = path?.contains('/') ? path.substring(path.lastIndexOf('/') + 1) : path
    if (base && !base.equalsIgnoreCase(fn)) {
      errors.add("<file-name> `${fn}` does not match WriteContent path `${path}`.")
    }
  }

  /**
   * Validate root object ids.
   * @param root Caller-supplied input.
   * @param errors Caller-supplied input.
   */
  private static void validateRootObjectIds(Element root, List<String> errors) {
    String rootId = textTrim(root, 'objectId')
    String rootGroup = textTrim(root, 'objectGroupId')
    if (!isValidUuidV4(rootId)) {
      errors.add('Root objectId must be a UUID v4.')
    }
    if (!rootGroup || !rootGroup.equalsIgnoreCase(objectGroupFromUuid(rootId))) {
      errors.add('Root objectGroupId must be the first four hex characters of objectId.')
    }
  }

  /**
   * Validate inline object ids.
   */
  private static void validateInlineObjectIds(
    Element root,
    Element inline,
    String collectionFieldId,
    List<String> errors
  ) {
    if (inline == null) {
      errors.add("Missing inline <component> under `${collectionFieldId}` (see writeVerification.inlineComponent).")
      return
    }
    String rootId = textTrim(root, 'objectId')
    String inlineId = textTrim(inline, 'objectId')
    if (!isValidUuidV4(inlineId)) {
      errors.add('Inline component objectId must be a UUID v4.')
    }
    if (inlineId.equalsIgnoreCase(rootId)) {
      errors.add('Root and inline component must use different objectId values.')
    }
    String inlineGroup = textTrim(inline, 'objectGroupId')
    if (!inlineGroup || !inlineGroup.equalsIgnoreCase(objectGroupFromUuid(inlineId))) {
      errors.add('Inline objectGroupId must be the first four hex characters of inline objectId.')
    }
  }

  /**
   * Validate root dates.
   * @param root Caller-supplied input.
   * @param dateFieldIds Caller-supplied input.
   * @param errors Caller-supplied input.
   */
  private static void validateRootDates(Element root, List<String> dateFieldIds, List<String> errors) {
    for (String fieldId : dateFieldIds) {
      if (!textTrim(root, fieldId)) {
        errors.add("Missing root <${fieldId}> (listed in writeVerification.dateFieldIds).")
      }
    }
  }

  /**
   * True when s all date fields.
   * @param root Caller-supplied input.
   * @param dateFieldIds Caller-supplied input.
   * @return True when the check succeeds.
   */
  private static boolean hasAllDateFields(Element root, List<String> dateFieldIds) {
    for (String fieldId : dateFieldIds) {
      if (!textTrim(root, fieldId)) {
        return false
      }
    }
    return true
  }

  /**
   * Validate body text length.
   */
  private static void validateBodyTextLength(
    Element bodyHost,
    String bodyFieldId,
    int minChars,
    List<String> errors
  ) {
    Element ch = bodyHost?.element(bodyFieldId)
    String html = ch != null ? (ch.getText() ?: '') : ''
    String plain = html.replaceAll(/<[^>]+>/, ' ').replaceAll(/\s+/, ' ').trim()
    if (plain.length() < minChars) {
      errors.add(
        "Field `${bodyFieldId}` is too short (${plain.length()} chars, need at least ${minChars}) — include the full prior-chat draft."
      )
    }
  }

  /**
   * Plain text from field.
   * @param host Caller-supplied input.
   * @param fieldId Identifier for the target resource.
   * @return Text result, or empty or null when unavailable.
   */
  private static String plainTextFromField(Element host, String fieldId) {
    if (host == null || !fieldId) {
      return ''
    }
    Element ch = host.element(fieldId)
    if (ch == null) {
      return ''
    }
    return (ch.getText() ?: '').replaceAll(/<[^>]+>/, ' ').replaceAll(/\s+/, ' ').trim()
  }

  /**
   * First sentence.
   * @param plain Caller-supplied input.
   * @param maxLen Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String firstSentence(String plain, int maxLen) {
    String t = (plain ?: '').trim()
    if (!t) {
      return ''
    }
    int end = t.indexOf('. ')
    String sentence = end > 20 ? t.substring(0, end + 1) : t
    if (sentence.length() > maxLen) {
      sentence = sentence.substring(0, Math.max(0, maxLen - 3)).trim() + '...'
    }
    return sentence
  }

  /**
   * Find first inline component.
   * @param root Caller-supplied input.
   * @param collectionFieldId Identifier for the target resource.
   * @return Element result.
   */
  private static Element findFirstInlineComponent(Element root, String collectionFieldId) {
    Element co = root?.element(collectionFieldId)
    if (co == null) {
      return null
    }
    for (Element item : co.elements('item')) {
      Element comp = item.element('component')
      if (comp != null) {
        return comp
      }
    }
    return null
  }

  /**
   * True when valid uuid v4.
   * @param s Caller-supplied input.
   * @return True when the check succeeds.
   */
  static boolean isValidUuidV4(String s) {
    return s != null && UUID_V4.matcher(s.trim()).matches()
  }

  /** UUID-shaped Crafter ids (repository filenames); not limited to RFC 4122 version-4. */
  static boolean isCrafterStyleObjectId(String s) {
    return s != null && CRAFT_OBJECT_ID.matcher(s.trim()).matches()
  }

  /**
   * Object group from uuid.
   * @param uuid Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String objectGroupFromUuid(String uuid) {
    String hex = (uuid ?: '').replace('-', '').toLowerCase(Locale.ROOT)
    return hex.length() >= 4 ? hex.substring(0, 4) : ''
  }

  /**
   * Text trim.
   * @param parent Caller-supplied input.
   * @param childName Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String textTrim(Element parent, String childName) {
    if (parent == null) {
      return ''
    }
    Element c = parent.element(childName)
    return c != null ? (c.getTextTrim() ?: '') : ''
  }

  /**
   * Updates child text.
   * @param parent Caller-supplied input.
   * @param childName Caller-supplied input.
   * @param value Caller-supplied input.
   */
  private static void setChildText(Element parent, String childName, String value) {
    Element c = parent.element(childName)
    if (c == null) {
      c = parent.addElement(childName)
    }
    c.setText(value ?: '')
  }

  /**
   * Ensure child text.
   * @param parent Caller-supplied input.
   * @param childName Caller-supplied input.
   * @param value Caller-supplied input.
   */
  private static void ensureChildText(Element parent, String childName, String value) {
    if (!textTrim(parent, childName)) {
      setChildText(parent, childName, value)
    }
  }

  /**
   * True when o now.
   * @return Text result, or empty or null when unavailable.
   */
  private static String isoNow() {
    return DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace('Z', 'Z')
  }

  /**
   * Now dt.
   * @param iso Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String nowDt(String iso) {
    String s = (iso ?: '').trim()
    if (!s) {
      return ''
    }
    return s.contains('.') ? s : s.replace('Z', '.000Z')
  }
}
