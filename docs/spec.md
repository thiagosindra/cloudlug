# CloudLug — Technical Design Specification

**Local-Only Cloud-to-Cloud File Transfer for Android**
*Technical Design Specification v1.2.1 • Open Source • Google Play*

CloudLug transfers files between supported cloud-storage providers using the user's Android device as the only intermediary. The application operates no file-transfer backend and is designed from the start around provider-neutral adapters, durable recovery, bounded local caching, conservative destination behavior, and future expansion to additional cloud services.

---

## Changes in v1.2.1

- **§4 gains `core/hashing`.** Ratifies ADR 0001 from the v0.1 implementation: the §19.4 pipeline has behaviour (so not `core/model`), is not about disk (so not `core/storage`), and one of its algorithms is specified only by Dropbox, so it cannot live in `core/transfer`, which must name no provider. Isolating it lets the engine select an algorithm through `ProviderCapabilities.nativeHashAlgorithm`.
- ADRs 0010 (one waiting state covers both offline and metered) and 0017 (sequential pipeline until measured, per §18) are ratified without text changes.

## Changes in v1.2

Amendments from the v0.1 implementation report (`docs/decisions.md`, 18 ADRs). Every reading the implementer took is now either ratified in the text or overruled here.

- **§15 ordering fixed.** Emergency reserve is defined before the cache budget; reserve first, then halve.
- **§13.2 item state defined as "furthest stage reached"**, with §15.3 chunk states carrying the download/upload overlap that §14 requires.
- **§19.3 now begins with the §19.2 idempotency check.** This ordering is load-bearing: without it, a Dropbox → Drive re-run reports conflicts against CloudLug's own uploads.
- **`SOURCE_CHANGED` and `SKIPPED_UNSUPPORTED` get their own counters, and a transfer with any of them ends `COMPLETED_WITH_ISSUES`** (renamed from `COMPLETED_WITH_ERRORS`). Only `COMPLETED` and `SKIPPED_DUPLICATE` items count as success (§13.1). Overrules the implementation's reading.
- **§21 step 3 deleted.** A provider that claims a server hash and returns none for an object leaves the item unverifiable, and unverifiable items do not complete.
- **Checkpointable hashers (§19.4).** Hash state is persisted with each chunk acknowledgment, so verification never degrades after process death. Replaces the "unverifiable after resume" outcome.
- **Enclosing folder is created at `READY → RUNNING`, not during `PREPARING` (§10).**
- **Size-unknown items (§11, §20.1).** `totalBytes` counts known sizes; the progress denominator falls back to file count.
- **§5:** `enumerate` is no longer `suspend`; the last emitted object's ID is the enumeration resume token (§11); `ProviderCapabilities` gains `disallowsTrailingSpaceOrDot` and `maxPathLength`.
- **§9:** a selection carries the destination-relative display path of each root, since only the picker knows it.
- **§10:** two transfers created in the same minute get a numeric suffix on the enclosing folder.
- **§13.1:** `PREPARING` can enter `WAITING_FOR_WIFI` and `AUTH_REQUIRED`; enumeration needs network and a valid token too.
- **§15.3:** chunk chain gains failure edges `DOWNLOADING → ALLOCATED` and `UPLOADING → READY`.
- **§31.3:** process interruption is separated from network failures; it must not be routed through the retry policy.
- **§33 roadmap resequenced:** Room and the Compose shell (against the fake provider) land before the Dropbox adapter.
- **§36:** provider-reported hash validation is the first task of adapter work, not the last.

## Changes in v1.1

- **Google Drive scope strategy rewritten (§8.2).** `drive.readonly` is a Google *restricted* scope that requires OAuth verification plus an annual third-party CASA security assessment. The Google Picker API is web-only, so the "provider-native picker + `drive.file`" strategy does not work on Android for reading arbitrary source folders. The roadmap now ships **Dropbox → Google Drive** first (needs only the non-sensitive `drive.file` scope) and treats **Google Drive → Dropbox** as a separately gated milestone.
- **New §20 Provider-Specific Semantics.** Google-native documents (Docs/Sheets/Slides have no bytes), same-name siblings in Drive folders, shortcuts, case sensitivity, name-legality differences, empty folders, source changed during transfer, and destination quota preflight were unaddressed.
- **Verification is now near-free (§19.4, §21).** The hash pipeline computes the *destination provider's native hash* locally while streaming (Dropbox `content_hash`, Drive MD5/SHA-256), so verification is a metadata comparison against the upload-finish response, not a re-download.
- **File state machine gained CONFLICT, SKIPPED_UNSUPPORTED, SOURCE_CHANGED (§13.2).** §19.3 produced a CONFLICT outcome that had no state.
- **Retry policy covers Drive's 403 rate-limit reasons and Google refresh-token expiry while the OAuth app is in "Testing" status (§23).**
- **Background execution (§17) now explains the Android 14 `dataSync` foreground-service 6-hour limit and sets a minimum SDK.**
- **OAuth flow requires Custom Tabs via AppAuth (§8.4).** Google blocks embedded WebViews.
- **Backup exclusion made concrete (§15.2).**
- Formatting fixed throughout: fenced code blocks, intact tree diagrams, restarted list numbering, unescaped characters.

