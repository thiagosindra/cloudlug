package dev.thiagosindra.cloudlug.provider.googledrive

/*
 * Google Drive adapter — v0.4 as a destination, v1.x as a source (spec §33).
 *
 * TODO(v0.4): implement CloudProvider over OkHttp for the destination role
 * only, using the non-sensitive `drive.file` scope (spec §8.2):
 *  - folder creation, resumable upload with 256 KiB granularity, verification
 *    via md5Checksum / sha256Checksum (spec §19.4, §21).
 *  - capabilities: allowsDuplicateSiblingNames = true, case-sensitive names,
 *    supportsStableObjectIds = true (spec §20.3).
 *  - 403 with userRateLimitExceeded / rateLimitExceeded / dailyLimitExceeded
 *    mapped to THROTTLED, storageQuotaExceeded to DESTINATION_STORAGE_FULL
 *    (spec §23).
 *  - My Drive only; shared drives are deferred (spec §20.8).
 *  - FIRST TASK: validate md5Checksum / sha256Checksum against real uploads
 *    before the rest of the adapter ships (spec §33, §36).
 *
 * TODO(v1.x): source support behind capabilities.canBeSource, which requires
 * either restricted-scope verification plus CASA for the Play build, or the
 * documented self-build path with the user's own OAuth client (spec §8.2).
 *
 * TODO(spec §36): re-confirm Google's current scope classification and CASA
 * tiering before any Drive code is written.
 */
