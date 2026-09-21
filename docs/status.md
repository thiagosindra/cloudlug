# Status — v0.3 (the Dropbox adapter)

What exists, what is compiled, what is verified — and, for the first time in
this project, what has been verified **against a real cloud account** rather
than against a fake that agrees with us.
Milestone definitions are in spec §33; design decisions are in
[`decisions.md`](decisions.md), where every ADR carries a Status line recording
whether the spec ratified or overruled it.

Spec is **v1.3**. ADRs 0019–0024 from the v0.2 report were all ratified into it;
ADRs 0027 and 0028 from this milestone are accepted pending ratification, and
[`spec-proposals/v1.4.md`](spec-proposals/v1.4.md) holds the spec-ready wording
for the nine amendments this milestone produced.

Spec v1.2 resequenced §33. Room and the Compose shell are **v0.2**; the Dropbox
adapter is **v0.3** and Google Drive **v0.4**. Earlier revisions of this file
numbered these one lower.

## How to verify

```bash
./gradlew build                      # every module, including :app — needs the Android SDK
./gradlew test                       # the JVM modules only
./gradlew :app:connectedDebugAndroidTest         # the §24 journeys — needs an emulator or device
./gradlew :core:security:connectedDebugAndroidTest   # §8.3, which only exists on a device
```

Nothing above touches the network or needs a credential. The two things that do
are manual workflows in the Actions tab — `Validate Dropbox content_hash` (§36)
and `Dropbox live contract tests` (§31.2) — each reading
`DROPBOX_REFRESH_TOKEN` from a repository secret and writing only beneath
`DROPBOX_TEST_ROOT`.

**"Green" means both CI jobs plus the emulator job**, not `./gradlew build`
alone.

JDK 17 or newer. `build` needs an Android SDK with platform 36; the JVM modules
still need none, which the `jvm` CI job proves by naming them explicitly.

## What exists

| Module | What it contains | Tests |
|---|---|---|
| `:core:model` | Identifiers, `CloudPath`, `ProviderHash`/`HashAlgorithm`, `HashCheckpoint`, transfer and item states, network policy | 17 |
| `:providers:api` | `CloudProvider` and `ProviderCapabilities` (§5), `CloudObject` (§6), `CloudAccount` (§7), `CloudSelection` with display paths (§9), upload/download types | 7 |
| `:core:database` | §12 entities, **Room** implementation and exported schema, §13 state machines, §15.3 chunk lifecycle, `TransferRepository`, in-memory store as the test double | 77 |
| `:core:hashing` | Checkpointable single-pass SHA-256 + destination-native hash (§19.4); hand-written SHA-256, SHA-1, MD5 and block-list SHA-256 | 28 |
| `:core:storage` | Cache budget and emergency reserve (§15, §15.1), `filesDir` chunk store with orphan purging (§15.2) | 19 |
| `:core:transfer` | Manifest builder (§10, §11, §20), collision algorithm (§19.3), retry policy (§23), verification (§21), network policy (§16), pipeline (§14), engine (§13.1, §22), `TransferController` (§35) | 79 |
| `:providers:fake` | `FakeCloudProvider` with the §31.3 failure injections, including per-chunk read and upload delays; the §31.2 contract suite in test fixtures | 55 |
| `:core:network` | The shared OkHttp stack and §26's redaction interceptor: a deny-by-default header allow-list, and no branch that can print a body | 9 |
| `:providers:dropbox` | The adapter (§5 surface, §23 mapping, §22.5 offset recovery), PKCE and the OAuth forms (§8.1), the token endpoint, and §8.3's token source | 74 |
| `:core:security` | `SecretStore` and the Keystore-backed AES-GCM implementation (§8.3) | 11 instrumented |
| `:core:auth` | §8.1's Custom Tab flow over AppAuth, the pending-attempt store, and §24.5's account records | 14 |
| `:feature:accounts` | §24.5: connect, disconnect, and §7's granted scopes | — |
| `:core:ui` | Material 3 theme, the §24.3 hop indicator, progress and status components, byte/count formatting | — |
| `:feature:home` | §24.1: active transfers and history | — |
| `:feature:new-transfer` | §24.2: the six-step wizard, including the review step | — |
| `:feature:transfer-details` | §24.3: live progress, current file, per-item outcomes, §22 controls | — |
| `:app` | `MainActivity`, navigation, Hilt graph: Room (opened here — ADR-0025), `filesDir` cache, ConnectivityManager, StatFs, the **real Dropbox provider**, two fakes for the demo and for Drive, debug crash reporter | 5 instrumented |
| `:tools:*` | Not shipped: the §36 `content_hash` harness and the `dropbox-auth` CLI that mints a refresh token | 2 |

