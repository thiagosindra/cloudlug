# Status — v0.2 (Room and the Compose shell)

What exists, what is compiled, what is verified, and what v0.3 needs from you.
Milestone definitions are in spec §33; design decisions are in
[`decisions.md`](decisions.md), where every ADR carries a Status line recording
whether the spec ratified or overruled it.

Spec v1.2 resequenced §33. Room and the Compose shell are **v0.2**; the Dropbox
adapter is **v0.3** and Google Drive **v0.4**. Earlier revisions of this file
numbered these one lower.

## How to verify

```bash
./gradlew build     # every module, including :app — needs the Android SDK
./gradlew test      # the JVM modules only
```

JDK 17 or newer. `build` needs an Android SDK with platform 36; the JVM modules
still need none, which the `jvm` CI job proves by naming them explicitly.

## What exists

| Module | What it contains | Tests |
|---|---|---|
| `:core:model` | Identifiers, `CloudPath`, `ProviderHash`/`HashAlgorithm`, `HashCheckpoint`, transfer and item states, network policy | 17 |
| `:providers:api` | `CloudProvider` and `ProviderCapabilities` (§5), `CloudObject` (§6), `CloudAccount` (§7), `CloudSelection` with display paths (§9), upload/download types | 7 |
| `:core:database` | §12 entities, **Room** implementation and exported schema, §13 state machines, §15.3 chunk lifecycle, `TransferRepository`, in-memory store as the test double | 75 |
| `:core:hashing` | Checkpointable single-pass SHA-256 + destination-native hash (§19.4); hand-written SHA-256, SHA-1, MD5 and block-list SHA-256 | 28 |
| `:core:storage` | Cache budget and emergency reserve (§15, §15.1), `filesDir` chunk store with orphan purging (§15.2) | 19 |
| `:core:transfer` | Manifest builder (§10, §11, §20), collision algorithm (§19.3), retry policy (§23), verification (§21), network policy (§16), pipeline (§14), engine (§13.1, §22), `TransferController` (§35) | 79 |
| `:providers:fake` | `FakeCloudProvider` with the §31.3 failure injections; the §31.2 contract suite in test fixtures | 46 |
| `:core:ui` | Material 3 theme, the §24.3 hop indicator, progress and status components, byte/count formatting | — |
| `:feature:home` | §24.1: active transfers and history | — |
| `:feature:new-transfer` | §24.2: the six-step wizard, including the review step | — |
| `:feature:transfer-details` | §24.3: live progress, current file, per-item outcomes, §22 controls | — |
| `:app` | `MainActivity`, navigation, Hilt graph: Room, `filesDir` cache, ConnectivityManager, StatFs, two fake providers | — |

**271 JVM tests, 0 failures.** `allWarningsAsErrors` is on everywhere.

## What is compiled versus what is verified

This distinction matters more than the test count, so it is spelled out.

**Verified by automated test.** Everything in `:core:*` and `:providers:*`. The
engine, the state machines, the hash pipeline including checkpoint-and-resume
across simulated process death, the cache accounting, the collision algorithm,
and the Room layer's transactional rollback — the last of these against real
SQLite on the JVM, held to the same contract as the in-memory store (ADR-0021).
`TransferControllerTest` drives the full flow the UI depends on — prepare,
start, observe, pause, resume, cancel — against a real on-disk chunk cache and
two fake providers.

**Compiled and lint-clean, but never executed.** Every Compose screen, the
ViewModels, the Hilt graph, the Android ports in `AppModule`, and the APK
itself. `./gradlew build` produces a real `app-debug.apk`, and CI uploads it.

**Not verified at all.** Nothing in this repository has run on an Android device
or emulator. No screen has been rendered, no ConnectivityManager or StatFs call
has executed, and the Room database has never been opened on Android — only on
the JVM. There is no emulator in the environment this was built in. The first
person to install the APK is doing something nobody has done yet.

