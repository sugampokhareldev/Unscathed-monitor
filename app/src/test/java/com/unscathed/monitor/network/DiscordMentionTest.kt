package com.unscathed.monitor.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordMentionTest {
    private val id = "123456789012345678"

    @Test
    fun acceptsRawAndFormattedIds() {
        assertEquals(id, DiscordMention.parseUserId(id))
        assertEquals(id, DiscordMention.parseUserId(" <@$id> "))
        assertEquals(id, DiscordMention.parseUserId("<@!$id>"))
    }

    @Test
    fun usernamesAndRolesAreNotUserIds() {
        assertNull(DiscordMention.parseUserId("@noobeiiks"))
        assertNull(DiscordMention.parseUserId("noobeiiks"))
        assertNull(DiscordMention.parseUserId("<@&$id>"))
        assertNull(DiscordMention.parseUserId("12345"))
    }

    @Test
    fun contentPingsWithIdOtherwiseShowsName() {
        assertEquals("<@$id>", DiscordMention.content(id, "noobeiiks"))
        assertEquals("@noobeiiks", DiscordMention.content(null, "@noobeiiks"))
        assertNull(DiscordMention.content(null, ""))
    }

    @Test
    fun userAskedEventsPingByDefault() {
        val pings = AlertEvent.defaultPings
        listOf(
            AlertEvent.DISCONNECTED, AlertEvent.RECONNECTED, AlertEvent.RECONNECT_FAILED,
            AlertEvent.MERCHANT,
        ).forEach { assertTrue("$it should ping", it in pings) }
        assertTrue(AlertEvent.STATUS !in pings)
    }
}
