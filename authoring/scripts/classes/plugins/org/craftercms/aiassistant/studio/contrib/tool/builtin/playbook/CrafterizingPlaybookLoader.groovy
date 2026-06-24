package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.playbook

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.config.StudioAiPlatformSettings
import plugins.org.craftercms.aiassistant.studio.sandbox.StudioAiSandboxClasspath

/**
 * Loads {@link #PLAYBOOK_FILE_NAME} from the plugin package (classpath or same directory as compiled classes).
 * The markdown file lives beside this class under {@code authoring/scripts/classes/plugins/org/craftercms/aiassistant/}
 * and is copied with the plugin so operators can edit it without recompiling Groovy.
 */
class CrafterizingPlaybookLoader {

  private static final Logger log = LoggerFactory.getLogger(CrafterizingPlaybookLoader.class)

  static final String PLAYBOOK_FILE_NAME = 'CrafterizingPlaybook.md'

  /** Optional env/system property: absolute path to override playbook file (hotfix without redeploying classes dir). */
  static final String SYSPROP_PATH = 'aiassistant.crafterizingPlaybook.path'

  private static final String PACKAGE_RESOURCE_PREFIX = 'plugins/org/craftercms/aiassistant/playbook/'

  /**
   * @return UTF-8 markdown text, or {@code null} if not found
   */
  static String loadMarkdown() {
    String pkgPath = "${PACKAGE_RESOURCE_PREFIX}${PLAYBOOK_FILE_NAME}"
    String text = StudioAiSandboxClasspath.readUtf8FromClassLoader(pkgPath)
    if (text?.trim()) {
      log.debug('Crafterizing playbook loaded from classpath: {} chars', text.length())
      return text
    }
    log.warn('Crafterizing playbook file {} not found on classpath; using embedded fallback.', PLAYBOOK_FILE_NAME)
    return null
  }

  /** Short inline fallback if the markdown file is missing at runtime. */
  static String embeddedFallbackMarkdown() {
    return '''# Crafterizing playbook (fallback)

The editable file `CrafterizingPlaybook.md` was not found next to the AI Assistant plugin classes.

- Ensure `authoring/scripts/classes/plugins/org/craftercms/aiassistant/playbook/CrafterizingPlaybook.md` is deployed (e.g. copied to `config/studio/scripts/classes/plugins/org/craftercms/aiassistant/playbook/`).
- Or set JVM system property `aiassistant.crafterizingPlaybook.path` to an absolute path of a markdown file.

See plugin docs for full crafterization phases: content types under `/config/studio/content-types/`, pages under `/site/website/`, components under `/site/components/`, templates under `/templates/web/`, populate `sections_o`, use CDATA for `*_html`, and use studio tools (GetContent, WriteContent, GetContentTypeFormDefinition) for edits.
'''
  }

  /**
   * Loads markdown or fallback from configuration or input.
   * @return Text result, or empty or null when unavailable.
   */
  static String loadMarkdownOrFallback() {
    def s = loadMarkdown()
    return (s != null && s.trim()) ? s : embeddedFallbackMarkdown()
  }
}
