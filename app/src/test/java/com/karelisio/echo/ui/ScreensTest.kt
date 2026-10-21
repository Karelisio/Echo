package com.karelisio.echo.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.karelisio.echo.AppContainer
import com.karelisio.echo.TestEchoApp
import com.karelisio.echo.data.history.EchoDirection
import com.karelisio.echo.data.pairing.PairCredentials
import com.karelisio.echo.data.pairing.PairingState
import com.karelisio.echo.ui.theme.EchoTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rendu réel des écrans (Robolectric) : parcours principal de l'appli, sans relais configuré. */
@RunWith(AndroidJUnit4::class)
class ScreensTest {

    private companion object {
        // Large : la CI (ou un build parallèle) peut ralentir fortement Robolectric.
        const val TIMEOUT_MS = 20_000L
    }

    @get:Rule
    val compose = createComposeRule()

    private val container: AppContainer
        get() = ApplicationProvider.getApplicationContext<TestEchoApp>().container

    private fun showApp() {
        compose.setContent { EchoTheme { EchoRoot(container, destination = null, onDestinationHandled = {}) } }
    }

    /** Attend qu'un texte soit à l'écran (chargements asynchrones, animations d'entrée). */
    private fun awaitDisplayed(text: String) {
        compose.waitUntil(TIMEOUT_MS) {
            runCatching { compose.onNodeWithText(text).assertIsDisplayed() }.isSuccess
        }
    }

    /** Clic par la sémantique : l'écran Robolectric (320×470) cache parfois les éléments sous les barres ou le clavier. */
    private fun click(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        try {
            compose.waitUntil(TIMEOUT_MS) {
                compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            println(compose.onRoot(useUnmergedTree = true).printToString())
            throw e
        }
    }

    @Test
    fun premierLancementPuisSaisieDeCode() {
        showApp()
        awaitDisplayed("Commencer")
        compose.onNodeWithText("Commencer").performClick()
        awaitDisplayed("Autoriser les notifications")
        compose.onNodeWithText("Plus tard").performClick()
        awaitDisplayed("Même en Ne pas déranger")
        awaitDisplayed("Autoriser l’accès")
        compose.onNodeWithText("Plus tard").performClick()
        awaitDisplayed("Recevoir appli fermée")
        compose.onNodeWithText("Plus tard").performClick()

        waitForText("Reliez vos téléphones")
        assertEquals(true, runBlocking { container.settings.current().onboardingDone })
        compose.onNodeWithText("J’ai un code").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("ab2-cd9x")
        // Saisie normalisée : majuscules, alphabet du relais, 6 caractères.
        compose.onNodeWithText("AB2CD9").assertIsDisplayed()
        click("Retour")
        waitForText("Afficher un code")
    }

    @Test
    fun accueilHistoriqueEtReglages() {
        runBlocking { container.settings.setOnboardingDone() }
        container.pairingStore.savePaired(PairCredentials("pair-de-test-0123456789", "secret", "a"), System.currentTimeMillis())
        showApp()

        // Accueil : envoi sans relais configuré → message d'erreur explicite, pas de plantage.
        waitForText("Touche pour envoyer un écho")
        compose.onNodeWithContentDescription("Envoyer un écho 💜").performClick()
        waitForText("L’adresse du relais n’est pas configurée", substring = true)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Cœur battant"))
        compose.onNodeWithText("Ronron").assertExists()

        // Historique : vide, puis un écho reçu.
        compose.onNodeWithText("Historique").performClick()
        waitForText("Rien pour l’instant")
        runBlocking { container.history.record(EchoDirection.RECEIVED, "🫶", "purr", System.currentTimeMillis()) }
        waitForText("Reçu")
        compose.onNodeWithText("Ronron").assertIsDisplayed()

        // Réglages : interrupteur Ne pas déranger et guide de dépannage.
        compose.onNodeWithText("Réglages").performClick()
        waitForText("Votre lien")
        compose.onNodeWithText("Reliés depuis le", substring = true).assertTextContains("2026", substring = true)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Vibrer même en Ne pas déranger"))
        click("Vibrer même en Ne pas déranger")
        // L'écriture DataStore est asynchrone : on attend que l'interrupteur affiche le nouvel état.
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasText("Vibrer même en Ne pas déranger") and isOff()).fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse(runBlocking { container.settings.current().vibrateInDnd })
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Mon téléphone ne vibre pas"))
        click("Mon téléphone ne vibre pas")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("1. Lancement des applications"))
        compose.onNodeWithText("1. Lancement des applications").assertExists()
    }

    @Test
    fun codeAfficheSurLeTelephoneA() {
        runBlocking { container.settings.setOnboardingDone() }
        container.pairingStore.savePending(
            PairCredentials("pair-de-test-0123456789", "secret", "a"),
            code = "K7M2QX",
            expiresAt = System.currentTimeMillis() + 9 * 60_000L,
        )
        showApp()
        waitForText("Ton code")
        "K7M2QX".forEach { compose.onNodeWithText(it.toString()).assertIsDisplayed() }
        compose.onNodeWithText("En attente de l’autre téléphone…").assertIsDisplayed()
        compose.onNodeWithText("Annuler").performClick()
        waitForText("Afficher un code")
        assertEquals(PairingState.Unpaired, container.pairingStore.current())
    }
}
