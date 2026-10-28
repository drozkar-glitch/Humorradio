package cz.taximares.humorradio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import kotlin.concurrent.thread

@OptIn(UnstableApi::class)
class RadioService : Service() {

    companion object {
        const val ACTION_PLAY = "cz.taximares.radiopm.ACTION_PLAY"
        const val ACTION_PLAY_FADE_IN = "cz.taximares.radiopm.ACTION_PLAY_FADE_IN"
        const val ACTION_STOP = "cz.taximares.radiopm.ACTION_STOP"
        const val ACTION_TOGGLE = "cz.taximares.radiopm.ACTION_TOGGLE"
        const val ACTION_NEXT = "cz.taximares.radiopm.ACTION_NEXT"
        const val ACTION_PREV = "cz.taximares.radiopm.ACTION_PREV"

        const val EXTRA_STATION_NAME = "extra_station_name"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_BACKUP_URL = "extra_backup_url"

        private const val CHANNEL_ID = "radiopm_playback_channel_v2"
        private const val NOTIFICATION_ID = 1001
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentStationName: String = "Rádio PM"
    private var currentStreamUrl: String = ""
    private var currentBackupUrl: String = ""
    private var currentRdsTitle: String = ""
    private var isCurrentlyPlaying: Boolean = false
    private var triedBackup: Boolean = false
    private var fadeVolume: Float = 1.0f
    private var defaultArtworkBitmap: Bitmap? = null

    private val fadeRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (fadeVolume < 1.0f) {
                fadeVolume = (fadeVolume + 0.02f).coerceAtMost(1.0f)
                p.volume = fadeVolume
                mainHandler.postDelayed(this, 600L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        defaultArtworkBitmap = createGoldenRadioBitmap()
        initMediaSession()
        initExoPlayer()
    }

    private fun initMediaSession() {
        val session = MediaSession(this, "RadioPMMediaSession")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                if (currentStreamUrl.isNotBlank()) {
                    startStream(currentStationName, currentStreamUrl, currentBackupUrl, false)
                } else {
                    RadioController.togglePlayback()
                }
            }

            override fun onPause() {
                pausePlaybackKeepSession()
            }

            override fun onStop() {
                stopPlaybackAndService()
            }

            override fun onSkipToNext() {
                RadioController.playNextStation()
            }

            override fun onSkipToPrevious() {
                RadioController.playPreviousStation()
            }
        })
        session.isActive = true
        mediaSession = session
    }

    private fun initExoPlayer() {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("RadioPM/1.0 (Android; ExoPlayer)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(10000)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                RadioController.updateState(true, "Načítám: $currentStationName...")
                                updateMediaSessionAndNotification()
                            }
                            Player.STATE_READY -> {
                                if (playWhenReady) {
                                    isCurrentlyPlaying = true
                                    RadioController.updateState(true, "Hraje: $currentStationName")
                                    updateMediaSessionAndNotification()
                                }
                            }
                            Player.STATE_ENDED -> {
                                handleStreamError()
                            }
                            Player.STATE_IDLE -> {}
                        }
                    }

                    override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                        isCurrentlyPlaying = isPlayingNow
                        if (isPlayingNow) {
                            RadioController.updateState(true, "Hraje: $currentStationName")
                        }
                        updateMediaSessionAndNotification()
                    }

                    override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                        val rawTitle = mediaMetadata.title?.toString()
                            ?: mediaMetadata.displayTitle?.toString()
                            ?: mediaMetadata.station?.toString()
                            ?: ""
                        val cleaned = fixCzechRdsEncoding(rawTitle).trim()
                        if (cleaned.isNotEmpty() && !cleaned.equals("null", ignoreCase = true)) {
                            currentRdsTitle = cleaned
                            RadioController.updateRds(cleaned)
                            updateMediaSessionAndNotification()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        handleStreamError()
                    }
                })
            }
    }

    private fun handleStreamError() {
        if (!triedBackup && currentBackupUrl.isNotBlank() && currentBackupUrl != currentStreamUrl) {
            triedBackup = true
            RadioController.updateState(true, "Přepínám na záložní stream...")
            resolveAndPlayUrl(currentBackupUrl)
        } else if (currentStreamUrl.startsWith("https://")) {
            val httpFallback = "http://" + currentStreamUrl.removePrefix("https://")
            if (httpFallback != currentBackupUrl) {
                currentBackupUrl = httpFallback
                triedBackup = true
                resolveAndPlayUrl(httpFallback)
                return
            }
            isCurrentlyPlaying = false
            RadioController.updateState(false, "Stream je momentálně nedostupný")
            updateMediaSessionAndNotification()
        } else {
            isCurrentlyPlaying = false
            RadioController.updateState(false, "Stream je momentálně nedostupný")
            updateMediaSessionAndNotification()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val name = intent.getStringExtra(EXTRA_STATION_NAME) ?: currentStationName
                val url = intent.getStringExtra(EXTRA_STREAM_URL) ?: currentStreamUrl
                val backup = intent.getStringExtra(EXTRA_BACKUP_URL) ?: ""
                startStream(name, url, backup, fadeIn = false)
            }
            ACTION_PLAY_FADE_IN -> {
                val name = intent.getStringExtra(EXTRA_STATION_NAME) ?: currentStationName
                val url = intent.getStringExtra(EXTRA_STREAM_URL) ?: currentStreamUrl
                val backup = intent.getStringExtra(EXTRA_BACKUP_URL) ?: ""
                startStream(name, url, backup, fadeIn = true)
            }
            ACTION_TOGGLE -> {
                if (isCurrentlyPlaying) {
                    pausePlaybackKeepSession()
                } else if (currentStreamUrl.isNotBlank()) {
                    startStream(currentStationName, currentStreamUrl, currentBackupUrl, fadeIn = false)
                } else {
                    RadioController.togglePlayback()
                }
            }
            ACTION_NEXT -> {
                RadioController.playNextStation()
            }
            ACTION_PREV -> {
                RadioController.playPreviousStation()
            }
            ACTION_STOP -> {
                stopPlaybackAndService()
            }
        }
        return START_STICKY
    }

    private fun startStream(name: String, url: String, backup: String, fadeIn: Boolean) {
        currentStationName = name
        currentStreamUrl = url
        currentBackupUrl = backup
        currentRdsTitle = "$name – živé vysílání"
        triedBackup = false
        isCurrentlyPlaying = true

        RadioController.updateState(true, "Připojuji: $name...")
        RadioController.updateRds(currentRdsTitle)

        val notification = buildRadioNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        updateMediaSessionState()

        mainHandler.removeCallbacks(fadeRunnable)
        if (fadeIn) {
            fadeVolume = 0.05f
            player?.volume = fadeVolume
            mainHandler.postDelayed(fadeRunnable, 600L)
        } else {
            fadeVolume = 1.0f
            player?.volume = 1.0f
        }

        resolveAndPlayUrl(url)
    }

    private fun resolveAndPlayUrl(rawUrl: String) {
        val trimmed = rawUrl.trim()
        val lower = trimmed.lowercase()
        if (lower.endsWith(".m3u") || lower.endsWith(".pls") || lower.contains(".m3u?") || lower.contains(".pls?")) {
            thread {
                val resolved = fetchPlaylistFirstStreamUrl(trimmed) ?: trimmed
                mainHandler.post {
                    playDirectUrlOnMainThread(resolved)
                }
            }
        } else {
            playDirectUrlOnMainThread(trimmed)
        }
    }

    private fun playDirectUrlOnMainThread(streamUrl: String) {
        val p = player ?: return
        p.stop()
        p.clearMediaItems()
        val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
        p.setMediaItem(mediaItem)
        p.prepare()
        p.playWhenReady = true
    }

    private fun fetchPlaylistFirstStreamUrl(playlistUrl: String): String? {
        return try {
            val conn = (URL(playlistUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "RadioPM/1.0")
            }
            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line!!.trim()
                    if (l.startsWith("http://", ignoreCase = true) || l.startsWith("https://", ignoreCase = true)) {
                        return l
                    }
                    if (l.startsWith("File", ignoreCase = true) && l.contains("=")) {
                        val afterEq = l.substringAfter("=").trim()
                        if (afterEq.startsWith("http", ignoreCase = true)) {
                            return afterEq
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun pausePlaybackKeepSession() {
        mainHandler.removeCallbacks(fadeRunnable)
        isCurrentlyPlaying = false
        player?.playWhenReady = false
        player?.stop()
        RadioController.updateState(false, "Pozastaveno: $currentStationName")
        updateMediaSessionAndNotification()
    }

    private fun stopPlaybackAndService() {
        mainHandler.removeCallbacks(fadeRunnable)
        isCurrentlyPlaying = false
        player?.playWhenReady = false
        player?.stop()
        mediaSession?.isActive = false
        RadioController.updateState(false, "Připraveno k poslechu")
        RadioController.updateRds("Vyber stanici a stiskni Play")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun resolveCurrentStationBitmap(): Bitmap? {
        val faviconUrl = RadioController.currentStation.value.faviconUrl
        if (faviconUrl.isNotBlank()) {
            val bytes = RadioController.stationIcons.value[faviconUrl]
            if (bytes != null && bytes.isNotEmpty()) {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) return bmp
            }
        }
        return defaultArtworkBitmap
    }

    private fun updateMediaSessionState() {
        val session = mediaSession ?: return
        session.isActive = true

        val stateBuilder = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(
                if (isCurrentlyPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                1.0f
            )
        session.setPlaybackState(stateBuilder.build())

        val artwork = resolveCurrentStationBitmap()
        val displayRds = currentRdsTitle.ifBlank { "$currentStationName – živé vysílání" }
        val metaBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, displayRds)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, currentStationName)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Rádio PM")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, -1L)

        if (artwork != null) {
            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork)
        }
        session.setMetadata(metaBuilder.build())
    }

    private fun updateMediaSessionAndNotification() {
        updateMediaSessionState()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildRadioNotification())
    }

    private fun buildRadioNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = Intent(this, RadioService::class.java).apply { action = ACTION_PREV }
        val prevPending = PendingIntent.getService(
            this,
            1,
            prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, RadioService::class.java).apply { action = ACTION_TOGGLE }
        val togglePending = PendingIntent.getService(
            this,
            2,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = Intent(this, RadioService::class.java).apply { action = ACTION_NEXT }
        val nextPending = PendingIntent.getService(
            this,
            3,
            nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevAction = Notification.Action.Builder(
            android.R.drawable.ic_media_previous,
            "Předchozí",
            prevPending
        ).build()

        val playPauseAction = Notification.Action.Builder(
            if (isCurrentlyPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isCurrentlyPlaying) "Pozastavit" else "Přehrát",
            togglePending
        ).build()

        val nextAction = Notification.Action.Builder(
            android.R.drawable.ic_media_next,
            "Další",
            nextPending
        ).build()

        val mediaStyle = Notification.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
        mediaSession?.sessionToken?.let { token ->
            mediaStyle.setMediaSession(token)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val subtitle = currentRdsTitle.ifBlank { "Živé vysílání" }
        val artwork = resolveCurrentStationBitmap()

        builder
            .setContentTitle(currentStationName)
            .setContentText(subtitle)
            .setSubText("Rádio PM")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(contentPendingIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(isCurrentlyPlaying)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(mediaStyle)

        if (artwork != null) {
            builder.setLargeIcon(artwork)
        }

        return builder.build()
    }

    private fun createGoldenRadioBitmap(): Bitmap {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.rgb(24, 24, 24)
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 48f, 48f, paint)

        paint.color = Color.rgb(255, 215, 0)
        canvas.drawRoundRect(RectF(36f, 76f, 220f, 212f), 22f, 22f, paint)
        paint.strokeWidth = 10f
        canvas.drawLine(68f, 76f, 176f, 34f, paint)
        canvas.drawCircle(176f, 34f, 10f, paint)

        paint.color = Color.rgb(24, 24, 24)
        canvas.drawRoundRect(RectF(52f, 92f, 204f, 122f), 8f, 8f, paint)
        canvas.drawCircle(92f, 166f, 34f, paint)
        canvas.drawCircle(168f, 166f, 24f, paint)

        paint.color = Color.rgb(255, 215, 0)
        canvas.drawCircle(92f, 166f, 18f, paint)
        canvas.drawCircle(168f, 166f, 10f, paint)

        return bmp
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Přehrávání Rádia PM (Now Bar)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ovládání přehrávání v Now Baru, na hodinkách a v autě"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun fixCzechRdsEncoding(input: String): String {
        if (input.isBlank()) return ""
        var text = decodeHtmlEntities(input)
        val win1250SuspectChars = "ÈØŠŽÝÁÍÉÚÙÌÏÒÓŤĎĹĽèøšžýáíéúùìïòóťďĺľ"
        val hasMojibake = text.any { it in win1250SuspectChars } &&
            (text.contains('È') || text.contains('Ø') || text.contains('ø') || text.contains('è') || text.contains('ì') || text.contains('ù'))
        if (hasMojibake) {
            try {
                val bytes = text.toByteArray(Charset.forName("ISO-8859-1"))
                text = String(bytes, Charset.forName("windows-1250"))
            } catch (_: Exception) {
            }
        }
        return text
    }

    private fun decodeHtmlEntities(text: String): String {
        var result = text
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&ndash;", "–")
            .replace("&mdash;", "—")
            .replace("&nbsp;", " ")
        val numRegex = Regex("&#(\\d+);")
        result = numRegex.replace(result) { matchResult ->
            val code = matchResult.groupValues[1].toIntOrNull()
            if (code != null && code in 32..65535) code.toChar().toString() else matchResult.value
        }
        return result
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(fadeRunnable)
        player?.release()
        player = null
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
