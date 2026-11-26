package org.openlife.app.test

import org.openlife.app.OpenLifeApp
import org.openlife.app.intake.InternalLinks
import java.util.UUID

/**
 * P1-20-R5: queues "open this source" exactly as InternalLinkActivity does
 * once it has validated the kind, the id and the nonce. MainActivity takes it
 * after the lock gate; tests then launch MainActivity with a plain intent.
 */
fun OpenLifeApp.requestOpenSource(id: UUID) {
    val links = container.internalLinks
    check(links.accept(InternalLinks.KIND_OPEN_SOURCE, id.toString(), links.issueNonce())) { "link refused" }
}

/** The old MainActivity extra; P1-20-R5 tests send it to prove it is ignored. */
const val LEGACY_OPEN_SOURCE_EXTRA = "org.openlife.app.MainActivity.openSourceId"
