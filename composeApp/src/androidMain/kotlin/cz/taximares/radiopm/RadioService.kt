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
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes as FrameworkAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata as AndroidMediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

@OptIn(UnstableApi::class)
class RadioService : Service() {

    companion object {
        const val ACTION_PLAY = "cz.taximares.radiopm.ACTION_PLAY"
        const val ACTION_PLAY_FADE_IN = "cz.taximares.radiopm.ACTION_PLAY_FADE_IN"
        const val ACTION_STOP = "cz.taximares.radiopm.ACTION_STOP"
        const val ACTION_PREV = "cz.taximares.radiopm.ACTION_PREV"
        const val ACTION_NEXT = "cz.taximares.radiopm.ACTION_NEXT"
        const val ACTION_PLAY_AUDIOBOOK = "cz.taximares.radiopm.ACTION_PLAY_AUDIOBOOK"
        const val ACTION_PAUSE_AUDIOBOOK = "cz.taximares.radiopm.ACTION_PAUSE_AUDIOBOOK"
        const val ACTION_SEEK_AUDIOBOOK = "cz.taximares.radiopm.ACTION_SEEK_AUDIOBOOK"

        const val EXTRA_STATION_NAME = "extra_station_name"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_BACKUP_URL = "extra_backup_url"

        const val EXTRA_AUDIOBOOK_ID = "extra_audiobook_id"
        const val EXTRA_AUDIOBOOK_TITLE = "extra_audiobook_title"
        const val EXTRA_AUDIOBOOK_GROUP = "extra_audiobook_group"
        const val EXTRA_AUDIOBOOK_URI = "extra_audiobook_uri"
        const val EXTRA_AUDIOBOOK_ZIP_ENTRY = "extra_audiobook_zip_entry"
        const val EXTRA_AUDIOBOOK_EXT = "extra_audiobook_ext"
        const val EXTRA_AUDIOBOOK_START_POS = "extra_audiobook_start_pos"
        const val EXTRA_AUDIOBOOK_COVER_KEY = "extra_audiobook_cover_key"
        const val EXTRA_SEEK_POSITION_MS = "extra_seek_position_ms"

        private const val CHANNEL_ID = "radiopm_playback_channel_v3"
        private const val NOTIFICATION_ID = 1001

        private val DECIMAL_ENTITY_REGEX = Regex("&#(\\d+);")
        private val HEX_ENTITY_REGEX = Regex("&#[xX]([0-9a-fA-F]+);")

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
    private var wifiLock: WifiManager.WifiLock? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var modeChangedListener: Any? = null

    private val handler = Handler(Looper.getMainLooper())
    private var currentStationName: String = "Rádio B"
    private var currentPrimaryUrl: String = ""
    private var currentBackupUrl: String = ""
    private var activeUrl: String = ""
    private var currentRds: String = "Živé vysílání"
    private var lastRawDecodedRds: String = ""
    private var triedBackup: Boolean = false
    private var userRequestedStop: Boolean = false
    private var pausedByCall: Boolean = false
    private var pausedByFocusLoss: Boolean = false

    private var isAudiobookMode: Boolean = false
    private var currentAudiobookItem: AudiobookItem? = null
    private var activeAudiobookPlayableUri: Uri? = null
    private var audiobookRequestToken: Int = 0

    private var fadeRunnable: Runnable? = null
    private var retryRunnable: Runnable? = null
    private var callMonitorRunnable: Runnable? = null
    private var activeCallWatchRunnable: Runnable? = null
    private var duckRestoreRunnable: Runnable? = null
    private var audiobookProgressRunnable: Runnable? = null
    private var cachedArtwork: Bitmap? = null
    private var cachedBookArtwork: Bitmap? = null

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                duckRestoreRunnable?.let { handler.removeCallbacks(it) }
                if (isPhoneCallActive()) pauseForPhoneCall() else pauseForExternalMedia()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (isPhoneCallActive()) pauseForPhoneCall() else duckBriefly()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                duckRestoreRunnable?.let { handler.removeCallbacks(it) }
                if (!userRequestedStop && !isPhoneCallActive()) {
                    if (pausedByCall || pausedByFocusLoss) {
                        resumeAfterInterruption()
                    } else {
                        player?.volume = 1.0f
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        RadioController.init(applicationContext)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        registerAudioModeListener()
        createNotificationChannel()
        initMediaSession()
        initExoPlayer()
    }

    private fun registerAudioModeListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = audioManager ?: return
            try {
                val listener = AudioManager.OnModeChangedListener { mode ->
                    handler.post {
                        val inCallOrRing = mode == AudioManager.MODE_IN_CALL ||
                            mode == AudioManager.MODE_IN_COMMUNICATION ||
                            mode == AudioManager.MODE_RINGTONE ||
                            mode == AudioManager.MODE_CALL_SCREENING
                        if (inCallOrRing) {
                            if (!userRequestedStop && !pausedByCall) pauseForPhoneCall()
                        } else if (mode == AudioManager.MODE_NORMAL) {
                            if (pausedByCall && !userRequestedStop) resumeAfterInterruption()
                        }
                    }
                }
                am.addOnModeChangedListener(mainExecutor, listener)
                modeChangedListener = listener
            } catch (_: Exception) {
            }
        }
    }

