package plugins.org.craftercms.aiassistant.studio.engine.rag

import plugins.org.craftercms.aiassistant.studio.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations

@Grab(group='org.springframework.ai', module='spring-ai-model', version='1.1.7', initClass=false)
@Grab(group='org.springframework.ai', module='spring-ai-openai', version='1.1.7', initClass=false)

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.HashSet
import java.util.Locale
import java.util.Set
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel

/**
 * Lazy-loaded RAG index over bundled AI Assistant instruction corpus: load persisted embeddings from the site's Studio
 * configuration repo via {@link StudioToolOperations}, or rebuild (embed + write) when missing/stale.
 * <p>
 * Site {@code tools.json} {@code pluginRag.mode}: {@code off} (default), {@code supplement} (full instructions + retrieved appendix),
 * {@code replace} (compact kernel + retrieved appendix). Opt-in only.</p>
 */
class PluginRagVectorRegistry {

  private static final Logger log = LoggerFactory.getLogger(PluginRagVectorRegistry.class)

  /** Same module/path convention as browser {@code get_configuration} with module {@code studio}. */
  private static final String STUDIO_CONFIG_REL_PATH = '/plugins/org/craftercms/aiassistant/aiassistant-plugin-rag-index.json'

  private static final int FORMAT_VERSION = 1

  private static final ConcurrentHashMap<String, Object> SITE_LOCKS = new ConcurrentHashMap<>()
  /** siteId|corpusSha|pluginBuild|embeddingModel -> compiled index */
  private static final ConcurrentHashMap<String, List<RagChunk>> SITE_INDEX = new ConcurrentHashMap<>()

  /** Cache key so corpus, plugin build, and embedding model changes invalidate the in-memory index. */
  private static String indexCacheKey(String siteId, String corpusSha, String pluginBuild, String embeddingModelName) {
    return "${siteId}|${corpusSha}|${pluginBuild}|${embeddingModelName}"
  }

  static final class RagChunk {
    final String text
    final float[] embedding

    RagChunk(String text, float[] embedding) {
      this.text = text
      this.embedding = embedding
    }
  }

  /**
   * Plugin rag mode.
   * @param projectCfg Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String pluginRagMode(Map projectCfg) {
    StudioAiAssistantProjectConfig.pluginRagMode(projectCfg ?: [:])
  }

  /**
   * Plugin rag mode active.
   * @param projectCfg Caller-supplied input.
   * @return True when the check succeeds.
   */
  static boolean pluginRagModeActive(Map projectCfg) {
    StudioAiAssistantProjectConfig.pluginRagModeActive(projectCfg ?: [:])
  }

  /** Compact kernel for {@code replace} mode — leading slice of full authoring instructions (classpath overrides apply). */
  static String ragKernelFromAuthoringInstructions(Map projectCfg) {
    String full = ToolPrompts.getLlm_AUTHORING_INSTRUCTIONS()
    int max = resolveKernelMaxChars(projectCfg)
    if (full.length() <= max) {
      return full
    }
    return full.substring(0, max) + '\n\n[Kernel ends — follow every retrieved chunk in "## Retrieved AI Assistant plugin reference" as part of system policy.]'
  }

  /**
   * Resolves kernel max chars from request and plugin context.
   * @param projectCfg Caller-supplied input.
   * @return int result.
   */
  private static int resolveKernelMaxChars(Map projectCfg) {
    StudioAiAssistantProjectConfig.pluginRagKernelMaxChars(projectCfg ?: [:])
  }

  /**
   * When native tools are on, optionally shrink or augment {@code authoringCore} (full {@link ToolPrompts#getLlm_AUTHORING_INSTRUCTIONS()}).
   */
  static String adjustAuthoringCore(
    String authoringCore,
    String siteId,
    String userText,
    StudioToolOperations ops,
    String llmApiKey,
    boolean toolSchemasOnApi,
    Map projectCfg = null
  ) {
    if (!toolSchemasOnApi) {
      return authoringCore
    }
    Map cfg = projectCfg ?: [:]
    def mode = pluginRagMode(cfg)
    if (mode == 'off') {
      return authoringCore
    }
    def site = (siteId ?: '').toString().trim()
    def key = (llmApiKey ?: '').toString().trim()
    if (!site || !ops || !key) {
      return authoringCore
    }
    try {
      def appendix = buildRetrievalAppendix(site, userText, ops, key, cfg)
      if (mode == 'supplement') {
        return appendix ? (authoringCore + '\n\n' + appendix) : authoringCore
      }
      if (mode == 'replace') {
        if (!appendix?.trim()) {
          log.warn('Plugin RAG replace mode: no retrieval appendix; falling back to full authoring instructions')
          return authoringCore
        }
        return ragKernelFromAuthoringInstructions(cfg) + '\n\n' + appendix
      }
    } catch (Throwable t) {
      log.warn('Plugin RAG adjustAuthoringCore failed (using full core): {}', t.message)
    }
    return authoringCore
  }

