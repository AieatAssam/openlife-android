package org.openlife.vault.repository

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * The one-import-at-a-time rule (design §12, C0-R29), decided by what the
 * user is doing rather than by lock state (P1-15-R1). The slot is taken when
 * preparation starts and stays taken while the prepared stage awaits Save or
 * Cancel. It is freed when preparation fails or is cancelled, when the stage
 * is saved or cancelled, or through [release] when the intake screen goes
 * away without deciding. The slot is in memory only: a new process starts
 * free and recovery removes any stage left behind.
 */
class ImportSlot {
    private sealed interface State {
        data object Free : State
        data object Preparing : State
        class Staged(val sourceId: UUID) : State
    }

    private val state = AtomicReference<State>(State.Free)

    /** True while an import is being prepared or awaits a decision. */
    val isOccupied: Boolean get() = state.get() != State.Free

    internal fun tryBeginPreparing(): Boolean = state.compareAndSet(State.Free, State.Preparing)

    internal fun preparedAs(sourceId: UUID) {
        state.compareAndSet(State.Preparing, State.Staged(sourceId))
    }

    internal fun preparationEnded() {
        state.compareAndSet(State.Preparing, State.Free)
    }

    /** Frees the slot if [sourceId] holds it; any other import keeps it. */
    fun release(sourceId: UUID) {
        val current = state.get()
        if (current is State.Staged && current.sourceId == sourceId) state.compareAndSet(current, State.Free)
    }
}
