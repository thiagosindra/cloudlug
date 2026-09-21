# Captured provider errors

Response bodies that a real Dropbox account produced, kept as fixtures so the
§23 retry mapping is tested against what the service actually sends rather than
against what the documentation implies it sends. The two differ often enough
that §36 makes checking it an early task.

Each file is named `<route>_<condition>_<status>.json` and is paired with the
HTTP status it arrived with, which the mapping needs and JSON cannot carry.

| File | Route | Status | Expected mapping (§23) |
| --- | --- | --- | --- |
| `upload_insufficient_space_409.json` | `/2/files/upload` | 409 | `CloudErrorKind.DESTINATION_STORAGE_FULL` |

§23 is specific about this one: Dropbox `insufficient_space` is a
`WAITING_FOR_STORAGE`-style hold with a user message, **not retried
automatically** and not a permanent item failure. An earlier revision of this
table called it permanent, which would have failed the transfer instead of holding it
for the user to free space and resume.

## Provenance

`upload_insufficient_space_409.json` is the verbatim body from a real
`insufficient_space` failure on `/2/files/upload`, HTTP 409, kept byte for byte.

It is worth saying why that matters, because the first version of this file was
a reconstruction from a description of the same response and it was **wrong in a
way that would have broken the parser**. The reconstruction nested the write
failure under an inner `path` object:

```json
{"error":{".tag":"path","path":{"reason":{".tag":"insufficient_space"}, ...}}}
```

Dropbox does not send that. It flattens the union member's fields alongside the
tag, so `reason` and `upload_session_id` sit directly on `error`:

```json
{"error":{".tag":"path","reason":{".tag":"insufficient_space"},"upload_session_id":"..."}}
```

Walking `error.path.reason` is the obvious thing to write against the
reconstruction, and it finds nothing in the real body: the mapping falls through
to a generic failure, turning a hold the user can act on into an opaque error —
exactly the §23 behaviour this fixture protects. Seeing the real shape is what
made `DropboxErrors` search for a tag at any depth instead, and
`DropboxErrorMappingTest` now pins both shapes so that tolerance survives a
later tidy-up. The `error_summary` is also plain `path/insufficient_space/`,
not the elided form that was guessed.

The `upload_session_id` is kept as it arrived. It came from a token that has
since been revoked, and an upload session expires on its own, so the value is
inert; the mapping never reads it. Redacting it would cost the one property this
fixture is for — being exactly what the service sent.