**384 JVM tests, 0 failures**, of which 20 are the live Dropbox tests and skip
without a credential — so **364 run hermetically**, on any machine, with no
network. Plus **16 instrumented tests** on an emulator in CI: 5 in `:app` and 11
in `:core:security`. `allWarningsAsErrors` is on everywhere.

## What is verified, and on what

**v0.2 shipped an APK that could not start.** It crashed on launch on Android 16
with `NoSuchMethodError`, because `:core:database` — a Kotlin/JVM module —
called Room's JVM-only `databaseBuilder`, which does not exist in the Android
artifact. 271 JVM tests passed and both CI jobs were green. The section this
replaces said plainly that nothing had run on a device, and listed "whether the
Hilt graph constructs" and "whether `BundledSQLiteDriver` opens a database in an
app data directory" as the first things to check. Both were precisely what
broke. Writing a risk down is not the same as testing it — ADR-0025.

**Verified by JVM test (273, 0 failures).** Everything in `:core:*` and
`:providers:*`: the engine, the state machines, the hash pipeline including
checkpoint-and-resume across simulated process death, cache accounting, the
collision algorithm, and Room's transactional rollback against real SQLite —
held to one contract shared with the in-memory store.
`NoPlatformSpecificRoomApiTest` now fails the build if this module's main source
set names a Room construction API again.

**Verified on an emulator, in CI.** `FirstRunSmokeTest` launches `MainActivity`
against the real `CloudLugApplication`, so Hilt builds the real graph and Room
opens the real database, then asserts the app reaches RESUMED and that all four
of Room's tables exist when read back through a second connection. This is the
exact path that crashed. It asserts the schema rather than the file's size
because Room journals in WAL mode on Android: a newly created schema lives in
`cloudlug.db-wal` and the main file stays zero bytes until a checkpoint, so the
first version of this assertion failed against a perfectly healthy app.

**Verified against the built artifact.** The debug APK's only
`RoomDatabase$Builder` constructor references are the Android ones; the crashing
three-argument signature appears in no dex. `DebugCrashReporter` is in the debug
APK and absent from release.

**Verified on an emulator, in CI.** `NewTransferJourneyTest` drives the §24.2
wizard the way a person does — home screen, both accounts, pick `photos`, pick
`My Drive`, review, start — clicking real rows and reading real text rather than
reaching for the ViewModel. It then follows the app to §24.3 and asserts the
transfer **completes with every item verified at the destination** (§21): 7
files, 11.3 MB. That is the first time the engine has been shown running to
completion inside the app rather than in a JVM test.

**Why it exists.** v0.2.1 shipped a wizard whose picker was empty on a device
(ADR-0026) while every check was green. `FirstRunSmokeTest` proved the app
starts; nothing proved it works, and the difference was a transfer that could
not be created at all.

**One thing it found on the way.** `WizardStep.STARTED` and its "Transfer
started." text can never render: `MainActivity.onStarted` navigates to the
detail screen and pops `NEW_TRANSFER`, so the wizard is gone before that state
could be drawn. Dead UI, left in place rather than removed in a test-fixing
commit.

**Verified on an emulator, in CI.** `AccountsScreenTest` opens §24.5 from the
home screen and asserts each provider is described truthfully: Dropbox
connectable, Google Drive named as not yet supported rather than given a button
that cannot work, and the demo provider absent, since it has no account to
connect. It stops where a real sign-in would begin.

**Still not verified.** No test asserts that anything *renders correctly* —
that text is legible, that nothing is clipped, that the §24.3 hop indicator
reads as intended. The journey tests prove the flows are reachable and that the
engine runs inside the app, not that the result looks right. Nobody has
completed a real Dropbox sign-in in the app.

## Real Dropbox, or only the fake?

This is the question v0.3 exists to answer honestly, so it gets its own
section. A fake provider agrees with whatever the adapter believes; the whole
risk of an adapter milestone is a misreading that both sides of our own code
share.

### Run against real Dropbox

- **`content_hash`, all four §36 cases** (2026-09-20). One byte, exactly one
  4 MiB block, ~10 MiB of random data spanning three blocks, and an existing
  file named by path. `:core:hashing`'s block-list SHA-256 matched Dropbox's
  own `content_hash` in every case. This ran first, before any adapter code,
  and had it disagreed the rest of the milestone would have been built on a
  wrong hash.
- **OAuth 2 with PKCE, end to end.** `tools/dropbox-auth` drove the real
  authorize URL, a real consent screen, a real code exchange and a real refresh
  token, using the same `DropboxOAuth` the app uses. The refresh token it
  produced is what the live tests now run on.
- **The §8.3 refresh path.** `DropboxCredentialsLiveTest` exchanges the stored
  refresh token and calls `get_current_account` with the result.
