# Cosmos DB Master-Key Cache Provider (Java)

In-process Cosmos DB master-key caching + Cosmos DB REST request signing (and optional execution) helpers.

This is for **Cosmos DB REST callers** (not the Cosmos Java data-plane SDK). It reduces Azure Resource Manager (ARM) traffic by caching Cosmos DB account master keys and refreshing them with **single-flight** behavior.

## Prereqs

- Java 21+
- Maven
- (If using ARM key retrieval) an Azure identity with RBAC permission:
  - `Microsoft.DocumentDB/databaseAccounts/listKeys/action`

## Quick start

1) Configure the harness

Copy the template and edit your local config:

- `samples/CosmosKeyProvider.Harness/config.example.json` (checked in)
- `samples/CosmosKeyProvider.Harness/config.json` (your local copy; gitignored)

Note: keep `config.json` local-only (it can include subscription/tenant/account details). Use the checked-in template (or `config.demo.json`) for shareable examples.

```json
{
  "Cosmos": {
    "Endpoint": "https://<account>.documents.azure.com:443/",
    "TenantId": "",
    "SubscriptionId": "",
    "ResourceGroup": "",
    "AccountName": "",
    "UseDemoKeySource": false,
    "ExecuteDbsGet": false
  }
}
```

Create your local file by copying the template (example):

- PowerShell: `Copy-Item samples/CosmosKeyProvider.Harness/config.example.json samples/CosmosKeyProvider.Harness/config.json`
- Bash: `cp samples/CosmosKeyProvider.Harness/config.example.json samples/CosmosKeyProvider.Harness/config.json`

- `UseDemoKeySource=true` uses an in-process demo key source (no Azure calls).
- To use real ARM key retrieval, set `UseDemoKeySource=false` and provide `SubscriptionId`, `ResourceGroup`, `AccountName`.
- Set `ExecuteDbsGet=true` to run a real `GET /dbs` request against the Cosmos data plane.

1) Run tests

```bash
mvn -q test
```

1) Run the harness

```bash
mvn -q -DskipTests exec:java -Dexec.mainClass=com.azurecosmosdb.cosmoskeyprovider.harness.Main
```

Optional config override:

```bash
mvn -q -DskipTests exec:java -Dexec.mainClass=com.azurecosmosdb.cosmoskeyprovider.harness.Main -Dexec.args="--config /path/to/config.json"
```

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

## Debug in VS Code

This repo includes a ready-to-run VS Code debug configuration for the harness.

### Prereqs

- VS Code extension: **Extension Pack for Java** (or equivalent Java debugging support)
- Java 21+

### Steps

1) Open the Java folder as your workspace:

- Open folder: `cosmos-key-provider/Java`

Alternatively, open the optional workspace file (mainly for convenience settings). Debug configs still come from `.vscode/launch.json`:

- Open workspace: `cosmos-key-provider/Java/cosmos-key-provider-java.code-workspace`

2) Pick a launch configuration:

- **CosmosKeyProvider Harness (Demo, no Azure calls)**
  - Uses `samples/CosmosKeyProvider.Harness/config.demo.json`
  - Always uses the in-process demo key source; does not call Azure.
- **CosmosKeyProvider Harness (ARM + ExecuteDbsGet)**
  - Uses `samples/CosmosKeyProvider.Harness/config.json`
  - Calls ARM `listKeys`, then (optionally) executes `GET /dbs` against the Cosmos data plane.

3) Start debugging:

- Run and Debug → select one of the above configs → Start Debugging

### Azure auth notes (ARM mode)

- The harness uses `DefaultAzureCredential`. For local dev it commonly authenticates via Azure CLI.
- Ensure you are logged in (and to the right tenant if needed): `az login --tenant <tenantId>`
- The identity needs RBAC permission: `Microsoft.DocumentDB/databaseAccounts/listKeys/action`

### Logging

- Runtime logging is intentionally kept quiet (Azure/Netty set to WARN) via `src/main/resources/logback.xml`.
