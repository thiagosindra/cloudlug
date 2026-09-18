# Status — v0.1 reconciled to spec v1.2.1

What exists, what is stubbed, and what comes next. Milestone definitions are in
spec §33; design decisions taken along the way are in
[`decisions.md`](decisions.md), where every ADR now carries a Status line
recording whether v1.2 ratified or overruled it.

**Note on milestone numbers.** Spec v1.2 resequenced §33: Room and the Compose
shell are **v0.2**, the Dropbox adapter moved to **v0.3** and Google Drive to
**v0.4**. Earlier revisions of this file numbered these one lower.

## How to verify

```bash
./gradlew build     # compiles every module and runs 240 unit tests
```

JDK 17 or newer. **No Android SDK is required** for anything currently in the
tree; that changes in v0.2 when `:app` arrives.

## What exists and is tested

| Module | What it contains | Tests |
|---|---|---|
| `:core:model` | Identifiers, `CloudPath`, `ProviderHash`/`HashAlgorithm`, transfer and item states, network policy | 17 |
| `:providers:api` | `CloudProvider` and `ProviderCapabilities` (§5), `CloudObject`/`CloudObjectId` (§6), `CloudAccount` (§7), `CloudSelection` (§9), upload/download types, `CloudErrorKind` | 7 |
| `:core:database` | §12 entities, DAO interfaces, §13 state machines, §15.3 chunk lifecycle, `TransferRepository` with transactional transitions and the §12.1 split counters, in-memory store with rollback | 51 |
| `:core:hashing` | Single-pass SHA-256 + destination-native hash (§19.4), all **checkpointable**; hand-written SHA-256, SHA-1, MD5 and block-list SHA-256 (Dropbox `content_hash`) | 28 |
| `:core:storage` | Cache budget and emergency reserve (§15, §15.1), `filesDir` chunk store with orphan purging (§15.2) | 19 |
| `:core:transfer` | Manifest builder (§10, §11, §20), collision algorithm (§19.3), retry policy (§23), verification ordering (§21), network policy (§16), pipeline (§14), engine (§13.1, §22) | 72 |
| `:providers:fake` | `FakeCloudProvider` with every §31.3 failure injection, and the §31.2 contract suite | 46 |

Coverage against the §31.1 checklist:

- legal and illegal transfer/item state transitions — `StateMachineTest`,
  `TransferRepositoryTest`
- cache accounting — `CacheAccountantTest`, `FileSystemChunkStoreTest`
- collision logic including multiple matches and case-insensitive collisions —
  `DestinationCollisionResolverTest`, `ManifestBuilderTest`
- network-policy transitions — `NetworkPolicyGateTest`, `TransferEngineTest`
- retries — `RetryPolicyTest`, `TransferEngineTest`
- both hash algorithms against known vectors — `StreamingHasherTest`,
  `DualHashPipelineTest`
- cleanup — `TransferEngineTest` (cache empty at completion, cancellation),
  `FileSystemChunkStoreTest` (orphan purge)

### Reconciled to v1.2 in this milestone

Six ADRs were overruled by the spec and the code changed to match:

- **Checkpointable hashers (§19.4).** `MessageDigest` cannot be snapshotted, so
  SHA-256, SHA-1 and MD5 are hand-written over one Merkle-Damgard skeleton and
  verified against the JDK at every padding boundary. Hash state is persisted
  with each chunk acknowledgment in the same transaction (§15.3), so
  verification no longer degrades after process death — ADR-0007, ADR-0019.
- **`COMPLETED_WITH_ISSUES` and split counters (§12.1, §13.1).** Only
  `COMPLETED` and `SKIPPED_DUPLICATE` count as success; `duplicateFiles`,
  `unsupportedFiles` and `sourceChangedFiles` are separate — ADR-0008.
- **Size-unknown items (§11).** `totalBytes` counts known sizes and
  `unknownSizeFiles` records the rest, so the UI knows the denominator is a
  lower bound.
- **`enumerate` is not `suspend`, and resumes from an object ID (§5, §11)** —
  ADR-0006, ADR-0015.
- **Name legality is complete (§5, §20.4)**, now that capabilities carry
  `maxPathLength` and `disallowsTrailingSpaceOrDot` — ADR-0013.
- **Selections carry destination display paths (§9)** instead of the engine
  taking a resolver callback — ADR-0014.
