package dev.thiagosindra.cloudlug.provider.dropbox

/*
 * Dropbox adapter — v0.3 (spec §33).
 *
 * Nothing is implemented here yet, on purpose: v0.1 builds the engine against
 * FakeCloudProvider so that provider neutrality (spec §32.7) is established
 * before any real API is involved.
 *
 * TODO(v0.3): implement CloudProvider over OkHttp, with
 *  - OAuth 2 authorization code + PKCE, `token_access_type=offline`, scopes
 *    files.metadata.read / files.content.read / files.content.write /
 *    account_info.read, and no client secret in the APK (spec §8.1).
 *  - capabilities: nativeHashAlgorithm = DROPBOX_CONTENT_HASH, 4 MiB
 *    content_hash blocks, case-insensitive paths, no duplicate siblings
 *    (spec §19.4, §20.3).
 *  - upload sessions with ~7 day expiry persisted per spec §22.5.
 *  - error mapping onto CloudErrorKind: 429 and 5xx to THROTTLED /
 *    TRANSIENT_NETWORK, insufficient_space to DESTINATION_STORAGE_FULL,
 *    invalid_grant to AUTH_REQUIRED (spec §23).
 *  - the module's test source set should subclass ProviderContractTest
 *    (spec §31.2) against a sandbox account.
 *  - FIRST TASK, before any of the above ships: upload a known file and compare
 *    the provider's reported content_hash against the local implementation.
 *    The v0.1 vectors were derived independently from the algorithm definition,
 *    which catches an implementation bug but not a misreading both share
 *    (spec §33, §36).
 */
