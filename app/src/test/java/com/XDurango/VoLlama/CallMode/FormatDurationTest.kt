package com.XDurango.VoLlama.CallMode

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatDurationTest {
    @Test fun `cero`() = assertEquals("00:00", formatDuration(0))
    @Test fun `menos de un segundo se trunca`() = assertEquals("00:00", formatDuration(999))
    @Test fun `segundos`() = assertEquals("00:59", formatDuration(59_999))
    @Test fun `minutos y segundos`() = assertEquals("01:05", formatDuration(65_000))
    @Test fun `mas de una hora sigue contando minutos`() = assertEquals("61:01", formatDuration(3_661_000))
}
