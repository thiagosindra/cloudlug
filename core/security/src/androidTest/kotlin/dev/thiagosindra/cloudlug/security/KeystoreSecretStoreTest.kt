package dev.thiagosindra.cloudlug.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

    private fun store() = KeystoreSecretStore(context, preferencesName, keyAlias)

    @After
    fun tearDown() = store().clear()

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
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString("dropbox", "bm90IGEgcmVhbCBjaXBoZXJ0ZXh0")
            .commit()

        assertNull(store().get("dropbox"))
    }
}
