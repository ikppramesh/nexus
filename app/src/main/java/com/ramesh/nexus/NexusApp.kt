package com.ramesh.nexus

import android.app.Application
import kotlin.concurrent.thread

class NexusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engines.init(this)
        thread(name = "nexus-preload", priority = Thread.MIN_PRIORITY) {
            runCatching { Engines.preload() }
        }
    }
}
