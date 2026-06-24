package plugins.org.craftercms.aiassistant.studio.contrib.llm.vendor.anthropic

import org.springframework.web.client.ResponseErrorHandler
import org.springframework.web.reactive.function.client.WebClient

/**
 * Anthropic HTTP wiring for Spring AI {@link org.springframework.ai.anthropic.api.AnthropicApi}.
 */
final class StudioAiAnthropicClientConfig {

  private StudioAiAnthropicClientConfig() {}

  static boolean isRetryableStatusCode(int code) {
    return code == 429 || code == 503 || code == 529
  }

  static ResponseErrorHandler responseErrorHandler() {
    return new StudioAiAnthropicResponseErrorHandler()
  }

  static WebClient.Builder webClientBuilder() {
    return WebClient.builder().filter(new StudioAiAnthropicWebClientRetryFilter())
  }

  static void applyTo(org.springframework.ai.anthropic.api.AnthropicApi.Builder builder) {
    builder
      .responseErrorHandler(responseErrorHandler())
      .webClientBuilder(webClientBuilder())
  }
}
