package plugins.org.craftercms.aiassistant.studio.contrib.imagegen

import plugins.org.craftercms.aiassistant.studio.spi.imagegen.StudioAiImageGenContext
import plugins.org.craftercms.aiassistant.studio.spi.imagegen.StudioAiImageGenerator
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.http.AiHttpProxy
import plugins.org.craftercms.aiassistant.studio.contrib.llm.StudioAiProviderCredentials
import plugins.org.craftercms.aiassistant.studio.engine.turn.AiOrchestration

import plugins.org.craftercms.aiassistant.studio.sandbox.StudioAiSandboxHttp
import java.nio.charset.StandardCharsets
import java.net.URL
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Locale
import java.util.Map
import java.util.Set

/**
 * Built-in {@code POST …/v1/images/generations} HTTP wire for {@link StudioAiImageGenerator}. Not tied to the chat LLM vendor:
 * credentials and base URL come from {@link StudioAiImageGenContext}.
 */
final class CompatibleImageGenerator implements StudioAiImageGenerator {

  private static final Logger LOG = LoggerFactory.getLogger(CompatibleImageGenerator.class)

  private static final Set<String> GPT_IMAGE_API_SIZES =
    Collections.unmodifiableSet(
      new LinkedHashSet<>(['auto', '1024x1024', '1024x1536', '1536x1024'])
    )

  /**
   * True when gpt image family model.
   * @param model Caller-supplied input.
   * @return True when the check succeeds.
   */
  static boolean isGptImageFamilyModel(String model) {
    String m = (model ?: '').toString().trim().toLowerCase(Locale.US)
    return m.startsWith('gpt-image') || m.startsWith('chatgpt-image')
  }

  /**
   * Maps model-chosen or legacy aspect sizes to values accepted by the GPT Image Images API.
   * Returns {@code null} to omit {@code size} from the JSON body when input is blank and model is not GPT Image family.
   */
  static String normalizeImageGenerationSize(String sizeRaw, String model) {
    String modelNorm = (model ?: '').toString().trim()
    boolean gptFamily = isGptImageFamilyModel(modelNorm)
    String size = (sizeRaw ?: '').toString().trim().toLowerCase(Locale.US)
    if (!gptFamily) {
      return size ?: null
    }
    if (!size) {
      // GPT image models accept "auto"; omitting size can yield 200 with no data[] on some hosts.
      return gptFamily ? 'auto' : null
    }
    if (GPT_IMAGE_API_SIZES.contains(size)) {
      return size
    }
    def dim = (size =~ /^(\d+)\s*[x×]\s*(\d+)$/)
    if (dim.matches()) {
      int w = dim.group(1) as int
      int h = dim.group(2) as int
      if (w > 0 && h > 0) {
        double ratio = w / (double) h
        String mapped
        if (ratio > 1.12d) {
          mapped = '1536x1024'
        } else if (ratio < 0.88d) {
          mapped = '1024x1536'
        } else {
          mapped = '1024x1024'
        }
        if (mapped != size) {
          LOG.info(
            'CompatibleImageGenerator: coerced unsupported size {} to {} for model {}',
            sizeRaw,
            mapped,
            modelNorm
          )
        }
        return mapped
      }
    }
    String mappedAlias
    switch (size) {
      case 'landscape':
      case 'wide':
        mappedAlias = '1536x1024'
        break
      case 'portrait':
      case 'tall':
        mappedAlias = '1024x1536'
        break
      case 'square':
        mappedAlias = '1024x1024'
        break
      default:
        mappedAlias = '1024x1024'
    }
    LOG.info(
      'CompatibleImageGenerator: coerced unsupported size {} to {} for model {}',
      sizeRaw,
      mappedAlias,
      modelNorm
    )
    mappedAlias
  }

