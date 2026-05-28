package plugins.org.craftercms.aiassistant.engine.routing.subrouting

import plugins.org.craftercms.aiassistant.studio.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.contrib.tool.site.StudioAiUserSiteTools
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations

import java.util.ArrayList
import java.util.Collections
import java.util.List
import java.util.Map

/**
 * Intent-routing signals for site {@code registry.json} tools — same hint shape as intent recipes
 * ({@code matchHints}, {@code dontMatchHints}, optional {@code priority}). Used alongside
 * {@link AuthoringIntentRecipeCatalog#findDeterministicRecipeMatches} in routing passes; does not invoke tools on the JVM.
 */
final class AuthoringIntentSiteToolCatalog {

  /** Utility class; no instances. */
  private AuthoringIntentSiteToolCatalog() {}

  /**
   * Loads normalized registry rows (including routing hints) for the current site.
   */
  static List<Map> loadSiteTools(StudioToolOperations ops) {
    Map cfg = StudioAiAssistantProjectConfig.load(ops)
    return StudioAiUserSiteTools.loadRegistryEntries(ops, cfg)
  }

  /**
   * All site tools whose {@code matchHints} hit {@code authorVisible} and whose {@code dontMatchHints} do not.
   *
   * @param entries from {@link #loadSiteTools} or {@link StudioAiUserSiteTools#loadRegistryEntries}
   * @param authorVisible current-turn author text (not full wire cand)
   * @return list of maps: {@code toolId}, {@code tool}, {@code routerReason}, {@code priority}
   */
  static List<Map> findDeterministicSiteToolMatches(List<Map> entries, String authorVisible) {
    if (entries == null || entries.isEmpty()) {
      return Collections.emptyList()
    }
    String visible = (authorVisible ?: '').toString().trim()
    if (!visible) {
      return Collections.emptyList()
    }
    List<Map> out = []
    for (Map entry : entries) {
      if (!(entry instanceof Map)) {
        continue
      }
      String toolId = entry.id?.toString()?.trim()
      if (!toolId) {
        continue
      }
      if (!siteToolMatchesHints(entry, visible)) {
        continue
      }
      int priority = 0
      Object pr = entry.get('priority')
      if (pr instanceof Number) {
        priority = ((Number) pr).intValue()
      }
      out.add([
        toolId      : toolId,
        tool        : entry,
        routerReason: 'deterministic_site_tool_hints',
        priority    : priority
      ] as Map)
    }
    out.sort { a, b ->
      int pa = a.priority instanceof Number ? ((Number) a.priority).intValue() : 0
      int pb = b.priority instanceof Number ? ((Number) b.priority).intValue() : 0
      pb <=> pa
    }
    return out
  }

  /** Comma-separated tool ids for SSE / session debug (truncated). */
  static List<String> siteToolMatchIds(List<Map> toolMatches, int maxIds = 24) {
    if (!toolMatches) {
      return []
    }
    List<String> ids = []
    for (Map m : toolMatches) {
      String id = m.toolId?.toString()?.trim()
      if (id) {
        ids.add(id)
      }
    }
    if (ids.size() <= maxIds) {
      return ids
    }
    return ids.subList(0, maxIds)
  }

  /**
   * True when {@code entry.matchHints} hit {@code authorVisible} and {@code dontMatchHints} do not.
   * Tools with no {@code matchHints} never match deterministically.
   */
  static boolean siteToolMatchesHints(Map entry, String authorVisible) {
    if (!(entry instanceof Map)) {
      return false
    }
    if (siteToolExcludedByDontMatchHints(entry, authorVisible)) {
      return false
    }
    List<String> hints = AuthoringIntentRecipeCatalog.hintStringList(entry.get('matchHints'))
    if (hints.isEmpty()) {
      return false
    }
    return AuthoringIntentRecipeCatalog.authorVisibleMatchesKeywordList(authorVisible, hints)
  }

  /** True when any {@code dontMatchHints} substring appears in {@code authorVisible}. */
  static boolean siteToolExcludedByDontMatchHints(Map entry, String authorVisible) {
    List<String> dont = AuthoringIntentRecipeCatalog.hintStringList(entry.get('dontMatchHints'))
    return !dont.isEmpty() &&
      AuthoringIntentRecipeCatalog.authorVisibleMatchesKeywordList(authorVisible, dont)
  }

  /** Markdown table for clarify prompts when site tools compete with recipes. */
  static String formatSiteToolMatchesMarkdown(List<Map> toolMatches) {
    if (toolMatches == null || toolMatches.isEmpty()) {
      return '(none)'
    }
    StringBuilder sb = new StringBuilder()
    sb.append('| toolId | description (short) | routerReason |\n')
    sb.append('|---|---|---|\n')
    for (Map m : toolMatches) {
      Map tool = m.tool instanceof Map ? (Map) m.tool : [:]
      String id = (m.toolId ?: tool.id ?: '').toString().replace('|', '/')
      String desc = (tool.description ?: '').toString().trim().replace('|', '/')
      if (desc.length() > 120) {
        desc = desc.substring(0, 117) + '…'
      }
      String reason = (m.routerReason ?: '').toString().replace('|', '/')
      sb.append('| `').append(id).append('` | ').append(desc).append(' | `').append(reason).append('` |\n')
    }
    return sb.toString()
  }
}