---

## 1. Product Definition

CloudLug is an Android application for transferring files and directory trees between cloud-storage providers using the user's Android device as the only intermediary. File contents, cloud credentials, transfer manifests, file metadata, hashes, and transfer history remain on the user's device except when data is transmitted directly to the selected cloud providers.

```
Cloud Provider A --HTTPS--> CloudLug on Android --HTTPS--> Cloud Provider B
                                   |
                             bounded cache
                                   |
                             Room database
```

CloudLug deliberately has no CloudLug account, transfer proxy, cloud worker, synchronization server, remote transfer database, advertising backend, or file-inspection service. The application should remain fully useful when built directly from its public source code.

## 2. Product Principles

### 2.1 Local-only
Cloud-provider traffic flows directly through the Android device. No CloudLug-operated remote system receives file contents or cloud credentials.

### 2.2 Provider-neutral
Any supported provider can be a source or a destination, subject to that provider's authorization constraints (see §8.2). Same-provider transfers are prohibited by the transfer-domain layer, not by individual adapters.

```
Allowed:   Dropbox -> Google Drive        Google Drive -> Dropbox
Rejected:  Dropbox -> Dropbox             Google Drive -> Google Drive
```

Future providers can include OneDrive, Box, WebDAV/Nextcloud, S3-compatible storage, Backblaze B2, pCloud, Proton Drive, and others without redesigning the transfer engine.

### 2.3 Copy, never synchronize
- No bidirectional synchronization.
- No source deletion or mutation.
- No destination move or rename operations.
- No automatic overwrite of existing destination objects.
- No cloud file editor or general-purpose file manager.

### 2.4 Recoverable
Transfers must tolerate application crash, process death, device reboot, Wi-Fi loss, API timeout, provider throttling, token expiration, storage pressure, pause, cancellation, and retry. The local database is authoritative; in-memory workers are disposable executors.

### 2.5 Conservative
When CloudLug cannot establish that an operation is safe, it stops, skips, or surfaces a conflict rather than overwriting data.

## 3. Technology Stack

| Area | Recommended technology |
|---|---|
| Language | Kotlin |
| Minimum SDK | API 26 (Android 8.0); UIDT path on API 34+ |
| UI | Jetpack Compose |
| Architecture | Modular MVVM / clean boundaries |
| Async | Kotlin Coroutines + Flow |
| Database | Room / SQLite |
| HTTP | OkHttp |
| Serialization | kotlinx.serialization |
| Dependency injection | Hilt |
| OAuth | AppAuth-Android (Custom Tabs, PKCE) |
| Credentials | Android Keystore-wrapped encryption |
| Background transfers | User-Initiated Data Transfer (JobScheduler) on API 34+ |
| Legacy fallback | WorkManager foreground worker (API < 34) |
| Image metadata | AndroidX ExifInterface |
| Build | Gradle Kotlin DSL |

Avoid analytics, advertising, and unnecessary third-party SDKs. Provider adapters should use the shared HTTP stack unless an official provider SDK materially improves correctness or OAuth integration. The Dropbox and Drive REST APIs are simple enough that hand-written OkHttp clients are preferred; they also avoid pulling transitive SDK dependencies into the Data Safety declaration.

## 4. Repository and Module Structure

```
cloudlug/
├── app/
├── core/
│   ├── model/
│   ├── database/
│   ├── hashing/
│   ├── network/
│   ├── security/
│   ├── transfer/
│   ├── storage/
│   └── ui/
├── providers/
│   ├── api/
│   ├── dropbox/
│   └── google-drive/
├── feature/
│   ├── home/
│   ├── accounts/
│   ├── new-transfer/
│   ├── source-picker/
│   ├── destination-picker/
│   ├── transfer-details/
│   └── settings/
└── docs/
    ├── architecture.md
    ├── security.md
    ├── privacy.md
    ├── provider-api.md
    └── threat-model.md
```

Provider implementations depend on `providers:api`. The transfer engine depends only on provider abstractions and must never directly depend on Dropbox or Google Drive modules. `core/hashing` holds the §19.4 pipeline and every hash algorithm, including provider-defined ones such as Dropbox's block hash; the engine selects an algorithm through `ProviderCapabilities.nativeHashAlgorithm` and never names a provider.

