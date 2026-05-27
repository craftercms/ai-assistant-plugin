package plugins.org.craftercms.aiassistant.contrib.tool.builtin.site

import plugins.org.craftercms.aiassistant.contrib.tool.site.StudioAiUserSiteTools
import plugins.org.craftercms.aiassistant.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolSchemas

/**
 * Dispatches to a site-defined Groovy tool under {@code config/studio/scripts/aiassistant/user-tools/}.
 */
class InvokeSiteUserTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'InvokeSiteUserTool' }

  @Override
  String description() {
    return 'Runs a site-defined Groovy tool from sandbox user-tools registry (see registry.json).'
  }

  @Override
  String description(StudioAiToolContext ctx) {
    return buildWireDescription(ctx)
  }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.INVOKE_SITE_USER_TOOL }

  /** Registered when the site {@code user-tools/registry.json} lists at least one tool. */
  @Override
  boolean enabled(StudioAiToolContext ctx) {
    return !StudioAiUserSiteTools.loadRegistryEntries(ctx.ops, ctx.aiProjectToolCfg).isEmpty()
  }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    Map m = new LinkedHashMap<>((Map) (input ?: [:]))
    String tid = m.toolId?.toString()?.trim()
    Map args = (m.args instanceof Map) ? (Map) m.args : [:]
    return StudioAiUserSiteTools.invokeRegisteredTool(ctx.ops, tid, args) as Map
  }

  /**
   * Builds wire description for tool or orchestration output.
   * @param ctx Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String buildWireDescription(StudioAiToolContext ctx) {
    List<Map> siteUserToolEntries = StudioAiUserSiteTools.loadRegistryEntries(ctx.ops, ctx.aiProjectToolCfg)
    if (siteUserToolEntries.isEmpty()) {
      return descriptionFallback()
    }
    StringBuilder desc = new StringBuilder(512)
    desc.append(
      'Runs a **site-defined** Groovy tool from sandbox `config/studio/scripts/aiassistant/user-tools/` (see `registry.json` in that folder). '
    )
    desc.append(
      'Pass **toolId** exactly as registered. Scripts receive binding variables: **studio** (StudioToolOperations), **args** (map from this call), **toolId**, **siteId**, **log** (SLF4J). Return a Map (e.g. ok, message, data). Registered tools: '
    )
    int i = 0
    for (Map e : siteUserToolEntries) {
      if (i++ > 0) {
        desc.append('; ')
      }
      desc.append(e.id)
      String d = e.description?.toString()?.trim()
      if (d) {
        desc.append(' — ').append(d.length() > 200 ? d.substring(0, 200) + '…' : d)
      }
    }
    if (desc.length() > 8000) {
      desc.setLength(7997)
      desc.append('…')
    }
    return desc.toString()
  }

  /**
   * Description fallback.
   * @return Text result, or empty or null when unavailable.
   */
  private static String descriptionFallback() {
    return 'Runs a site-defined Groovy tool from sandbox user-tools registry (see registry.json).'
  }
}
