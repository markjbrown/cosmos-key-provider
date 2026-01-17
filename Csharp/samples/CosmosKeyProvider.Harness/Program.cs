using System.Collections.Concurrent;
using Azure;
using Azure.Identity;
using CosmosKeyProvider;
using Microsoft.Extensions.Configuration;

// This console harness is meant to be a readable “how to use it” sample.
//
// It demonstrates the full intended pattern for Cosmos DB REST callers:
//
//   A) Get the current master key (control plane)
//      - from ARM via Managed Identity (recommended in production), or
//      - from a demo key source (no Azure calls; useful for local testing)
//
//   B) Cache the key in-process using CosmosKeyProvider
//      - hot path: zero ARM calls
//      - cold path: single-flight refresh to avoid a thundering herd
//
//   C) Use CosmosRestExecutor to call Cosmos data plane (REST)
//      - builds an HttpRequestMessage (via a factory)
//      - signs it with the cached key
//      - sends it via HttpClient
//      - on 401/403, fails over to the cached secondary key
//      - refreshes keys so future requests use the new primary
//      - if secondary also fails with 401/403, forces a refresh and retries primary once

var config = BuildConfiguration();
var settings = HarnessSettings.Load(config);

Console.WriteLine("Config: appsettings.json (+ optional appsettings.Development.json)");
Console.WriteLine($"Cosmos endpoint: {settings.Endpoint}");

var keySource = CreateKeySource(settings);

// CosmosKeyProvider is the in-memory cache.
// You typically create ONE instance per process (singleton) and share it.
var provider = new global::CosmosKeyProvider.CosmosKeyProvider(
	keySource,
	options: new CosmosKeyProviderOptions { MinRefreshInterval = TimeSpan.FromMinutes(5) });

try
{
	Console.WriteLine("\n1) Cold start single-flight demo...");
	var keysObserved = await Task.WhenAll(Enumerable.Range(0, 50).Select(async _ => await provider.GetKeyAsync()));
	var distinct = keysObserved.Distinct().Count();
	Console.WriteLine($"Distinct keys observed: {distinct} (expected: 1)");
	Console.WriteLine(distinct == 1 ? "OK" : "WARNING: unexpected distinct key count");

	Console.WriteLine("\n2) REST request signing demo...");
	var key = await provider.GetKeyAsync();

	// Example: List Databases (GET /dbs) => resourceType = "dbs", resourceLink = "".
	var request = new HttpRequestMessage(HttpMethod.Get, new Uri(new Uri(settings.Endpoint), "/dbs"));
	CosmosRestRequestSigner.SignRequestWithMasterKey(
		request,
		resourceType: "dbs",
		resourceLink: "",
		base64MasterKey: key);

	Console.WriteLine($"Request: {request.Method} {request.RequestUri}");
	Console.WriteLine($"authorization: {request.Headers.GetValues("authorization").First()}");
	Console.WriteLine($"x-ms-date: {request.Headers.GetValues("x-ms-date").First()}");
	Console.WriteLine($"x-ms-version: {request.Headers.GetValues("x-ms-version").First()}");

	if (settings.ExecuteDbsGet)
	{
		Console.WriteLine("\n3) Executing GET /dbs against Cosmos data plane...");

		// Use a single HttpClient instance (reuse is recommended).
		using var httpClient = new HttpClient();

		// CosmosRestExecutor expects a *request factory* so that it can retry with a fresh request
		// if it sees 401/403 (key rotation).
		//	- attempt 0: sign+send with cached primary
		//	- attempt 1 (if needed): sign+send with cached secondary
		//	- then refresh keys so future requests use the new primary
		//	- attempt 2 (only if secondary also fails): force refresh keys, retry primary once
		static HttpRequestMessage CreateRequest(Uri endpointUri, CancellationToken _)
		{
			var req = new HttpRequestMessage(HttpMethod.Get, new Uri(endpointUri, "/dbs"));
			req.Headers.Accept.ParseAdd("application/json");
			return req;
		}

		var endpointUri = new Uri(settings.Endpoint);
		using var response = await CosmosRestExecutor.SendAsync(
			httpClient,
			ct => CreateRequest(endpointUri, ct),
			provider,
			resourceType: "dbs",
			resourceLink: "",
			options: null,
			logger: null,
			cancellationToken: default);

		Console.WriteLine($"HTTP {(int)response.StatusCode} {response.ReasonPhrase}");
		var content = await response.Content.ReadAsStringAsync();
		var preview = content.Length <= 2000 ? content : content[..2000] + "...";
		Console.WriteLine(preview);
		Console.WriteLine("\nSUCCESS: GET /dbs executed.");
	}
	else
	{
		Console.WriteLine("\n3) Skipping actual GET /dbs (set Cosmos:ExecuteDbsGet=true to execute)." );
	}

	Console.WriteLine("\nSUCCESS: key cache + REST signing completed.");
}
catch (RequestFailedException ex) when (ex.Status == 401 && ex.ErrorCode == "InvalidAuthenticationTokenTenant")
{
	Console.Error.WriteLine("\nFAILED: ARM key retrieval was attempted, but the token tenant does not match the subscription tenant.");
	Console.Error.WriteLine(ex.Message);
	Console.Error.WriteLine("\nFix options:");
	Console.Error.WriteLine("- Set Cosmos:TenantId in appsettings to the subscription's tenant GUID, then rerun.");
	Console.Error.WriteLine("- If using Azure CLI auth: run `az login --tenant <tenantId>` then rerun.");
	Console.Error.WriteLine("- If you just want to test cache/signing: set Cosmos:UseDemoKeySource=true in appsettings.");

	Environment.ExitCode = 1;
}
catch (Exception ex)
{
	Console.Error.WriteLine("\nFAILED: harness encountered an error.");
	Console.Error.WriteLine(ex);
	Environment.ExitCode = 1;
}

