# Design decisions

An ADR-style log of every place where `docs/spec.md` was ambiguous,
contradictory, silent on something the code had to decide, or impossible to
follow as written in the current environment. Each entry records the question,
the decision, and what would change it.

The point of this file is that nothing in the implementation diverges from the
specification silently. If you find code that contradicts the spec and is not
explained here, that is a bug in the code or in this file.

Every ADR carries a **Status** line. Spec v1.2 was written against this log and
settled all eighteen of the v0.1 entries: *Ratified* means the spec now says
what the ADR decided, so the reasoning below is history and the rule lives in
`docs/spec.md`; *Overruled* means v1.2 decided differently and the code has been
changed to match — the entry is kept so the change is traceable rather than
looking like drift; *Superseded* means the milestone the ADR described has
passed. Entries from ADR-0019 on are newer than v1.2.1 and are live decisions
again.

---

## ADR-0001 — Module layout follows spec §4, with one addition and several deferrals

**Status.** Ratified — v1.2.1 adds `core/hashing` to the §4 tree and names it as the home of the §19.4 pipeline.

**Question.** §4 fixes the repository structure. v0.1 does not implement all of
it, and the dual-hash pipeline (§19.4) has no obvious home in the listed
modules: it is not `core/model` (it has behaviour), not `core/storage` (it is
not about disk), and it cannot live in `core/transfer`, which must not name any
provider — yet one of the algorithms is specified only by Dropbox.

**Decision.** Add `core/hashing`, which owns `StreamingHasher`, the algorithm
implementations, and `DualHashPipeline`. The engine selects an algorithm through
`ProviderCapabilities.nativeHashAlgorithm`, never by naming a provider, so
`core:hashing` can contain a block-list SHA-256 implementation (what Dropbox
calls `content_hash`) without `core:transfer` knowing that Dropbox exists.

Deferred to later milestones, and absent for now: `core/network` (the OkHttp
stack arrives with the first adapter), `core/security` (Keystore credential
storage, v0.5), `core/ui`, `app/` and `feature/*` (see ADR-0003).

---

## ADR-0002 — v0.1 has no Room; DAO interfaces plus an in-memory store

**Status.** Superseded — §33 v0.2 lands Room behind these DAO interfaces. The entry stands as the record of why v0.1 shipped without it.

**Question.** §12 specifies a Room persistence model, and the milestone requires
transactional state transitions. Room is an Android/androidx library and its
artifacts are served only from Google's Maven repository, which is unreachable
from the environment this milestone was built in. A JDBC/SQLite stand-in was
considered and rejected: it would be thrown away, because Room's generated
schema and DAO shapes would differ from a hand-written one.

**Decision.** `:core:database` defines the §12 entities as plain Kotlin data
classes, the DAOs as interfaces shaped the way Room implements them (suspend
functions for writes, `Flow` for observation), and an in-memory implementation
used by tests and by the app until Room arrives.

Every rule about *which* writes are legal lives in `TransferRepository`, one
layer above the DAOs, so swapping the in-memory store for Room cannot change
behaviour. `InMemoryCloudLugDatabase.withTransaction` snapshots every table and
restores it if the block throws, so rollback is genuinely exercised rather than
assumed.

**What changes it.** The first task of v0.2: add `@Entity`, `@Dao` and the
TypeConverters for value classes, enums, `Instant`, `CloudPath` and
`ProviderHash`, and implement `CloudLugDatabase` over `RoomDatabase`. No
repository or engine code should need to change.

---

## ADR-0003 — v0.1 ships the JVM core only; the Android modules are deferred

**Status.** Superseded — §33 v0.2 lands `:app`, `core/ui` and `feature/*`.

**Question.** §4 and §24 call for `app/`, `core/ui` and `feature/*` built with
Compose, and §3 names Hilt and AppAuth. None of AGP, Compose, Hilt or androidx
can be resolved in this environment, so those modules could be written but never
compiled or run.

