package cz.taximares.radiopm

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val StopRed = Color(0xFFE53935)
private val PlayGreen = Color(0xFF00C853)

private data class LiquidThemePalette(
    val isDark: Boolean,
    val bgGradient: List<Color>,
    val drawerBg: Color,
    val dialogBg: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val goldAccent: Color,
    val cyanAccent: Color,
    val cardBg: Color,
    val cardBorder: Color,
    val activeCardBg: Color,
    val rdsBoxBg: Color
)

private fun getPalette(isDark: Boolean): LiquidThemePalette {
    return if (isDark) {
        LiquidThemePalette(
            isDark = true,
            bgGradient = listOf(Color(0xFF07131E), Color(0xFF0C2233), Color(0xFF050E17)),
            drawerBg = Color(0xFF0A1926),
            dialogBg = Color(0xFF0E2233),
            primaryText = Color.White,
            secondaryText = Color.White.copy(alpha = 0.68f),
            goldAccent = Color(0xFFFFD54F),
            cyanAccent = Color(0xFF4DD0E1),
            cardBg = Color.White.copy(alpha = 0.07f),
            cardBorder = Color.White.copy(alpha = 0.18f),
            activeCardBg = Color(0xFFFFD54F).copy(alpha = 0.18f),
            rdsBoxBg = Color.Black.copy(alpha = 0.38f)
        )
    } else {
        LiquidThemePalette(
            isDark = false,
            bgGradient = listOf(Color(0xFFEAF4FC), Color(0xFFD6E9F8), Color(0xFFC5E0F5)),
            drawerBg = Color(0xFFF2F8FD),
            dialogBg = Color(0xFFFFFFFF),
            primaryText = Color(0xFF0A1E2E),
            secondaryText = Color(0xFF33526B),
            goldAccent = Color(0xFFB26A00),
            cyanAccent = Color(0xFF007791),
            cardBg = Color.White.copy(alpha = 0.72f),
            cardBorder = Color(0xFF0A1E2E).copy(alpha = 0.15f),
            activeCardBg = Color(0xFFFFD54F).copy(alpha = 0.35f),
            rdsBoxBg = Color.White.copy(alpha = 0.85f)
        )
    }
}

private fun filterSongRds(isPlaying: Boolean, rawRds: String, stationName: String): String {
    if (!isPlaying) return ""
    val clean = rawRds.trim()
    if (clean.isEmpty()) return ""
    if (clean.equals("Vyber stanici a stiskni Play", ignoreCase = true)) return ""
    if (clean.equals("Živé vysílání", ignoreCase = true)) return ""
    if (clean.endsWith("– živé vysílání", ignoreCase = true)) return ""
    if (clean.endsWith("- živé vysílání", ignoreCase = true)) return ""
    if (clean.equals(stationName.trim(), ignoreCase = true)) return ""
    return clean
}

