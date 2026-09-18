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

The enclosing folder is created at the `READY → RUNNING` transition, not during `PREPARING`. The destination picker has already shown the folder is browsable and §20.7 has shown there is space; if creation fails at start, the transfer fails fast with zero bytes moved. Creating it earlier leaves an empty folder behind whenever a user reviews a manifest and abandons it.

## 11. Transfer Manifest

Before moving file bytes, CloudLug enumerates the selected objects and persists a logical manifest. The manifest represents what should be transferred independently from what is currently executing, and survives process death.

Enumeration is itself resumable because large trees can take minutes to walk. Each page of results is persisted in the same transaction as its cursor; the cursor is the ID of the last object emitted. On resume, `enumerate(resumeAfter = cursor)` continues from there. An adapter that cannot resume from an ID may restart from the beginning, and the manifest builder deduplicates by source object ID so a restart is idempotent.

**Size-unknown items.** Some manifest items have no size before transfer (provider-native documents that will be exported, §20.1). `size` is nullable for those items. `totalBytes` sums only known sizes and `unknownSizeFiles` records how many items are excluded; while that count is non-zero the UI drives the progress denominator by file count and shows bytes as "at least". When an item's size becomes known (after export), `totalBytes` is updated in the same transaction as the item.

## 12. Persistence Model

### 12.1 TransferEntity
```
id, createdAt, updatedAt
sourceProvider, sourceAccountId
destinationProvider, destinationAccountId
destinationRootId, destinationContainerId, destinationContainerName
status, networkPolicy
enumerationCursor
totalFiles, completedFiles, duplicateFiles, unsupportedFiles, sourceChangedFiles,
  conflictFiles, failedFiles, cancelledFiles
totalBytes, completedBytes, unknownSizeFiles
lastErrorCode, lastErrorMessage
```

### 12.2 TransferItemEntity
```
id, transferId
sourceObjectId, sourceRevision, sourceRelativePath
filename, mimeType, size, modifiedAt
objectKind                     (FILE | FOLDER | PROVIDER_NATIVE_DOCUMENT | SHORTCUT)
sourceProviderHash
computedSha256
computedDestinationNativeHash
hashCheckpoint                 (serialized hasher state as of the last acknowledged chunk; see §19.4)
destinationParentId, destinationObjectId, destinationRelativePath
status, statusReason
downloadedBytes, uploadedBytes
uploadSessionId, uploadSessionMetadata, uploadSessionExpiresAt
retryCount, lastErrorCode, lastErrorMessage
createdAt, updatedAt
```

### 12.3 CacheChunkEntity
```
id, transferItemId
offset, length
localFilename
hash
status
createdAt
```

### 12.4 AccountEntity
Store only non-secret account metadata in Room (display name, email, granted scopes, provider account ID). Credentials are kept separately in secure storage.

## 13. State Machines

### 13.1 Transfer
```
DRAFT -> PREPARING -> READY -> RUNNING
            |                    |-> PAUSED
            |-> WAITING_FOR_WIFI |-> WAITING_FOR_WIFI
            |-> AUTH_REQUIRED    |-> WAITING_FOR_STORAGE
                                 |-> AUTH_REQUIRED
                                 |-> FAILED
                                 |-> CANCELLED
                                 `-> COMPLETED / COMPLETED_WITH_ISSUES
```

`PREPARING` (enumeration) needs network and a valid token just as `RUNNING` does, so it has the same waiting edges rather than failing a transfer because Wi-Fi dropped mid-walk.

**Completion rule.** A transfer ends `COMPLETED` only if every manifest item ended `COMPLETED` or `SKIPPED_DUPLICATE`. Any item in `SKIPPED_UNSUPPORTED`, `SOURCE_CHANGED`, `CONFLICT`, `FAILED`, or `CANCELLED` makes the transfer `COMPLETED_WITH_ISSUES`, and the summary shows each count separately. A transfer that moved half of what the user selected must never read "Completed" (§2.5).

### 13.2 File
```
PENDING
  -> CHECKING_DESTINATION
       -> SKIPPED_DUPLICATE
       -> SKIPPED_UNSUPPORTED
       -> CONFLICT
       -> SOURCE_CHANGED
       -> DOWNLOADING -> CACHED -> UPLOADING -> VERIFYING -> COMPLETED

