using System.Net;
using System.Net.Http.Headers;
using System.Linq;
using System.Text;
using CosmosKeyProvider;
using Xunit;

namespace CosmosKeyProvider.Tests;

public sealed class CosmosRestExecutorTests
{
    [Fact]
    public async Task SendAsync_On401_UsesSecondaryKey_ThenRefreshesKeys()
    {
        // This test demonstrates the intended integration pattern:
        //
        // 1) A key source (normally ARM) produces the *current* Cosmos account keys (primary + secondary).
        // 2) CosmosKeyProvider caches those keys in-memory.
        // 3) CosmosRestExecutor signs + sends with primary; if Cosmos returns 401/403 it fails over
        //    to secondary; if secondary succeeds it refreshes keys so future requests use the new primary.

        // Cosmos REST master-key auth expects the master key to be base64.
        // (Real Cosmos account keys are base64 strings.)
        var primaryKeyOne = Convert.ToBase64String(Encoding.UTF8.GetBytes("primary-one"));
        var secondaryKeyOne = Convert.ToBase64String(Encoding.UTF8.GetBytes("secondary-one"));
        var primaryKeyTwo = Convert.ToBase64String(Encoding.UTF8.GetBytes("primary-two"));
        var secondaryKeyTwo = Convert.ToBase64String(Encoding.UTF8.GetBytes("secondary-two"));

        // This stub simulates key rotation:
        // - First call returns the "old" keys (primary-one/secondary-one)
        // - Second call returns the "new" keys (primary-two/secondary-two)
        var keySource = new SequenceKeySource(
            new[]
            {
                new CosmosAccountKeys(primaryKeyOne, secondaryKeyOne),
                new CosmosAccountKeys(primaryKeyTwo, secondaryKeyTwo),
            });

        // The key provider is the component your app would keep as a singleton.
        // It will call the key source at most once on cold start, and again only when forced.
        var keyProvider = new global::CosmosKeyProvider.CosmosKeyProvider(keySource);

        // This fake HTTP handler simulates Cosmos data plane behavior:
        // first request => 401 Unauthorized (primary key rejected)
        // second request => 200 OK (secondary key accepted)
        var handler = new RecordingHandler(
            new[]
            {
                new HttpResponseMessage(HttpStatusCode.Unauthorized),
                new HttpResponseMessage(HttpStatusCode.OK),
            });

        using var httpClient = new HttpClient(handler);

        // IMPORTANT: CosmosRestExecutor requires a request factory that creates a NEW HttpRequestMessage
        // for each attempt. Requests/contents are generally not safe to reuse across retries.
        static HttpRequestMessage CreateRequest(CancellationToken _)
        {
            var request = new HttpRequestMessage(HttpMethod.Get, "https://example.documents.azure.com/dbs");
            request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
            return request;
        }

        // Make the signature deterministic so we can compare headers between attempt 0 and attempt 1.
        // The date will differ across attempts, so the resulting Authorization header should differ.
        var options = new CosmosRestExecutorOptions
        {
            ApiVersion = "2018-12-31",
            UtcNowProvider = attempt => attempt == 0
                ? new DateTimeOffset(2020, 01, 01, 00, 00, 00, TimeSpan.Zero)
                : new DateTimeOffset(2020, 01, 01, 00, 00, 01, TimeSpan.Zero)
        };

        // Act: send GET /dbs.
        // - Attempt 0 signs with primary-one and receives 401.
        // - Attempt 1 signs with secondary-one and succeeds.
        // - The executor then refreshes keys so future requests use the new primary.
        var response = await CosmosRestExecutor.SendAsync(
            httpClient,
            CreateRequest,
            keyProvider,
            resourceType: "dbs",
            resourceLink: "",
            options: options,
            logger: null,
            cancellationToken: default);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        // Two requests should have been sent (primary + secondary).
        Assert.Equal(2, handler.Requests.Count);

        // Key-source call count:
        // - Cold start populates BOTH primary and secondary from the key source.
        // - After the secondary succeeds, the executor forces a refresh so future requests can use the new primary.
        Assert.Equal(2, keySource.CallCount);

        // Both attempts should have been signed and should carry the required Cosmos headers.
        foreach (var req in handler.Requests)
        {
            Assert.True(req.Headers.Contains("x-ms-date"));
            Assert.True(req.Headers.Contains("x-ms-version"));
            Assert.True(req.Headers.Contains("authorization"));
        }

        // Ensure we actually re-signed (key and date changed => different auth header).
        var auth1 = handler.Requests[0].Headers.GetValues("authorization").Single();
        var auth2 = handler.Requests[1].Headers.GetValues("authorization").Single();
        Assert.NotEqual(auth1, auth2);
    }

