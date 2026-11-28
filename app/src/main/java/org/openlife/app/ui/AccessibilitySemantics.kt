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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay

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

    /**
     * Drops [announcement] from the host once its hold time has passed. A newer
     * message is left alone: only the exact message that was held is cleared.
     */
    internal fun expire(announcement: Announcement) {
        if (current == announcement) {
            owner = null
            current = null
        }
    }
}

/** How long a posted message stays on the host node before it is dropped (P1-10 review fix). */
internal const val STATUS_HOLD_MILLIS = 4_000L

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
 * nothing to say it has no text, so TalkBack has nothing to focus. A message
 * stays on the node only for a few seconds (see [StatusChannel]), so linear
 * navigation does not read it a second time (runbook A41).
 *
 * Place it outside any `when`, `if`, `verticalScroll` or lazy list; a dialog is
 * its own window and needs its own host. The announcement never carries
 * document content (OCR text, span text, file names, queries): such text is
 * shown as visible text only.
 */
@Composable
fun StatusHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val channel = remember { StatusChannel() }
    val resources = LocalResources.current
    val held = channel.current
    // Keyed on the message: a newer message restarts the timer, so an older one never clears it.
    LaunchedEffect(held) {
        if (held != null) {
            delay(STATUS_HOLD_MILLIS)
            channel.expire(held)
        }
    }
    Box(modifier) {
        CompositionLocalProvider(LocalStatusChannel provides channel) { content() }
        LiveNode(channel.current?.resolve(resources), Modifier.align(Alignment.TopStart).testTag(STATUS_NODE_TAG))
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
