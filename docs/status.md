# Status — v0.5 delivered (the transfer outlives the app)

What exists, what is compiled, what is verified — and what has been verified
**against a real cloud account or a real device** rather than against a fake
that agrees with us.

**v0.5 is background execution.** Before it, the engine ran in an
application-scoped coroutine started by a ViewModel, which made §17's "correctness
must never depend on any worker staying alive" false in the most direct way
available: the transfer died with the process, and the process dies whenever
Android wants the memory. A migration measured in hours could not survive the
user leaving the app.

It now runs where the platform can keep it alive: a User-Initiated Data Transfer
job on API 34+, a WorkManager worker with a `dataSync` foreground service on
26–33, both behind one three-method interface, with §16's network policy stated
as the job's own constraint and §24.4's notification carrying the direction,
progress, current file, and Pause and Cancel. Neither scheduler holds state —
recovery is `SchedulingPolicy` re-read over rows (§2.4) — and the in-app
`TransferController` the §31.3 journey drives is wrapped rather than replaced
(ADR-0031).

**Roadmap change, ratified before the work began:** v0.5 is background
execution and Google Drive moves to v0.6. Spec §33's table is amended in
[`spec-proposals/v1.5.md`](spec-proposals/v1.5.md) §9.

## What writing §31.4's first scenario found

§31.4's first four scenarios all begin "kill the process", and **none of them
had ever been run.** CloudLug's own `androidTest` cannot: instrumentation loads
into the process of the package it targets, so `am force-stop` from `:app`'s
tests takes the test down with the app. The JVM tests could not either — a
`TestScope` cannot be killed, and cancelling one *is* the pause case.

So pause stood in for process death everywhere. A pause unwinds: the coroutine
is cancelled, every `finally` runs, and the rows describe a transfer that
stopped tidily. Death leaves whatever happened to be written at the instant the
process went away. Four defects lived in that gap, and all four are the project's
recurring shape — a **handoff**, not a component (ADR-0032):

1. **Recovery put an interrupted item where the engine cannot start.**
   `recoveryStatusFor` sent `UPLOADING` to `CACHED` and left `VERIFYING` alone.
   A file begins at `CHECKING_DESTINATION`, which neither can reach, so the next
   run threw `IllegalItemTransitionException` and ended the whole transfer.
2. **The upload session outlived the process.** The resuming pass reads from
   byte zero — §19.4 computes both hashes in it — so its first chunk went to a
   session already holding bytes and came back `incorrect_offset`, which §23
   reads as `UPLOAD_SESSION_EXPIRED` and `RetryExecutor` raises as an exception
   nothing caught.
3. **A transfer stopped by the platform for want of Wi-Fi said it was running.**
   WorkManager enforces a constraint by cancelling the worker, which the engine
   cannot tell from a pause; the row still said `RUNNING`.
4. **And then it never resumed.** The worker reported success, the work was
   finished, and nothing re-applied the constraint — so §16's "resumes
   automatically" waited for someone to open the app.

**The assertion that was green for the first two** asserted that every recovery
target is a legal transition. `CACHED` is. It never asked whether the item could
*leave* again, which is the only thing a recovery target is for. That is the
third vacuous assertion of the same family this project has found, and
[`testing.md`](testing.md) rule 2 is about exactly it.

Milestone definitions are in spec §33; design decisions are in
[`decisions.md`](decisions.md), where every ADR carries a Status line recording
whether the spec ratified or overruled it. Standing rules about how things are
tested are in [`testing.md`](testing.md).

Spec is **v1.3**. ADRs 0019–0024 were ratified into it; ADRs 0027–0032 are
accepted pending ratification, and
[`spec-proposals/v1.5.md`](spec-proposals/v1.5.md) holds the spec-ready wording
for the nine amendments v0.4 and v0.5 produced — §2.2, §5, §13.1, §23, §24.2,
§4, §17, §13.2/§22.5 and §33.

## v0.4, for the record

Two Dropbox accounts connect from the app, §2.2 permits the pair, the wizard
picks accounts on both sides and browses each account's tree, and **files move
between two real Dropbox accounts on a real phone**. Three defects stood between
the milestone's code and that sentence, all three in a handoff: a recursive
`list_folder` whose response `ManifestBuilder` could not use (ADR-0029), an
`upload_session/finish` struct the adapter read as a union and turned into a
permanent failure (ADR-0030), and a picker that could not descend, so only an
account's top level was reachable at all.

## How to verify

