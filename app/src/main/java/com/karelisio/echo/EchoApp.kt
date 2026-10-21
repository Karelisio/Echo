package com.karelisio.echo

import android.app.Application
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

open class EchoApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        container.notifications.ensureChannels()
        if (runsStartupWork) {
            container.observeWidgetInputs()
            container.appScope.launch(Dispatchers.IO) { container.onAppStart() }
        }
    }

    /** Surchargé par les tests (préférences non chiffrées, base en mémoire…). */
    protected open fun createContainer(): AppContainer = AppContainer(this)

    protected open val runsStartupWork: Boolean get() = true
}

val Context.appContainer: AppContainer
    get() = (applicationContext as EchoApp).container
