package org.openlife.vault.storage

import org.openlife.vault.crypto.EnvelopeAuthenticationException
import org.openlife.vault.crypto.EnvelopeCodec
import org.openlife.vault.crypto.EnvelopeDomain
import org.openlife.vault.crypto.KeystoreUnavailableException
import org.openlife.vault.crypto.KeystoreWrapper
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.SecureRandom

/** Outcome of [VaultBootstrapper.bootstrap]. See design §10 "Vault bootstrap". */
sealed interface VaultBootstrapResult {
    /** [databaseSecret] is the raw passphrase bytes to open [OpenLifeDatabase] with. */
    data class Ready(val databaseSecret: ByteArray) : VaultBootstrapResult

    /**
     * The vault could not be opened and nothing was changed on disk: no
     * replacement key was generated, no file was deleted, no empty database
     * was created. [cause] is a typed, non-sensitive category.
     */
    data class Unavailable(val cause: VaultUnavailableCause) : VaultBootstrapResult
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

val VaultUnavailableCause.retryable: Boolean
    get() = when (this) {
        VaultUnavailableCause.KEYSTORE_TEMPORARILY_UNAVAILABLE,
        VaultUnavailableCause.DATABASE_OPEN_FAILED,
        VaultUnavailableCause.STORAGE_IO_ERROR,
        -> true

        VaultUnavailableCause.KEY_FILE_CORRUPT,
        VaultUnavailableCause.KEY_UNWRAP_FAILED,
        VaultUnavailableCause.DATABASE_WITHOUT_KEY,
        VaultUnavailableCause.RESET_INCOMPLETE,
        -> false
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
        return try {
            if (paths.resetMarkerFile.exists()) {
                return VaultBootstrapResult.Unavailable(VaultUnavailableCause.RESET_INCOMPLETE)
            }
            paths.ensureDirectoriesExist()
            when (val keyRead = readKey(paths)) {
                KeyRead.Corrupt -> VaultBootstrapResult.Unavailable(VaultUnavailableCause.KEY_FILE_CORRUPT)

                is KeyRead.Present -> unwrapExisting(keyRead.envelope, wrapper)

                KeyRead.Absent -> if (paths.databaseFile.exists()) {
                    // A database with no usable key file is never safe to recreate.
                    VaultBootstrapResult.Unavailable(VaultUnavailableCause.DATABASE_WITHOUT_KEY)
                } else {
                    createFresh(paths, wrapper)
                }
            }
        } catch (exception: IOException) {
            VaultBootstrapResult.Unavailable(classify(exception))
        } catch (exception: GeneralSecurityException) {
            VaultBootstrapResult.Unavailable(classify(exception))
        } catch (exception: ProviderException) {
            VaultBootstrapResult.Unavailable(classify(exception))
        } catch (exception: SecurityException) {
            VaultBootstrapResult.Unavailable(classify(exception))
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
    } catch (exception: EnvelopeAuthenticationException) {
        unwrapFailure(exception)
    } catch (exception: KeystoreUnavailableException) {
        unwrapFailure(exception)
    }

    private fun unwrapFailure(exception: Exception): VaultBootstrapResult = VaultBootstrapResult.Unavailable(
        if (isTemporaryKeystoreFailure(exception)) {
            VaultUnavailableCause.KEYSTORE_TEMPORARILY_UNAVAILABLE
        } else {
            VaultUnavailableCause.KEY_UNWRAP_FAILED
        },
    )

    private fun createFresh(paths: VaultPaths, wrapper: KeystoreWrapper): VaultBootstrapResult {
        val secret = ByteArray(DATABASE_SECRET_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        return try {
            val envelope = wrapper.wrap(secret, EnvelopeDomain.DATABASE_SECRET)
            VaultKeyFile.writeAndSync(paths.databaseKeyFile, envelope)
            VaultBootstrapResult.Ready(secret)
        } catch (exception: IOException) {
            secret.fill(0)
            VaultBootstrapResult.Unavailable(classify(exception))
        } catch (exception: GeneralSecurityException) {
            secret.fill(0)
            VaultBootstrapResult.Unavailable(classify(exception))
        } catch (exception: ProviderException) {
            secret.fill(0)
            VaultBootstrapResult.Unavailable(classify(exception))
        } catch (exception: SecurityException) {
            secret.fill(0)
            VaultBootstrapResult.Unavailable(classify(exception))
        }
    }

    private fun classify(exception: Exception): VaultUnavailableCause = when {
        isTemporaryKeystoreFailure(exception) -> VaultUnavailableCause.KEYSTORE_TEMPORARILY_UNAVAILABLE
        exception is IOException -> VaultUnavailableCause.STORAGE_IO_ERROR
        else -> VaultUnavailableCause.STORAGE_IO_ERROR
    }

    private fun isTemporaryKeystoreFailure(exception: Throwable): Boolean {
        var current: Throwable? = exception
        while (current != null) {
            if (current is KeyStoreException || current is ProviderException ||
                current.javaClass.name.endsWith("UserNotAuthenticatedException")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private sealed interface KeyRead {
        data object Absent : KeyRead
        data object Corrupt : KeyRead
        data class Present(val envelope: org.openlife.vault.crypto.Envelope) : KeyRead
    }
}
