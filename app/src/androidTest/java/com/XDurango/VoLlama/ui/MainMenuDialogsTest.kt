package com.XDurango.VoLlama.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.XDurango.VoLlama.MainMenu.ConnectionErrorDialog
import com.XDurango.VoLlama.MainMenu.IncomingCallDialog
import com.XDurango.VoLlama.Nearby.NearbyConnectionService.NearbyStatus
import com.XDurango.VoLlama.ui.theme.VoLlamaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Capa de UI del menú principal: diálogos de llamada entrante y de error de conexión. */
class MainMenuDialogsTest {

    @get:Rule val rule = createComposeRule()

    @Test fun llamadaEntrante_aceptaYRechaza() {
        var accepted = 0
        var rejected = 0
        rule.setContent {
            VoLlamaTheme {
                IncomingCallDialog("Pixel", onDismissRequest = { rejected++ }, onConfirmation = { accepted++ })
            }
        }
        rule.onNodeWithText("Solicitud de llamada").assertIsDisplayed()
        rule.onNodeWithText("Aceptar llamada").performClick()
        rule.onNodeWithText("Rechazar").performClick()

        assertEquals(1, accepted)
        assertEquals(1, rejected)
    }

    @Test fun walkieTalkieEntrante_usaTextosPropios() {
        rule.setContent {
            VoLlamaTheme {
                IncomingCallDialog("Pixel", isWalkieTalkie = true, onDismissRequest = {}, onConfirmation = {})
            }
        }
        rule.onNodeWithText("Solicitud de Walkie-Talkie").assertIsDisplayed()
        rule.onNodeWithText("Aceptar").assertIsDisplayed()
    }

    private fun showError(status: NearbyStatus) =
        rule.setContent { VoLlamaTheme { ConnectionErrorDialog(status, onConfirmation = {}) } }

    @Test fun errorDeConexion_rechazada() {
        showError(NearbyStatus.REJECTED)
        rule.onNodeWithText("Conexión Rechazada").assertIsDisplayed()
    }

    @Test fun errorDeConexion_errorDeRed() {
        showError(NearbyStatus.CONNECTION_ERROR)
        rule.onNodeWithText("Hubo un error de conexión, intente nuevamente").assertIsDisplayed()
    }

    @Test fun errorDeConexion_otroEstado_esDesconocido() {
        showError(NearbyStatus.IDLE)
        rule.onNodeWithText("Error desconocido").assertIsDisplayed()
    }
}
