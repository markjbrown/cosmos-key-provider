namespace CosmosKeyProvider;

/// <summary>
/// Default clock implementation for production (uses <see cref="DateTimeOffset.UtcNow"/>).
/// </summary>
public sealed class SystemClock : IClock
{
    public static SystemClock Instance { get; } = new();

    private SystemClock() { }

    public DateTimeOffset UtcNow => DateTimeOffset.UtcNow;
}