  @Override
  Map generate(Map input, StudioAiImageGenContext ctx) {
    String apiKey = (ctx?.imagesApiKey ?: '').toString().trim()
    if (!apiKey) {
      return [error: true, message: 'No API key configured for image generation']
    }
    String urlPost = (ctx?.imagesGenerationsHttpUrl ?: '').toString().trim()
    if (!urlPost) {
      urlPost = StudioAiProviderCredentials.httpLlmImagesGenerationsUrl()
    }
    return postImagesGenerations(apiKey, urlPost, (ctx?.defaultImageModel ?: '').toString(), (Map) (input ?: [:]))
  }

  /**
   * JSON body for {@code POST /v1/images/generations} — assembled as a string so no accidental extra keys are merged.
   */
  static String buildImagesGenerationsRequestJson(String model, String prompt, String size, String quality) {
    String mLower = model.toLowerCase(Locale.US)
    boolean gptFamily = mLower.startsWith('gpt-image') || mLower.startsWith('chatgpt-image')
    StringBuilder sb = new StringBuilder(Math.max(96, prompt.length() + 96))
    sb.append('{')
    sb.append('"model":').append(JsonOutput.toJson(model)).append(',')
    sb.append('"prompt":').append(JsonOutput.toJson(prompt)).append(',')
    sb.append('"n":1')
    // Do not send response_format — rejected by gpt-image and many current /v1/images/generations hosts.
    // Default response is a temporary https URL; b64_json is converted to a data URL when present.
    if (size) {
      sb.append(',"size":').append(JsonOutput.toJson(size))
    }
    if (quality && gptFamily) {
      sb.append(',"quality":').append(JsonOutput.toJson(quality))
    }
    if (gptFamily) {
      sb.append(',"output_format":').append(JsonOutput.toJson('png'))
    }
    sb.append('}')
    return sb.toString()
  }

  /**
   * Post images generations.
   * @param apiKey Caller-supplied input.
   * @param postUrl Caller-supplied input.
   * @param defaultImageModel Caller-supplied input.
   * @param input Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  /** Bumped when image wire changes so studio.log confirms Groovy reloaded this class after deploy/restart. */
  private static final String WIRE_REVISION = 'sandbox-http-v3'

