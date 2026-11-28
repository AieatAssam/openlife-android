package org.openlife.app.ui.facts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.openlife.app.AppContainer
import org.openlife.vault.facts.FactWriteResult
import org.openlife.vault.facts.ReviewDecision
import org.openlife.vault.facts.extract.UserValueRefusal
import java.util.UUID

/** What the add-fact form says after a save attempt. Never carries the value (no fact text in a message). */
enum class FactMessage {
    SAVED,
    REFUSED_BLANK,
    REFUSED_TOO_LONG,
    REFUSED_CONTROL_CHARACTER,
    REFUSED_NOT_ONE_VALUE,
    REFUSED_NOT_CHOSEN,
    REFUSED_MONEY_CODE,
    NOT_SAVED,
}

/**
 * P3-05-R1: a thin layer over [FactsPresenter], scoped to the Facts back-stack entry. The facts and the last
 * message are held in memory only and dropped by [clear] (on hide), by every content scrub and before a vault reset.
 */
class FactsViewModel(container: AppContainer, sourceId: UUID) : ViewModel() {
    private val presenter = FactsPresenter(container.facts, sourceId, viewModelScope)
    private val commands = FactsCommands(presenter, container.writeScope)
    private val unregisterScrub = container.scrubber.register { clear() }
    private val unregisterResetCallback = container.registerPreReset(::clear)

    val state: StateFlow<FactsUiState> = presenter.state
    val message: StateFlow<FactMessage?> = commands.message

    fun start() = presenter.start()

    fun clear() = commands.clear()

    fun review(item: FactItem, decision: ReviewDecision) = commands.review(item, decision)

    fun findAgain() = commands.findAgain()

    fun choose(item: FactItem, index: Int) = commands.choose(item, index)

    fun add(input: UserFactInput) = commands.add(input)

    /** C2-R37: a correction reports the same messages as an add. */
    fun correct(item: FactItem, input: UserFactInput) = commands.correct(item, input)

    override fun onCleared() {
        unregisterScrub()
        unregisterResetCallback()
        clear()
    }

    companion object {
        fun factory(container: AppContainer, sourceId: UUID): ViewModelProvider.Factory = viewModelFactory {
            initializer { FactsViewModel(container, sourceId) }
        }
    }
}

private fun UserValueRefusal.toMessage(): FactMessage = when (this) {
    UserValueRefusal.BLANK -> FactMessage.REFUSED_BLANK
    UserValueRefusal.TOO_LONG -> FactMessage.REFUSED_TOO_LONG
    UserValueRefusal.CONTROL_CHARACTER -> FactMessage.REFUSED_CONTROL_CHARACTER
    UserValueRefusal.NOT_CHOSEN -> FactMessage.REFUSED_NOT_CHOSEN
    UserValueRefusal.MONEY_NEEDS_CODE -> FactMessage.REFUSED_MONEY_CODE
    UserValueRefusal.NOT_ONE_VALUE -> FactMessage.REFUSED_NOT_ONE_VALUE
    UserValueRefusal.KIND_MISMATCH -> FactMessage.NOT_SAVED
}

/**
 * P3-05 review U-05: the writes the facts screen asks for. Each runs in [writeScope] (the application scope), so
 * leaving the screen does not cancel a write in flight; a refused or failed write reports [FactMessage.NOT_SAVED]
 * and never the value. JVM-testable: [FactsViewModel] itself needs a ViewModelStore and viewModelScope.
 */
internal class FactsCommands(private val presenter: FactsPresenter, private val writeScope: CoroutineScope) {
    private val mutableMessage = MutableStateFlow<FactMessage?>(null)
    val message: StateFlow<FactMessage?> = mutableMessage.asStateFlow()

    fun clear() {
        presenter.clear()
        mutableMessage.value = null
    }

    fun review(item: FactItem, decision: ReviewDecision) {
        writeScope.launch { reportWrite(presenter.review(item, decision)) }
    }

    fun choose(item: FactItem, index: Int) {
        writeScope.launch { reportWrite(presenter.chooseCandidate(item, index)) }
    }

    fun findAgain() {
        writeScope.launch { presenter.findAgain() }
    }

    fun add(input: UserFactInput) = report { presenter.addUserInput(input) }

    /** C2-R37: a correction reports the same messages as an add. */
    fun correct(item: FactItem, input: UserFactInput) = report { presenter.correct(item, input) }

    private fun report(outcome: suspend () -> UserFactOutcome) {
        writeScope.launch {
            mutableMessage.value = when (val result = outcome()) {
                is UserFactOutcome.Saved -> when (result.result) {
                    is FactWriteResult.Written -> FactMessage.SAVED
                    else -> FactMessage.NOT_SAVED
                }

                is UserFactOutcome.Refused -> result.reason.toMessage()
            }
        }
    }

    private fun reportWrite(result: FactWriteResult) {
        if (result !is FactWriteResult.Written) mutableMessage.value = FactMessage.NOT_SAVED
    }
}
