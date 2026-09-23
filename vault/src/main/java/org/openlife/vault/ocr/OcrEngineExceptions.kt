package org.openlife.vault.ocr

/** The engine could not decode the authenticated image (P2-02-R3). */
class OcrDecodeException(message: String) : Exception(message)

/** The engine failed for a reason other than decode, limits, script or cancellation. */
class OcrEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)