- **The §31.2 contract suite, all 19 tests**, through the manual
  `Dropbox live contract tests` workflow: authentication, paged enumeration,
  one-level listing, quota, download and range download, folder creation,
  chunked upload and resume, lookup including multi-match, metadata, hashing
  and abort. The same suite the fake passes, unweakened.
- **One §23 error body, captured from a real failure.** The
  `insufficient_space` fixture is the body of a failed run, its session id
  pseudonymized, and it corrected
  a reconstruction of mine that had the union nested when Dropbox flattens it.

### Only against the fake, or only against MockWebServer

- **The transfer engine driving Dropbox.** Every §22 behaviour — pause, resume,
  cancel one file mid-upload, retry — is covered against the fake with the
  §31.3 delay injections, and against Dropbox not at all. The contract suite
  checks the adapter's surface, not a transfer running through it.
- **Recovery after process death with a real account.** §31.4's scenarios run
  against the fake only.
- **The §24.5 screen with a connected account.** The emulator test covers the
  screen up to the point where a Custom Tab would open; a real sign-in needs a
  browser and someone's password, which do not belong in CI. Nobody has yet
  connected Dropbox in the app and watched the row fill in.
- **A transfer that actually moves bytes between two real accounts.** This has
  never happened. It cannot until v0.4 gives Dropbox somewhere to send them, or
  until a second Dropbox account is connected — which the adapter cannot do yet
  (see the gaps below).
- **Rate limits, throttling and `Retry-After`.** The §23 mapping is tested
  against synthetic bodies. No real 429 has been seen.
- **Large files, deep trees, awkward names.** The live suite uses small
  fixtures. Nothing has been run against a 4 GiB file or a 50,000-file tree.

## Reconciled to spec v1.2 (the previous PR)

Six ADRs were overruled and the code changed to match: checkpointable hashers
(0007), split counters and `COMPLETED_WITH_ISSUES` (0008), the enumeration
resume token (0006), `enumerate` losing `suspend` (0015), name-legality
capabilities (0013), and selection-carried display paths (0014). The enclosing
folder moved to `READY -> RUNNING` (§10), and size-unknown items got their own
counter (§11).

## Known gaps

1. ~~**§31.3's slow-source and slow-destination injections.**~~ Landed in
   v0.3 Step 0: `FakeCloudProvider.readDelay` and `uploadChunkDelay` make a
   file take virtual time, so `MidFileInterruptionTest` pauses, resumes and
   cancels **partway through an object** rather than at a state boundary.

   They found a real §22.2 defect on their first run. `cancelItem` aborts the
   item's upload session while the worker is still mid-file, so the worker's
   next call threw `UploadSessionRestartException` past both of `run()`'s
   handlers, killed the transfer and left every remaining item `PENDING` with
   the transfer stuck in `RUNNING`. Cancelling one file stopped all of them.
   Unreachable without a slow source, which is exactly why v1.3 made these
   injections normative.
2. **Dropbox enumeration re-walks the whole tree after process death.** §11
   resumes from the last object id the caller persisted, and the Dropbox
   adapter honours that by starting the walk again and discarding entries until
   it passes the anchor. Correct — §5 says an adapter that cannot resume from
   an object id may restart, because the manifest deduplicates by source object
   id — but not free.

   The cost is user-facing. A 50,000-file tree is roughly a hundred
   `list_folder` pages that emit nothing, paid in full on every resume, against
   the same rate limit budget as useful work. On a phone that woke, resumed and
   was killed again, it is paid repeatedly.

   Dropbox's own `list_folder` cursor is built for this, and persisting it would
   make a resume O(remaining) instead of O(tree). It cannot simply be threaded
   through the contract: ADR-0015 removed `CloudSelection.resumeCursor`
   deliberately, and re-adding opaque provider state would reverse that. The
   adapter could instead keep a private cursor cache keyed by the resume anchor,
   leaving the contract's object-id resume as the only thing the engine knows
   about, and falling back to a full walk whenever Dropbox invalidates the
   cursor. **v0.5 recovery hardening**, alongside §31.4.

   Writing this up found a real defect, now fixed and covered by the §31.2
   contract suite: if the resume anchor no longer existed — deleted at the
   source, or moved out of the selection — both providers skipped until an id
   that would never arrive and emitted **nothing**, producing a manifest that
   looked complete with no work in it. Silent, and indistinguishable from
   success. Both now fall back to a full walk.

3. **No background execution.** §17's UIDT on API 34+ and the WorkManager
   fallback below it are not implemented. A transfer runs in an
   application-scoped coroutine and dies with the process; the database makes
   that recoverable, but the OS may stop a long transfer. v0.5.
4. **No notification.** §24.4 specifies an ongoing notification with progress
   and controls. It arrives with the background work it belongs to.
