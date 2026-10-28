package cz.taximares.radiopm

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow

data class StreamVariant(
    val label: String,
    val url: String,
    val bitrate: Int = 128
)

data class RadioStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val backupUrl: String = "",
    val faviconUrl: String = "",
    val qualityLabel: String = "128 kbps",
    val variants: List<StreamVariant> = listOf(StreamVariant(qualityLabel, streamUrl))
)

val defaultCzechStations: List<RadioStation> = listOf(
    RadioStation(
        id = "radiob_roudnice",
        name = "Rádio B",
        streamUrl = "https://stream.radiojar.com/9nayamd31tzuv",
        backupUrl = "http://stream.radiojar.com/9nayamd31tzuv",
        faviconUrl = "https://www.radiob.cz/assets/favicon/apple-touch-icon.png",
        qualityLabel = "128 kbps",
        variants = listOf(
            StreamVariant("128 kbps", "https://stream.radiojar.com/9nayamd31tzuv", 128)
        )
    ),
    RadioStation(
        id = "radiozurnal",
        name = "ČRo Radiožurnál",
        streamUrl = "https://rozhlas.stream/radiozurnal_high.aac",
        backupUrl = "https://rozhlas.stream/radiozurnal_mp3_128.mp3",
        faviconUrl = "https://portal.rozhlas.cz/sites/default/files/favicon_radiozurnal.png",
        qualityLabel = "128 kbps",
        variants = listOf(
            StreamVariant("64 kbps AAC", "https://rozhlas.stream/radiozurnal_low.aac", 64),
            StreamVariant("128 kbps MP3", "https://rozhlas.stream/radiozurnal_mp3_128.mp3", 128),
            StreamVariant("256 kbps MP3", "https://rozhlas.stream/radiozurnal_mp3_256.mp3", 256)
        )
    ),
    RadioStation(
        id = "dvojka",
        name = "ČRo Dvojka",
        streamUrl = "https://rozhlas.stream/dvojka_mp3_128.mp3",
        backupUrl = "https://rozhlas.stream/dvojka_high.aac",
        faviconUrl = "https://portal.rozhlas.cz/sites/default/files/favicon_dvojka.png",
        qualityLabel = "128 kbps",
        variants = listOf(
            StreamVariant("64 kbps AAC", "https://rozhlas.stream/dvojka_low.aac", 64),
            StreamVariant("128 kbps MP3", "https://rozhlas.stream/dvojka_mp3_128.mp3", 128),
            StreamVariant("256 kbps MP3", "https://rozhlas.stream/dvojka_mp3_256.mp3", 256)
        )
    ),
    RadioStation(
        id = "plus",
        name = "ČRo Plus",
        streamUrl = "https://rozhlas.stream/plus_mp3_128.mp3",
        backupUrl = "https://rozhlas.stream/plus_high.aac",
        faviconUrl = "https://portal.rozhlas.cz/sites/default/files/favicon_plus.png",
        qualityLabel = "128 kbps",
        variants = listOf(
            StreamVariant("64 kbps AAC", "https://rozhlas.stream/plus_low.aac", 64),
            StreamVariant("128 kbps MP3", "https://rozhlas.stream/plus_mp3_128.mp3", 128)
        )
    ),
    RadioStation(
        id = "impuls",
        name = "Rádio Impuls",
        streamUrl = "https://icecast1.play.cz/impuls128.mp3",
        backupUrl = "http://icecast5.play.cz/impuls128.mp3",
        faviconUrl = "https://www.impuls.cz/apple-touch-icon.png",
        qualityLabel = "128 kbps",
        variants = listOf(
            StreamVariant("64 kbps", "https://icecast1.play.cz/impuls64.mp3", 64),
            StreamVariant("128 kbps", "https://icecast1.play.cz/impuls128.mp3", 128)
        )
    ),
    RadioStation(
        id = "cesky_impuls",
        name = "Český Impuls",
        streamUrl = "https://icecast6.play.cz/cesky-impuls.mp3",
        backupUrl = "http://icecast1.play.cz/cesky-impuls.mp3",
        faviconUrl = "https://www.impuls.cz/apple-touch-icon.png",
        qualityLabel = "128 kbps"
    ),
    RadioStation(
        id = "evropa2",
        name = "Evropa 2",
        streamUrl = "https://ice.actve.net/fm-evropa2-128",
        backupUrl = "http://ice.actve.net/fm-evropa2-128",
        faviconUrl = "https://www.evropa2.cz/apple-touch-icon.png",
        qualityLabel = "128 kbps"
    ),
    RadioStation(
        id = "frekvence1",
        name = "Frekvence 1",
        streamUrl = "https://ice.actve.net/fm-frekvence1-128",
        backupUrl = "http://ice.actve.net/fm-frekvence1-128",
        faviconUrl = "https://www.frekvence1.cz/apple-touch-icon.png",
        qualityLabel = "128 kbps"
    ),
    RadioStation(
        id = "blanik",
        name = "Rádio Blaník",
        streamUrl = "https://ice.abradio.cz/blanikfm128.mp3",
        backupUrl = "http://ice.abradio.cz/blanikfm128.mp3",
        faviconUrl = "https://radioblanik.cz/apple-touch-icon.png",
        qualityLabel = "128 kbps",
        variants = listOf(
            StreamVariant("64 kbps", "https://ice.abradio.cz/blanikfm64.mp3", 64),
            StreamVariant("128 kbps", "https://ice.abradio.cz/blanikfm128.mp3", 128)
        )
    ),
    RadioStation(
        id = "beat",
        name = "Radio Beat",
        streamUrl = "https://icecast2.play.cz/radiobeat128.mp3",
        backupUrl = "http://icecast5.play.cz/radiobeat128.mp3",
        faviconUrl = "https://www.radiobeat.cz/apple-touch-icon.png",
        qualityLabel = "128 kbps"
    ),
    RadioStation(
        id = "country",
        name = "Country Radio",
        streamUrl = "https://icecast4.play.cz/country128.mp3",
        backupUrl = "http://icecast2.play.cz/country128.mp3",
        faviconUrl = "https://www.countryradio.cz/apple-touch-icon.png",
        qualityLabel = "128 kbps"
    ),
    RadioStation(
        id = "kiss",
        name = "Rádio Kiss",
        streamUrl = "https://icecast4.play.cz/kiss128.mp3",
        backupUrl = "http://icecast1.play.cz/kiss128.mp3",
        faviconUrl = "https://www.kiss.cz/apple-touch-icon.png",
        qualityLabel = "128 kbps"
    ),
    RadioStation(
        id = "signal",
        name = "Signál Rádio",
        streamUrl = "https://icecast1.play.cz/signal128.mp3",
        backupUrl = "http://icecast6.play.cz/signal128.mp3",
        faviconUrl = "https://www.signalradio.cz/apple-touch-icon.png",
        qualityLabel = "128 kbps"
    ),
    RadioStation(
        id = "bonton",
        name = "Rádio Bonton",
        streamUrl = "https://ice.actve.net/fm-bonton-128",
        backupUrl = "http://ice.actve.net/fm-bonton-128",
        faviconUrl = "https://www.radiobonton.cz/apple-touch-icon.png",
        qualityLabel = "128 kbps"
    ),
    RadioStation(
        id = "fajn",
        name = "Fajn Radio",
        streamUrl = "https://ice.abradio.cz/fajn128.mp3",
        backupUrl = "http://ice.abradio.cz/fajn128.mp3",
        faviconUrl = "https://fajnradio.cz/apple-touch-icon.png",
        qualityLabel = "128 kbps"
    ),
    RadioStation(
        id = "rockzone",
        name = "RockZone 105,9",
        streamUrl = "https://icecast5.play.cz/rockzone128.mp3",
        backupUrl = "http://icecast1.play.cz/rockzone128.mp3",
        faviconUrl = "https://www.rockzone.cz/apple-touch-icon.png",
        qualityLabel = "128 kbps"
    ),
    RadioStation(
        id = "humor",
        name = "Rádio Humor",
        streamUrl = "https://ice4.abradio.cz/humor128.mp3",
        backupUrl = "http://mp3stream4.abradio.cz/humor128.mp3",
        faviconUrl = "https://www.abradio.cz/static/images/logo/humor.png",
        qualityLabel = "128 kbps",
        variants = listOf(
            StreamVariant("64 kbps", "https://ice4.abradio.cz/humor64.mp3", 64),
            StreamVariant("128 kbps", "https://ice4.abradio.cz/humor128.mp3", 128)
        )
    ),
    RadioStation(
        id = "povidka",
        name = "Rádio Povídka",
        streamUrl = "https://ice3.abradio.cz/povidka128.mp3",
        backupUrl = "http://mp3stream3.abradio.cz/povidka128.mp3",
        faviconUrl = "https://www.abradio.cz/static/images/logo/povidka.png",
        qualityLabel = "128 kbps"
    ),
    RadioStation(
        id = "pohadka",
        name = "Rádio Pohádka",
        streamUrl = "https://ice3.abradio.cz/pohadka128.mp3",
        backupUrl = "http://mp3stream3.abradio.cz/pohadka128.mp3",
        faviconUrl = "https://www.abradio.cz/static/images/logo/pohadka.png",
        qualityLabel = "128 kbps"
    )
)

