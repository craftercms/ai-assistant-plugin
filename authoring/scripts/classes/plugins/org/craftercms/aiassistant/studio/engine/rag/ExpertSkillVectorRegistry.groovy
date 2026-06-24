package plugins.org.craftercms.aiassistant.studio.engine.rag

import plugins.org.craftercms.aiassistant.studio.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations

@Grab(group='org.springframework.ai', module='spring-ai-commons', version='1.1.7', initClass=false)
@Grab(group='org.springframework.ai', module='spring-ai-model', version='1.1.7', initClass=false)
@Grab(group='org.springframework.ai', module='spring-ai-vector-store', version='1.1.7', initClass=false)
@Grab(group='org.springframework.ai', module='spring-ai-openai', version='1.1.7', initClass=false)

import java.security.MessageDigest
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.List
import java.util.Locale
import java.util.Map
import java.util.Set
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.document.MetadataMode
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.openai.OpenAiEmbeddingModel
import org.springframework.ai.openai.OpenAiEmbeddingOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.SimpleVectorStore

/**
 * Per-URL expert guidance: fetch markdown once, chunk, embed with Spring AI {@link OpenAiEmbeddingModel},
 * store in an in-memory {@link SimpleVectorStore}, and run similarity search for {@code QueryExpertGuidance}.
 */
class ExpertSkillVectorRegistry {

  private static final Logger log = LoggerFactory.getLogger(ExpertSkillVectorRegistry.class)

  private static final ConcurrentHashMap<String, SimpleVectorStore> STORES = new ConcurrentHashMap<>()
  private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>()

  /** Stable id for RAG corpus keyed by URL (first 16 hex chars of SHA-256 of trimmed URL UTF-8). */
  static String skillIdForUrl(String url) {
    def u = (url ?: '').toString().trim()
    def md = MessageDigest.getInstance('SHA-256')
    byte[] digest = md.digest(u.getBytes('UTF-8'))
    StringBuilder hex = new StringBuilder('es_')
    for (int i = 0; i < 8 && i < digest.length; i++) {
      hex.append(String.format('%02x', digest[i] & 0xff))
    }
    return hex.toString()
  }

  /**
   * Normalize client {@code skills} JSON: https? URLs, dedupe by {@link #skillIdForUrl}, cap list size.
   * @return list of maps: {@code skillId}, {@code name}, {@code url}, {@code description}
   */
  static List<Map> normalizeRequestExpertSkills(Object raw, Map projectCfg = null) {
    List<Map> out = new ArrayList<>()
    if (!(raw instanceof List)) {
      return out
    }
    Set<String> seen = new HashSet<>()
    int cap = StudioAiAssistantProjectConfig.agentSkillsRagMaxSkills(projectCfg ?: [:])
    for (Object row : (List) raw) {
      if (out.size() >= cap) {
        break
      }
      if (!(row instanceof Map)) {
        continue
      }
      Map m = (Map) row
      String url = m.url?.toString()?.trim()
      if (!url) {
        continue
      }
      String low = url.toLowerCase(Locale.US)
      if (!low.startsWith('https://') && !low.startsWith('http://')) {
        continue
      }
      boolean enabled = false
      def enRaw = m.enabled
      if (enRaw == Boolean.TRUE) {
        enabled = true
      } else if (enRaw != null) {
        String enStr = enRaw.toString().trim().toLowerCase(Locale.US)
        enabled = enStr == 'true' || enStr == '1' || enStr == 'yes'
      }
      if (!enabled) {
        continue
      }
      String name = m.name?.toString()?.trim() ?: 'Skill'
      String desc = m.description?.toString()?.trim() ?: ''
      String sid = skillIdForUrl(url)
      if (!seen.add(sid)) {
        continue
      }
      out << [skillId: sid, name: name, url: url, description: desc]
    }
    return out
  }