@Composable
fun App() {
    val isDark = isSystemInDarkTheme()
    val palette = remember(isDark) { getPalette(isDark) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = palette.bgGradient))
    ) {
        val isWatchScreen = maxWidth < 300.dp || maxHeight < 300.dp
        if (isWatchScreen) {
            WatchRadioScreen()
        } else {
            LaunchedEffect(Unit) {
                RadioController.setWatchStationListOpen(false)
            }
            PhoneRadioScreen(palette = palette)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WatchRadioScreen() {
    val isPlaying by RadioController.isPlaying.collectAsState()
    val currentStation by RadioController.currentStation.collectAsState()
    val rdsText by RadioController.rdsText.collectAsState()
    val allStations by RadioController.allStations.collectAsState()
    val presets by RadioController.presets.collectAsState()
    val stationIcons by RadioController.stationIcons.collectAsState()
    val volumePercent by RadioController.volumePercent.collectAsState()
    val isListOpen by RadioController.isWatchStationListOpen.collectAsState()
    val scrollEvent by RadioController.watchListScrollEvent.collectAsState()

    val goldAccent = Color(0xFFFFD54F)
    val cyanAccent = Color(0xFF4DD0E1)
    val displayRds = remember(isPlaying, rdsText, currentStation.name) {
        filterSongRds(isPlaying, rdsText, currentStation.name)
    }

    LaunchedEffect(currentStation.faviconUrl) {
        if (currentStation.faviconUrl.isNotBlank()) {
            RadioController.requestStationIcon(currentStation.faviconUrl)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF060D14)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            val strokeWidth = 5.dp.toPx()
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)

            drawArc(
                color = Color.White.copy(alpha = 0.10f),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            val sweep = (volumePercent.coerceIn(0, 100) / 100f) * 270f
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(cyanAccent, goldAccent, cyanAccent)
                ),
                startAngle = 135f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        if (!isListOpen) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Hlasitost $volumePercent %",
                        color = cyanAccent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = currentStation.name,
                        color = goldAccent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = displayRds.ifEmpty { " " },
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (displayRds.isNotEmpty()) {
                                    Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                                } else {
                                    Modifier
                                }
                            )
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable { RadioController.playPreviousStation() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⏮", color = Color.White, fontSize = 15.sp)
                    }

                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(if (isPlaying) StopRed else PlayGreen)
                            .border(2.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                            .clickable { RadioController.togglePlayback() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isPlaying) "■" else "▶",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable { RadioController.playNextStation() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⏭", color = Color.White, fontSize = 15.sp)
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.14f))
                        .border(1.dp, goldAccent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .clickable { RadioController.setWatchStationListOpen(true) }
                        .padding(horizontal = 14.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Stanice (${allStations.size})",
                        color = goldAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        } else {
            val listState = rememberLazyListState()
            val favoriteStations = remember(presets) { presets.filterNotNull() }
            val combinedList = remember(favoriteStations, allStations) {
                (favoriteStations + allStations).distinctBy { it.name }
            }

            LaunchedEffect(scrollEvent.first) {
                if (scrollEvent.first != 0L) {
                    listState.scrollBy(scrollEvent.second * 95f)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                        .clickable { RadioController.setWatchStationListOpen(false) }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "← Zpět na přehrávač",
                        color = cyanAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(combinedList.size) { idx ->
                        val station = combinedList[idx]
                        val selected = station.id == currentStation.id || station.name == currentStation.name
                        val isFav = favoriteStations.any { it.name == station.name }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (selected) goldAccent.copy(alpha = 0.24f)
                                    else Color.White.copy(alpha = 0.08f)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (selected) goldAccent else Color.White.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    RadioController.selectAndPlayStation(station)
                                    RadioController.setWatchStationListOpen(false)
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StationLogo(
                                station = station,
                                size = 24.dp,
                                isSelected = selected,
                                iconBytes = stationIcons[station.faviconUrl]
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isFav) "★ ${station.name}" else station.name,
                                color = if (selected) goldAccent else Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneRadioScreen(
    palette: LiquidThemePalette
) {
    val isPlaying by RadioController.isPlaying.collectAsState()
    val statusMessage by RadioController.statusMessage.collectAsState()
    val rdsText by RadioController.rdsText.collectAsState()
    val currentStation by RadioController.currentStation.collectAsState()
    val presets by RadioController.presets.collectAsState()
    val allStations by RadioController.allStations.collectAsState()
    val stationIcons by RadioController.stationIcons.collectAsState()
    val isUpdatingStations by RadioController.isUpdatingStations.collectAsState()
    val sleepMinutes by RadioController.sleepTimerRemainingMinutes.collectAsState()
    val isPreAlarmEnabled by RadioController.isPreAlarmEnabled.collectAsState()
    val preAlarmLabel by RadioController.preAlarmLabel.collectAsState()

    val displayRds = remember(isPlaying, rdsText, currentStation.name) {
        filterSongRds(isPlaying, rdsText, currentStation.name)
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var stationForPresetDialog by remember { mutableStateOf<RadioStation?>(null) }
    var stationForQualityDialog by remember { mutableStateOf<RadioStation?>(null) }

    LaunchedEffect(currentStation.faviconUrl) {
        if (currentStation.faviconUrl.isNotBlank()) {
            RadioController.requestStationIcon(currentStation.faviconUrl)
        }
    }

    val filteredStations = remember(allStations, searchQuery) {
        val trimmed = searchQuery.trim()
        if (trimmed.isEmpty()) {
            allStations
        } else {
            val q = normalizeSearchText(trimmed)
            allStations.filter { st ->
                normalizeSearchText(st.name).contains(q)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = palette.drawerBg,
                drawerContentColor = palette.primaryText,
                modifier = Modifier.width(330.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .systemBarsPadding()
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Katalog českých rádií",
                                color = palette.goldAccent,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isUpdatingStations) "Aktualizuji seznam..." else "${allStations.size} stanic v databázi",
                                color = palette.secondaryText,
                                fontSize = 12.sp
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.cardBg)
                                .border(1.dp, palette.cardBorder, RoundedCornerShape(10.dp))
                                .clickable { RadioController.refreshOnlineStations() }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "↻ Obnovit",
                                color = palette.cyanAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Hledat stanici (např. Rádio B, Impuls...)", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = palette.primaryText,
                            unfocusedTextColor = palette.primaryText,
                            focusedBorderColor = palette.goldAccent,
                            unfocusedBorderColor = palette.cardBorder,
                            focusedPlaceholderColor = palette.secondaryText,
                            unfocusedPlaceholderColor = palette.secondaryText
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    if (filteredStations.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Žádná stanice neodpovídá hledání „$searchQuery“",
                                color = palette.secondaryText,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 20.dp)
                        ) {
                            items(filteredStations.size) { index ->
                                val station = filteredStations[index]
                                val isSelected = station.id == currentStation.id || station.name == currentStation.name

                                GlassCardBox(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            RadioController.selectAndPlayStation(station)
                                            scope.launch { drawerState.close() }
                                        },
                                    borderColor = if (isSelected) palette.goldAccent else palette.cardBorder,
                                    backgroundColor = if (isSelected) palette.activeCardBg else palette.cardBg
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        StationLogo(
                                            station = station,
                                            size = 42.dp,
                                            isSelected = isSelected,
                                            iconBytes = stationIcons[station.faviconUrl]
                                        )

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = station.name,
                                                color = if (isSelected) palette.goldAccent else palette.primaryText,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "Kvalita: ${station.qualityLabel}",
                                                color = palette.secondaryText,
                                                fontSize = 11.sp,
                                                maxLines = 1
                                            )
                                        }

                                        if (station.variants.size > 1) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(palette.cyanAccent.copy(alpha = 0.16f))
                                                    .clickable { stationForQualityDialog = station }
                                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                                            ) {
                                                Text(
                                                    text = "Kvalita",
                                                    color = palette.cyanAccent,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                        }

                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(palette.goldAccent.copy(alpha = 0.18f))
                                                .clickable { stationForPresetDialog = station }
                                                .padding(horizontal = 8.dp, vertical = 5.dp)
                                        ) {
                                            Text(
                                                text = "★ Předvolba",
                                                color = palette.goldAccent,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Horni lista: Stanice vlevo, RADIO PM uprostred, kvalita vpravo
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                GlassCardBox(
                    modifier = Modifier.clickable {
                        scope.launch { drawerState.open() }
                    },
                    borderColor = palette.goldAccent.copy(alpha = 0.5f),
                    backgroundColor = palette.cardBg
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "☰",
                            color = palette.goldAccent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Stanice",
                            color = palette.primaryText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Text(
                    text = "RÁDIO PM",
                    color = palette.goldAccent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )

                GlassCardBox(
                    modifier = Modifier.clickable {
                        if (currentStation.variants.size > 1) {
                            stationForQualityDialog = currentStation
                        } else {
                            RadioController.refreshOnlineStations()
                        }
                    },
                    borderColor = palette.cyanAccent.copy(alpha = 0.45f),
                    backgroundColor = palette.cardBg
                ) {
                    Text(
                        text = currentStation.qualityLabel,
                        color = palette.cyanAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // Hlavni panel stanice v puvodni velikosti s RDS oknem (prazdne pri zastaveni nebo bez nazvu skladby)
            GlassCardBox(
                modifier = Modifier.fillMaxWidth(),
                borderColor = if (isPlaying) palette.goldAccent.copy(alpha = 0.75f) else palette.cardBorder,
                backgroundColor = palette.cardBg
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        StationLogo(
                            station = currentStation,
                            size = 56.dp,
                            isSelected = isPlaying,
                            iconBytes = stationIcons[currentStation.faviconUrl]
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = statusMessage,
                                color = if (isPlaying) PlayGreen else palette.secondaryText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = currentStation.name,
                                color = palette.goldAccent,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.rdsBoxBg)
                            .border(1.dp, palette.cardBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = displayRds.ifEmpty { " " },
                            color = palette.primaryText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (displayRds.isNotEmpty()) {
                                        Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                }
            }

            // 4 predvolby (2x2) bez nadpisu
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (row in 0 until 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for (col in 0 until 2) {
                            val index = row * 2 + col
                            val presetStation = presets.getOrNull(index)
                            val isCurrentPreset = presetStation != null &&
                                (presetStation.id == currentStation.id || presetStation.name == currentStation.name)

                            GlassCardBox(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(68.dp)
                                    .combinedClickable(
                                        onClick = {
                                            if (presetStation != null) {
                                                RadioController.selectAndPlayStation(presetStation)
                                            } else {
                                                RadioController.saveCurrentToPreset(index)
                                            }
                                        },
                                        onLongClick = {
                                            RadioController.saveCurrentToPreset(index)
                                        }
                                    ),
                                borderColor = if (isCurrentPreset) palette.goldAccent else palette.cardBorder,
                                backgroundColor = if (isCurrentPreset) palette.activeCardBg else palette.cardBg
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (presetStation != null) {
                                        StationLogo(
                                            station = presetStation,
                                            size = 42.dp,
                                            isSelected = isCurrentPreset,
                                            iconBytes = stationIcons[presetStation.faviconUrl]
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = presetStation.name,
                                            color = if (isCurrentPreset) palette.goldAccent else palette.primaryText,
                                            fontSize = 14.sp,
                                            lineHeight = 17.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "+ Předvolba ${index + 1}",
                                                color = palette.secondaryText,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Hlavni tlacitko SPUSTIT / VYPNOUT RADIO pres celou sirku (bez bocnich tlacitek)
            val mainBtnColor by animateColorAsState(if (isPlaying) StopRed else PlayGreen)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(mainBtnColor)
                    .border(1.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                    .clickable { RadioController.togglePlayback() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isPlaying) "■ VYPNOUT RÁDIO" else "▶ SPUSTIT RÁDIO",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
            }

            // Spodni moduly: Casovac vypnuti, Predbuzeni a Copyright
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassCardBox(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { RadioController.cycleSleepTimer() },
                        borderColor = if (sleepMinutes > 0) palette.goldAccent else palette.cardBorder,
                        backgroundColor = palette.cardBg
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "⏱ ČASOVAČ VYPNUTÍ",
                                color = palette.secondaryText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (sleepMinutes > 0) "Vypne za $sleepMinutes min" else "Vypnuto (klepni)",
                                color = if (sleepMinutes > 0) palette.goldAccent else palette.primaryText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    GlassCardBox(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { RadioController.toggleOneShotPreAlarm() },
                        borderColor = if (isPreAlarmEnabled) palette.cyanAccent else palette.cardBorder,
                        backgroundColor = palette.cardBg
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "⏰ PŘEDBUZENÍ (-30 MIN)",
                                color = palette.secondaryText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = preAlarmLabel,
                                color = if (isPreAlarmEnabled) palette.cyanAccent else palette.primaryText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Text(
                    text = "© 2026 Created by Pavel Mareš",
                    color = palette.secondaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    stationForPresetDialog?.let { st ->
        AlertDialog(
            onDismissRequest = { stationForPresetDialog = null },
            containerColor = palette.dialogBg,
            titleContentColor = palette.goldAccent,
            textContentColor = palette.primaryText,
            title = { Text("Uložit „${st.name}“ do předvolby", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (idx in 0 until 4) {
                        val existing = presets.getOrNull(idx)
                        Surface(
                            color = palette.cardBg,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, palette.cardBorder, RoundedCornerShape(10.dp))
                                .clickable {
                                    RadioController.saveStationToPreset(idx, st)
                                    stationForPresetDialog = null
                                }
                        ) {
                            Text(
                                text = "Předvolba ${idx + 1}: ${existing?.name ?: "(volné místo)"}",
                                color = palette.primaryText,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { stationForPresetDialog = null }) {
                    Text("Zavřít", color = palette.cyanAccent)
                }
            }
        )
    }

    stationForQualityDialog?.let { st ->
        AlertDialog(
            onDismissRequest = { stationForQualityDialog = null },
            containerColor = palette.dialogBg,
            titleContentColor = palette.cyanAccent,
            textContentColor = palette.primaryText,
            title = { Text("Kvalita streamu – ${st.name}", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    st.variants.forEach { variant ->
                        val isCurrent = variant.url == st.streamUrl
                        Surface(
                            color = if (isCurrent) palette.cyanAccent.copy(alpha = 0.22f) else palette.cardBg,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, palette.cardBorder, RoundedCornerShape(10.dp))
                                .clickable {
                                    RadioController.selectStationVariant(st, variant)
                                    stationForQualityDialog = null
                                }
                        ) {
                            Text(
                                text = if (isCurrent) "✓ ${variant.label}" else variant.label,
                                color = if (isCurrent) palette.cyanAccent else palette.primaryText,
                                fontSize = 14.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { stationForQualityDialog = null }) {
                    Text("Zavřít", color = palette.goldAccent)
                }
            }
        )
    }
}

@Composable
fun GlassCardBox(
    modifier: Modifier = Modifier,
    borderColor: Color = Color.White.copy(alpha = 0.18f),
    backgroundColor: Color = Color.White.copy(alpha = 0.07f),
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp)),
        content = content
    )
}

@Composable
fun StationLogo(
    station: RadioStation,
    size: Dp,
    isSelected: Boolean,
    iconBytes: ByteArray? = null
) {
    LaunchedEffect(station.faviconUrl) {
        if (station.faviconUrl.isNotBlank()) {
            RadioController.requestStationIcon(station.faviconUrl)
        }
    }

    val decodedBitmap = remember(iconBytes) {
        if (iconBytes != null && iconBytes.isNotEmpty()) {
            decodeByteArrayToImageBitmap(iconBytes)
        } else {
            null
        }
    }

    val initials = remember(station.name) { stationInitials(station.name) }
    val bgBrush = remember(station.id, isSelected) {
        if (isSelected) {
            Brush.linearGradient(listOf(Color(0xFFFFD54F), Color(0xFFFFA000)))
        } else {
            Brush.linearGradient(listOf(Color(0xFF1A3A54), Color(0xFF0F2537)))
        }
    }

    val cornerShape = RoundedCornerShape(size * 0.26f)

    Box(
        modifier = Modifier
            .size(size)
            .clip(cornerShape)
            .background(bgBrush)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.24f),
                shape = cornerShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (decodedBitmap != null) {
            Image(
                bitmap = decodedBitmap,
                contentDescription = station.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .clip(cornerShape)
            )
        } else {
            Text(
                text = initials,
                color = if (isSelected) Color(0xFF121212) else Color(0xFFFFD54F),
                fontSize = (size.value * 0.36f).sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        }
    }
}

private fun stationInitials(name: String): String {
    val withoutParens = name.replace(Regex("\\(.*?\\)"), " ").trim()
    val hasRadioPrefix = withoutParens.contains("Rádio", ignoreCase = true) ||
        withoutParens.contains("Radio", ignoreCase = true)
    val stripped = withoutParens
        .replace("Rádio", "", ignoreCase = true)
        .replace("Radio", "", ignoreCase = true)
        .replace("ČRo", "ČR", ignoreCase = true)
    val cleaned = stripped.map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
        .joinToString("")
        .trim()
    val parts = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase()
        parts.size == 1 && parts[0].length >= 2 -> parts[0].take(2).uppercase()
        parts.size == 1 && parts[0].length == 1 && hasRadioPrefix -> "R${parts[0]}".uppercase()
        cleaned.isNotEmpty() -> cleaned.take(2).uppercase()
        else -> "PM"
    }
}

private fun normalizeSearchText(input: String): String {
    val map = mapOf(
        'á' to 'a', 'č' to 'c', 'ď' to 'd', 'é' to 'e', 'ě' to 'e',
        'í' to 'i', 'ň' to 'n', 'ó' to 'o', 'ř' to 'r', 'š' to 's',
        'ť' to 't', 'ú' to 'u', 'ů' to 'u', 'ý' to 'y', 'ž' to 'z'
    )
    val sb = StringBuilder(input.length)
    for (ch in input.lowercase()) {
        sb.append(map[ch] ?: ch)
    }
    return sb.toString()
}
