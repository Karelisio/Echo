package com.karelisio.echo.echo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EchoMessageTest {

    @Test
    fun nettoieLaPhrase() {
        assertNull(EchoMessage.clean(null))
        assertNull(EchoMessage.clean(" \n\t "))
        assertEquals("Tu me manques 🌙", EchoMessage.clean("  Tu me\n\tmanques   🌙 "))
        assertEquals("a b", EchoMessage.clean("a\u0000b"))
        assertEquals(EchoMessage.MAX_LENGTH, EchoMessage.clean("x".repeat(500))!!.length)
        assertEquals(EchoMessage.MAX_RECEIVED_LENGTH, EchoMessage.clean("x".repeat(500), EchoMessage.MAX_RECEIVED_LENGTH)!!.length)
    }

    @Test
    fun neCoupeJamaisUnEmojiEnDeux() {
        val text = "a".repeat(EchoMessage.MAX_LENGTH - 1) + "😘"
        assertEquals("a".repeat(EchoMessage.MAX_LENGTH - 1), EchoMessage.clean(text))
        assertEquals("ab😘", EchoMessage.truncate("ab😘c", 4))
    }

    @Test
    fun brouillonSurUneLigne() {
        assertEquals("Bonne nuit ", EchoMessage.draft("Bonne\nnuit "))
        assertEquals(EchoMessage.MAX_LENGTH, EchoMessage.draft("y".repeat(200)).length)
    }
}
