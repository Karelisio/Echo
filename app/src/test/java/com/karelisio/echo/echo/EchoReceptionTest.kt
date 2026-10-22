package com.karelisio.echo.echo

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.karelisio.echo.TestEchoApp
import com.karelisio.echo.data.history.EchoDirection
import com.karelisio.echo.data.pairing.PairCredentials
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Réception d'un écho (comme depuis FCM) : historique et notification avec la phrase de l'autre. */
@RunWith(AndroidJUnit4::class)
class EchoReceptionTest {

    private val app = ApplicationProvider.getApplicationContext<TestEchoApp>()
    private val container = app.container

    private fun echo(text: String?, sentAt: Long) = buildMap {
        put("type", "echo")
        put("pairId", PAIR_ID)
        put("emoji", "🫶")
        put("pattern", "purr")
        put("sentAt", sentAt.toString())
        if (text != null) put("text", text)
    }

    private fun shownNotification(): Notification =
        shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()

    @Test
    fun affichePuisEnregistreLaPhraseRecue() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        container.pairingStore.savePaired(PairCredentials(PAIR_ID, "secret", "a"), System.currentTimeMillis())

        val now = System.currentTimeMillis()
        container.receiver.onEcho(echo(" Tu me\nmanques ", sentAt = now - 1_000))
        val received = runBlocking { container.history.lastReceivedNow() }!!
        assertEquals(EchoDirection.RECEIVED, received.direction)
        assertEquals("Tu me manques", received.message)
        val notification = shownNotification()
        assertEquals("🫶 Écho reçu", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Tu me manques", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        // Réponse directe : champ de saisie avec les suggestions, puis « Renvoyer un écho ».
        val answer = notification.actions[0]
        assertEquals("Répondre", answer.title.toString())
        assertEquals("Je pense à toi", answer.remoteInputs.single().choices.first().toString())
        assertEquals("Renvoyer un écho", notification.actions[1].title.toString())

        // Sans phrase (ancienne version de l'appli chez l'autre) : texte par défaut.
        container.receiver.onEcho(echo(null, sentAt = now))
        assertNull(runBlocking { container.history.lastReceivedNow() }!!.message)
        assertEquals("Quelqu’un pense à toi", shownNotification().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    private companion object {
        const val PAIR_ID = "pair-de-test-0123456789"
    }
}
