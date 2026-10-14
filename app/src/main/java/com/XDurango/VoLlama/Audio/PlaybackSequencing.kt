package com.XDurango.VoLlama.Audio

/**
 * Política pura de qué hacer según el número de secuencia (16 bits, con wrap) del paquete recibido
 * frente al esperado. Vive aparte de AudioEngine para poder probarla sin hardware de audio.
 */
internal sealed interface GapAction {
    /** Paquete esperado: decodificar normal. */
    data object InOrder : GapAction

    /** Se perdieron [count] paquetes (1..[PlaybackSequencing.MAX_CONCEALED]): rellenar con PLC. */
    data class Conceal(val count: Int) : GapAction

    /** Pérdida grande: el estado del decoder ya no sirve, reiniciarlo. */
    data object ResetDecoder : GapAction

    /** Duplicado o paquete tardío (anterior al esperado): descartar sin tocar el decoder. */
    data object Drop : GapAction
}

internal object PlaybackSequencing {
    const val MAX_CONCEALED = 2
    private const val SEQ_MASK = 0xFFFF
    private const val HALF_RANGE = 0x8000

    fun classify(expectedSeq: Int, seq: Int): GapAction {
        val gap = (seq - expectedSeq) and SEQ_MASK
        return when {
            gap == 0 -> GapAction.InOrder
            gap <= MAX_CONCEALED -> GapAction.Conceal(gap)
            gap < HALF_RANGE -> GapAction.ResetDecoder
            else -> GapAction.Drop // seq "en el pasado" (módulo 2^16)
        }
    }

    fun next(seq: Int): Int = (seq + 1) and SEQ_MASK
}
