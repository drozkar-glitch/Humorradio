package cz.taximares.radiopm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadata as AndroidMediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class RadioService : Service() {

    companion object {
        const val ACTION_PLAY = "cz.taximares.radiopm.ACTION_PLAY"
        const val ACTION_PLAY_FADE_IN = "cz.taximares.radiopm.ACTION_PLAY_FADE_IN"
        const val ACTION_STOP = "cz.taximares.radiopm.ACTION_STOP"
        const val ACTION_PREV = "cz.taximares.radiopm.ACTION_PREV"
        const val ACTION_NEXT = "cz.taximares.radiopm.ACTION_NEXT"

        const val EXTRA_STATION_NAME = "extra_station_name"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_BACKUP_URL = "extra_backup_url"

        private const val CHANNEL_ID = "radiopm_playback_channel_v3"
        private const val NOTIFICATION_ID = 1001

        private val WINDOWS_1250_TABLE = charArrayOf(
            '\u20AC', ' ', '\u201A', ' ', '\u201E', '\u2026', '\u2020', '\u2021',
            ' ', '\u2030', '\u0160', '\u2039', '\u015A', '\u0164', '\u017D', '\u0179',
            '\u00C9', '\u2018', '\u2019', '\u201C', '\u201D', '\u2022', '\u2013', '\u2014',
            ' ', '\u2122', '\u0161', '\u203A', '\u015B', '\u0165', '\u017E', '\u017A',
            '\u00A0', '\u02C7', '\u02D8', '\u0141', '\u00A4', '\u0104', '\u00A6', '\u00A7',
            '\u00A8', '\u00A9', '\u015E', '\u00AB', '\u00AC', '\u00AD', '\u00AE', '\u017B',
            '\u00B0', '\u00B1', '\u02DB', '\u0142', '\u00B4', '\u00B5', '\u00B6', '\u00B7',
            '\u00B8', '\u0105', '\u015F', '\u00BB', '\u013D', '\u02DD', '\u013E', '\u017C',
            '\u0154', '\u00C1', '\u00C2', '\u0102', '\u00C4', '\u0139', '\u0106', '\u00C7',
            '\u010C', '\u00C9', '\u0118', '\u00CB', '\u011A', '\u00CD', '\u00CE', '\u010E',
            '\u0110', '\u0143', '\u0147', '\u00D3', '\u00D4', '\u0150', '\u00D6', '\u00D7',
            '\u0158', '\u016E', '\u00DA', '\u0170', '\u00DC', '\u00DD', '\u0162', '\u00DF',
            '\u0155', '\u00E1', '\u00E2', '\u0103', '\u00E4', '\u013A', '\u0107', '\u00E7',
            '\u010D', '\u00E9', '\u0119', '\u00EB', '\u011B', '\u00ED', '\u00EE', '\u010F',
            '\u0111', '\u0144', '\u0148', '\u00F3', '\u00F4', '\u0151', '\u00F6', '\u00F7',
            '\u0159', '\u016F', '\u00FA', '\u0171', '\u00FC', '\u00FD', '\u0163', '\u02D9'
        )
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private var currentStationName: String = "Rádio B"
    private var currentPrimaryUrl: String = ""
    private var currentBackupUrl: String = ""
    private var activeUrl: String = ""
    private var currentRds: String = "Živé vysílání"
    private var lastRawDecodedRds: String = ""
    private var triedBackup: Boolean = false
    private var userRequestedStop: Boolean = false
    private var pausedByAudioFocus: Boolean = false

    private var fadeRunnable: Runnable? = null
    private var retryRunnable: Runnable? = null
    private var cachedArtwork: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        RadioController.init(applicationContext)
        createNotificationChannel()
        initMediaSession()
        initExoPlayer()
    }

    private fun initMediaSession() {
        val session = MediaSession(this, "RadioPMMediaSession")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                if (currentPrimaryUrl.isNotEmpty()) {
                    playUrl(currentPrimaryUrl, fadeIn = false)
                } else {
                    RadioController.togglePlayback()
                }
            }

            override fun onPause() {
                stopPlaybackInternal()
            }

            override fun onStop() {
                stopPlaybackInternal()
            }

            override fun onSkipToNext() {
                RadioController.playNextStation()
            }

            override fun onSkipToPrevious() {
                RadioController.playPreviousStation()
            }

            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            RadioController.playNextStation()
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            RadioController.playPreviousStation()
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> {
                            onPlay()
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> {
                            onPause()
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                            RadioController.togglePlayback()
                            return true
                        }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
        })
        session.isActive = true
        mediaSession = session
    }

    private fun decodeCzechRdsBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            if (b0 == 0) {
                break
            } else if (b0 < 0x80) {
                sb.append(b0.toChar())
                i++
            } else if (b0 in 0xC2..0xDF && i + 1 < bytes.size) {
                val b1 = bytes[i + 1].toInt() and 0xFF
                if ((b1 and 0xC0) == 0x80) {
                    val cp = ((b0 and 0x1F) shl 6) or (b1 and 0x3F)
                    sb.append(cp.toChar())
                    i += 2
                } else {
                    sb.append(WINDOWS_1250_TABLE[b0 - 0x80])
                    i++
                }
            } else if (b0 in 0xE0..0xEF && i + 2 < bytes.size) {
                val b1 = bytes[i + 1].toInt() and 0xFF
                val b2 = bytes[i + 2].toInt() and 0xFF
                if ((b1 and 0xC0) == 0x80 && (b2 and 0xC0) == 0x80) {
                    val cp = ((b0 and 0x0F) shl 12) or ((b1 and 0x3F) shl 6) or (b2 and 0x3F)
                    sb.append(cp.toChar())
                    i += 3
                } else {
                    sb.append(WINDOWS_1250_TABLE[b0 - 0x80])
                    i++
                }
            } else {
                sb.append(WINDOWS_1250_TABLE[b0 - 0x80])
                i++
            }
        }
        return cleanAndRepairCzechText(sb.toString())
    }

    private fun extractStreamTitleFromRawIcy(rawIcy: ByteArray): String {
        val marker = "StreamTitle='".encodeToByteArray()
        var startIdx = -1
        for (i in 0..(rawIcy.size - marker.size)) {
            var match = true
            for (j in marker.indices) {
                if (rawIcy[i + j] != marker[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                startIdx = i + marker.size
                break
            }
        }
        if (startIdx < 0) return ""

        var endIdx = rawIcy.size
        for (i in startIdx until rawIcy.size) {
            if (rawIcy[i].toInt() == 0) {
                endIdx = i
                break
            }
            if (rawIcy[i] == '\''.code.toByte() && i + 1 < rawIcy.size && rawIcy[i + 1] == ';'.code.toByte()) {
                endIdx = i
                break
            }
        }
        if (endIdx <= startIdx) return ""
        val titleBytes = rawIcy.copyOfRange(startIdx, endIdx)
        return decodeCzechRdsBytes(titleBytes)
    }

    private fun fixCzechRdsEncoding(input: String): String {
        if (input.isEmpty()) return ""
        val sb = StringBuilder(input.length)
        val byteBuffer = ArrayList<Byte>()

        fun flushBytes() {
            if (byteBuffer.isNotEmpty()) {
                val arr = ByteArray(byteBuffer.size) { idx -> byteBuffer[idx] }
                sb.append(decodeCzechRdsBytes(arr))
                byteBuffer.clear()
            }
        }

        for (ch in input) {
            val code = ch.code
            if (code in 0x80..0xFF) {
                byteBuffer.add(code.toByte())
            } else {
                flushBytes()
                sb.append(ch)
            }
        }
        flushBytes()

        return cleanAndRepairCzechText(sb.toString())
    }

    private fun cleanAndRepairCzechText(input: String): String {
        if (input.isEmpty()) return ""
        var s = input
            .replace("Ăˇ", "á").replace("Ă\u0081", "Á")
            .replace("ÄŤ", "č").replace("ÄŚ", "Č")
            .replace("ÄŹ", "ď").replace("ÄŽ", "Ď")
            .replace("Ă©", "é").replace("Ă‰", "É")
            .replace("Ä›", "ě").replace("Äš", "Ě")
            .replace("Ă­", "í").replace("ĂŤ", "Í")
            .replace("Ĺˆ", "ň").replace("Ĺ‡", "Ň")
            .replace("Ăł", "ó").replace("Ă“", "Ó")
            .replace("Ĺ™", "ř").replace("Ĺ\u0098", "Ř")
            .replace("Ĺˇ", "š").replace("Ĺ ", "Š")
            .replace("ĹĄ", "ť").replace("Ĺ¤", "Ť")
            .replace("Ăş", "ú").replace("Ăš", "Ú")
            .replace("ĹŻ", "ů").replace("Ĺ®", "Ů")
            .replace("Ă˝", "ý").replace("Ăť", "Ý")
            .replace("Ĺľ", "ž").replace("Ĺ˝", "Ž")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&ndash;", "–")
            .replace("&mdash;", "—")

        if (s.contains('\uFFFD')) {
            s = repairServerReplacementChars(s)
        }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun repairServerReplacementChars(text: String): String {
        var s = text
            .replace("R\uFFFDdio", "Rádio")
            .replace("r\uFFFDdio", "rádio")
            .replace("Kdy\uFFFD", "Když")
            .replace("kdy\uFFFD", "když")
            .replace("v\uFFFDdy", "vždy")
            .replace("V\uFFFDdy", "Vždy")
            .replace("l\uFFFDska", "láska")
            .replace("L\uFFFDska", "Láska")
            .replace("p\uFFFDsnič", "písnič")
            .replace("P\uFFFDsnič", "Písnič")
            .replace("je\uFFFDtě", "ještě")
            .replace("Je\uFFFDtě", "Ještě")

        // Oprava \uFFFD na konci ceskych slov a jmen (napr. Ztracený, Ledecký, Český, Dobrý, Nový, Krásný)
        val hardConsonants = setOf('b', 'd', 'f', 'g', 'h', 'k', 'l', 'm', 'n', 'p', 'r', 's', 't', 'v', 'z')
        val softConsonants = setOf('c', 'č', 'ď', 'j', 'ň', 'ř', 'š', 'ť', 'ž')

        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\uFFFD') {
                val prev = if (i > 0) s[i - 1] else ' '
                val next = if (i + 1 < s.length) s[i + 1] else ' '
                val isEndOfWord = !next.isLetterOrDigit()
                val prevLower = prev.lowercaseChar()
                if (isEndOfWord && prevLower in hardConsonants) {
                    sb.append(if (prev.isUpperCase()) 'Ý' else 'ý')
                } else if (isEndOfWord && prevLower in softConsonants) {
                    sb.append(if (prev.isUpperCase()) 'Í' else 'í')
                } else if (prev.isLetter() && next.isLetter()) {
                    sb.append(if (prev.isUpperCase() && next.isUpperCase()) 'Ý' else 'ý')
                }
            } else {
                sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    private fun initExoPlayer() {
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttrs, true)
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    pausedByAudioFocus = true
                    RadioController.updateState(false, "Pozastaveno (hovor)")
                    updateMediaSessionAndNotification(false)
                } else if (playWhenReady && pausedByAudioFocus && !userRequestedStop) {
                    pausedByAudioFocus = false
                    val urlToResume = activeUrl.ifBlank { currentPrimaryUrl }
                    if (urlToResume.isNotEmpty()) {
                        handler.post {
                            if (!userRequestedStop) {
                                playUrl(urlToResume, fadeIn = false)
                            }
                        }
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    pausedByAudioFocus = false
                    RadioController.updateState(true, "Hraje: $currentStationName")
                    updateMediaSessionAndNotification(true)
                } else if (!userRequestedStop && !pausedByAudioFocus && exo.playbackState == Player.STATE_BUFFERING) {
                    RadioController.updateState(true, "Načítání: $currentStationName...")
                    updateMediaSessionAndNotification(true)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING && !userRequestedStop && !pausedByAudioFocus) {
                    RadioController.updateState(true, "Načítání: $currentStationName...")
                } else if (playbackState == Player.STATE_ENDED && !userRequestedStop && !pausedByAudioFocus) {
                    val urlToResume = activeUrl.ifBlank { currentPrimaryUrl }
                    if (urlToResume.isNotEmpty()) {
                        playUrl(urlToResume, fadeIn = false)
                    }
                }
            }

            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val entry = metadata.get(i)
                    val rawBytes = try {
                        val field = entry.javaClass.getDeclaredField("rawMetadata")
                        field.isAccessible = true
                        field.get(entry) as? ByteArray
                    } catch (_: Exception) {
                        null
                    }
                    if (rawBytes != null && rawBytes.isNotEmpty()) {
                        val decodedFromRaw = extractStreamTitleFromRawIcy(rawBytes)
                        if (decodedFromRaw.isNotEmpty()) {
                            lastRawDecodedRds = decodedFromRaw
                            currentRds = decodedFromRaw
                            RadioController.updateRds(decodedFromRaw)
                            if (player?.isPlaying == true) {
                                updateMediaSessionAndNotification(true)
                            }
                        }
                    }
                }
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val rawTitle = mediaMetadata.title?.toString()?.trim().orEmpty()
                val rawArtist = mediaMetadata.artist?.toString()?.trim().orEmpty()
                val title = fixCzechRdsEncoding(rawTitle)
                val artist = fixCzechRdsEncoding(rawArtist)
                val formatted = when {
                    artist.isNotEmpty() && title.isNotEmpty() -> "$artist – $title"
                    title.isNotEmpty() -> title
                    else -> ""
                }

                val rds = when {
                    lastRawDecodedRds.isNotEmpty() && (formatted.isEmpty() || rawTitle.contains('\uFFFD')) -> lastRawDecodedRds
                    formatted.isNotEmpty() -> formatted
                    lastRawDecodedRds.isNotEmpty() -> lastRawDecodedRds
                    else -> "$currentStationName – živé vysílání"
                }

                currentRds = rds
                RadioController.updateRds(rds)
                if (player?.isPlaying == true) {
                    updateMediaSessionAndNotification(true)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (userRequestedStop || pausedByAudioFocus) return
                if (!triedBackup && currentBackupUrl.isNotBlank() && currentBackupUrl != activeUrl) {
                    triedBackup = true
                    RadioController.updateState(true, "Přepínám na záložní stream...")
                    playUrl(currentBackupUrl, fadeIn = false)
                } else {
                    RadioController.updateState(false, "Obnovuji spojení se stanicí...")
                    retryRunnable?.let { handler.removeCallbacks(it) }
                    val r = Runnable {
                        if (!userRequestedStop && !pausedByAudioFocus && currentPrimaryUrl.isNotEmpty()) {
                            triedBackup = false
                            playUrl(currentPrimaryUrl, fadeIn = false)
                        }
                    }
                    retryRunnable = r
                    handler.postDelayed(r, 3500L)
                }
            }
        })

        player = exo
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY, ACTION_PLAY_FADE_IN -> {
                val fadeIn = intent.action == ACTION_PLAY_FADE_IN
                currentStationName = intent.getStringExtra(EXTRA_STATION_NAME) ?: currentStationName
                currentPrimaryUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: currentPrimaryUrl
                currentBackupUrl = intent.getStringExtra(EXTRA_BACKUP_URL) ?: ""
                triedBackup = false
                userRequestedStop = false
                pausedByAudioFocus = false
                lastRawDecodedRds = ""
                currentRds = "$currentStationName – živé vysílání"
                RadioController.updateRds(currentRds)

                acquireWakeLock()
                startForegroundWithMediaNotification(true)

                if (currentPrimaryUrl.isNotEmpty()) {
                    playUrl(currentPrimaryUrl, fadeIn = fadeIn)
                }
            }
            ACTION_PREV -> {
                RadioController.playPreviousStation()
            }
            ACTION_NEXT -> {
                RadioController.playNextStation()
            }
            ACTION_STOP -> {
                stopPlaybackInternal()
            }
        }
        return START_STICKY
    }

    private fun playUrl(url: String, fadeIn: Boolean) {
        val exo = player ?: return
        activeUrl = url
        lastRawDecodedRds = ""
        retryRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable?.let { handler.removeCallbacks(it) }

        RadioController.updateState(true, "Připojuji: $currentStationName...")
        updateMediaSessionAndNotification(true)

        exo.stop()
        exo.clearMediaItems()
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()

        if (fadeIn) {
            exo.volume = 0.05f
            exo.playWhenReady = true
            startSmoothFadeIn()
        } else {
            exo.volume = 1.0f
            exo.playWhenReady = true
        }
    }

    private fun startSmoothFadeIn() {
        val steps = 30
        val intervalMs = 2000L
        var currentStep = 1
        val r = object : Runnable {
            override fun run() {
                val exo = player ?: return
                if (userRequestedStop) return
                val vol = (currentStep.toFloat() / steps.toFloat()).coerceIn(0.05f, 1.0f)
                exo.volume = vol
                if (currentStep < steps) {
                    currentStep++
                    handler.postDelayed(this, intervalMs)
                }
            }
        }
        fadeRunnable = r
        handler.postDelayed(r, intervalMs)
    }

    private fun stopPlaybackInternal() {
        userRequestedStop = true
        pausedByAudioFocus = false
        lastRawDecodedRds = ""
        retryRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable?.let { handler.removeCallbacks(it) }
        player?.stop()
        player?.clearMediaItems()
        RadioController.updateState(false, "Zastaveno")
        RadioController.updateRds("")
        updateMediaSessionAndNotification(false)
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun updateMediaSessionAndNotification(playing: Boolean) {
        val session = mediaSession ?: return
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
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                1.0f
            )
        session.setPlaybackState(stateBuilder.build())

        val art = getOrCreateArtworkBitmap()
        val metaBuilder = AndroidMediaMetadata.Builder()
            .putString(AndroidMediaMetadata.METADATA_KEY_TITLE, currentStationName)
            .putString(AndroidMediaMetadata.METADATA_KEY_ARTIST, currentRds)
            .putString(AndroidMediaMetadata.METADATA_KEY_ALBUM, "Rádio PM")
            .putBitmap(AndroidMediaMetadata.METADATA_KEY_ALBUM_ART, art)
            .putBitmap(AndroidMediaMetadata.METADATA_KEY_DISPLAY_ICON, art)
        session.setMetadata(metaBuilder.build())

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildMediaNotification(playing))
    }

    private fun startForegroundWithMediaNotification(playing: Boolean) {
        val notif = buildMediaNotification(playing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun buildMediaNotification(playing: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevPi = PendingIntent.getService(
            this,
            101,
            Intent(this, RadioService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playStopPi = PendingIntent.getService(
            this,
            102,
            Intent(this, RadioService::class.java).apply {
                action = if (playing) ACTION_STOP else ACTION_PLAY
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextPi = PendingIntent.getService(
            this,
            103,
            Intent(this, RadioService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevAction = Notification.Action.Builder(
            android.R.drawable.ic_media_previous,
            "Předchozí",
            prevPi
        ).build()

        val playPauseAction = Notification.Action.Builder(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (playing) "Zastavit" else "Přehrát",
            playStopPi
        ).build()

        val nextAction = Notification.Action.Builder(
            android.R.drawable.ic_media_next,
            "Další",
            nextPi
        ).build()

        val mediaStyle = Notification.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setLargeIcon(getOrCreateArtworkBitmap())
            .setContentTitle(currentStationName)
            .setContentText(currentRds)
            .setSubText("Rádio PM")
            .setContentIntent(contentPi)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(mediaStyle)

        return builder.build()
    }

    private fun getOrCreateArtworkBitmap(): Bitmap {
        cachedArtwork?.let { return it }
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(12, 28, 44)
        }
        canvas.drawColor(bgPaint.color)

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 213, 79)
        }
        canvas.drawRoundRect(RectF(36f, 76f, 220f, 210f), 24f, 24f, bodyPaint)

        val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(14, 24, 34)
        }
        canvas.drawRoundRect(RectF(52f, 92f, 204f, 124f), 8f, 8f, darkPaint)
        canvas.drawCircle(94f, 166f, 30f, darkPaint)
        canvas.drawCircle(170f, 166f, 20f, darkPaint)

        cachedArtwork = bmp
        return bmp
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Přehrávání Rádia PM",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ovládání živého vysílání v Now Baru, na hodinkách a v autě"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioPM::StreamingWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable?.let { handler.removeCallbacks(it) }
        player?.release()
        player = null
        mediaSession?.release()
        mediaSession = null
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
