package com.karelisio.echo.widget

import android.content.Context
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.testing.unit.hasStartActivityClickAction
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.karelisio.echo.MainActivity
import com.karelisio.echo.data.history.EchoDirection
import com.karelisio.echo.data.history.EchoEvent
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EchoWidgetContentTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L

    private fun state(
        paired: Boolean = true,
        status: WidgetStatus = WidgetStatus.Idle,
        lastReceivedAgoMs: Long? = 7 * 60_000L,
    ) = WidgetUiState(
        paired = paired,
        emoji = "🫶",
        lastReceived = lastReceivedAgoMs?.let {
            EchoEvent(id = 1, direction = EchoDirection.RECEIVED, emoji = "💜", pattern = "heartbeat", timestamp = now - it)
        },
        status = status,
        now = now,
    )

    @Test
    fun carreAfficheLeDernierEchoEtEnvoieAuTap() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(EchoWidget.SQUARE)
        provideComposable { WidgetContent(state()) }
        onNode(hasText("🫶")).assertExists()
        onNode(hasText("💜 il y a 7 min")).assertExists()
        onNode(hasRunCallbackClickAction<SendEchoAction>()).assertExists()
    }

    @Test
    fun formatLargeEtLibelleArrondi() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(EchoWidget.LARGE)
        provideComposable { WidgetContent(state(lastReceivedAgoMs = 23 * 60_000L)) }
        onNode(hasText("Dernier écho reçu")).assertExists()
        onNode(hasText("💜 il y a 20 min")).assertExists()
    }

    @Test
    fun etatsDEnvoi() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(EchoWidget.WIDE)
        provideComposable { WidgetContent(state(status = WidgetStatus.Failed)) }
        onNode(hasText("Échec, réessaie")).assertExists()
    }

    @Test
    fun envoiReussi() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(EchoWidget.SQUARE)
        provideComposable { WidgetContent(state(status = WidgetStatus.Sent, lastReceivedAgoMs = null)) }
        onNode(hasTextEqualTo("✓")).assertExists()
        onNode(hasText("Envoyé ✓")).assertExists()
    }

    @Test
    fun nonRelieOuvreLAppli() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(EchoWidget.SQUARE)
        provideComposable { WidgetContent(state(paired = false)) }
        onNode(hasText("Ouvre Écho pour relier vos téléphones")).assertExists()
        onNode(hasStartActivityClickAction<MainActivity>()).assertExists()
    }
}
