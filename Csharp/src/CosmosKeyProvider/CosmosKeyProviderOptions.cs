namespace CosmosKeyProvider;

public sealed record CosmosKeyProviderOptions
{
    /// <summary>
    /// Minimum amount of time to wait between non-forced refresh attempts.
    /// </summary>
    /// <remarks>
    /// This prevents repeated control-plane calls (e.g., ARM listKeys) under load.
    /// Forced refresh (<c>force: true</c>) bypasses this, so key rotation can be handled immediately.
    /// </remarks>
    public TimeSpan MinRefreshInterval { get; init; } = TimeSpan.FromMinutes(5);
}
