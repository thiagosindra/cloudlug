# Captured Google Drive responses

Written by `./gradlew :tools:drive-capture:captureDriveFixtures`. Do not edit by hand:
a fixture is only worth having if it is what the service actually sent
(`docs/testing.md` rule 1).

`manifest.json` records each exchange's status and the response headers the
adapter reads (`Location`, `Range`, `Retry-After`, `Content-Type`); the body is
in the file it names. A resumable upload answers mostly in status and headers.

File and folder ids, `permissionId`, `nextPageToken`s and the `upload_id` in each
session URI are replaced with stable pseudonyms of the same length and alphabet,
everywhere they occur — including inside error messages and `Location`. The
account's email address, display name and photo link are replaced with invented
values. Checksums, sizes, timestamps and `version` are kept verbatim. Every name
was created by the capture tool. The tool refuses to write if any real value
survives redaction.

| Body | Method | Route | Status | Provenance | Note |
| --- | --- | --- | --- | --- | --- |
| `about_get_200.json` | GET | `/drive/v3/about` | 200 | captured 2026-10-03 | storageQuota for §20.7; user for §7's account row |
| `files_create_folder_200.json` | POST | `/drive/v3/files` | 200 | captured 2026-10-03 | the workspace, under the test root |
| `files_create_folder_nested_200.json` | POST | `/drive/v3/files` | 200 | captured 2026-10-03 | a folder inside the workspace |
| `files_create_folder_parent_not_found_404.json` | POST | `/drive/v3/files` | 404 | captured 2026-10-03 | prepareDestination under a parent that is gone |
| `files_delete_204.txt` | DELETE | `/drive/v3/files/{id}` | 204 | captured 2026-10-03 | the body is empty; the status is the answer |
| `files_get_file_200.json` | GET | `/drive/v3/files/{id}` | 200 | captured 2026-10-03 | the checksums §21 step 2 falls back to |
| `files_get_folder_200.json` | GET | `/drive/v3/files/{id}` | 200 | captured 2026-10-03 |  |
| `files_get_invalid_token_401.json` | GET | `/drive/v3/files/{id}` | 401 | captured 2026-10-03 | §23 AUTH_REQUIRED |
| `files_get_not_found_404.json` | GET | `/drive/v3/files/{id}` | 404 | captured 2026-10-03 | the file just deleted |
| `files_list_bad_query_400.json` | GET | `/drive/v3/files` | 400 | captured 2026-10-03 | a malformed q |
| `files_list_by_name_duplicates_200.json` | GET | `/drive/v3/files` | 200 | captured 2026-10-03 | §19.3: two siblings with one name |
| `files_list_by_name_none_200.json` | GET | `/drive/v3/files` | 200 | captured 2026-10-03 | §19.3: no match |
| `files_list_by_parent_page1_200.json` | GET | `/drive/v3/files` | 200 | captured 2026-10-03 | pageSize=2 over three children |
| `files_list_by_parent_page2_200.json` | GET | `/drive/v3/files` | 200 | captured 2026-10-03 | the last page: no nextPageToken |
| `files_list_nested_200.json` | GET | `/drive/v3/files` | 200 | captured 2026-10-03 | one level below the workspace |
| `token_refresh_invalid_grant_400.json` | POST | `/token` | 400 | captured 2026-10-03 | §23: what a Testing-status token looks like after seven days |
| `upload_cancel.json` | DELETE | `{session}` | 499 | captured 2026-10-03 | §22.2: abandoning a session |
| `upload_chunk_308.txt` | PUT | `{session}` | 308 | captured 2026-10-03 | Range names what arrived |
| `upload_chunk_resent_from_zero.txt` | PUT | `{session}` | 308 | captured 2026-10-03 | a chunk the session already holds |
| `upload_chunk_unaligned.txt` | PUT | `{session}` | 308 | captured 2026-10-03 | a non-final chunk of 300000 bytes |
| `upload_finish_200.json` | PUT | `{session}` | 200 | captured 2026-10-03 | the final chunk; fields asked for at initiation |
| `upload_initiate_200.txt` | POST | `/upload/drive/v3/files?uploadType=resumable` | 200 | captured 2026-10-03 | the session URI is the Location header |
| `upload_status_308.txt` | PUT | `{session}` | 308 | captured 2026-10-03 | §22.5's offset query |
| `upload_status_after_finish.json` | PUT | `{session}` | 200 | captured 2026-10-03 | querying a session that has completed |
| `upload_status_cancelled.json` | PUT | `{session}` | 499 | captured 2026-10-03 | querying a session after abandoning it |
| `upload_status_nothing_received_308.txt` | PUT | `{session}` | 308 | captured 2026-10-03 | no Range header: nothing has arrived |

## Not capturable

These cannot be provoked on demand without harming the account. They are absent
rather than invented, and the §23 tests that need them say so:

- 403 `userRateLimitExceeded` / `rateLimitExceeded` / `dailyLimitExceeded` —
  needs sustained throttling of the project's quota.
- 403 `storageQuotaExceeded` — needs a full account.
- 429 and 5xx — need Google to be throttling or failing.
- A session URI past its one-week expiry — needs a week.