Any active state -> FAILED or CANCELLED
```

- **CONFLICT**: destination object exists and is not provably identical. Terminal in v1; the user can retry after removing the destination object.
- **SKIPPED_UNSUPPORTED**: the object cannot be transferred as bytes (e.g. a Google-native document with export disabled, a Drive shortcut) — see §20.
- **SOURCE_CHANGED**: the source revision at download time differs from the manifest revision. Terminal for this run; "Retry incomplete files" re-enumerates and picks up the new revision.

**Item state means "furthest stage reached".** §14 requires chunk N+1 to download while chunk N uploads, so an item is genuinely in two stages at once. The item state records the most advanced stage any chunk has reached; the §15.3 chunk states carry the concurrent truth. `UPLOADING` therefore does not mean downloading has finished.

State transitions should be transactional in Room and illegal transitions should be unit-tested.

## 14. Transfer Pipeline

```
Cloud A
  |
  v
Download producer
  |
  v
Persistent bounded chunk cache
  |
  v
Upload consumer
  |
  v
Cloud B
```

Download and upload may overlap within a file. The producer applies backpressure when the cache reaches its high-water mark; the uploader deletes chunks only after they are no longer needed for recovery (i.e. the destination has acknowledged the byte range in `queryUpload` or `uploadChunk`).

## 15. Chunk and Storage Strategy

Do not make 50% of free storage the chunk size. Use small provider-compatible network chunks inside a bounded persistent cache. The reserve is subtracted first, then the budget is taken from what remains; the two operations do not commute on a tight device, so the order below is normative.

```
reserve        = max(1 GiB, totalDeviceStorage * 0.05)      (§15.1)
usableStorage  = freeSpace - reserve
cacheBudget    = min(usableStorage * 0.50, configuredMaximumCache)

initial configuredMaximumCache = 5 GiB
initial networkChunkSize      = 8 MiB
```

8 MiB is a multiple of Google Drive's 256 KiB resumable-upload granularity and Dropbox's 4 MiB `content_hash` block size, so one chunk size satisfies both providers and lets the Dropbox native hash be computed per chunk. Provider adapters may change chunk size or alignment according to `uploadChunkAlignment` / `maxUploadChunkBytes`.

### 15.1 Emergency reserve
The reserve is `max(1 GiB, totalDeviceStorage * 0.05)` and is never touched by the cache. Before allocating another chunk, CloudLug ensures the required bytes plus reserve remain available. If storage is constrained, stop downloading and let the uploader drain the cache; if the cache is already empty and space is still insufficient, enter `WAITING_FOR_STORAGE`.

### 15.2 Cache location and backup exclusion
```
<filesDir or cacheDir>/cloudlug-cache/
└── <transfer-id>/
    └── <item-id>/
        ├── 00000000.chunk
        ├── 00000001.chunk
        └── ...
```

Use `filesDir` (not `cacheDir`) so the OS does not evict chunks mid-transfer. Temporary file contents must not be placed in shared storage. Declare `android:fullBackupContent` / `android:dataExtractionRules` excluding the cache directory and the encrypted-credential store; auto-backup of the Room database is acceptable only if credentials are provably not in it.

### 15.3 Chunk lifecycle
```
ALLOCATED -> DOWNLOADING -> READY -> UPLOADING -> ACKNOWLEDGED -> DELETED
                 |                      |
                 `-> ALLOCATED          `-> READY        (failure edges)
