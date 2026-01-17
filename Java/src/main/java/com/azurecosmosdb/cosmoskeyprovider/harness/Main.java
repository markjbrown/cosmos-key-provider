package com.azurecosmosdb.cosmoskeyprovider.harness;

import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azurecosmosdb.cosmoskeyprovider.ArmCosmosAccountKeySource;
import com.azurecosmosdb.cosmoskeyprovider.CosmosAccountKeySource;
import com.azurecosmosdb.cosmoskeyprovider.CosmosAccountKeys;
import com.azurecosmosdb.cosmoskeyprovider.CosmosKeyProvider;
import com.azurecosmosdb.cosmoskeyprovider.CosmosKeyProviderOptions;
import com.azurecosmosdb.cosmoskeyprovider.CosmosRestExecutor;
import com.azurecosmosdb.cosmoskeyprovider.CosmosRestExecutorOptions;
import com.azurecosmosdb.cosmoskeyprovider.CosmosRestRequestSigner;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {
  private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

  private static final Path DEFAULT_CONFIG_PATH =
      Path.of("samples", "CosmosKeyProvider.Harness", "config.json");

  private static final Path TEMPLATE_CONFIG_PATH =
      Path.of("samples", "CosmosKeyProvider.Harness", "config.example.json");

  public static void main(String[] args) {
    try {
      Path configPath = parseConfigPath(args);
      HarnessConfig config = loadConfig(configPath);

      System.out.println("Config: " + configPath.toAbsolutePath());
      System.out.println("Cosmos endpoint: " + config.cosmos.endpoint);

      CosmosAccountKeySource keySource = createKeySource(config);

      CosmosKeyProvider provider = new CosmosKeyProvider(
          keySource,
          new CosmosKeyProviderOptions(Duration.ofMinutes(5)));

      System.out.println("\n1) Cold start single-flight demo...");
      int concurrency = 50;
      List<CompletableFuture<String>> futures = new ArrayList<>(concurrency);
      for (int i = 0; i < concurrency; i++) {
        futures.add(provider.getPrimaryKey());
      }
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

      Set<String> distinct = ConcurrentHashMap.newKeySet();
      for (CompletableFuture<String> f : futures) {
        distinct.add(f.join());
      }
      System.out.println("Distinct keys observed: " + distinct.size() + " (expected: 1)");
      System.out.println(distinct.size() == 1 ? "OK" : "WARNING: unexpected distinct key count");

      System.out.println("\n2) REST request signing demo...");
      String key = provider.getPrimaryKey().join();

      URI endpoint = URI.create(config.cosmos.endpoint);
      URI dbsUri = endpoint.resolve("/dbs");

      HttpRequest.Builder builder = HttpRequest.newBuilder(dbsUri).GET();
      CosmosRestRequestSigner.signRequestWithMasterKey(
          builder,
          "GET",
          "dbs",
          "",
          key);

      HttpRequest signed = builder.build();

      System.out.println("Request: GET " + signed.uri());
      System.out.println("authorization: " + signed.headers().firstValue("authorization").orElse(""));
      System.out.println("x-ms-date: " + signed.headers().firstValue("x-ms-date").orElse(""));
      System.out.println("x-ms-version: " + signed.headers().firstValue("x-ms-version").orElse(""));

      if (config.cosmos.executeDbsGet) {
        System.out.println("\n3) Executing GET /dbs against Cosmos data plane...");

        HttpClient client = HttpClient.newHttpClient();

        CosmosRestExecutorOptions options = new CosmosRestExecutorOptions(
            CosmosRestRequestSigner.DEFAULT_API_VERSION,
            attempt -> Instant.now());

        var response = CosmosRestExecutor.sendAsync(
                client,
                attempt -> CosmosRestExecutor.UnsignedRequest.get(dbsUri),
                provider,
                "dbs",
                "",
                options,
                LOGGER)
            .get(30, TimeUnit.SECONDS);

        System.out.println("HTTP " + response.statusCode());
        String content = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
        String preview = content.length() <= 2000 ? content : content.substring(0, 2000) + "...";
        System.out.println(preview);
        System.out.println("\nSUCCESS: GET /dbs executed.");
      } else {
        System.out.println("\n3) Skipping actual GET /dbs (set Cosmos:ExecuteDbsGet=true to execute).");
      }

      System.out.println("\nSUCCESS: key cache + REST signing completed.");
    } catch (Exception ex) {
      System.err.println("\nFAILED: harness encountered an error.");
      ex.printStackTrace(System.err);
      System.exit(1);
    }
  }

  private static Path parseConfigPath(String[] args) {
    if (args == null || args.length == 0) {
      return DEFAULT_CONFIG_PATH;
    }

    if (args.length == 2 && "--config".equals(args[0])) {
      return Path.of(args[1]);
    }

    throw new IllegalArgumentException("Usage: Main [--config <path-to-config.json>]");
  }

  private static HarnessConfig loadConfig(Path path) throws IOException {
    Objects.requireNonNull(path, "path");

    if (!Files.exists(path)) {
      String message = "Config file not found: " + path.toAbsolutePath()
          + System.lineSeparator()
          + "Create it by copying the template:"
          + System.lineSeparator()
          + "  Copy-Item " + TEMPLATE_CONFIG_PATH + " " + path
          + System.lineSeparator()
          + "Or run with: --config <path-to-config.json>";
      throw new IllegalStateException(message);
    }

    ObjectMapper mapper = new ObjectMapper();
    return mapper.readValue(Files.readString(path), HarnessConfig.class);
  }

  private static CosmosAccountKeySource createKeySource(HarnessConfig config) {
    if (config.cosmos.useDemoKeySource) {
      System.out.println("Using in-process demo key source (forced; no Azure calls).");
      return createDemoKeySource();
    }

    if (config.cosmos.hasArmSettings()) {
      DefaultAzureCredential credential = createCredential(config.cosmos.tenantId);

      System.out.println("Using ARM-backed key source via DefaultAzureCredential.");
      if (config.cosmos.tenantId != null && !config.cosmos.tenantId.isBlank()) {
        System.out.println("Using explicit tenantId: " + config.cosmos.tenantId);
      }

      return new ArmCosmosAccountKeySource(
          credential,
          config.cosmos.tenantId,
          config.cosmos.subscriptionId,
          config.cosmos.resourceGroup,
          config.cosmos.accountName);
    }

    System.out.println("Using in-process demo key source (no Azure calls).");
    System.out.println("Set Cosmos:UseDemoKeySource=false and fill in SubscriptionId/ResourceGroup/AccountName to use real ARM.");
    return createDemoKeySource();
  }

  private static DefaultAzureCredential createCredential(String tenantId) {
    DefaultAzureCredentialBuilder builder = new DefaultAzureCredentialBuilder();
    if (tenantId != null && !tenantId.isBlank()) {
      builder.tenantId(tenantId);
    }
    return builder.build();
  }

  private static CosmosAccountKeySource createDemoKeySource() {
    Queue<CosmosAccountKeys> keys = new ArrayDeque<>();
    keys.add(new CosmosAccountKeys("ZGVtby1rZXktMQ==", "ZGVtby1zZWNvbmRhcnktMQ=="));
    keys.add(new CosmosAccountKeys("ZGVtby1rZXktMg==", "ZGVtby1zZWNvbmRhcnktMg=="));

    return () -> CompletableFuture.supplyAsync(() -> {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      CosmosAccountKeys next = keys.poll();
      if (next == null) {
        next = new CosmosAccountKeys("ZGVtby1rZXktMg==", "ZGVtby1zZWNvbmRhcnktMg==");
      }
      return next;
    });
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static final class HarnessConfig {
    public CosmosSection cosmos;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static final class CosmosSection {
    public String endpoint;
    public String tenantId;
    public String subscriptionId;
    public String resourceGroup;
    public String accountName;
    public boolean useDemoKeySource;
    public boolean executeDbsGet;

    public boolean hasArmSettings() {
      return subscriptionId != null && !subscriptionId.isBlank()
          && resourceGroup != null && !resourceGroup.isBlank()
          && accountName != null && !accountName.isBlank();
    }
  }
}
