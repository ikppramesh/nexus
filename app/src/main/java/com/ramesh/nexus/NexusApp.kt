package com.ramesh.nexus

import android.app.Application

class NexusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Models load on first use, not here: all of them together take about 3 GB, and on a busy
        // phone that pushed the voice into swap and slowed it to a crawl.
        Engines.init(this)
    }
}
