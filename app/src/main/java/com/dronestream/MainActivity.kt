package com.dronestream

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker

class MainActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var urlField: EditText
    private lateinit var qualitySpinner: Spinner
    private lateinit var toggleButton: Button
    private lateinit var statusView: TextView

    private var service: StreamService? = null
    private var bound = false

    private val prefs by lazy { getSharedPreferences("stream", Context.MODE_PRIVATE) }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            status("Screen capture denied")
            return@registerForActivityResult
        }
        val svc = service ?: run { status("Service not bound"); return@registerForActivityResult }

        val cfg = qualityPresets[qualitySpinner.selectedItemPosition]
        val error = svc.prepare(result.resultCode, data, cfg)
        if (error != null) { status(error); return@registerForActivityResult }

        val url = urlField.text.toString().trim()
        prefs.edit().putString("url", url).putInt("quality", qualitySpinner.selectedItemPosition).apply()
        svc.startStream(url)
        toggleButton.text = "Stop"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_main)

        urlField = findViewById(R.id.url)
        qualitySpinner = findViewById(R.id.quality)
        toggleButton = findViewById(R.id.toggle)
        statusView = findViewById(R.id.status)

        urlField.setText(prefs.getString("url", "rtsp://192.168.52.128:8554/drone"))
        qualitySpinner.setSelection(prefs.getInt("quality", 1))

        toggleButton.setOnClickListener {
            val svc = service
            if (svc != null && svc.isStreaming) {
                svc.stopStream()
                toggleButton.text = "Start"
                status("Stopped")
            } else {
                startCapture()
            }
        }

        val needed = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }

    private fun startCapture() {
        val intent = Intent(this, StreamService::class.java)
        ContextCompat.startForegroundService(this, intent)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, StreamService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            service?.callback = null
            unbindService(connection)
            bound = false
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as StreamService.LocalBinder).service
            service?.callback = this@MainActivity
            bound = true
            toggleButton.text = if (service?.isStreaming == true) "Stop" else "Start"
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private fun status(text: String) = runOnUiThread { statusView.text = text }

    override fun onConnectionStarted(url: String) = status("Connecting…")
    override fun onConnectionSuccess() = status("Live")
    override fun onConnectionFailed(reason: String) = status("Failed: $reason")
    override fun onNewBitrate(bitrate: Long) = status("Live — ${bitrate / 1000} kbps up")
    override fun onDisconnect() = status("Disconnected")
    override fun onAuthError() = status("Auth error")
    override fun onAuthSuccess() = status("Authenticated")

    companion object {
        val qualityPresets = listOf(
            StreamConfig(854, 480, 30, 1_200_000),
            StreamConfig(1280, 720, 30, 2_500_000),
            StreamConfig(1920, 1080, 30, 5_000_000)
        )
    }
}
