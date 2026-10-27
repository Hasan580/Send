package com.hassanazawi.send

import android.app.Application
import com.hassanazawi.send.net.Engine

class SendApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
    }
}
