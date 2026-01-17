package com.azurecosmosdb.cosmoskeyprovider;

import com.azure.core.credential.TokenCredential;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.cosmos.CosmosManager;
import com.azure.resourcemanager.cosmos.models.DatabaseAccountListKeysResult;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cosmos account key source backed by Azure Resource Manager (control plane).
 * <p>
 * Requires RBAC permission: {@code Microsoft.DocumentDB/databaseAccounts/listKeys/action}.
 */
public final class ArmCosmosAccountKeySource implements CosmosAccountKeySource {
  private static final Logger LOGGER = LoggerFactory.getLogger(ArmCosmosAccountKeySource.class);

  private final CosmosManager cosmosManager;
  private final String resourceGroupName;
  private final String accountName;

  /**
   * Creates an ARM-backed key source.
   *
   * @param credential Azure credential (for example {@code DefaultAzureCredential}).
   * @param tenantId Optional tenant ID; may be null/blank to defer to the credential defaults.
   * @param subscriptionId Azure subscription ID.
   * @param resourceGroupName Resource group containing the Cosmos account.
   * @param accountName Cosmos DB account name.
   */
  public ArmCosmosAccountKeySource(
      TokenCredential credential,
      String tenantId,
      String subscriptionId,
      String resourceGroupName,
      String accountName) {
    this(
        CosmosManager.authenticate(
            Objects.requireNonNull(credential, "credential"),
            new AzureProfile(normalizeOptional(tenantId), requireNonBlank(subscriptionId, "subscriptionId"), AzureEnvironment.AZURE)),
        resourceGroupName,
        accountName);
  }

  /**
   * Creates an ARM-backed key source from an already-configured {@link CosmosManager}.
   */
  public ArmCosmosAccountKeySource(
      CosmosManager cosmosManager,
      String resourceGroupName,
      String accountName) {
    this.cosmosManager = Objects.requireNonNull(cosmosManager, "cosmosManager");
    this.resourceGroupName = requireNonBlank(resourceGroupName, "resourceGroupName");
    this.accountName = requireNonBlank(accountName, "accountName");
  }

  @Override
  public CompletableFuture<CosmosAccountKeys> getKeys() {
    LOGGER.info("Fetching Cosmos DB master keys from ARM for {}/{}", resourceGroupName, accountName);

    return cosmosManager
        .databaseAccounts()
        .listKeysAsync(resourceGroupName, accountName)
        .map(ArmCosmosAccountKeySource::toCosmosAccountKeys)
        .toFuture();
  }

  static CosmosAccountKeys toCosmosAccountKeys(DatabaseAccountListKeysResult result) {
    Objects.requireNonNull(result, "result");
    return new CosmosAccountKeys(result.primaryMasterKey(), result.secondaryMasterKey());
  }

  private static String requireNonBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must be non-blank");
    }
    return value;
  }

  private static String normalizeOptional(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value;
  }
}