```bash
./gradlew build                      # every module, including :app — needs the Android SDK
./gradlew test                       # the JVM modules only
./gradlew :app:connectedDebugAndroidTest         # the §24 journeys — needs an emulator or device
./gradlew :core:security:connectedDebugAndroidTest   # §8.3, which only exists on a device
./gradlew :tools:recovery-test:connectedDebugAndroidTest  # §31.4 and §16, from outside CloudLug's process
```

The last of those installs `:app` first, on purpose: it instruments *itself* so
it can force-stop CloudLug and live, and nothing else would put CloudLug on the
device.

Nothing above touches the network or needs a credential. The two things that do
are manual workflows in the Actions tab — `Validate Dropbox content_hash` (§36)
and `Dropbox live contract tests` (§31.2) — each reading
`DROPBOX_REFRESH_TOKEN` from a repository secret and writing only beneath
`DROPBOX_TEST_ROOT`.

**"Green" means both CI jobs plus the emulator job**, not `./gradlew build`
alone. The emulator job runs all three connected suites above.

That job is also what CI costs: roughly thirteen minutes of its twenty per
run, nearly all of it booting an AVD. The workflow therefore runs once per
commit rather than twice — `push` is restricted to `main`, and a branch's
checks come from its pull request — and `concurrency` cancels a run the
moment a newer commit supersedes it. Both were added after a single evening
of iteration exhausted the account's monthly Actions minutes, most of it
spent on superseded commits and on running everything twice.

JDK 17 or newer. `build` needs an Android SDK with platform 36; the JVM modules
still need none, which the `jvm` CI job proves by naming them explicitly.

## What exists

| Module | What it contains | Tests |
|---|---|---|
| `:core:model` | Identifiers, `CloudPath`, `ProviderHash`/`HashAlgorithm`, `HashCheckpoint`, transfer and item states, network policy | 17 |
| `:providers:api` | `CloudProvider` and `ProviderCapabilities` (§5), `CloudObject` (§6), `CloudAccount` (§7), `CloudSelection` with display paths (§9), upload/download types | 7 |
| `:core:database` | §12 entities, **Room** implementation and exported schema, §13 state machines, §15.3 chunk lifecycle, `TransferRepository`, in-memory store as the test double | 80 |
| `:core:hashing` | Checkpointable single-pass SHA-256 + destination-native hash (§19.4); hand-written SHA-256, SHA-1, MD5 and block-list SHA-256 | 28 |
| `:core:storage` | Cache budget and emergency reserve (§15, §15.1), `filesDir` chunk store with orphan purging (§15.2) | 19 |
| `:core:transfer` | Manifest builder (§10, §11, §20), collision algorithm (§19.3), retry policy (§23), verification (§21), network policy (§16), pipeline (§14), engine (§13.1, §22), `TransferController` (§35), **`SchedulingPolicy`** — which transfers have work left and what network each needs (§16, §17) | 95 |
| `:providers:fake` | `FakeCloudProvider` with the §31.3 failure injections, including per-chunk read and upload delays; the §31.2 contract suite in test fixtures | 59 |
| `:core:network` | The shared OkHttp stack and §26's redaction interceptor: a deny-by-default header allow-list, and no branch that can print a body | 9 |
| `:providers:dropbox` | The adapter (§5 surface, §23 mapping, §22.5 offset recovery), PKCE and the OAuth forms (§8.1), the token endpoint, and §8.3's token source | 95 |
| `:core:security` | `SecretStore` and the Keystore-backed AES-GCM implementation (§8.3) | 11 instrumented |
| `:core:scheduling` | §17: the two platform schedulers, `TransferRunner`, §24.4's notification with its Pause and Cancel actions, the boot receiver | — |
| `:core:auth` | §8.1's Custom Tab flow over AppAuth, the pending-attempt store, and §24.5's account records | 22 |
| `:feature:accounts` | §24.5: connect, disconnect, and §7's granted scopes | — |
| `:core:ui` | Material 3 theme, the §24.3 hop indicator, progress and status components, byte/count formatting | 4 |
| `:feature:home` | §24.1: active transfers and history | — |
| `:feature:new-transfer` | §24.2: the six-step wizard, including the review step | 6 |
| `:feature:transfer-details` | §24.3: live progress, current file, per-item outcomes, §22 controls | — |
| `:app` | `MainActivity`, navigation, WorkManager's configuration and JobScheduler id range, Hilt graph: Room (opened here — ADR-0025), `filesDir` cache, ConnectivityManager, StatFs, the **real Dropbox provider**, two fakes for the demo and for Drive, debug crash reporter | 10 instrumented |
| `:tools:*` | Not shipped: the §36 `content_hash` harness, the `dropbox-auth` CLI that mints a refresh token, the §31.2 fixture capture, and `recovery-test` — an empty application that instruments itself so it can force-stop CloudLug and survive | 9 + 3 instrumented |

