# Captured provider errors

Response bodies that a real Dropbox account produced, kept as fixtures so the
§23 retry mapping is tested against what the service actually sends rather than
against what the documentation implies it sends. The two differ often enough
that §36 makes checking it an early task.

Each file is named `<route>_<condition>_<status>.json` and is paired with the
HTTP status it arrived with, which the mapping needs and JSON cannot carry.

| File | Route | Status | Expected mapping (§23) |
| --- | --- | --- | --- |
| `upload_insufficient_space_409.json` | `/2/files/upload` | 409 | `CloudErrorKind.INSUFFICIENT_SPACE`, permanent |

## Provenance

`upload_insufficient_space_409.json` is shaped from an `insufficient_space`
failure observed while building the §36 hash-check tool: HTTP 409, the error
tagged `path`, the write reason `insufficient_space`, and an
`upload_session_id` alongside it — Dropbox's `UploadWriteFailed`. The session id
is redacted because it identifies an upload in a real account and the mapping
never reads it.

The surrounding structure is reconstructed to that description rather than
pasted byte for byte, so `error_summary` carries the documented prefix and an
elided tail. If the verbatim body is still to hand it is worth swapping in: the
point of a fixture is that it is evidence, and a reconstruction is only as good
as the description it came from.
