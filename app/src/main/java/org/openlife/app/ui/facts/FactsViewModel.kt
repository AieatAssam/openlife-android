package org.openlife.app.ui.facts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.openlife.app.AppContainer
import org.openlife.vault.facts.FactKind
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
    NOT_SAVED,
}

/**
 * P3-05-R1: a thin layer over [FactsPresenter], scoped to the Facts back-stack entry. The facts and the last
 * message are held in memory only and dropped by [clear] (on hide), by every content scrub and before a vault reset.
 */
class FactsViewModel(container: AppContainer, sourceId: UUID) : ViewModel() {
    private val presenter = FactsPresenter(container.facts, sourceId, viewModelScope)
    private val unregisterScrub = container.scrubber.register { clear() }
    private val unregisterResetCallback = container.registerPreReset(::clear)
    private val mutableMessage = MutableStateFlow<FactMessage?>(null)

    val state: StateFlow<FactsUiState> = presenter.state
    val message: StateFlow<FactMessage?> = mutableMessage.asStateFlow()

    fun start() = presenter.start()

    fun clear() {
        presenter.clear()
        mutableMessage.value = null
    }

    fun review(item: FactItem, decision: ReviewDecision) {
        viewModelScope.launch { presenter.review(item, decision) }
    }

    fun findAgain() {
        viewModelScope.launch { presenter.findAgain() }
    }

    fun choose(item: FactItem, index: Int) {
        viewModelScope.launch { presenter.chooseCandidate(item, index) }
    }

    fun addText(kind: FactKind, input: String) {
        viewModelScope.launch {
            mutableMessage.value = when (val outcome = presenter.addUserText(kind, input)) {
                is UserFactOutcome.Saved -> when (outcome.result) {
                    is FactWriteResult.Written -> FactMessage.SAVED
                    else -> FactMessage.NOT_SAVED
                }

                is UserFactOutcome.Refused -> outcome.reason.toMessage()
            }
        }
    }

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
    UserValueRefusal.MONEY_NEEDS_CODE, UserValueRefusal.NOT_ONE_VALUE -> FactMessage.REFUSED_NOT_ONE_VALUE
}
