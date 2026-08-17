package com.openchat

import android.app.Application
import java.io.File

class OpenChatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(filesDir, CrashFileName).writeText(error.stackTraceToString()) }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CrashFileName = "last-crash.txt"
    }
}