**450 JVM tests, 0 failures**, of which 22 are the live Dropbox tests and skip
without a credential — so **428 run hermetically**, on any machine, with no
network. Plus **24 instrumented tests** on an emulator in CI: 10 in `:app`, 11
in `:core:security` and 3 in `:tools:recovery-test`, all passing.
`allWarningsAsErrors` is on everywhere.

## What is verified, and on what

**v0.2 shipped an APK that could not start.** It crashed on launch on Android 16
with `NoSuchMethodError`, because `:core:database` — a Kotlin/JVM module —
called Room's JVM-only `databaseBuilder`, which does not exist in the Android
artifact. 271 JVM tests passed and both CI jobs were green. The section this
replaces said plainly that nothing had run on a device, and listed "whether the
Hilt graph constructs" and "whether `BundledSQLiteDriver` opens a database in an
app data directory" as the first things to check. Both were precisely what
broke. Writing a risk down is not the same as testing it — ADR-0025.

**Verified on an emulator, in CI, from outside CloudLug's own process —
§31.4's first scenario, run for real.** `:tools:recovery-test` is an empty
application that instruments itself, which is the only way to force-stop
CloudLug and live to assert what happens next. It walks the §24.2 wizard,
waits until §24.3 says a file is genuinely moving, `am force-stop`s CloudLug,
confirms with `pidof` that the process is gone, relaunches, and asserts the
transfer finishes with nothing to report. That is the sentence v0.5 exists to
make true, and until 2026-09-24 nothing had ever tested it: instrumentation
loads into the process of the package it targets, so `:app`'s own tests cannot
kill CloudLug without dying with it.

Its sibling takes Wi-Fi away mid-transfer under UNMETERED_ONLY and asserts
three things §16 and §24.4 promise: the transfer parks and §24.3 names the
condition, the notification shade says the same thing, and putting Wi-Fi back
is enough — nothing is pressed. All three were false before v0.5.

Three tests, 0 skipped, 0 failed, alongside `:app`'s ten and
`:core:security`'s eleven.

**Getting there found a fifth defect, and it was in the app.** Both scenarios
stopped at their first action — clicking §24.1's "New Transfer" — with the
rest of that screen plainly composed. The harness prints the exported
accessibility tree on failure, and it settled the question outright:

    android.view.View text="" desc="" clickable=true visible=true [168,568][304,624]

The button is exported, clickable, visible and correctly placed, and carries
**no label at all**. Not culled — unlabelled. A FAB merges its descendants and
the merged node arrived with neither text nor a description, so TalkBack
announced CloudLug's primary action as an anonymous button. `Accounts` in the
same bar escapes it only because a TextButton publishes its child TextView as
a node of its own.

Fourth defect of the milestone to live in a handoff rather than a component.
The button drew correctly, `:app`'s journey clicked it, and the semantics tree
those tests read had the text all along; only the export dropped it, and
nothing looked there until a test had to drive the app from another process —
which is also what a screen reader does.

**Not verified: the UIDT branch.** CI's emulator is API 30, so everything
above runs against WorkManager. API 34+ is compiled and lint-clean and has
never executed. See known gap 3.

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

**Verified on a real device, and it failed.** v0.3 was green on all three CI
jobs and crashed on a Samsung phone running Android 16 the instant the Dropbox
consent screen handed control back:

    java.lang.IllegalStateException: You need to use a Theme.AppCompat theme
    (or descendant) with this activity.
      at net.openid.appauth.RedirectUriReceiverActivity.onCreate

AppAuth's redirect receiver is an `AppCompatActivity` and inherited
`Theme.CloudLug`, which descends from the platform's Material theme. The crash
was in `onCreate`, before the redirect was read — so nothing about the code
that *handles* a redirect was wrong, and no amount of testing that code would
have found it. Only starting the activity does, and nothing ever had.

That is the v0.2 lesson for the second time: `FirstRunSmokeTest` proved the app
starts, the journey test proved a transfer runs, `AccountsScreenTest` proved the
accounts screen draws — and the one step none of them took was the one that
broke. The debug crash reporter earned its keep again.

