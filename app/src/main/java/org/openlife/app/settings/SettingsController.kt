package org.openlife.app.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
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

/** RED stub (P1-19-R15). */
class SettingsController(private val scope: CoroutineScope) {
    val verify: StateFlow<VerifyState> get() = TODO("P1-19-R15 $scope")
    val reset: StateFlow<ResetState> get() = TODO("P1-19-R15")

    fun verifyAll(run: suspend () -> VerifyReport?): Unit = TODO("P1-19-R15 $run")

    fun openReset(): Unit = TODO("P1-19-R15")

    fun setWord(word: String): Unit = TODO("P1-19-R15 $word")

    fun continueReset(): Unit = TODO("P1-19-R15")

    fun dismissReset(): Unit = TODO("P1-19-R15")

    fun confirmReset(run: suspend () -> VaultResetResult): Unit = TODO("P1-19-R15 $run")

    fun consumeResetCompleted(): Unit = TODO("P1-19-R15")
}
