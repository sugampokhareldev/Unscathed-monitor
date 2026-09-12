package com.unscathed.monitor.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLinksTest {
    @Test
    fun parsesPlaceIdFromCommonInputs() {
        assertEquals("123456789", GameLinks.parsePlaceId("https://www.roblox.com/games/123456789/Unscathed"))
        assertEquals("123456789", GameLinks.parsePlaceId("https://www.roblox.com/de/games/123456789/Unscathed?x=1"))
        assertEquals("123456789", GameLinks.parsePlaceId("roblox://experiences/start?placeId=123456789"))
        assertEquals("123456789", GameLinks.parsePlaceId(" 123456789 "))
        assertNull(GameLinks.parsePlaceId("https://www.roblox.com/home"))
        assertNull(GameLinks.parsePlaceId("12"))
    }

    @Test
    fun detectsPrivateServerLinks() {
        assertTrue(GameLinks.isPrivateServerLink("https://www.roblox.com/share?code=abc123&type=Server"))
        assertTrue(GameLinks.isPrivateServerLink("https://www.roblox.com/games/1/x?privateServerLinkCode=999"))
        assertFalse(GameLinks.isPrivateServerLink("https://www.roblox.com/games/123/Unscathed"))
    }

    @Test
    fun launchOrderIsPrivateServerThenDeepLinkThenWeb() {
        assertEquals(
            listOf(
                "https://www.roblox.com/share?code=abc&type=Server",
                "roblox://experiences/start?placeId=42000",
                "https://www.roblox.com/games/42000",
            ),
            GameLinks.launchUris("42000", " https://www.roblox.com/share?code=abc&type=Server "),
        )
        assertEquals(emptyList<String>(), GameLinks.launchUris("", ""))
    }

    @Test
    fun unscathedIsTheDefaultGame() {
        assertEquals("Unscathed", GameCatalog.default.name)
        assertEquals(GameCatalog.UNSCATHED, GameCatalog.byId("unknown.id"))
        assertTrue(Feature.KEEP_RETRYING in GameCatalog.UNSCATHED.defaultFeatures)
        assertFalse(Feature.REJOIN_WHEN_FROZEN in GameCatalog.UNSCATHED.defaultFeatures)
        assertTrue(Feature.AUTO_PLAY in GameCatalog.UNSCATHED.defaultFeatures)
        assertTrue(Feature.EVENT_ALERTS in GameCatalog.UNSCATHED.defaultFeatures)
        assertTrue(GameCatalog.UNSCATHED.events.any { it.id == "dark_arts_merchant" })
        assertEquals("122951224417794", GameCatalog.UNSCATHED.suggestedPlaceId)
    }
}