```

An abandoned download returns the chunk to `ALLOCATED`; a failed upload attempt returns it to `READY`. Both retain any bytes already on disk, so §32 invariant 4 is unaffected. Each chunk acknowledgment is persisted in the same transaction as the item's `hashCheckpoint` (§19.4).

## 16. Network Policy

The default is Wi-Fi/unmetered networks only. Cellular transfers require explicit user opt-in. Internally model this as network policy rather than assuming all Wi-Fi is unmetered.

```kotlin
enum class TransferNetworkPolicy { UNMETERED_ONLY, ANY_NETWORK }
```

If a transfer running under `UNMETERED_ONLY` loses its allowed network, it transitions to `WAITING_FOR_WIFI` and automatically resumes when an allowed network returns. It must never silently fall back to cellular. Express the policy as a JobScheduler/WorkManager network constraint so the platform enforces it too.

## 17. Android Background Execution

On API 34+, use User-Initiated Data Transfer jobs (`JobInfo.Builder.setUserInitiated(true)` with `setEstimatedNetworkBytes`) for long-running transfers, with a visible notification and the network constraint from §16. UIDT exists precisely because Android 14 caps `dataSync` foreground services at roughly 6 hours per 24-hour window, which a multi-hundred-gigabyte migration will exceed. Persist all state because Android may still terminate the process.

On API 26–33, use a WorkManager long-running worker with a `dataSync` foreground service. Correctness must never depend on any worker staying alive.

Request the user disable battery optimization for CloudLug only if telemetry-free beta testing shows it is required; do not demand it by default.

## 18. Concurrency

Start conservatively with one active file, one download producer, and one upload consumer. Within a file, downloading chunk N+1 while uploading chunk N is the main throughput win and is safe with resumable uploads. Later versions may permit a small number of concurrent files based on device conditions and provider capabilities. More concurrency must not be assumed to be faster on a mobile device.

For trees with many small files, per-file API round trips dominate; measure before optimizing, and prefer batching metadata calls over file-level parallelism.

## 19. Idempotency, Duplicate Detection, and Hashing

### 19.1 Separate two concepts
- **Transfer idempotency**: did CloudLug already transfer this exact source object?
- **Content duplication**: does equivalent content already exist at the destination?

### 19.2 Idempotency record
```
source provider, source account, source object ID, source revision, source size, source hash
destination object ID, destination path
```

Completed items are never blindly retransferred. On retry, CloudLug verifies destination existence/identity where practical and skips valid completed items.

### 19.3 Destination collision algorithm
The §19.2 idempotency record is consulted **before** this algorithm, and the ordering is load-bearing rather than an optimisation: for Dropbox → Drive the two providers' hashes are not comparable, so without the idempotency check every re-run would report conflicts against files CloudLug itself uploaded.

```
idempotency record exists for (source object, revision)
  and destination object still present with matching size -> SKIPPED_DUPLICATE (already transferred)
otherwise:
lookupDestination(parent, name)
  no match           -> upload
  multiple matches   -> CONFLICT
  one match:
    size differs     -> CONFLICT
    size equal:
      strong hash available on both sides and equal   -> SKIPPED_DUPLICATE
      strong hash available on both sides and differs -> CONFLICT
      no comparable hash                              -> CONFLICT