**Decision.** Ship only pure Kotlin/JVM modules in v0.1, so `./gradlew test`
works with no Android SDK and CI verifies something real. The Compose shell,
navigation and placeholder screens move to v0.2, where they can be compiled and
run against the fake provider rather than committed unverified.

**Consequence.** Nothing in the current tree depends on Android APIs. The engine
takes `NetworkMonitor`, `StorageMonitor`, `ProviderRegistry` and `ChunkStore` as
interfaces, which is what lets the Android layer supply ConnectivityManager,
StatFs, Hilt bindings and `filesDir` later without touching the engine.

---

## ADR-0004 — Reading the transfer state machine of §13.1

**Status.** Ratified — v1.2 §13.1 gives `PREPARING` the `WAITING_FOR_WIFI` and `AUTH_REQUIRED` edges for the reason given here. Point 3 now reads `COMPLETED_WITH_ISSUES`, which is what that state is called since v1.2.

**Question.** The §13.1 diagram hangs the waiting states off `RUNNING` only, and
does not say what follows a terminal state.

**Decision.**

1. `PREPARING` may also enter `WAITING_FOR_WIFI` and `AUTH_REQUIRED`.
   Enumeration needs the network and a valid token, and failing an entire
   transfer because Wi-Fi dropped mid-walk would contradict §2.4.
2. Waiting and paused transfers may be cancelled, and return to `RUNNING` when
   their condition clears (§16, §22.1).
3. `FAILED`, `CANCELLED` and `COMPLETED_WITH_ERRORS` may return to `PREPARING`,
   because "Retry incomplete files" (§22.4) re-enumerates. `COMPLETED` may not:
   there is nothing incomplete to retry.

---

## ADR-0005 — Reading the file state machine of §13.2

**Status.** Ratified — v1.2 §13.2 states outright that item state means "furthest stage reached", with §15.3 chunk states carrying the overlap.

**Question.** §13.2 draws one linear chain, but several specified behaviours do
not fit on it.

**Decision.**

1. **Manifest-time classification.** §20.1–§20.4 settle objects before any state
   change, so an item may be *created* in `SKIPPED_UNSUPPORTED` or `CONFLICT`.
   No other initial status is legal.
2. **Overlapped download and upload.** §14 overlaps chunk N+1's download with
   chunk N's upload, but the item chain stays
   `DOWNLOADING -> CACHED -> UPLOADING` as drawn. The item status means "furthest
   stage reached"; per-chunk truth during the overlap is carried by
   `CacheChunkStatus` (§15.3) and the item's `uploadedBytes`. This keeps history
   comparable and avoids inventing a state §13.2 does not have.
3. **`SOURCE_CHANGED` from `DOWNLOADING`.** §20.6 compares revisions before
   opening the stream, but some providers only reveal the revision on the
   response itself, so the transition is legal from both states.
4. **`UPLOADING -> CACHED`.** An expired upload session restarts from cached
   chunks rather than from a partially written destination object (§22.5).
5. **Recovery after process death.** `CHECKING_DESTINATION` and `DOWNLOADING`
   re-queue to `PENDING`; `UPLOADING` drops to `CACHED` so the session is
   re-queried before anything is sent; `VERIFYING` simply repeats, being a
   metadata comparison.
6. **Retry.** `FAILED`, `CANCELLED`, `SOURCE_CHANGED` and `CONFLICT` return to
   `PENDING` — `CONFLICT` because §13.2 says the user may retry after removing
   the destination object. `COMPLETED` and the `SKIPPED_*` states do not:
   retrying them would duplicate completed work or re-decide something the
   manifest already settled.

---

## ADR-0006 — The enumeration cursor is an opaque resume token

**Status.** Overruled — v1.2 §5 and §11 make the resume token the last emitted object's ID, passed as `enumerate(resumeAfter = ...)`, rather than an opaque cursor on the selection. `CloudSelection.resumeCursor` is gone.

**Question.** §11 and §12.1 require resumable enumeration with a persisted page
cursor, but the §5 signature is `enumerate(...): Flow<CloudObject>`, which
carries no cursor back to the caller.