expect fun decodeByteArrayToImageBitmap(bytes: ByteArray): ImageBitmap?

expect object RadioController {
    val isPlaying: StateFlow<Boolean>
    val statusMessage: StateFlow<String>
    val rdsText: StateFlow<String>
    val currentStation: StateFlow<RadioStation>
    val presets: StateFlow<List<RadioStation?>>
    val allStations: StateFlow<List<RadioStation>>
    val stationIcons: StateFlow<Map<String, ByteArray>>
    val isUpdatingStations: StateFlow<Boolean>
    val sleepTimerRemainingMinutes: StateFlow<Int>
    val isPreAlarmEnabled: StateFlow<Boolean>
    val preAlarmLabel: StateFlow<String>
    val volumePercent: StateFlow<Int>
    val isWatchStationListOpen: StateFlow<Boolean>
    val watchListScrollEvent: StateFlow<Pair<Long, Float>>

    fun togglePlayback()
    fun playNextStation()
    fun playPreviousStation()
    fun adjustVolume(direction: Int)
    fun setWatchStationListOpen(open: Boolean)
    fun onRotaryScroll(delta: Float)
    fun selectAndPlayStation(station: RadioStation)
    fun selectStationVariant(station: RadioStation, variant: StreamVariant)
    fun saveCurrentToPreset(index: Int)
    fun saveStationToPreset(index: Int, station: RadioStation?)
    fun refreshOnlineStations()
    fun requestStationIcon(url: String)
    fun cycleSleepTimer()
    fun toggleOneShotPreAlarm()
}
