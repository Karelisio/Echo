package com.karelisio.echo.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.karelisio.echo.appContainer
import kotlinx.coroutines.runBlocking

/**
 * Réception des messages « data » haute priorité envoyés par le relais. FCM réveille l'appli même
 * fermée et appelle [onMessageReceived] sur un thread de travail : on y vibre immédiatement.
 */
class EchoMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val container = applicationContext.appContainer
        val data = message.data
        when (data["type"]) {
            "echo" -> container.receiver.onEcho(data)
            "paired" -> runBlocking {
                container.pairing.onRemotePaired(data["pairId"])
                container.widget.refresh()
            }
            "unpaired" -> runBlocking {
                if (container.pairing.onRemoteUnpaired(data["pairId"])) {
                    container.notifications.showUnpaired()
                    container.widget.refresh()
                }
            }
        }
    }

    // Jeton FCM renouvelé : on le transmet au relais (voir PairingRepository.currentFcmToken
    // pour le choix de l'API historique, dépréciée mais toujours celle activée par défaut).
    @Deprecated("Remplacé par onRegistered() quand l'enregistrement « v1 » de FCM est activé")
    override fun onNewToken(token: String) {
        TokenSyncWorker.enqueue(applicationContext, token)
    }

    /** Équivalent de [onNewToken] pour la nouvelle API d'enregistrement de firebase-messaging 26+. */
    override fun onRegistered(token: String) {
        TokenSyncWorker.enqueue(applicationContext, token)
    }
}