**Decision.** Keep the §5 signature unchanged. `CloudSelection` gains a
`resumeCursor`, and the engine persists the last emitted object's opaque ID as
the cursor in the same transaction as the page it belongs to. An adapter that
cannot resume from a cursor may restart enumeration; `appendManifestItems`
deduplicates by source object ID, so restarting is correct, only slower.

---

## ADR-0007 — Hash pipeline state is not persisted across process death

**Status.** Overruled — v1.2 §19.4 requires checkpointable hashers, and §21 removed the permissive fallback this ADR relied on, so degrading after process death would now mean the item cannot complete at all. SHA-256, SHA-1 and MD5 are hand-written with serializable state; see ADR-0019.

**Question.** §19.4 computes both hashes in one streaming pass and forbids
re-reading a multi-gigabyte cached object merely to hash it. A `MessageDigest`'s
internal state cannot be serialised, so an item whose upload resumes in a new
process has no partial hash to restore.

**Decision.** Do not attempt to persist hasher state. On resume, the engine
re-feeds the cached chunks it still holds; if acknowledged chunks have already
been deleted, the destination-native hash is unavailable and verification falls
back down the §21 ordering — `resolveMetadata`, then the size-only path where
capabilities permit it. This never weakens §32.3: an item that cannot be
verified does not reach `COMPLETED`.

**What would change it.** Block-structured algorithms (Dropbox `content_hash`)
*can* be resumed by persisting each block digest in `CacheChunkEntity.hash`,
which §12.3 already provides for. Worth doing in v0.4 if real transfers show
resumed uploads losing verification.

---

## ADR-0008 — Counter semantics §12.1 leaves undefined

**Status.** Overruled — v1.2 §12.1 splits `skippedFiles` into `duplicateFiles`, `unsupportedFiles` and `sourceChangedFiles` and adds `unknownSizeFiles`, and §13.1 counts only `COMPLETED` and `SKIPPED_DUPLICATE` as success. The last bullet below is exactly what v1.2 reversed: skipped items alone *do* now make a transfer `COMPLETED_WITH_ISSUES`.

**Question.** §12.1 names counters but does not define what each counts, and
§13.2 adds `SOURCE_CHANGED`, which has no counter at all.

**Decision.**

- `totalFiles` counts every non-folder manifest item, including ones already
  classified unsupported, so progress denominators do not shift mid-transfer.
- `totalBytes` sums reported sizes; objects with no size (§20.1) contribute
  nothing.
- `skippedFiles` covers `SKIPPED_DUPLICATE`, `SKIPPED_UNSUPPORTED` and
  `SOURCE_CHANGED`: none moved bytes, and none is a failure the user must act on
  immediately.
- A transfer ends `COMPLETED_WITH_ERRORS` when `failedFiles`, `conflictFiles` or
  `cancelledFiles` is non-zero, and `COMPLETED` otherwise. Skipped items alone
  do not make a transfer an error.

---

## ADR-0009 — Order of operations in the §15 cache budget

**Status.** Ratified — v1.2 §15 states the order normatively: reserve first, then halve, then cap.

**Question.** §15 defines `cacheBudget = min(usableStorage * 0.50,
configuredMaximumCache)` before §15.1 defines `usableStorage = freeSpace -
max(1 GiB, totalDeviceStorage * 0.05)`. Read in the other order the reserve
would be subtracted after halving, yielding a larger cache.

**Decision.** Subtract the emergency reserve first, then halve what remains,
then cap at the configured maximum. This is the conservative reading and matches
§2.5.

---

## ADR-0010 — One waiting state for all connectivity holds

**Status.** Ratified — recorded in the v1.2.1 changelog without a text change.

**Question.** §13.1 provides `WAITING_FOR_WIFI`, but a transfer can also be
holding because there is no network at all, including under `ANY_NETWORK`.

