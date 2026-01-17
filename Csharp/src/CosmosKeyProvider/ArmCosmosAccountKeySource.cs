using Azure.Core;
using Azure.ResourceManager;
using Azure.ResourceManager.CosmosDB;

namespace CosmosKeyProvider;

/// <summary>
/// Retrieves the Cosmos DB account primary master key using Azure Resource Manager (control plane).
/// </summary>
/// <remarks>
/// This is intentionally separated from <see cref="CosmosKeyProvider"/> so callers can swap in alternative
/// key sources (mock/demo/test sources, Key Vault, etc.).
///
/// Required Azure RBAC action for the identity:
/// <c>Microsoft.DocumentDB/databaseAccounts/listKeys/action</c>
/// </remarks>
public sealed class ArmCosmosAccountKeySource : ICosmosAccountKeySource
{
    private readonly ArmClient _armClient;
    private readonly string _subscriptionId;
    private readonly string _resourceGroup;
    private readonly string _accountName;

    public ArmCosmosAccountKeySource(
        string subscriptionId,
        string resourceGroup,
        string accountName,
        TokenCredential credential)
        : this(subscriptionId, resourceGroup, accountName, new ArmClient(credential))
    {
    }

    public ArmCosmosAccountKeySource(
        string subscriptionId,
        string resourceGroup,
        string accountName,
        ArmClient armClient)
    {
        _subscriptionId = subscriptionId ?? throw new ArgumentNullException(nameof(subscriptionId));
        _resourceGroup = resourceGroup ?? throw new ArgumentNullException(nameof(resourceGroup));
        _accountName = accountName ?? throw new ArgumentNullException(nameof(accountName));
        _armClient = armClient ?? throw new ArgumentNullException(nameof(armClient));
    }

    public async Task<CosmosAccountKeys> GetKeysAsync(CancellationToken cancellationToken = default)
    {
        // Construct the ARM resource identifier for the Cosmos DB account.
        var accountId = CosmosDBAccountResource.CreateResourceIdentifier(
            _subscriptionId,
            _resourceGroup,
            _accountName);

        var account = _armClient.GetCosmosDBAccountResource(accountId);

        // NOTE: ARM call - this is the expensive/control-plane path.
        // This is the call we want to avoid on the hot path; CosmosKeyProvider ensures it only happens
        // on cold start and on key rotation events (or when forced).
        var keys = await account.GetKeysAsync(cancellationToken).ConfigureAwait(false);

        // Cosmos returns both keys. Caching both enables a low-latency fallback during primary rotation.
        return new CosmosAccountKeys(keys.Value.PrimaryMasterKey, keys.Value.SecondaryMasterKey);
    }
}
