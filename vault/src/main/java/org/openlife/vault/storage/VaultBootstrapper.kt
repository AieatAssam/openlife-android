package org.openlife.vault.storage

import java.security.SecureRandom
import org.openlife.vault.crypto.EnvelopeAuthenticationException
import org.openlife.vault.crypto.EnvelopeCodec
import org.openlife.vault.crypto.EnvelopeDomain
import org.openlife.vault.crypto.KeystoreWrapper

/** Outcome of [VaultBootstrapper.bootstrap]. See design §10 "Vault bootstrap". */
sealed interface VaultBootstrapResult {
    /** [databaseSecret] is the raw passphrase bytes to open [OpenLifeDatabase] with. */
    data class Ready(val databaseSecret: ByteArray) : VaultBootstrapResult

    /**
     * The vault could not be opened and nothing was changed on disk: no
     * replacement key was generated, no file was deleted, no empty database
     * was created. [reason] is a non-sensitive category, not raw exception
     * detail (design §9: error messages use non-sensitive categories).
     */
    data class Unavailable(val reason: String) : VaultBootstrapResult
}

private const val DATABASE_SECRET_LENGTH_BYTES = 32

/**
 * Establishes (or reopens) the vault's database secret, following design
 * §10 exactly:
 *
 * - An existing, unwrappable key file is always reused — this covers both
 *   the ordinary "open an existing vault" path and "retry an interrupted
 *   fresh bootstrap that wrote its key but never got to open the database"
 *   (design §10: "An interrupted fresh bootstrap may retry using that
 *   existing wrapper if no database exists").
 * - A key file that exists but cannot be decoded or unwrapped, or a
 *   database that exists with no usable key file, both stop at
 *   [VaultBootstrapResult.Unavailable] rather than fabricating a
 *   replacement (C0-R18, C0-12).
 * - Only when neither a key file nor a database exists is a fresh secret
 *   generated, wrapped, and written+synced *before* it is returned for the
 *   database to be opened with.
 */
object VaultBootstrapper {

    fun bootstrap(paths: VaultPaths, wrapper: KeystoreWrapper): VaultBootstrapResult {
        paths.ensureDirectoriesExist()

        val keyEnvelope = try {
            VaultKeyFile.read(paths.databaseKeyFile)
        } catch (e: EnvelopeCodec.MalformedEnvelopeException) {
            return VaultBootstrapResult.Unavailable("key file is corrupt")
        }

        if (keyEnvelope != null) {
            return try {
                val secret = wrapper.unwrap(keyEnvelope, EnvelopeDomain.DATABASE_SECRET)
                VaultBootstrapResult.Ready(secret)
            } catch (e: EnvelopeAuthenticationException) {
                VaultBootstrapResult.Unavailable("existing key could not be unwrapped")
            }
        }

        if (paths.databaseFile.exists()) {
            // A database with no usable key file is exactly the state that
            // must never be treated as "empty, safe to recreate" — see
            // design §10 and the C0-12 test row.
            return VaultBootstrapResult.Unavailable("database exists without a usable key file")
        }

        val secret = ByteArray(DATABASE_SECRET_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val envelope = wrapper.wrap(secret, EnvelopeDomain.DATABASE_SECRET)
        VaultKeyFile.writeAndSync(paths.databaseKeyFile, envelope)
        return VaultBootstrapResult.Ready(secret)
    }
}
