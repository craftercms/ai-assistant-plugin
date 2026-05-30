package plugins.org.craftercms.aiassistant.studio.contrib.llm.vendor.anthropic

import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import reactor.util.retry.Retry

import java.time.Duration

/**
 * Named {@link ExchangeFilterFunction} — retries Anthropic {@code 429}/{@code 503} on WebClient streams.
 */
final class StudioAiAnthropicWebClientRetryFilter implements ExchangeFilterFunction {

  @Override
  Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
    return next.exchange(request)
      .flatMap { ClientResponse response ->
        if (StudioAiAnthropicClientConfig.isRetryableStatusCode(response.statusCode().value())) {
          return response.createException().flatMap { Mono.error(it) }
        }
        Mono.just(response)
      }
      .retryWhen(
        Retry.backoff(5, Duration.ofSeconds(5))
          .maxBackoff(Duration.ofSeconds(65))
          .filter { Throwable t ->
            Throwable cur = t
            while (cur != null) {
              if (cur instanceof WebClientResponseException) {
                if (StudioAiAnthropicClientConfig.isRetryableStatusCode(((WebClientResponseException) cur).statusCode.value())) {
                  return true
                }
              }
              cur = cur.cause
            }
            return false
          }
      )
  }
}
