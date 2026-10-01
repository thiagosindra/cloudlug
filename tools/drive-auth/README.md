# drive-auth

Mints the scratch account's Google Drive refresh token for
`DRIVE_REFRESH_TOKEN`, and finds or creates the test root for
`DRIVE_TEST_ROOT`.

```bash
DRIVE_TOOL_CLIENT_SECRET=... ./gradlew -q :tools:drive-auth:driveAuth | pbcopy
```

It prints a URL. Open it in a browser **on the same machine**, sign in with
the scratch account and approve. The browser returns to a listener on
`127.0.0.1`. The refresh token is the only thing printed on stdout. Everything
else, including the test root's id, goes to stderr.

## Why this client needs a secret and the app does not

Google ended the flow that shows the code on screen in 2022, and it does not
allow loopback redirects for Android clients. So this tool uses a separate
**Desktop** OAuth client, and Google requires a Desktop client's
`client_secret` at the token endpoint even with PKCE. That secret lives only in
`DRIVE_TOOL_CLIENT_SECRET`, in the environment of the one command that needs
it. The app uses the Android client, which has no secret at all
(`docs/oauth.md`).

## The test root

Under `drive.file`, a token sees only files this project's clients created. A
folder named `cloudlug-contract-tests` made by hand in the Drive web UI is
invisible to it. So the tool looks among the folders it *can* see at the top of
My Drive:

- **none** — creates one and prints its id;
- **one** — prints its id;
- **several** — Drive allows same-name siblings, so this is possible. The tool
  lists their ids and picks none of them.

Every Drive tool refuses to write unless `DRIVE_TEST_ROOT` names a folder
called `cloudlug-contract-tests`.

## Seven days

While the OAuth app is in **Testing** status, Google expires its refresh tokens
after seven days. After that, the tools fail with `invalid_grant`, and a
message saying to run this again. §23 maps the same condition to
`AUTH_REQUIRED` in the app.
