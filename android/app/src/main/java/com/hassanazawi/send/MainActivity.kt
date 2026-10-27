package com.hassanazawi.send

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.hassanazawi.send.net.Engine
import com.hassanazawi.send.service.SendService
import com.hassanazawi.send.ui.SendRoot
import java.io.File

class MainActivity : ComponentActivity() {
    private val pickDocs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { Engine.sendUris(it) }
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { Engine.sendUris(it) }
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // The service keeps the server reachable; opening the app is what makes the PC find and join this phone.
        SendService.start(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) handle(intent)

        setContent {
            SendRoot(
                onPickFiles = { pickDocs.launch(arrayOf("*/*")) },
                onPickMedia = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        Engine.foreground = true
    }

    override fun onStop() {
        Engine.foreground = false
        super.onStop()
    }

    @Suppress("DEPRECATION")
    private fun handle(i: Intent?) {
        i ?: return
        when (i.action) {
            Intent.ACTION_SEND -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)
                val text = i.getStringExtra(Intent.EXTRA_TEXT)
                if (uri != null) Engine.sendUris(listOf(uri)) else if (!text.isNullOrBlank()) Engine.sendText(text)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list: List<Uri> = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
                Engine.sendUris(list)
            }
            ACTION_DEBUG_SEND -> if (BuildConfig.DEBUG) {
                // adb test hook (debug builds only): files inside the app's own storage
                val paths = i.getStringExtra("paths").orEmpty().split(',').filter { it.isNotBlank() }
                Engine.sendUris(paths.map { Uri.fromFile(File(it)) })
                i.removeExtra("paths")
            }
        }
        i.action = null
    }

    companion object {
        const val ACTION_DEBUG_SEND = "com.hassanazawi.send.DEBUG_SEND"
    }
}
