using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;

namespace CosmosKeyProvider;

public static class CosmosRestRequestSigner
{
    /// <summary>
    /// Default Cosmos DB REST API version header value.
    /// </summary>
    public const string DefaultApiVersion = "2018-12-31";

    /// <summary>
    /// Generates the master-key authorization token for Cosmos DB REST requests.
    /// </summary>
    /// <remarks>
    /// Format and signing details:
    /// https://learn.microsoft.com/en-us/rest/api/cosmos-db/access-control-on-cosmosdb-resources#constructkeytoken
    /// </remarks>
    public static string GenerateMasterKeyAuthorizationSignature(
        HttpMethod method,
        string resourceType,
        string resourceLink,
        string rfc1123Date,
        string base64MasterKey)
    {
        if (method is null) throw new ArgumentNullException(nameof(method));
        if (resourceType is null) throw new ArgumentNullException(nameof(resourceType));
        if (resourceLink is null) throw new ArgumentNullException(nameof(resourceLink));
        if (rfc1123Date is null) throw new ArgumentNullException(nameof(rfc1123Date));
        if (base64MasterKey is null) throw new ArgumentNullException(nameof(base64MasterKey));

        // Cosmos expects lowercased verb/resourceType/date in the string-to-sign.
        // resourceLink is NOT lowercased; it is the logical resource path for the request.
        // Examples:
        // - List DBs:  resourceType="dbs",  resourceLink=""
        // - Get a DB:  resourceType="dbs",  resourceLink="dbs/<dbRidOrId>" (depending on the API call)
        // - List colls: resourceType="colls", resourceLink="dbs/<dbRidOrId>"
        var payload =
            $"{method.Method.ToLowerInvariant()}\n" +
            $"{resourceType.ToLowerInvariant()}\n" +
            $"{resourceLink}\n" +
            $"{rfc1123Date.ToLowerInvariant()}\n\n";

        // IMPORTANT: the master key you get from Cosmos (and from ARM listKeys) is base64.
        // Convert.FromBase64String will throw if the key is not valid base64.
        var keyBytes = Convert.FromBase64String(base64MasterKey);
        var payloadBytes = Encoding.UTF8.GetBytes(payload);

        using var hmacSha256 = new HMACSHA256(keyBytes);
        var hashPayload = hmacSha256.ComputeHash(payloadBytes);
        var signature = Convert.ToBase64String(hashPayload);

        // Cosmos requires URL-encoded auth string.
        // This value becomes the HTTP 'authorization' header.
        return WebUtility.UrlEncode($"type=master&ver=1.0&sig={signature}");
    }

    /// <summary>
    /// Signs a request with the Cosmos DB master-key Authorization header and required x-ms-* headers.
    /// </summary>
    public static void SignRequestWithMasterKey(
        HttpRequestMessage request,
        string resourceType,
        string resourceLink,
        string base64MasterKey,
        DateTimeOffset? utcNow = null,
        string apiVersion = DefaultApiVersion)
    {
        if (request is null) throw new ArgumentNullException(nameof(request));
        if (resourceType is null) throw new ArgumentNullException(nameof(resourceType));
        if (resourceLink is null) throw new ArgumentNullException(nameof(resourceLink));
        if (base64MasterKey is null) throw new ArgumentNullException(nameof(base64MasterKey));
        if (apiVersion is null) throw new ArgumentNullException(nameof(apiVersion));

        // Cosmos expects RFC1123 format for x-ms-date.
        // We keep it lowercase to match the canonicalization used in the signature.
        var date = (utcNow ?? DateTimeOffset.UtcNow)
            .UtcDateTime
            .ToString("R", CultureInfo.InvariantCulture)
            .ToLowerInvariant();

        var auth = GenerateMasterKeyAuthorizationSignature(
            request.Method,
            resourceType,
            resourceLink,
            date,
            base64MasterKey);

        // Required headers for Cosmos REST authentication:
        // - authorization (computed signature)
        // - x-ms-date (must match the value used in the signature)
        // - x-ms-version
        //
        // Use TryAddWithoutValidation to avoid strict header parsing rules.
        request.Headers.TryAddWithoutValidation("authorization", auth);
        request.Headers.TryAddWithoutValidation("x-ms-date", date);
        request.Headers.TryAddWithoutValidation("x-ms-version", apiVersion);

        // Optional but typical for Cosmos REST JSON responses.
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
    }
}