```

CloudLug never automatically overwrites a conflict. Renaming conflicts (e.g. `name (1).ext`) may be added later as an explicit user choice.

### 19.4 Hashing
The hash pipeline runs once, incrementally, as bytes pass through the phone. It computes:

- a provider-independent **SHA-256** of the whole object, and
- the **destination provider's native hash**, so verification after upload is a metadata comparison:
  - Dropbox: `content_hash` = SHA-256 of the concatenated SHA-256 digests of each 4 MiB block.
  - Google Drive: MD5 (`md5Checksum`); the v3 API also reports `sha1Checksum` / `sha256Checksum`, so store SHA-256 and compare whichever is returned.

**Hashers are checkpointable.** SHA-256, MD5, and the Dropbox block hash all have small internal state (a few words, a bit count, and under 64 bytes of pending buffer, plus for the block hash the list of completed block digests at 32 bytes per 4 MiB). CloudLug implements these hashers with serializable state rather than using `MessageDigest` directly, and persists that state as the item's `hashCheckpoint` in the same transaction as each chunk acknowledgment. After process death, hashing resumes from the checkpoint, so verification (§21) never degrades because acknowledged chunks have been deleted from the cache.

Do not reread a multi-gigabyte cached object merely to hash it. Source provider hashes are used opportunistically for pre-download duplicate detection when the algorithms are comparable (Dropbox → Drive: they are not; the source hash is only useful for detecting source changes).

### 19.5 EXIF
EXIF is optional supplemental metadata, not the primary identity mechanism. It may later help identify likely photo duplicates with different filenames, but exact content hashes and durable transfer state are stronger.

## 20. Provider-Specific Semantics

The engine stays provider-agnostic, but the manifest must record how each object will be handled so the user sees it in the review step.

### 20.1 Google-native documents
Docs, Sheets, Slides, Drawings, Forms, etc. have no byte stream and report `size = null`. Policy:
- Default: `SKIPPED_UNSUPPORTED` with reason "Google-native document".
- Optional setting: export on transfer (Docs → `.docx`, Sheets → `.xlsx`, Slides → `.pptx`, others → PDF). Exported files have no source hash and no size before export (see size-unknown items, §11); the size is recorded once export completes. Verification is by destination native hash of the exported bytes. Export is capped by Google at ~10 MB per file; larger documents fall back to `SKIPPED_UNSUPPORTED`.

### 20.2 Shortcuts and links
Drive shortcuts (`application/vnd.google-apps.shortcut`) are not followed. They are `SKIPPED_UNSUPPORTED`. Dropbox has no equivalent object.

### 20.3 Same-name siblings
Google Drive permits multiple children with identical names in one folder. Dropbox does not, and its paths are case-insensitive. When mapping a Drive source tree to a Dropbox destination, siblings that collide case-insensitively are `CONFLICT` at manifest time, before any bytes move.

### 20.4 Name legality
Providers differ on illegal characters, trailing spaces/dots, and maximum name/path length. Names that cannot be represented at the destination are `CONFLICT` at manifest time with a reason. v1 does not auto-rename.

### 20.5 Empty folders
Empty source folders are created at the destination so the tree is preserved.

### 20.6 Source changed during transfer
`resolveMetadata` is called immediately before `openDownload`. If the revision differs from the manifest, the item becomes `SOURCE_CHANGED`. CloudLug does not silently transfer a newer version than the user reviewed.

### 20.7 Destination quota preflight
Before `READY`, compare `totalBytes` against `quota(destinationAccount)`. If insufficient, refuse to start with a clear message rather than failing mid-transfer.

### 20.8 Shared drives and shared-with-me
v1 supports only "My Drive" as a Drive destination. Shared drives require `supportsAllDrives` handling and different quota semantics; defer.

## 21. Verification

A successful upload API response is not by itself sufficient to mark an item `COMPLETED`. Use the strongest available destination verification, in order:

1. Destination native hash returned by `finishUpload` equals the locally computed native hash.
2. If the provider omits the hash from the finish response, `resolveMetadata` on the new object and compare.
3. Metadata confirmation only (existence, size) — permitted only when capabilities declare `supportsServerHash = false`, and the item is flagged "verified by size only" in history.

A provider that declares `supportsServerHash = true` and then returns no hash for an object leaves the item **unverifiable**: it does not complete, and the failure reason says so. A successful upload response is never sufficient on its own. Because hashers are checkpointable (§19.4), resumption after process death does not change which step applies.

`COMPLETED` means destination verification succeeded.

## 22. Pause, Cancel, Retry

### 22.1 Pause
1. Stop scheduling new downloads.
2. Stop scheduling new upload chunks.
3. Safely finish or cancel current requests.
4. Persist upload-session state (including expiry).
5. Retain useful cached chunks.
6. Set transfer to `PAUSED`.

### 22.2 Cancel one file
1. Cancel active network calls for that item.
2. Abort its resumable destination session when appropriate.
3. Remove incomplete destination artifacts where safely possible.
4. Delete its local chunks.
5. Set item to `CANCELLED`.
6. Continue other items.

### 22.3 Cancel transfer
1. Stop all transfer workers.
2. Abort active network operations.
3. Clean incomplete destination sessions where possible.
4. Remove all local chunks for unfinished work.
5. Retain history/manifest.
6. Mark unfinished items `CANCELLED`.
7. Mark transfer `CANCELLED`.

Already completed destination files remain untouched.

### 22.4 Retry incomplete files
The UI should prefer "Retry incomplete files" over "Restart". Retry re-evaluates the manifest, verifies completed items, and retries pending, failed, cancelled, or source-changed items without blindly duplicating completed work.

### 22.5 Upload-session expiry
Resumable sessions expire (Google: about one week; Dropbox: about 7 days). `uploadSessionExpiresAt` is persisted; on resume, an expired session is abandoned and the item restarts from its cached chunks, never from a partially written destination object.

## 23. Retry Policy

| Condition | Handling |
|---|---|
| HTTP 408, 429, 5xx, timeouts, connection resets, DNS failures | Exponential backoff with jitter; honor `Retry-After`. |
| Google 403 with reason `userRateLimitExceeded`, `rateLimitExceeded`, `dailyLimitExceeded` | Treat as throttling, not as a permanent error. |
| Google 403 `storageQuotaExceeded`, Dropbox `insufficient_space` | `WAITING_FOR_STORAGE`-style hold with user message; not retried automatically. |
| 401, or a refresh that returns `invalid_grant` | `AUTH_REQUIRED`; never loop. Expected during beta if the Google OAuth app is in Testing status (7-day refresh-token expiry). |
| Permanent provider errors (404 on source, 400 malformed) | Item `FAILED` with a user-readable reason. |

Backoff caps at a few minutes per attempt; a bounded retry count moves the item to `FAILED` so the transfer can finish `COMPLETED_WITH_ISSUES`.

## 24. User Experience

### 24.1 Home
```
CloudLug                                  [+ New Transfer]

