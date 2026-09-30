# drive-hash-check

Spec §36 for Google Drive: upload real bytes, and compare what Drive reports
(`md5Checksum`, `sha256Checksum`) with what `:core:hashing` computes. Run it
before any adapter code depends on those values. It is not a test: it needs a
live token and the network, so it never runs in CI or as part of `build`.

```bash
DRIVE_REFRESH_TOKEN=... \
DRIVE_TOOL_CLIENT_SECRET=... \
DRIVE_TEST_ROOT=... \
DRIVE_HASH_CHECK_FILE=/path/to/some/local/file \
  ./gradlew :tools:drive-hash-check:validateDriveHashes --console=plain
```

All four values are read from the environment for this one command. None of
them goes in a file. `:tools:drive-auth:driveAuth` prints the first and the
third. The same check runs from the Actions tab as **Validate Drive
checksums**, using repository secrets.

## What it checks

| Case | Why this size |
|---|---|
| 1 byte | the degenerate case |
| exactly 256 KiB | Drive's resumable-upload granularity, sent as one final chunk |
| exactly 8 MiB | one whole CloudLug chunk (§15) with no remainder |
| ~10 MiB | a whole chunk, a checkpoint-and-restore of the hash, then an unaligned final chunk |
| `DRIVE_HASH_CHECK_FILE` | bytes this tool did not generate |

Each case goes up the way the adapter will send it: a resumable session in
8 MiB chunks. Every chunk except the last must be answered with `308` and a
`Range` naming exactly the bytes sent. Anything else is reported as
`PROTOCOL`, because §22.5's offset recovery rests on it.

For each case it reports MD5 and SHA-256 twice. The first comes from the
response that finished the upload, which is §21 step 1. The second comes from
a later `files.get`, which is §21 step 2. A value Drive did not send is
`NOT RETURNED` rather than a failure, because finding out whether Drive sends
it is part of the point of the run. A case fails on any `MISMATCH`, any
`PROTOCOL` problem, or MD5 never being reported at all.

## Why the named file is local, not already in Drive

The Dropbox check's strongest case was a file that some other client had
already uploaded. Its bytes had never passed through CloudLug, so a shared
misreading between the uploader and the hasher could not hide there.

**That case cannot exist under `drive.file`.** The scope grants access only to
files this project's OAuth clients created, or that a user opened with the
app through the Google Picker. The Picker is web-only (§8.2). A file uploaded
through the Drive web UI or another app is invisible to the token this tool
holds. `files.get` answers `404` for it, exactly as if it did not exist.

So the fifth case is the nearest honest substitute: a local file you choose,
whose bytes this tool did not generate. Drive computes its checksums from
what arrives, so the comparison still tests Drive's hashing against ours. The
one thing it cannot rule out is a flaw common to both our uploader and our
hasher. CloudLug's destination-only design never has to trust another
uploader's hash anyway: every object it verifies, it uploaded itself.

The label printed for this case is "the named local file", never its name,
because this output gets pasted into pull requests.
