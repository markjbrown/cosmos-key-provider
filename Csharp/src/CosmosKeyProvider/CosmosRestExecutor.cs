using System.Net;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace CosmosKeyProvider;

public static class CosmosRestExecutor
{
    /// <summary>
    /// Sends a Cosmos DB REST request using cached Cosmos account master keys.
    /// </summary>
    /// <remarks>
    /// Happy-path behavior:
    /// - Sign + send using the cached primary key.
    /// - If Cosmos returns 401/403 (commonly key rotation), sign + send using the cached secondary key.
    /// - If the secondary request succeeds, force-refresh keys so future requests use the new primary.
    /// - If the secondary request also returns 401/403, force-refresh and retry once using the refreshed primary.
    ///
    /// This sequence minimizes both data-plane retries and control-plane (ARM) calls:
    /// - The primary->secondary failover uses only cached data.
    /// - The forced refresh is single-flight in <see cref="CosmosKeyProvider"/> so only one caller hits ARM.
    /// </summary>
    /// IMPORTANT: <paramref name="requestFactory"/> must create a NEW <see cref="HttpRequestMessage"/> instance each time.
    /// Most <see cref="HttpContent"/> instances are not reusable after sending.
    /// </remarks>
    public static async Task<HttpResponseMessage> SendAsync(
        HttpClient httpClient,
        Func<CancellationToken, HttpRequestMessage> requestFactory,
        CosmosKeyProvider keyProvider,
        string resourceType,
        string resourceLink,
        CosmosRestExecutorOptions? options = null,
        ILogger? logger = null,
        CancellationToken cancellationToken = default)
    {
        if (httpClient is null) throw new ArgumentNullException(nameof(httpClient));
        if (requestFactory is null) throw new ArgumentNullException(nameof(requestFactory));
        if (keyProvider is null) throw new ArgumentNullException(nameof(keyProvider));
        if (resourceType is null) throw new ArgumentNullException(nameof(resourceType));
        if (resourceLink is null) throw new ArgumentNullException(nameof(resourceLink));

        options ??= new CosmosRestExecutorOptions();
        logger ??= NullLogger.Instance;

        async Task<HttpResponseMessage> SendOnceWithKeyAsync(int attempt, string key)
        {
            // IMPORTANT: the request must be NEW per attempt because:
            // - HttpRequestMessage instances should not be sent twice
            // - HttpContent is often a single-use stream
            var request = requestFactory(cancellationToken);
            if (request is null)
            {
                throw new InvalidOperationException("Request factory returned null.");
            }

            // The date is part of the Cosmos signature; this is why re-signing is necessary on retry.
            var utcNow = options.UtcNowProvider?.Invoke(attempt) ?? DateTimeOffset.UtcNow;
            CosmosRestRequestSigner.SignRequestWithMasterKey(
                request,
                resourceType,
                resourceLink,
                key,
                utcNow: utcNow,
                apiVersion: options.ApiVersion);

            return await httpClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
                .ConfigureAwait(false);
        }

        // Attempt 0: sign + send using the cached primary key.
        // GetKeyAsync is the "hot path": once cached, this is just an in-memory read.
        // On cold start it will perform a single-flight refresh (key source / ARM).
        var primaryKey = await keyProvider.GetKeyAsync(cancellationToken).ConfigureAwait(false);
        var response = await SendOnceWithKeyAsync(attempt: 0, primaryKey).ConfigureAwait(false);

        // 401/403 on Cosmos data plane commonly indicates that the master key used to sign the request
        // is no longer valid (e.g., primary key rotation/regeneration).
        if (response.StatusCode is not (HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden))
        {
            return response;
        }

        logger.LogWarning(
            "Cosmos REST request returned {StatusCode} for primary key; trying secondary key.",
            (int)response.StatusCode);

        response.Dispose();

        // Attempt 1: sign + send using the cached secondary key.
        // This is intentionally a pure in-memory failover (no ARM call) to minimize latency during rotation.
        var secondaryKey = await keyProvider.GetSecondaryKeyAsync(cancellationToken).ConfigureAwait(false);
        var secondaryResponse = await SendOnceWithKeyAsync(attempt: 1, secondaryKey).ConfigureAwait(false);

        if (secondaryResponse.StatusCode is not (HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden))
        {
            // The secondary key worked, which strongly suggests the primary key was rotated.
            // Refresh now so future requests can return to using the new primary key.
            logger.LogInformation(
                "Cosmos REST request succeeded with secondary key; forcing key refresh for future requests.");

            // Force refresh bypasses MinRefreshInterval so a detected key-rotation can be handled immediately.
            await keyProvider.RefreshKeyAsync(force: true, cancellationToken).ConfigureAwait(false);
            return secondaryResponse;
        }

        logger.LogWarning(
            "Cosmos REST request returned {StatusCode} for secondary key; forcing key refresh and retrying primary once.",
            (int)secondaryResponse.StatusCode);

        secondaryResponse.Dispose();

        // Force refresh bypasses MinRefreshInterval so a detected key-rotation can be handled immediately.
        await keyProvider.RefreshKeyAsync(force: true, cancellationToken).ConfigureAwait(false);

        // Attempt 2: retry once with the refreshed primary key.
        // If the refreshed primary is also invalid, the caller sees the 401/403 response.
        primaryKey = await keyProvider.GetKeyAsync(cancellationToken).ConfigureAwait(false);
        return await SendOnceWithKeyAsync(attempt: 2, primaryKey).ConfigureAwait(false);
    }
}
