package com.azurecosmosdb.cosmoskeyprovider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

public class CosmosRestExecutorTests {
  @Test
  void primary401_secondary200_forcesRefresh_andReturnsSecondaryResponse() throws Exception {
    String primary1 = "AA==";
    String secondary1 = "AQ==";
    String primary2 = "Ag==";
    String secondary2 = "Aw==";

    AtomicInteger keySourceCalls = new AtomicInteger();
    Queue<CosmosAccountKeys> keyBatches = new ArrayDeque<>();
    keyBatches.add(new CosmosAccountKeys(primary1, secondary1));
    keyBatches.add(new CosmosAccountKeys(primary2, secondary2));

    CosmosAccountKeySource keySource = () -> {
      keySourceCalls.incrementAndGet();
      return CompletableFuture.completedFuture(keyBatches.remove());
    };

    CosmosKeyProvider keyProvider = new CosmosKeyProvider(
        keySource,
        new CosmosKeyProviderOptions(java.time.Duration.ofMinutes(5)),
        java.time.Clock.systemUTC(),
        LoggerFactory.getLogger(CosmosKeyProvider.class));

    try (TestServer server = new TestServer(new PrimarySecondaryHandler(
        "dbs",
        "",
        primary1,
        secondary1,
        /*acceptPrimary*/ false,
        /*acceptSecondary*/ true,
        /*acceptNewPrimary*/ false,
        primary2))) {

      HttpClient client = HttpClient.newHttpClient();
      URI uri = server.baseUri().resolve("/dbs");

      CosmosRestExecutorOptions options = new CosmosRestExecutorOptions(
          CosmosRestRequestSigner.DEFAULT_API_VERSION,
          attempt -> Instant.parse("2026-01-01T00:00:0" + attempt + "Z"));

      CompletableFuture<java.net.http.HttpResponse<byte[]>> responseFuture = CosmosRestExecutor.sendAsync(
          client,
          attempt -> CosmosRestExecutor.UnsignedRequest.get(uri),
          keyProvider,
          "dbs",
          "",
          options,
          LoggerFactory.getLogger(CosmosRestExecutor.class));

      var response = responseFuture.get(5, TimeUnit.SECONDS);

      assertEquals(200, response.statusCode());
      assertEquals(2, server.handler.requestCount.get(), "expected 2 requests (primary then secondary)");
      assertEquals(2, keySourceCalls.get(), "expected forced refresh after secondary success");
    }
  }

  @Test
  void primary401_secondary401_refresh_thenPrimary200() throws Exception {
    String primary1 = "AA==";
    String secondary1 = "AQ==";
    String primary2 = "Ag==";
    String secondary2 = "Aw==";

    AtomicInteger keySourceCalls = new AtomicInteger();
    Queue<CosmosAccountKeys> keyBatches = new ArrayDeque<>();
    keyBatches.add(new CosmosAccountKeys(primary1, secondary1));
    keyBatches.add(new CosmosAccountKeys(primary2, secondary2));

    CosmosAccountKeySource keySource = () -> {
      keySourceCalls.incrementAndGet();
      return CompletableFuture.completedFuture(keyBatches.remove());
    };

    CosmosKeyProvider keyProvider = new CosmosKeyProvider(
        keySource,
        new CosmosKeyProviderOptions(java.time.Duration.ofMinutes(5)),
        java.time.Clock.systemUTC(),
        LoggerFactory.getLogger(CosmosKeyProvider.class));

    try (TestServer server = new TestServer(new PrimarySecondaryHandler(
        "dbs",
        "",
        primary1,
        secondary1,
        /*acceptPrimary*/ false,
        /*acceptSecondary*/ false,
        /*acceptNewPrimary*/ true,
        primary2))) {

      HttpClient client = HttpClient.newHttpClient();
      URI uri = server.baseUri().resolve("/dbs");

      CosmosRestExecutorOptions options = new CosmosRestExecutorOptions(
          CosmosRestRequestSigner.DEFAULT_API_VERSION,
          attempt -> Instant.parse("2026-01-01T00:00:0" + attempt + "Z"));

      var response = CosmosRestExecutor.sendAsync(
              client,
              attempt -> CosmosRestExecutor.UnsignedRequest.get(uri),
              keyProvider,
              "dbs",
              "",
              options,
              LoggerFactory.getLogger(CosmosRestExecutor.class))
          .get(5, TimeUnit.SECONDS);

      assertEquals(200, response.statusCode());
      assertEquals(3, server.handler.requestCount.get(), "expected 3 requests (primary, secondary, refreshed primary)");
      assertEquals(2, keySourceCalls.get(), "expected single forced refresh before final retry");
    }
  }

  private static final class TestServer implements AutoCloseable {
    private final HttpServer server;
    private final PrimarySecondaryHandler handler;

    TestServer(PrimarySecondaryHandler handler) throws IOException {
      this.handler = Objects.requireNonNull(handler, "handler");
      this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      this.server.createContext("/", handler);
      this.server.start();
    }

    URI baseUri() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  private static final class PrimarySecondaryHandler implements HttpHandler {
    private final String resourceType;
    private final String resourceLink;
    private final String oldPrimaryKey;
    private final String oldSecondaryKey;
    private final boolean acceptOldPrimary;
    private final boolean acceptOldSecondary;
    private final boolean acceptNewPrimary;
    private final String newPrimaryKey;

    private final AtomicInteger requestCount = new AtomicInteger();

    private PrimarySecondaryHandler(
        String resourceType,
        String resourceLink,
        String oldPrimaryKey,
        String oldSecondaryKey,
        boolean acceptPrimary,
        boolean acceptSecondary,
        boolean acceptNewPrimary,
        String newPrimaryKey) {
      this.resourceType = resourceType;
      this.resourceLink = resourceLink;
      this.oldPrimaryKey = oldPrimaryKey;
      this.oldSecondaryKey = oldSecondaryKey;
      this.acceptOldPrimary = acceptPrimary;
      this.acceptOldSecondary = acceptSecondary;
      this.acceptNewPrimary = acceptNewPrimary;
      this.newPrimaryKey = newPrimaryKey;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      requestCount.incrementAndGet();

      String auth = exchange.getRequestHeaders().getFirst("authorization");
      String date = exchange.getRequestHeaders().getFirst("x-ms-date");

      if (auth == null || date == null) {
        write(exchange, 400, "missing auth/date");
        return;
      }

      String method = exchange.getRequestMethod();

      String expectedOldPrimary = CosmosRestRequestSigner.generateMasterKeyAuthorizationSignature(
          method, resourceType, resourceLink, date, oldPrimaryKey);
      String expectedOldSecondary = CosmosRestRequestSigner.generateMasterKeyAuthorizationSignature(
          method, resourceType, resourceLink, date, oldSecondaryKey);
      String expectedNewPrimary = CosmosRestRequestSigner.generateMasterKeyAuthorizationSignature(
          method, resourceType, resourceLink, date, newPrimaryKey);

      if (auth.equals(expectedOldPrimary)) {
        write(exchange, acceptOldPrimary ? 200 : 401, "old-primary");
        return;
      }

      if (auth.equals(expectedOldSecondary)) {
        write(exchange, acceptOldSecondary ? 200 : 401, "old-secondary");
        return;
      }

      if (auth.equals(expectedNewPrimary)) {
        write(exchange, acceptNewPrimary ? 200 : 401, "new-primary");
        return;
      }

      write(exchange, 400, "unexpected auth");
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
      byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "text/plain");
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    }
  }
}