**Verified on an emulator, in CI.** `OAuthRedirectTest` now delivers the
redirect straight to AppAuth's receiver, as the browser would, and asserts the
activity starts and leaves the app standing — once with a pending attempt and
once with none. Driving a real browser in CI is not possible and a person's
password does not belong in an emulator, but the leg that crashed is now
covered.

**Not verified: the no-browser path.** v0.3.1 also fixed a second crash in the
same leg — `authorizationIntent()` throws `ActivityNotFoundException` when the
device has no browser, and the screen called it from the button's `onClick`,
where nothing catches it. The test for it *skips* on the CI emulator, which
turns out to have something that answers a browsable `https` intent:

    AccountsScreenTest > connecting_without_a_browser_explains_itself_instead_of_crashing SKIPPED

So the message a browser-less device would see is reasoned about, not observed.
Covering it deterministically means testing the ViewModel rather than the
device — turning `NoBrowserAvailableException` into a message is CloudLug's
logic, while whether AppAuth can find a browser is not — and that needs a seam
`AccountsViewModel` does not have: it takes a concrete `TransferController`
(for §24.5's "this will stop N transfers" count) which cannot be stood in for.
Worth a narrow dependency there, not worth widening a fix PR for. **v0.4.**

**Verified on a real device, and it failed again — one step further along.**
v0.3.1 fixed the redirect crash, and the next attempt got past it and died on
the token exchange:

    android.os.NetworkOnMainThreadException
      at okhttp3.internal.connection.RealCall.execute
      at ...DropboxTokenClient.post
      at ...AccountsViewModel$onAuthorizationResult$1

Not a bug in the exchange. `suspend` does not move work off a thread — it runs
on whatever dispatcher the caller is already on — and every blocking
`execute()` in `:providers:dropbox` relied on its caller to pick one. Until
v0.3 the only caller was the transfer engine, whose scope is `Dispatchers.IO`,
so the adapter was **accidentally correct for the one caller it had**. The
accounts screen called it from `viewModelScope`, which is `Dispatchers.Main`.

Five blocking calls had this shape, not one. The fix is in the two HTTP
classes rather than at the call site: a `suspend` function that blocks is
lying about its contract, and fixing the caller would have left the same trap
for the next one.

**Verified by JVM test.** `MainSafetyTest` runs each entry point on a thread
it owns and asserts the HTTP call did not happen on that thread — the property
rather than the path, so a future method that blocks without switching fails
there instead of on a phone. Reverting the fix fails it; that was checked
rather than assumed.

Worth recording how nearly this test was useless: kotlinx.coroutines decorates
thread names with ` @coroutine#N` under test, so comparing the decorated name
against the bare one passed whether or not the call had blocked. A sanity
assertion on the line above it is the only reason that surfaced.

**Still not verified.** No test asserts that anything *renders correctly* —
that text is legible, that nothing is clipped, that the §24.3 hop indicator
reads as intended. The journey tests prove the flows are reachable and that the
engine runs inside the app, not that the result looks right. And no test has
carried a redirect all the way through a real token exchange to a connected
account row: the receiver is covered, the exchange behind it is covered against
MockWebServer, and the join between them has still only ever run on a phone.

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
- **Two Dropbox accounts connected at once** (2026-09-22), each with its own
  credential under its own Keystore key, both listed on §24.5.
- **A Dropbox → Dropbox transfer, completing** (2026-09-23). Files enumerated
  from one real account, downloaded to the device, uploaded to a second real
  account, and verified at the destination against Dropbox's own
  `content_hash` (§21). This is the sentence v0.4 existed to make true, and it
  took three fixes after the milestone's code was written.
- **Four §23 error shapes, as diagnosis rather than as fixtures.** `409`
  with no mapped tag, `path/not_folder`, and the two failures above were all
  first seen on a real account. What CI knows about them is a guess (below).

### Only against the fake, or only against MockWebServer

- **Every offline fixture except one.** `docs/testing.md` rule 1 says a
  recorded test replays what the service sent, and is explicit that a
  hand-written approximation does not count. Right now only
  `errors/upload_insufficient_space_409.json` meets that bar. Everything in
  `providers/dropbox/src/test/resources/fixtures/` is **shaped by hand and
  unverified**, and its README says so at the top. The capture tool that
  replaces them exists —
  `./gradlew :tools:dropbox-capture:captureDropboxFixtures` — and has not been
  run, because it needs a live scratch account. Until it is, the offline tests
  prove that the adapter is self-consistent, not that it agrees with Dropbox.
  That distinction is not academic: both of this milestone's adapter bugs were
  a plausible guess about a response shape that differed from the real one in
  exactly the way that mattered.
- **A transfer running through Dropbox under load.** Every §22 behaviour —
  pause, resume, cancel one file mid-upload, retry — is covered against the
  fake with the §31.3 delay injections. A real transfer has now completed, but
  nobody has paused or cancelled one mid-file against a real account.
- **Recovery after process death against a real provider.** §31.4's first
  scenario now runs for real, on an emulator, against the demo providers — a
  genuine `am force-stop` and a genuine resume. Against Dropbox it has not run,
  and the fake differs from Dropbox in one way that bears directly on it: the
  fake's upload sessions read as already expired, so every resume begins a
  fresh one. A real session does not, which is the path defect 2 above lived
  in. The force-stop device run is one of the two things v0.5 asks of you.
- **Reboot, on any API level.** The boot receiver reconciles from rows and is
  covered by nothing. On 34+ the job is `setPersisted`, which the platform's
  validation accepts alongside `setUserInitiated`, and whether it is actually
  restored has not been observed.
- **The browser leg, still.** The emulator test covers §24.5 up to the point
  where a Custom Tab would open, and `OAuthRedirectTest` covers the return trip
  from the point the browser hands it back. The tab itself has only ever run on
  a phone, and cannot run in CI — but it has now run there successfully, which
  is a different thing from untested (see below).
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

3. ~~**No background execution.**~~ Landed in v0.5. §17's UIDT on API 34+ and
   the WorkManager `dataSync` worker below it, behind one interface, with §16
   as the job's network constraint and resume driven by re-reading rows
   (ADR-0031).

   **The UIDT path has never run.** CI's emulator is API 30, so everything
   above only exercises the WorkManager branch; the 34+ branch is compiled,
   lint-clean and unexercised. An API 34 matrix entry is a separate PR — see
   "What v0.6 needs from you".
4. ~~**No notification.**~~ Landed in v0.5. §24.4's ongoing notification, with
   direction, aggregate progress, current filename, Pause and Cancel, and a
   waiting state that names its condition — which outlives the job that raised
   it, because a foreground notification dies with its worker and a parked
   transfer is precisely when the user is still owed an explanation.
5. **The pipeline is sequential per chunk** — correct but not overlapping
   download and upload in wall-clock terms. ADR-0017; §18 says measure first.
6. ~~**`content_hash` is validated against independently computed vectors and
   against the JDK, not against live Dropbox responses.**~~ Done, 2026-09-20:
   all four §36 cases matched. See above.
7. ~~**One Dropbox account per install.**~~ Fixed in v0.4. `DropboxTokenSource`,
   `DropboxApi` and the refresh-token store take an `AccountId`; §2.2 now
   rejects the same *account* rather than the same provider; §24.2 picks
   accounts rather than providers.

   **Not yet proven on a device.** The unit tests cover two accounts not
   sharing a token, a rotated credential filed against the right account, and
   the amended §2.2 rule — but no Dropbox-to-Dropbox transfer has run, because
   that needs two real accounts connected on a phone. Until it does, the
   engine has still only ever moved bytes between two fakes.
8. **Nothing enforces main-safety anywhere else.** `MainSafetyTest` covers
   `:providers:dropbox`, which is where the blocking is today. Google Drive's
   adapter will have the same shape and nothing would catch a repeat except
   another hand-written test per module. A shared test fixture, the way §31.2's
   contract suite is shared, would make it structural. **v0.4**, alongside the
   Drive adapter that will need it.
9. ~~**The sign-in has never completed end to end anywhere.**~~ Done, on a
   Samsung phone running Android 16, 2026-09-22. Browser → consent →
   redirect → token exchange → `get_current_account` → account row, in one
   unbroken run, on the third attempt. §24.5 showed the account's name and
   address, "Can be a source or a destination", and the four scopes Dropbox
   actually granted.

   Three crashes stood between the first attempt and this one, each one step
   further along and each in a *handoff* rather than inside a component: the
   redirect receiver's theme (browser → app), the blocking token exchange (UI →
   adapter), and before those the missing receiver configuration. Every
   component involved was individually correct and individually tested.

   What this does **not** cover is what happens on the next launch: the
   accounts screen reads Room, and the credential lives in Keystore. Those are
   different stores and nothing has yet read the credential back after a
   process death.
