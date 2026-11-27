package org.openlife.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openlife.app.R

/**
 * P1-10 review fix 3: the status host holds a message only for a while. The
 * timer lives in [StatusHost]; the clearing decision is [StatusChannel.expire],
 * which these tests drive directly (no sleeps, no coroutines).
 */
class StatusChannelTest {
    private val older = Announcement.statusOf(R.string.list_title)
    private val newer = Announcement.statusOf(R.string.cancel_action)

    @Test
    fun expireClearsTheHeldMessage() {
        val channel = StatusChannel()
        val poster = Any()
        channel.post(older, poster)

        channel.expire(older)

        assertNull(channel.current)
    }

    @Test
    fun expireOfAnOlderMessageLeavesTheNewerOne() {
        val channel = StatusChannel()
        channel.post(older, Any())
        channel.post(newer, Any())

        channel.expire(older)

        assertEquals(newer, channel.current)
    }

    @Test
    fun clearIgnoresAPosterThatDoesNotOwnTheMessage() {
        val channel = StatusChannel()
        val first = Any()
        channel.post(older, first)
        channel.post(newer, Any())

        channel.clear(first)

        assertEquals(newer, channel.current)
    }
}
