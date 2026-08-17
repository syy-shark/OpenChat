package com.openchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.openchat.session.OpenChatSession
import com.openchat.ui.shell.CrashScreen
import com.openchat.ui.shell.OpenChatApp
import java.io.File

class MainActivity : ComponentActivity() {
    private val session by lazy { OpenChatSession(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crash = File(filesDir, OpenChatApplication.CrashFileName)
        if (crash.isFile) {
            val stack = runCatching { crash.readText() }.getOrElse { crash.absolutePath }
            setContent {
                CrashScreen(stack) {
                    crash.delete()
                    recreate()
                }
            }
            return
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(ChromeColor, ChromeColor),
            navigationBarStyle = SystemBarStyle.light(ChromeColor, ChromeColor),
        )
        setContent {
            OpenChatApp(session)
        }
    }

    private companion object {
        const val ChromeColor = 0xFFF7F7F7.toInt()
    }
}
