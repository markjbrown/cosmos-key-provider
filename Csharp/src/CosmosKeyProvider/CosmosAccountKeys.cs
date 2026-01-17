namespace CosmosKeyProvider;

/// <summary>
/// Cosmos DB account master keys.
/// </summary>
/// <remarks>
/// Cosmos DB exposes two master keys (primary + secondary) to support key rotation.
/// A common operational pattern is:
/// 1) Use primary for normal traffic.
/// 2) If primary is rotated/regenerated, fall back to secondary (which remains valid).
/// 3) Refresh keys from the control plane so the new primary can be used again.
/// </remarks>
public sealed record CosmosAccountKeys(string PrimaryMasterKey, string SecondaryMasterKey);
