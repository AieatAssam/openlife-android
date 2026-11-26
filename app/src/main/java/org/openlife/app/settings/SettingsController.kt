package org.openlife.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openlife.vault.repository.ResetConfirmation
import org.openlife.vault.repository.VaultResetResult
import org.openlife.vault.repository.VerifyReport

sealed interface VerifyOutcome {
    data class Done(val report: VerifyReport) : VerifyOutcome

    data object Unavailable : VerifyOutcome

    data object Failed : VerifyOutcome
}

enum class ResetStage { CLOSED, TYPE_WORD, FINAL_CONFIRMATION }

enum class ResetFailure { BUSY, FAILED }

data class VerifyState(val running: Boolean = false, val outcome: VerifyOutcome? = null)

data class ResetState(
    val stage: ResetStage = ResetStage.CLOSED,
    val word: String = "",
    val running: Boolean = false,
    val failure: ResetFailure? = null,
    val completed: Boolean = false,
)

/**
 * P1-19-R15 (A-11): the Settings screen's Verify-all pass and vault-reset
 * stages. Held by [SettingsViewModel], so a configuration change (MainActivity
 * declares none, so rotation recreates it) neither drops a running pass nor
 * closes a half-confirmed reset. Reset completion is state, consumed by the
 * screen that navigates away, so a completion that lands during recreation
 * is not lost.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
class SettingsController(private val scope: CoroutineScope) {
    private val mutableVerify = MutableStateFlow(VerifyState())
    private val mutableReset = MutableStateFlow(ResetState())
    val verify: StateFlow<VerifyState> = mutableVerify.asStateFlow()
    val reset: StateFlow<ResetState> = mutableReset.asStateFlow()
    private var confirmation: ResetConfirmation? = null

    fun verifyAll(run: suspend () -> VerifyReport?) {
        if (mutableVerify.value.running) return
        mutableVerify.value = mutableVerify.value.copy(running = true)
        scope.launch {
            val outcome = try {
                run()?.let(VerifyOutcome::Done) ?: VerifyOutcome.Unavailable
            } catch (cancelled: CancellationException) {
                mutableVerify.value = mutableVerify.value.copy(running = false)
                throw cancelled
            } catch (_: Exception) {
                // A reset or storage failure mid-pass; items already checked keep their state.
                VerifyOutcome.Failed
            }
            mutableVerify.value = VerifyState(running = false, outcome = outcome)
        }
    }

    fun openReset() {
        confirmation = null
        mutableReset.value = ResetState(stage = ResetStage.TYPE_WORD)
    }

    fun setWord(word: String) = mutableReset.update { it.copy(word = word) }

    /**
     * The exact [requiredWord] mints the [ResetConfirmation] a reset needs
     * (P1-08 review); anything else leaves the flow on the word step. The
     * confirmation lives here, never in state the UI or saved state can copy.
     */
    fun continueReset(requiredWord: String) {
        val minted = ResetConfirmation.ofTypedWord(mutableReset.value.word, requiredWord) ?: return
        confirmation = minted
        mutableReset.update { it.copy(stage = ResetStage.FINAL_CONFIRMATION, failure = null) }
    }

    fun dismissReset() {
        if (mutableReset.value.running) return
        confirmation = null
        mutableReset.update { it.copy(stage = ResetStage.CLOSED) }
    }

    /** Runs the reset with the minted confirmation; without one (no exact word yet), nothing runs. */
    fun confirmReset(run: suspend (ResetConfirmation) -> VaultResetResult) {
        val confirmed = confirmation ?: return
        if (mutableReset.value.running) return
        mutableReset.update { it.copy(running = true, failure = null) }
        scope.launch {
            val result = try {
                run(confirmed)
            } catch (cancelled: CancellationException) {
                mutableReset.update { it.copy(running = false) }
                throw cancelled
            } catch (_: Exception) {
                VaultResetResult.FAILED
            }
            // A completed reset spends the confirmation; BUSY or FAILED keeps it for a retry from the same dialog.
            if (result == VaultResetResult.COMPLETED) confirmation = null
            mutableReset.update {
                when (result) {
                    VaultResetResult.COMPLETED -> ResetState(completed = true)
                    VaultResetResult.BUSY -> it.copy(running = false, failure = ResetFailure.BUSY)
                    VaultResetResult.FAILED -> it.copy(running = false, failure = ResetFailure.FAILED)
                }
            }
        }
    }

    fun consumeResetCompleted() = mutableReset.update { it.copy(completed = false) }
}

/** Keeps the [SettingsController] across configuration changes. */
class SettingsViewModel : ViewModel() {
    val controller = SettingsController(viewModelScope)
}
