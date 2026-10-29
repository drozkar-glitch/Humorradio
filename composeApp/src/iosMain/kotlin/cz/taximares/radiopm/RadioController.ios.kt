package cz.taximares.radiopm

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.skia.Image
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.Foundation.NSData
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.dataWithContentsOfURL
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_SEC
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import platform.posix.memcpy

actual fun decodeByteArrayToImageBitmap(bytes: ByteArray): ImageBitmap? {
    return try {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (_: Exception) {
        null
    }
}

@OptIn(ExperimentalForeignApi::class)
actual object RadioController {
    private const val KEY_LAST_STATION_ID = "last_station_id"
    private const val KEY_LAST_STATION_DATA = "last_station_data_v1"
    private const val KEY_PRESET_PREFIX = "saved_preset_v1_"

    private var player: AVPlayer? = null
    private var audioObserversRegistered = false
    private var wasPlayingBeforeInterruption = false
    private var rdsPollingActive = false

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

    private val _isPlaying = MutableStateFlow(false)
    actual val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _statusMessage = MutableStateFlow("Připraveno k poslechu")
    actual val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _rdsText = MutableStateFlow("")
    actual val rdsText: StateFlow<String> = _rdsText.asStateFlow()

    private val _allStations = MutableStateFlow(defaultCzechStations)
    actual val allStations: StateFlow<List<RadioStation>> = _allStations.asStateFlow()

    private val _currentStation = MutableStateFlow(defaultCzechStations.first())
    actual val currentStation: StateFlow<RadioStation> = _currentStation.asStateFlow()

    private val _presets = MutableStateFlow<List<RadioStation?>>(List(4) { null })
    actual val presets: StateFlow<List<RadioStation?>> = _presets.asStateFlow()

    private val _stationIcons = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    actual val stationIcons: StateFlow<Map<String, ByteArray>> = _stationIcons.asStateFlow()

    private val _isUpdatingStations = MutableStateFlow(false)
    actual val isUpdatingStations: StateFlow<Boolean> = _isUpdatingStations.asStateFlow()

    private val _sleepTimerRemainingMinutes = MutableStateFlow(0)
    actual val sleepTimerRemainingMinutes: StateFlow<Int> = _sleepTimerRemainingMinutes.asStateFlow()

    private val _isPreAlarmEnabled = MutableStateFlow(false)
    actual val isPreAlarmEnabled: StateFlow<Boolean> = _isPreAlarmEnabled.asStateFlow()

    private val _preAlarmLabel = MutableStateFlow("Předbuzení vypnuto")
    actual val preAlarmLabel: StateFlow<String> = _preAlarmLabel.asStateFlow()

    private val _volumePercent = MutableStateFlow(75)
    actual val volumePercent: StateFlow<Int> = _volumePercent.asStateFlow()

    private val _isWatchStationListOpen = MutableStateFlow(false)
    actual val isWatchStationListOpen: StateFlow<Boolean> = _isWatchStationListOpen.asStateFlow()

    private val _watchListScrollEvent = MutableStateFlow(0L to 0f)
    actual val watchListScrollEvent: StateFlow<Pair<Long, Float>> = _watchListScrollEvent.asStateFlow()

    init {
        loadSavedStateFromDefaults()
    }

    private fun serializeStation(st: RadioStation): String {
        return listOf(st.id, st.name, st.streamUrl, st.backupUrl, st.faviconUrl, st.qualityLabel)
            .joinToString("\t")
    }

    private fun deserializeStation(raw: String): RadioStation? {
        val parts = raw.split("\t")
        if (parts.size < 3) return null
        val id = parts[0].trim()
        val name = parts[1].trim()
        val streamUrl = parts[2].trim()
        if (name.isEmpty() || streamUrl.isEmpty()) return null
        val backupUrl = parts.getOrNull(3)?.trim().orEmpty()
        val faviconUrl = parts.getOrNull(4)?.trim().orEmpty()
        val qualityLabel = parts.getOrNull(5)?.trim()?.ifEmpty { "128 kbps" } ?: "128 kbps"
        val fromAll = _allStations.value.find { it.id == id || it.name.equals(name, ignoreCase = true) }
        return if (fromAll != null) {
            fromAll.copy(streamUrl = streamUrl, qualityLabel = qualityLabel)
        } else {
            RadioStation(
                id = id.ifEmpty { "ios_saved" },
                name = name,
                streamUrl = streamUrl,
                backupUrl = backupUrl,
                faviconUrl = faviconUrl,
                qualityLabel = qualityLabel
            )
        }
    }

    private fun saveLastStationToDefaults(station: RadioStation) {
        val defs = NSUserDefaults.standardUserDefaults
        defs.setObject(station.id, forKey = KEY_LAST_STATION_ID)
        defs.setObject(serializeStation(station), forKey = KEY_LAST_STATION_DATA)
    }

    private fun loadSavedStateFromDefaults() {
        val defs = NSUserDefaults.standardUserDefaults

        // Nacteni 4 predvoleb
        val loadedPresets = MutableList<RadioStation?>(4) { null }
        for (i in 0..3) {
            val rawPreset = defs.stringForKey("$KEY_PRESET_PREFIX$i")
            if (!rawPreset.isNullOrBlank()) {
                loadedPresets[i] = deserializeStation(rawPreset)
            }
        }
        _presets.value = loadedPresets

        // Nacteni posledni spustene stanice
        var restored: RadioStation? = null
        val rawLastData = defs.stringForKey(KEY_LAST_STATION_DATA)
        if (!rawLastData.isNullOrBlank()) {
            restored = deserializeStation(rawLastData)
        }
        if (restored == null) {
            val lastId = defs.stringForKey(KEY_LAST_STATION_ID)
            if (!lastId.isNullOrBlank()) {
                restored = _allStations.value.find {
                    it.id == lastId || it.name.equals(lastId, ignoreCase = true)
                }
            }
        }
        if (restored != null) {
            _currentStation.value = restored
        }
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
        val s = text
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

    private fun startIosRdsPolling() {
        if (rdsPollingActive) return
        rdsPollingActive = true
        scheduleNextIosRdsPoll()
    }

    private fun scheduleNextIosRdsPoll() {
        if (!_isPlaying.value) {
            rdsPollingActive = false
            return
        }
        val station = _currentStation.value
        val streamUrl = station.streamUrl
        if (streamUrl.contains("stream.radiojar.com/")) {
            val mount = streamUrl.substringAfter("stream.radiojar.com/").substringBefore("?").substringBefore("/").trim()
            if (mount.isNotEmpty()) {
                val apiUrl = "https://www.radiojar.com/api/stations/$mount/now_playing/"
                dispatch_async(dispatch_get_global_queue(0, 0u)) {
                    val nsUrl = NSURL.URLWithString(apiUrl)
                    val data: NSData? = nsUrl?.let { NSData.dataWithContentsOfURL(it) }
                    if (data != null && data.length.toInt() in 2..8192) {
                        val bytes = ByteArray(data.length.toInt())
                        bytes.usePinned { pinned ->
                            memcpy(pinned.addressOf(0), data.bytes, data.length)
                        }
                        val jsonStr = decodeCzechRdsBytes(bytes)
                        val title = extractJsonField(jsonStr, "title")
                        val artist = extractJsonField(jsonStr, "artist")
                        val combined = when {
                            artist.isNotEmpty() && title.isNotEmpty() -> "$artist – $title"
                            title.isNotEmpty() -> title
                            else -> ""
                        }
                        if (combined.isNotEmpty()) {
                            val clean = fixCzechRdsEncoding(combined)
                            dispatch_async(dispatch_get_main_queue()) {
                                if (_isPlaying.value) {
                                    _rdsText.value = clean
                                }
                            }
                        }
                    }
                }
            }
        }

        val delay = dispatch_time(DISPATCH_TIME_NOW, (10L * NSEC_PER_SEC.toLong()))
        dispatch_after(delay, dispatch_get_main_queue()) {
            scheduleNextIosRdsPoll()
        }
    }

    private fun extractJsonField(json: String, key: String): String {
        val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
        return pattern.find(json)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun ensureAudioObservers() {
        if (audioObserversRegistered) return
        audioObserversRegistered = true

        val center = NSNotificationCenter.defaultCenter

        center.addObserverForName(
            name = AVAudioSessionInterruptionNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { notification ->
            val typeNum = notification?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber
            val typeVal = typeNum?.unsignedLongValue ?: 0uL
            if (typeVal == 1uL) {
                if (_isPlaying.value) {
                    wasPlayingBeforeInterruption = true
                    player?.pause()
                    _isPlaying.value = false
                    _statusMessage.value = "Pozastaveno (hovor)"
                }
            } else if (typeVal == 0uL) {
                if (wasPlayingBeforeInterruption) {
                    wasPlayingBeforeInterruption = false
                    playStation(_currentStation.value)
                }
            }
        }

        center.addObserverForName(
            name = AVAudioSessionRouteChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { notification ->
            val reasonNum = notification?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber
            val reasonVal = reasonNum?.unsignedLongValue ?: 0uL
            if (reasonVal == 2uL && _isPlaying.value) {
                player?.play()
            }
        }
    }

    actual fun setWatchStationListOpen(open: Boolean) {
        _isWatchStationListOpen.value = open
    }

    actual fun onRotaryScroll(delta: Float) {
        if (_isWatchStationListOpen.value) {
            _watchListScrollEvent.value = 1L to delta
        }
    }

    actual fun adjustVolume(direction: Int) {
        _volumePercent.value = (_volumePercent.value + direction * 5).coerceIn(0, 100)
    }

    actual fun togglePlayback() {
        if (_isPlaying.value) {
            wasPlayingBeforeInterruption = false
            rdsPollingActive = false
            player?.pause()
            _isPlaying.value = false
            _statusMessage.value = "Zastaveno"
            _rdsText.value = ""
        } else {
            playStation(_currentStation.value)
        }
    }

    actual fun playNextStation() {
        val favs = _presets.value.filterNotNull()
        val list = if (favs.isNotEmpty()) favs else _allStations.value
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.id == _currentStation.value.id }
        val next = if (idx in list.indices) list[(idx + 1) % list.size] else list.first()
        selectAndPlayStation(next)
    }

    actual fun playPreviousStation() {
        val favs = _presets.value.filterNotNull()
        val list = if (favs.isNotEmpty()) favs else _allStations.value
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.id == _currentStation.value.id }
        val prev = if (idx in list.indices) list[(idx - 1 + list.size) % list.size] else list.last()
        selectAndPlayStation(prev)
    }

    actual fun selectAndPlayStation(station: RadioStation) {
        _currentStation.value = station
        saveLastStationToDefaults(station)
        playStation(station)
    }

    actual fun selectStationVariant(station: RadioStation, variant: StreamVariant) {
        val updated = station.copy(streamUrl = variant.url, qualityLabel = variant.label)
        _currentStation.value = updated
        saveLastStationToDefaults(updated)
        playStation(updated)
    }

    private fun playStation(station: RadioStation) {
        ensureAudioObservers()
        try {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(true, error = null)
        } catch (_: Exception) {
        }

        val nsUrl = NSURL.URLWithString(station.streamUrl) ?: return
        val item = AVPlayerItem.playerItemWithURL(nsUrl)
        if (player == null) {
            player = AVPlayer.playerWithPlayerItem(item)
        } else {
            player?.replaceCurrentItemWithPlayerItem(item)
        }
        player?.play()
        _isPlaying.value = true
        _statusMessage.value = "Hraje: ${station.name}"
        _rdsText.value = ""
        saveLastStationToDefaults(station)
        startIosRdsPolling()
    }

    actual fun saveCurrentToPreset(index: Int) {
        saveStationToPreset(index, _currentStation.value)
    }

    actual fun saveStationToPreset(index: Int, station: RadioStation?) {
        if (index !in 0..3) return
        val list = _presets.value.toMutableList()
        list[index] = station
        _presets.value = list
        val defs = NSUserDefaults.standardUserDefaults
        if (station != null) {
            defs.setObject(serializeStation(station), forKey = "$KEY_PRESET_PREFIX$index")
        } else {
            defs.removeObjectForKey("$KEY_PRESET_PREFIX$index")
        }
    }

    actual fun refreshOnlineStations() {
    }

    actual fun requestStationIcon(url: String) {
        val clean = url.trim()
        if (clean.isEmpty() || _stationIcons.value.containsKey(clean)) return
        dispatch_async(dispatch_get_global_queue(0, 0u)) {
            val nsUrl = NSURL.URLWithString(clean)
            val data: NSData? = nsUrl?.let { NSData.dataWithContentsOfURL(it) }
            if (data != null && data.length.toInt() in 1..(512 * 1024)) {
                val bytes = ByteArray(data.length.toInt())
                bytes.usePinned { pinned ->
                    memcpy(pinned.addressOf(0), data.bytes, data.length)
                }
                dispatch_async(dispatch_get_main_queue()) {
                    _stationIcons.value = _stationIcons.value + (clean to bytes)
                }
            }
        }
    }

    actual fun cycleSleepTimer() {
        val options = listOf(0, 15, 30, 45, 60, 90)
        val cur = _sleepTimerRemainingMinutes.value
        _sleepTimerRemainingMinutes.value = options.firstOrNull { it > cur } ?: 0
    }

    actual fun toggleOneShotPreAlarm() {
        _isPreAlarmEnabled.value = !_isPreAlarmEnabled.value
        _preAlarmLabel.value = if (_isPreAlarmEnabled.value) "Předbuzení aktivní" else "Předbuzení vypnuto"
    }
}