What that leaves untested, concretely: whether the Hilt graph actually
constructs at runtime, whether the screens lay out sensibly on a real display,
whether `BundledSQLiteDriver` opens a database in an app's data directory, and
whether the wizard's provider pickers behave when the fake tree is browsed.
These are the first things to check on a device.

## Reconciled to spec v1.2 (the previous PR)

Six ADRs were overruled and the code changed to match: checkpointable hashers
(0007), split counters and `COMPLETED_WITH_ISSUES` (0008), the enumeration
resume token (0006), `enumerate` losing `suspend` (0015), name-legality
capabilities (0013), and selection-carried display paths (0014). The enclosing
folder moved to `READY -> RUNNING` (§10), and size-unknown items got their own
counter (§11).

## Known gaps

1. **No background execution.** §17's UIDT on API 34+ and the WorkManager
   fallback below it are not implemented. A transfer runs in an
   application-scoped coroutine and dies with the process; the database makes
   that recoverable, but the OS may stop a long transfer. v0.5.
2. **No notification.** §24.4 specifies an ongoing notification with progress
   and controls. It arrives with the background work it belongs to.
3. **The fake provider has no slow-source injection**, which §31.3 lists. That
   is why the controller tests cover pause and resume as state transitions but
   not pausing mid-file.
4. **The pipeline is sequential per chunk** — correct but not overlapping
   download and upload in wall-clock terms. ADR-0017; §18 says measure first.
5. **`content_hash` is validated against independently computed vectors and
   against the JDK, not against live Dropbox responses.** Both catch an
   implementation bug; neither catches a misreading both sides share. §36 and
   the v0.3 milestone make this the *first* task of adapter work.
6. **No Keystore credential storage, no OAuth, no Play assets.** §8.3, §8.4,
   §29 — v0.3 and v0.5. No client IDs, real or placeholder, appear anywhere.
7. **`:providers:dropbox` and `:providers:google-drive` are stubs** carrying
   TODOs naming the spec sections they must satisfy.

## What v0.3 (Dropbox adapter) needs from you

Two things need your account rather than code. Registration is not instant, so
they are worth starting before the code is ready for them.

1. **Dropbox App Console registration** — https://www.dropbox.com/developers/apps
   - The **app key** (client ID). This is a public identifier and may live in
     the repository. I will not invent a placeholder that looks real.
   - Whether the app is **scoped access** with the **full Dropbox** or the **app
     folder** access type. Full Dropbox is needed for CloudLug to be useful as a
     source; app folder would confine transfers to one folder.
   - Confirmation that these scopes are enabled: `files.metadata.read`,
     `files.content.read`, `files.content.write`, `account_info.read` (§8.1).
   - **Do not send me the app secret.** CloudLug uses PKCE and must never embed
     a client secret in the APK (§8.1, §30). If the console shows you one, leave
     it where it is.

2. **Redirect URI.** Pick one and register it. I suggest the custom scheme
   `dev.thiagosindra.cloudlug://oauth/dropbox`, validated against the pending
   PKCE state (§8.4). If you would rather use verified App Links, I need a
   domain you control and the ability to host `assetlinks.json` on it.

3. **Scopes, stated back to me.** Once registered, tell me the scope list the
   console actually shows as enabled, rather than the list you requested. §8.1
   is what the code will assume, and a mismatch surfaces as an `AUTH_REQUIRED`
   loop rather than as a clear error.

Useful but not blocking:

- **A device to run the APK on**, and what you see. Given the section above,
  this is the single most valuable thing you can send back — even "it crashes on
  launch with this stack trace" closes a real gap.
- A **test Dropbox account** with awkward data: deep trees, many small files, a
  file over 4 GiB, names with trailing dots and non-ASCII characters. Please do
  not send credentials; a description of the tree is enough for me to mirror it
  in the fake provider.

For v0.4 (Google Drive destination) you will need a Google Cloud project with
OAuth credentials and the `drive.file` scope — but §36 asks that Google's scope
classification and CASA tiering be re-confirmed before any Drive code is
written, so that check comes first.