  /**
   * Builds retrieval appendix for tool or orchestration output.
   * @return Text result, or empty or null when unavailable.
   */
  private static String buildRetrievalAppendix(
    String siteId,
    String userText,
    StudioToolOperations ops,
    String apiKey,
    Map projectCfg
  ) {
    List<RagChunk> idx = getOrBuildIndex(siteId, ops, apiKey, projectCfg)
    if (idx == null || idx.isEmpty()) {
      return ''
    }
    EmbeddingModel embeddingModel = ExpertSkillVectorRegistry.buildEmbeddingModel(apiKey, projectCfg)
    List<String> queries = retrievalQueries(userText)
    List<float[]> queryVecs = []
    for (String q : queries) {
      try {
        queryVecs.add(embeddingModel.embed(q))
      } catch (Throwable t) {
        log.debug('Plugin RAG query embed skip: {}', t.message)
      }
    }
    if (queryVecs.isEmpty()) {
      return ''
    }
    int topK = resolveTopK(projectCfg)
    int maxChars = resolveMaxAppendChars(projectCfg)
    List<ScoredChunk> scored = []
    for (RagChunk ch : idx) {
      float best = -1f
      for (float[] qv : queryVecs) {
        float s = cosineSimilarity(ch.embedding, qv)
        if (s > best) {
          best = s
        }
      }
      scored.add(new ScoredChunk(ch.text, best))
    }
    scored.sort { a, b -> Float.compare(b.score, a.score) }
    StringBuilder sb = new StringBuilder()
    sb.append('## Retrieved AI Assistant plugin reference (similarity-ranked; apply together with kernel/full policy)\n')
    int used = 0
    int n = 0
    Set<String> seen = new HashSet<>()
    for (ScoredChunk sc : scored) {
      if (n >= topK) {
        break
      }
      String t = sc.text?.toString() ?: ''
      if (!t.trim()) {
        continue
      }
      String fp = fingerprint(t)
      if (!seen.add(fp)) {
        continue
      }
      String block = "\n### [${n + 1}] (score=${String.format(Locale.US, '%.3f', sc.score)})\n${t}\n"
      if (used + block.length() > maxChars) {
        break
      }
      sb.append(block)
      used += block.length()
      n++
    }
    return sb.toString().trim()
  }

  private static final class ScoredChunk {
    final String text
    final float score

    ScoredChunk(String text, float score) {
      this.text = text
      this.score = score
    }
  }

  /**
   * Fingerprint.
   * @param t Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String fingerprint(String t) {
    try {
      MessageDigest md = MessageDigest.getInstance('SHA-256')
      byte[] d = md.digest(t.getBytes(StandardCharsets.UTF_8))
      StringBuilder h = new StringBuilder()
      for (int i = 0; i < 8; i++) {
        h.append(String.format('%02x', d[i] & 0xff))
      }
      return h.toString()
    } catch (Throwable e) {
      return Integer.toString(t.hashCode())
    }
  }

  /**
   * Retrieval queries.
   * @param userText Caller-supplied input.
   * @return List<String> result.
   */
  private static List<String> retrievalQueries(String userText) {
    String u = (userText ?: '').toString().trim()
    List<String> q = new ArrayList<>()
    if (u) {
      q.add(u.length() > 2000 ? u.substring(0, 2000) : u)
    }
    q.add('WriteContent GetContent XML page component content type form definition CDATA')
    q.add('ListContentDependencyScope TranslateContentBatch TranslateContentItem translate localize full page pathChunks sections_o referenced components')
    q.add('FreeMarker template update_template content vs code analyze_template hardcoded')
    q.add('revert_change publish_content GetPreviewHtml ListContentDependencyScope')
    q.add('GetCrafterizingPlaybook crafterize expert skill QueryExpertGuidance')
    return q
  }

