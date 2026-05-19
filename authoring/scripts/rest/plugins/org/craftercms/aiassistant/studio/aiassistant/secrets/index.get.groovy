import plugins.org.craftercms.aiassistant.secrets.StudioAiAssistantSecretsService
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

/**
 * Admin index for site secrets (metadata and expressions only — no decrypted values).
 * Query: {@code siteId} (required).
 */
String siteId = (params?.siteId ?: request.getParameter('siteId'))?.toString()?.trim()
if (!siteId) {
  response.status = 400
  return [ok: false, message: 'Missing siteId']
}

def ops = new StudioToolOperations(request, applicationContext, params)
boolean seeded = StudioAiAssistantSecretsService.ensureDefaultSecretsFileIfMissing(ops)
Map index = StudioAiAssistantSecretsService.adminIndex(ops)
index.secretsSeeded = seeded
return index
