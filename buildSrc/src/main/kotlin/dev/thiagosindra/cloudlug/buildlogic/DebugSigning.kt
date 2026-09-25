package dev.thiagosindra.cloudlug.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest

/**
 * The SHA-1 of the certificate in [keystore], formatted the way a console
 * expects it.
 *
 * Read through the `KeyStore` API rather than by running `keytool -list` and
 * parsing it: that output is localised and has changed shape between JDKs, and
 * a fingerprint that silently comes back wrong is worse than one that fails to
 * come back at all.
 */
internal fun sha1Of(keystore: File, storePassword: String, alias: String): String {
    val store = KeyStore.getInstance("PKCS12")
    keystore.inputStream().use { store.load(it, storePassword.toCharArray()) }
    val certificate = checkNotNull(store.getCertificate(alias)) {
        "no certificate under alias '$alias' in ${keystore.path}"
    }
    return MessageDigest.getInstance("SHA-1")
        .digest(certificate.encoded)
        .joinToString(":") { byte -> "%02X".format(byte) }
}

/**
 * Prints the committed debug certificate's fingerprint (`docs/oauth.md`).
 *
 * A Google OAuth client for Android is registered against the package name and
 * this value, so it is the one thing a contributor has to copy into a console
 * by hand. Printed on every debug assemble rather than hidden behind a task
 * nobody knows to run: the moment it is wanted is the moment a build is
 * already in front of you.
 */
abstract class DebugSigningReport : DefaultTask() {

    @get:InputFile
    abstract val keystore: RegularFileProperty

    @get:Input
    abstract val storePassword: Property<String>

    @get:Input
    abstract val keyAlias: Property<String>

    /** The bare value, for anything that wants it without parsing a log. */
    @get:OutputFile
    abstract val fingerprintFile: RegularFileProperty

    @TaskAction
    fun report() {
        val sha1 = sha1Of(keystore.get().asFile, storePassword.get(), keyAlias.get())
        fingerprintFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(sha1 + "\n")
        }
        logger.lifecycle("")
        logger.lifecycle("Debug signing certificate (committed; see docs/oauth.md)")
        logger.lifecycle("  SHA-1: $sha1")
        logger.lifecycle("")
    }
}

/**
 * Fails the build when `docs/oauth.md` stops naming the key it describes.
 *
 * That fingerprint is transcribed into an OAuth console by hand, and a stale
 * one costs somebody an afternoon: an Android client with the wrong SHA-1
 * fails at the authorization step with a redirect error that never mentions
 * signing. Wired into `check`, so it runs wherever the tests do rather than
 * only in CI.
 */
abstract class VerifyDebugFingerprint : DefaultTask() {

    @get:InputFile
    abstract val keystore: RegularFileProperty

    @get:Input
    abstract val storePassword: Property<String>

    @get:Input
    abstract val keyAlias: Property<String>

    @get:InputFile
    abstract val documentation: RegularFileProperty

    @TaskAction
    fun verify() {
        val sha1 = sha1Of(keystore.get().asFile, storePassword.get(), keyAlias.get())
        val doc = documentation.get().asFile
        check(doc.readText().contains(sha1)) {
            "${doc.name} does not name the debug certificate it documents.\n" +
                "  the keystore's SHA-1 is $sha1\n" +
                "  update ${doc.path} — that value is copied into an OAuth console by hand"
        }
    }
}
