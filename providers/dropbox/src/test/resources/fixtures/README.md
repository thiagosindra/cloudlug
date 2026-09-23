# Captured Dropbox responses

Written by `./gradlew :tools:dropbox-capture:captureDropboxFixtures`. Do not edit
by hand: a fixture is only worth having if it is what the service actually sent
(`docs/testing.md` rule 1).

## Not captured by the last run

**Every file below is hand-written and unverified.** No capture run has produced
them yet, because the tool needs a live scratch account and the environment this
milestone was written in has no token. They are shaped from Dropbox's documented
types and from the two responses this project has seen for real, and they are
here so the offline tests have somewhere to load from — not because anyone has
seen Dropbox send them.

Treat every byte as a guess until the table above exists. The reason to care is
on the record twice: the flattened error union `DropboxErrors` was first written
against was a reconstruction, and it was wrong in a way that broke the parser;
and `upload_session/finish` returns a struct with no `.tag` where every other
route returns a union member, which failed every file of the first real transfer
(ADR-0030). Both were cases where a plausible guess and the real response
differed in exactly the way that mattered.

Running the capture task overwrites these files and replaces this section with a
table recording the route, status and capture date of each.

- `create_folder_v2_200.json`
- `get_metadata_file_200.json`
- `get_metadata_not_found_409.json`
- `list_folder_200.json`
- `list_folder_nested_200.json`
- `list_folder_not_folder_409.json`
- `upload_session_append_v2_200.json` — deliberately empty; the status is the whole answer
- `upload_session_finish_200.json`
- `upload_session_start_200.json`

## Not capturable

Some responses cannot be provoked without harming the account or waiting on
Dropbox's own behaviour. They are absent rather than invented:

- `insufficient_space` (§23 `DESTINATION_STORAGE_FULL`) — needs a full account.
  The one real body is kept in `../errors/`, from a genuine failure.
- 429 with `Retry-After` — needs sustained throttling.
- 5xx — needs Dropbox to be having a bad day.
