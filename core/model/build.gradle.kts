plugins {
    id("cloudlug.jvm-module")
}

// Shared value types (spec §5–§7). Deliberately dependency-free so that every
// other module, including future provider adapters, can depend on it.