- **The enclosing folder is created at `READY -> RUNNING` (§10)**, so abandoning
  a reviewed manifest leaves nothing behind — ADR-0020.

The §31.4 crash scenarios that do not need Android are covered end to end:
process killed mid-download, Wi-Fi lost, a cellular-only network while cellular
is forbidden, local storage filled, credentials expired, destination full, and a
corrupted chunk caught by verification. Reboot, force-stop and
UIDT/WorkManager behaviour need a device and are v0.4.

## What is deliberately not here

- **No Dropbox or Google Drive API calls, and no OAuth.** `:providers:dropbox`
  and `:providers:google-drive` are stubs carrying TODOs that name the spec
  sections they must satisfy. No client IDs, real or placeholder, appear
  anywhere in the tree.
- **No Room yet.** `:core:database` uses plain entities, DAO interfaces and an
  in-memory implementation; the §13 rules live in `TransferRepository` above the
  DAO layer so Room can be dropped in without behaviour changing — ADR-0002.
  Room is the first half of v0.2.
- **No Compose shell, no `:app`, no `feature/*`.** The second half of v0.2 —
  ADR-0003.
  The engine takes `NetworkMonitor`, `StorageMonitor`, `ProviderRegistry` and
  `ChunkStore` as interfaces precisely so the Android layer can supply
  ConnectivityManager, StatFs, Hilt bindings and `filesDir` later.
- **No Keystore credential storage, no UIDT/WorkManager scheduling, no Play
  assets.** Spec §8.3, §17, §29 — v0.2 and beyond.
- **No `core/network`, `core/security`, `core/ui` modules yet**, and the §30
  documentation set beyond this file, `decisions.md` and the root policy
  documents. They arrive with the code they document.
- **Native-document export (§20.1) is not implemented.** Provider-native
  documents are `SKIPPED_UNSUPPORTED`, which is the specified default; the
  optional export-on-transfer setting belongs with the Drive adapter.

## Known gaps to close later

1. **The pipeline is sequential per chunk** — correct but not yet overlapping
   download and upload in wall-clock terms. ADR-0017 was ratified by v1.2.1;
   §18 says measure first, so this waits for real providers.
2. **`content_hash` is validated against independently computed vectors and
   against the JDK, not against live Dropbox responses.** Both checks catch an
   implementation bug; neither catches a misreading of the algorithm that both
   sides share. Spec §36 and the v0.3 milestone now make this the *first* task
   of adapter work rather than the last.
3. **Nothing has run on a device.** Every test here is a JVM unit test. The
   §31.4 scenarios needing a real device — reboot, force-stop, UIDT — are v0.5.

Two gaps listed under v0.1 are now closed: hash resume after process death
(checkpointable hashers, ADR-0007/0019) and partial name legality
(ADR-0013).

## What v0.2 (Room and the Compose shell) needs from you

Nothing. It is Room behind the existing DAO interfaces, then `:app`, `core:ui`
and `feature/*` wired to `FakeCloudProvider`, both buildable with the public
Android SDK and no provider account.

## What v0.3 (Dropbox adapter) will need from you

Two things need your account rather than code, and are worth starting early
because registration is not instant:

1. **Dropbox App Console registration** — https://www.dropbox.com/developers/apps
   - the **app key** (client ID). A public identifier, fine to commit; I will
     not invent a placeholder that looks real.
   - whether the app is **scoped access** with **full Dropbox** or **app folder**
     access. Full Dropbox is needed for CloudLug to be useful as a source.
   - confirmation that `files.metadata.read`, `files.content.read`,
     `files.content.write` and `account_info.read` are enabled (spec §8.1).
   - **Do not send the app secret.** CloudLug uses PKCE and must never embed a
     client secret in the APK (§8.1, §30).
2. **Redirect URI.** I suggest the custom scheme
   `dev.thiagosindra.cloudlug://oauth/dropbox`, validated against the pending
   PKCE state (spec §8.4). For verified App Links instead, I need a domain you
   control and the ability to host `assetlinks.json` on it.

Useful but not blocking: a **test Dropbox account** with awkward data — deep
trees, many small files, a file over 4 GiB, names with trailing dots and
non-ASCII characters. Please do not send credentials; a description of the tree
is enough for me to mirror it in the fake provider.

For v0.4 (Google Drive destination) you will need a Google Cloud project with
OAuth credentials and the `drive.file` scope — but spec §36 asks that Google's
scope classification and CASA tiering be re-confirmed before any Drive code is
written.
