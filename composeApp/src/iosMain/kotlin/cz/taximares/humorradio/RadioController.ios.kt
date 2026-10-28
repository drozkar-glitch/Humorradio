package cz.taximares.humorradio

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
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.dataWithContentsOfURL
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
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
    private var player: AVPlayer? = null

    private val _isPlaying = MutableStateFlow(false)
    actual val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _statusMessage = MutableStateFlow("Připraveno k poslechu")
    actual val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _rdsText = MutableStateFlow("Vyber stanici a stiskni Play")
    actual val rdsText: StateFlow<String> = _rdsText.asStateFlow()

    private val _allStations = MutableStateFlow(defaultCzechStations)
    actual val allStations: StateFlow<List<RadioStation>> = _allStations.asStateFlow()

    private val _currentStation = MutableStateFlow(defaultCzechStations.first())
    actual val currentStation: StateFlow<RadioStation> = _currentStation.asStateFlow()

    private val _presets = MutableStateFlow<List<RadioStation?>>(
        listOf(
            defaultCzechStations.find { it.id == "humor" },
            defaultCzechStations.find { it.id == "radiob_roudnice" },
            defaultCzechStations.find { it.id == "pohadka" },
            defaultCzechStations.find { it.id == "povidka" }
        )
    )
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
            player?.pause()
            _isPlaying.value = false
            _statusMessage.value = "Pozastaveno"
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
        playStation(station)
    }

    actual fun selectStationVariant(station: RadioStation, variant: StreamVariant) {
        val updated = station.copy(streamUrl = variant.url, qualityLabel = variant.label)
        _currentStation.value = updated
        playStation(updated)
    }

    private fun playStation(station: RadioStation) {
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
        _rdsText.value = "${station.name} – živé vysílání"
        NSUserDefaults.standardUserDefaults.setObject(station.id, forKey = "last_station_id")
    }

    actual fun saveCurrentToPreset(index: Int) {
        saveStationToPreset(index, _currentStation.value)
    }

    actual fun saveStationToPreset(index: Int, station: RadioStation?) {
        if (index !in 0..3) return
        val list = _presets.value.toMutableList()
        list[index] = station
        _presets.value = list
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