## 5. Provider Abstraction

```kotlin
interface CloudProvider {
    val type: ProviderType
    val capabilities: ProviderCapabilities

    suspend fun authenticate(): CloudAccount
    suspend fun disconnect(account: AccountId)

    suspend fun resolveMetadata(account: AccountId, objectId: CloudObjectId): CloudObject
    fun enumerate(account: AccountId, selection: CloudSelection, resumeAfter: CloudObjectId? = null): Flow<CloudObject>
    suspend fun quota(account: AccountId): StorageQuota?

    suspend fun openDownload(account: AccountId, objectId: CloudObjectId, range: LongRange?): CloudDownload

    suspend fun prepareDestination(account: AccountId, parent: CloudObjectId, relativePath: CloudPath): DestinationObject
    suspend fun lookupDestination(account: AccountId, parent: CloudObjectId, name: String): List<CloudObject>

    suspend fun beginUpload(account: AccountId, request: UploadRequest): UploadSession
    suspend fun uploadChunk(session: UploadSession, chunk: Chunk): UploadProgress
    suspend fun queryUpload(session: UploadSession): UploadProgress
    suspend fun finishUpload(session: UploadSession): CloudObject
    suspend fun abortUpload(session: UploadSession)
}

data class ProviderCapabilities(
    val canBeSource: Boolean,               // false when read scope is not available in this build
    val canBeDestination: Boolean,
    val supportsRangeDownload: Boolean,
    val supportsResumableUpload: Boolean,
    val supportsServerHash: Boolean,
    val nativeHashAlgorithm: HashAlgorithm?, // e.g. DROPBOX_CONTENT_HASH, MD5, SHA256
    val supportsFolderPicker: Boolean,
    val supportsMultipleSourceSelection: Boolean,
    val supportsStableObjectIds: Boolean,
    val supportsModifiedTimeWrite: Boolean,
    val supportsCustomMetadata: Boolean,
    val caseSensitiveNames: Boolean,
    val allowsDuplicateSiblingNames: Boolean,
    val uploadChunkAlignment: Long,          // bytes; chunk sizes must be a multiple of this
    val maxUploadChunkBytes: Long,
    val illegalNameCharacters: Set<Char>,
    val maxNameLength: Int,
    val maxPathLength: Int?,
    val disallowsTrailingSpaceOrDot: Boolean
)
```

`enumerate` is a cold flow, so it is not `suspend`. `resumeAfter` is the ID of the last object the caller persisted; an adapter that cannot resume from an object ID may restart from the beginning, and the manifest builder deduplicates by source object ID (§11).

`lookupDestination` returns a list because some providers (Google Drive) allow multiple siblings with the same name. Capabilities allow the engine to adapt to future providers rather than encoding provider-specific assumptions.

## 6. Provider Identity

Paths are presentation/layout information, not canonical identity. Stable provider IDs are used whenever available.

```kotlin
data class CloudObjectId(
    val provider: ProviderType,
    val opaqueId: String
)

data class CloudObject(
    val id: CloudObjectId,
    val name: String,
    val type: CloudObjectType,           // FILE, FOLDER, SHORTCUT, PROVIDER_NATIVE_DOCUMENT
    val parentId: CloudObjectId?,
    val size: Long?,                     // null for provider-native documents
    val modifiedAt: Instant?,
    val providerHash: ProviderHash?,
    val revision: String?,
    val mimeType: String?,
    val exportFormats: List<String>?     // for PROVIDER_NATIVE_DOCUMENT only
)
```

## 7. Account Model

Accounts are connected independently. The data model should support multiple accounts per provider even if the first UI exposes one account per provider.

```kotlin
data class CloudAccount(
    val id: AccountId,
    val provider: ProviderType,
    val displayName: String?,
    val displayEmail: String?,
    val grantedScopes: Set<String>
)
```

Transfers reference `sourceAccountId` and `destinationAccountId`, not merely provider types. `grantedScopes` lets the engine determine whether an account can act as source, destination, or both.

## 8. OAuth and Credential Architecture

### 8.1 Dropbox
Use OAuth 2 Authorization Code with PKCE (`token_access_type=offline` for a refresh token). Never embed a client secret in the APK. Request the minimum practical scopes: `files.metadata.read`, `files.content.read`, `files.content.write`, `account_info.read`. Dropbox refresh tokens do not expire on their own; they are invalidated by revocation or by the user unlinking the app.

### 8.2 Google Drive

This is the single largest product risk and must be decided before architecture is finalized.

**Facts (verify against current Google policy before each release):**

