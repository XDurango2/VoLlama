package com.XDurango.VoLlama

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.XDurango.VoLlama.Audio.OpusCodec
import com.steegler.koopus.OpusRepacketizer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/**
 * Capa de codec: ejercita libkoopus.so real (necesita dispositivo/emulador).
 * Cubre los crashes vistos en uso: JNI sin compilar (UnsatisfiedLinkError), handle nativo mal
 * resuelto y decodeAggregated sobre paquetes de 2 frames.
 */
@RunWith(AndroidJUnit4::class)
class OpusCodecInstrumentedTest {

    private lateinit var codec: OpusCodec

    @Before fun setUp() { codec = OpusCodec() }

    @After fun tearDown() { codec.close() }

    private fun tone(): ShortArray = ShortArray(OpusCodec.FRAME_SIZE_SAMPLES) { i ->
        (sin(2 * PI * 440 * i / OpusCodec.SAMPLE_RATE) * 8000).toInt().toShort()
    }

    @Test fun open_esIdempotente() {
        codec.open()
        codec.open()
        assertNotNull(codec.encode(tone()))
    }

    @Test fun encode_antesDeOpen_devuelveNull() {
        assertNull(codec.encode(tone()))
        assertNull(codec.decode(ByteArray(0)))
    }

    @Test fun encode_producePaqueteNoVacio_yMenorQuePCM() {
        codec.open()
        val packet = codec.encode(tone())
        assertNotNull(packet)
        assertTrue(packet!!.isNotEmpty())
        assertTrue("el paquete Opus debe comprimir el PCM", packet.size < OpusCodec.FRAME_SIZE_SAMPLES * 2)
    }

    @Test fun roundTrip_decodificaUnFrameCompleto() {
        codec.open()
        val pcm = codec.decode(codec.encode(tone()))
        assertNotNull(pcm)
        assertEquals(OpusCodec.FRAME_SIZE_SAMPLES, pcm!!.size)
    }

    @Test fun decode_conNull_generaPLC() {
        codec.open()
        codec.decode(codec.encode(tone()))
        val concealed = codec.decode(null)
        assertNotNull(concealed)
        assertEquals(OpusCodec.FRAME_SIZE_SAMPLES, concealed!!.size)
    }

    @Test fun decodeAggregated_paqueteDe2Frames_devuelve40ms() {
        codec.open()
        val a = codec.encode(tone())!!
        val b = codec.encode(tone())!!
        val repacketizer = OpusRepacketizer.create().init()
        try {
            repacketizer.cat(a)
            repacketizer.cat(b)
            val out = ByteArray(4000)
            val len = repacketizer.out(out)
            assertTrue(len > 0)

            val pcm = codec.decodeAggregated(out.copyOf(len), OpusCodec.FRAME_SIZE_SAMPLES * 2)
            assertNotNull("decodeAggregated fallo con paquete de 2 frames", pcm)
            assertEquals(OpusCodec.FRAME_SIZE_SAMPLES * 2, pcm!!.size)
        } finally {
            repacketizer.destroy()
        }
    }

    @Test fun decodeAggregated_paqueteDeUnSoloFrame_tambienFunciona() {
        codec.open()
        val single = codec.encode(tone())!!
        val pcm = codec.decodeAggregated(single, OpusCodec.FRAME_SIZE_SAMPLES * 2)
        assertNotNull(pcm)
        assertEquals(OpusCodec.FRAME_SIZE_SAMPLES, pcm!!.size)
    }

    @Test fun close_liberaYPermiteReabrirParaLaSiguienteLlamada() {
        repeat(3) {
            codec.open()
            assertNotNull(codec.encode(tone()))
            codec.close()
            assertNull("tras close() no se debe poder codificar", codec.encode(tone()))
        }
    }

    @Test fun close_sinOpen_noFalla() {
        codec.close()
        codec.close()
    }

    @Test fun resetDecoder_conCodecCerrado_noFalla() {
        codec.resetDecoder()
        codec.open()
        codec.resetDecoder()
        assertNotNull(codec.decode(codec.encode(tone())))
    }

    @Test fun unRepacketizerSeCreaYDestruyeSinExcepciones() {
        // Regresion: nativeCreate lanzaba UnsatisfiedLinkError porque el .cpp no se compilaba.
        OpusRepacketizer.create().init().destroy()
    }
}
