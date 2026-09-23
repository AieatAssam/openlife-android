package org.openlife.vault.ocr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A running engine operation that can be cancelled and reports when it has settled. */
interface EngineTask<T> {
    /** Called once when the operation has finished, failed, or acknowledged cancellation. */
    fun onSettled(listener: (Result<T>) -> Unit)

    fun cancel()
}

/** How long a cancelled engine task may take to settle before the caller gives up waiting. */
const val ENGINE_SETTLE_TIMEOUT_MILLIS: Long = 5_000L

/**
 * Awaits [task] in a cancellation-aware way (P2-02-R4). If the caller is
 * cancelled (by the user or the repository's deadline), the task is cancelled
 * and awaited, for up to [settleTimeoutMillis], before the cancellation
 * propagates. An engine can therefore release its input (for example
 * recycle a bitmap) in `finally` without racing its own worker. A task that
 * never settles is abandoned; the engine must then keep its input alive
 * (leak rather than crash).
 */
suspend fun <T> awaitEngineTask(task: EngineTask<T>, settleTimeoutMillis: Long = ENGINE_SETTLE_TIMEOUT_MILLIS): T {
    val settled = CompletableDeferred<Result<T>>()
    task.onSettled { settled.complete(it) }
    val result = try {
        settled.await()
    } catch (cancelled: CancellationException) {
        task.cancel()
        withContext(NonCancellable) { withTimeoutOrNull(settleTimeoutMillis) { settled.await() } }
        throw cancelled
    }
    return result.getOrElse { throw engineFailure(it) }
}

/** The engine stopping by itself while its caller is still active is an engine failure, not a cancel. */
private fun engineFailure(error: Throwable): Throwable =
    if (error is CancellationException) OcrEngineException("OCR engine task was cancelled", error) else error
