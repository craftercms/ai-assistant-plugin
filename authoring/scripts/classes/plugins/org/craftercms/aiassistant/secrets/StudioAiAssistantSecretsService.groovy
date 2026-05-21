package plugins.org.craftercms.aiassistant.secrets

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

import java.util.Locale

/**
 * Site-scoped secrets registry at {@link StudioAiAssistantSecretsCatalog#SECRETS_JSON_PATH}.
 * <p><strong>Load:</strong> {@link #loadDocument(StudioToolOperations)} uses
 * {@link plugins.org.craftercms.aiassistant.tools.StudioToolOperations#readStudioConfigurationUtf8}
 * (same path as {@code tools.json}). Missing or blank file → empty in-memory document (no synthetic rows).
 * First visit seeds {@link StudioAiAssistantSecretsCatalog#defaultSecretsDocument()} via
 * {@link #ensureDefaultSecretsFileIfMissing}.</p>
 * <p><strong>Resolve:</strong> {@link #resolveSecretKey} reads only values stored in the committed file for that key
 * (no catalog default substitution at runtime). Expands {@code ${env:…}}, {@code ${enc:…}}, and {@code ${secret:…}} via
 * {@link StudioAiSecretMacroResolver}. Admin APIs return expressions and metadata only — never decrypted literals.</p>
 */
final class StudioAiAssistantSecretsService {

  private static final Logger LOG = LoggerFactory.getLogger(StudioAiAssistantSecretsService.class)

  private static final java.util.regex.Pattern SECRET_KEY_PATTERN =
    java.util.regex.Pattern.compile('^[a-z][a-z0-9_]{0,63}$')

  private StudioAiAssistantSecretsService() {}

  private static Map emptyDocument() {
    return [version: 1, secrets: []]
  }

  /**
   * Parsed {@code secrets.json} for the site. Empty document when the file is missing or unreadable
   * (not {@link StudioAiAssistantSecretsCatalog#defaultSecretsDocument()}).
   */
  static Map loadDocument(StudioToolOperations ops) {
    if (ops == null) {
      return emptyDocument()
    }
    String site = ops.resolveEffectiveSiteId('')
    if (!site) {
      return emptyDocument()
    }
    String raw = ops.readStudioConfigurationUtf8(site, StudioAiAssistantSecretsCatalog.SECRETS_JSON_PATH)
    if (raw == null || !raw.toString().trim()) {
      return emptyDocument()
    }
    return parseDocument(raw.toString()) ?: emptyDocument()
  }

  static Map loadDocument(String siteId, Object applicationContext) {
    String site = (siteId ?: '').toString().trim()
    if (!site) {
      return emptyDocument()
    }
    String raw = readConfigurationUtf8(site, applicationContext)
    if (raw == null || !raw.toString().trim()) {
      return emptyDocument()
    }
    return parseDocument(raw.toString()) ?: emptyDocument()
  }

  static Map parseDocument(String raw) {
    if (!raw?.trim()) {
      return null
    }
    try {
      Object parsed = new JsonSlurper().parseText(raw.trim())
      if (!(parsed instanceof Map)) {
        return null
      }
      Map root = (Map) parsed
      List<Map> rows = []
      Object secrets = root.get('secrets')
      if (secrets instanceof List) {
        for (Object o : (List) secrets) {
          if (o instanceof Map) {
            String key = ((Map) o).key?.toString()?.trim()
            if (key && SECRET_KEY_PATTERN.matcher(key).matches()) {
              rows.add([
                key  : key,
                value: ((Map) o).value != null ? ((Map) o).value.toString() : ''
              ])
            }
          }
        }
      }
      return [version: root.version instanceof Number ? ((Number) root.version).intValue() : 1, secrets: rows]
    } catch (Throwable t) {
      LOG.warn('StudioAiAssistantSecretsService: invalid secrets JSON: {}', t.message)
      return null
    }
  }

  /** Stored expression for {@code secretKey} only when that key exists in the loaded document. */
  static String rawStoredValue(StudioToolOperations ops, String secretKey) {
    if (ops == null) {
      return ''
    }
    return rawStoredValueFromDocument(loadDocument(ops), secretKey)
  }

  static String rawStoredValue(String siteId, Object applicationContext, String secretKey) {
    String key = (secretKey ?: '').toString().trim()
    if (!key) {
      return ''
    }
    return rawStoredValueFromDocument(loadDocument(siteId, applicationContext), key)
  }

