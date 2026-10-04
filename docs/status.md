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

## v0.6 in progress — Google Drive as a destination

**Step 1 (merged): the tools.** `tools/drive-auth`, `drive-hash-check` and
`drive-capture`, with `GoogleOAuth` shared with the app. The first capture run
found a defect in the capture tool itself: it published a `session_crd` URL
parameter, because redaction knew only `upload_id` by name. Redaction of URL
parameters is now deny-by-default (a PR of its own). The leaked value was only
ever on an unmerged branch, and nothing on `main` contains it. That branch was
rewritten, and the capture redone with the fix on 2026-10-04. The fixtures in
this repository come from the second run.

**Step 2: the adapter.** `GoogleDriveCloudProvider` over `:core:network`:
- **Discovery:** `rootOf`, `listChildren` with paging, `lookupDestination`
  (every same-name sibling, so §19.3 can call it a conflict), and quota from
  `about.get`.
- **Folders:** an idempotent `prepareDestination`, which refuses when two
  folders share the name (spec-proposals/v1.6 §5).
- **Uploads:** resumable, in 8 MiB chunks. The session URI is persisted in
  `uploadSessionMetadata`, with an expiry a day inside Google's week. The
  acknowledged offset comes from the `308` `Range` header.
- **Verification:** `finishUpload` returns the file the final chunk produced,
  and after process death it recovers that file from the completed session.
  §21 now compares Drive's SHA-256 as well as its MD5 (v1.6 §6).
- **Errors:** §23's 403 reasons and `invalid_grant` are mapped.
- **Source calls:** `canBeSource` follows the granted scopes. An account
  holding only `drive.file` refuses enumeration and download by name.

Credentials are keyed by account for both providers in one provider-neutral
`RefreshTokenStore`. `AccountRepository` is keyed by account, not provider.
`GoogleDriveConnector` signs in through AppAuth with a Custom Tab, the
reverse-client-id scheme (enabled on the client; v1.6 §4 records the risk
that Google withdraws it), `access_type=offline` and `prompt=consent`, and it
revokes through `oauth2/revoke` before forgetting. The accounts screen offers
Google Drive.

**`GOOGLE_DRIVE` is the real adapter now.** The registry is keyed by provider
type, so the demo destination moved to its own debug-only
`FAKE_DESTINATION`. Without that move, a real Drive account's transfer would
have gone into the in-memory fake and reported success.

**Step 3: the wizard offers Dropbox → Google Drive honestly.** Under
`drive.file` the destination picker can show only My Drive and CloudLug's own
folders. The picker now says so, rather than leaving the user looking for
folders that will never appear. The review step names the enclosing folder,
which §24.2 step 5 asked for and which had never been shown. It also says where
the folder will appear. When the top of My Drive was the only choice, it adds
that the folder can be moved afterwards in Google Drive (v1.6 §2). The signal
is a new provider-neutral `AccountRoles.seesOnlyOwnObjects`, set from the
granted scopes, so a self-build with `drive.readonly` sees neither line.

### Real Drive, recordings only, or nothing yet

**Run against real Google Drive:**
- **the §36 hash check, all five cases** (reported 2026-10-04). One byte,
  exactly 256 KiB, exactly 8 MiB, ~10 MiB (a whole chunk, a checkpoint and
  restore of the hash, then an unaligned final chunk), and a local file of the
  maintainer's. Each went up as a resumable session in 8 MiB chunks with no
  `PROTOCOL` problem. `md5Checksum` and `sha256Checksum` matched
  `:core:hashing` in every case, both in the response that finished the upload
  (§21 step 1) and from a later `files.get` (§21 step 2). So Drive reports
  SHA-256 in both places, and v1.6 §6's second-hash comparison has a real
  value to compare against;
- the Desktop client's authorize URL;
- the capture run: every route the adapter uses, plus 404, 400, 401,
  `invalid_grant` and the cancelled-session `499`;
- the recorded MD5 and SHA-256 of two uploads. Both match the bytes sent,
  which are deterministic, so this is checked offline on every PR by the
  handoff test.

**Run only against those recordings:**
- every adapter route (`DriveCapturedRoutesTest`);
- the real engine moving a file through the real adapter to `COMPLETED` by
  destination hash (`DriveDestinationHandoffTest`).

**Not yet run:**
- **The live contract suite.** Its 23 tests skip without
  `DRIVE_REFRESH_TOKEN`.
- **Sign-in on a phone**: the Custom Tab, the custom-scheme redirect, and
  revocation.
- **Throttling and a full account.** Their 403 bodies are the captured
  envelope with the reason swapped; none has been seen for real.
