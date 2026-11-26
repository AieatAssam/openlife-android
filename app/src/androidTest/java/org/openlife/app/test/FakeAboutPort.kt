package org.openlife.app.test

import org.openlife.app.settings.AboutPort
import org.openlife.app.settings.BuildIdentity
import org.openlife.app.settings.BundledDocument
import org.openlife.app.settings.DiagnosticsCounts
import org.openlife.app.settings.WrappingKeyReport

/** P1-08: a synthetic About port for screen tests hosted on [FakeAppContainer]. */
class FakeAboutPort(
    private val identity: BuildIdentity = BuildIdentity("0.0.0-test", 1, "unknown", emptyList()),
    private val key: WrappingKeyReport = WrappingKeyReport.NoKey,
    private val counts: DiagnosticsCounts = DiagnosticsCounts(null, null, emptyMap()),
    private val text: (BundledDocument) -> String = { "# Synthetic document\n\nSynthetic text." },
) : AboutPort {
    override suspend fun buildIdentity(): BuildIdentity = identity

    override suspend fun wrappingKey(): WrappingKeyReport = key

    override suspend fun diagnostics(): DiagnosticsCounts = counts

    override suspend fun document(document: BundledDocument): String = text(document)
}
