package com.azurecosmosdb.cosmoskeyprovider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process cache for Cosmos DB account master keys (primary + secondary).
 * <p>
 * Design intent:
 * <ul>
 *   <li>Hot path: serve cached keys with no locks and no ARM calls.</li>
 *   <li>Cold/refresh path: fetch keys from a control-plane source (typically ARM) with single-flight protection.</li>
 *   <li>MinRefreshInterval prevents refresh storms from turning into ARM storms. Forced refresh bypasses this.</li>
 * </ul>
 */
public final class CosmosKeyProvider {
  private static final Logger DEFAULT_LOGGER = LoggerFactory.getLogger(CosmosKeyProvider.class);

  private final CosmosAccountKeySource keySource;
  private final Duration minRefreshInterval;
  private final Clock clock;
  private final Logger logger;

  private volatile CosmosAccountKeys cachedKeys;
  private volatile Instant lastRefreshUtc = Instant.MIN;

  private final Object refreshGate = new Object();
  private CompletableFuture<Void> refreshInFlight;

  public CosmosKeyProvider(CosmosAccountKeySource keySource) {
    this(keySource, new CosmosKeyProviderOptions(), Clock.systemUTC(), DEFAULT_LOGGER);
  }

  public CosmosKeyProvider(CosmosAccountKeySource keySource, CosmosKeyProviderOptions options) {
    this(keySource, options, Clock.systemUTC(), DEFAULT_LOGGER);
  }

  public CosmosKeyProvider(
      CosmosAccountKeySource keySource,
      CosmosKeyProviderOptions options,
      Clock clock,
      Logger logger) {
    this.keySource = Objects.requireNonNull(keySource, "keySource");
    this.minRefreshInterval = Objects.requireNonNull(options, "options").minRefreshInterval();
    if (this.minRefreshInterval.isNegative()) {
      throw new IllegalArgumentException("options.minRefreshInterval must be non-negative");
    }
    this.clock = Objects.requireNonNull(clock, "clock");
    this.logger = Objects.requireNonNull(logger, "logger");
  }

  /**
   * Gets the primary master key, refreshing on cold start.
   */
  public CompletableFuture<String> getPrimaryKey() {
    CosmosAccountKeys current = cachedKeys;
    if (current != null) {
      return CompletableFuture.completedFuture(current.primaryMasterKey());
    }

    return refreshKeys(false).thenApply(ignored -> cachedKeys.primaryMasterKey());
  }

  /**
   * Gets the secondary master key, refreshing on cold start.
   */
  public CompletableFuture<String> getSecondaryKey() {
    CosmosAccountKeys current = cachedKeys;
    if (current != null) {
      return CompletableFuture.completedFuture(current.secondaryMasterKey());
    }

    return refreshKeys(false).thenApply(ignored -> cachedKeys.secondaryMasterKey());
  }

  /**
   * Refreshes keys from the control plane.
   *
   * @param force when true, bypasses {@code minRefreshInterval}
   */
  public CompletableFuture<Void> refreshKeys(boolean force) {
    CosmosAccountKeys currentKeys = cachedKeys;
    if (!force && currentKeys != null && isWithinMinRefreshInterval()) {
      return CompletableFuture.completedFuture(null);
    }

    CompletableFuture<Void> refreshFuture;
    synchronized (refreshGate) {
      if (!force && cachedKeys != null && isWithinMinRefreshInterval()) {
        return CompletableFuture.completedFuture(null);
      }

      if (refreshInFlight != null) {
        return refreshInFlight;
      }

      refreshFuture = new CompletableFuture<>();
      refreshInFlight = refreshFuture;
    }

    logger.info("Refreshing Cosmos DB account keys via key source. Force={}", force);

    keySource.getKeys().whenComplete((keys, error) -> {
      synchronized (refreshGate) {
        refreshInFlight = null;
      }

      if (error != null) {
        refreshFuture.completeExceptionally(error);
        return;
      }

      try {
        validateKeys(keys);
        cachedKeys = keys;
        lastRefreshUtc = Instant.now(clock);
        logger.info("Cosmos DB account keys refreshed successfully.");
        refreshFuture.complete(null);
      } catch (RuntimeException e) {
        refreshFuture.completeExceptionally(e);
      }
    });

    return refreshFuture;
  }

  private boolean isWithinMinRefreshInterval() {
    Duration sinceLastRefresh = Duration.between(lastRefreshUtc, Instant.now(clock));
    return sinceLastRefresh.compareTo(minRefreshInterval) < 0;
  }

  private static void validateKeys(CosmosAccountKeys keys) {
    if (keys == null) {
      throw new IllegalStateException("Key source returned null keys.");
    }

    if (keys.primaryMasterKey() == null || keys.primaryMasterKey().isBlank()) {
      throw new IllegalStateException("Key source returned an empty Cosmos DB primary master key.");
    }

    if (keys.secondaryMasterKey() == null || keys.secondaryMasterKey().isBlank()) {
      throw new IllegalStateException("Key source returned an empty Cosmos DB secondary master key.");
    }
  }
}
