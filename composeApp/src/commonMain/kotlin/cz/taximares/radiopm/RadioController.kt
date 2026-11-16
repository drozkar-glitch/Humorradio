package cz.taximares.radiopm

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow

enum class PlaybackMode {
    RADIO,
    AUDIOBOOK
}

data class AudiobookItem(
    val id: String,
    val title: String,
    val groupTitle: String = "",
    val sourceUri: String,
    val zipEntryPath: String = "",
    val extension: String = "mp3",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlayed: Boolean = false,
    val coverKey: String = ""
)

data class AudiobookGroup(
    val key: String,
    val title: String,
    val chapters: List<AudiobookItem>,
    val coverKey: String,
    val representativeItem: AudiobookItem,
    val playedChaptersCount: Int,
    val isAllPlayed: Boolean,
    val hasProgress: Boolean,
    val resumeChapter: AudiobookItem
)

fun AudiobookItem.effectiveBookKey(): String {
    val g = groupTitle.trim()
    return if (g.isNotEmpty()) {
        "book_${g.lowercase()}"
    } else {
        "single_$id"
    }
}

fun AudiobookItem.effectiveBookTitle(): String {
    val g = groupTitle.trim()
    return if (g.isNotEmpty()) g else title.trim().ifEmpty { "Audiokniha" }
}

fun AudiobookItem.effectiveCoverKey(): String {
    return coverKey.ifBlank { effectiveBookKey() }
}

fun groupAudiobooksIntoBooks(
    items: List<AudiobookItem>,
    currentAudiobookId: String? = null
): List<AudiobookGroup> {
    if (items.isEmpty()) return emptyList()
    val buckets = LinkedHashMap<String, MutableList<AudiobookItem>>()
    for (item in items) {
        val key = item.effectiveBookKey()
        buckets.getOrPut(key) { mutableListOf() }.add(item)
    }
    val groups = mutableListOf<AudiobookGroup>()
    for ((key, rawChapters) in buckets) {
        val chapters = if (rawChapters.size > 1) {
            rawChapters.sortedWith { a, b ->
                val pCmp = naturalCompareStrings(a.zipEntryPath, b.zipEntryPath)
                if (a.zipEntryPath.isNotEmpty() && b.zipEntryPath.isNotEmpty() && pCmp != 0) {
                    pCmp
                } else {
                    naturalCompareStrings(a.title, b.title)
                }
            }
        } else {
            rawChapters
        }
        val first = chapters.first()
        val playedCount = chapters.count { it.isPlayed }
        val allPlayed = playedCount == chapters.size
        val activeMatch = if (currentAudiobookId != null) {
            chapters.find { it.id == currentAudiobookId }
        } else {
            null
        }
        val inProgressMatch = chapters.firstOrNull { !it.isPlayed && it.positionMs > 0L }
        val firstUnplayed = chapters.firstOrNull { !it.isPlayed }
        val resume = activeMatch ?: inProgressMatch ?: firstUnplayed ?: first
        val anyProgress = playedCount > 0 || chapters.any { it.positionMs > 0L }
        val covKey = chapters.firstOrNull { it.coverKey.isNotBlank() }?.coverKey ?: first.effectiveCoverKey()

        groups.add(
            AudiobookGroup(
                key = key,
                title = first.effectiveBookTitle(),
                chapters = chapters,
                coverKey = covKey,
                representativeItem = first,
                playedChaptersCount = playedCount,
                isAllPlayed = allPlayed,
                hasProgress = anyProgress,
                resumeChapter = resume
            )
        )
    }
    return groups
}

val SUPPORTED_AUDIO_EXTENSIONS: Set<String> = setOf(
    "mp3", "m4b", "m4a", "aac", "mp4", "ogg", "oga",
    "opus", "flac", "wav", "amr", "3gp", "webm", "mkv"
)

private val SUPPORTED_IMAGE_EXTENSIONS: Set<String> = setOf(
    "jpg", "jpeg", "png", "webp"
)

fun isSupportedAudioFileName(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase().trim()
    return ext in SUPPORTED_AUDIO_EXTENSIONS
}

fun isSupportedImageFileName(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase().trim()
    return ext in SUPPORTED_IMAGE_EXTENSIONS
}

fun formatAudioDuration(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L)) / 1000L
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    val mm = minutes.toString().padStart(2, '0')
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0L) {
        "$hours:$mm:$ss"
    } else {
        "$mm:$ss"
    }
}

fun naturalCompareStrings(a: String, b: String): Int {
    val s1 = a.lowercase()
    val s2 = b.lowercase()
    var i1 = 0
    var i2 = 0
    while (i1 < s1.length && i2 < s2.length) {
        val c1 = s1[i1]
        val c2 = s2[i2]
        if (c1.isDigit() && c2.isDigit()) {
            var start1 = i1
            while (start1 < s1.length && s1[start1] == '0') start1++
            var end1 = start1
            while (end1 < s1.length && s1[end1].isDigit()) end1++

            var start2 = i2
            while (start2 < s2.length && s2[start2] == '0') start2++
            var end2 = start2
            while (end2 < s2.length && s2[end2].isDigit()) end2++

            val lenDiff = (end1 - start1) - (end2 - start2)
            if (lenDiff != 0) return lenDiff

            var k1 = start1
            var k2 = start2
            while (k1 < end1 && k2 < end2) {
                if (s1[k1] != s2[k2]) return s1[k1].compareTo(s2[k2])
                k1++
                k2++
            }
            val zeroDiff = (start1 - i1) - (start2 - i2)
            if (zeroDiff != 0 && end1 == s1.length && end2 == s2.length) return zeroDiff
            i1 = end1
            i2 = end2
        } else {
            if (c1 != c2) return c1.compareTo(c2)
            i1++
            i2++
        }
    }
    return s1.length.compareTo(s2.length)
}

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
    val stationIcons: StateFlow<kotlin.collections.Map<String, ByteArray>>
    val isUpdatingStations: StateFlow<Boolean>
    val sleepTimerRemainingMinutes: StateFlow<Int>
    val isPreAlarmEnabled: StateFlow<Boolean>
    val preAlarmLabel: StateFlow<String>
    val volumePercent: StateFlow<Int>
    val isWatchStationListOpen: StateFlow<Boolean>
    val watchListScrollEvent: StateFlow<Pair<Long, Float>>

    val playbackMode: StateFlow<PlaybackMode>
    val audiobooks: StateFlow<List<AudiobookItem>>
    val currentAudiobook: StateFlow<AudiobookItem?>
    val audiobookCovers: StateFlow<kotlin.collections.Map<String, ByteArray>>
    val audiobookPositionMs: StateFlow<Long>
    val audiobookDurationMs: StateFlow<Long>
    val isImportingAudiobooks: StateFlow<Boolean>

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

    fun pickAudiobookFiles()
    fun pickAudiobookFolder()
    fun selectAndPlayAudiobook(item: AudiobookItem)
    fun requestAudiobookCover(item: AudiobookItem)
    fun seekAudiobookTo(positionMs: Long)
    fun seekAudiobookRelative(deltaMs: Long)
    fun toggleAudiobookPlayed(itemId: String)
    fun toggleAudiobookGroupPlayed(groupKey: String)
    fun removeAudiobook(itemId: String)
    fun removeAudiobookGroup(groupKey: String)
    fun removePlayedAudiobooks()
}