    [Fact]
    public async Task SendAsync_On401Then401_RefreshesKeys_AndRetriesPrimaryOnce()
    {
        var primaryKeyOne = Convert.ToBase64String(Encoding.UTF8.GetBytes("primary-one"));
        var secondaryKeyOne = Convert.ToBase64String(Encoding.UTF8.GetBytes("secondary-one"));
        var primaryKeyTwo = Convert.ToBase64String(Encoding.UTF8.GetBytes("primary-two"));
        var secondaryKeyTwo = Convert.ToBase64String(Encoding.UTF8.GetBytes("secondary-two"));

        var keySource = new SequenceKeySource(
            new[]
            {
                new CosmosAccountKeys(primaryKeyOne, secondaryKeyOne),
                new CosmosAccountKeys(primaryKeyTwo, secondaryKeyTwo),
            });

        var keyProvider = new global::CosmosKeyProvider.CosmosKeyProvider(keySource);

        // Simulate:
        // - primary fails (401)
        // - secondary fails (401)
        // - after refresh, primary succeeds (200)
        var handler = new RecordingHandler(
            new[]
            {
                new HttpResponseMessage(HttpStatusCode.Unauthorized),
                new HttpResponseMessage(HttpStatusCode.Unauthorized),
                new HttpResponseMessage(HttpStatusCode.OK),
            });

        using var httpClient = new HttpClient(handler);

        static HttpRequestMessage CreateRequest(CancellationToken _)
        {
            var request = new HttpRequestMessage(HttpMethod.Get, "https://example.documents.azure.com/dbs");
            request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
            return request;
        }

        var options = new CosmosRestExecutorOptions
        {
            ApiVersion = "2018-12-31",
            UtcNowProvider = attempt => attempt switch
            {
                0 => new DateTimeOffset(2020, 01, 01, 00, 00, 00, TimeSpan.Zero),
                1 => new DateTimeOffset(2020, 01, 01, 00, 00, 01, TimeSpan.Zero),
                _ => new DateTimeOffset(2020, 01, 01, 00, 00, 02, TimeSpan.Zero),
            }
        };

        var response = await CosmosRestExecutor.SendAsync(
            httpClient,
            CreateRequest,
            keyProvider,
            resourceType: "dbs",
            resourceLink: "",
            options: options,
            logger: null,
            cancellationToken: default);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal(3, handler.Requests.Count);

        // Cold start fetch + forced refresh fetch.
        Assert.Equal(2, keySource.CallCount);

        foreach (var req in handler.Requests)
        {
            Assert.True(req.Headers.Contains("x-ms-date"));
            Assert.True(req.Headers.Contains("x-ms-version"));
            Assert.True(req.Headers.Contains("authorization"));
        }
    }

    private sealed class SequenceKeySource : ICosmosAccountKeySource
    {
        private readonly Queue<CosmosAccountKeys> _keys;

        public int CallCount { get; private set; }

        public SequenceKeySource(IEnumerable<CosmosAccountKeys> keys)
        {
            _keys = new Queue<CosmosAccountKeys>(keys);
        }

        public Task<CosmosAccountKeys> GetKeysAsync(CancellationToken cancellationToken = default)
        {
            CallCount++;
            if (_keys.Count == 0)
            {
                throw new InvalidOperationException("No more keys configured for the test.");
            }

            return Task.FromResult(_keys.Dequeue());
        }
    }

    private sealed class RecordingHandler : HttpMessageHandler
    {
        private readonly Queue<HttpResponseMessage> _responses;

        public List<HttpRequestMessage> Requests { get; } = new();

        public RecordingHandler(IEnumerable<HttpResponseMessage> responses)
        {
            _responses = new Queue<HttpResponseMessage>(responses);
        }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Requests.Add(request);

            if (_responses.Count == 0)
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK));
            }

            return Task.FromResult(_responses.Dequeue());
        }
    }
}
