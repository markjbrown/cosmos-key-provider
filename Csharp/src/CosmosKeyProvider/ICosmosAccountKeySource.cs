namespace CosmosKeyProvider;

public interface ICosmosAccountKeySource
{
    /// <summary>
    /// Fetches the current Cosmos DB account master keys (primary and secondary) from a control-plane source (typically ARM).
    /// </summary>
    /// <remarks>
    /// This method is expected to be relatively expensive compared to data-plane requests.
    /// The <see cref="CosmosKeyProvider"/> cache is responsible for making sure this is called
    /// rarely (cold start, forced refresh on key rotation, etc.).
    /// </remarks>
    Task<CosmosAccountKeys> GetKeysAsync(CancellationToken cancellationToken = default);
}
