package org.openlife.vault.ocr

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A running engine operation that can be cancelled and reports when it settles. */
interface EngineTask<T> {
    fun onSettled(listener: (Result<T>) -> Unit)
    fun cancel()
}

/** P2-02-R4 stub: awaits the task but neither cancels it nor waits for it to settle on cancellation. */
suspend fun <T> awaitEngineTask(task: EngineTask<T>): T = suspendCancellableCoroutine { continuation ->
    task.onSettled { result ->
        if (continuation.isActive) {
            result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
        }
    }
}
