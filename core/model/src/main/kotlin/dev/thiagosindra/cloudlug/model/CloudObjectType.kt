package dev.thiagosindra.cloudlug.model

/**
 * The kind of object an adapter reported (spec §6).
 *
 * [PROVIDER_NATIVE_DOCUMENT] and [SHORTCUT] have no transferable byte stream by
 * default and become `SKIPPED_UNSUPPORTED` at manifest time (spec §20.1, §20.2).
 */
enum class CloudObjectType {
    FILE,
    FOLDER,
    SHORTCUT,
    PROVIDER_NATIVE_DOCUMENT,
}
