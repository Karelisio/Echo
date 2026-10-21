package com.karelisio.echo.echo

import org.junit.Assert.assertEquals
import org.junit.Test

class EchoReceiverTest {

    @Test
    fun nettoieLEmojiRecu() {
        assertEquals("💜", EchoReceiver.sanitizeEmoji(null))
        assertEquals("💜", EchoReceiver.sanitizeEmoji("   "))
        assertEquals("❤️‍🔥", EchoReceiver.sanitizeEmoji("  ❤️‍🔥 "))
        assertEquals("ab", EchoReceiver.sanitizeEmoji("a\u0000\nb"))
        assertEquals(32, EchoReceiver.sanitizeEmoji("x".repeat(100)).length)
    }
}
