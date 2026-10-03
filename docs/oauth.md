# OAuth client registration

CloudLug ships no shared provider application. Each contributor registers their
own, because a provider application is an identity — rate limits, review status
and scope grants all attach to it — and because §30 keeps registration
instructions in documentation rather than credentials in the repository.

Nothing on this page is a secret. **The app** is a public client: it uses OAuth
2 authorization code with PKCE and has no client secret, in the repository or
in the APK (§8.1). If registering the *app's* client offers you a secret, you
have picked the wrong client type — go back and choose Android.

**The Drive tools** are the one exception, and they are not the app. They need
a Desktop client (see [Google Drive](#google-drive)), and Google requires a
Desktop client's `client_secret` at the token endpoint even under PKCE. That
secret exists only in `DRIVE_TOOL_CLIENT_SECRET`, in the environment of the
command that runs a tool, and as a repository secret for the manual workflows.
Never in a file, never in a commit, never in the APK.

## The debug signing certificate

```
SHA-1    3F:C2:98:05:FF:40:8A:EA:DB:F4:B4:16:DA:CD:3E:41:FD:EB:FE:AA
SHA-256  38:49:75:BA:0C:BD:D6:27:36:8E:57:95:EA:5E:74:88:A5:FB:C4:A8:09:C8:C7:BD:4B:03:6F:5D:F5:13:FE:85
Package  dev.thiagosindra.cloudlug
```

That certificate lives in **`app/debug.keystore`, committed to this
repository**, with the Android defaults: store password `android`, key
password `android`, alias `androiddebugkey`. `:app` is configured to sign
debug builds with it, so every contributor and CI produce byte-identical
signing identity.

**It is committed on purpose and it protects nothing.** A Google OAuth client
for Android is keyed on the package name *and* the certificate's SHA-1, so a
debug key generated per machine would mean one OAuth client per contributor,
and a sign-in that works on one laptop failing on the next with an error that
names neither cause. Android's own default debug key is equally public and
uses the same password. It signs debug builds only.

**The release key is not in this repository and never will be.** A release
build is signed by whoever ships it, with a key they hold. There is no release
signing config in `app/build.gradle.kts` for the same reason.

You do not need to copy the fingerprint from this page. Any debug build prints
it:

```
$ ./gradlew :app:assembleDebug

Debug signing certificate (committed; see docs/oauth.md)
  SHA-1: 3F:C2:98:05:FF:40:8A:EA:DB:F4:B4:16:DA:CD:3E:41:FD:EB:FE:AA
```

`./gradlew :app:debugSigningReport` prints it on its own, and
`verifyDebugFingerprint` — which runs as part of `check` — fails the build if
this page stops naming the key it describes. A fingerprint is copied into a
console by hand, so a stale one costs somebody an afternoon of a sign-in that
fails for no stated reason.

## Google Drive

Drive is **v0.6**. CloudLug's own registration is one Google Cloud project
holding two OAuth clients:

| Client | Id (public) | Used by | Secret |
|---|---|---|---|
| Android | `17997718186-fjt7n8oehpagku3dio8rcqt0fbc5ssuh.apps.googleusercontent.com` | the app (`GoogleOAuth.ANDROID_CLIENT_ID`) | none — Google issues none for Android |
| Desktop | `17997718186-k07nk3kp29pedomv7fk8o5u14799rt5v.apps.googleusercontent.com` | `tools/drive-*` only (`DriveTooling.DESKTOP_CLIENT_ID`) | `DRIVE_TOOL_CLIENT_SECRET`, at runtime only |

The Android client is registered against the debug SHA-1 above. To register
your own:

1. **Google Cloud console** → create a project (or pick yours) →
   **APIs & Services → Library** → enable the **Google Drive API**.
2. **APIs & Services → OAuth consent screen**: External, fill in the app name
   and support email. Add yourself under **Test users** — an app in Testing
   status only admits listed users, and its refresh tokens expire after seven
   days, which §23 maps to `AUTH_REQUIRED` rather than treating as a fault.
3. **Scopes**: request **`.../auth/drive.file`** only. It is non-sensitive, and
   it is all CloudLug needs to *write* to Drive. `drive.readonly` — needed for
   Drive as a **source** — is a restricted scope requiring annual CASA
   assessment, which is why §8.2 makes Drive destination-only in the Play build
   and documents the source path for self-builders using their own client.
4. **Credentials → Create credentials → OAuth client ID → Android.**
   - **Package name**: `dev.thiagosindra.cloudlug`
   - **SHA-1 certificate fingerprint**: the SHA-1 above
   - No client secret is issued for an Android client. That is correct.
5. **Credentials → Create credentials → OAuth client ID → Desktop app**, for
   the tools. Google shows a client secret for it; keep it in a password
   manager and export it as `DRIVE_TOOL_CLIENT_SECRET` only for the command
   that needs it. Both clients must be in the **same project** (see below).
6. For a **release** build, add a second Android client with the same package
   name and your own release certificate's SHA-1. Do not put that fingerprint
   in this repository.

An Android OAuth client with the wrong SHA-1 fails at the authorization step
with a redirect error that does not mention signing. If sign-in fails and the
fingerprint is the thing you changed, that is the thing to check.

### Why the tools need a second client

Google retired the out-of-band flow (the code shown on screen, which is how
`tools/dropbox-auth` works) in 2022, and disallows loopback redirects for
Android clients. A terminal tool therefore needs a Desktop client, and a
Desktop client's token exchange needs its secret. The app never uses it.

### What `drive.file` can see

`drive.file` grants access only to files created by this project's OAuth
clients, or opened with the app through the Google Picker — which is web-only
(§8.2). Everything else in the account is invisible: a `files.get` answers
`404`, and a `files.list` leaves it out. Three consequences, all accepted for
v0.6 and written up in [`spec-proposals/v1.6.md`](spec-proposals/v1.6.md):

- **The test root is created by the tools**, not by hand. `drive-auth` finds a
  `cloudlug-contract-tests` folder among those it can see or creates one; a
  folder of that name made in the Drive web UI is invisible to it.
- **§36's "an existing file" hash case uses a local file** the tool uploads.
  A file some other client uploaded cannot be read at all.
- **The destination picker shows My Drive and CloudLug's own folders only.**
  The review step says the folder will be created at the top of My Drive and
  can be moved afterwards in Drive.

Google's documentation does not say whether per-file `drive.file` access is
shared between OAuth clients of the **same project**. Keeping both clients in
one project is the configuration that makes the tools' test root and the app's
folders comparable; whether the app can see a folder the tools created is
recorded as unverified in `docs/status.md` until a device shows it.

### Scope classification, checked 2026-09-30

§36 asks for this before any Drive code. Google's Drive scope page
(`developers.google.com/workspace/drive/api/guides/api-specific-auth`) lists
`drive.file` as **non-sensitive** and `drive` and `drive.readonly` as
**restricted**, with a security assessment required for restricted-scope data.
That matches §8.2.

### The Android redirect, and the risk it carries

The app's Google sign-in returns through Google's reverse-client-id custom
scheme, `com.googleusercontent.apps.<client id>:/oauth2redirect`
(`GoogleOAuth.ANDROID_REDIRECT_URI`), claimed by the app's manifest and
validated against the pending PKCE state (§8.4).

Google's native-app guide says **"Custom URI schemes are no longer supported
on Android and Chrome apps"**. The scheme works for CloudLug only because
**"Enable custom URI scheme" is switched on in the Android client's Advanced
settings**, which the maintainer confirmed on 2026-10-03. If you register
your own Android client, switch it on too, or sign-in will fail at the
redirect.

That setting can be withdrawn. If Google removes it, the fallback is Google's
own authorization client, which goes through Play services rather than a
browser and leaves no refresh token in CloudLug's hands. That would change
§8.3 and §8.4. The options are written up in
[`spec-proposals/v1.6.md`](spec-proposals/v1.6.md) §4.

## Dropbox

Dropbox does **not** key its app on a signing certificate — it matches on the
redirect URI — so the fingerprint above is irrelevant here.

1. **Dropbox App Console** → Create app → Scoped access → **Full Dropbox**.
2. **Permissions**: `files.metadata.read`, `files.content.read`,
   `files.content.write`, `account_info.read`. Nothing else; §7 shows the user
   the scopes that were actually granted, so an over-broad request is visible
   to them.
3. **Redirect URI**: `dev.thiagosindra.cloudlug://oauth/dropbox`, claimed by the
   app's manifest and validated against the pending attempt on the way back
   (§8.4).
4. Use the **app key** only. CloudLug is a public client: PKCE plus
   `token_access_type=offline`, and no app secret. The key committed in
   `DropboxOAuth.kt` is a public identifier, visible in every authorization URL
   a user ever sees.

## Credentials at runtime

Never in a file, never in a commit. The live tools and the §31.2 contract gate
read `DROPBOX_REFRESH_TOKEN` and `DROPBOX_TEST_ROOT`, and the Drive tools read
`DRIVE_REFRESH_TOKEN`, `DRIVE_TEST_ROOT` and `DRIVE_TOOL_CLIENT_SECRET`, from
the environment; CI reads them from repository secrets. `tools/dropbox-auth`
and `tools/drive-auth` mint refresh tokens interactively; the second also
prints `DRIVE_TEST_ROOT`. See CONTRIBUTING.md.
