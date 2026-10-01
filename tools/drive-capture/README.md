# drive-capture

Records the Google Drive responses that the adapter's offline tests replay
(`docs/testing.md` rule 1), before the adapter exists.

```bash
DRIVE_REFRESH_TOKEN=... DRIVE_TOOL_CLIENT_SECRET=... DRIVE_TEST_ROOT=... \
  ./gradlew :tools:drive-capture:captureDriveFixtures --console=plain
```

It writes to `providers/google-drive/src/test/resources/fixtures/`:

- one body file per exchange;
- `manifest.json`, with each exchange's status and the headers the adapter
  reads;
- a generated `README.md` listing what was captured and what could not be.

Review the diff before committing it.

## What it captures

- `about.get`
- folder creation
- listing by parent, with a real second page (three children, `pageSize=2`)
- lookup by name, with two same-name siblings (§19.3) and with none
- `files.get` with checksum fields
- a resumable upload's whole lifecycle:
  - initiate
  - a status query before any bytes, with no `Range`
  - an aligned chunk answered `308`
  - the status query
  - a chunk re-sent from byte zero, which is what §22.5's recovery pass does
  - the final chunk
  - a query after completion
  - an unaligned non-final chunk
  - cancelling a session, and querying it afterwards
- `files.delete`
- the errors it can provoke safely:
  - `404` for a deleted id, and for creating under a deleted parent
  - `400` for a malformed query
  - `401` for a bad token
  - `invalid_grant` from the token endpoint

It does not capture:

- the 403 rate-limit reasons, `storageQuotaExceeded`, 429 or 5xx, because none
  can be provoked without harming the account or waiting on Google;
- an expired session, which takes a week.

The generated README says so. It records their absence rather than inventing
bodies for them.

## Redaction

Values are pseudonymized, stable for the run, with the same length and
alphabet:

- ids and `permissionId`;
- `nextPageToken`s;
- the `upload_id` inside each session URI.

Each is replaced wherever it occurs, including inside error messages. The
account's email address, display name and photo link are replaced with
invented values. Checksums, sizes, timestamps and `version` are kept.

The tool **refuses to write anything** in any of these cases:

- a real value survives redaction;
- an email address outside the reserved example domains remains;
- anything shaped like a Google token remains;
- a response echoed the refresh token.
