# Cosmos DB Master-Key Cache Provider

In-process Cosmos DB master-key caching + Cosmos DB REST request signing (and optional execution) helpers.

This repo includes two fully working implementations:

- C#: [Csharp/README.md](Csharp/README.md)
- Java: [Java/README.md](Java/README.md)

This is for **Cosmos DB REST callers** (not the Cosmos data-plane SDKs). It reduces Azure Resource Manager (ARM) traffic by caching Cosmos DB account master keys and refreshing them with **single-flight** behavior.

## Pick a language

- If you want .NET: start at [Csharp/README.md](Csharp/README.md)
- If you want Java: start at [Java/README.md](Java/README.md)

## VS Code workspaces (run/debug)

This repo has language-scoped VS Code workspace files so each sample opens cleanly with the right tooling.

- Open the C# workspace: [cosmos-key-provider-csharp.code-workspace](cosmos-key-provider-csharp.code-workspace)
- Open the Java workspace: [cosmos-key-provider-java.code-workspace](cosmos-key-provider-java.code-workspace)

You can open them via VS Code UI (`File` → `Open Workspace from File...`) or from a terminal:

```bash
code cosmos-key-provider-csharp.code-workspace
code cosmos-key-provider-java.code-workspace
```

### Run

- C# harness: follow [Csharp/README.md](Csharp/README.md) (`dotnet run --project samples/CosmosKeyProvider.Harness`)
- Java harness: follow [Java/README.md](Java/README.md) (`mvn -q -DskipTests exec:java -Dexec.mainClass=com.azurecosmosdb.cosmoskeyprovider.harness.Main`)

### Debug

- C#: open the harness project in `Csharp/samples/CosmosKeyProvider.Harness` and use `Run` → `Start Debugging` (VS Code will prompt to create a `launch.json` if needed).
- Java: open [Java/src/main/java/com/azurecosmosdb/cosmoskeyprovider/harness/Main.java](Java/src/main/java/com/azurecosmosdb/cosmoskeyprovider/harness/Main.java) and use the inline `Run`/`Debug` CodeLens above `main` (provided by the Java extensions).

## How it works

### Components

- `ArmCosmosAccountKeySource`
  - Calls ARM `listKeys` to fetch **primary + secondary** Cosmos master keys.
- `CosmosKeyProvider`
  - In-memory cache for keys.
  - Single-flight refresh so only one caller hits ARM during cold start / refresh storms.
  - `minRefreshInterval` throttles non-forced refresh attempts.
- `CosmosRestRequestSigner`
  - Computes the Cosmos REST `authorization` header (HMAC-SHA256) and sets required headers.
- `CosmosRestExecutor`
  - Optional helper that signs + sends requests and handles key rotation behavior.

### Call flow (401/403 key rotation handling)

The executor follows the same logic as the C# implementation:

1) Attempt 0: sign + send using cached **primary** key.
2) If status is not 401/403: return response.
3) Attempt 1 (only on 401/403): sign + send using cached **secondary** key.
   - If secondary succeeds: force-refresh keys (single-flight) so future requests can use the new primary.
4) If secondary also returns 401/403: force-refresh keys and retry **primary** once (attempt 2).

This minimizes both:

- Data-plane retries (only one fallback path)
- Control-plane calls (refresh is single-flight and normally rate-limited)

## Notes / troubleshooting

- If ARM key retrieval fails due to tenant mismatch, set `TenantId` to the tenant that owns the subscription.
  - Local dev via Azure CLI often needs `az login --tenant <tenantId>`.
- If you only want to validate caching/signing logic without Azure access, set `UseDemoKeySource=true`.
