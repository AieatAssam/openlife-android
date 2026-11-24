package org.openlife.vault.storage

import org.openlife.vault.crypto.EnvelopeAuthenticationException
import org.openlife.vault.crypto.EnvelopeCodec
import org.openlife.vault.crypto.EnvelopeDomain
import org.openlife.vault.crypto.KeystoreFailures
import org.openlife.vault.crypto.KeystoreUnavailableException
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.crypto.MissingWrappingKeyException
import java.io.IOException
import java.security.GeneralSecurityException
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

    /** Key file and saved originals exist but the database file is gone (P1-18-R4). */
    DATABASE_MISSING,
}

val VaultUnavailableCause.retryable: Boolean
    get() = when (this) {
        VaultUnavailableCause.KEYSTORE_TEMPORARILY_UNAVAILABLE,
        VaultUnavailableCause.DATABASE_OPEN_FAILED,
        VaultUnavailableCause.STORAGE_IO_ERROR,
        VaultUnavailableCause.DATABASE_MISSING,
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
            // A temporary key left by an interrupted write never reached its
            // rename, so it was never the key (P1-18-R10).
            VaultKeyFile.removeLeftoverTemp(paths.databaseKeyFile)
            val plan = planBootstrap(
                readKey = { readKey(paths) },
                databaseExists = paths.databaseFile.exists(),
                // Unlistable counts as "not provably empty".
                hasArtefacts = paths.artefactsDir.list()?.isNotEmpty() ?: true,
                // SQLCipher opens a 0-byte file as a new, empty database (review R-08).
                databaseEmpty = paths.databaseFile.exists() && paths.databaseFile.length() == 0L,
            )
            when (plan) {
                is BootstrapPlan.Stop -> VaultBootstrapResult.Unavailable(plan.cause)
                is BootstrapPlan.UnwrapExisting -> unwrapExisting(plan.envelope, wrapper)
                BootstrapPlan.CreateFresh -> createFresh(paths, wrapper)
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
    } catch (_: MissingWrappingKeyException) {
        // The key file outlived its Keystore key: the vault is unrecoverable
        // and no replacement key is generated (P1-14-R2).
        VaultBootstrapResult.Unavailable(VaultUnavailableCause.KEY_UNWRAP_FAILED)
    }

    private fun unwrapFailure(exception: Exception): VaultBootstrapResult =
        VaultBootstrapResult.Unavailable(unwrapFailureCause(exception))

    /** Why unwrapping `database.key` failed; JVM-testable (P1-18 review R-01). */
    internal fun unwrapFailureCause(exception: Exception): VaultUnavailableCause =
        if (KeystoreFailures.isTemporary(exception)) {
            VaultUnavailableCause.KEYSTORE_TEMPORARILY_UNAVAILABLE
        } else {
            VaultUnavailableCause.KEY_UNWRAP_FAILED
        }

    private fun createFresh(paths: VaultPaths, wrapper: KeystoreWrapper): VaultBootstrapResult {
        val secret = ByteArray(DATABASE_SECRET_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        return try {
            // The only place a wrapping key may be created: a fresh install.
            wrapper.ensureWrappingKey()
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
        } catch (_: MissingWrappingKeyException) {
            // The key vanished between creation and use; retrying may succeed.
            secret.fill(0)
            VaultBootstrapResult.Unavailable(VaultUnavailableCause.KEYSTORE_TEMPORARILY_UNAVAILABLE)
        }
    }

    private fun classify(exception: Exception): VaultUnavailableCause = when {
        // One classifier shared with KeystoreWrapper (P1-18-R2).
        KeystoreFailures.isTemporary(exception) -> VaultUnavailableCause.KEYSTORE_TEMPORARILY_UNAVAILABLE

        else -> VaultUnavailableCause.STORAGE_IO_ERROR
    }
}

/** What reading `database.key` found. */
internal sealed interface KeyRead {
    data object Absent : KeyRead
    data object Corrupt : KeyRead
    data class Present(val envelope: org.openlife.vault.crypto.Envelope) : KeyRead
}

/** What bootstrap does for the files on disk, decided before any Keystore call (P1-18-R4, R10). */
internal sealed interface BootstrapPlan {
    data object CreateFresh : BootstrapPlan
    data class UnwrapExisting(val envelope: org.openlife.vault.crypto.Envelope) : BootstrapPlan
    data class Stop(val cause: VaultUnavailableCause) : BootstrapPlan
}

/**
 * Decides bootstrap from disk state alone. [readKey] is only called when
 * the key's contents matter.
 *
 * - Saved originals with no database, or a 0-byte one, are DATABASE_MISSING
 *   with or without a key file, decided before the key is read: opening an
 *   empty database there would let recovery delete every blob as an orphan
 *   (P1-18-R4, review R-05/R-08, design §11).
 * - A malformed key with no database and no artefacts has nothing at stake:
 *   it can only be a first-run write torn by an older build, so it is a
 *   fresh install (P1-18-R10, ADR-0005 addendum). With a database or any
 *   artefact it stays KEY_FILE_CORRUPT.
 */
internal fun planBootstrap(
    readKey: () -> KeyRead,
    databaseExists: Boolean,
    hasArtefacts: Boolean,
    databaseEmpty: Boolean = false,
): BootstrapPlan {
    // Saved originals with no usable database are never a fresh install,
    // whatever the key file says (P1-18-R4; review R-05, R-08).
    if ((!databaseExists || databaseEmpty) && hasArtefacts) {
        return BootstrapPlan.Stop(VaultUnavailableCause.DATABASE_MISSING)
    }
    return when (val key = readKey()) {
        KeyRead.Corrupt -> if (!databaseExists && !hasArtefacts) {
            BootstrapPlan.CreateFresh
        } else {
            BootstrapPlan.Stop(VaultUnavailableCause.KEY_FILE_CORRUPT)
        }

        is KeyRead.Present -> BootstrapPlan.UnwrapExisting(key.envelope)

        // A database with no usable key file is never safe to recreate.
        KeyRead.Absent -> if (databaseExists) {
            BootstrapPlan.Stop(VaultUnavailableCause.DATABASE_WITHOUT_KEY)
        } else {
            BootstrapPlan.CreateFresh
        }
    }
}