Active
  Dropbox -> Google Drive
  ████████████░░░  74%
  1,103 / 1,482 files

History
  Google Drive -> Dropbox     Completed              Sep 14 • 843 files
  Dropbox -> Google Drive     3 failed, 12 skipped   Sep 11 • 2,140 files
```

### 24.2 New Transfer Wizard
1. Choose source provider/account (only accounts with read capability).
2. Choose destination provider/account; same provider is disabled.
3. Use source picker to select files/folders.
4. Use destination picker to select the parent destination folder.
5. Review size, file count, items to be skipped or in conflict (§20), network policy, available local storage, destination quota, cache limit, and destination enclosing folder.
6. Start transfer.

### 24.3 Transfer Detail
```
Dropbox -> Google Drive
██████████████░░░░  72%
1,068 / 1,482 files
31.4 / 43.7 GB

photos/
  2025/
    ✓ photo1.png
    ✓ photo2.png
    → summer.jpg
    ○ beach.jpg

CURRENT FILE
summer.jpg
381 MB / 742 MB

Dropbox        Phone        Google Drive
  ●  ======>    ●  ------>     ○
Downloading

[Cancel File]
[Pause Transfer]   [Cancel Transfer]
```

During upload, the highlighted arrow changes from source→phone to phone→destination. During verification, neither transfer arrow is active and the destination is highlighted.

### 24.4 Notifications
The active notification shows provider direction, aggregate progress, current filename, and pause/cancel controls. Waiting states explicitly say why the transfer is paused, such as waiting for Wi-Fi. Failures surface a review action.

## 25. Privacy Model

Recommended user-facing statement:

> "CloudLug has no transfer servers. Files are transferred directly between your selected cloud providers through your Android device."

Do not claim that nobody except the user can access the files, because the source and destination providers necessarily can. Do not claim end-to-end encryption unless CloudLug later introduces an actual E2EE file format.

## 26. Logging and Diagnostics

Logs must never contain access tokens, refresh tokens, authorization codes, file contents, or `Authorization` headers. Implement this as a single OkHttp interceptor plus a log-sink redactor rather than per-call discipline. Prefer opaque internal identifiers over filenames. Debug logging of filenames should be opt-in.

No automatic remote logging in v1. Provide an explicit "Export diagnostic report" function that produces a sanitized, user-reviewable text or JSON report.

## 27. Analytics

Recommended v1 policy: no Firebase Analytics, advertising SDK, third-party crash reporter, or behavioral telemetry. Use Play Console aggregate diagnostics, explicit diagnostic exports, and GitHub issues.

## 28. Threat Model

| Threat | Mitigation |
|---|---|
| Stolen unlocked phone | Android sandbox; Keystore; optional biometric app lock later. |
| Rooted/compromised device | Document that CloudLug cannot guarantee credential confidentiality on a compromised OS. |
| Malicious APK modification | Publish reproducible-build guidance where practical. |
| OAuth redirect interception | PKCE plus verified App Links / claimed-scheme validation against pending state. |
| Token leakage | Central log/header redaction. |
| Cache remnants | Delete chunks immediately when no longer needed; recursively clean cache after completion/cancellation and on app start. |
| Destination overwrite | Never overwrite automatically. |
| Replay after crash | Stable manifest, stable destination identity where available, and verification. |
| MITM | TLS/HTTPS only; Network Security Config with cleartext disabled; never bypass certificate validation. |

## 29. Google Play and Privacy Compliance

CloudLug accesses personal/sensitive data because cloud files and authentication credentials are involved. The published privacy policy and Google Play Data Safety declaration must accurately reflect the actual binary and all included dependencies.

Privacy policy should state that CloudLug operates no server that receives, stores, inspects, or transfers cloud files; selected files may be temporarily cached in private application storage; credentials are stored locally using Android security facilities; data is not sold; and cloud file contents are not used for advertising, profiling, analytics, or AI training.

Google's OAuth verification also requires a hosted privacy policy, a homepage, and a demonstration video of the consent flow; budget time for this before public beta.

## 30. Open-Source Project

Choose an OSI-approved license based on desired downstream restrictions; Apache-2.0 is a strong permissive default, while GPL-3.0 provides stronger copyleft.

Never commit signing keys, Play credentials, test-account credentials, or secrets. Mobile OAuth client identifiers should be treated as public identifiers, not secrets. Provider registration instructions belong in development documentation so contributors can use their own provider applications — this is also the documented path to Google Drive as a *source* for self-builders (§8.2).

```
README.md
LICENSE
CONTRIBUTING.md
SECURITY.md
PRIVACY.md
CODE_OF_CONDUCT.md
docs/
├── architecture.md
├── transfer-engine.md
├── provider-api.md
├── persistence.md
├── oauth.md
├── security.md
├── threat-model.md
├── privacy.md
└── testing.md
```

## 31. Testing Strategy

### 31.1 Unit tests
Test legal and illegal transfer/item state transitions, cache accounting, collision logic (including multiple-match and case-insensitive collisions), network-policy transitions, retries, both hash algorithms against known vectors, and cleanup.

### 31.2 Provider contract tests
Every provider adapter should pass the same behavioral contract for authentication, enumeration (paged), quota, download/range download where supported, folder creation, upload, resume, session expiry, lookup (including multi-match), metadata, hashing behavior, and abort.

### 31.3 FakeCloudProvider
Build `FakeCloudProvider` before real provider integrations. It should simulate network disconnect after N bytes, 429, 500, 403 rate-limit reasons, token expiration, corrupt chunks, destination conflicts, duplicate-name siblings, provider-native documents, expired upload sessions, provider timeouts, slow source, slow destination, and unsupported capabilities.

Process interruption is a separate injection, not a network failure. A network error is retried and may end an item `FAILED`; a killed process must leave the database exactly as it was so §31.4 can test recovery. The fake therefore raises a non-provider exception for process death that the retry policy never sees. Conflating the two makes the §31.4 scenarios test error handling instead of recovery.

### 31.4 Crash/recovery tests
- Kill process during enumeration.
- Kill process during download.
- Kill process during upload.
- Kill process during verification.
- Reboot during upload.
- Remove and restore Wi-Fi.
- Switch to cellular while cellular is forbidden.
- Fill local storage.
- Expire/revoke OAuth credentials.
- Expire the resumable upload session.

Every scenario must converge to a valid state without overwriting destination data.

## 32. Engineering Invariants

1. CloudLug never deletes source data.
2. CloudLug never overwrites an existing destination object automatically.
3. `COMPLETED` means destination verification succeeded.
4. A cached chunk is deleted only when it is no longer needed for recovery.
5. No transfer occurs over a network disallowed by the transfer policy.
6. An interrupted transfer can be safely retried.
7. Provider-specific behavior never leaks into the core transfer engine.
8. No CloudLug-operated remote system receives user file contents or cloud credentials.
9. Every object in the manifest ends in exactly one terminal state with a recorded reason.

## 33. MVP Roadmap

| Milestone | Scope |
|---|---|
| v0.1 — Core architecture | Provider API, state machines, transactional repository over DAO interfaces, cache manager, checkpointable dual-hash pipeline, transfer engine, `FakeCloudProvider` and contract suite. Pure JVM; all tests run without an Android SDK. *(Delivered.)* |
| v0.2 — Room and Compose shell | Room implementation behind the existing DAO interfaces (a mechanical annotation pass while the shapes are fresh), then the Compose shell wired to the fake provider so a fake transfer runs end to end on a device. This exercises the engine's public surface before any real API is involved. |
| v0.3 — Dropbox adapter | Dropbox as source and destination. **First task:** upload a known file and compare the provider's `content_hash` with the local implementation. |
| v0.4 — Google Drive destination | Drive adapter with `drive.file` only: folder creation, resumable upload, verification via `md5Checksum`/`sha256Checksum`. **Dropbox → Drive works end to end.** |
| v0.5 — Recovery hardening | Device-dependent §31.4 scenarios (reboot, force-stop, UIDT), session expiry against real providers, storage pressure, real throttling shapes. |
| v0.6 — Security review | OAuth, Keystore, logging, cache cleanup, backup rules, network configuration, dependencies. |
| v0.7 — Public beta | Move the Google OAuth app to Production status (non-restricted verification). Small Google Play testing cohort; collect bug reports without adding file/content telemetry. |
| v1.0 — Stable release | **Dropbox → Google Drive.** Only after large real-world migrations and repeated failure-injection tests complete reliably. |
| v1.x — Drive as source | Drive enumeration/download behind `canBeSource`. Ship first as a documented self-build path (own OAuth client, `drive.readonly`). Pursue restricted-scope verification + CASA for the Play build only if maintainer capacity allows. |

## 34. Future Providers and Features

### 34.1 Providers
- Microsoft OneDrive
- Box
- WebDAV / Nextcloud
- S3-compatible storage
- Backblaze B2
- pCloud
- Proton Drive

### 34.2 Features
- Controlled parallel transfers
- Charging-only transfers
- Battery threshold
- Maximum bandwidth
- Provider-to-local backup
- Local-to-provider upload
- Transfer manifest export
- Conflict auto-rename as an explicit option
- "Compare with destination" conflict resolution: user-triggered download-and-compare for `CONFLICT` items under a size threshold (relevant when provider hashes are not comparable, e.g. Dropbox → Drive re-runs)
- Photo duplicate analysis
- Multiple accounts per provider
- Transfer estimates
- Per-provider rate-limit tuning
- Google shared-drive support

Do not add synchronization casually. Synchronization introduces materially different conflict, deletion, and reconciliation semantics.

## 35. Architectural Summary

```
Compose UI
    |
