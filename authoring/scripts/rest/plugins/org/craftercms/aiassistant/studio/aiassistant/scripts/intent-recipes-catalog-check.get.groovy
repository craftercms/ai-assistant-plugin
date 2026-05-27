import plugins.org.craftercms.aiassistant.studio.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.engine.routing.AuthoringIntentRecipeCatalog
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations

/**
 * GET diagnostic for intent recipe catalog deployment (bundled JSON, merged count, prefetch allowlist).
 * Query: {@code siteId} (optional if present on request).
 */
String siteId = (params?.siteId ?: request?.getParameter('siteId'))?.toString()?.trim()
def ops = new StudioToolOperations(request, applicationContext, params)
if (siteId) {
  try {
    request?.setAttribute('aiassistant.siteId', siteId)
  } catch (Throwable ignored) {
  }
}
Map projectCfg = StudioAiAssistantProjectConfig.load(ops)
Map health = AuthoringIntentRecipeCatalog.catalogHealthCheck(ops, projectCfg)
return [ok: Boolean.TRUE.equals(health.ok), health: health]