  static Map postImagesGenerations(String apiKey, String postUrl, String defaultImageModel, Map input) {
    def prompt = input?.prompt?.toString()?.trim()
    if (!prompt) {
      return [error: true, message: 'Missing required field: prompt']
    }
    def modelRaw = input?.model?.toString()?.trim() ?: defaultImageModel
    if (!modelRaw?.trim()) {
      return [error: true, message: 'No image model configured']
    }
    String model = AiOrchestration.normalizeImagesApiModelId(AiOrchestration.llmCanonicalizeApiModelToken(modelRaw))
    if (!model?.trim()) {
      return [error: true, message: 'No image model configured']
    }
    String sizeRaw = input?.size?.toString()?.trim()
    def quality = input?.quality?.toString()?.trim()
    model = AiOrchestration.normalizeImagesApiModelId(model)
    if (!model?.trim()) {
      return [error: true, message: 'No image model configured']
    }
    String size = normalizeImageGenerationSize(sizeRaw, model)
    String body = buildImagesGenerationsRequestJson(model, prompt, size, quality)
    LOG.info(
      'CompatibleImageGenerator: POST {} model={} size={} revision={}',
      postUrl,
      model,
      size ?: '(omit)',
      WIRE_REVISION
    )
    if (LOG.isDebugEnabled()) {
      LOG.debug('CompatibleImageGenerator wireJson={}', AiHttpProxy.elideForLog(body, 900))
    }
    try {
      def ex = StudioAiSandboxHttp.postBytes(
        URI.create(postUrl),
        body.getBytes(StandardCharsets.UTF_8),
        'application/json; charset=UTF-8',
        [
          authorization   : apiKey,
          connectTimeoutMs: 120_000,
          readTimeoutMs   : 300_000,
          maxRedirects    : 0,
          ssrfCheck       : false
        ])
      if (!ex.ok && (ex.errorMessage ?: '').toString().trim()) {
        return [error: true, message: "Images API I/O: ${ex.errorMessage}"]
      }
      int code = ex.statusCode
      def text = ex.bodyText ?: ''
      if (code < 200 || code >= 300) {
        def errMsg = "Images API HTTP ${code}"
        try {
          def parsed = new JsonSlurper().parseText(text ?: '{}')
          if (parsed instanceof Map && parsed.error?.message) {
            errMsg = "${errMsg}: ${parsed.error.message}"
          } else if (text?.trim()) {
            errMsg = "${errMsg}: ${text.length() > 800 ? text.substring(0, 800) + '…' : text}"
          }
        } catch (Throwable ignored) {
          if (text?.trim()) errMsg = "${errMsg}: ${text.length() > 800 ? text.substring(0, 800) + '…' : text}"
        }
        if (errMsg?.contains('response_format')) {
          LOG.warn(
            'CompatibleImageGenerator HTTP {} — provider rejected response_format (wire revision {} omits that field). attemptElided={}',
            code,
            WIRE_REVISION,
            AiHttpProxy.elideForLog(body, 700)
          )
        }
        return [error: true, message: errMsg, httpStatus: code]
      }
      def json = new JsonSlurper().parseText(text ?: '{}')
      if (!(json instanceof Map)) {
        return [error: true, message: 'Unexpected images API response shape']
      }
      def data = json.data
      if (!(data instanceof List) || data.isEmpty()) {
        String keys = (json instanceof Map) ? ((Map) json).keySet()?.join(',') : ''
        LOG.warn(
          'CompatibleImageGenerator: HTTP {} from {} model={} bodyChars={} topLevelKeys={} bodyPrefix=\n{}',
          code,
          postUrl,
          model,
          (text ?: '').length(),
          keys,
          AiHttpProxy.elideForLog(text, 1200)
        )
        String hint = keys?.contains('error') ? ' See bodyPrefix for provider error JSON.' : ''
        return [
          error  : true,
          message: "Images API returned no data array (HTTP ${code}, bodyChars=${(text ?: '').length()}).${hint}",
          raw    : AiHttpProxy.elideForLog(text, 4000)
        ]
      }
      def first = data[0]
      if (!(first instanceof Map)) {
        return [error: true, message: 'Images API data[0] is not an object', raw: text]
      }
      LOG.info(
        'CompatibleImageGenerator: HTTP {} ok model={} dataItems={} hasUrl={} hasB64={} revision={}',
        code,
        model,
        data.size(),
        first.url != null,
        first.b64_json != null,
        WIRE_REVISION
      )
      def out = [
        ok            : true,
        tool          : 'GenerateImage',
        model         : model,
        url           : first.url,
        b64_json      : first.b64_json,
        revised_prompt: first.revised_prompt
      ]
      if (!out.url && !out.b64_json) {
        return [error: true, message: 'No url or b64_json in image result', raw: text]
      }
      if (!out.url && out.b64_json) {
        def of = (first.output_format ?: json.output_format)?.toString()?.trim()?.toLowerCase(Locale.US)
        def mime = 'image/png'
        if (of == 'jpeg' || of == 'jpg') {
          mime = 'image/jpeg'
        } else if (of == 'webp') {
          mime = 'image/webp'
        } else if (of == 'png') {
          mime = 'image/png'
        }
        out.url = 'data:' + mime + ';base64,' + out.b64_json
        out.urlIsData = true
        out.remove('b64_json')
      }
      out.hint = Boolean.TRUE.equals(out.urlIsData)
        ? 'Image is returned as base64; `url` is a data URL for chat preview. For production CMS assets, save to /static-assets/ and reference that path.'
        : 'Image URL expires; for CMS use, download and upload to /static-assets/ then reference in content.'
      return out
    } catch (Throwable t) {
      LOG.warn('CompatibleImageGenerator failed: {}', t.toString())
      return [error: true, message: (t.message ?: t.toString())]
    }
  }
}