5. **The pipeline is sequential per chunk** — correct but not overlapping
   download and upload in wall-clock terms. ADR-0017; §18 says measure first.
6. ~~**`content_hash` is validated against independently computed vectors and
   against the JDK, not against live Dropbox responses.**~~ Done, 2026-09-20:
   all four §36 cases matched. See above.
7. **One Dropbox account per install.** `AccountId`'s own documentation says
   multiple accounts per provider are supported, and the engine is ready for
   it — every `CloudProvider` method takes an `AccountId`. The gap is entirely
   in this adapter: `DropboxTokenSource` has no account parameter, so one
   process holds one Dropbox credential, and `KeystoreRefreshTokens` stores it
   under a single fixed key.

   This is not hypothetical. Dropbox-to-Dropbox between two of a user's own
   accounts is a plausible first thing to want, and it is the one shape of
   transfer this build cannot express. The fix is to thread `AccountId` through
   the token source and key the credential by it; `authenticate()` is the only
   call without one, and the grant already carries `account_id` in its token
   response, so it need not be invented. **v0.4**, because Google Drive wants
   exactly the same change and doing it twice would be the waste.
8. **Nothing retries a refresh that fails transiently.** An `AUTH_REQUIRED`
   correctly stops and asks the user to reconnect, but a refresh that fails
   because the network dropped surfaces to the transfer as an auth error rather
   than as §23's transient case.
9. **No Google Drive.** `:providers:google-drive` is still a stub carrying TODOs
   naming the spec sections it must satisfy. v0.4.
10. **No Play assets.** §29 — v0.5.

## What v0.3 needed from you, and what came of it

All of it arrived. The app key `spv3k58wyxixvz4` is committed deliberately: it
is a public identifier, visible in every authorization URL a user ever sees. No
client secret exists in this repository, in the APK, or anywhere in my
possession — CloudLug is a public client and PKCE is the whole of its
protection (§8.1). The registered redirect is
`dev.thiagosindra.cloudlug://oauth/dropbox`, claimed by the manifest and
validated against the pending state on the way back (§8.4). Access type is full
Dropbox, and the four granted scopes are what §7 now displays on the accounts
row rather than what the code assumes.

Two things you did that are worth recording, because both were worth more than
the code they unblocked: you ran the §36 harness against a real account before
any adapter existed, and you pasted back the verbatim `insufficient_space`
error body from a failed run — which corrected a reconstruction of mine that
had the tagged union nested where Dropbox flattens it.

## What v0.4 (Google Drive) needs from you

Two things need your account rather than code, and one of them gates the
others. Registration is not instant, so they are worth starting before the code
is ready for them.

1. **Confirm Google's scope classification first — before any Drive code.**
   §36 asks for this explicitly, and it is the equivalent of what the
   `content_hash` check was for Dropbox: a misreading here is not a bug that a
   test catches, it is a milestone built on a wrong assumption. Specifically:
   whether `drive.file` is still classified as non-sensitive, what
   `drive.readonly` is classified as now, and whether a CASA security
   assessment applies at the tier CloudLug would be in. The answer decides
   whether Drive can be a **source** at all, or only a destination — which
   changes what v0.4 *is*.
2. **A Google Cloud project with an OAuth client** for Android, and the SHA-1 of
   the signing certificate the build uses. Drive's Android OAuth client is bound
   to the package name and certificate fingerprint rather than to a redirect
   URI, which is a different shape from Dropbox and will need its own connector.
   As with Dropbox: **no client secret**, and none is needed.
3. **The scope list the console shows as enabled**, stated back to me rather
   than the list requested. §7 now drives real behaviour — the accounts row
   says whether an account can be a source, a destination or neither — so a
   mismatch shows up as a row that refuses work rather than as a clear error.

Useful but not blocking:

- **Connect Dropbox in the app and tell me what you see.** Nobody has done this
  yet. The emulator test covers §24.5 up to the point where the Custom Tab
  opens; the tab itself, the consent screen, the return trip and the row
  filling in with your name and scopes have been exercised by no one. This is
  the v0.2 crash-report lesson again: the one thing tests structurally cannot
  do is be a person holding a phone.
- A **test Dropbox account with awkward data**: deep trees, many small files, a
  file over 4 GiB, names with trailing dots and non-ASCII characters. The live
  suite currently uses small fixtures, so the §20.4 name rules and the chunked
  upload path have never met anything difficult. Please do not send
  credentials; a description of the tree is enough for me to mirror it in the
  fake.
- **A second Dropbox account**, if you want Dropbox-to-Dropbox to be the first
  real end-to-end transfer rather than waiting for Drive. It needs gap 7 above
  fixed first, which is v0.4 work either way.
