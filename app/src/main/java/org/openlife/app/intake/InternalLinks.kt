package org.openlife.app.intake

import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** A navigation request from inside OpenLife; typed, ids only. */
sealed interface InternalLink {
    data class OpenSource(val sourceId: UUID) : InternalLink
}

/** P1-20-R5 (AS-5): the validated hand-off from the non-exported entry to MainActivity. */
class InternalLinks(private val nonces: PickerNonce = PickerNonce()) {
    val pending: StateFlow<InternalLink?> get() = TODO()

    fun issueNonce(): String = TODO()

    fun accept(kind: String?, rawId: String?, nonce: String?): Boolean = TODO()

    fun take(): InternalLink? = TODO()

    companion object {
        const val KIND_OPEN_SOURCE = "open_source"
    }
}
