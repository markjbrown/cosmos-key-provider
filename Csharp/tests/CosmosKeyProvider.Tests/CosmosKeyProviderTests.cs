using System.Collections.Concurrent;
using CosmosKeyProvider;

namespace CosmosKeyProvider.Tests;

public sealed class CosmosKeyProviderTests
{
    [Fact]
    public async Task GetKeyAsync_ConcurrentColdStart_IsSingleFlight()
    {
        var clock = new FakeClock(DateTimeOffset.UtcNow);
        var keySource = new FakeKeySource(async ct =>
        {
            await Task.Delay(50, ct);
            return new CosmosAccountKeys(
                PrimaryMasterKey: "a2V5MQ==",   // base64("key1")
                SecondaryMasterKey: "c2VjMg=="); // base64("sec2")
        });

        var provider = new global::CosmosKeyProvider.CosmosKeyProvider(
            keySource,
            options: new CosmosKeyProviderOptions { MinRefreshInterval = TimeSpan.FromMinutes(5) },
            clock: clock);

        var keys = await Task.WhenAll(Enumerable.Range(0, 50).Select(_ => provider.GetKeyAsync()));

        Assert.All(keys, k => Assert.Equal("a2V5MQ==", k));
        Assert.Equal(1, keySource.CallCount);
    }

    [Fact]
    public async Task RefreshKeyAsync_RespectsMinRefreshInterval_WhenNotForced()
    {
        var start = new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero);
        var clock = new FakeClock(start);

        var keys = new ConcurrentQueue<string>(new[] { "a2V5MQ==", "a2V5Mg==", "a2V5Mw==" });
        var keySource = new FakeKeySource(_ =>
        {
            if (!keys.TryDequeue(out var key))
            {
                throw new InvalidOperationException("No more keys available in test queue.");
            }

            // Secondary isn't important for this test; keep it stable.
            return Task.FromResult(new CosmosAccountKeys(key, SecondaryMasterKey: "c2VjMg=="));
        });

        var provider = new global::CosmosKeyProvider.CosmosKeyProvider(
            keySource,
            options: new CosmosKeyProviderOptions { MinRefreshInterval = TimeSpan.FromMinutes(5) },
            clock: clock);

        await provider.RefreshKeyAsync(force: true);
        Assert.Equal(1, keySource.CallCount);

        clock.Advance(TimeSpan.FromMinutes(1));
        await provider.RefreshKeyAsync(force: false);
        Assert.Equal(1, keySource.CallCount);

        clock.Advance(TimeSpan.FromMinutes(10));
        await provider.RefreshKeyAsync(force: false);
        Assert.Equal(2, keySource.CallCount);

        var currentKey = await provider.GetKeyAsync();
        Assert.Equal("a2V5Mg==", currentKey);
    }

    [Fact]
    public void CosmosRestAuthSignature_MatchesDocsExample()
    {
        // Example from:
        // https://learn.microsoft.com/en-us/rest/api/cosmos-db/access-control-on-cosmosdb-resources#constructkeytoken
        var verb = HttpMethod.Get;
        var resourceType = "dbs";
        var resourceLink = "dbs/ToDoList";
        var date = "Thu, 27 Apr 2017 00:51:12 GMT";
        var key = "dsZQi3KtZmCv1ljt3VNWNm7sQUF1y5rJfC6kv5JiwvW0EndXdDku/dkKBp8/ufDToSxLzR4y+O/0H/t4bQtVNw==";

        var actual = CosmosRestRequestSigner.GenerateMasterKeyAuthorizationSignature(
            verb,
            resourceType,
            resourceLink,
            date,
            key);

        // Deterministic expected output for the example above.
        const string expected = "type%3Dmaster%26ver%3D1.0%26sig%3Dc09PEVJrgp2uQRkr934kFbTqhByc7TVr3OHyqlu%2Bc%2Bc%3D";
        Assert.Equal(expected, actual);
    }

    private sealed class FakeClock : IClock
    {
        public FakeClock(DateTimeOffset initialUtcNow) => UtcNow = initialUtcNow;

        public DateTimeOffset UtcNow { get; private set; }

        public void Advance(TimeSpan delta) => UtcNow = UtcNow.Add(delta);
    }

    private sealed class FakeKeySource : ICosmosAccountKeySource
    {
        private readonly Func<CancellationToken, Task<CosmosAccountKeys>> _getKeys;

        public FakeKeySource(Func<CancellationToken, Task<CosmosAccountKeys>> getKeys) => _getKeys = getKeys;

        public int CallCount => _callCount;

        private int _callCount;

        public async Task<CosmosAccountKeys> GetKeysAsync(CancellationToken cancellationToken = default)
        {
            Interlocked.Increment(ref _callCount);
            return await _getKeys(cancellationToken);
        }
    }
}