  private static String rawStoredValueFromDocument(Map doc, String secretKey) {
    String key = (secretKey ?: '').toString().trim()
    if (!key) {
      return ''
    }
    for (Map row : (List<Map>) (doc?.secrets ?: [])) {
      if (key == row.key?.toString()) {
        return row.value != null ? row.value.toString() : ''
      }
    }
    return ''
  }

  /**
   * Resolves a secret to plaintext for server-side use only (tools, LLM, MCP headers).
   * Returns empty when the key is absent from {@code secrets.json} or expansion yields no value.
   */
  static String resolveSecretKey(String siteId, Object applicationContext, String secretKey) {
    String stored = rawStoredValue(siteId, applicationContext, secretKey)
    if (!stored?.trim()) {
      return ''
    }
    return StudioAiSecretMacroResolver.expand(siteId, applicationContext, stored)
  }

  static String resolveSecretKey(StudioToolOperations ops, String secretKey) {
    if (ops == null) {
      return ''
    }
    String siteId = ops.resolveEffectiveSiteId('')
    String stored = rawStoredValue(ops, secretKey)
    if (!stored?.trim()) {
      return ''
    }
    return StudioAiSecretMacroResolver.expand(siteId, ops.applicationContext, stored)
  }

  /** Stored expression from site {@code secrets.json} (not expanded). */
  static String storedSecretExpression(StudioToolOperations ops, String secretKey) {
    if (ops == null) {
      return ''
    }
    return rawStoredValue(ops, secretKey)
  }

  /** Stored kind + resolution outcome for author-facing errors (never includes resolved plaintext). */
  static Map secretResolutionStatus(StudioToolOperations ops, String secretKey) {
    String stored = rawStoredValue(ops, secretKey)
    String kind = StudioAiSecretMacroResolver.classifyStoredValue(stored)
    String resolved = resolveSecretKey(ops, secretKey)
    boolean unresolvedMacro = resolved?.contains('${')
    return [
      configured      : (stored ?: '').trim().length() > 0,
      storedKind      : kind,
      envVar          : StudioAiSecretMacroResolver.envVarFromExpression(stored),
      unresolvedMacro : unresolvedMacro,
      resolvedEmpty   : !(resolved ?: '').trim()
    ]
  }

  /**
   * Writes {@link StudioAiAssistantSecretsCatalog#defaultSecretsDocument()} when the site file is missing or blank.
   * Out-of-the-box LLM and integration keys start as {@code ${env:VAR}}; authors may replace with {@code ${enc:…}} or other expressions.
   * @return {@code true} when a new file was written
   */
  static boolean ensureDefaultSecretsFileIfMissing(StudioToolOperations ops) {
    if (ops == null) {
      return false
    }
    String siteId = ops.resolveEffectiveSiteId('')
    String raw = ops.readStudioConfigurationUtf8(siteId, StudioAiAssistantSecretsCatalog.SECRETS_JSON_PATH)
    if (raw != null && raw.toString().trim()) {
      return false
    }
    Map doc = StudioAiAssistantSecretsCatalog.defaultSecretsDocument()
    String json = JsonOutput.prettyPrint(JsonOutput.toJson(doc))
    ops.writeStudioConfiguration(siteId, StudioAiAssistantSecretsCatalog.SECRETS_JSON_PATH, json.getBytes('UTF-8'))
    ops.publishConfigChangeRefresh(siteId)
    LOG.info('StudioAiAssistantSecretsService: seeded default {} for siteId={}', StudioAiAssistantSecretsCatalog.SECRETS_JSON_PATH, siteId)
    return true
  }

  /**
   * Admin index: known slots + custom entries; never includes decrypted literals.
   */
  static Map adminIndex(StudioToolOperations ops) {
    String siteId = ops.resolveEffectiveSiteId('')
    Map doc = loadDocument(ops)
    Map<String, String> byKey = entriesByKey(doc)

    List<Map> knownOut = []
    for (Map slot : StudioAiAssistantSecretsCatalog.knownSlots()) {
      String key = slot.key?.toString()
      knownOut.add(adminRowForKnownSlot(slot, byKey.get(key)))
    }

    Set<String> knownKeys = new LinkedHashSet<>()
    for (Map slot : StudioAiAssistantSecretsCatalog.knownSlots()) {
      knownKeys.add(slot.key?.toString())
    }

    List<Map> customOut = []
    for (Map row : (List<Map>) (doc.secrets ?: [])) {
      String key = row.key?.toString()
      if (!key || knownKeys.contains(key)) {
        continue
      }
      customOut.add(adminRowForKey(key, key, byKey.get(key), '', null, false, false))
    }

    return [
      ok          : true,
      siteId      : siteId,
      studioPath  : StudioAiAssistantSecretsCatalog.SECRETS_JSON_PATH,
      knownSecrets: knownOut,
      customSecrets: customOut
    ]
  }

