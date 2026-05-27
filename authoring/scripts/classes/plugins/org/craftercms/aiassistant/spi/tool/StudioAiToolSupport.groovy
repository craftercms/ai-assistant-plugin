package plugins.org.craftercms.aiassistant.spi.tool

import plugins.org.craftercms.aiassistant.engine.catalog.AiOrchestrationTools

/**
 * Shared helpers for tool implementations (delegates to {@link AiOrchestrationTools}).
 */
final class StudioAiToolSupport {

  /**
   * Private constructor; not for direct use.
   */
private StudioAiToolSupport() {}

  /**
   * Repo path from tool input.
   * @param input Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String repoPathFromToolInput(Map input) {
    return AiOrchestrationTools.repoPathFromToolInput(input)
  }

  /**
   * Extracts content type id from item xml from repository XML or related text.
   * @param xml Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String extractContentTypeIdFromItemXml(String xml) {
    return AiOrchestrationTools.extractContentTypeIdFromItemXml(xml)
  }

  /**
   * Extracts form field ids from form definition xml from repository XML or related text.
   * @param formXml Caller-supplied input.
   * @return List<String> result.
   */
  static List<String> extractFormFieldIdsFromFormDefinitionXml(String formXml) {
    return AiOrchestrationTools.extractFormFieldIdsFromFormDefinitionXml(formXml)
  }
}
