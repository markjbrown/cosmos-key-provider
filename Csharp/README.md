# Cosmos DB master-key cache provider (C#)

This folder contains a small library + tests + console harness that implement a master-key caching/refresh pattern for **Cosmos DB NoSQL REST (data plane)** callers.

The goal is to eliminate control-plane (ARM) “thundering herd” when retrieving account keys, while still handling key rotation safely.

## Quick start

1) Configure the harness by editing:

- `samples/CosmosKeyProvider.Harness/appsettings.json`

Set:

- `Cosmos:Endpoint` (e.g. `https://<account>.documents.azure.com:443/`)
- `Cosmos:TenantId` (optional; use when your subscription is in a different tenant than your default login)
- `Cosmos:SubscriptionId`
- `Cosmos:ResourceGroup`
- `Cosmos:AccountName`
- `Cosmos:ExecuteDbsGet` (`true` to actually send `GET /dbs`)

2) Run the harness:

```powershell
cd Csharp
dotnet run --project samples/CosmosKeyProvider.Harness -c Release
```

Success criteria:

- You should see `SUCCESS: GET /dbs executed.` (if `Cosmos:ExecuteDbsGet=true`)
- You should see `SUCCESS: key cache + REST signing completed.`

## Call flow (what gets called, and how it branches)

### 1) Getting keys (control plane)

- `ArmCosmosAccountKeySource.GetKeysAsync()` calls ARM `listKeys` and returns both master keys:
  - primary master key
  - secondary master key

### 2) Caching keys (in-process)

- `CosmosKeyProvider.GetKeyAsync()` returns the cached primary key (hot path).
- On cold start, `GetKeyAsync()` does a single-flight refresh and caches BOTH keys.
- `CosmosKeyProvider.GetSecondaryKeyAsync()` returns the cached secondary key (also refreshes on cold start).
- `CosmosKeyProvider.RefreshKeyAsync(force: true)` bypasses `MinRefreshInterval` to handle rotation quickly.

### 3) Sending REST calls (data plane)

`CosmosRestExecutor.SendAsync(...)` performs this sequence:

1. Attempt 0: sign+send with the cached primary key.
2. If the response is NOT `401` or `403`: return the response.
3. Attempt 1: sign+send with the cached secondary key.
4. If the response is NOT `401` or `403`:
   - refresh keys (force) so future requests use the new primary
   - return the response
5. If the secondary response is also `401`/`403`:
   - refresh keys (force)
   - Attempt 2: sign+send with the refreshed primary key (retry once)

This gives you a fast failover path during primary key rotation while still converging back to the new primary key.

## What’s included

- In-memory cache + refresh gate: `CosmosKeyProvider`
- ARM key source: `ArmCosmosAccountKeySource`
- REST signing helper: `CosmosRestRequestSigner`
- Optional REST executor helper: `CosmosRestExecutor`

## Tests

```powershell
cd Csharp
dotnet test CosmosKeyProvider.sln -c Release
```

