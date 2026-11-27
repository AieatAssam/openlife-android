package org.openlife.app.ui

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** P1-10: marks a title as a heading, so TalkBack's heading navigation reaches it. */
fun Modifier.asHeading(): Modifier = semantics { heading() }

/**
 * P1-10 (review H-01): what a screen's status node says. It holds string and
 * plural resource ids and counts only, so no document content (OCR text,
 * file names, search queries) can reach a live region through it.
 */
@Immutable
class Announcement private constructor(private val parts: List<Part>) {
    private sealed interface Part

    /** [id] formatted with the strings named by [args] (resource ids, not text). */
    private data class Text(
        @StringRes val id: Int,
        val args: List<Int>,
    ) : Part

    private data class Count(
        @PluralsRes val id: Int,
        val count: Int,
    ) : Part

    /** This announcement followed by [other], read as one message. */
    operator fun plus(other: Announcement): Announcement = Announcement(parts + other.parts)

    internal fun resolve(resources: Resources): String = parts.joinToString(" ") { part ->
        when (part) {
            is Text -> resources.getString(part.id, *part.args.map(resources::getString).toTypedArray())
            is Count -> resources.getQuantityString(part.id, part.count, part.count)
        }
    }

    override fun equals(other: Any?): Boolean = other is Announcement && other.parts == parts

    override fun hashCode(): Int = parts.hashCode()

    companion object {
        /** A status string; each of [args] is a string resource id filled into its placeholders. */
        fun statusOf(
            @StringRes id: Int,
            @StringRes vararg args: Int,
        ): Announcement = Announcement(listOf(Text(id, args.toList())))

        /** A plural status for [count] items. */
        fun countOf(
            @PluralsRes id: Int,
            count: Int,
        ): Announcement = Announcement(listOf(Count(id, count)))
    }
}

/** Where [Announce] posts; one per [StatusHost]. Without a host, announcements go nowhere. */
class StatusChannel internal constructor() {
    internal var current by mutableStateOf<Announcement?>(null)
        private set
    private var owner: Any? = null

    internal fun post(announcement: Announcement, poster: Any) {
        owner = poster
        current = announcement
    }

    internal fun clear(poster: Any) {
        if (owner === poster) {
            owner = null
            current = null
        }
    }
}

private val LocalStatusChannel = staticCompositionLocalOf<StatusChannel?> { null }

/**
 * P1-10 (review H-01): the screen's one status node. Compose sends a live-region
 * event only when a node that already exists changes (AndroidComposeView-
 * AccessibilityDelegateCompat.sendSemanticsPropertyChangeEvents skips nodes
 * with no previous copy), so a status Text that appears with a new state branch
 * may never be read. This node is composed once per screen, as the last
 * (top-most) child of a Box at the screen root, so it is never covered,
 * scrolled away or merged into a parent, and its text changes in place.
 *
 * It is 1 dp, not zero: a node with empty bounds is left out of the
 * accessibility tree and would miss its own first change. When there is
 * nothing to say it has no text, so TalkBack has nothing to focus. While it
 * holds a message, linear navigation reads that message here as well as
 * where it is shown (runbook A41).
 *
 * Place it outside any `when`, `if`, `verticalScroll` or lazy list; a dialog is
 * its own window and needs its own host. [spanSelection] is the viewer's
 * selected-span announcement (P2-06): the one live region that carries
 * document text, on purpose, read only by the user's own enabled
 * accessibility service (THREAT_MODEL).
 */
@Composable
fun StatusHost(modifier: Modifier = Modifier, spanSelection: String? = null, content: @Composable () -> Unit) {
    val channel = remember { StatusChannel() }
    val resources = LocalResources.current
    Box(modifier) {
        CompositionLocalProvider(LocalStatusChannel provides channel) { content() }
        LiveNode(channel.current?.resolve(resources), Modifier.align(Alignment.TopStart).testTag(STATUS_NODE_TAG))
        LiveNode(spanSelection, Modifier.align(Alignment.TopEnd).testTag(SPAN_SELECTION_NODE_TAG))
    }
}

/**
 * Posts [announcement] to the enclosing [StatusHost] while this call is in the
 * composition, and clears it when the call leaves (the branch that showed the
 * status is gone). Call it beside the visible status text.
 */
@Composable
fun Announce(announcement: Announcement) {
    val channel = LocalStatusChannel.current ?: return
    val poster = remember { Any() }
    DisposableEffect(channel, announcement) {
        channel.post(announcement, poster)
        onDispose { channel.clear(poster) }
    }
}

@Composable
private fun LiveNode(text: String?, modifier: Modifier) {
    Box(
        modifier.size(1.dp).semantics {
            liveRegion = LiveRegionMode.Polite
            if (!text.isNullOrEmpty()) contentDescription = text
        },
    )
}

/** Test tag of every screen's stable status node. */
const val STATUS_NODE_TAG = "status_announcer"

/** Test tag of the viewer's stable selected-span node (P2-06). */
const val SPAN_SELECTION_NODE_TAG = "viewer_span_announcement"
