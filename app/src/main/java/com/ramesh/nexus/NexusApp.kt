package com.ramesh.nexus

import android.app.Application
import kotlin.concurrent.thread

class NexusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engines.init(this)
        thread(name = "nexus-preload") {
            runCatching { Engines.preload() }
        }
    }
}
