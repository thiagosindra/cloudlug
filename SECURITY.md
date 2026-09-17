# Security Policy

CloudLug handles cloud credentials and file contents on the user's device. We
take reports about either seriously.

## Reporting a vulnerability

Please report security issues privately through
[GitHub Security Advisories](https://github.com/thiagosindra/cloudlug/security/advisories/new)
rather than opening a public issue.

Include the affected version or commit, the platform, reproduction steps, and
what an attacker gains. If you have a proof of concept, redact any real tokens
or file contents from it.

We aim to acknowledge a report within a week and to agree a disclosure timeline
with you. This is a volunteer-maintained project; please tell us if you have a
deadline in mind.

## Scope

In scope:

- Leakage of access tokens, refresh tokens or authorization codes — including
  into logs, diagnostics, backups or crash output.
- OAuth redirect interception or PKCE handling flaws.
- Cache remnants: file contents left readable after completion, cancellation,
  crash or uninstall.
- Destination data loss: anything that makes CloudLug overwrite or delete data
  it should not (spec §32).
- Transport security failures: certificate validation bypass, cleartext traffic.
- Verification bypass: anything that lets an item reach `COMPLETED` without the
  destination content being confirmed.

Out of scope:

- Compromised or rooted devices. CloudLug cannot guarantee credential
  confidentiality on an operating system that is already under attacker control,
  and says so in its threat model (spec §28).
- Vulnerabilities in the cloud providers themselves — report those to the
  provider.
- The fact that the source and destination providers can read the files you
  store with them. That is inherent to the product, and CloudLug does not claim
  otherwise.

## What CloudLug does structurally

- Credentials are never stored in the database, in plain preferences, in logs or
  in transfer manifests; long-lived secrets are encrypted with Android
  Keystore-protected keys (spec §8.3).
- Token and header redaction is centralised in the HTTP stack and log sink
  rather than left to per-call discipline (spec §26).
- Diagnostics are exported explicitly by the user, sanitised and reviewable.
  There is no automatic remote logging (spec §26, §27).
- The cache directory and credential store are excluded from Android backup
  (spec §15.2).
