package cz.taximares.humorradio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RadioService : Service() {

    companion object {
        const val ACTION_PLAY = "cz.taximares.humorradio.ACTION_PLAY"
        const val ACTION_STOP = "cz.taximares.humorradio.ACTION_STOP"
        private const val STREAM_URL = "http://mp3stream4.abradio.cz/humor128.mp3"
        private const val NOTIFICATION_ID = 1
        private const val RECONNECT_DELAY_MS = 3000L

        private val _isPlaying = MutableStateFlow(false)
        val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    }

    private var player: ExoPlayer? = null
    private var shouldKeepPlaying = false
    private val reconnectHandler = Handler(Looper.getMainLooper())

    private val reconnectRunnable = Runnable {
        if (shouldKeepPlaying) {
            player?.apply {
                stop()
                setMediaItem(MediaItem.fromUri(STREAM_URL))
                prepare()
                playWhenReady = true
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        if (playing) {
                            _isPlaying.value = true
                            reconnectHandler.removeCallbacks(reconnectRunnable)
                            updateNotification("Hraje: Rádio Humor")
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED && shouldKeepPlaying) {
                            scheduleReconnect()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (shouldKeepPlaying) {
                            scheduleReconnect()
                        } else {
                            _isPlaying.value = false
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        }
                    }
                })
            }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> startRadio()
            ACTION_STOP -> stopRadio()
        }
        return START_STICKY
    }

    private fun startRadio() {
        shouldKeepPlaying = true
        reconnectHandler.removeCallbacks(reconnectRunnable)

        val notification = createNotification("Hraje: Rádio Humor")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        player?.apply {
            stop()
            setMediaItem(MediaItem.fromUri(STREAM_URL))
            prepare()
            playWhenReady = true
        }
        _isPlaying.value = true
    }

    private fun scheduleReconnect() {
        _isPlaying.value = true
        updateNotification("Výpadek signálu – zkouším znovu připojit...")
        reconnectHandler.removeCallbacks(reconnectRunnable)
        reconnectHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun stopRadio() {
        shouldKeepPlaying = false
        reconnectHandler.removeCallbacks(reconnectRunnable)

        player?.apply {
            playWhenReady = false
            stop()
        }
        _isPlaying.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }

    private fun createNotification(contentText: String): Notification {
        val channelId = "humor_radio_channel"
        val notificationManager = getSystemService(NotificationManager::class.java)

        val channel = NotificationChannel(
            channelId,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RadioService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Rádio Humor")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Vypnout rádio",
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        shouldKeepPlaying = false
        reconnectHandler.removeCallbacks(reconnectRunnable)
        _isPlaying.value = false
        player?.release()
        player = null
        super.onDestroy()
    }
}
