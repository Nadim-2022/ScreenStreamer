package com.dronestream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.InternalSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream

/**
 * Captures the controller screen with MediaProjection and pushes it to go2rtc.
 *
 * Runs as a foreground service so the stream survives the Autel app being in the
 * foreground and the screen being interacted with during flight.
 */
class StreamService : Service(), ConnectChecker {

    companion object {
        const val TAG = "StreamService"
        private const val CHANNEL_ID = "screen_stream"
        private const val NOTIFICATION_ID = 4711

        // Set by MainActivity before binding, read here.
        var INSTANCE: StreamService? = null
            private set
    }

    inner class LocalBinder : Binder() {
        val service: StreamService get() = this@StreamService
    }

    private val binder = LocalBinder()

    /** UI callback so MainActivity can show connection state. */
    var callback: ConnectChecker? = null

    private lateinit var genericStream: GenericStream
    private lateinit var projectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null

    val isStreaming: Boolean get() = ::genericStream.isInitialized && genericStream.isStreaming

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        genericStream = GenericStream(applicationContext, this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // On Android 14+ the foreground service must already be running with the
        // mediaProjection type BEFORE getMediaProjection() is called.
        startForegroundCompat("Ready")
        return START_STICKY
    }

    override fun onDestroy() {
        stopStream()
        if (::genericStream.isInitialized) genericStream.release()
        mediaProjection?.stop()
        mediaProjection = null
        INSTANCE = null
        super.onDestroy()
    }

    /**
     * Turn the MediaProjection consent result into a live video source.
     * Call this once after the user accepts the capture dialog.
     */
    /** Returns null on success, or a human-readable reason on failure. */
    fun prepare(resultCode: Int, data: Intent, cfg: StreamConfig): String? {
        stopStream()
        mediaProjection?.stop()

        val projection = try {
            projectionManager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection", e); return "MediaProjection: ${e.message}"
        } ?: return "MediaProjection was null"
        mediaProjection = projection

        try {
            genericStream.changeVideoSource(ScreenSource(applicationContext, projection))
        } catch (e: Exception) {
            Log.e(TAG, "video source", e); return "Screen source: ${e.message}"
        }

        // Audio is optional. If it fails, keep going with video only.
        var audioOk = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                genericStream.changeAudioSource(InternalSource(projection))
            } else {
                genericStream.changeAudioSource(MicrophoneSource())
            }
            audioOk = genericStream.prepareAudio(cfg.sampleRate, cfg.stereo, cfg.audioBitrate)
        } catch (e: Exception) {
            Log.w(TAG, "audio source failed, continuing video-only", e)
        }
        if (!audioOk) Log.w(TAG, "streaming without audio")

        genericStream.getGlInterface().setCameraOrientation(0)
        genericStream.getStreamClient().setReTries(10)

        return try {
            if (genericStream.prepareVideo(cfg.width, cfg.height, cfg.videoBitrate, cfg.fps)) null
            else "Encoder refused ${cfg.width}x${cfg.height}@${cfg.fps}"
        } catch (e: Exception) {
            Log.e(TAG, "prepareVideo", e); "prepareVideo: ${e.message}"
        }
    }

    fun startStream(endpoint: String) {
        if (!genericStream.isStreaming) {
            genericStream.startStream(endpoint)
            startForegroundCompat("Streaming to $endpoint")
        }
    }

    fun stopStream() {
        if (::genericStream.isInitialized && genericStream.isStreaming) {
            genericStream.stopStream()
        }
        startForegroundCompat("Ready")
    }

    /** Drop the bitrate mid-flight when the uplink degrades. */
    fun setBitrate(bps: Int) {
        if (::genericStream.isInitialized) genericStream.setVideoBitrateOnFly(bps)
    }

    // --- notification -------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen stream", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(text: String) {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Drone screen streamer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // --- ConnectChecker, forwarded to the UI --------------------------------

    override fun onConnectionStarted(url: String) { callback?.onConnectionStarted(url) }
    override fun onConnectionSuccess() { callback?.onConnectionSuccess() }
    override fun onConnectionFailed(reason: String) { callback?.onConnectionFailed(reason) }
    override fun onNewBitrate(bitrate: Long) { callback?.onNewBitrate(bitrate) }
    override fun onDisconnect() { callback?.onDisconnect() }
    override fun onAuthError() { callback?.onAuthError() }
    override fun onAuthSuccess() { callback?.onAuthSuccess() }
}

data class StreamConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val videoBitrate: Int = 2_500_000,
    val sampleRate: Int = 44_100,
    val stereo: Boolean = true,
    val audioBitrate: Int = 96_000
)
