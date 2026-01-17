namespace CosmosKeyProvider;

/// <summary>
/// Time abstraction used to make refresh-interval logic deterministic in unit tests.
/// </summary>
public interface IClock
{
    DateTimeOffset UtcNow { get; }
}