    private fun unregisterAudioModeListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = audioManager ?: return
            val listener = modeChangedListener as? AudioManager.OnModeChangedListener ?: return
            try {
                am.removeOnModeChangedListener(listener)
            } catch (_: Exception) {
            }
            modeChangedListener = null
        }
    }

    private fun isPhoneCallActive(): Boolean {
        val am = audioManager ?: return false
        val mode = am.mode
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING)
    }

    private fun startActivePlaybackCallWatcher() {
        activeCallWatchRunnable?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (userRequestedStop || pausedByCall) return
                if (isPhoneCallActive()) {
                    pauseForPhoneCall()
                    return
                }
                if (!pausedByFocusLoss) handler.postDelayed(this, 800L)
            }
        }
        activeCallWatchRunnable = r
        handler.postDelayed(r, 800L)
    }

    private fun stopActivePlaybackCallWatcher() {
        activeCallWatchRunnable?.let { handler.removeCallbacks(it) }
        activeCallWatchRunnable = null
    }

    private fun startAudiobookProgressTicker() {
        audiobookProgressRunnable?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (!isAudiobookMode || userRequestedStop || pausedByCall || pausedByFocusLoss) return
                flushAudiobookProgress(forcePersist = false, rewindMs = 0L)
                if (player?.isPlaying == true) {
                    updateMediaSessionAndNotification(true)
                }
                handler.postDelayed(this, 1000L)
            }
        }
        audiobookProgressRunnable = r
        handler.postDelayed(r, 1000L)
    }

    private fun stopAudiobookProgressTicker() {
        audiobookProgressRunnable?.let { handler.removeCallbacks(it) }
        audiobookProgressRunnable = null
    }

    private fun flushAudiobookProgress(forcePersist: Boolean, rewindMs: Long = 0L) {
        if (!isAudiobookMode) return
        val exo = player ?: return
        val book = currentAudiobookItem ?: return
        val rawPos = exo.currentPosition
        if (rawPos < 0L) return
        val newPos = (rawPos - rewindMs).coerceAtLeast(0L)
        val dur = if (exo.duration > 0L && exo.duration != C.TIME_UNSET) exo.duration else book.durationMs
        val updated = book.copy(positionMs = newPos, durationMs = dur)
        currentAudiobookItem = updated
        RadioController.onAudiobookProgressFromService(updated.id, newPos, dur, forcePersist)
    }

    private fun pauseForPhoneCall() {
        if (userRequestedStop) return
        pausedByCall = true
        pausedByFocusLoss = false
        stopActivePlaybackCallWatcher()
        stopAudiobookProgressTicker()
        retryRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable?.let { handler.removeCallbacks(it) }
        duckRestoreRunnable?.let { handler.removeCallbacks(it) }

        if (isAudiobookMode) {
            flushAudiobookProgress(forcePersist = true, rewindMs = 2000L)
            player?.pause()
        } else {
            player?.playWhenReady = false
            player?.stop()
        }
        RadioController.updateState(false, "Pozastaveno (hovor)")
        updateMediaSessionAndNotification(false)
        startCallEndMonitor()
    }

    private fun pauseForExternalMedia() {
        if (userRequestedStop) return
        pausedByFocusLoss = true
        stopActivePlaybackCallWatcher()
        stopAudiobookProgressTicker()
        retryRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable?.let { handler.removeCallbacks(it) }
        duckRestoreRunnable?.let { handler.removeCallbacks(it) }

        if (isAudiobookMode) {
            flushAudiobookProgress(forcePersist = true, rewindMs = 1500L)
            player?.pause()
        } else {
            player?.playWhenReady = false
            player?.stop()
        }
        RadioController.updateState(false, "Pozastaveno")
        updateMediaSessionAndNotification(false)

        handler.postDelayed({
            if (!userRequestedStop && pausedByFocusLoss && isPhoneCallActive()) pauseForPhoneCall()
        }, 400L)
        handler.postDelayed({
            if (!userRequestedStop && pausedByFocusLoss && isPhoneCallActive()) pauseForPhoneCall()
        }, 1200L)
    }

    private fun startCallEndMonitor() {
        callMonitorRunnable?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (userRequestedStop || !pausedByCall) return
                if (!isPhoneCallActive()) resumeAfterInterruption() else handler.postDelayed(this, 1000L)
            }
        }
        callMonitorRunnable = r
        handler.postDelayed(r, 1000L)
    }

    private fun resumeAfterInterruption() {
        callMonitorRunnable?.let { handler.removeCallbacks(it) }
        if (userRequestedStop || (!pausedByCall && !pausedByFocusLoss)) return
        if (isPhoneCallActive()) {
            startCallEndMonitor()
            return
        }
        pausedByCall = false
        pausedByFocusLoss = false
        requestAudioFocusSafely()

        if (isAudiobookMode) {
            val book = currentAudiobookItem
            val uri = activeAudiobookPlayableUri
            val exo = player
            if (book != null && uri != null && exo != null) {
                if (exo.playbackState == Player.STATE_IDLE || exo.mediaItemCount == 0) {
                    exo.setMediaItem(MediaItem.fromUri(uri), book.positionMs)
                    exo.prepare()
                } else {
                    exo.seekTo(book.positionMs)
                }
                exo.volume = 1.0f
                exo.playWhenReady = true
                startAudiobookProgressTicker()
            } else if (book != null) {
                startAudiobookInternal(book)
            }
        } else {
            val urlToResume = activeUrl.ifBlank { currentPrimaryUrl }
            if (urlToResume.isNotEmpty()) playUrl(urlToResume, fadeIn = false)
        }
    }

    private fun duckBriefly() {
        val exo = player ?: return
        if (userRequestedStop || pausedByCall || pausedByFocusLoss) return
        exo.volume = 0.20f
        duckRestoreRunnable?.let { handler.removeCallbacks(it) }
        handler.postDelayed({
            if (!userRequestedStop && !pausedByCall && isPhoneCallActive()) pauseForPhoneCall()
        }, 300L)
        val r = Runnable {
            if (!userRequestedStop && !pausedByCall && !pausedByFocusLoss) {
                if (isPhoneCallActive()) pauseForPhoneCall() else player?.volume = 1.0f
            }
        }
        duckRestoreRunnable = r
        handler.postDelayed(r, 2500L)
    }

    private fun requestAudioFocusSafely() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        FrameworkAudioAttributes.Builder()
                            .setUsage(FrameworkAudioAttributes.USAGE_MEDIA)
                            .setContentType(FrameworkAudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(audioFocusListener, handler)
                    .build().also { audioFocusRequest = it }
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (_: Exception) {
        }
    }

    private fun abandonAudioFocusSafely() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(audioFocusListener)
            }
        } catch (_: Exception) {
        }
    }

    private fun initMediaSession() {
        val session = MediaSession(this, "RadioPMMediaSession")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                if (isAudiobookMode) {
                    val book = currentAudiobookItem
                    if (book != null) {
                        userRequestedStop = false
                        pausedByCall = false
                        pausedByFocusLoss = false
                        requestAudioFocusSafely()
                        startAudiobookInternal(book)
                        return
                    }
                }
                if (currentPrimaryUrl.isNotEmpty()) {
                    userRequestedStop = false
                    pausedByCall = false
                    pausedByFocusLoss = false
                    requestAudioFocusSafely()
                    playUrl(currentPrimaryUrl, fadeIn = false)
                } else {
                    RadioController.togglePlayback()
                }
            }

            override fun onPause() {
                if (isAudiobookMode) pauseAudiobookInternal() else stopPlaybackInternal()
            }

            override fun onStop() {
                if (isAudiobookMode) pauseAudiobookInternal() else stopPlaybackInternal()
            }

            override fun onSkipToNext() {
                RadioController.playNextStation()
            }

            override fun onSkipToPrevious() {
                RadioController.playPreviousStation()
            }

            override fun onFastForward() {
                if (isAudiobookMode) RadioController.seekAudiobookRelative(30_000L)
            }

            override fun onRewind() {
                if (isAudiobookMode) RadioController.seekAudiobookRelative(-15_000L)
            }

            override fun onSeekTo(pos: Long) {
                if (isAudiobookMode) RadioController.seekAudiobookTo(pos)
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
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            if (isAudiobookMode) RadioController.seekAudiobookRelative(30_000L)
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            if (isAudiobookMode) RadioController.seekAudiobookRelative(-15_000L)
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

    private fun mapWin1250IfInLatin1Range(codePoint: Int): Char {
        return if (codePoint in 0x80..0xFF) WINDOWS_1250_TABLE[codePoint - 0x80] else codePoint.toChar()
    }

    private fun decodeCzechRdsBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            if (b0 == 0) break
            else if (b0 < 0x80) {
                sb.append(b0.toChar())
                i++
            } else if (b0 in 0xC2..0xDF && i + 1 < bytes.size) {
                val b1 = bytes[i + 1].toInt() and 0xFF
                if ((b1 and 0xC0) == 0x80) {
                    val cp = ((b0 and 0x1F) shl 6) or (b1 and 0x3F)
                    sb.append(mapWin1250IfInLatin1Range(cp))
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
                    sb.append(mapWin1250IfInLatin1Range(cp))
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
        return decodeCzechRdsBytes(rawIcy.copyOfRange(startIdx, endIdx))
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
            if (code in 0x80..0xFF) byteBuffer.add(code.toByte()) else {
                flushBytes()
                sb.append(ch)
            }
        }
        flushBytes()
        return cleanAndRepairCzechText(sb.toString())
    }

    private fun decodeHtmlEntities(input: String): String {
        if (!input.contains('&')) return input
        var s = input
            .replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#039;", "'").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&ndash;", "–").replace("&mdash;", "—")
            .replace("&aacute;", "á").replace("&Aacute;", "Á")
            .replace("&eacute;", "é").replace("&Eacute;", "É")
            .replace("&ecaron;", "ě").replace("&Ecaron;", "Ě")
            .replace("&iacute;", "í").replace("&Iacute;", "Í")
            .replace("&oacute;", "ó").replace("&Oacute;", "Ó")
            .replace("&uacute;", "ú").replace("&Uacute;", "Ú")
            .replace("&uring;", "ů").replace("&Uring;", "Ů")
            .replace("&yacute;", "ý").replace("&Yacute;", "Ý")
            .replace("&ccaron;", "č").replace("&Ccaron;", "Č")
            .replace("&dcaron;", "ď").replace("&Dcaron;", "Ď")
            .replace("&ncaron;", "ň").replace("&Ncaron;", "Ň")
            .replace("&rcaron;", "ř").replace("&Rcaron;", "Ř")
            .replace("&scaron;", "š").replace("&Scaron;", "Š")
            .replace("&tcaron;", "ť").replace("&Tcaron;", "Ť")
            .replace("&zcaron;", "ž").replace("&Zcaron;", "Ž")
        if (s.contains("&#")) {
            s = DECIMAL_ENTITY_REGEX.replace(s) { m ->
                val code = m.groupValues[1].toIntOrNull()
                if (code != null && code in 32..0x10FFFF) code.toChar().toString() else m.value
            }
            s = HEX_ENTITY_REGEX.replace(s) { m ->
                val code = m.groupValues[1].toIntOrNull(16)
                if (code != null && code in 32..0x10FFFF) code.toChar().toString() else m.value
            }
        }
        return s
    }

    private fun cleanAndRepairCzechText(input: String): String {
        if (input.isEmpty()) return ""
        val s = decodeHtmlEntities(input)
        val sb = StringBuilder(s.length)
        for (ch in s) {
            if (ch.code in 0x80..0xFF) sb.append(WINDOWS_1250_TABLE[ch.code - 0x80]) else sb.append(ch)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun initExoPlayer() {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("RadioPM/1.0 (Android; ExoPlayer)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(12000)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttrs, false)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    pausedByCall = false
                    pausedByFocusLoss = false
                    retryRunnable?.let { handler.removeCallbacks(it) }
                    if (isAudiobookMode) {
                        val title = currentAudiobookItem?.title ?: currentStationName
                        RadioController.updateState(true, "Audiokniha: $title")
                        flushAudiobookProgress(forcePersist = false)
                        startAudiobookProgressTicker()
                    } else {
                        RadioController.updateState(true, "Hraje: $currentStationName")
                    }
                    updateMediaSessionAndNotification(true)
                    startActivePlaybackCallWatcher()
                } else if (!userRequestedStop && !pausedByCall && !pausedByFocusLoss && exo.playbackState == Player.STATE_BUFFERING) {
                    RadioController.updateState(true, "Načítám: $currentStationName...")
                    updateMediaSessionAndNotification(true)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && isAudiobookMode) {
                    flushAudiobookProgress(forcePersist = true)
                } else if (playbackState == Player.STATE_BUFFERING && !userRequestedStop && !pausedByCall && !pausedByFocusLoss) {
                    RadioController.updateState(true, "Načítám: $currentStationName...")
                } else if (playbackState == Player.STATE_ENDED && !userRequestedStop && !pausedByCall && !pausedByFocusLoss) {
                    if (isAudiobookMode) {
                        stopAudiobookProgressTicker()
                        val finishedId = currentAudiobookItem?.id
                        if (!finishedId.isNullOrBlank()) {
                            RadioController.onAudiobookFinishedFromService(finishedId)
                        }
                    } else {
                        scheduleStreamReconnect()
                    }
                }
            }

            override fun onMetadata(metadata: Metadata) {
                if (isAudiobookMode) return
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
                            if (player?.isPlaying == true) updateMediaSessionAndNotification(true)
                        }
                    }
                }
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                if (isAudiobookMode) return
                val rawTitle = mediaMetadata.title?.toString()?.trim().orEmpty()
                val rawArtist = mediaMetadata.artist?.toString()?.trim().orEmpty()
                val title = fixCzechRdsEncoding(rawTitle)
                val artist = fixCzechRdsEncoding(rawArtist)
                val formatted = when {
                    artist.isNotEmpty() && title.isNotEmpty() -> "$artist — $title"
                    title.isNotEmpty() -> title
                    else -> ""
                }
                val rds = when {
                    lastRawDecodedRds.isNotEmpty() && (formatted.isEmpty() || rawTitle.contains('\uFFFD')) -> lastRawDecodedRds
                    formatted.isNotEmpty() -> formatted
                    lastRawDecodedRds.isNotEmpty() -> lastRawDecodedRds
                    else -> "$currentStationName — živé vysílání"
                }
                currentRds = rds
                RadioController.updateRds(rds)
                if (player?.isPlaying == true) updateMediaSessionAndNotification(true)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (userRequestedStop || pausedByCall || pausedByFocusLoss) return
                if (isAudiobookMode) {
                    stopAudiobookProgressTicker()
                    RadioController.updateState(false, "Nelze přehrát soubor: $currentStationName")
                    updateMediaSessionAndNotification(false)
                } else {
                    scheduleStreamReconnect()
                }
            }
        })
        player = exo
    }

    private fun scheduleStreamReconnect() {
        if (isAudiobookMode || userRequestedStop || pausedByCall || pausedByFocusLoss) return
        if (!triedBackup && currentBackupUrl.isNotBlank() && currentBackupUrl != activeUrl) {
            triedBackup = true
            RadioController.updateState(true, "Přepínám na záložní stream...")
            updateMediaSessionAndNotification(true)
            playUrl(currentBackupUrl, fadeIn = false)
            return
        }
        RadioController.updateState(true, "Obnovuji spojení: $currentStationName...")
        updateMediaSessionAndNotification(true)
        retryRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (!isAudiobookMode && !userRequestedStop && !pausedByCall && !pausedByFocusLoss && currentPrimaryUrl.isNotEmpty()) {
                triedBackup = false
                playUrl(currentPrimaryUrl, fadeIn = false)
            }
        }
        retryRunnable = r
        handler.postDelayed(r, 3500L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY, ACTION_PLAY_FADE_IN -> {
                if (isAudiobookMode) {
                    flushAudiobookProgress(forcePersist = true)
                    stopAudiobookProgressTicker()
                }
                isAudiobookMode = false
                currentAudiobookItem = null
                activeAudiobookPlayableUri = null
                val fadeIn = intent.action == ACTION_PLAY_FADE_IN
                currentStationName = intent.getStringExtra(EXTRA_STATION_NAME) ?: currentStationName
                currentPrimaryUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: currentPrimaryUrl
                currentBackupUrl = intent.getStringExtra(EXTRA_BACKUP_URL) ?: ""
                triedBackup = false
                userRequestedStop = false
                pausedByCall = false
                pausedByFocusLoss = false
                lastRawDecodedRds = ""
                currentRds = "$currentStationName — živé vysílání"
                RadioController.updateRds(currentRds)
                acquireLocks()
                requestAudioFocusSafely()
                startForegroundWithMediaNotification(true)
                if (currentPrimaryUrl.isNotEmpty()) playUrl(currentPrimaryUrl, fadeIn = fadeIn)
            }
            ACTION_PLAY_AUDIOBOOK -> {
                if (isAudiobookMode && currentAudiobookItem != null) {
                    val incomingId = intent.getStringExtra(EXTRA_AUDIOBOOK_ID).orEmpty()
                    if (incomingId != currentAudiobookItem?.id) {
                        flushAudiobookProgress(forcePersist = true)
                    }
                }
                val item = AudiobookItem(
                    id = intent.getStringExtra(EXTRA_AUDIOBOOK_ID).orEmpty(),
                    title = intent.getStringExtra(EXTRA_AUDIOBOOK_TITLE) ?: "Audiokniha",
                    groupTitle = intent.getStringExtra(EXTRA_AUDIOBOOK_GROUP).orEmpty(),
                    sourceUri = intent.getStringExtra(EXTRA_AUDIOBOOK_URI).orEmpty(),
                    zipEntryPath = intent.getStringExtra(EXTRA_AUDIOBOOK_ZIP_ENTRY).orEmpty(),
                    extension = intent.getStringExtra(EXTRA_AUDIOBOOK_EXT) ?: "mp3",
                    positionMs = intent.getLongExtra(EXTRA_AUDIOBOOK_START_POS, 0L),
                    coverKey = intent.getStringExtra(EXTRA_AUDIOBOOK_COVER_KEY).orEmpty()
                )
                userRequestedStop = false
                pausedByCall = false
                pausedByFocusLoss = false
                acquireLocks()
                requestAudioFocusSafely()
                startAudiobookInternal(item)
            }
            ACTION_PAUSE_AUDIOBOOK -> pauseAudiobookInternal()
            ACTION_SEEK_AUDIOBOOK -> {
                if (isAudiobookMode) {
                    val posMs = intent.getLongExtra(EXTRA_SEEK_POSITION_MS, 0L).coerceAtLeast(0L)
                    currentAudiobookItem = currentAudiobookItem?.copy(positionMs = posMs)
                    player?.seekTo(posMs)
                    updateMediaSessionAndNotification(player?.isPlaying == true)
                }
            }
            ACTION_PREV -> RadioController.playPreviousStation()
            ACTION_NEXT -> RadioController.playNextStation()
            ACTION_STOP -> stopPlaybackInternal()
        }
        return START_STICKY
    }

    private fun startAudiobookInternal(item: AudiobookItem) {
        isAudiobookMode = true
        currentAudiobookItem = item
        currentStationName = item.title
        currentRds = item.groupTitle.ifBlank { "Audiokniha" }
        RadioController.updateRds("")
        RadioController.requestAudiobookCover(item)
        retryRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable?.let { handler.removeCallbacks(it) }
        stopAudiobookProgressTicker()

        val reqToken = ++audiobookRequestToken
        RadioController.updateState(true, if (item.zipEntryPath.isNotEmpty()) "Rozbaluji: ${item.title}..." else "Načítám: ${item.title}...")
        startForegroundWithMediaNotification(true)

        thread {
            val playableUri = AudiobookStorageAndroid.resolvePlayableUri(applicationContext, item)
            handler.post {
                if (reqToken != audiobookRequestToken || userRequestedStop || !isAudiobookMode) return@post
                if (playableUri == null) {
                    RadioController.updateState(false, "Soubor nelze otevřít")
                    updateMediaSessionAndNotification(false)
                    return@post
                }
                activeAudiobookPlayableUri = playableUri
                if (isPhoneCallActive()) {
                    pauseForPhoneCall()
                    return@post
                }
                val exo = player ?: return@post
                exo.stop()
                exo.clearMediaItems()
                exo.setMediaItem(MediaItem.fromUri(playableUri), item.positionMs.coerceAtLeast(0L))
                exo.volume = 1.0f
                exo.prepare()
                exo.playWhenReady = true
                startAudiobookProgressTicker()
                startActivePlaybackCallWatcher()
            }
        }
    }

    private fun pauseAudiobookInternal() {
        flushAudiobookProgress(forcePersist = true)
        userRequestedStop = true
        pausedByCall = false
        pausedByFocusLoss = false
        stopAudiobookProgressTicker()
        stopActivePlaybackCallWatcher()
        player?.playWhenReady = false
        player?.pause()
        abandonAudioFocusSafely()
        val title = currentAudiobookItem?.title ?: currentStationName
        RadioController.updateState(false, "Pozastaveno: $title")
        updateMediaSessionAndNotification(false)
        releaseLocks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun playUrl(url: String, fadeIn: Boolean) {
        activeUrl = url
        lastRawDecodedRds = ""
        retryRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable?.let { handler.removeCallbacks(it) }
        if (isPhoneCallActive()) {
            pauseForPhoneCall()
            return
        }
        RadioController.updateState(true, "Připojuji: $currentStationName...")
        updateMediaSessionAndNotification(true)
        startActivePlaybackCallWatcher()

        val trimmed = url.trim()
        val lower = trimmed.lowercase()
        if (lower.endsWith(".m3u") || lower.endsWith(".pls") || lower.contains(".m3u?") || lower.contains(".pls?")) {
            thread {
                val resolved = fetchPlaylistFirstStreamUrl(trimmed) ?: trimmed
                handler.post {
                    if (!isAudiobookMode && !userRequestedStop && !pausedByCall && !pausedByFocusLoss) {
                        startExoPlayerDirectUrl(resolved, fadeIn)
                    }
                }
            }
        } else {
            startExoPlayerDirectUrl(trimmed, fadeIn)
        }
    }

    private fun startExoPlayerDirectUrl(streamUrl: String, fadeIn: Boolean) {
        val exo = player ?: return
        if (isPhoneCallActive()) {
            pauseForPhoneCall()
            return
        }
        exo.stop()
        exo.clearMediaItems()
        exo.setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
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
                    if (l.startsWith("http://", ignoreCase = true) || l.startsWith("https://", ignoreCase = true)) return l
                    if (l.startsWith("File", ignoreCase = true) && l.contains("=")) {
                        val afterEq = l.substringAfter("=").trim()
                        if (afterEq.startsWith("http", ignoreCase = true)) return afterEq
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun startSmoothFadeIn() {
        val steps = 30
        val intervalMs = 2000L
        var currentStep = 1
        val r = object : Runnable {
            override fun run() {
                val exo = player ?: return
                if (userRequestedStop || pausedByCall || pausedByFocusLoss) return
                exo.volume = (currentStep.toFloat() / steps.toFloat()).coerceIn(0.05f, 1.0f)
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
        if (isAudiobookMode) flushAudiobookProgress(forcePersist = true)
        userRequestedStop = true
        pausedByCall = false
        pausedByFocusLoss = false
        lastRawDecodedRds = ""
        stopAudiobookProgressTicker()
        stopActivePlaybackCallWatcher()
        retryRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable?.let { handler.removeCallbacks(it) }
        callMonitorRunnable?.let { handler.removeCallbacks(it) }
        duckRestoreRunnable?.let { handler.removeCallbacks(it) }

        player?.playWhenReady = false
        player?.stop()
        player?.clearMediaItems()
        abandonAudioFocusSafely()

        RadioController.updateState(false, "Zastaveno")
        RadioController.updateRds("")
        updateMediaSessionAndNotification(false)
        releaseLocks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun resolveCurrentArtwork(): Bitmap {
        if (isAudiobookMode) {
            val covKey = currentAudiobookItem?.effectiveCoverKey().orEmpty()
            if (covKey.isNotBlank()) {
                val bytes = RadioController.audiobookCovers.value[covKey]
                if (bytes != null && bytes.isNotEmpty()) {
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (decoded != null) return decoded
                }
            }
            return getOrCreateBookArtworkBitmap()
        }
        val faviconUrl = RadioController.currentStation.value.faviconUrl
        if (faviconUrl.isNotBlank()) {
            val bytes = RadioController.stationIcons.value[faviconUrl]
            if (bytes != null && bytes.isNotEmpty()) {
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (decoded != null) return decoded
            }
        }
        return getOrCreateArtworkBitmap()
    }

    private fun updateMediaSessionAndNotification(playing: Boolean) {
        val session = mediaSession ?: return
        var actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (isAudiobookMode) {
            actions = actions or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
        }
        val pos = if (isAudiobookMode) (player?.currentPosition ?: currentAudiobookItem?.positionMs ?: 0L).coerceAtLeast(0L) else PlaybackState.PLAYBACK_POSITION_UNKNOWN
        val stateBuilder = PlaybackState.Builder()
            .setActions(actions)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, pos, 1.0f)
        session.setPlaybackState(stateBuilder.build())

        val art = resolveCurrentArtwork()
        val dur = if (isAudiobookMode) {
            val d = player?.duration ?: -1L
            if (d > 0L && d != C.TIME_UNSET) d else (currentAudiobookItem?.durationMs ?: -1L)
        } else -1L

        val metaBuilder = AndroidMediaMetadata.Builder()
            .putString(AndroidMediaMetadata.METADATA_KEY_TITLE, currentStationName)
            .putString(AndroidMediaMetadata.METADATA_KEY_ARTIST, currentRds)
            .putString(AndroidMediaMetadata.METADATA_KEY_ALBUM, "Rádio PM")
            .putLong(AndroidMediaMetadata.METADATA_KEY_DURATION, dur)
            .putBitmap(AndroidMediaMetadata.METADATA_KEY_ALBUM_ART, art)
            .putBitmap(AndroidMediaMetadata.METADATA_KEY_DISPLAY_ICON, art)
        session.setMetadata(metaBuilder.build())

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildMediaNotification(playing))
    }

    private fun startForegroundWithMediaNotification(playing: Boolean) {
        val notif = buildMediaNotification(playing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun buildMediaNotification(playing: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this, 100, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prevPi = PendingIntent.getService(
            this, 101, Intent(this, RadioService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = Intent(this, RadioService::class.java).apply {
            if (isAudiobookMode) {
                if (playing) {
                    action = ACTION_PAUSE_AUDIOBOOK
                } else {
                    action = ACTION_PLAY_AUDIOBOOK
                    val b = currentAudiobookItem
                    if (b != null) {
                        putExtra(EXTRA_AUDIOBOOK_ID, b.id)
                        putExtra(EXTRA_AUDIOBOOK_TITLE, b.title)
                        putExtra(EXTRA_AUDIOBOOK_GROUP, b.groupTitle)
                        putExtra(EXTRA_AUDIOBOOK_URI, b.sourceUri)
                        putExtra(EXTRA_AUDIOBOOK_ZIP_ENTRY, b.zipEntryPath)
                        putExtra(EXTRA_AUDIOBOOK_EXT, b.extension)
                        putExtra(EXTRA_AUDIOBOOK_START_POS, b.positionMs)
                        putExtra(EXTRA_AUDIOBOOK_COVER_KEY, b.effectiveCoverKey())
                    }
                }
            } else {
                action = if (playing) ACTION_STOP else ACTION_PLAY
            }
        }
        val playStopPi = PendingIntent.getService(
            this, 102, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextPi = PendingIntent.getService(
            this, 103, Intent(this, RadioService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevAction = Notification.Action.Builder(android.R.drawable.ic_media_previous, "Předchozí", prevPi).build()
        val playPauseAction = Notification.Action.Builder(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (playing) (if (isAudiobookMode) "Pozastavit" else "Zastavit") else "Přehrát",
            playStopPi
        ).build()
        val nextAction = Notification.Action.Builder(android.R.drawable.ic_media_next, "Další", nextPi).build()

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
            .setLargeIcon(resolveCurrentArtwork())
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

    private fun getOrCreateBookArtworkBitmap(): Bitmap {
        cachedBookArtwork?.let { return it }
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(12, 28, 44))
        val pagesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(235, 228, 212) }
        canvas.drawRoundRect(RectF(62f, 36f, 208f, 222f), 18f, 18f, pagesPaint)
        val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(26, 74, 108) }
        canvas.drawRoundRect(RectF(50f, 32f, 198f, 218f), 18f, 18f, coverPaint)
        val spinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(16, 48, 72) }
        canvas.drawRoundRect(RectF(50f, 32f, 76f, 218f), 12f, 12f, spinePaint)
        val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 213, 79) }
        canvas.drawRect(94f, 84f, 172f, 98f, goldPaint)
        canvas.drawRect(94f, 114f, 158f, 126f, goldPaint)
        cachedBookArtwork = bmp
        return bmp
    }

    private fun getOrCreateArtworkBitmap(): Bitmap {
        cachedArtwork?.let { return it }
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(12, 28, 44))
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 213, 79) }
        canvas.drawRoundRect(RectF(36f, 76f, 220f, 210f), 24f, 24f, bodyPaint)
        val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(14, 24, 34) }
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
                description = "Ovládání živého vysílání a audioknih v Now Baru, na hodinkách a v autě"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun acquireLocks() {
        try {
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioPM::StreamingWakeLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {
        }
        try {
            if (wifiLock?.isHeld != true && !isAudiobookMode) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wm != null) {
                    val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        @Suppress("DEPRECATION")
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    }
                    wifiLock = wm.createWifiLock(lockMode, "RadioPM::StreamingWifiLock").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        if (isAudiobookMode) flushAudiobookProgress(forcePersist = true)
        stopAudiobookProgressTicker()
        stopActivePlaybackCallWatcher()
        retryRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable?.let { handler.removeCallbacks(it) }
        callMonitorRunnable?.let { handler.removeCallbacks(it) }
        duckRestoreRunnable?.let { handler.removeCallbacks(it) }
        unregisterAudioModeListener()
        abandonAudioFocusSafely()
        player?.release()
        player = null
        mediaSession?.release()
        mediaSession = null
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
