namespace CosmosKeyProvider;

/// <summary>
/// Options for Cosmos REST execution/signing.
/// </summary>
/// <remarks>
/// This is intentionally small: it mainly exists to allow deterministic signing in unit tests
/// (by controlling the timestamp used for the signature).
/// </remarks>
public sealed class CosmosRestExecutorOptions
{
    /// <summary>
    /// Value for the required x-ms-version header.
    /// </summary>
    public string ApiVersion { get; init; } = CosmosRestRequestSigner.DefaultApiVersion;

    /// <summary>
    /// Optional time provider for signing requests. The input is the attempt number (0 = first attempt, 1 = retry).
    /// </summary>
    public Func<int, DateTimeOffset>? UtcNowProvider { get; init; }
}
