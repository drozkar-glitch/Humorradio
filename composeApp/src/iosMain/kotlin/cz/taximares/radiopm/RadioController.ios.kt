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
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSData
import platform.Foundation.NSJSONSerialization
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.dataWithContentsOfURL
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_MSEC
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

private class IosParsedStreamItem(
    val streamUrl: String,
    val bitrate: Int,
    val favicon: String
)

private class IosStationGroupBucket(
    val name: String,
    val items: MutableList<IosParsedStreamItem> = mutableListOf()
)

@OptIn(ExperimentalForeignApi::class)
actual object RadioController {
    private const val KEY_LAST_STATION_ID = "last_station_id"
    private const val KEY_LAST_STATION_DATA = "last_station_data_v1"
    private const val KEY_PRESET_PREFIX = "saved_preset_v1_"
    private const val KEY_CACHED_STATIONS_IOS = "cached_stations_ios_v1"
    private const val KEY_AUDIOBOOKS_IOS = "saved_audiobooks_ios_v1"
    private const val KEY_LAST_AUDIOBOOK_ID = "last_audiobook_id_v1"
    private const val KEY_LAST_MODE = "last_playback_mode_v1"

    private val DECIMAL_ENTITY_REGEX = Regex("&#(\\d+);")
    private val HEX_ENTITY_REGEX = Regex("&#[xX]([0-9a-fA-F]+);")

    private var player: AVPlayer? = null
    private var audioObserversRegistered = false
    private var wasPlayingBeforeInterruption = false
    private var rdsPollingActive = false
    private var audiobookPollingActive = false

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

    private val initialPresetsList: List<RadioStation?> = List(4) { null }
    private val _presets = MutableStateFlow(initialPresetsList)
    actual val presets: StateFlow<List<RadioStation?>> = _presets.asStateFlow()

    private val initialIconsMap: kotlin.collections.Map<String, ByteArray> = emptyMap()
    private val _stationIcons = MutableStateFlow(initialIconsMap)
    actual val stationIcons: StateFlow<kotlin.collections.Map<String, ByteArray>> = _stationIcons.asStateFlow()

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

    private val _playbackMode = MutableStateFlow(PlaybackMode.RADIO)
    actual val playbackMode: StateFlow<PlaybackMode> = _playbackMode.asStateFlow()

    private val _audiobooks = MutableStateFlow<List<AudiobookItem>>(emptyList())
    actual val audiobooks: StateFlow<List<AudiobookItem>> = _audiobooks.asStateFlow()

    private val _currentAudiobook = MutableStateFlow<AudiobookItem?>(null)
    actual val currentAudiobook: StateFlow<AudiobookItem?> = _currentAudiobook.asStateFlow()

    private val _audiobookPositionMs = MutableStateFlow(0L)
    actual val audiobookPositionMs: StateFlow<Long> = _audiobookPositionMs.asStateFlow()

    private val _audiobookDurationMs = MutableStateFlow(0L)
    actual val audiobookDurationMs: StateFlow<Long> = _audiobookDurationMs.asStateFlow()

    private val _isImportingAudiobooks = MutableStateFlow(false)
    actual val isImportingAudiobooks: StateFlow<Boolean> = _isImportingAudiobooks.asStateFlow()

    init {
        loadCachedStationsFromDefaults()
        loadSavedStateFromDefaults()
        loadAudiobooksFromDefaults()
        refreshOnlineStations()
    }

    private fun serializeAudiobook(item: AudiobookItem): String {
        return listOf(
            item.id.replace("\t", " ").replace("\n", " "),
            item.title.replace("\t", " ").replace("\n", " "),
            item.groupTitle.replace("\t", " ").replace("\n", " "),
            item.sourceUri.replace("\t", "").replace("\n", ""),
            item.zipEntryPath.replace("\t", "").replace("\n", ""),
            item.extension.replace("\t", "").replace("\n", ""),
            item.positionMs.toString(),
            item.durationMs.toString(),
            if (item.isPlayed) "1" else "0"
        ).joinToString("\t")
    }

    private fun deserializeAudiobook(line: String): AudiobookItem? {
        val p = line.split("\t")
        if (p.size < 9) return null
        val id = p[0].trim()
        val title = p[1].trim()
        val src = p[3].trim()
        if (id.isEmpty() || title.isEmpty() || src.isEmpty()) return null
        return AudiobookItem(
            id = id,
            title = title,
            groupTitle = p[2].trim(),
            sourceUri = src,
            zipEntryPath = p[4].trim(),
            extension = p[5].trim().ifEmpty { "mp3" },
            positionMs = p[6].toLongOrNull() ?: 0L,
            durationMs = p[7].toLongOrNull() ?: 0L,
            isPlayed = p[8] == "1"
        )
    }

    private fun saveAudiobooksToDefaults() {
        val defs = NSUserDefaults.standardUserDefaults
        val raw = _audiobooks.value.joinToString("\n") { serializeAudiobook(it) }
        defs.setObject(raw, forKey = KEY_AUDIOBOOKS_IOS)
        _currentAudiobook.value?.let { defs.setObject(it.id, forKey = KEY_LAST_AUDIOBOOK_ID) }
        defs.setObject(_playbackMode.value.name, forKey = KEY_LAST_MODE)
    }

    private fun loadAudiobooksFromDefaults() {
        val defs = NSUserDefaults.standardUserDefaults
        val raw = defs.stringForKey(KEY_AUDIOBOOKS_IOS).orEmpty()
        if (raw.isNotBlank()) {
            val loaded = raw.split("\n").mapNotNull { line ->
                val t = line.trim()
                if (t.isNotEmpty()) deserializeAudiobook(t) else null
            }
            _audiobooks.value = loaded
            val lastId = defs.stringForKey(KEY_LAST_AUDIOBOOK_ID)
            val found = loaded.find { it.id == lastId } ?: loaded.firstOrNull()
            _currentAudiobook.value = found
            if (found != null) {
                _audiobookPositionMs.value = found.positionMs
                _audiobookDurationMs.value = found.durationMs
            }
        }
        if (defs.stringForKey(KEY_LAST_MODE) == PlaybackMode.AUDIOBOOK.name && _currentAudiobook.value != null) {
            _playbackMode.value = PlaybackMode.AUDIOBOOK
        }
    }

    private fun serializeStation(st: RadioStation): String {
        val variantsEncoded = st.variants.joinToString(";;") { v ->
            val safeLabel = v.label.replace("|", " ").replace(";", " ").replace("\t", " ").replace("\n", " ")
            val safeUrl = v.url.replace("|", "").replace(";", "").replace("\t", "").replace("\n", "")
            "$safeLabel|$safeUrl|${v.bitrate}"
        }
        return listOf(
            st.id.replace("\t", " ").replace("\n", " "),
            st.name.replace("\t", " ").replace("\n", " "),
            st.streamUrl.replace("\t", "").replace("\n", ""),
            st.backupUrl.replace("\t", "").replace("\n", ""),
            st.faviconUrl.replace("\t", "").replace("\n", ""),
            st.qualityLabel.replace("\t", " ").replace("\n", " "),
            variantsEncoded
        ).joinToString("\t")
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
        val variantsRaw = parts.getOrNull(6)?.trim().orEmpty()
        val parsedVariants = mutableListOf<StreamVariant>()
        if (variantsRaw.isNotEmpty()) {
            for (vToken in variantsRaw.split(";;")) {
                val vParts = vToken.split("|")
                if (vParts.size >= 2) {
                    val vLabel = vParts[0].trim().ifEmpty { qualityLabel }
                    val vUrl = vParts[1].trim()
                    val vBitrate = vParts.getOrNull(2)?.trim()?.toIntOrNull() ?: 128
                    if (vUrl.isNotEmpty()) {
                        parsedVariants.add(StreamVariant(label = vLabel, url = vUrl, bitrate = vBitrate))
                    }
                }
            }
        }
        val fromAll = _allStations.value.find { it.id == id || it.name.equals(name, ignoreCase = true) }
        return if (fromAll != null) {
            fromAll.copy(
                streamUrl = streamUrl.ifBlank { fromAll.streamUrl },
                backupUrl = backupUrl.ifBlank { fromAll.backupUrl },
                faviconUrl = faviconUrl.ifBlank { fromAll.faviconUrl },
                qualityLabel = qualityLabel.ifBlank { fromAll.qualityLabel },
                variants = if (parsedVariants.size > fromAll.variants.size) parsedVariants else fromAll.variants
            )
        } else {
            RadioStation(
                id = id.ifEmpty { "ios_saved" },
                name = name,
                streamUrl = streamUrl,
                backupUrl = backupUrl,
                faviconUrl = faviconUrl,
                qualityLabel = qualityLabel,
                variants = parsedVariants.ifEmpty { listOf(StreamVariant(qualityLabel, streamUrl)) }
            )
        }
    }

    private fun saveStationsToCache(stations: List<RadioStation>) {
        try {
            val serialized = stations.joinToString("\n") { serializeStation(it) }
            NSUserDefaults.standardUserDefaults.setObject(serialized, forKey = KEY_CACHED_STATIONS_IOS)
        } catch (_: Exception) {
        }
    }

    private fun loadCachedStationsFromDefaults() {
        try {
            val raw = NSUserDefaults.standardUserDefaults.stringForKey(KEY_CACHED_STATIONS_IOS)
            if (raw.isNullOrBlank()) return
            val loaded = raw.split("\n").mapNotNull { line ->
                val t = line.trim()
                if (t.isNotEmpty()) deserializeStation(t) else null
            }
            if (loaded.isNotEmpty()) {
                _allStations.value = mergeStationsWithDefaults(loaded)
            }
        } catch (_: Exception) {
        }
    }

    private fun saveLastStationToDefaults(station: RadioStation) {
        val defs = NSUserDefaults.standardUserDefaults
        defs.setObject(station.id, forKey = KEY_LAST_STATION_ID)
        defs.setObject(serializeStation(station), forKey = KEY_LAST_STATION_DATA)
        defs.setObject(PlaybackMode.RADIO.name, forKey = KEY_LAST_MODE)
    }

    private fun saveAllPresetsToDefaults() {
        val defs = NSUserDefaults.standardUserDefaults
        val list = _presets.value
        for (i in 0..3) {
            val st = list.getOrNull(i)
            if (st != null) {
                defs.setObject(serializeStation(st), forKey = "$KEY_PRESET_PREFIX$i")
            } else {
                defs.removeObjectForKey("$KEY_PRESET_PREFIX$i")
            }
        }
    }

    private fun loadSavedStateFromDefaults() {
        val defs = NSUserDefaults.standardUserDefaults
        val loadedPresets = MutableList<RadioStation?>(4) { null }
        for (i in 0..3) {
            val rawPreset = defs.stringForKey("$KEY_PRESET_PREFIX$i")
            if (!rawPreset.isNullOrBlank()) {
                loadedPresets[i] = deserializeStation(rawPreset)
            }
        }
        _presets.value = loadedPresets

        var restored: RadioStation? = null
        val rawLastData = defs.stringForKey(KEY_LAST_STATION_DATA)
        if (!rawLastData.isNullOrBlank()) {
            restored = deserializeStation(rawLastData)
        }
        if (restored == null) {
            val lastId = defs.stringForKey(KEY_LAST_STATION_ID)
            if (!lastId.isNullOrBlank()) {
                restored = _allStations.value.find { it.id == lastId || it.name.equals(lastId, ignoreCase = true) }
            }
        }
        if (restored != null) {
            _currentStation.value = restored
            if (restored.faviconUrl.isNotBlank()) {
                requestStationIcon(restored.faviconUrl)
            }
        }
    }

    private fun cleanStationGroupName(rawName: String): String {
        return rawName
            .replace(Regex("\\s*\\(\\d+\\s*kbps\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*\\d+\\s*kbps$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun parseRadioBrowserData(data: NSData): List<RadioStation> {
        val jsonArray = try {
            NSJSONSerialization.JSONObjectWithData(data, options = 0u, error = null) as? List<*>
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val buckets = mutableListOf<IosStationGroupBucket>()
        val bucketIndexByName = mutableListOf<Pair<String, Int>>()
        for (element in jsonArray) {
            val obj = element as? kotlin.collections.Map<*, *> ?: continue
            val name = (obj["name"] as? String)?.trim().orEmpty()
            val urlResolved = (obj["url_resolved"] as? String)?.trim().orEmpty()
            val urlOrig = (obj["url"] as? String)?.trim().orEmpty()
            val streamUrl = if (urlResolved.isNotEmpty()) urlResolved else urlOrig
            val codec = (obj["codec"] as? String)?.trim()?.uppercase().orEmpty()
            val bitrateNum = obj["bitrate"] as? NSNumber
            val bitrate = bitrateNum?.intValue?.let { if (it <= 0) 128 else it } ?: 128
            val favicon = (obj["favicon"] as? String)?.trim().orEmpty()

            if (name.isEmpty() || streamUrl.isEmpty()) continue
            if (codec.contains("UNKNOWN") || streamUrl.endsWith(".asx", ignoreCase = true) || streamUrl.endsWith(".wma", ignoreCase = true)) {
                continue
            }
            val cleanName = cleanStationGroupName(name)
            val existingIdx = bucketIndexByName.firstOrNull { it.first.equals(cleanName, ignoreCase = true) }?.second
            val bucket = if (existingIdx != null) {
                buckets[existingIdx]
            } else {
                val newBucket = IosStationGroupBucket(cleanName)
                bucketIndexByName.add(cleanName to buckets.size)
                buckets.add(newBucket)
                newBucket
            }
            bucket.items.add(IosParsedStreamItem(streamUrl, bitrate, favicon))
        }

        val result = mutableListOf<RadioStation>()
        var stationCounter = 0
        for (bucket in buckets) {
            stationCounter++
            val sorted = bucket.items.distinctBy { it.streamUrl }.sortedByDescending { it.bitrate }
            val primary = sorted.first()
            val backup = sorted.getOrNull(1)?.streamUrl ?: ""
            val icon = sorted.firstOrNull { it.favicon.isNotEmpty() }?.favicon ?: ""
            val variants = sorted.mapIndexed { idx, item ->
                val br = item.bitrate
                val label = if (sorted.count { it.bitrate == br } > 1) "${br} kbps (#${idx + 1})" else "${br} kbps"
                StreamVariant(label = label, url = item.streamUrl, bitrate = br)
            }
            val slug = bucket.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            val id = if (slug.isNotEmpty()) "online_$slug" else "online_st_$stationCounter"
            result.add(
                RadioStation(
                    id = id,
                    name = bucket.name,
                    streamUrl = primary.streamUrl,
                    backupUrl = backup,
                    faviconUrl = icon,
                    qualityLabel = "${primary.bitrate} kbps",
                    variants = variants
                )
            )
        }
        return result
    }

    private fun mergeStationsWithDefaults(online: List<RadioStation>): List<RadioStation> {
        val result = mutableListOf<RadioStation>()
        val usedOnlineIndices = mutableSetOf<Int>()
        for (def in defaultCzechStations) {
            val matchIdx = online.indexOfFirst {
                it.name.equals(def.name, ignoreCase = true) ||
                    (def.id == "radiob_roudnice" && it.name.equals("Radio B", ignoreCase = true))
            }
            if (matchIdx >= 0) {
                usedOnlineIndices.add(matchIdx)
                val on = online[matchIdx]
                val combinedVariants = (on.variants + def.variants).distinctBy { it.url }
                result.add(
                    def.copy(
                        streamUrl = def.streamUrl,
                        backupUrl = on.streamUrl.ifBlank { def.backupUrl },
                        faviconUrl = def.faviconUrl.ifBlank { on.faviconUrl },
                        variants = combinedVariants
                    )
                )
            } else {
                result.add(def)
            }
        }
        for (i in online.indices) {
            if (i !in usedOnlineIndices) result.add(online[i])
        }
        return result
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
        return decodeHtmlEntities(sb.toString()).replace(Regex("\\s+"), " ").trim()
    }

    private fun decodeHtmlEntities(input: String): String {
        if (!input.contains('&')) return input
        var s = input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
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

    private fun startIosRdsPolling() {
        if (rdsPollingActive) return
        rdsPollingActive = true
        scheduleNextIosRdsPoll()
    }

    private fun scheduleNextIosRdsPoll() {
        if (!_isPlaying.value || _playbackMode.value != PlaybackMode.RADIO) {
            rdsPollingActive = false
            return
        }
        val streamUrl = _currentStation.value.streamUrl
        if (streamUrl.contains("stream.radiojar.com/")) {
            val mount = streamUrl.substringAfter("stream.radiojar.com/").substringBefore("?").substringBefore("/").trim()
            if (mount.isNotEmpty()) {
                val apiUrl = "https://www.radiojar.com/api/stations/$mount/now_playing/"
                dispatch_async(dispatch_get_global_queue(0, 0u)) {
                    val nsUrl = NSURL.URLWithString(apiUrl)
                    val data: NSData? = nsUrl?.let { NSData.dataWithContentsOfURL(it) }
                    if (data != null && data.length.toInt() in 2..8192) {
                        val bytes = ByteArray(data.length.toInt())
                        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
                        val jsonStr = decodeCzechRdsBytes(bytes)
                        val title = Regex("\"title\"\\s*:\\s*\"([^\"]*)\"").find(jsonStr)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                        val artist = Regex("\"artist\"\\s*:\\s*\"([^\"]*)\"").find(jsonStr)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                        val combined = when {
                            artist.isNotEmpty() && title.isNotEmpty() -> "$artist — $title"
                            title.isNotEmpty() -> title
                            else -> ""
                        }
                        if (combined.isNotEmpty()) {
                            dispatch_async(dispatch_get_main_queue()) {
                                if (_isPlaying.value && _playbackMode.value == PlaybackMode.RADIO) {
                                    _rdsText.value = combined
                                }
                            }
                        }
                    }
                }
            }
        }
        val delay = dispatch_time(DISPATCH_TIME_NOW, (10L * NSEC_PER_SEC.toLong()))
        dispatch_after(delay, dispatch_get_main_queue()) { scheduleNextIosRdsPoll() }
    }

    private fun startAudiobookProgressPolling() {
        if (audiobookPollingActive) return
        audiobookPollingActive = true
        scheduleNextAudiobookTick()
    }

    private fun scheduleNextAudiobookTick() {
        if (!_isPlaying.value || _playbackMode.value != PlaybackMode.AUDIOBOOK) {
            audiobookPollingActive = false
            return
        }
        syncCurrentAudiobookProgress(persist = true)
        val delay = dispatch_time(DISPATCH_TIME_NOW, (1000L * NSEC_PER_MSEC.toLong()))
        dispatch_after(delay, dispatch_get_main_queue()) { scheduleNextAudiobookTick() }
    }

    private fun syncCurrentAudiobookProgress(persist: Boolean) {
        val p = player ?: return
        val curItem = _currentAudiobook.value ?: return
        val posSec = CMTimeGetSeconds(p.currentTime())
        val durSec = p.currentItem?.let { CMTimeGetSeconds(it.duration) } ?: 0.0
        val posMs = if (!posSec.isNaN() && posSec.isFinite() && posSec >= 0.0) (posSec * 1000.0).toLong() else curItem.positionMs
        val durMs = if (!durSec.isNaN() && durSec.isFinite() && durSec > 0.0) (durSec * 1000.0).toLong() else curItem.durationMs
        _audiobookPositionMs.value = posMs
        if (durMs > 0L) _audiobookDurationMs.value = durMs
        val updated = curItem.copy(positionMs = posMs, durationMs = if (durMs > 0L) durMs else curItem.durationMs)
        _currentAudiobook.value = updated
        _audiobooks.value = _audiobooks.value.map { if (it.id == updated.id) updated else it }
        if (persist) saveAudiobooksToDefaults()
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
            if (typeVal == 1uL && _isPlaying.value) {
                wasPlayingBeforeInterruption = true
                if (_playbackMode.value == PlaybackMode.AUDIOBOOK) syncCurrentAudiobookProgress(persist = true)
                player?.pause()
                _isPlaying.value = false
                _statusMessage.value = "Pozastaveno (hovor)"
            } else if (typeVal == 0uL && wasPlayingBeforeInterruption) {
                wasPlayingBeforeInterruption = false
                if (_playbackMode.value == PlaybackMode.AUDIOBOOK) {
                    _currentAudiobook.value?.let { selectAndPlayAudiobook(it) }
                } else {
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
            if ((reasonNum?.unsignedLongValue ?: 0uL) == 2uL && _isPlaying.value) {
                player?.play()
            }
        }
        center.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ ->
            if (_playbackMode.value == PlaybackMode.AUDIOBOOK) {
                onAudiobookFinished()
            }
        }
    }

    private fun onAudiobookFinished() {
        val finished = _currentAudiobook.value ?: return
        val list = _audiobooks.value
        val idx = list.indexOfFirst { it.id == finished.id }
        val marked = finished.copy(positionMs = 0L, isPlayed = true)
        val newList = list.map { if (it.id == marked.id) marked else it }
        _audiobooks.value = newList
        _currentAudiobook.value = marked
        _audiobookPositionMs.value = 0L
        saveAudiobooksToDefaults()

        val nextCandidate = if (idx >= 0 && idx + 1 < newList.size) {
            newList.subList(idx + 1, newList.size).firstOrNull { !it.isPlayed } ?: newList.getOrNull(idx + 1)
        } else {
            null
        }
        if (nextCandidate != null && !nextCandidate.isPlayed) {
            selectAndPlayAudiobook(nextCandidate)
        } else {
            _isPlaying.value = false
            _statusMessage.value = "Audiokniha dohrána"
        }
    }

    actual fun setWatchStationListOpen(open: Boolean) {
        _isWatchStationListOpen.value = open
    }

    actual fun onRotaryScroll(delta: Float) {
        if (_isWatchStationListOpen.value) _watchListScrollEvent.value = 1L to delta
    }

    actual fun adjustVolume(direction: Int) {
        _volumePercent.value = (_volumePercent.value + direction * 5).coerceIn(0, 100)
    }

    actual fun togglePlayback() {
        if (_playbackMode.value == PlaybackMode.AUDIOBOOK) {
            val cur = _currentAudiobook.value
            if (_isPlaying.value) {
                wasPlayingBeforeInterruption = false
                syncCurrentAudiobookProgress(persist = true)
                player?.pause()
                _isPlaying.value = false
                _statusMessage.value = "Pozastaveno"
            } else if (cur != null) {
                selectAndPlayAudiobook(cur)
            }
            return
        }
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
        if (_playbackMode.value == PlaybackMode.AUDIOBOOK) {
            val list = _audiobooks.value
            if (list.isEmpty()) return
            val curId = _currentAudiobook.value?.id
            val idx = list.indexOfFirst { it.id == curId }
            val next = if (idx in list.indices) list[(idx + 1) % list.size] else list.first()
            selectAndPlayAudiobook(next)
            return
        }
        val favs = _presets.value.filterNotNull()
        val list = if (favs.isNotEmpty()) favs else _allStations.value
        if (list.isEmpty()) return
        val cur = _currentStation.value
        val idx = list.indexOfFirst { it.id == cur.id || it.name.equals(cur.name, ignoreCase = true) }
        val next = if (idx in list.indices) list[(idx + 1) % list.size] else list.first()
        selectAndPlayStation(next)
    }

    actual fun playPreviousStation() {
        if (_playbackMode.value == PlaybackMode.AUDIOBOOK) {
            val list = _audiobooks.value
            if (list.isEmpty()) return
            val curId = _currentAudiobook.value?.id
            val idx = list.indexOfFirst { it.id == curId }
            val prev = if (idx in list.indices) list[(idx - 1 + list.size) % list.size] else list.last()
            selectAndPlayAudiobook(prev)
            return
        }
        val favs = _presets.value.filterNotNull()
        val list = if (favs.isNotEmpty()) favs else _allStations.value
        if (list.isEmpty()) return
        val cur = _currentStation.value
        val idx = list.indexOfFirst { it.id == cur.id || it.name.equals(cur.name, ignoreCase = true) }
        val prev = if (idx in list.indices) list[(idx - 1 + list.size) % list.size] else list.last()
        selectAndPlayStation(prev)
    }

    actual fun selectAndPlayStation(station: RadioStation) {
        if (_playbackMode.value == PlaybackMode.AUDIOBOOK && _isPlaying.value) {
            syncCurrentAudiobookProgress(persist = true)
        }
        _playbackMode.value = PlaybackMode.RADIO
        _currentStation.value = station
        saveLastStationToDefaults(station)
        if (station.faviconUrl.isNotBlank()) requestStationIcon(station.faviconUrl)
        playStation(station)
    }

    actual fun selectStationVariant(station: RadioStation, variant: StreamVariant) {
        val updated = station.copy(streamUrl = variant.url, qualityLabel = variant.label)
        _allStations.value = _allStations.value.map {
            if (it.id == station.id || it.name.equals(station.name, ignoreCase = true)) updated else it
        }
        _presets.value = _presets.value.map {
            if (it != null && (it.id == station.id || it.name.equals(station.name, ignoreCase = true))) updated else it
        }
        saveAllPresetsToDefaults()
        selectAndPlayStation(updated)
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
        if (player == null) player = AVPlayer.playerWithPlayerItem(item) else player?.replaceCurrentItemWithPlayerItem(item)
        player?.play()
        _playbackMode.value = PlaybackMode.RADIO
        _isPlaying.value = true
        _statusMessage.value = "Hraje: ${station.name}"
        _rdsText.value = ""
        saveLastStationToDefaults(station)
        startIosRdsPolling()
    }

    actual fun pickAudiobookFiles() {
        _isImportingAudiobooks.value = true
        AudiobookStorageIos.pickFilesOrZip { imported ->
            mergeImportedAudiobooks(imported)
            _isImportingAudiobooks.value = false
        }
    }

    actual fun pickAudiobookFolder() {
        _isImportingAudiobooks.value = true
        AudiobookStorageIos.pickFolder { imported ->
            mergeImportedAudiobooks(imported)
            _isImportingAudiobooks.value = false
        }
    }

    private fun mergeImportedAudiobooks(imported: List<AudiobookItem>) {
        if (imported.isEmpty()) return
        val existingById = _audiobooks.value.associateBy { it.id }
        val merged = _audiobooks.value.toMutableList()
        for (item in imported) {
            if (!existingById.containsKey(item.id)) {
                merged.add(item)
            }
        }
        _audiobooks.value = merged
        if (_currentAudiobook.value == null) {
            _currentAudiobook.value = merged.firstOrNull()
        }
        saveAudiobooksToDefaults()
    }

    actual fun selectAndPlayAudiobook(item: AudiobookItem) {
        if (_playbackMode.value == PlaybackMode.AUDIOBOOK && _isPlaying.value && _currentAudiobook.value?.id != item.id) {
            syncCurrentAudiobookProgress(persist = true)
        }
        ensureAudioObservers()
        val latest = _audiobooks.value.find { it.id == item.id } ?: item
        val startPosMs = if (latest.isPlayed && latest.positionMs <= 0L) 0L else latest.positionMs
        val preparedItem = if (latest.isPlayed && startPosMs == 0L) latest.copy(isPlayed = false) else latest

        _playbackMode.value = PlaybackMode.AUDIOBOOK
        _currentAudiobook.value = preparedItem
        _audiobookPositionMs.value = startPosMs
        _audiobookDurationMs.value = preparedItem.durationMs
        _audiobooks.value = _audiobooks.value.map { if (it.id == preparedItem.id) preparedItem else it }
        _statusMessage.value = "Načítám: ${preparedItem.title}..."
        rdsPollingActive = false
        _rdsText.value = ""

        dispatch_async(dispatch_get_global_queue(0, 0u)) {
            val localPath = AudiobookStorageIos.resolvePlayablePath(preparedItem)
            dispatch_async(dispatch_get_main_queue()) {
                if (localPath == null) {
                    _isPlaying.value = false
                    _statusMessage.value = "Soubor nelze otevřít"
                    return@dispatch_async
                }
                try {
                    val session = AVAudioSession.sharedInstance()
                    session.setCategory(AVAudioSessionCategoryPlayback, error = null)
                    session.setActive(true, error = null)
                } catch (_: Exception) {
                }
                val fileUrl = NSURL.fileURLWithPath(localPath)
                val avItem = AVPlayerItem.playerItemWithURL(fileUrl)
                if (player == null) player = AVPlayer.playerWithPlayerItem(avItem) else player?.replaceCurrentItemWithPlayerItem(avItem)
                if (startPosMs > 0L) {
                    player?.seekToTime(CMTimeMakeWithSeconds(startPosMs.toDouble() / 1000.0, 1000))
                }
                player?.play()
                _isPlaying.value = true
                _statusMessage.value = "Audiokniha: ${preparedItem.title}"
                saveAudiobooksToDefaults()
                startAudiobookProgressPolling()
            }
        }
    }

    actual fun seekAudiobookTo(positionMs: Long) {
        if (_playbackMode.value != PlaybackMode.AUDIOBOOK) return
        val target = positionMs.coerceAtLeast(0L)
        player?.seekToTime(CMTimeMakeWithSeconds(target.toDouble() / 1000.0, 1000))
        _audiobookPositionMs.value = target
        val cur = _currentAudiobook.value ?: return
        val updated = cur.copy(positionMs = target)
        _currentAudiobook.value = updated
        _audiobooks.value = _audiobooks.value.map { if (it.id == updated.id) updated else it }
        saveAudiobooksToDefaults()
    }

    actual fun seekAudiobookRelative(deltaMs: Long) {
        val dur = _audiobookDurationMs.value
        val raw = (_audiobookPositionMs.value + deltaMs).coerceAtLeast(0L)
        val clamped = if (dur > 0L) raw.coerceAtMost(dur) else raw
        seekAudiobookTo(clamped)
    }

    actual fun toggleAudiobookPlayed(itemId: String) {
        val updatedList = _audiobooks.value.map { item ->
            if (item.id == itemId) {
                val nextPlayed = !item.isPlayed
                item.copy(isPlayed = nextPlayed, positionMs = if (nextPlayed) 0L else item.positionMs)
            } else {
                item
            }
        }
        _audiobooks.value = updatedList
        _currentAudiobook.value = updatedList.find { it.id == _currentAudiobook.value?.id }
        saveAudiobooksToDefaults()
    }

    actual fun removeAudiobook(itemId: String) {
        val wasCurrent = _currentAudiobook.value?.id == itemId
        if (wasCurrent && _playbackMode.value == PlaybackMode.AUDIOBOOK && _isPlaying.value) {
            player?.pause()
            _isPlaying.value = false
            _statusMessage.value = "Připraveno k poslechu"
        }
        val remaining = _audiobooks.value.filterNot { it.id == itemId }
        _audiobooks.value = remaining
        if (wasCurrent) {
            _currentAudiobook.value = remaining.firstOrNull()
            _audiobookPositionMs.value = _currentAudiobook.value?.positionMs ?: 0L
            _audiobookDurationMs.value = _currentAudiobook.value?.durationMs ?: 0L
            if (remaining.isEmpty()) _playbackMode.value = PlaybackMode.RADIO
        }
        saveAudiobooksToDefaults()
    }

    actual fun removePlayedAudiobooks() {
        val curId = _currentAudiobook.value?.id
        val remaining = _audiobooks.value.filterNot { it.isPlayed && !(it.id == curId && _isPlaying.value) }
        _audiobooks.value = remaining
        if (_currentAudiobook.value != null && remaining.none { it.id == curId }) {
            _currentAudiobook.value = remaining.firstOrNull()
            _audiobookPositionMs.value = _currentAudiobook.value?.positionMs ?: 0L
            _audiobookDurationMs.value = _currentAudiobook.value?.durationMs ?: 0L
            if (remaining.isEmpty()) _playbackMode.value = PlaybackMode.RADIO
        }
        saveAudiobooksToDefaults()
    }

    actual fun saveCurrentToPreset(index: Int) {
        saveStationToPreset(index, _currentStation.value)
    }

    actual fun saveStationToPreset(index: Int, station: RadioStation?) {
        if (index !in 0..3) return
        val list = _presets.value.toMutableList()
        list[index] = station
        _presets.value = list
        saveAllPresetsToDefaults()
    }

    actual fun refreshOnlineStations() {
        if (_isUpdatingStations.value) return
        _isUpdatingStations.value = true
        dispatch_async(dispatch_get_global_queue(0, 0u)) {
            val hosts = listOf(
                "de1.api.radio-browser.info",
                "at1.api.radio-browser.info",
                "nl1.api.radio-browser.info",
                "fi1.api.radio-browser.info"
            )
            var fetched: List<RadioStation> = emptyList()
            for (host in hosts) {
                try {
                    val apiUrl = "https://$host/json/stations/bycountrycodeexact/CZ?hidebroken=true&order=clickcount&reverse=true&limit=1500"
                    val nsUrl = NSURL.URLWithString(apiUrl)
                    val data = nsUrl?.let { NSData.dataWithContentsOfURL(it) }
                    if (data != null && data.length.toInt() > 10) {
                        fetched = parseRadioBrowserData(data)
                        if (fetched.isNotEmpty()) break
                    }
                } catch (_: Exception) {
                }
            }
            dispatch_async(dispatch_get_main_queue()) {
                if (fetched.isNotEmpty()) {
                    val merged = mergeStationsWithDefaults(fetched)
                    _allStations.value = merged
                    saveStationsToCache(merged)
                }
                _isUpdatingStations.value = false
            }
        }
    }

    actual fun requestStationIcon(url: String) {
        val clean = url.trim()
        if (clean.isEmpty() || _stationIcons.value.containsKey(clean)) return
        dispatch_async(dispatch_get_global_queue(0, 0u)) {
            val nsUrl = NSURL.URLWithString(clean)
            val data: NSData? = nsUrl?.let { NSData.dataWithContentsOfURL(it) }
            if (data != null && data.length.toInt() in 1..(512 * 1024)) {
                val bytes = ByteArray(data.length.toInt())
                bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
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
