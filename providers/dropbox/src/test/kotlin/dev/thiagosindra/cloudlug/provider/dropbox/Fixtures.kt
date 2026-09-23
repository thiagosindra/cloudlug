package dev.thiagosindra.cloudlug.provider.dropbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The recorded Dropbox responses the offline tests replay
 * (`docs/testing.md` rule 1).
 *
 * Loading from files rather than from string literals in the test is the whole
 * point: a literal is a guess about the shape, and this adapter has been wrong
 * about a response shape twice — once in the flattened error union, once in
 * `upload_session/finish` returning a struct with no `.tag`. A fixture can be
 * replaced by a capture run; a literal has to be re-guessed.
 *
 * Tests read ids *out of* the fixtures rather than hardcoding them, so a
 * capture run that assigns different pseudonyms does not break them.
 */
internal object Fixtures {

    private val json = Json { ignoreUnknownKeys = true }

    fun raw(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name.json")) {
            "no fixture '$name'. Capture it with :tools:dropbox-capture:captureDropboxFixtures"
        }.bufferedReader().readText()

    fun obj(name: String): JsonObject = json.parseToJsonElement(raw(name)).jsonObject

    /** The `id` of the n-th entry of a `list_folder` fixture. */
    fun entryId(name: String, index: Int = 0): String =
        obj(name).entries(index).let { it["id"]?.text() } ?: error("entry $index of '$name' has no id")

    fun entryName(name: String, index: Int = 0): String =
        obj(name).entries(index).let { it["name"]?.text() } ?: error("entry $index of '$name' has no name")

    fun field(name: String, key: String): String =
        obj(name)[key]?.text() ?: error("fixture '$name' has no string '$key'")

    /** `size` arrives as a JSON number, so it has no quotes to strip. */
    fun number(name: String, key: String): Long =
        (obj(name)[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
            ?: error("fixture '$name' has no numeric '$key'")

    /** `create_folder_v2` wraps its `FolderMetadata` in `metadata`. */
    fun metadata(name: String, key: String): String =
        obj(name)["metadata"]?.jsonObject?.get(key)?.text() ?: error("fixture '$name' has no metadata.$key")

    private fun JsonObject.entries(index: Int): JsonObject =
        (this["entries"] as? kotlinx.serialization.json.JsonArray)?.getOrNull(index)?.jsonObject
            ?: error("fixture has no entry at $index")

    private fun kotlinx.serialization.json.JsonElement.text(): String? =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}