static IConfigurationRoot BuildConfiguration() =>
	new ConfigurationBuilder()
		.SetBasePath(AppContext.BaseDirectory)
		.AddJsonFile("appsettings.json", optional: true, reloadOnChange: false)
		// Optional local override file. This repo's .gitignore is set up to keep this out of source control.
		.AddJsonFile("appsettings.Development.json", optional: true, reloadOnChange: false)
		.Build();

static ICosmosAccountKeySource CreateKeySource(HarnessSettings settings)
{
	if (settings.UseDemoKeySource)
	{
		Console.WriteLine("Using in-process demo key source (forced; no Azure calls)." );
		return CreateDemoKeySource();
	}

	if (settings.HasArmSettings)
	{
		// Real ARM-backed key retrieval (Managed Identity / DefaultAzureCredential).
		//
		// IMPORTANT: Your identity needs RBAC permission to list keys:
		//   Microsoft.DocumentDB/databaseAccounts/listKeys/action
		//
		// If you see InvalidAuthenticationTokenTenant, set Cosmos:TenantId
		// to the tenant GUID that owns the subscription.
		var credential = string.IsNullOrWhiteSpace(settings.TenantId)
			? new DefaultAzureCredential()
			: new DefaultAzureCredential(new DefaultAzureCredentialOptions { TenantId = settings.TenantId });

		Console.WriteLine("Using ARM-backed key source via DefaultAzureCredential.");
		if (!string.IsNullOrWhiteSpace(settings.TenantId))
		{
			Console.WriteLine($"Using explicit tenantId: {settings.TenantId}");
		}

		return new ArmCosmosAccountKeySource(
			settings.SubscriptionId!,
			settings.ResourceGroup!,
			settings.AccountName!,
			credential);
	}

	Console.WriteLine("Using in-process demo key source (no Azure calls)." );
	Console.WriteLine("Set Cosmos:UseDemoKeySource=false and fill in SubscriptionId/ResourceGroup/AccountName to use real ARM." );
	return CreateDemoKeySource();
}

static ICosmosAccountKeySource CreateDemoKeySource()
{
	// Local demo: fake key source that returns a stable key until forced refresh.
	// Cosmos account keys are base64-encoded; keep the demo realistic.
	var keys = new ConcurrentQueue<CosmosAccountKeys>(
		new[]
		{
			new CosmosAccountKeys(
				PrimaryMasterKey: "ZGVtby1rZXktMQ==",
				SecondaryMasterKey: "ZGVtby1zZWNvbmRhcnktMQ=="),
			new CosmosAccountKeys(
				PrimaryMasterKey: "ZGVtby1rZXktMg==",
				SecondaryMasterKey: "ZGVtby1zZWNvbmRhcnktMg=="),
		});

	return new DelegateKeySource(ct =>
	{
		// Simulate a control-plane call taking time.
		return Task.Delay(100, ct).ContinueWith(_ =>
		{
			if (!keys.TryDequeue(out var keyPair))
			{
				keyPair = new CosmosAccountKeys(
					PrimaryMasterKey: "ZGVtby1rZXktMg==",
					SecondaryMasterKey: "ZGVtby1zZWNvbmRhcnktMg==");
			}

			return keyPair;
		}, ct);
	});
}

internal sealed record HarnessSettings(
	string Endpoint,
	string? TenantId,
	string? SubscriptionId,
	string? ResourceGroup,
	string? AccountName,
	bool UseDemoKeySource,
	bool ExecuteDbsGet)
{
	public bool HasArmSettings =>
		!string.IsNullOrWhiteSpace(SubscriptionId)
		&& !string.IsNullOrWhiteSpace(ResourceGroup)
		&& !string.IsNullOrWhiteSpace(AccountName);

	public static HarnessSettings Load(IConfiguration config)
	{
		var endpoint = config["Cosmos:Endpoint"] ?? "https://localhost:8081";
		var tenantId = config["Cosmos:TenantId"];
		var subscriptionId = config["Cosmos:SubscriptionId"];
		var resourceGroup = config["Cosmos:ResourceGroup"];
		var accountName = config["Cosmos:AccountName"];

		var useDemo = bool.TryParse(config["Cosmos:UseDemoKeySource"], out var parsedUseDemo) && parsedUseDemo;
		var executeGet = bool.TryParse(config["Cosmos:ExecuteDbsGet"], out var parsedExecuteGet) && parsedExecuteGet;

		return new HarnessSettings(
			Endpoint: endpoint,
			TenantId: tenantId,
			SubscriptionId: subscriptionId,
			ResourceGroup: resourceGroup,
			AccountName: accountName,
			UseDemoKeySource: useDemo,
			ExecuteDbsGet: executeGet);
	}
}

internal sealed class DelegateKeySource : ICosmosAccountKeySource
{
	private readonly Func<CancellationToken, Task<CosmosAccountKeys>> _getKeys;

	public DelegateKeySource(Func<CancellationToken, Task<CosmosAccountKeys>> getKeys) => _getKeys = getKeys;

	public Task<CosmosAccountKeys> GetKeysAsync(CancellationToken cancellationToken = default) => _getKeys(cancellationToken);
}