10. **Nothing retries a refresh that fails transiently.** An `AUTH_REQUIRED`
   correctly stops and asks the user to reconnect, but a refresh that fails
   because the network dropped surfaces to the transfer as an auth error rather
   than as §23's transient case.
11. **No Google Drive.** `:providers:google-drive` is still a stub carrying TODOs
   naming the spec sections it must satisfy. **v0.6** since the roadmap change.
12. **No Play assets.** §29.
13. **Reboot on API 34+ is assumed, not proven.** `setPersisted` is permitted
   alongside `setUserInitiated` — the platform's own validation confirms it —
   and a boot receiver reconciles regardless. Whether a persisted UIDT job is
   actually restored across a reboot has not been observed on a device, and
   cannot be until the 34+ matrix entry exists.
14. **Byte-level resume does not exist.** §22.1 says cached chunks are what
   makes resuming cheap, and §14's cache is per-chunk, but the unit of resume
   is the *item*: §19.4 computes both hashes in the single pass that reads the
   object, so a pass cannot start anywhere but byte zero. An interrupted file
   is re-read in full. §19.4's pipeline is checkpointable by design
   (ADR-0004), so this is reachable — as a throughput change with its own
   §31.4 run, not as a correctness fix. Spelt out in
   [`spec-proposals/v1.5.md`](spec-proposals/v1.5.md) §8 so §22.5 and §13.2
   stop implying otherwise.
