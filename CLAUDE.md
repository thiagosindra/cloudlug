# CloudLug — working rules

CloudLug is an Android app that moves files directly between two cloud
accounts, with no server of its own. `docs/spec.md` is the contract: sections
are cited as §N throughout the code and the commit history, and it wins over
anything written here.

Orientation, in the order it is usually needed: [`docs/status.md`](docs/status.md)
for what exists and what has actually been verified,
[`docs/decisions.md`](docs/decisions.md) for why each design choice was made
and whether the spec ratified it, [`docs/testing.md`](docs/testing.md) for the
standing rules about how things are tested, and
[`CONTRIBUTING.md`](CONTRIBUTING.md) for everything below in its fuller form.

## Privacy — the rules that have no exceptions

This repository is public, and it belongs to a project whose whole job is
other people's files. **Nothing that identifies a person, an account or a
device goes into it** — not in code, fixtures, tests, documentation, commit
messages or pull request descriptions. Every one of those is permanent and
searchable, and a later deletion does not remove the value from history.

Never write, in any of those places:

- **account identifiers** — Dropbox `dbid:` or `account_id` values, Google
  account ids, team member ids;
- **email addresses**, including the maintainer's own. Use `example.com`,
  `example.invalid` or `example.test`;
- **display names**, real or borrowed;
- **real cloud paths or filenames.** Every path and name in a fixture or test
  is invented or was created by the capture tool;
- **release keystore fingerprints** — a release signing certificate's SHA-1
  or SHA-256, and the key itself. The **debug** key is the one deliberate
  exception: `app/debug.keystore` is committed and its SHA-1 is published
  in `docs/oauth.md`, because a Google OAuth client for Android is keyed on
  the package name and that fingerprint, so it has to be the same for
  everyone. It signs debug builds only and protects nothing;
- **device identifiers, including a phone's model number.** Write "a Samsung
  phone running Android 16"; the model earns a bug report nothing.

**Provider responses enter the repository only through the capture tool**
(`./gradlew :tools:dropbox-capture:captureDropboxFixtures`), which
pseudonymizes object ids, account ids, paths, upload session ids and
`list_folder` cursors, keeps one pseudonym per real value across a run, and
preserves length and character set so the body still parses as it did. Do not
paste a body from a log, a debugger or a browser: it has been through no such
pass. Session ids and cursors are not credentials, and are replaced anyway —
"inert" and "not mine to publish" are different tests, and only the second one
governs a public repository.

**Credentials only ever come from the environment.** `DROPBOX_REFRESH_TOKEN`
and `DROPBOX_TEST_ROOT` as environment variables locally, repository secrets in
CI. Never a file — not `local.properties`, not `secrets.properties`, not a
Gradle property — and never a commit. Never ask the maintainer for an app
secret: CloudLug is a public client, PKCE-only, and no client secret exists.
The committed Dropbox app key is a public identifier, visible in every
authorization URL a user ever sees.

Spec §26 governs logging: no tokens, authorization headers, file contents or
filenames, ever. `:core:network`'s redaction interceptor enforces it with a
deny-by-default header allow-list.

If something sensitive does get committed, **say so** rather than deleting it
in a follow-up: the value stays reachable by commit SHA until history is
rewritten, and that is far cheaper before a push than after.

## Testing

Two standing rules, both earned the hard way and both in `docs/testing.md`:

1. **Every adapter ships an offline test that runs the real adapter against
   recorded provider JSON**, not just the live suite. Live tests prove the
   wire; recorded tests prove the handoff, and only one of the two runs on
   every PR.
2. **An assertion that can pass by having nothing to check is not an
   assertion.** Run a new property test against deliberately broken code once
   before trusting it. This project has found four defects that a green test
   was covering for.

`./gradlew test` needs no Android SDK; `./gradlew build` does. "Green" means
both CI jobs **plus** the emulator job, which runs `:app`, `:core:security` and
`:tools:recovery-test`.

## Style

Kotlin official style, warnings are errors. Comments explain reasoning, not
mechanics — a comment that restates the code is noise, one that names the spec
rule a line exists to satisfy is worth its space. Public types in `:core:*` and
`:providers:api` carry KDoc saying what callers must *not* assume.
