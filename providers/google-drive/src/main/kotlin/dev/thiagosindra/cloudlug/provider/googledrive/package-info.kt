package dev.thiagosindra.cloudlug.provider.googledrive

/*
 * Google Drive adapter — v0.6 as a destination (spec §33 as amended by
 * spec-proposals/v1.5.md §9), source behind capabilities.canBeSource later.
 *
 * v0.6 Step 1 puts only GoogleOAuth here, so the tools that validate Drive's
 * hashes and capture its responses drive the app's own OAuth forms. The adapter
 * itself follows once the hashes match and the fixtures exist (docs/testing.md:
 * wire first, code second).
 */