  private static Map adminRowForKnownSlot(Map slot, String stored) {
    String key = slot.key?.toString()?.trim()
    return adminRowForKey(
      key,
      slot.label?.toString(),
      stored,
      slot.defaultEnvVar?.toString(),
      slot.provider?.toString(),
      Boolean.TRUE.equals(slot.optional),
      true
    )
  }

  private static Map adminRowForKey(
    String key,
    String label,
    String stored,
    String defaultEnvVar,
    String llmProvider = null,
    boolean optional = false,
    boolean knownSlot = false
  ) {
    String persisted = (stored ?: '').toString().trim()
    String defaultExpr = knownSlot ? StudioAiAssistantSecretsCatalog.defaultValueExpressionForKey(key) : ''
    String kind = persisted ? StudioAiSecretMacroResolver.classifyStoredValue(persisted) : 'empty'
    Map row = [
      key          : key,
      label        : label ?: key,
      configured   : persisted.length() > 0,
      valueKind    : kind,
      defaultEnvVar: defaultEnvVar ?: '',
      llmProvider  : llmProvider ?: '',
      optional     : optional,
      suggestedExpression: defaultExpr ?: ''
    ]
    if ('env' == kind) {
      row.valueExpression = persisted
      row.envVar = StudioAiSecretMacroResolver.envVarFromExpression(persisted) ?: (defaultEnvVar ?: '')
    } else if ('enc' == kind) {
      row.valueExpression = maskEncExpression(persisted)
    } else if ('secret_ref' == kind) {
      row.valueExpression = persisted
    } else if ('literal' == kind) {
      row.hasEncryptedLiteral = true
    }
    return row
  }

  private static String maskEncExpression(String stored) {
    String s = (stored ?: '').toString().trim()
    if (!s.startsWith('${enc:')) {
      return '${enc:…}'
    }
    int len = s.length()
    if (len <= 20) {
      return '${enc:…}'
    }
    return '${enc:' + s.substring(6, Math.min(14, len - 1)) + '…}'
  }

  private static Map<String, String> entriesByKey(Map doc) {
    Map<String, String> out = new LinkedHashMap<>()
    for (Map row : (List<Map>) (doc.secrets ?: [])) {
      String key = row.key?.toString()?.trim()
      if (key) {
        out.put(key, row.value != null ? row.value.toString() : '')
      }
    }
    return out
  }

  /**
   * Persists secrets from admin save. Each item: {@code key}, optional {@code clear}, and one of:
   * {@code valueExpression}, {@code envVar}, {@code plainValue} (encrypted on server), or {@code encCipher} ({@code ${enc:…}}).
   */
  static Map saveAdminEntries(StudioToolOperations ops, List<Map> entries) {
    String siteId = ops.resolveEffectiveSiteId('')
    Map doc = loadDocument(ops)
    Map<String, String> byKey = entriesByKey(doc)

    for (Map item : (entries ?: [])) {
      if (!(item instanceof Map)) {
        continue
      }
      String key = item.key?.toString()?.trim()?.toLowerCase(Locale.ROOT)
      if (!key || !SECRET_KEY_PATTERN.matcher(key).matches()) {
        return [ok: false, message: "Invalid secret key '${item.key}' (use lowercase letters, digits, underscore; start with a letter)."]
      }
      if (Boolean.TRUE.equals(item.clear) || Boolean.TRUE.equals(item.remove)) {
        byKey.remove(key)
        continue
      }

      String next = resolveSaveValue(siteId, ops.applicationContext, item, byKey.get(key))
      if (next != null) {
        byKey.put(key, next)
      }
    }

    List<Map> rows = []
    Set<String> knownKeys = new LinkedHashSet<>()
    for (Map slot : StudioAiAssistantSecretsCatalog.knownSlots()) {
      knownKeys.add(slot.key?.toString())
    }
    for (Map slot : StudioAiAssistantSecretsCatalog.knownSlots()) {
      String k = slot.key?.toString()
      if (byKey.containsKey(k)) {
        rows.add([key: k, value: byKey.get(k)])
        byKey.remove(k)
      } else {
        rows.add([key: k, value: StudioAiAssistantSecretsCatalog.defaultValueExpressionForKey(k)])
      }
    }
    List<String> customKeys = new ArrayList<>(byKey.keySet())
    Collections.sort(customKeys)
    for (String k : customKeys) {
      if (!knownKeys.contains(k)) {
        rows.add([key: k, value: byKey.get(k)])
      }
    }

    Map outDoc = [version: 1, secrets: rows]
    String json = JsonOutput.prettyPrint(JsonOutput.toJson(outDoc))
    ops.writeStudioConfiguration(siteId, StudioAiAssistantSecretsCatalog.SECRETS_JSON_PATH, json.getBytes('UTF-8'))
    ops.publishConfigChangeRefresh(siteId)
    return [ok: true, message: 'Secrets saved']
  }

