# Testing rules

Standing rules about *how* CloudLug is tested. What is currently tested, and on
what hardware, is in [`status.md`](status.md); why a given design is the way it
is, in [`decisions.md`](decisions.md). This file is the short list of things
that must be true of any new test or any new adapter.

---

## 1. Every adapter ships an offline test through `ManifestBuilder`

**Rule.** A provider adapter is not complete when it passes the §31.2 contract
suite. It must also ship an **offline** test that drives the real adapter, over
recorded provider JSON on a local HTTP server, through the real
`ManifestBuilder` — and that test runs on every PR, with no credential and no
network.

**Why.** Live tests prove the wire. Recorded tests prove the handoff, and the
handoff is where the defects have actually been.

v0.4's Dropbox adapter passed its live contract suite. `ManifestBuilder` passed
its own tests against the fake provider. Both were green continuously, and the
one pairing that ships — the real adapter feeding the real manifest builder —
could not complete a single transfer, because `enumerate` emitted objects with
no `parentId` and never emitted the selection roots. Nothing anywhere ran the
two together (ADR-0029). The two crashes before it were the same shape:
browser → app, and UI → adapter. Every component was individually correct and
individually tested.

**What "recorded" means.** Real response bodies from the provider, saved as
fixtures, served by `MockWebServer`. Not a hand-written approximation of what
the provider probably returns — the flattened-union error shape in
`DropboxErrors` was guessed wrong once, and a mapper written against the guess
degrades a hold the user can act on into an opaque failure.

**What it must cover, at minimum.** Whatever the engine will do to the
adapter's output: for `enumerate`, that each selection root arrives, that every
other object names a parent already emitted, and that a root which is not a
folder is emitted rather than listed.

`DropboxEnumerationTest` is the worked example.

**Live tests still matter** — they are the only thing that proves the request
shapes, the auth headers and the pagination are right against the real service.
The rule is "as well as", not "instead of". The division:

| | proves | runs |
|---|---|---|
| Live contract suite (§31.2) | the wire: real requests, real auth, real paging | when a credential is present |
| Offline recorded test | the handoff: what the engine does with the output | every PR |

---

## 2. An assertion that can pass by having nothing to check is not an assertion

**Rule.** Before writing an assertion over a collection or a nullable, ask what
it does when the value is empty or null. If the answer is "passes", anchor it
on the positive case in the same test.

**Why.** §31.2's "parents before children" check read:

```kotlin
val parent = obj.parentId ?: return@forEach       // no parent -> nothing to check -> pass
val parentPosition = positions[parent] ?: return@forEach
assertTrue(parentPosition < positions.getValue(obj.id))
```

It asked whether each object's parent came *earlier*. An object with **no**
parent passes by having nothing to compare — so the check was silently vacuous
for exactly the adapter that was broken, and only for that one. The fake
provider set parents correctly, so it looked like a working test for years.

The fix is to state the property over every object rather than over the ones
that happen to have a value: *every object except a selection root names a
parent that came before it.*

**The shapes to watch for**, all of which have appeared in this repo:

- `?: return` / `?: return@forEach` on the value under test
- `if (a != null && b != null) { assertEquals(...) }` — the assertion is inside
  a condition the buggy case fails
- `assertTrue(xs.none { ... })` with no companion check that `xs` is non-empty
- `assertEquals(emptyList(), xs)` as the *only* use of a query in a test file —
  it cannot distinguish "nothing matched" from "the query is broken"
- `assertEquals(all.drop(n), resumed)` — empty matches empty when `all` is short
- a `@Test` whose name promises more than its body asserts

**Capability gates are the legitimate exception.** `if
(!capabilities.supportsRangeDownload) return@runTest` is a declared,
inspectable skip, not a silent one. But note the cost: an adapter that
under-declares a capability skips that coverage and nothing says so.

**Sanity-check a new property test by breaking the code on purpose.** Assert
the failure you expect, once, before trusting a green run. `MainSafetyTest` was
nearly vacuous — coroutines decorate thread names, so the comparison it made
could never have failed — and only a deliberate sanity assertion revealed it.
The enumeration tests in this milestone were each run against the old adapter
and confirmed to fail for the stated reason.

---

## 3. Verify, then commit — in that order, and not with `;`

**Rule.** Chain a verification and a commit with `&&`, never `;`, and check the
exit code.

**Why.** Commit `7389a18` on `main` does not compile. The build that would have
caught it ran, failed, and the `;` let the push happen anyway.
