# CloudLug Privacy Policy (draft)

**Status: draft for the v0.1 source release.** CloudLug is not yet published, and
the behaviour described here is what the code is being built to do. This document
must be re-checked against the shipping binary and every dependency it contains
before any Play Store release, because the Play Data Safety declaration has to
match the actual app (spec §29).

## The short version

CloudLug has no transfer servers. Files are transferred directly between your
selected cloud providers through your Android device.

We cannot claim that nobody but you can access your files: the source and
destination cloud providers necessarily can, since the files are stored with
them. CloudLug does not add end-to-end encryption and does not claim to.

## What CloudLug operates

Nothing. There is no CloudLug account, no transfer proxy, no cloud worker, no
synchronisation server, no remote transfer database, no advertising backend and
no file-inspection service. No CloudLug-operated system receives your file
contents or your cloud credentials.

## What stays on your device

- **Cloud credentials.** Refresh credentials are stored encrypted using keys
  protected by the Android Keystore. Access tokens are short-lived and held in
  memory. Credentials are never written to the transfer database, to plain
  preferences, to logs or to transfer manifests.
- **File contents, temporarily.** Files being transferred are cached in private
  application storage, in bounded chunks, and each chunk is deleted as soon as
  the destination has acknowledged it. The cache is cleaned after completion,
  after cancellation and on app start.
- **Transfer metadata.** File names, sizes, paths, hashes, timestamps and
  transfer history are stored in a local database so that transfers survive
  interruption.

The cache directory and the credential store are excluded from Android's
automatic backup.

## What leaves your device

Only traffic to the cloud providers you connect, over HTTPS: authentication with
the provider, listing the folders you choose, downloading the files you select
from the source, and uploading them to the destination.

## What CloudLug does not do

- No analytics SDK, no advertising SDK, no third-party crash reporter, no
  behavioural telemetry.
- Your data is not sold, and never will be.
- File contents are not used for advertising, profiling, analytics or AI
  training. CloudLug does not inspect file contents beyond computing checksums
  locally to verify that a transfer arrived intact.
- No automatic remote logging. Diagnostic reports exist, but you export them
  yourself, they are sanitised of tokens and headers, and you can read them
  before sharing them.

## Permissions

CloudLug requests network access, and the ability to run a long transfer in the
background with a visible notification. It does not request access to your
device's photos, contacts, location or shared storage: everything it transfers
comes from the cloud accounts you connect.

## Data deletion

Disconnecting an account deletes the credentials stored for it on the device and
revokes them with the provider where the provider supports revocation.
Uninstalling CloudLug removes the local database and cache. Files already
transferred to the destination provider belong to you and are unaffected —
CloudLug never deletes data at either end.

## Children

CloudLug is not directed at children and collects no data about anyone.

## Contact

Questions about this policy: open an issue at
https://github.com/thiagosindra/cloudlug/issues.

## Changes

Material changes to this policy will be recorded in the repository history and
summarised in the release notes of the version they apply to.

<!--
TODO before public beta (spec §29, §36):
 - host this policy at a stable public URL; Google OAuth verification requires
   a hosted privacy policy plus a homepage and a consent-flow demo video.
 - re-derive the Play Data Safety declaration from the shipping dependency set.
 - confirm the wording covers every provider connected at that point.
-->