  /**
   * Resolves top k from request and plugin context.
   * @param projectCfg Caller-supplied input.
   * @return int result.
   */
  private static int resolveTopK(Map projectCfg) {
    StudioAiAssistantProjectConfig.pluginRagTopK(projectCfg ?: [:])
  }

  /**
   * Resolves max append chars from request and plugin context.
   * @param projectCfg Caller-supplied input.
   * @return int result.
   */
  private static int resolveMaxAppendChars(Map projectCfg) {
    StudioAiAssistantProjectConfig.pluginRagMaxAppendChars(projectCfg ?: [:])
  }

  /**
   * Cosine similarity.
   * @param a Caller-supplied input.
   * @param b Caller-supplied input.
   * @return float result.
   */
  private static float cosineSimilarity(float[] a, float[] b) {
    if (a == null || b == null || a.length != b.length || a.length == 0) {
      return -1f
    }
    double dot = 0
    double na = 0
    double nb = 0
    for (int i = 0; i < a.length; i++) {
      float x = a[i]
      float y = b[i]
      dot += x * y
      na += x * x
      nb += y * y
    }
    double denom = Math.sqrt(na) * Math.sqrt(nb)
    return denom > 1e-12 ? (float) (dot / denom) : 0f
  }

  /**
   * Returns or build index.
   * @param siteId Studio or repository context for this call.
   * @param ops Caller-supplied input.
   * @param apiKey Caller-supplied input.
   * @param projectCfg Caller-supplied input.
   * @return List<RagChunk> result.
   */
  static List<RagChunk> getOrBuildIndex(String siteId, StudioToolOperations ops, String apiKey, Map projectCfg = null) {
    def site = (siteId ?: '').toString().trim()
    if (!site || !ops || !apiKey?.trim()) {
      return []
    }
    String corpus = buildCorpusText()
    String corpusSha = sha256Utf8(corpus)
    String pluginBuild = resolvePluginBuildId()
    String embeddingModelName = ExpertSkillVectorRegistry.resolveEmbeddingModelName(projectCfg)
    String cacheKey = indexCacheKey(site, corpusSha, pluginBuild, embeddingModelName)
    List<RagChunk> cached = SITE_INDEX.get(cacheKey)
    if (cached != null) {
      return cached
    }
    Object lk = SITE_LOCKS.computeIfAbsent(site, { k -> new Object() })
    synchronized (lk) {
      cached = SITE_INDEX.get(cacheKey)
      if (cached != null) {
        return cached
      }
      Map persisted = readPersistedWrapper(ops, site)
      List<RagChunk> loaded = tryLoadFromPersisted(persisted, corpusSha, pluginBuild, embeddingModelName)
      if (loaded != null && !loaded.isEmpty()) {
        SITE_INDEX.put(cacheKey, loaded)
        log.info('Plugin RAG index loaded from repo siteId={} chunks={}', site, loaded.size())
        return loaded
      }
      log.info('Plugin RAG index rebuild start siteId={} corpusSha={} model={}', site, corpusSha, embeddingModelName)
      List<RagChunk> built = buildIndexFromCorpus(corpus, apiKey, projectCfg)
      if (built.isEmpty()) {
        SITE_INDEX.put(cacheKey, built)
        return built
      }
      try {
        byte[] jsonBytes = serializeWrapper(corpusSha, pluginBuild, embeddingModelName, built).getBytes(StandardCharsets.UTF_8)
        ops.writeStudioConfiguration(site, STUDIO_CONFIG_REL_PATH, jsonBytes)
        log.info('Plugin RAG index persisted siteId={} bytes={} chunks={}', site, jsonBytes.length, built.size())
      } catch (Throwable t) {
        log.warn('Plugin RAG persist failed (in-memory only): {}', t.message)
      }
      SITE_INDEX.put(cacheKey, built)
      return built
    }
  }

