package com.XDurango.VoLlama.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.XDurango.VoLlama.CallMode.CallEndReason
import com.XDurango.VoLlama.CallMode.CallEvent
import com.XDurango.VoLlama.CallMode.CallScreenContent
import com.XDurango.VoLlama.CallMode.CallState
import com.XDurango.VoLlama.CallMode.CallUiState
import com.XDurango.VoLlama.ui.theme.VoLlamaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Capa de UI de la pantalla de llamada: estados visibles y eventos que emiten los botones. */
class CallScreenTest {

    @get:Rule val rule = createComposeRule()

    private val events = mutableListOf<CallEvent>()

    private fun show(
        name: String = "Pixel 7",
        callState: CallState = CallState.IN_CALL,
        ui: CallUiState = CallUiState(isInCall = true)
    ) = rule.setContent {
        VoLlamaTheme {
            CallScreenContent(name, "ep-1", callState, ui, onEvent = { events += it })
        }
    }

    @Test fun mientrasConecta_muestraLlamando() {
        show(callState = CallState.CONNECTING)
        rule.onNodeWithText("Llamando...").assertIsDisplayed()
    }

    @Test fun enLlamada_muestraNombreYEstado() {
        show()
        rule.onNodeWithText("Pixel 7").assertIsDisplayed()
        rule.onNodeWithText("Llamada en curso • ep-1").assertIsDisplayed()
    }

    @Test fun rechazada_muestraMensaje() {
        show(callState = CallState.ENDED, ui = CallUiState(endReason = CallEndReason.REJECTED))
        rule.onNodeWithText("Llamada rechazada").assertIsDisplayed()
    }

    @Test fun muestraInicialYDuracion() {
        show(ui = CallUiState(isInCall = true, callDuration = 65_000))
        rule.onNodeWithText("P").assertIsDisplayed()
        rule.onNodeWithText("01:05").assertIsDisplayed()
    }

    @Test fun sinNombre_usaSignoDeInterrogacion() {
        show(name = "")
        rule.onNodeWithText("?").assertIsDisplayed()
    }

    @Test fun botonSilenciar_emiteToggleMute() {
        show()
        rule.onNodeWithContentDescription("Silenciar").performClick()
        assertEquals(listOf<CallEvent>(CallEvent.ToggleMute), events)
    }

    @Test fun silenciado_cambiaLaEtiqueta() {
        show(ui = CallUiState(isInCall = true, isMuted = true))
        rule.onNodeWithText("Silenciado").assertIsDisplayed()
    }

    @Test fun botonAltavoz_emiteToggleSpeaker() {
        show()
        rule.onNodeWithContentDescription("Altavoz").performClick()
        assertEquals(listOf<CallEvent>(CallEvent.ToggleSpeaker), events)
    }

    @Test fun botonColgar_emiteEndCall() {
        show()
        rule.onNodeWithContentDescription("Finalizar llamada").performClick()
        assertEquals(listOf<CallEvent>(CallEvent.EndCall), events)
    }

    @Test fun colgarDosVeces_emiteDosEventos_yElManagerDebeSerIdempotente() {
        // La UI no filtra toques repetidos; la idempotencia es responsabilidad de CallManager.
        show()
        repeat(2) { rule.onNodeWithContentDescription("Finalizar llamada").performClick() }
        assertEquals(2, events.count { it == CallEvent.EndCall })
    }
}
