package cz.taximares.radiopm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

actual fun decodeByteArrayToImageBitmap(bytes: ByteArray): ImageBitmap? {
    return try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

actual object RadioController {
    private const val PREFS_NAME = "radiopm_prefs"
    private const val KEY_PRESETS_V4 = "saved_presets_v4_empty"
    private const val KEY_LAST_STATION = "last_station_id"
    private const val KEY_CACHED_STATIONS = "cached_stations_json_v8"
    private const val KEY_PRE_ALARM_ENABLED = "pre_alarm_enabled"
    private const val KEY_PRE_ALARM_TIME = "pre_alarm_target_ms"

    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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

    private val _presets = MutableStateFlow<List<RadioStation?>>(List(4) { null })
    actual val presets: StateFlow<List<RadioStation?>> = _presets.asStateFlow()

    private val _stationIcons = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    actual val stationIcons: StateFlow<Map<String, ByteArray>> = _stationIcons.asStateFlow()
    private val loadingIcons = mutableSetOf<String>()

    private val _isUpdatingStations = MutableStateFlow(false)
    actual val isUpdatingStations: StateFlow<Boolean> = _isUpdatingStations.asStateFlow()

    private val _sleepTimerRemainingMinutes = MutableStateFlow(0)
    actual val sleepTimerRemainingMinutes: StateFlow<Int> = _sleepTimerRemainingMinutes.asStateFlow()

    private val _isPreAlarmEnabled = MutableStateFlow(false)
    actual val isPreAlarmEnabled: StateFlow<Boolean> = _isPreAlarmEnabled.asStateFlow()

    private val _preAlarmLabel = MutableStateFlow("Předbuzení vypnuto")
    actual val preAlarmLabel: StateFlow<String> = _preAlarmLabel.asStateFlow()

    private val _volumePercent = MutableStateFlow(70)
    actual val volumePercent: StateFlow<Int> = _volumePercent.asStateFlow()

    private val _isWatchStationListOpen = MutableStateFlow(false)
    actual val isWatchStationListOpen: StateFlow<Boolean> = _isWatchStationListOpen.asStateFlow()

    private val _watchListScrollEvent = MutableStateFlow(0L to 0f)
    actual val watchListScrollEvent: StateFlow<Pair<Long, Float>> = _watchListScrollEvent.asStateFlow()

    private var sleepTimerRunnable: Runnable? = null

    fun init(context: Context) {
        if (appContext != null) {
            syncVolumePercent()
            return
        }
        appContext = context.applicationContext
        loadCachedStations()
        loadPresetsAndLastStation()
        restorePreAlarmState()
        syncVolumePercent()
        refreshOnlineStations()
    }

    fun syncVolumePercent() {
        val ctx = appContext ?: return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            _volumePercent.value = ((cur.toFloat() / max.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        } catch (_: Exception) {
        }
    }

    actual fun setWatchStationListOpen(open: Boolean) {
        _isWatchStationListOpen.value = open
    }

    actual fun onRotaryScroll(delta: Float) {
        if (_isWatchStationListOpen.value) {
            triggerHapticTick()
            _watchListScrollEvent.value = System.currentTimeMillis() to delta
        } else {
            val dir = if (delta > 0f) 1 else if (delta < 0f) -1 else 0
            if (dir != 0) {
                adjustVolume(dir)
            }
        }
    }

    actual fun adjustVolume(direction: Int) {
        val ctx = appContext ?: return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val next = (cur + direction).coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
            _volumePercent.value = ((next.toFloat() / max.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
            triggerHapticTick()
        } catch (_: Exception) {
        }
    }

    private fun triggerHapticTick() {
        val ctx = appContext ?: return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(12L)
            }
        } catch (_: Exception) {
        }
    }

    fun updateState(playing: Boolean, message: String) {
        _isPlaying.value = playing
        _statusMessage.value = message
    }

    fun updateRds(rds: String) {
        _rdsText.value = rds
    }

    actual fun togglePlayback() {
        val ctx = appContext ?: return
        if (_isPlaying.value) {
            val stopIntent = Intent(ctx, RadioService::class.java).apply {
                action = RadioService.ACTION_STOP
            }
            ctx.startService(stopIntent)
        } else {
            startStationService(_currentStation.value, fadeIn = false)
        }
    }

    actual fun playNextStation() {
        val favoriteStations = _presets.value.filterNotNull()
        val listToCycle = if (favoriteStations.isNotEmpty()) favoriteStations else _allStations.value
        if (listToCycle.isEmpty()) return

        val cur = _currentStation.value
        val idx = listToCycle.indexOfFirst { it.id == cur.id || it.name.equals(cur.name, ignoreCase = true) }
        val nextStation = if (idx in listToCycle.indices) {
            listToCycle[(idx + 1) % listToCycle.size]
        } else {
            listToCycle.first()
        }
        selectAndPlayStation(nextStation)
    }

    actual fun playPreviousStation() {
        val favoriteStations = _presets.value.filterNotNull()
        val listToCycle = if (favoriteStations.isNotEmpty()) favoriteStations else _allStations.value
        if (listToCycle.isEmpty()) return

        val cur = _currentStation.value
        val idx = listToCycle.indexOfFirst { it.id == cur.id || it.name.equals(cur.name, ignoreCase = true) }
        val prevStation = if (idx in listToCycle.indices) {
            listToCycle[(idx - 1 + listToCycle.size) % listToCycle.size]
        } else {
            listToCycle.last()
        }
        selectAndPlayStation(prevStation)
    }

    actual fun selectAndPlayStation(station: RadioStation) {
        _currentStation.value = station
        saveLastStation(station.id)
        if (station.faviconUrl.isNotBlank()) {
            requestStationIcon(station.faviconUrl)
        }
        startStationService(station, fadeIn = false)
    }

    actual fun selectStationVariant(station: RadioStation, variant: StreamVariant) {
        val updated = station.copy(
            streamUrl = variant.url,
            qualityLabel = variant.label
        )
        _allStations.value = _allStations.value.map {
            if (it.id == station.id) updated else it
        }
        _presets.value = _presets.value.map {
            if (it?.id == station.id) updated else it
        }
        savePresetsToPrefs()
        selectAndPlayStation(updated)
    }

    private fun startStationService(station: RadioStation, fadeIn: Boolean) {
        val ctx = appContext ?: return
        val playIntent = Intent(ctx, RadioService::class.java).apply {
            action = if (fadeIn) RadioService.ACTION_PLAY_FADE_IN else RadioService.ACTION_PLAY
            putExtra(RadioService.EXTRA_STATION_NAME, station.name)
            putExtra(RadioService.EXTRA_STREAM_URL, station.streamUrl)
            putExtra(RadioService.EXTRA_BACKUP_URL, station.backupUrl)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(playIntent)
        } else {
            ctx.startService(playIntent)
        }
    }

    actual fun saveCurrentToPreset(index: Int) {
        saveStationToPreset(index, _currentStation.value)
    }

    actual fun saveStationToPreset(index: Int, station: RadioStation?) {
        if (index !in 0..3) return
        val updated = _presets.value.toMutableList()
        updated[index] = station
        _presets.value = updated
        savePresetsToPrefs()
    }

    actual fun requestStationIcon(url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        if (_stationIcons.value.containsKey(cleanUrl)) return
        synchronized(loadingIcons) {
            if (!loadingIcons.add(cleanUrl)) return
        }
        thread {
            try {
                val conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "RadioPM/1.0")
                }
                if (conn.responseCode in 200..299) {
                    val out = ByteArrayOutputStream()
                    conn.inputStream.use { input ->
                        val buf = ByteArray(4096)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            if (out.size() > 512 * 1024) break
                        }
                    }
                    val bytes = out.toByteArray()
                    if (bytes.isNotEmpty()) {
                        mainHandler.post {
                            _stationIcons.value = _stationIcons.value + (cleanUrl to bytes)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                synchronized(loadingIcons) {
                    loadingIcons.remove(cleanUrl)
                }
            }
        }
    }

    actual fun refreshOnlineStations() {
        if (_isUpdatingStations.value) return
        _isUpdatingStations.value = true
        thread {
            val hosts = listOf(
                "de1.api.radio-browser.info",
                "at1.api.radio-browser.info",
                "nl1.api.radio-browser.info"
            )
            var fetched: List<RadioStation> = emptyList()
            for (host in hosts) {
                try {
                    val apiUrl = "https://$host/json/stations/bycountrycodeexact/CZ?hidebroken=true&order=clickcount&reverse=true&limit=1500"
                    val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("User-Agent", "RadioPM/1.0")
                    }
                    if (conn.responseCode == 200) {
                        val sb = StringBuilder()
                        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                            var line: String?
                            while (r.readLine().also { line = it } != null) {
                                sb.append(line)
                            }
                        }
                        fetched = parseRadioBrowserJson(sb.toString())
                        if (fetched.isNotEmpty()) break
                    }
                } catch (_: Exception) {
                }
            }

            mainHandler.post {
                if (fetched.isNotEmpty()) {
                    val merged = mergeStationsWithDefaults(fetched)
                    _allStations.value = merged
                    saveStationsToCache(merged)
                }
                _isUpdatingStations.value = false
            }
        }
    }

    private fun parseRadioBrowserJson(rawJson: String): List<RadioStation> {
        val arr = JSONArray(rawJson)
        val grouped = LinkedHashMap<String, MutableList<Triple<String, Int, String>>>()

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val name = obj.optString("name", "").trim()
            val urlResolved = obj.optString("url_resolved", "").trim()
            val urlOrig = obj.optString("url", "").trim()
            val streamUrl = if (urlResolved.isNotEmpty()) urlResolved else urlOrig
            val codec = obj.optString("codec", "").uppercase(Locale.ROOT)
            val bitrate = obj.optInt("bitrate", 128).let { if (it <= 0) 128 else it }
            val favicon = obj.optString("favicon", "").trim()

            if (name.isEmpty() || streamUrl.isEmpty()) continue
            if (codec.contains("UNKNOWN") || streamUrl.endsWith(".asx", ignoreCase = true) || streamUrl.endsWith(".wma", ignoreCase = true)) {
                continue
            }

            val cleanName = cleanStationGroupName(name)
            val list = grouped.getOrPut(cleanName) { mutableListOf() }
            list.add(Triple(streamUrl, bitrate, favicon))
        }

        val result = mutableListOf<RadioStation>()
        var stationCounter = 0
        for ((name, streams) in grouped) {
            stationCounter++
            val uniqueStreams = streams.distinctBy { it.first }
            val sorted = uniqueStreams.sortedByDescending { it.second }
            val primary = sorted.first()
            val backup = sorted.getOrNull(1)?.first ?: ""
            val icon = sorted.firstOrNull { it.third.isNotEmpty() }?.third ?: ""

            val variants = sorted.mapIndexed { idx, triple ->
                val br = triple.second
                val label = if (sorted.count { it.second == br } > 1) {
                    "${br} kbps (#${idx + 1})"
                } else {
                    "${br} kbps"
                }
                StreamVariant(label = label, url = triple.first, bitrate = br)
            }

            val slug = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
            val id = "online_${stationCounter}_${slug.ifEmpty { "st" }}"
            result.add(
                RadioStation(
                    id = id,
                    name = name,
                    streamUrl = primary.first,
                    backupUrl = backup,
                    faviconUrl = icon,
                    qualityLabel = "${primary.second} kbps",
                    variants = variants
                )
            )
        }
        return result
    }

    private fun cleanStationGroupName(rawName: String): String {
        return rawName
            .replace(Regex("\\s*\\(\\d+\\s*kbps\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*\\d+\\s*kbps$", RegexOption.IGNORE_CASE), "")
            .trim()
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
            if (i !in usedOnlineIndices) {
                result.add(online[i])
            }
        }
        return result
    }

    private fun saveStationsToCache(stations: List<RadioStation>) {
        val ctx = appContext ?: return
        try {
            val arr = JSONArray()
            for (st in stations) {
                val obj = JSONObject()
                obj.put("id", st.id)
                obj.put("name", st.name)
                obj.put("streamUrl", st.streamUrl)
                obj.put("backupUrl", st.backupUrl)
                obj.put("faviconUrl", st.faviconUrl)
                obj.put("qualityLabel", st.qualityLabel)
                val vArr = JSONArray()
                for (v in st.variants) {
                    val vObj = JSONObject()
                    vObj.put("label", v.label)
                    vObj.put("url", v.url)
                    vObj.put("bitrate", v.bitrate)
                    vArr.put(vObj)
                }
                obj.put("variants", vArr)
                arr.put(obj)
            }
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CACHED_STATIONS, arr.toString())
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun loadCachedStations() {
        val ctx = appContext ?: return
        try {
            val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CACHED_STATIONS, null) ?: return
            val arr = JSONArray(raw)
            val loaded = mutableListOf<RadioStation>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val vArr = obj.optJSONArray("variants")
                val variants = mutableListOf<StreamVariant>()
                if (vArr != null) {
                    for (j in 0 until vArr.length()) {
                        val vObj = vArr.getJSONObject(j)
                        variants.add(
                            StreamVariant(
                                label = vObj.optString("label", "128 kbps"),
                                url = vObj.optString("url", ""),
                                bitrate = vObj.optInt("bitrate", 128)
                            )
                        )
                    }
                }
                val rawId = obj.optString("id").ifBlank { "cached_$i" }
                loaded.add(
                    RadioStation(
                        id = "${rawId}_$i",
                        name = obj.optString("name"),
                        streamUrl = obj.optString("streamUrl"),
                        backupUrl = obj.optString("backupUrl"),
                        faviconUrl = obj.optString("faviconUrl"),
                        qualityLabel = obj.optString("qualityLabel", "128 kbps"),
                        variants = variants.ifEmpty {
                            listOf(StreamVariant(obj.optString("qualityLabel", "128 kbps"), obj.optString("streamUrl")))
                        }
                    )
                )
            }
            if (loaded.isNotEmpty()) {
                _allStations.value = mergeStationsWithDefaults(loaded)
            }
        } catch (_: Exception) {
        }
    }

    private fun savePresetsToPrefs() {
        val ctx = appContext ?: return
        try {
            val arr = JSONArray()
            for (st in _presets.value.take(4)) {
                if (st == null) {
                    arr.put(JSONObject.NULL)
                } else {
                    val obj = JSONObject()
                    obj.put("id", st.id)
                    obj.put("name", st.name)
                    obj.put("streamUrl", st.streamUrl)
                    obj.put("backupUrl", st.backupUrl)
                    obj.put("faviconUrl", st.faviconUrl)
                    obj.put("qualityLabel", st.qualityLabel)
                    arr.put(obj)
                }
            }
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PRESETS_V4, arr.toString())
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun saveLastStation(stationId: String) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_STATION, stationId)
            .apply()
    }

    private fun loadPresetsAndLastStation() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawPresets = prefs.getString(KEY_PRESETS_V4, null)
        if (rawPresets != null) {
            try {
                val arr = JSONArray(rawPresets)
                val list = MutableList<RadioStation?>(4) { null }
                for (i in 0 until minOf(4, arr.length())) {
                    if (!arr.isNull(i)) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("id")
                        val name = obj.optString("name")
                        val fromAll = _allStations.value.find { it.id == id || it.name.equals(name, ignoreCase = true) }
                        list[i] = fromAll ?: RadioStation(
                            id = id.ifBlank { "preset_$i" },
                            name = name,
                            streamUrl = obj.optString("streamUrl"),
                            backupUrl = obj.optString("backupUrl"),
                            faviconUrl = obj.optString("faviconUrl"),
                            qualityLabel = obj.optString("qualityLabel", "128 kbps")
                        )
                    }
                }
                _presets.value = list
            } catch (_: Exception) {
                _presets.value = List(4) { null }
            }
        } else {
            _presets.value = List(4) { null }
        }

        val lastId = prefs.getString(KEY_LAST_STATION, null)
        if (lastId != null) {
            _allStations.value.find { it.id == lastId }?.let {
                _currentStation.value = it
            }
        }
    }

    actual fun cycleSleepTimer() {
        val options = listOf(0, 15, 30, 45, 60, 90)
        val cur = _sleepTimerRemainingMinutes.value
        val next = options.firstOrNull { it > cur } ?: 0
        setSleepTimerMinutes(next)
    }

    private fun setSleepTimerMinutes(minutes: Int) {
        sleepTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        _sleepTimerRemainingMinutes.value = minutes
        if (minutes <= 0) return

        val tick = object : Runnable {
            override fun run() {
                val rem = _sleepTimerRemainingMinutes.value - 1
                if (rem <= 0) {
                    _sleepTimerRemainingMinutes.value = 0
                    if (_isPlaying.value) {
                        togglePlayback()
                    }
                } else {
                    _sleepTimerRemainingMinutes.value = rem
                    mainHandler.postDelayed(this, 60_000L)
                }
            }
        }
        sleepTimerRunnable = tick
        mainHandler.postDelayed(tick, 60_000L)
    }

    actual fun toggleOneShotPreAlarm() {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (_isPreAlarmEnabled.value) {
            cancelPreAlarm(ctx, am)
            return
        }

        val nextAlarmClock = am.nextAlarmClock
        if (nextAlarmClock == null) {
            _isPreAlarmEnabled.value = false
            _preAlarmLabel.value = "V systému není zapnutý budík"
            try {
                val showAlarms = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                ctx.startActivity(showAlarms)
            } catch (_: Exception) {
            }
            return
        }

        val alarmTimeMs = nextAlarmClock.triggerTime
        val preAlarmTriggerMs = (alarmTimeMs - 30 * 60 * 1000L).coerceAtLeast(System.currentTimeMillis() + 5000L)

        val pi = buildPreAlarmPendingIntent(ctx)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preAlarmTriggerMs, pi)

        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val preTimeStr = sdf.format(Date(preAlarmTriggerMs))
        val alarmTimeStr = sdf.format(Date(alarmTimeMs))

        _isPreAlarmEnabled.value = true
        _preAlarmLabel.value = "Rádio v $preTimeStr (budík $alarmTimeStr)"

        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PRE_ALARM_ENABLED, true)
            .putLong(KEY_PRE_ALARM_TIME, preAlarmTriggerMs)
            .apply()
    }

    private fun cancelPreAlarm(ctx: Context, am: AlarmManager) {
        am.cancel(buildPreAlarmPendingIntent(ctx))
        _isPreAlarmEnabled.value = false
        _preAlarmLabel.value = "Předbuzení vypnuto"
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PRE_ALARM_ENABLED, false)
            .remove(KEY_PRE_ALARM_TIME)
            .apply()
    }

    private fun restorePreAlarmState() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_PRE_ALARM_ENABLED, false)
        val targetMs = prefs.getLong(KEY_PRE_ALARM_TIME, 0L)
        if (enabled && targetMs > System.currentTimeMillis()) {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            _isPreAlarmEnabled.value = true
            _preAlarmLabel.value = "Rádio zapne v ${sdf.format(Date(targetMs))}"
        } else {
            _isPreAlarmEnabled.value = false
            _preAlarmLabel.value = "Předbuzení vypnuto"
        }
    }

    fun onPreAlarmTriggered(ctx: Context) {
        _isPreAlarmEnabled.value = false
        _preAlarmLabel.value = "Předbuzení proběhlo"
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PRE_ALARM_ENABLED, false)
            .remove(KEY_PRE_ALARM_TIME)
            .apply()
        startStationService(_currentStation.value, fadeIn = true)
    }

    private fun buildPreAlarmPendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, WakeAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            ctx,
            7701,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
