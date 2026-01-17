package com.azurecosmosdb.cosmoskeyprovider;

import java.util.concurrent.CompletableFuture;

/**
 * Fetches Cosmos DB account master keys from a control-plane source (typically Azure Resource Manager).
 * <p>
 * This call is expected to be expensive relative to data-plane requests. The in-process cache
 * ({@link CosmosKeyProvider}) is responsible for ensuring this is called rarely (cold start,
 * forced refresh on key rotation, etc.).
 */
public interface CosmosAccountKeySource {
  /**
   * Fetches the current Cosmos DB master keys (primary + secondary).
   */
  CompletableFuture<CosmosAccountKeys> getKeys();
}
