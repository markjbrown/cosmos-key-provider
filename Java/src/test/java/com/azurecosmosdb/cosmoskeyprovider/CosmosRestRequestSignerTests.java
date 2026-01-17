package com.azurecosmosdb.cosmoskeyprovider;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CosmosRestRequestSignerTests {
  @Test
  void cosmosRestAuthSignature_matchesDocsExample() {
    // Example from:
    // https://learn.microsoft.com/en-us/rest/api/cosmos-db/access-control-on-cosmosdb-resources#constructkeytoken
    String verb = "GET";
    String resourceType = "dbs";
    String resourceLink = "dbs/ToDoList";
    String date = "Thu, 27 Apr 2017 00:51:12 GMT";
    String key = "dsZQi3KtZmCv1ljt3VNWNm7sQUF1y5rJfC6kv5JiwvW0EndXdDku/dkKBp8/ufDToSxLzR4y+O/0H/t4bQtVNw==";

    String actual = CosmosRestRequestSigner.generateMasterKeyAuthorizationSignature(
        verb,
        resourceType,
        resourceLink,
        date,
        key);

    String expected = "type%3Dmaster%26ver%3D1.0%26sig%3Dc09PEVJrgp2uQRkr934kFbTqhByc7TVr3OHyqlu%2Bc%2Bc%3D";
    assertEquals(expected, actual);
  }

  @Test
  void signRequestWithMasterKey_setsRequiredHeaders_deterministicDate() {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://example.com/dbs"));

    CosmosRestRequestSigner.signRequestWithMasterKey(
        builder,
        "GET",
        "dbs",
        "",
        "AA==",
        Instant.parse("2026-01-01T00:00:00Z"),
        CosmosRestRequestSigner.DEFAULT_API_VERSION);

    HttpRequest request = builder.GET().build();

    assertTrue(request.headers().firstValue("authorization").isPresent());
    assertEquals("thu, 01 jan 2026 00:00:00 gmt", request.headers().firstValue("x-ms-date").orElseThrow());
    assertEquals(CosmosRestRequestSigner.DEFAULT_API_VERSION, request.headers().firstValue("x-ms-version").orElseThrow());
    assertEquals("application/json", request.headers().firstValue("Accept").orElseThrow());
  }
}