- **Whether the app's Android client can see folders the tools' Desktop
  client created.** Both are in one project; Google does not document it.

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
| `:providers:google-drive` | The Drive adapter (§5 destination surface, §23 mapping, §22.5 resumable sessions), `GoogleOAuth`, §8.3's token source; replay of every captured route, the engine handoff and main-safety | 59 + 23 live |
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
| `:tools:*` | Not shipped: the §36 `content_hash` harness, the `dropbox-auth` CLI that mints a refresh token, the §31.2 fixture capture, and `recovery-test` — an empty application that instruments itself so it can force-stop CloudLug and survive. v0.6 adds `drive-auth`, `drive-hash-check` and `drive-capture` over a shared `drive-common` | 33 + 3 instrumented |

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
- **Sixteen routes, captured verbatim** (2026-09-24). Every route the adapter
  uses, plus every error it can provoke without harming the account, recorded
  by `:tools:dropbox-capture` with ids and paths pseudonymised and hashes kept.
  The offline tests replay these, so `docs/testing.md` rule 1 is met rather
  than merely written down: CI now checks that the adapter agrees with Dropbox,
  not that it agrees with me.

  One hand-written fixture was wrong, and the way it was wrong is the point.
  `path/not_folder/...` — with an elided ellipsis — where Dropbox sends
  `path/not_folder/`. That is the *second* invented ellipsis in an
  `error_summary` in this project; `errors/README.md` records the first. The
  test asserted my guess, so the guess passed for a week.

  The captures also showed that Dropbox answers a malformed path with **plain
  prose and no JSON at all**, so §23's `malformed_path` branch never fires for
  the case it was written for, and the only diagnostic is a body that quotes
  the rejected path — §26 keeps it out of the message, which now says the body
  was not JSON instead of just naming the status.

### Only against the fake, or only against MockWebServer

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
  against synthetic bodies. No real 429 has been seen, and it cannot be
  provoked to order — the fixtures README says so rather than inventing one.
- **Paging.** `list_folder/continue` is captured but degenerate: the capture
  asked for `limit=1` and the workspace held one entry, so Dropbox had no
  second page to give. A listing that really sets `has_more: true` is still
  unexercised offline. Capturing it needs a workspace seeded with more entries
  than the limit.
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
8. ~~**Nothing enforces main-safety anywhere else.**~~ Drive has its own
   `DriveMainSafetyTest` as of v0.6, covering the code exchange,
   `authenticate`, the picker's listing and `prepareDestination`. Each test
   fails when the call stops switching threads. It is still a test per
   module rather than a shared fixture.

   Originally: **Nothing enforces main-safety anywhere else.** `MainSafetyTest` covers
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

## What v0.6 needs from you to be done

The code is complete. What is left needs your account, your phone, or both.

1. ~~**The §36 hash-check output.**~~ Done: all five cases matched on MD5
   and SHA-256, in both places Drive reports them. See "Run against real
   Google Drive" above.
2. **The live contract suite**, from **Drive live contract tests** in the
   Actions tab. Nothing has run it against real Drive. While the OAuth app is
   in Testing status, the refresh token behind it expires every seven days.
3. **The first real Dropbox → Google Drive transfer, on your phone.** Watch
   for these:
   - The Google sign-in, which nothing has run end to end: Custom Tab,
     custom-scheme redirect, code exchange, `about.get`, then the account row.
   - The picker note, and the review's "created at the top of My Drive" line.
   - Every file ending "verified by destination hash".
   - The folder appearing in My Drive under the name the review showed.
   - Disconnecting Google Drive afterwards, which revokes the grant. The app
     should then disappear from your Google account's third-party access
     list.

   If the sign-in fails at the redirect, check first that "Enable custom URI
   scheme" is still on for the Android client (v1.6 §4).

## What v0.7 needs

v0.7 is the public beta (§33 as amended by v1.5 §9: everything below v0.6
shifts by one).

- **Move the Google OAuth app to Production.** This is the non-restricted
  verification for `drive.file`. Until then, every Drive account stops working
  seven days after it connects. §23 reports that correctly as
  `AUTH_REQUIRED`, but no beta user should be expected to put up with it.
- **A release Android OAuth client**, with your release certificate's SHA-1,
  kept out of this repository (`docs/oauth.md`). It needs the custom URI
  scheme enabled too, if Google still offers it for new clients. If not, v1.6
  §4's fallback becomes v0.7 work.
- **Decide the package name before the first Play upload**, because the
  application id is permanent there. Changing it later invalidates both OAuth
  clients and the Dropbox redirect.
- **Fold v1.5 and v1.6 into the spec.** v1.6 holds the §8.2 correction, the
  §24.2 picker and review wording, §10's duplicate-folder refusal, §6 and §21's
  second hash, §5's grant-based source rule, and the §8.4 decision with its
  risk.
- **The two device runs v0.5 asked for**, now with Drive as the destination:
  an overnight multi-gigabyte transfer with the screen off, and a force-stop
  mid-file. A real Drive session survives process death, unlike the fake's,
  and `finishUpload`'s recovery from a completed session has only run against
  recordings.
- **The API 34+ emulator entry**, still: the UIDT branch has never run in CI.
