package plugins.org.craftercms.aiassistant.secrets

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Expands secret placeholders in configuration strings on the Studio JVM.
 * <ul>
 *   <li>{@code ${env:VAR}} — {@link System#getenv}</li>
 *   <li>{@code ${enc:...}} — Crafter {@code encryptionService.decrypt(siteId, ...)} when available</li>
 *   <li>{@code ${secret:key}} — resolves another entry from site {@code secrets.json} (no cycles)</li>
 * </ul>
 */
final class StudioAiSecretMacroResolver {

  private static final Logger LOG = LoggerFactory.getLogger(StudioAiSecretMacroResolver.class)

  private static final java.util.regex.Pattern ENV_MACRO =
    java.util.regex.Pattern.compile('\\$\\{env:([A-Za-z0-9_.]+)\\}')

  private static final java.util.regex.Pattern ENC_MACRO =
    java.util.regex.Pattern.compile('\\$\\{enc:([^}]+)\\}')

  private static final java.util.regex.Pattern SECRET_MACRO =
    java.util.regex.Pattern.compile('\\$\\{secret:([a-z][a-z0-9_]*)\\}')

  private StudioAiSecretMacroResolver() {}

  static String expand(String siteId, Object applicationContext, String input) {
    return expandWithStack(siteId, applicationContext, input, new LinkedHashSet<String>(), 0)
  }

  private static String expandWithStack(
    String siteId,
    Object applicationContext,
    String input,
    Set<String> secretStack,
    int depth
  ) {
    if (input == null) {
      return ''
    }
    String s = input.toString()
    if (!s.contains('${')) {
      return s
    }
    if (depth > 12) {
      LOG.warn('StudioAiSecretMacroResolver: max expansion depth exceeded siteId={}', siteId)
      return s
    }

    s = expandEnvMacros(s)
    s = expandEncMacros(siteId, applicationContext, s)
    s = expandSecretMacros(siteId, applicationContext, s, secretStack, depth)
    return s
  }

  static String expandEnvMacros(String input) {
    if (input == null) {
      return ''
    }
    String s = input.toString()
    if (!s.contains('${env:')) {
      return s
    }
    java.util.regex.Matcher m = ENV_MACRO.matcher(s)
    StringBuffer sb = new StringBuffer()
    while (m.find()) {
      String name = m.group(1)
      String val = ''
      try {
        String gv = System.getenv(name)
        if (gv != null) {
          val = gv
        }
      } catch (Throwable ignored) {
      }
      m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(val))
    }
    m.appendTail(sb)
    return sb.toString()
  }

  private static String expandEncMacros(String siteId, Object applicationContext, String input) {
    String s = (input ?: '').toString()
    if (!s.contains('${enc:')) {
      return s
    }
    Object encSvc = resolveEncryptionService(applicationContext)
    if (encSvc == null) {
      LOG.debug('StudioAiSecretMacroResolver: encryptionService not available; ${enc:…} left unchanged')
      return s
    }
    java.util.regex.Matcher m = ENC_MACRO.matcher(s)
    StringBuffer sb = new StringBuffer()
    while (m.find()) {
      String cipher = m.group(1)?.toString()?.trim() ?: ''
      String plain = ''
      if (cipher && siteId?.trim()) {
        try {
          if (encSvc.metaClass.respondsTo(encSvc, 'decrypt', String, String)) {
            plain = encSvc.decrypt(siteId.trim(), cipher)?.toString() ?: ''
          }
        } catch (Throwable t) {
          LOG.warn('StudioAiSecretMacroResolver: decrypt failed siteId={}: {}', siteId, t.message)
        }
      }
      m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(plain))
    }
    m.appendTail(sb)
    return sb.toString()
  }

  private static String expandSecretMacros(
    String siteId,
    Object applicationContext,
    String input,
    Set<String> secretStack,
    int depth
  ) {
    String s = (input ?: '').toString()
    if (!s.contains('${secret:')) {
      return s
    }
    java.util.regex.Matcher m = SECRET_MACRO.matcher(s)
    StringBuffer sb = new StringBuffer()
    while (m.find()) {
      String secretKey = m.group(1)?.toString()?.trim() ?: ''
      String val = ''
      if (secretKey && !secretStack.contains(secretKey)) {
        secretStack.add(secretKey)
        try {
          String stored = StudioAiAssistantSecretsService.rawStoredValue(siteId, applicationContext, secretKey)
          val = expandWithStack(siteId, applicationContext, stored, secretStack, depth + 1)
        } finally {
          secretStack.remove(secretKey)
        }
      }
      m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(val))
    }
    m.appendTail(sb)
    return sb.toString()
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

  /** Classifies a stored value for admin UI (never returns decrypted plaintext for literals). */
  static String classifyStoredValue(String stored) {
    String s = (stored ?: '').toString().trim()
    if (!s) {
      return 'empty'
    }
    if (ENV_MACRO.matcher(s).matches()) {
      return 'env'
    }
    if (s.startsWith('${enc:')) {
      return 'enc'
    }
    if (SECRET_MACRO.matcher(s).matches()) {
      return 'secret_ref'
    }
    return 'literal'
  }

  static String envVarFromExpression(String stored) {
    String s = (stored ?: '').toString().trim()
    java.util.regex.Matcher m = ENV_MACRO.matcher(s)
    if (m.matches()) {
      return m.group(1) ?: ''
    }
    return ''
  }
}
