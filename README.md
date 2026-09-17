# CloudLug

**Local-only cloud-to-cloud file transfer for Android.**

CloudLug moves files and folder trees between cloud-storage providers using your
Android device as the only intermediary. There is no CloudLug account, no
transfer proxy, no cloud worker, and no server that ever sees your files or your
credentials.

```
Cloud Provider A --HTTPS--> CloudLug on your phone --HTTPS--> Cloud Provider B
                                      |
                                bounded local cache
                                      |
                                 local database
```

The full technical design specification lives in [`docs/spec.md`](docs/spec.md);
it is the source of truth for this project.

## Status: v0.1 — core architecture

This repository currently contains the transfer engine and its supporting
modules, with **no real provider adapters and no OAuth yet**. Everything is
driven by an in-memory `FakeCloudProvider` and is covered by unit tests that run
on any JVM, with no Android SDK required:

```bash
./gradlew test
```

See [`docs/status.md`](docs/status.md) for exactly what exists, what is stubbed,
and what comes next, and [`docs/decisions.md`](docs/decisions.md) for the design
decisions taken where the specification was ambiguous.

## What CloudLug does and does not do

- **Copies, never synchronises.** No bidirectional sync, no source deletion, no
  destination move or rename.
- **Never overwrites automatically.** If CloudLug cannot prove that an existing
  destination object is identical, the item becomes a conflict for you to
  resolve.
- **Verifies before claiming success.** An item is `COMPLETED` only when the
  destination's own hash matches the hash computed locally while the bytes were
  streaming through the device.
- **Survives interruption.** Process death, reboot, lost Wi-Fi, throttling,
  expired tokens and storage pressure all resolve to a valid state that can be
  resumed.
- **Stays on the networks you allow.** Wi-Fi/unmetered by default; cellular only
  with explicit opt-in, and never as a silent fallback.
- **Carries no analytics, no advertising SDK and no third-party crash
  reporter.**

## Architecture

```
Compose UI  (v0.2)
    |
Transfer engine  :core:transfer
    |-- manifest builder        tree walk, classification, conflicts
    |-- pipeline                download -> bounded chunk cache -> upload
    |-- policies                collision, retry, network, verification
    |
    +--> CloudProvider adapter A  :providers:*
    +--> CloudProvider adapter B
    |
:core:database   :core:storage   :core:hashing   :core:model
```

The engine knows only that bytes move from one `CloudProvider` to another. It
contains no reference to any particular cloud service — a property enforced by a
test, not by convention.

| Module | Contents |
|---|---|
| `:core:model` | Shared value types: identifiers, paths, hashes, states, network policy |
| `:providers:api` | `CloudProvider`, `ProviderCapabilities`, `CloudObject`, `CloudAccount`, `CloudSelection` |
| `:core:database` | Persistence model, DAOs, transactional state machines |
| `:core:hashing` | Single-pass SHA-256 + destination-native hash pipeline |
| `:core:storage` | Cache budget, emergency reserve, on-disk chunk store |
| `:core:transfer` | Manifest builder, transfer pipeline, policies, engine |
| `:providers:fake` | `FakeCloudProvider` and the provider contract test suite |
| `:providers:dropbox`, `:providers:google-drive` | Stubs; adapters land in v0.2 and v0.3 |

## Building

Requires JDK 17 or newer. No Android SDK is needed for the modules in this
milestone.

```bash
./gradlew build        # compile everything and run all unit tests
./gradlew test         # unit tests only
```

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Contributors
register their own provider applications; no shared credentials exist, and none
may be committed. Security issues go to [SECURITY.md](SECURITY.md).

## Privacy

CloudLug has no transfer servers. Files are transferred directly between your
selected cloud providers through your Android device. Note that the source and
destination providers can of course still access the files you store with them.
See [PRIVACY.md](PRIVACY.md).

## License

[Apache License 2.0](LICENSE).
