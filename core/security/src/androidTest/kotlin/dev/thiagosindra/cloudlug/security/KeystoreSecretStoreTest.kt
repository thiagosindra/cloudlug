package dev.thiagosindra.cloudlug.security

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §8.3 on a real device, because Android Keystore does not exist anywhere else.
 *
 * The interesting assertions are negative: not "the secret comes back" — a
 * plaintext store would pass that — but "the bytes on disk are not the secret",
 * which is the only thing §8.3 is actually asking for.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val preferencesName = "cloudlug-credentials-test"
    private val keyAlias = "cloudlug-credential-key-test"

    private val reported = mutableListOf<Pair<String, UnreadableReason>>()

    private fun store(listener: SecretStoreListener = SecretStoreListener { key, reason ->
        reported += key to reason
    }) = KeystoreSecretStore(context, preferencesName, keyAlias, listener)

    private fun writeRaw(value: String) =
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString("dropbox", value)
            .commit()

    @After
    fun tearDown() {
        store().clear()
        reported.clear()
    }

    @Test
    fun a_stored_secret_comes_back_unchanged() {
        val secret = "sl.notarealrefreshtoken.AbCd-1234_x"
        store().put("dropbox", secret)
        assertEquals(secret, store().get("dropbox"))
    }

    @Test
    fun the_secret_is_not_on_disk_in_the_clear() {
        val secret = "sl.notarealrefreshtoken.AbCd-1234_x"
        store().put("dropbox", secret)

        // Read the backing file the way anything with the app's storage would.
        val raw = assertNotNull(
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).getString("dropbox", null),
            "nothing was written",
        )

        assertFalse(secret in raw, "the secret is stored in the clear: $raw")
        assertFalse("notarealrefreshtoken" in raw, "part of the secret survived: $raw")
    }

    @Test
    fun the_same_secret_encrypts_differently_every_time() {
        // GCM's security collapses if a key and IV are ever reused, so two
        // writes of identical plaintext must not produce identical ciphertext.
        val secret = "the same secret twice"
        store().put("first", secret)
        store().put("second", secret)

        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        assertFalse(
            preferences.getString("first", null) == preferences.getString("second", null),
            "identical plaintext produced identical ciphertext — the IV is being reused",
        )
    }

    @Test
    fun a_removed_secret_is_gone() {
        store().put("dropbox", "value")
        store().remove("dropbox")
        assertNull(store().get("dropbox"))
    }

    @Test
    fun clearing_leaves_nothing_readable() {
        store().put("dropbox", "value")
        store().clear()

        assertNull(store().get("dropbox"))
        assertNull(
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).getString("dropbox", null),
            "the ciphertext outlived the disconnect",
        )
    }

    @Test
    fun tampered_bytes_read_as_absent_rather_than_throwing() {
        store().put("dropbox", "value")
        // GCM authenticates, so a flipped byte fails the tag. §8.3's recovery
        // is to reconnect, which needs a null — not a crash on a code path the
        // user reaches by restoring a backup.
        writeRaw("bm90IGEgcmVhbCBjaXBoZXJ0ZXh0")

        assertNull(store().get("dropbox"))
    }

    @Test
    fun an_unreadable_credential_is_reported_rather_than_quietly_absent() {
        store().put("dropbox", "value")
        writeRaw("bm90IGEgcmVhbCBjaXBoZXJ0ZXh0")

        assertNull(store().get("dropbox"))

        val (key, reason) = assertNotNull(reported.singleOrNull(), "nothing was reported: $reported")
        assertEquals("dropbox", key)
        assertTrue(
            reason is UnreadableReason.DecryptionFailed,
            "the key is present and the bytes failed the tag, so this is tampering, not a lost key: $reason",
        )
    }

    @Test
    fun a_destroyed_keystore_key_is_reported_as_such_and_not_as_tampering() {
        store().put("dropbox", "value")

        // What a factory reset or a restored backup leaves behind: the
        // ciphertext is still in preferences, the key that would open it is not.
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)

        assertNull(store().get("dropbox"))

        assertEquals(
            listOf<Pair<String, UnreadableReason>>("dropbox" to UnreadableReason.KeystoreKeyMissing),
            reported.toList(),
            "a lost key must not be indistinguishable from an edited file",
        )
    }

    @Test
    fun an_absent_credential_is_not_reported() {
        // The ordinary first-run path. A warning here would be noise, and noise
        // is how a real warning gets ignored.
        assertNull(store().get("never-stored"))
        assertTrue(reported.isEmpty(), "absence was reported as a failure: $reported")
    }

    @Test
    fun a_malformed_value_is_reported_without_reaching_the_cipher() {
        // Decodes cleanly but is too short to hold even an IV, so there is
        // nothing to hand the cipher.
        writeRaw(Base64.encodeToString(ByteArray(4), Base64.NO_WRAP))
        assertNull(store().get("dropbox"))

        // Outside the alphabet entirely.
        writeRaw("!!!!")
        assertNull(store().get("dropbox"))

        assertEquals(
            listOf<Pair<String, UnreadableReason>>(
                "dropbox" to UnreadableReason.Malformed,
                "dropbox" to UnreadableReason.Malformed,
            ),
            reported.toList(),
        )
    }

    @Test
    fun the_report_carries_no_credential_material() {
        val secret = "sl.notarealrefreshtoken.AbCd-1234_x"
        store().put("dropbox", secret)
        val ciphertext = assertNotNull(
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).getString("dropbox", null),
        )

        // Corrupt in place, so the failure happens with the real secret's
        // ciphertext in hand — the situation where a careless log would leak.
        // A whole trailing base64 group, replaced with a group it is not, so
        // the value stays decodable and the tag certainly fails.
        val tail = ciphertext.takeLast(4)
        writeRaw(ciphertext.dropLast(4) + if (tail == "AAAA") "BBBB" else "AAAA")
        assertNull(store().get("dropbox"))

        val (key, reason) = assertNotNull(reported.singleOrNull())
        val announced = "$key ${reason.description}"
        assertFalse(secret in announced, "the secret was announced: $announced")
        assertFalse("notarealrefreshtoken" in announced, "part of the secret was announced: $announced")
        assertFalse(ciphertext in announced, "the ciphertext was announced: $announced")
    }
}
