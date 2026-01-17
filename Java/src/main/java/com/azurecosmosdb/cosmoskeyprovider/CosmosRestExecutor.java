package com.azurecosmosdb.cosmoskeyprovider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper for executing Cosmos DB REST requests using cached Cosmos account master keys.
 * <p>
 * Happy-path behavior:
 * <ul>
 *   <li>Sign + send using the cached primary key.</li>
 *   <li>If Cosmos returns 401/403, sign + send using the cached secondary key.</li>
 *   <li>If the secondary request succeeds, force-refresh keys so future requests use the new primary.</li>
 *   <li>If the secondary request also returns 401/403, force-refresh and retry once using the refreshed primary.</li>
 * </ul>
 * <p>
 * IMPORTANT: {@link UnsignedRequestFactory} must create a NEW request instance each time.
 */
public final class CosmosRestExecutor {
  private static final Logger DEFAULT_LOGGER = LoggerFactory.getLogger(CosmosRestExecutor.class);

  private CosmosRestExecutor() {
  }

  @FunctionalInterface
  public interface UnsignedRequestFactory {
    UnsignedRequest create(int attempt);
  }

  /**
   * Represents a request that has not yet been signed with Cosmos auth headers.
   */
  public record UnsignedRequest(
      String httpMethod,
      URI uri,
      HttpRequest.BodyPublisher bodyPublisher,
      List<Header> headers) {
    public UnsignedRequest {
      Objects.requireNonNull(httpMethod, "httpMethod");
      Objects.requireNonNull(uri, "uri");
      Objects.requireNonNull(bodyPublisher, "bodyPublisher");
      headers = (headers == null) ? List.of() : List.copyOf(headers);
    }

    public static UnsignedRequest get(URI uri) {
      return new UnsignedRequest("GET", uri, HttpRequest.BodyPublishers.noBody(), List.of());
    }
  }

  public record Header(String name, String value) {
    public Header {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(value, "value");
    }
  }

  public static CompletableFuture<HttpResponse<byte[]>> sendAsync(
      HttpClient httpClient,
      UnsignedRequestFactory requestFactory,
      CosmosKeyProvider keyProvider,
      String resourceType,
      String resourceLink) {
    return sendAsync(httpClient, requestFactory, keyProvider, resourceType, resourceLink, new CosmosRestExecutorOptions(), DEFAULT_LOGGER);
  }

  public static CompletableFuture<HttpResponse<byte[]>> sendAsync(
      HttpClient httpClient,
      UnsignedRequestFactory requestFactory,
      CosmosKeyProvider keyProvider,
      String resourceType,
      String resourceLink,
      CosmosRestExecutorOptions options,
      Logger logger) {
    Objects.requireNonNull(httpClient, "httpClient");
    Objects.requireNonNull(requestFactory, "requestFactory");
    Objects.requireNonNull(keyProvider, "keyProvider");
    Objects.requireNonNull(resourceType, "resourceType");
    Objects.requireNonNull(resourceLink, "resourceLink");

    CosmosRestExecutorOptions effectiveOptions = (options == null) ? new CosmosRestExecutorOptions() : options;
    Logger effectiveLogger = (logger == null) ? DEFAULT_LOGGER : logger;

    return keyProvider.getPrimaryKey()
        .thenCompose(primaryKey -> sendOnceWithKeyAsync(
            httpClient,
            requestFactory,
            resourceType,
            resourceLink,
            effectiveOptions,
            attempt(0),
            primaryKey))
        .thenCompose(response0 -> {
          if (!isAuthFailure(response0.statusCode())) {
            return CompletableFuture.completedFuture(response0);
          }

          effectiveLogger.warn("Cosmos REST request returned {} for primary key; trying secondary key.", response0.statusCode());

          return keyProvider.getSecondaryKey()
              .thenCompose(secondaryKey -> sendOnceWithKeyAsync(
                  httpClient,
                  requestFactory,
                  resourceType,
                  resourceLink,
                  effectiveOptions,
                  attempt(1),
                  secondaryKey))
              .thenCompose(response1 -> {
                if (!isAuthFailure(response1.statusCode())) {
                  effectiveLogger.info("Cosmos REST request succeeded with secondary key; forcing key refresh for future requests.");
                  return keyProvider.refreshKeys(true).thenApply(ignored -> response1);
                }

                effectiveLogger.warn(
                    "Cosmos REST request returned {} for secondary key; forcing key refresh and retrying primary once.",
                    response1.statusCode());

                return keyProvider.refreshKeys(true)
                    .thenCompose(ignored -> keyProvider.getPrimaryKey())
                    .thenCompose(refreshedPrimary -> sendOnceWithKeyAsync(
                        httpClient,
                        requestFactory,
                        resourceType,
                        resourceLink,
                        effectiveOptions,
                        attempt(2),
                        refreshedPrimary));
              });
        });
  }

  private static CompletableFuture<HttpResponse<byte[]>> sendOnceWithKeyAsync(
      HttpClient httpClient,
      UnsignedRequestFactory requestFactory,
      String resourceType,
      String resourceLink,
      CosmosRestExecutorOptions options,
      int attempt,
      String base64MasterKey) {
    UnsignedRequest unsigned = requestFactory.create(attempt);
    if (unsigned == null) {
      throw new IllegalStateException("Request factory returned null.");
    }

    HttpRequest.Builder builder = HttpRequest.newBuilder(unsigned.uri());

    for (Header header : Optional.ofNullable(unsigned.headers()).orElse(List.of())) {
      builder.header(header.name(), header.value());
    }

    builder.method(unsigned.httpMethod(), unsigned.bodyPublisher());

    CosmosRestRequestSigner.signRequestWithMasterKey(
        builder,
        unsigned.httpMethod(),
        resourceType,
        resourceLink,
        base64MasterKey,
        options.utcNow(attempt),
        options.apiVersion());

    HttpRequest request = builder.build();
    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
  }

  private static boolean isAuthFailure(int statusCode) {
    return statusCode == 401 || statusCode == 403;
  }

  private static int attempt(int attempt) {
    return attempt;
  }
}