- `drive.file` is a *non-sensitive* scope. It grants access only to files the app created or that the user explicitly opened with the app via the Google Picker API. It is sufficient for CloudLug **as a destination**: CloudLug creates the enclosing folder and every object inside it, so it can read them back for verification.
- `drive.readonly` and `drive` are *restricted* scopes. They require Google OAuth verification **and** a CASA (Cloud Application Security Assessment) by an authorized lab, revalidated annually. This is a recurring obligation on the maintainer, not a one-time cost.
- The Google Picker API is a JavaScript/web component. There is no Android-native Drive picker that grants `drive.file` access to an arbitrary user-chosen folder and its descendants. Embedding the web Picker in a WebView is fragile and not recommended.
- While a Google Cloud OAuth app is in **Testing** publishing status (unverified), refresh tokens expire after 7 days and only listed test users can sign in. This directly affects the public beta.

**Decision:**

1. **v1 ships Dropbox → Google Drive only**, using `drive.file` as the destination scope. No restricted scopes, no CASA, and Google verification is limited to the standard (non-restricted) review.
2. **Google Drive → Dropbox** is a separately gated milestone (§32) that requires either:
   - completing restricted-scope verification and CASA for the Play-published build, or
   - a documented self-build path: users who build from source register their own Google OAuth client, add themselves as a test user, and request `drive.readonly`. This keeps the feature available to the open-source community without imposing CASA on the maintainer.
3. The `CloudProvider.capabilities.canBeSource` flag is driven by the scopes actually granted, so the same Google Drive adapter serves both configurations without code changes.
4. Do **not** design around the Storage Access Framework / Drive DocumentsProvider as a source. It yields content URIs, not Drive object IDs, and provides no provider hash, no revision, and no resumable semantics.

### 8.3 Credential storage
Secrets must never be stored in Room, plaintext preferences, logs, crash reports, or transfer manifests. Store refresh credentials and other long-lived secrets encrypted with keys protected by Android Keystore (AES-GCM, key generated in Keystore, `setUserAuthenticationRequired(false)` in v1 so background transfers can refresh tokens). Access tokens remain short-lived and in memory.

Disconnecting an account should revoke credentials when the provider supports revocation (Dropbox `auth/token/revoke`; Google `oauth2/revoke`), delete local credential material, and invalidate authenticated sessions. An optional future setting may disconnect accounts automatically when all active transfers finish.

### 8.4 OAuth flow on Android
Use AppAuth-Android with Chrome Custom Tabs and a custom-scheme or App Links redirect. Google rejects OAuth in embedded WebViews. Redirect handling must be tied to CloudLug's package (verified App Links or a claimed custom scheme validated against the pending PKCE state) to mitigate redirect interception.

## 9. Source and Destination Selection

A transfer first chooses two different providers/accounts. Selecting a provider as source disables the same provider as destination. Accounts whose granted scopes cannot support a role are disabled for that role with an explanation.

The v1 source picker is an in-app browser built on the provider's enumeration API. The transfer engine must not care how a selection was obtained.

```kotlin
interface CloudSelectionProvider {
    suspend fun selectSource(): CloudSelection
    suspend fun selectDestinationFolder(): CloudSelection
}
```

`CloudSelection` may represent a single file, multiple files, one or more directories, or a mixed selection, subject to provider capabilities.

Because `CloudObject` carries identity rather than a path (§6), the engine cannot derive a root's ancestors from the object alone. The selection therefore carries, for each root, its destination-relative display path (e.g. `photos/2026/April` for a root the user picked at `/photos/2026/April`). The picker is the only component that knows this, and it is what §10 uses to preserve the tree above the selected roots.

## 10. Transfer Creation and Path Mapping

After the user selects source objects and a destination folder, CloudLug creates one enclosing destination folder named with the app and transfer date/time.

```
Source:                       Destination:
  /docs/abc.txt                 /stuff/
  /photos/2025/
  /photos/2026/April/         Created:
  /projects/code.py             /stuff/CloudLug - 2026-09-17 09-57/

CloudLug - 2026-09-17 09-57/
├── docs/
│   └── abc.txt
├── photos/
│   ├── 2025/
│   │   ├── photo1.png
│   │   └── July/
│   │       └── summer.jpg
│   └── 2026/
│       └── April/
│           ├── 1.png
│           ├── 2.png
│           └── 3.png
└── projects/
    └── code.py
```

Original relative paths are preserved, including empty folders. Independent transfers must not silently merge into the same enclosing folder: because the name has minute resolution, a second transfer created in the same minute gets a numeric suffix on the enclosing folder (`CloudLug - 2026-09-17 09-57 (2)`). Items inside are never auto-renamed. The enclosing-folder name uses only characters legal on every supported provider (no `: / \ < > " | ? *`).

The enclosing folder is created at the `READY → RUNNING` transition, not during `PREPARING`. The destination picker has already shown the folder is browsable and §20.7 has shown there is space; if creation fails at start, the trans