  /**
   * Loads persisted wrapper from configuration or input.
   * @param ops Caller-supplied input.
   * @param siteId Studio or repository context for this call.
   * @return Map payload for tools or orchestration.
   */
  private static Map readPersistedWrapper(StudioToolOperations ops, String siteId) {
    try {
      String raw = ops.readStudioConfigurationUtf8(siteId, STUDIO_CONFIG_REL_PATH)
      if (!raw?.trim()) {
        return null
      }
      def slurper = new JsonSlurper()
      Object parsed = slurper.parseText(raw)
      return parsed instanceof Map ? (Map) parsed : null
    } catch (Throwable t) {
      log.debug('Plugin RAG read persisted failed: {}', t.message)
      return null
    }
  }

  /**
   * Try load from persisted.
   * @param wrapper Caller-supplied input.
   * @param corpusSha Caller-supplied input.
   * @param pluginBuild Caller-supplied input.
   * @param embeddingModelName Caller-supplied input.
   * @return List<RagChunk> result.
   */
  private static List<RagChunk> tryLoadFromPersisted(Map wrapper, String corpusSha, String pluginBuild, String embeddingModelName) {
    if (wrapper == null) {
      return null
    }
    try {
      int fv = intVal(wrapper.get('formatVersion'), -1)
      if (fv != FORMAT_VERSION) {
        return null
      }
      if (!corpusSha.equals(wrapper.get('corpusSha256')?.toString())) {
        return null
      }
      if (!pluginBuild.equals(wrapper.get('pluginBuildId')?.toString())) {
        return null
      }
      if (!embeddingModelName.equals(wrapper.get('embeddingModel')?.toString())) {
        return null
      }
      String b64 = wrapper.get('payloadGzipBase64')?.toString()
      if (!b64?.trim()) {
        return null
      }
      byte[] gz = Base64.getDecoder().decode(b64.trim())
      ByteArrayOutputStream bout = new ByteArrayOutputStream(Math.max(gz.length * 2, 8192))
      new GZIPInputStream(new ByteArrayInputStream(gz)).withStream { InputStream gzin ->
        byte[] buf = new byte[8192]
        int n
        while ((n = gzin.read(buf)) != -1) {
          bout.write(buf, 0, n)
        }
      }
      def inner = new JsonSlurper().parseText(new String(bout.toByteArray(), StandardCharsets.UTF_8))
      if (!(inner instanceof Map)) {
        return null
      }
      Object chunksObj = ((Map) inner).get('chunks')
      if (!(chunksObj instanceof List)) {
        return null
      }
      List<RagChunk> out = new ArrayList<>()
      for (Object row : (List) chunksObj) {
        if (!(row instanceof Map)) {
          continue
        }
        Map m = (Map) row
        String text = m.get('text')?.toString()
        Object emb = m.get('embedding')
        float[] vec = listToFloatArray(emb)
        if (text?.trim() && vec != null && vec.length > 0) {
          out.add(new RagChunk(text, vec))
        }
      }
      return out.isEmpty() ? null : out
    } catch (Throwable t) {
      log.debug('Plugin RAG deserialize failed: {}', t.message)
      return null
    }
  }

  /**
   * Int val.
   * @param o Caller-supplied input.
   * @param dflt Caller-supplied input.
   * @return int result.
   */
  private static int intVal(Object o, int dflt) {
    try {
      if (o instanceof Number) {
        return ((Number) o).intValue()
      }
      if (o != null) {
        return Integer.parseInt(o.toString().trim())
      }
    } catch (Throwable ignored) {}
    return dflt
  }

  /**
   * Lists to float array for the model or author.
   * @param emb Caller-supplied input.
   * @return float[] result.
   */
  private static float[] listToFloatArray(Object emb) {
    if (!(emb instanceof List)) {
      return null
    }
    List list = (List) emb
    float[] a = new float[list.size()]
    for (int i = 0; i < list.size(); i++) {
      Object x = list.get(i)
      if (!(x instanceof Number)) {
        return null
      }
      a[i] = ((Number) x).floatValue()
    }
    return a
  }