  /**
   * Resolves embedding model name from request and plugin context.
   * @param projectCfg Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String resolveEmbeddingModelName(Map projectCfg = null) {
    StudioAiAssistantProjectConfig.agentSkillsRagEmbeddingModel(projectCfg ?: [:])
  }

  /**
   * Builds embedding model for tool or orchestration output.
   * @param llmApiKey Caller-supplied input.
   * @param projectCfg Caller-supplied input.
   * @return EmbeddingModel result.
   */
  static EmbeddingModel buildEmbeddingModel(String llmApiKey, Map projectCfg = null) {
    OpenAiApi api = OpenAiApi.builder().apiKey(llmApiKey).build()
    OpenAiEmbeddingOptions opts = OpenAiEmbeddingOptions.builder().model(resolveEmbeddingModelName(projectCfg)).build()
    return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, opts)
  }

  /**
   * Chunk markdown.
   * @param markdown Caller-supplied input.
   * @param maxChunkChars Caller-supplied input.
   * @return List<String> result.
   */
  static List<String> chunkMarkdown(String markdown, int maxChunkChars) {
    String md = (markdown ?: '').toString()
    if (!md.trim()) {
      return []
    }
    int maxC = maxChunkChars > 256 ? maxChunkChars : 1800
    if (md.length() <= maxC) {
      return [md]
    }
    String[] lines = md.split(/\r?\n/, -1)
    List<String> sections = new ArrayList<>()
    StringBuilder cur = new StringBuilder()
    for (String line : lines) {
      boolean heading = line.trim().startsWith('#')
      if (heading && cur.length() > 0 && cur.length() + line.length() + 1 > maxC) {
        sections << cur.toString()
        cur.setLength(0)
      }
      if (cur.length() + line.length() + 1 > maxC && cur.length() > 0) {
        sections << cur.toString()
        cur.setLength(0)
      }
      if (cur.length() > 0) {
        cur.append('\n')
      }
      cur.append(line)
    }
    if (cur.length() > 0) {
      sections << cur.toString()
    }
    return sections
  }

  /**
   * Lock for.
   * @param skillId Identifier for the target resource.
   * @return Object result.
   */
  private static Object lockFor(String skillId) {
    LOCKS.computeIfAbsent(skillId, { k -> new Object() })
  }

  /**
   * Ensure corpus loaded.
   */
  static void ensureCorpusLoaded(
    String skillId,
    String sourceUrl,
    EmbeddingModel embeddingModel,
    StudioToolOperations ops,
    Map projectCfg = null
  ) {
    if (STORES.containsKey(skillId)) {
      return
    }
    Object lk = lockFor(skillId)
    synchronized (lk) {
      if (STORES.containsKey(skillId)) {
        return
      }
      def fetch = plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.http.HttpUrlFetch.fetch(sourceUrl, null)
      if (!Boolean.TRUE.equals(fetch?.ok)) {
        String msg = fetch?.message?.toString() ?: 'fetch failed'
        throw new IllegalStateException("Expert skill URL could not be loaded (${sourceUrl}): ${msg}")
      }
      String body = fetch?.body?.toString() ?: ''
      if (!body.trim()) {
        throw new IllegalStateException("Expert skill URL returned empty body: ${sourceUrl}")
      }
      int maxChunks = StudioAiAssistantProjectConfig.agentSkillsRagMaxChunks(projectCfg ?: [:])
      int maxChunkChars = StudioAiAssistantProjectConfig.agentSkillsRagMaxChunkChars(projectCfg ?: [:])
      List<String> chunks = chunkMarkdown(body, maxChunkChars)
      if (chunks.size() > maxChunks) {
        log.warn('Expert skill {}: truncating chunks {} -> {}', skillId, chunks.size(), maxChunks)
        chunks = chunks.subList(0, maxChunks)
      }
      SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build()
      List<Document> docs = new ArrayList<>()
      for (int i = 0; i < chunks.size(); i++) {
        String text = chunks.get(i)
        Map<String, Object> meta = new HashMap<>()
        meta.put('skillId', skillId)
        meta.put('sourceUrl', sourceUrl)
        meta.put('chunkIndex', i)
        docs.add(new Document(text, meta))
      }
      store.add(docs)
      STORES.put(skillId, store)
      log.info('Expert skill RAG loaded skillId={} url={} chunks={}', skillId, sourceUrl, docs.size())
    }
  }

  /**
   * Query expert skill.
   * @return Map payload for tools or orchestration.
   */
  static Map queryExpertSkill(
    String skillId,
    String query,
    int topK,
    Map<String, String> urlBySkillId,
    EmbeddingModel embeddingModel,
    StudioToolOperations ops,
    Map projectCfg = null
  ) {
    String sid = (skillId ?: '').toString().trim()
    if (!sid) {
      throw new IllegalArgumentException('Missing required field: skillId')
    }
    String q = (query ?: '').toString().trim()
    if (!q) {
      throw new IllegalArgumentException('Missing required field: query')
    }
    String url = urlBySkillId?.get(sid)
    if (!url?.trim()) {
      return [
        ok     : false,
        action : 'query_expert_guidance',
        skillId: sid,
        message: "Unknown skillId '${sid}'. Use a skillId from the expert skills table in the system message."
      ]
    }
    ensureCorpusLoaded(sid, url.trim(), embeddingModel, ops, projectCfg)
    SimpleVectorStore store = STORES.get(sid)
    if (store == null) {
      throw new IllegalStateException("Corpus missing after load for skillId=${sid}")
    }
    int k = topK > 0 && topK <= 20 ? topK : 8
    SearchRequest req = SearchRequest.builder()
      .query(q)
      .topK(k)
      .similarityThresholdAll()
      .build()
    List<Document> hits = store.doSimilaritySearch(req)
    List<Map> chunks = new ArrayList<>()
    hits.each { Document d ->
      chunks << [
        text : d.text,
        score: d.score,
        meta : d.metadata ?: [:]
      ]
    }
    return [
      ok        : true,
      action    : 'query_expert_guidance',
      skillId   : sid,
      query     : q,
      topK      : k,
      matchCount: chunks.size(),
      chunks    : chunks
    ]
  }
}
