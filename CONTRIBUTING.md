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

Put local credentials in files that are already git-ignored
(`local.properties`, `secrets.properties`) and never in version control.

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
