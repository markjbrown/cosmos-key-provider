using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace CosmosKeyProvider;

/// <summary>
/// In-process cache for a Cosmos DB account master key (primary key).
/// </summary>
/// <remarks>
/// Design intent:
/// - Hot path: serve the cached key with no locks and no ARM calls.
/// - Cold/refresh path: fetch the key from a control-plane source (typically ARM) with single-flight protection.
/// - Consumers typically call <see cref="GetKeyAsync"/> for signing REST requests, and call
///   <see cref="RefreshKeyAsync"/> with <c>force: true</c> when they detect key rotation (401/403).
/// </remarks>
public sealed class CosmosKeyProvider
{
    private readonly ICosmosAccountKeySource _keySource;
    private readonly CosmosKeyProviderOptions _options;
    private readonly IClock _clock;
    private readonly ILogger<CosmosKeyProvider> _logger;

    // The cached *base64* master keys. Cosmos REST authentication expects the master key in base64.
    // We cache both so callers can fall back to the secondary key during primary key rotation.
    private string? _cachedPrimaryKey;
    private string? _cachedSecondaryKey;

    // Timestamp of the last successful refresh.
    // Used to enforce MinRefreshInterval for non-forced refresh attempts.
    private DateTimeOffset _lastRefreshUtc = DateTimeOffset.MinValue;

    // Single-flight gate: at most one refresh (ARM call) in progress at any time.
    // SemaphoreSlim is used instead of a lock to support async waits.
    private readonly SemaphoreSlim _refreshLock = new(1, 1);

    public CosmosKeyProvider(
        ICosmosAccountKeySource keySource,
        CosmosKeyProviderOptions? options = null,
        IClock? clock = null,
        ILogger<CosmosKeyProvider>? logger = null)
    {
        _keySource = keySource ?? throw new ArgumentNullException(nameof(keySource));
        _options = options ?? new CosmosKeyProviderOptions();
        _clock = clock ?? SystemClock.Instance;
        _logger = logger ?? NullLogger<CosmosKeyProvider>.Instance;

        if (_options.MinRefreshInterval < TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(
                nameof(options),
                _options.MinRefreshInterval,
                "MinRefreshInterval must be non-negative.");
        }
    }

    public async Task<string> GetKeyAsync(CancellationToken cancellationToken = default)
    {
        // Hot path: return the primary key with no locking.
        // (This is the normal/steady-state key used for signing.)
        var current = _cachedPrimaryKey;
        if (!string.IsNullOrEmpty(current))
        {
            return current;
        }

        // Cold path: avoid a thundering herd on cold start.
        // Multiple callers may observe null, but only the first will actually hit the control plane.
        // Others will wait on the refresh lock and then re-check the refreshed cache.
        await RefreshKeyAsync(force: false, cancellationToken).ConfigureAwait(false);
        return _cachedPrimaryKey!;
    }

    /// <summary>
    /// Gets the cached secondary key (if available), refreshing on cold start.
    /// </summary>
    /// <remarks>
    /// This is intended for key-rotation handling: if requests signed with the primary key start failing
    /// (401/403), many systems can successfully sign with the secondary key while they refresh.
    /// </remarks>
    public async Task<string> GetSecondaryKeyAsync(CancellationToken cancellationToken = default)
    {
        // Hot path: return the secondary key with no locking.
        var current = _cachedSecondaryKey;
        if (!string.IsNullOrEmpty(current))
        {
            return current;
        }

        // Cold path: refresh and populate both keys.
        await RefreshKeyAsync(force: false, cancellationToken).ConfigureAwait(false);
        return _cachedSecondaryKey!;
    }

    public async Task RefreshKeyAsync(bool force = false, CancellationToken cancellationToken = default)
    {
        // Fast exit before taking the lock.
        // This is what prevents a storm of refresh calls from turning into a storm of ARM calls.
        if (!force && _clock.UtcNow - _lastRefreshUtc < _options.MinRefreshInterval)
        {
            return;
        }

        await _refreshLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            // Double-check after acquiring the lock: another caller may have refreshed while we were waiting.
            if (!force && _clock.UtcNow - _lastRefreshUtc < _options.MinRefreshInterval)
            {
                return;
            }

            _logger.LogInformation("Refreshing Cosmos DB account key via key source. Force={Force}", force);

            // This is the control-plane call (ARM, typically). It can be throttled if called too frequently.
            // We fetch both keys so callers can fall back to the secondary key during a primary rotation.
            var newKeys = await _keySource.GetKeysAsync(cancellationToken).ConfigureAwait(false);
            if (newKeys is null)
            {
                throw new InvalidOperationException("Key source returned null keys.");
            }

            if (string.IsNullOrWhiteSpace(newKeys.PrimaryMasterKey))
            {
                throw new InvalidOperationException("Key source returned an empty Cosmos DB primary master key.");
            }

            if (string.IsNullOrWhiteSpace(newKeys.SecondaryMasterKey))
            {
                throw new InvalidOperationException("Key source returned an empty Cosmos DB secondary master key.");
            }

            _cachedPrimaryKey = newKeys.PrimaryMasterKey;
            _cachedSecondaryKey = newKeys.SecondaryMasterKey;
            _lastRefreshUtc = _clock.UtcNow;

            _logger.LogInformation("Cosmos DB account keys refreshed successfully.");
        }
        finally
        {
            _refreshLock.Release();
        }
    }
}