**Decision.** Both map to `WAITING_FOR_WIFI`: they clear the same way, when an
allowed network returns. The notification text (§24.4) explains the actual
reason; the state machine does not need to distinguish them.

---

## ADR-0011 — §21 step 3 is strict

**Status.** Ratified — v1.2 §21 says a provider declaring `supportsServerHash` that returns no hash leaves the item unverifiable, and unverifiable items do not complete.

**Question.** §21 step 3 allows "size match plus provider integrity signal", but
does not say what counts as an integrity signal, and step 4 permits metadata-only
verification exclusively where `supportsServerHash = false`.

**Decision.** A provider that declares `supportsServerHash = true` and then
reports no hash for an object has given no integrity signal, so verification
reports *unverifiable* and the item does not complete. §21 states outright that a
successful upload response is not sufficient, so accepting a size match there
would contradict it, and §32.3 makes `COMPLETED` a claim about verification.

---

## ADR-0012 — Enclosing-folder name collisions

**Status.** Ratified — v1.2 §10 specifies the numeric suffix for two transfers created in the same minute.

**Question.** §10 requires that independent transfers not silently merge into
one enclosing folder, but the name is derived from the date and minute, so two
transfers started in the same minute would collide.

**Decision.** If the name is taken at the destination, append ` (2)`, ` (3)` and
so on until it is free. This applies to the enclosing folder only; individual
items are still never auto-renamed (§19.3), because renaming a *file* changes
what the user reviewed, while the enclosing folder is CloudLug's own container.

---

## ADR-0013 — Name legality is incomplete until a real adapter needs more

**Status.** Overruled — v1.2 §5 adds `disallowsTrailingSpaceOrDot` and `maxPathLength`, which is what this ADR was waiting for. Both rules are enforced now; the TODO is gone.

**Question.** §20.4 says providers differ on illegal characters, trailing spaces
and dots, and maximum name *and path* length. `ProviderCapabilities` as
specified in §5 carries `illegalNameCharacters` and `maxNameLength` only.

**Decision.** Implement exactly what capabilities express: blank names, `.`,
`..`, illegal characters and over-long names are conflicts. Trailing-dot,
trailing-space and path-length rules need new capability flags, which are better
added alongside the first adapter that actually has such a rule than invented
now. Marked with a TODO in `DestinationNameLegality`.

---

## ADR-0014 — Where a selected root lands in the destination tree

**Status.** Overruled — v1.2 §9 has the selection carry each root's destination-relative display path, so the path is data travelling with the selection rather than a `rootPathResolver` callback handed to the builder. The reasoning about *why* the picker is the only component that knows the path survives intact.

**Question.** §10's example maps a selection of `/photos/2026/April` to
`photos/2026/April` at the destination — the source's ancestors are preserved —
but `CloudObject` deliberately carries identity rather than a path (§6), so the
engine cannot derive those ancestors from the selection alone.

**Decision.** `ManifestBuilder` takes a `rootPathResolver` supplying each
selected root's destination-relative path, defaulting to the root's own name.
The picker, which browsed to the object and therefore knows its display path,
supplies it. This keeps paths out of the identity model while still reproducing
§10's layout.

---

## ADR-0015 — `enumerate` keeps its `suspend` modifier

**Status.** Overruled — v1.2 §5 drops the redundant `suspend`, which is the revision this ADR said would be needed to justify removing it.

**Question.** §5 declares `suspend fun enumerate(...): Flow<CloudObject>`. The
`suspend` is redundant: returning a cold `Flow` does not suspend.

**Decision.** Keep the signature exactly as specified. The redundancy costs
nothing, and matching §5 character for character makes the provider API
reviewable against the spec. Worth removing only if the spec is revised.

---

## ADR-0016 — Two chunk transitions §15.3 does not draw

**Status.** Ratified — v1.2 §15.3 draws both failure edges.

**Question.** §15.3 draws
`ALLOCATED -> DOWNLOADING -> READY -> UPLOADING -> ACKNOWLEDGED -> DELETED`,
which has no way back when an attempt fails.

