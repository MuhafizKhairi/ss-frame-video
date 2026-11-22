package local.frameshot

import android.app.Application

class FrameshotApplication : Application() {
    lateinit var state: AppState
        private set

    override fun onCreate() {
        super.onCreate()
        state = AppState(this)
    }
}