Transfer Controller
    |
Transfer Engine
    |-- state machine
    |-- cache manager
    |-- integrity verifier (dual hash)
    |-- network policy
    |-- retry engine
    |
    +--> CloudProvider A adapter
    |
    +--> CloudProvider B adapter
    |
Room / Keystore / private local cache
```

The core rule is simple: the transfer engine knows that bytes move from CloudProvider A to CloudProvider B. It does not know what Dropbox, Google Drive, OneDrive, Box, S3, WebDAV, or any future provider is. That boundary is what allows CloudLug to evolve from a Dropbox/Drive migration utility into a general-purpose, privacy-oriented cloud migration application.

## 36. Early Technical Validation Items

- **Confirm current Google scope classifications and CASA requirements** against developers.google.com and the App Defense Alliance CASA tiering page before writing any Drive code.
- Prototype `drive.file` behavior end to end: create folder, upload, read back metadata/hash, and confirm it works for objects CloudLug created after an app reinstall.
- Validate Dropbox and Drive resumable-upload semantics, chunk alignment, session expiry, and recovery behavior.
- Validate `content_hash` and MD5/SHA-256 computation against provider-reported values on real uploads. The v0.1 vectors were derived independently from the algorithm definitions, which catches an implementation bug but not a shared misreading; this check is the first hour of each adapter milestone, not the last.
- Validate UIDT behavior across Android 14+ and WorkManager/foreground fallback on older supported Android versions, including the `dataSync` time limit.
- Measure thermal, battery, and throughput characteristics on large transfers before increasing concurrency.
- Validate cache cleanup after force-stop, process death, reboot, cancellation, and low-storage events.
- Test Google-native-document export sizes and the ~10 MB export cap.