  /** {@link #maskEncExpression} placeholders must not overwrite stored ciphertext on save. */
  private static boolean isMaskedEncAdminPlaceholder(String expr) {
    String s = (expr ?: '').toString().trim()
    if (!s.startsWith('${enc:')) {
      return false
    }
    return s.contains('\u2026') || s.contains('…') || s.endsWith('…}')
  }

  private static String resolveSaveValue(String siteId, Object applicationContext, Map item, String previous) {
    if (item.containsKey('valueExpression')) {
      String expr = item.valueExpression?.toString()?.trim() ?: ''
      if (!expr) {
        return ''
      }
      if (isMaskedEncAdminPlaceholder(expr)) {
        return null
      }
      String kind = StudioAiSecretMacroResolver.classifyStoredValue(expr)
      if ('literal' == kind) {
        return encryptPlaintext(siteId, applicationContext, expr)
      }
      return expr
    }
    if (item.envVar != null) {
      String env = item.envVar?.toString()?.trim() ?: ''
      if (!env) {
        return ''
      }
      return "\${env:${env}}"
    }
    if (item.encCipher != null) {
      String cipher = item.encCipher?.toString()?.trim() ?: ''
      if (!cipher) {
        return ''
      }
      if (cipher.startsWith('${enc:')) {
        return cipher
      }
      return "\${enc:${cipher}}"
    }
    if (item.plainValue != null) {
      String plain = item.plainValue?.toString() ?: ''
      if (!plain.trim()) {
        return previous ?: ''
      }
      return encryptPlaintext(siteId, applicationContext, plain)
    }
    return null
  }

  private static String encryptPlaintext(String siteId, Object applicationContext, String plaintext) {
    String plain = (plaintext ?: '').toString()
    Object encSvc = resolveEncryptionService(applicationContext)
    if (encSvc == null || !siteId?.trim()) {
      throw new IllegalStateException(
        'Crafter encryptionService is not available. Use ${env:VAR} or paste a ${enc:…} value from Studio Encrypt Marked.'
      )
    }
    try {
      if (!encSvc.metaClass.respondsTo(encSvc, 'encrypt', String, String)) {
        throw new IllegalStateException('encryptionService.encrypt(siteId, text) is not available on this Studio build.')
      }
      String cipher = encSvc.encrypt(siteId.trim(), plain)?.toString()?.trim()
      if (!cipher) {
        throw new IllegalStateException('encryptionService.encrypt returned empty ciphertext.')
      }
      return "\${enc:${cipher}}"
    } catch (IllegalStateException ise) {
      throw ise
    } catch (Throwable t) {
      throw new IllegalStateException("Encrypt failed: ${t.message}", t)
    }
  }

  private static String readConfigurationUtf8(String siteId, Object applicationContext) {
    if (!siteId?.trim() || applicationContext == null) {
      return null
    }
    String path = StudioAiAssistantSecretsCatalog.SECRETS_JSON_PATH
    try {
      Object cfg = applicationContext.get('configurationService')
      if (cfg != null && cfg.metaClass.respondsTo(cfg, 'getConfigurationAsString', String, String, String, String)) {
        return cfg.getConfigurationAsString(siteId.trim(), 'studio', path, '')
      }
    } catch (Throwable t) {
      LOG.debug('StudioAiAssistantSecretsService: configuration read failed siteId={}: {}', siteId, t.message)
    }
    return null
  }

  private static Object resolveEncryptionService(Object applicationContext) {
    if (applicationContext == null) {
      return null
    }
    for (String beanName : ['encryptionService', 'encryptionServiceImpl']) {
      try {
        Object bean = applicationContext.get(beanName)
        if (bean != null) {
          return bean
        }
      } catch (Throwable ignored) {
      }
    }
    return null
  }
}
