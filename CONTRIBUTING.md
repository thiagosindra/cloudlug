# Contributing to CloudLug

Thanks for your interest. CloudLug is an open-source Android application for
moving files between cloud providers with the phone as the only intermediary.
The design specification in [`docs/spec.md`](docs/spec.md) is the source of
truth; please read the sections relevant to your change before starting.

## Ground rules

1. **Never commit secrets.** No signing keys, Play credentials, test-account
   credentials, refresh tokens or API secrets, in code, tests, fixtures or
   commit history. Mobile OAuth *client identifiers* are public identifiers
   rather than secrets, but placeholder values that look real should not be
   invented either.
2. **Never weaken an engineering invariant** (spec §32) without changing the
   specification first and saying why in `docs/decisions.md`. In particular:
   CloudLug never deletes source data, never overwrites a destination object
   automatically, and `COMPLETED` always means destination verification
   succeeded.
3. **No analytics, advertising or third-party crash reporting**, and no
   dependency that pulls them in transitively. Every dependency shows up in the
   Play Data Safety declaration.
4. **Provider specifics stay in provider modules.** `:core:transfer` must not
   mention any cloud service; behaviour differences are expressed through
   `ProviderCapabilities`. There is a test that enforces this.

## Development setup

```bash
git clone https://github.com/thiagosindra/cloudlug.git
cd cloudlug
./gradlew build
```

JDK 17 or newer is required. The modules in the current milestone are pure
Kotlin/JVM, so no Android SDK is needed to build or test them.

### Provider credentials

CloudLug ships no shared provider application. When adapter work begins, each
contributor registers their own:

- **Dropbox**: create an app in the Dropbox App Console, use the OAuth 2
  authorization-code flow with PKCE and `token_access_type=offline`, and request
  only `files.metadata.read`, `files.content.read`, `files.content.write` and
  `account_info.read`.
- **Google Drive**: create OAuth credentials in your own Google Cloud project.
  The Play build uses the non-sensitive `drive.file` scope only. Drive *as a
  source* needs `drive.readonly`, which is a restricted scope; that path is for
  self-builders using their own client, added as a test user on their own
  project (spec §8.2).

**Credentials reach the build through the environment, never through a file.**
The live tools and the contract gate read `DROPBOX_REFRESH_TOKEN` and
`DROPBOX_TEST_ROOT` from environment variables; CI reads them from repository
secrets. Not `local.properties`, not `secrets.properties`, not a Gradle
property — a git-ignored file is one `git add -f`, one editor "save all", or
one fresh clone with stale ignore rules away from being committed, and the
rule that has no exception is easier to follow than the rule that has one.

## Privacy in contributions

CloudLug moves other people's files for a living, and this repository is
public. Nothing that identifies a person, an account or a device belongs in
it — not in code, not in a fixture, not in a test, not in documentation, not
in a commit message, and not in a pull request description. All of those are
permanent and all of them are searchable.

**Never commit, and never write into a PR description:**

- account identifiers — Dropbox `dbid:` or `account_id` values, Google account
  ids, team member ids;
- email addresses, including your own, in documentation, mock-ups, fixtures or
  test data. Use the reserved domains: `example.com`, `example.invalid`,
  `example.test`;
- display names, real or borrowed;
- real cloud paths or filenames. Every path and name in a fixture or a test is
  one this project invented or the capture tool created;
- keystore fingerprints — the SHA-1 or SHA-256 of a **release** signing
  certificate. The debug certificate is the deliberate exception and is
  published in `docs/oauth.md`; see [Signing keys](#signing-keys);
- device identifiers, including the **model number** of a phone you tested on.
  "a Samsung phone running Android 16" says everything a bug report needs.

**Provider responses enter the repository only through the capture tool.**
Run `./gradlew :tools:dropbox-capture:captureDropboxFixtures`; do not paste a
body you saw in a log, a debugger or a browser. The tool pseudonymizes object
ids, account ids, paths, upload session ids and `list_folder` cursors, keeps
the same pseudonym for the same value across a whole run so containment
survives, and preserves each replacement's length and character set so the
body still parses the way the real one did. A response that arrives any other
way has been through no such pass, and "it looked harmless" is how the two
session ids and the real filename that used to be in this history got here.

Session ids and cursors are **not** credentials — neither is usable without an
access token, and a session expires in about a week. They are replaced anyway:
"inert" and "not mine to publish" are different tests, and only the second one
governs a public repository.

**Credentials never enter the repository in any form.** Environment variables
for local runs, repository secrets for CI, and nothing else — see
[Provider credentials](#provider-credentials). No tokens, authorization codes,
`Authorization` headers, file contents or filenames in logs either; spec §26 is
the rule and `:core:network`'s redaction interceptor enforces it with a
deny-by-default header allow-list.

**If something does get in**, say so rather than quietly deleting it in a later
commit: a deletion leaves the value in history, reachable by commit SHA, for as
long as the repository exists. Removing it means rewriting history, and doing
that is much cheaper before the commit is pushed than after.

### Signing keys

**`app/debug.keystore` is committed, and that is deliberate.** It carries the
Android defaults — store password `android`, key password `android`, alias
`androiddebugkey` — and `:app` signs debug builds with it, so every
contributor and CI produce the same certificate.

It has to be shared because a Google OAuth client for Android is registered
against the package name *and* the signing certificate's SHA-1. A debug key
generated per machine would mean one OAuth client per contributor, and a
sign-in that works on one laptop failing on the next with an error that
mentions neither signing nor the key. Android's own default debug key is
equally public and uses the same password.

It protects nothing. It signs debug builds only, and anyone can extract an
equivalent certificate from any debug APK ever built. Publishing its SHA-1 in
`docs/oauth.md` gives away nothing that installing a debug build would not.

**The release key is not in this repository and never will be.** There is no
release signing config in `app/build.gradle.kts`: a release build is signed by
whoever ships it, with a key they hold and keep out of version control, and
that certificate's fingerprint stays out too. `.gitignore` still excludes
`*.keystore` and `*.jks` — `app/debug.keystore` is a single explicit
exception, written as one, so adding a second takes a deliberate edit.

Any debug build prints the fingerprint, and `verifyDebugFingerprint` — part of
`check` — fails if `docs/oauth.md` stops naming the key it describes.

## Making a change

1. Work on a branch.
2. Keep commits small and logically separate, with messages that explain *why*
   the change is right, not only what it does. Reference the spec section a
   change implements.
3. Add tests. Behaviour that the specification requires should fail without your
   change. New provider adapters must pass `ProviderContractTest` (spec §31.2).
4. Run `./gradlew build` before pushing; CI runs the same command.
5. If you found the specification ambiguous, contradictory or wrong, record the
   question and the choice you made in `docs/decisions.md` rather than diverging
   silently.

## Code style

- Kotlin official style; the build treats warnings as errors.
- Comments explain reasoning, not mechanics. A comment that restates the code is
  noise; a comment that says which spec rule a line exists to satisfy is worth
  its space.
- Public types in `:core:*` and `:providers:api` carry KDoc explaining the
  contract, including what callers must *not* assume.

## Reporting bugs

Open a GitHub issue with the device, Android version, providers involved, and
what you expected versus what happened. CloudLug collects no telemetry, so your
description is the only signal there is. Never paste tokens, authorization
codes, or `Authorization` headers into an issue; the diagnostic export is
sanitised for this reason.
