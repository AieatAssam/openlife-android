package org.openlife.app.intake

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** A navigation request from inside OpenLife; typed, ids only. */
sealed interface InternalLink {
    data class OpenSource(val sourceId: UUID) : InternalLink
}

/**
 * P1-20-R5 (AS-5): the hand-off from the non-exported [InternalLinkActivity]
 * to MainActivity. A link is accepted only with a known kind, a well-formed
 * id and the current single-use per-process nonce; it then waits here, in
 * memory, until MainActivity takes it after the app lock. MainActivity reads
 * no navigation extra from any intent.
 */
class InternalLinks(private val nonces: PickerNonce = PickerNonce()) {
    private val mutablePending = MutableStateFlow<InternalLink?>(null)

    /** The accepted link MainActivity has not taken yet. */
    val pending: StateFlow<InternalLink?> = mutablePending.asStateFlow()

    /** For the caller that will start [InternalLinkActivity]; replaces any unused nonce. */
    fun issueNonce(): String = nonces.issue()

    /** Validates the kind and id first, so a malformed request does not burn a valid nonce. */
    fun accept(kind: String?, rawId: String?, nonce: String?): Boolean {
        if (kind != KIND_OPEN_SOURCE) return false
        val sourceId = openSourceRequest(rawId, nonce, nonces) ?: return false
        mutablePending.value = InternalLink.OpenSource(sourceId)
        return true
    }

    /** MainActivity takes the link once; null when there is none. */
    fun take(): InternalLink? = mutablePending.getAndSet(null)

    companion object {
        const val KIND_OPEN_SOURCE = "open_source"
    }
}

private fun <T> MutableStateFlow<T>.getAndSet(value: T): T {
    while (true) {
        val current = this.value
        if (compareAndSet(current, value)) return current
    }
}
