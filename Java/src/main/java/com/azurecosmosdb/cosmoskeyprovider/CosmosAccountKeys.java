package com.azurecosmosdb.cosmoskeyprovider;

import java.util.Objects;

/**
 * Cosmos DB account master keys.
 * <p>
 * Cosmos DB exposes two master keys (primary + secondary) to support key rotation. A common operational
 * pattern is:
 * <ol>
 *   <li>Use the primary key for normal traffic.</li>
 *   <li>If the primary is regenerated/rotated, temporarily fall back to the secondary key.</li>
 *   <li>Refresh keys from the control plane so future requests can return to using the new primary.</li>
 * </ol>
 * <p>
 * These are the base64-encoded strings returned by Cosmos. For REST authentication, the base64 form is
 * used as input to HMAC.
 */
public final class CosmosAccountKeys {
  private final String primaryMasterKey;
  private final String secondaryMasterKey;

  public CosmosAccountKeys(String primaryMasterKey, String secondaryMasterKey) {
    this.primaryMasterKey = requireNonBlank(primaryMasterKey, "primaryMasterKey");
    this.secondaryMasterKey = requireNonBlank(secondaryMasterKey, "secondaryMasterKey");
  }

  public String primaryMasterKey() {
    return primaryMasterKey;
  }

  public String secondaryMasterKey() {
    return secondaryMasterKey;
  }

  private static String requireNonBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must be non-blank");
    }
    return value;
  }
}
