package plugins.org.craftercms.aiassistant.studio.contrib.llm.vendor.anthropic

import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.http.client.ClientHttpResponse
import org.springframework.util.StreamUtils
import org.springframework.web.client.ResponseErrorHandler

import java.nio.charset.StandardCharsets

/**
 * Named {@link ResponseErrorHandler} for Anthropic — sandbox-safe (no anonymous inner class).
 * Treats {@code 429}/{@code 503} as {@link TransientAiException} so {@code RetryTemplate} retries.
 */
final class StudioAiAnthropicResponseErrorHandler implements ResponseErrorHandler {

  @Override
  boolean hasError(ClientHttpResponse response) throws IOException {
    return response?.statusCode?.error ?: false
  }

  @Override
  void handleError(ClientHttpResponse response) throws IOException {
    if (!(response?.statusCode?.error)) {
      return
    }
    String body = ''
    try {
      body = StreamUtils.copyToString(response.body, StandardCharsets.UTF_8)
    } catch (Throwable ignored) {
    }
    int code = response.statusCode.value()
    String msg = "${code} - ${body ?: '(empty body)'}"
    if (StudioAiAnthropicClientConfig.isRetryableStatusCode(code)) {
      throw new TransientAiException(msg)
    }
    if (response.statusCode.is4xxClientError()) {
      throw new NonTransientAiException(msg)
    }
    throw new TransientAiException(msg)
  }
}
