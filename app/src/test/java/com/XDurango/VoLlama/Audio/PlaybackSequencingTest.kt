package com.XDurango.VoLlama.Audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** Política de secuencia del receptor: pérdida, wrap de 16 bits, duplicados y paquetes tardíos. */
class PlaybackSequencingTest {

    private fun classify(expected: Int, seq: Int) = PlaybackSequencing.classify(expected, seq)

    @Test fun `paquete esperado es InOrder`() {
        assertEquals(GapAction.InOrder, classify(10, 10))
    }

    @Test fun `perdida de 1 o 2 paquetes se oculta con PLC`() {
        assertEquals(GapAction.Conceal(1), classify(10, 11))
        assertEquals(GapAction.Conceal(2), classify(10, 12))
    }

    @Test fun `perdida de 3 o mas reinicia el decoder`() {
        assertEquals(GapAction.ResetDecoder, classify(10, 13))
        assertEquals(GapAction.ResetDecoder, classify(10, 10 + 0x7FFF))
    }

    @Test fun `duplicado o paquete tardio se descarta sin reiniciar el decoder`() {
        // Antes: (seq - expected) and 0xFFFF == 0xFFFF > 2 => resetDecoder() ante un simple duplicado.
        assertEquals(GapAction.Drop, classify(10, 9))
        assertEquals(GapAction.Drop, classify(10, 5))
        assertEquals(GapAction.Drop, classify(10, 10 + 0x8000))
    }

    @Test fun `el wrap de 16 bits no se confunde con perdida`() {
        assertEquals(GapAction.InOrder, classify(0, 0))
        assertEquals(GapAction.InOrder, classify(0xFFFF, 0xFFFF))
        assertEquals(GapAction.Conceal(1), classify(0xFFFF, 0))
        assertEquals(GapAction.Conceal(2), classify(0xFFFF, 1))
        assertEquals(GapAction.Drop, classify(0, 0xFFFF))
    }

    @Test fun `next da la vuelta en 0xFFFF`() {
        assertEquals(1, PlaybackSequencing.next(0))
        assertEquals(0, PlaybackSequencing.next(0xFFFF))
    }
}
