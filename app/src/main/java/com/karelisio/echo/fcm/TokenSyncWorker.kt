package com.karelisio.echo.fcm

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.karelisio.echo.appContainer
import com.karelisio.echo.data.relay.RelayException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Transmet au relais un jeton FCM renouvelé (onNewToken) ou modifié depuis le dernier envoi.
 * Passe par WorkManager pour attendre le réseau et réessayer avec un délai croissant.
 */
class TokenSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        applicationContext.appContainer.pairing.syncToken(inputData.getString(KEY_TOKEN))
        Result.success()
    } catch (e: RelayException) {
        when {
            e.isNetwork || e.status >= 500 || e.code == "rate_limited" -> retryOrFail()
            else -> Result.failure() // paire inconnue, secret refusé… : rien à réessayer
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Jeton FCM indisponible (services Google momentanément injoignables).
        retryOrFail()
    }

    private fun retryOrFail(): Result = if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

    companion object {
        private const val WORK_NAME = "fcm-token-sync"
        private const val KEY_TOKEN = "token"
        private const val MAX_ATTEMPTS = 8

        fun enqueue(context: Context, token: String? = null) {
            val request = OneTimeWorkRequestBuilder<TokenSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_TOKEN to token))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
