package com.azurecosmosdb.cosmoskeyprovider;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cosmos DB REST master-key request signer.
 * <p>
 * Format and signing details:
 * https://learn.microsoft.com/en-us/rest/api/cosmos-db/access-control-on-cosmosdb-resources#constructkeytoken
 */
public final class CosmosRestRequestSigner {
  /**
   * Default Cosmos DB REST API version header value.
   */
  public static final String DEFAULT_API_VERSION = "2018-12-31";

    // Use a strict RFC1123-style format with a 2-digit day-of-month.
    // (Some built-in RFC1123 formatters emit a single-digit day, which can be surprising.)
    private static final DateTimeFormatter RFC_1123 =
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .withZone(ZoneOffset.UTC);

  private CosmosRestRequestSigner() {
  }

  /**
   * Generates the master-key authorization token for Cosmos DB REST requests.
   */
  public static String generateMasterKeyAuthorizationSignature(
      String httpMethod,
      String resourceType,
      String resourceLink,
      String rfc1123Date,
      String base64MasterKey) {
    Objects.requireNonNull(httpMethod, "httpMethod");
    Objects.requireNonNull(resourceType, "resourceType");
    Objects.requireNonNull(resourceLink, "resourceLink");
    Objects.requireNonNull(rfc1123Date, "rfc1123Date");
    Objects.requireNonNull(base64MasterKey, "base64MasterKey");

    // Cosmos expects lowercased verb/resourceType/date in the string-to-sign.
    // resourceLink is NOT lowercased.
    String payload =
        httpMethod.toLowerCase(Locale.ROOT) + "\n" +
            resourceType.toLowerCase(Locale.ROOT) + "\n" +
            resourceLink + "\n" +
            rfc1123Date.toLowerCase(Locale.ROOT) + "\n\n";

    byte[] keyBytes = java.util.Base64.getDecoder().decode(base64MasterKey);
    byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

    byte[] hashPayload;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
      hashPayload = mac.doFinal(payloadBytes);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to compute Cosmos REST auth signature.", e);
    }

    String signature = java.util.Base64.getEncoder().encodeToString(hashPayload);

    // Cosmos requires URL-encoded auth string.
    String authString = "type=master&ver=1.0&sig=" + signature;
    return URLEncoder.encode(authString, StandardCharsets.UTF_8);
  }

  /**
   * Signs a request builder with the Cosmos DB master-key Authorization header and required x-ms-* headers.
   */
  public static void signRequestWithMasterKey(
      HttpRequest.Builder requestBuilder,
      String httpMethod,
      String resourceType,
      String resourceLink,
      String base64MasterKey,
      Instant utcNow,
      String apiVersion) {
    Objects.requireNonNull(requestBuilder, "requestBuilder");
    Objects.requireNonNull(httpMethod, "httpMethod");
    Objects.requireNonNull(resourceType, "resourceType");
    Objects.requireNonNull(resourceLink, "resourceLink");
    Objects.requireNonNull(base64MasterKey, "base64MasterKey");
    Objects.requireNonNull(apiVersion, "apiVersion");

    Instant now = (utcNow != null) ? utcNow : Instant.now();

    // Cosmos expects RFC1123 for x-ms-date; we keep it lowercase to match signature canonicalization.
    String date = RFC_1123.format(now).toLowerCase(Locale.ROOT);

    String auth = generateMasterKeyAuthorizationSignature(
        httpMethod,
        resourceType,
        resourceLink,
        date,
        base64MasterKey);

    requestBuilder.header("authorization", auth);
    requestBuilder.header("x-ms-date", date);
    requestBuilder.header("x-ms-version", apiVersion);

    // Optional but typical for Cosmos REST JSON responses.
    requestBuilder.header("Accept", "application/json");
  }

  public static void signRequestWithMasterKey(
      HttpRequest.Builder requestBuilder,
      String httpMethod,
      String resourceType,
      String resourceLink,
      String base64MasterKey) {
    signRequestWithMasterKey(
        requestBuilder,
        httpMethod,
        resourceType,
        resourceLink,
        base64MasterKey,
        Instant.now(),
        DEFAULT_API_VERSION);
  }
}
