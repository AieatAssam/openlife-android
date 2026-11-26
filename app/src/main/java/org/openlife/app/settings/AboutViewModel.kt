package org.openlife.app.settings

import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openlife.app.AppContainer
import java.io.IOException

/** A bundled document while it is open on About. */
sealed interface DocumentState {
    data object Loading : DocumentState

    data class Shown(val lines: List<DocumentLine>) : DocumentState

    data object Failed : DocumentState
}

/**
 * What About shows. Facts arrive independently, so a slow Keystore query
 * never hides the version. [documents] holds only the open documents; a
 * closed one is absent. No user content is ever part of this state.
 */
data class AboutState(
    val identity: BuildIdentity? = null,
    val keyLevel: KeySecurityLevel? = null,
    val diagnostics: DiagnosticsCounts? = null,
    val documents: Map<BundledDocument, DocumentState> = emptyMap(),
)

/**
 * P1-08: loads About's facts once and opens or closes the bundled documents.
 * A document is read and split into lines off the main thread (the licence
 * report is tens of kilobytes; P1-08 constraint) and dropped when closed.
 */
class AboutController(
    private val port: AboutPort,
    private val scope: CoroutineScope,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutableState = MutableStateFlow(AboutState())
    val state: StateFlow<AboutState> = mutableState.asStateFlow()

    fun load() {
        scope.launch { port.buildIdentity().let { identity -> mutableState.update { it.copy(identity = identity) } } }
        scope.launch {
            val level = VaultSecurityInfo.levelOf(port.wrappingKey())
            mutableState.update { it.copy(keyLevel = level) }
        }
        scope.launch { port.diagnostics().let { counts -> mutableState.update { it.copy(diagnostics = counts) } } }
    }

    fun toggle(document: BundledDocument) {
        if (document in mutableState.value.documents) {
            mutableState.update { it.copy(documents = it.documents - document) }
            return
        }
        mutableState.update { it.copy(documents = it.documents + (document to DocumentState.Loading)) }
        scope.launch {
            val loaded = read(document)
            // Closed again while loading: keep it closed.
            mutableState.update { current ->
                val stillOpen = document in current.documents
                if (stillOpen) current.copy(documents = current.documents + (document to loaded)) else current
            }
        }
    }

    private suspend fun read(document: BundledDocument): DocumentState = try {
        val text = port.document(document)
        DocumentState.Shown(withContext(parseDispatcher) { DocumentLines.parse(text) })
    } catch (_: IOException) {
        DocumentState.Failed
    } catch (_: Resources.NotFoundException) {
        DocumentState.Failed
    }
}

/** Entry-scoped holder of the [AboutController], built from the container (P1-20-R1). */
class AboutViewModel(port: AboutPort) : ViewModel() {
    val controller = AboutController(port, viewModelScope).also { it.load() }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AboutViewModel(container.about) }
        }
    }
}
