# Status — v0.1 (core architecture)

What exists, what is stubbed, and what v0.2 needs. Milestone definitions are in
spec §33; design decisions taken along the way are in
[`decisions.md`](decisions.md).

## How to verify

```bash
./gradlew build     # compiles every module and runs 211 unit tests
```

JDK 17 or newer. **No Android SDK is required**, and none was available in the
environment where this milestone was built.

## What exists and is tested

| Module | What it contains | Tests |
|---|---|---|
| `:core:model` | Identifiers, `CloudPath`, `ProviderHash`/`HashAlgorithm`, transfer and item states, network policy | 17 |
| `:providers:api` | `CloudProvider` and `ProviderCapabilities` (§5), `CloudObject`/`CloudObjectId` (§6), `CloudAccount` (§7), `CloudSelection` (§9), upload/download types, `CloudErrorKind` | 7 |
| `:core:database` | §12 entities, DAO interfaces, §13 state machines, §15.3 chunk lifecycle, `TransferRepository` with transactional transitions and counters, in-memory store with rollback | 43 |
| `:core:hashing` | Single-pass SHA-256 + destination-native hash (§19.4); SHA-256, SHA-1, MD5 and block-list SHA-256 (Dropbox `content_hash`) | 15 |
| `:core:storage` | Cache budget and emergency reserve (§15, §15.1), `filesDir` chunk store with orphan purging (§15.2) | 19 |
| `:core:transfer` | Manifest builder (§10, §11, §20), collision algorithm (§19.3), retry policy (§23), verification ordering (§21), network policy (§16), pipeline (§14), engine (§13.1, §22) | 64 |
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
- **No Room.** `:core:database` uses plain entities, DAO interfaces and an
  in-memory implementation; the §13 rules live in `TransferRepository` above the
  DAO layer so Room can be dropped in without behaviour changing — ADR-0002.
- **No Compose shell, no `:app`, no `feature/*`.** Deferred to v0.2 — ADR-0003.
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

1. **Hash resume after process death** — if acknowledged chunks were already
   deleted, a resumed upload cannot recompute the destination-native hash and
   falls back down the §21 ordering. Block-structured algorithms could persist
   per-block digests in `CacheChunkEntity.hash`. ADR-0007.
2. **Name legality is partial** — trailing dots/spaces and maximum path length
   need capability flags that §5 does not define. ADR-0013.
3. **The pipeline is sequential per chunk** — correct but not yet overlapping
   download and upload in wall-clock terms. ADR-0017; §18 says measure first.
4. **`content_hash` is validated against independently computed vectors, not
   against live Dropbox responses.** Spec §36 requires the latter before
   shipping the adapter.

## What v0.2 (Dropbox adapter) needs from you

Two things need your account, not code:

1. **Dropbox App Console registration.** Create an app at
   https://www.dropbox.com/developers/apps and tell me:
   - the **app key** (client ID). This is a public identifier and may live in the
     repository; I will not invent a placeholder that looks real.
   - whether the app is **scoped access** with the **full Dropbox** or **app
     folder** access type. Full Dropbox is needed for CloudLug to be useful as a
     source; app folder would restrict transfers to a single folder.
   - confirmation that these scopes are enabled: `files.metadata.read`,
     `files.content.read`, `files.content.write`, `account_info.read` (spec
     §8.1).
   - **Do not send me the app secret.** CloudLug uses PKCE and must never embed a
     client secret in the APK.
2. **Redirect URI.** Pick one and register it in the App Console. I suggest the
   custom scheme `dev.thiagosindra.cloudlug://oauth/dropbox`, validated against
   the pending PKCE state (spec §8.4). If you would rather use verified App
   Links, I need the domain you control and the ability to host
   `assetlinks.json` on it.

Also useful, though I can proceed without them:

- A **test Dropbox account** with a few gigabytes of junk data — deep trees, many
  small files, a file over 4 GiB, names with awkward characters. Please do not
  send credentials; a description of the tree is enough for me to mirror it in
  the fake provider.
- Your call on whether v0.2 should add Room at the same time. My recommendation
  is to land Room *first*, since it is a mechanical annotation pass while the
  DAO shapes are still fresh, and every later milestone builds on it.

For v0.3 (Google Drive destination) you will need a Google Cloud project with
OAuth credentials and the `drive.file` scope, but that can wait — and spec §36
asks that Google's scope classification and CASA tiering be re-confirmed before
any Drive code is written.