  /**
   * Builds index from corpus for tool or orchestration output.
   * @param corpus Caller-supplied input.
   * @param apiKey Caller-supplied input.
   * @param projectCfg Caller-supplied input.
   * @return List<RagChunk> result.
   */
  private static List<RagChunk> buildIndexFromCorpus(String corpus, String apiKey, Map projectCfg) {
    Map cfg = projectCfg ?: [:]
    EmbeddingModel embeddingModel = ExpertSkillVectorRegistry.buildEmbeddingModel(apiKey, cfg)
    int maxChunkChars = StudioAiAssistantProjectConfig.pluginRagMaxChunkChars(cfg)
    List<String> texts = ExpertSkillVectorRegistry.chunkMarkdown(corpus, maxChunkChars)
    int maxChunks = StudioAiAssistantProjectConfig.pluginRagMaxChunks(cfg)
    if (texts.size() > maxChunks) {
      log.warn('Plugin RAG truncating chunks {} -> {}', texts.size(), maxChunks)
      texts = texts.subList(0, maxChunks)
    }
    List<RagChunk> out = new ArrayList<>()
    int batchSize = StudioAiAssistantProjectConfig.pluginRagEmbedBatchSize(cfg)
    for (int i = 0; i < texts.size(); i += batchSize) {
      int hi = Math.min(i + batchSize, texts.size())
      List<String> sub = texts.subList(i, hi)
      List vecs = embeddingModel.embed(sub)
      if (vecs == null || vecs.size() != sub.size()) {
        throw new IllegalStateException('Embedding batch size mismatch')
      }
      for (int j = 0; j < sub.size(); j++) {
        Object v = vecs.get(j)
        if (!(v instanceof float[])) {
          throw new IllegalStateException('Unexpected embedding type: ' + (v?.getClass()?.name))
        }
        out.add(new RagChunk(sub.get(j), (float[]) v))
      }
    }
    return out
  }

  /**
   * Serialize wrapper.
   * @param corpusSha Caller-supplied input.
   * @param pluginBuild Caller-supplied input.
   * @param embeddingModelName Caller-supplied input.
   * @param chunks Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String serializeWrapper(String corpusSha, String pluginBuild, String embeddingModelName, List<RagChunk> chunks) {
    List<Map> rows = new ArrayList<>()
    int dims = 0
    for (RagChunk ch : chunks) {
      if (ch.embedding != null && ch.embedding.length > 0) {
        dims = ch.embedding.length
      }
      List<Float> embList = new ArrayList<>(ch.embedding.length)
      for (float f : ch.embedding) {
        embList.add(f)
      }
      rows.add([
        text     : ch.text,
        embedding: embList
      ])
    }
    String innerJson = JsonOutput.toJson([chunks: rows])
    byte[] innerBytes = innerJson.getBytes(StandardCharsets.UTF_8)
    ByteArrayOutputStream gzOut = new ByteArrayOutputStream(Math.max(innerBytes.length / 2, 4096))
    GZIPOutputStream z = new GZIPOutputStream(gzOut)
    try {
      z.write(innerBytes)
    } finally {
      z.close()
    }
    String b64 = Base64.getEncoder().encodeToString(gzOut.toByteArray())
    Map outer = [
      formatVersion : FORMAT_VERSION,
      corpusSha256  : corpusSha,
      pluginBuildId : pluginBuild,
      embeddingModel: embeddingModelName,
      dimensions    : dims,
      chunkCount    : chunks.size(),
      payloadGzipBase64: b64
    ]
    return JsonOutput.toJson(outer)
  }

  /**
   * Builds corpus text for tool or orchestration output.
   * @return Text result, or empty or null when unavailable.
   */
  static String buildCorpusText() {
    StringBuilder sb = new StringBuilder(256_000)
    appendSection(sb, 'GENERAL_LLM_AUTHORING_INSTRUCTIONS', ToolPrompts.getLlm_AUTHORING_INSTRUCTIONS())
    appendSection(sb, 'GENERAL_LLM_USER_MESSAGE_TOOLS_POLICY_PREFIX', ToolPrompts.getLlm_USER_MESSAGE_TOOLS_POLICY_PREFIX())
    appendSection(sb, 'GENERAL_LLM_CHAT_ONLY_SYSTEM', ToolPrompts.getLlm_CHAT_ONLY_SYSTEM())
    appendSection(sb, 'GENERAL_LLM_FORM_ENGINE_SUPPRESS_REPO_WRITES', ToolPrompts.getLlm_FORM_ENGINE_SUPPRESS_REPO_WRITES())
    appendSection(sb, 'GENERAL_XML_REPAIR_REMINDER_AFTER_BAD_READ', ToolPrompts.getXML_REPAIR_REMINDER_AFTER_BAD_READ())
    appendSection(sb, 'CMS_CONTENT_UPDATE_CONTENT', ToolPrompts.getUPDATE_CONTENT())
    appendSection(sb, 'CMS_CONTENT_UPDATE_CONTENT_FORM_ENGINE', ToolPrompts.getUPDATE_CONTENT_FORM_ENGINE())
    appendSection(sb, 'CMS_DEVELOPMENT_ANALYZE_TEMPLATE', ToolPrompts.getANALYZE_TEMPLATE())
    appendSection(sb, 'CMS_DEVELOPMENT_UPDATE_TEMPLATE', ToolPrompts.getUPDATE_TEMPLATE())
    appendSection(sb, 'CMS_DEVELOPMENT_UPDATE_TEMPLATE_FORM_ENGINE', ToolPrompts.getUPDATE_TEMPLATE_FORM_ENGINE())
    appendSection(sb, 'CMS_DEVELOPMENT_UPDATE_CONTENT_TYPE', ToolPrompts.getUPDATE_CONTENT_TYPE())
    appendSection(sb, 'CMS_DEVELOPMENT_UPDATE_CONTENT_TYPE_FORM_ENGINE', ToolPrompts.getUPDATE_CONTENT_TYPE_FORM_ENGINE())
    appendSection(sb, 'CMS_CONTENT_DESC_GET_CONTENT', ToolPrompts.getDESC_GET_CONTENT())
    appendSection(sb, 'CMS_CONTENT_DESC_LIST_CONTENT_DEPENDENCY_SCOPE', ToolPrompts.getDESC_LIST_CONTENT_DEPENDENCY_SCOPE())
    appendSection(sb, 'CMS_DEVELOPMENT_DESC_GET_CONTENT_TYPE_FORM_DEFINITION', ToolPrompts.getDESC_GET_CONTENT_TYPE_FORM_DEFINITION())
    appendSection(sb, 'CMS_CONTENT_DESC_WRITE_CONTENT', ToolPrompts.getDESC_WRITE_CONTENT())
    appendSection(sb, 'CMS_CONTENT_DESC_TRANSFORM_CONTENT_SUBGRAPH', ToolPrompts.getDESC_TRANSFORM_CONTENT_SUBGRAPH())
    sb.toString()
  }