15. **The §31.4 harness drives the UI, so it reads the screen and nothing
   else.** There is no database to open from outside CloudLug's process and no
   ViewModel to ask. "The transfer completed" means §24.1 or §24.3 said so.
   That is the right trade for a recovery test — a scenario that reads its
   answer out of the process it just killed is not testing recovery — but it
   makes the test as brittle as the strings it matches.

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

## What v0.5 needs from you — the two device runs

Both are things this environment cannot do, and both are the kind of thing that
has caught a defect every time it has been done.

1. **A multi-gigabyte transfer with the screen off, overnight.** This is the
   only test of the sentence v0.5 exists for. What to watch for: the
   notification still present and still moving in the morning; on Android 14+,
   whether a `dataSync` worker hits the ~6-hour cap (it should not on 34+,
   which uses UIDT instead, and the cap is the whole reason that branch
   exists); and the transfer's file count matching the manifest at the end.
2. **A force-stop mid-file**, from Settings → Apps → Force stop while a large
   file is in flight, then reopening the app. Expect the transfer to pick up
   where the rows left it. Two of v0.5's four defects were exactly this against
   the emulator's fake providers; a real Dropbox session behaves differently
   from the fake in one way that matters — its session does **not** read as
   already expired — so this path is only lightly covered by the automated
   tests.

If either fails, the useful artefacts are the §24.3 screen (which now shows the
failed item's `lastErrorMessage`, not just its category) and the transfer's
file counts.

## What v0.6 (Google Drive destination) needs from you

Two things need your account rather than code, and one gates the other.
Registration is not instant, so they are worth starting before the code is.

1. **Confirm Google's scope classification, before any Drive code.** As of
   2026-09-21, out of band: `drive.file` non-sensitive, `drive.readonly`
   restricted with annual CASA. That is what makes Drive destination-only in
   the Play build (§8.2), and it is worth re-confirming because it is the one
   input that could invalidate the milestone's shape rather than its schedule.
2. **A Google Cloud project with an OAuth client for Android**, package
   `dev.thiagosindra.cloudlug` and the debug signing certificate's SHA-1, with
   the Drive API enabled and `drive.file` requested. **No client secret** —
   §8.1 is PKCE-only, the same as Dropbox, and I will not ask for one.
3. **A scratch Google account** and a `DRIVE_TEST_ROOT` folder inside it, the
   way `DROPBOX_TEST_ROOT` works today: the live contract gate refuses to run
   without it and never addresses the account root.

And one thing I would ask for, which is not blocking:

4. **A second fixture-capture run against Dropbox seeding more than one entry
   in the workspace**, so `list_folder` paging is non-degenerate. The last run
   asked for `limit=1` against a workspace holding one object, so `has_more`
   came back `false` and the continue route returned nothing. A real multi-page
   listing is still unexercised offline, and paging is where §11's cursor
   resume lives.

Finally, one decision that is yours: **the API 34+ emulator matrix entry.** It
is the only way to exercise the UIDT branch in CI, and it is its own PR because
an API 34 AVD has failed to boot on these runners before. If it cannot be made
reliable, the honest outcome is to say so here and accept UIDT as
device-verified only — which is a real reduction in confidence, since UIDT is
the branch that runs on every phone shipping today.
