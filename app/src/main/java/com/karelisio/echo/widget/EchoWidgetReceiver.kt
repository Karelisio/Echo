package com.karelisio.echo.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.karelisio.echo.appContainer
import kotlinx.coroutines.launch

class EchoWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = EchoWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        val container = context.appContainer
        container.appScope.launch { container.widget.refresh() }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefreshWorker.cancel(context)
    }
}
