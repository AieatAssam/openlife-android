package org.openlife.vault.storage

import org.openlife.vault.crypto.EnvelopeAuthenticationException
import org.openlife.vault.crypto.EnvelopeCodec
import org.openlife.vault.crypto.EnvelopeDomain
import org.openlife.vault.crypto.KeystoreWrapper
import java.security.SecureRandom

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
    data class Unavailable(
        val reason: String,
        val cause: VaultUnavailableCause = VaultUnavailableCause.STORAGE_IO_ERROR,
    ) : VaultBootstrapResult
}

/** Typed bootstrap failures; detailed platform exceptions are never exposed to callers. */
enum class VaultUnavailableCause {
    KEYSTORE_TEMPORARILY_UNAVAILABLE,
    KEY_FILE_CORRUPT,
    KEY_UNWRAP_FAILED,
    DATABASE_WITHOUT_KEY,
    DATABASE_OPEN_FAILED,
    STORAGE_IO_ERROR,
    RESET_INCOMPLETE,
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
        return when (val keyRead = readKey(paths)) {
            KeyRead.Corrupt -> VaultBootstrapResult.Unavailable("key file is corrupt")

            is KeyRead.Present -> unwrapExisting(keyRead.envelope, wrapper)

            KeyRead.Absent -> if (paths.databaseFile.exists()) {
                // A database with no usable key file is exactly the state that
                // must never be treated as "empty, safe to recreate".
                VaultBootstrapResult.Unavailable("database exists without a usable key file")
            } else {
                createFresh(paths, wrapper)
            }
        }
    }

    private fun readKey(paths: VaultPaths): KeyRead = try {
        VaultKeyFile.read(paths.databaseKeyFile)?.let(KeyRead::Present) ?: KeyRead.Absent
    } catch (_: EnvelopeCodec.MalformedEnvelopeException) {
        KeyRead.Corrupt
    }

    private fun unwrapExisting(
        envelope: org.openlife.vault.crypto.Envelope,
        wrapper: KeystoreWrapper,
    ): VaultBootstrapResult = try {
        VaultBootstrapResult.Ready(wrapper.unwrap(envelope, EnvelopeDomain.DATABASE_SECRET))
    } catch (_: EnvelopeAuthenticationException) {
        VaultBootstrapResult.Unavailable("existing key could not be unwrapped")
    }

    private fun createFresh(paths: VaultPaths, wrapper: KeystoreWrapper): VaultBootstrapResult {
        val secret = ByteArray(DATABASE_SECRET_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val envelope = wrapper.wrap(secret, EnvelopeDomain.DATABASE_SECRET)
        VaultKeyFile.writeAndSync(paths.databaseKeyFile, envelope)
        return VaultBootstrapResult.Ready(secret)
    }

    private sealed interface KeyRead {
        data object Absent : KeyRead
        data object Corrupt : KeyRead
        data class Present(val envelope: org.openlife.vault.crypto.Envelope) : KeyRead
    }
}
