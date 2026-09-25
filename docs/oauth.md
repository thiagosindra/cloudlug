# OAuth client registration

CloudLug ships no shared provider application. Each contributor registers their
own, because a provider application is an identity — rate limits, review status
and scope grants all attach to it — and because §30 keeps registration
instructions in documentation rather than credentials in the repository.

Nothing on this page is a secret. CloudLug is a **public client**: it uses
OAuth 2 authorization code with PKCE and has no client secret anywhere, in the
repository, in the APK, or on any machine (§8.1). If a registration flow offers
you a client secret, you have picked the wrong client type — go back and choose
the one for a native or Android application.

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

Drive is **v0.6**; these steps are here so registration, which is not instant,
can start before the code needs it.

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
5. For a **release** build, add a second Android client with the same package
   name and your own release certificate's SHA-1. Do not put that fingerprint
   in this repository.

An Android OAuth client with the wrong SHA-1 fails at the authorization step
with a redirect error that does not mention signing. If sign-in fails and the
fingerprint is the thing you changed, that is the thing to check.

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
read `DROPBOX_REFRESH_TOKEN` and `DROPBOX_TEST_ROOT` from the environment; CI
reads them from repository secrets. `tools/dropbox-auth` mints a refresh token
interactively if you need one. See CONTRIBUTING.md.
