package com.karelisio.echo.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.karelisio.echo.appContainer
import com.karelisio.echo.util.RelativeTime
import java.util.concurrent.TimeUnit

/**
 * Met à jour « il y a 5 min » sur le widget exactement quand le libellé change (chaque minute,
 * puis toutes les 5 min, chaque heure, chaque jour) au lieu d'un rafraîchissement périodique.
 * Sans contrainte particulière : en veille profonde, le système regroupe ces mises à jour,
 * ce qui est sans importance puisque personne ne regarde l'écran.
 */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        applicationContext.appContainer.widget.refresh()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "widget-refresh"
        private const val MARGIN_MS = 500L

        fun schedule(context: Context, lastReceivedAt: Long?) {
            val delay = lastReceivedAt?.let { RelativeTime.nextChangeIn(it, System.currentTimeMillis()) }
            if (delay == null) {
                cancel(context)
                return
            }
            val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setInitialDelay(delay + MARGIN_MS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
