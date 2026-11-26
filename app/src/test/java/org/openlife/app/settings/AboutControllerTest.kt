package org.openlife.app.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** P1-08: About's facts load independently; documents open, close and fail without leaking state. */
class AboutControllerTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    @After
    fun tearDown() = scope.cancel()

    private class Port(private val read: suspend (BundledDocument) -> String) : AboutPort {
        override suspend fun buildIdentity() = BuildIdentity("1.0", 2, "abc1234", listOf("AA:BB"))

        override suspend fun wrappingKey(): WrappingKeyReport = WrappingKeyReport.Reported(null, false)

        override suspend fun diagnostics() = DiagnosticsCounts(null, null, emptyMap())

        override suspend fun document(document: BundledDocument): String = read(document)
    }

    private fun controller(read: suspend (BundledDocument) -> String) =
        AboutController(Port(read), scope, Dispatchers.Unconfined)

    @Test
    fun loadFillsIdentityKeyLevelAndDiagnostics() {
        val about = controller { "" }
        about.load()
        val state = about.state.value
        assertEquals("abc1234", state.identity?.gitCommit)
        assertEquals(KeySecurityLevel.SOFTWARE, state.keyLevel)
        assertEquals(DiagnosticsCounts(null, null, emptyMap()), state.diagnostics)
        assertTrue(state.documents.isEmpty())
    }

    @Test
    fun toggleOpensParsedDocumentAndClosesIt() {
        val about = controller { "# Title\n\nBody text." }
        about.toggle(BundledDocument.PRIVACY_POLICY)
        val shown = about.state.value.documents[BundledDocument.PRIVACY_POLICY]
        assertEquals(DocumentState.Shown(listOf(DocumentLine(true, "Title"), DocumentLine(false, "Body text."))), shown)
        about.toggle(BundledDocument.PRIVACY_POLICY)
        assertFalse(BundledDocument.PRIVACY_POLICY in about.state.value.documents)
    }

    @Test
    fun unreadableDocumentIsReportedAsFailed() {
        val about = controller { throw IOException("missing") }
        about.toggle(BundledDocument.THIRD_PARTY_LICENCES)
        assertEquals(DocumentState.Failed, about.state.value.documents[BundledDocument.THIRD_PARTY_LICENCES])
    }

    @Test
    fun documentClosedWhileLoadingStaysClosed() {
        val pending = CompletableDeferred<String>()
        val about = controller { pending.await() }
        about.toggle(BundledDocument.THIRD_PARTY_LICENCES)
        assertEquals(DocumentState.Loading, about.state.value.documents[BundledDocument.THIRD_PARTY_LICENCES])
        about.toggle(BundledDocument.THIRD_PARTY_LICENCES)
        pending.complete("late text")
        assertTrue(about.state.value.documents.isEmpty())
    }
}
