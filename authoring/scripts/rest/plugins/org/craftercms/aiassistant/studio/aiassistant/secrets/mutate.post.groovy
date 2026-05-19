import jakarta.servlet.http.HttpServletResponse
import plugins.org.craftercms.aiassistant.http.AiHttpProxy
import plugins.org.craftercms.aiassistant.secrets.StudioAiAssistantSecretsService
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

/**
 * Persist site secrets from Project Tools → Secrets tab.
 * Query: {@code siteId}. Body JSON:
 * <pre>
 * { "entries": [ { "key": "openai_api_key", "envVar": "OPENAI_API_KEY" }, … ] }
 * { "entries": [ { "key": "my_hook", "plainValue": "…" } ] }
 * { "entries": [ { "key": "old", "remove": true } ] }
 * </pre>
 * Resolved secret values are never returned in the response.
 */
def body = AiHttpProxy.parseJsonBody(request)
if (Boolean.TRUE.equals(body?.get('__aiassistantInvalidJson'))) {
  response.status = HttpServletResponse.SC_BAD_REQUEST
  return [ok: false, message: 'Invalid JSON request body', detail: body?.get('__aiassistantInvalidJsonDetail')?.toString() ?: '']
}

Map reqBody = (body instanceof Map) ? (Map) body : [:]
String siteId = (params?.siteId ?: reqBody.get('siteId') ?: request.getParameter('siteId'))?.toString()?.trim()
if (!siteId) {
  response.status = HttpServletResponse.SC_BAD_REQUEST
  return [ok: false, message: 'Missing siteId']
}

Object entriesRaw = reqBody.entries
if (!(entriesRaw instanceof List)) {
  response.status = HttpServletResponse.SC_BAD_REQUEST
  return [ok: false, message: 'Missing entries array']
}

List<Map> entries = []
for (Object o : (List) entriesRaw) {
  if (o instanceof Map) {
    entries.add((Map) o)
  }
}

def ops = new StudioToolOperations(request, applicationContext, params)
try {
  return StudioAiAssistantSecretsService.saveAdminEntries(ops, entries)
} catch (IllegalStateException ise) {
  response.status = HttpServletResponse.SC_BAD_REQUEST
  return [ok: false, message: ise.message ?: 'Secrets save failed']
}
