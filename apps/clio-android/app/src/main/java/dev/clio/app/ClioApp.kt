package dev.clio.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide scope for work that must outlive a screen (e.g. the final draft save). */
class ClioApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