  /**
   * Append section.
   * @param sb Caller-supplied input.
   * @param title Caller-supplied input.
   * @param body Caller-supplied input.
   */
  private static void appendSection(StringBuilder sb, String title, String body) {
    sb.append('\n\n=== ').append(title).append(" ===\n\n")
    sb.append((body ?: '').toString())
  }

  /**
   * Sha256 utf8.
   * @param s Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String sha256Utf8(String s) {
    MessageDigest md = MessageDigest.getInstance('SHA-256')
    byte[] d = md.digest((s ?: '').getBytes(StandardCharsets.UTF_8))
    StringBuilder hex = new StringBuilder(d.length * 2)
    for (byte b : d) {
      hex.append(String.format('%02x', b & 0xff))
    }
    return hex.toString()
  }

  /**
   * Resolves plugin build id from request and plugin context.
   * @return Text result, or empty or null when unavailable.
   */
  static String resolvePluginBuildId() {
    try {
      String yaml = plugins.org.craftercms.aiassistant.studio.sandbox.StudioAiSandboxClasspath
        .readUtf8FromClassLoader('craftercms-plugin.yaml')
      if (yaml?.trim()) {
        def m = (yaml =~ /(?m)^\s+major:\s*(\d+)\s*$/)
        def m2 = (yaml =~ /(?m)^\s+minor:\s*(\d+)\s*$/)
        def m3 = (yaml =~ /(?m)^\s+patch:\s*(\d+)\s*$/)
        if (m.find() && m2.find() && m3.find()) {
          return "${m.group(1)}.${m2.group(1)}.${m3.group(1)}"
        }
      }
    } catch (Throwable ignored) {}
    return 'unknown'
  }

  /**
   * Fetches stream utf8 for tool use.
   * @param is Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String slurpStreamUtf8(InputStream is) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream()
    byte[] buf = new byte[8192]
    int n
    while ((n = is.read(buf)) != -1) {
      baos.write(buf, 0, n)
    }
    return new String(baos.toByteArray(), StandardCharsets.UTF_8)
  }
}
