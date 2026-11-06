package fr.astreinte.relais

import android.app.Application
import fr.astreinte.relais.data.Store
import fr.astreinte.relais.sys.Notifier

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifier.createChannels(this)
    }
}
