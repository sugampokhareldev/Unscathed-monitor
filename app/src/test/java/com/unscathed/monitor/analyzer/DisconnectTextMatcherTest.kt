package com.unscathed.monitor.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DisconnectTextMatcherTest {
    private val reconnectBox = NormBox(0.55f, 0.6f, 0.7f, 0.66f)
    private val leaveBox = NormBox(0.3f, 0.6f, 0.45f, 0.66f)

    @Test
    fun matchesStandardDisconnectDialog() {
        val m = DisconnectTextMatcher.match(
            listOf(
                OcrLine("Disconnected"),
                OcrLine("Lost connection to the game server, please reconnect"),
                OcrLine("(Error Code: 277)"),
                OcrLine("Leave", leaveBox),
                OcrLine("Reconnect", reconnectBox),
            ),
        )
        assertNotNull(m)
        assertEquals(277, m!!.errorCode)
        assertEquals(reconnectBox, m.reconnectButton)
        assertEquals(leaveBox, m.leaveButton)
        assertEquals("Error 277: Lost connection to the game server", m.description)
    }

    @Test
    fun toleratesOcrSpacingAndCase() {
        val m = DisconnectTextMatcher.match(listOf(OcrLine("ERROR  CODE : 279")))
        assertEquals(279, m?.errorCode)
    }

    @Test
    fun buttonPairAloneIsEnough() {
        val m = DisconnectTextMatcher.match(listOf(OcrLine("Leave", leaveBox), OcrLine("Reconnect", reconnectBox)))
        assertNotNull(m)
        assertNull(m!!.errorCode)
    }

    @Test
    fun phraseInsideMessageIsNotTreatedAsButton() {
        val m = DisconnectTextMatcher.match(listOf(OcrLine("Disconnected"), OcrLine("please reconnect later")))
        assertNotNull(m)
        assertNull(m!!.reconnectButton)
    }

    @Test
    fun ordinaryGameTextDoesNotMatch() {
        assertNull(DisconnectTextMatcher.match(listOf(OcrLine("Coins: 1,204"), OcrLine("Shop"), OcrLine("Leave"))))
        assertNull(DisconnectTextMatcher.match(emptyList()))
    }

    @Test
    fun parsesNoRecoverCodeList() {
        assertEquals(setOf(268, 273), RobloxErrorCodes.parseCodeList(" 268, 273 ;x"))
    }
}