**Decision.** Allow `DOWNLOADING -> ALLOCATED` (a download attempt abandoned;
the range will be fetched again) and `UPLOADING -> READY` (an upload attempt
failed or its session expired; the same cached bytes will be sent again). Both
keep the bytes retained, which is what §32.4 requires; neither allows deletion
of anything the destination has not acknowledged.

---

## ADR-0017 — The v0.1 pipeline is sequential per chunk

**Status.** Ratified — recorded in the v1.2.1 changelog without a text change; §18 still says measure first.

**Question.** §14 overlaps a file's download and upload; §18 says to start
conservatively and measure before adding concurrency.

**Decision.** v0.1 runs one chunk at a time: read, hash, persist, upload, drop
once acknowledged. Every step already goes through the database, so introducing
a concurrent producer/consumer pair is a contained change once there are real
providers and real measurements to justify it (v0.4). The correctness properties
that matter — nothing deleted before acknowledgement, cache never over budget,
verification before completion — are already enforced and tested, and do not
depend on the loop's shape.

---

## ADR-0018 — Process death is not a provider error

**Status.** Ratified — v1.2 §31.3 separates process interruption from network failure and forbids routing it through the retry policy.

**Question.** §31.3 lists "process interruption" among the failures
`FakeCloudProvider` must simulate, alongside network errors, but the two demand
opposite handling: a network error should be retried and may end an item in
`FAILED`, while a killed process should leave the database untouched and be
recovered from on the next run.

**Decision.** `FakeCloudProvider.interruptProcess()` raises
`ProcessInterruptedException`, which is deliberately not a `CloudException`, so
the retry policy never sees it and no item is settled by it. The §31.4 crash
scenarios then test what they claim to: recovery through the authoritative
database, not error handling.

---

## ADR-0019 — The block-list hasher checkpoints in constant space

**Question.** §19.4 describes the checkpointable state of the Dropbox block hash
as "the list of completed block digests at 32 bytes per 4 MiB". Taken literally,
a 100 GiB object would carry roughly 800 KB of checkpoint, rewritten on every
chunk acknowledgment — into a column that §12.2 puts on the item row.

**Decision.** Do not retain the digest list. The outer hash is itself a
streaming SHA-256 over the concatenated block digests, so each block digest is
folded into it the moment the block closes. The checkpoint is then the outer
hasher's state plus the in-progress block's state: two Merkle-Damgard snapshots,
constant size, a few hundred bytes regardless of object size.

This produces byte-identical output — it is the same concatenation, absorbed
incrementally instead of buffered — and `CheckpointTest` pins that by hashing
across block boundaries and by showing the checkpoint does not grow over 32 MiB.

**What changes it.** Nothing in the algorithm. If a future provider defined a
block hash whose outer function needed the digests out of order, this would not
hold, and the flat list would be the only option.

---

## ADR-0020 — `READY -> RUNNING` is a method, not an implicit step

**Question.** v1.2 §10 attaches enclosing-folder creation to the
`READY -> RUNNING` transition. The engine had no way to perform that transition
on its own: `run()` transitioned and then processed items in one call, so
creating the folder "at the transition" had no seam to attach to, and nothing
could observe a started-but-not-yet-moving transfer.

**Decision.** Add `TransferEngine.start()`, which creates the folder and
performs the transition, and have `run()` call it when the transfer is not yet
`RUNNING`. Resuming a paused transfer therefore does not create a second folder,
and a caller that wants the two separated — the wizard's "Start transfer" button
in §24.2, and the tests that seed a destination object before any byte moves —
can have them.

The two steps also fail differently, which is the deeper reason to separate
them: §10 says a transfer whose folder cannot be created fails fast with zero
bytes moved, whereas a failure inside `run()` leaves a partially transferred
manifest to resume.

**What changes it.** If §10 were revised to create the folder lazily, at the
first item that needs a destination parent, `start()` would lose its only
responsibility and should be folded back into `run()`.
