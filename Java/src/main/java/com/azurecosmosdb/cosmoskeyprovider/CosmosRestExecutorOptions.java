package com.azurecosmosdb.cosmoskeyprovider;

import java.time.Instant;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * Options for Cosmos REST execution/signing.
 * <p>
 * Intentionally small: it mainly exists to allow deterministic signing in unit tests
 * (by controlling the timestamp used for the signature).
 */
public final class CosmosRestExecutorOptions {
  private final String apiVersion;
  private final IntFunction<Instant> utcNowProvider;

  public CosmosRestExecutorOptions() {
    this(CosmosRestRequestSigner.DEFAULT_API_VERSION, attempt -> Instant.now());
  }

  public CosmosRestExecutorOptions(String apiVersion, IntFunction<Instant> utcNowProvider) {
    this.apiVersion = Objects.requireNonNull(apiVersion, "apiVersion");
    this.utcNowProvider = Objects.requireNonNull(utcNowProvider, "utcNowProvider");
  }

  public String apiVersion() {
    return apiVersion;
  }

  public Instant utcNow(int attempt) {
    return utcNowProvider.apply(attempt);
  }
}
