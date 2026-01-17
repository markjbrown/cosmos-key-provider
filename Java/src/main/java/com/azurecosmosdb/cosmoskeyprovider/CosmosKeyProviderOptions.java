package com.azurecosmosdb.cosmoskeyprovider;

import java.time.Duration;
import java.util.Objects;

public final class CosmosKeyProviderOptions {
  private final Duration minRefreshInterval;

  public CosmosKeyProviderOptions() {
    this(Duration.ofMinutes(5));
  }

  public CosmosKeyProviderOptions(Duration minRefreshInterval) {
    this.minRefreshInterval = Objects.requireNonNull(minRefreshInterval, "minRefreshInterval");
    if (minRefreshInterval.isNegative()) {
      throw new IllegalArgumentException("minRefreshInterval must be non-negative");
    }
  }

  public Duration minRefreshInterval() {
    return minRefreshInterval;
  }
}
