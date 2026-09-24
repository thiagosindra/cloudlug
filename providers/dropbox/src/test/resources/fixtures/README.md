# Captured Dropbox responses

Written by `./gradlew :tools:dropbox-capture:captureDropboxFixtures`. Do not edit by hand:
a fixture is only worth having if it is what the service actually sent
(`docs/testing.md` rule 1).

Object ids, account ids and paths are replaced with stable pseudonyms — the same
real id maps to the same pseudonym across every file, so the containment the
enumeration tests check survives. `rev`, `content_hash`, `session_id` and
timestamps are kept verbatim: none of them names a person or a place, and the
hashes are what §21's verification asserts against. Every name in these files
was created by the capture tool, so no real filename appears.

JSON cannot carry an HTTP status, and §23 maps on the status as well as the
body, so the status is recorded here.

| File | Route | Status | Provenance | Note |
| --- | --- | --- | --- | --- |
| `create_folder_v2_200.json` | `/2/files/create_folder_v2` | 200 | captured 2026-09-24 |  |
| `create_folder_v2_conflict_409.json` | `/2/files/create_folder_v2` | 409 | captured 2026-09-24 | a second create of the same folder |
| `get_metadata_file_200.json` | `/2/files/get_metadata` | 200 | captured 2026-09-24 |  |
| `get_metadata_invalid_token_401.json` | `/2/files/get_metadata` | 401 | captured 2026-09-24 |  |
| `get_metadata_malformed_path_400.json` | `/2/files/get_metadata` | 400 | captured 2026-09-24 |  |
| `get_metadata_not_found_409.json` | `/2/files/get_metadata` | 409 | captured 2026-09-24 |  |
| `get_space_usage_200.json` | `/2/users/get_space_usage` | 200 | captured 2026-09-24 |  |
| `list_folder_200.json` | `/2/files/list_folder` | 200 | captured 2026-09-24 |  |
| `list_folder_continue_200.json` | `/2/files/list_folder/continue` | 200 | captured 2026-09-24 |  |
| `list_folder_nested_200.json` | `/2/files/list_folder` | 200 | captured 2026-09-24 | the folder inside the workspace |
| `list_folder_not_folder_409.json` | `/2/files/list_folder` | 409 | captured 2026-09-24 | listing a file |
| `list_folder_paged_200.json` | `/2/files/list_folder` | 200 | captured 2026-09-24 | limit=1, so has_more is true |
| `upload_session_append_v2_200.json` | `/2/files/upload_session/append_v2` | 200 | captured 2026-09-24 | the body is empty; the status is the whole answer |
| `upload_session_finish_200.json` | `/2/files/upload_session/finish` | 200 | captured 2026-09-24 |  |
| `upload_session_incorrect_offset_409.json` | `/2/files/upload_session/append_v2` | 409 | captured 2026-09-24 | appending at an offset the session has passed |
| `upload_session_start_200.json` | `/2/files/upload_session/start` | 200 | captured 2026-09-24 |  |

## Not capturable

Some responses cannot be provoked without harming the account or waiting on
Dropbox's own behaviour. They are absent rather than invented:

- `insufficient_space` (§23 `DESTINATION_STORAGE_FULL`) — needs a full account.
  The one real body is kept in `../errors/`, from a genuine failure.
- 429 with `Retry-After` — needs sustained throttling.
- 5xx — needs Dropbox to be having a bad day.
