package cz.taximares.humorradio

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
import androidx.compose.foundation.lazy.items
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

private val GoldAccent = Color(0xFFFFD54F)
private val CyanAccent = Color(0xFF4DD0E1)
private val StopRed = Color(0xFFE53935)
private val PlayGreen = Color(0xFF00C853)
private val DeepWaterTop = Color(0xFF07131E)
private val DeepWaterMid = Color(0xFF0C2233)
private val DeepWaterBottom = Color(0xFF050E17)

@Composable
fun App() {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DeepWaterTop, DeepWaterMid, DeepWaterBottom)
                )
            )
    ) {
        val isWatchScreen = maxWidth < 300.dp || maxHeight < 300.dp
        if (isWatchScreen) {
            WatchRadioScreen()
        } else {
            LaunchedEffect(Unit) {
                RadioController.setWatchStationListOpen(false)
            }
            PhoneRadioScreen()
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
        // Kruhovy ukazatel hlasitosti po obvodu displeje hodinek
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
                    listOf(CyanAccent, GoldAccent, CyanAccent)
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
                        color = CyanAccent,
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
                        color = GoldAccent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = rdsText,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .basicMarquee(iterations = Int.MAX_VALUE)
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
                        .border(1.dp, GoldAccent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .clickable { RadioController.setWatchStationListOpen(true) }
                        .padding(horizontal = 14.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Stanice (${allStations.size})",
                        color = GoldAccent,
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
                (favoriteStations + allStations).distinctBy { it.id }
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
                        color = CyanAccent,
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
                    items(combinedList, key = { it.id }) { station ->
                        val selected = station.id == currentStation.id
                        val isFav = favoriteStations.any { it.id == station.id }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (selected) GoldAccent.copy(alpha = 0.24f)
                                    else Color.White.copy(alpha = 0.08f)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (selected) GoldAccent else Color.White.copy(alpha = 0.12f),
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
                                color = if (selected) GoldAccent else Color.White,
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
private fun PhoneRadioScreen() {
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
        if (searchQuery.isBlank()) {
            allStations
        } else {
            val q = normalizeSearchText(searchQuery)
            allStations.filter { normalizeSearchText(it.name).contains(q) }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF0A1926),
                drawerContentColor = Color.White,
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
                                color = GoldAccent,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isUpdatingStations) "Aktualizuji seznam..." else "${allStations.size} stanic v databázi",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 12.sp
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.10f))
                                .clickable { RadioController.refreshOnlineStations() }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "↻ Obnovit",
                                color = CyanAccent,
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
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = GoldAccent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                            focusedPlaceholderColor = Color.White.copy(alpha = 0.45f),
                            unfocusedPlaceholderColor = Color.White.copy(alpha = 0.45f)
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 20.dp)
                    ) {
                        items(filteredStations, key = { it.id }) { station ->
                            val isSelected = station.id == currentStation.id

                            GlassCardBox(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        RadioController.selectAndPlayStation(station)
                                        scope.launch { drawerState.close() }
                                    },
                                borderColor = if (isSelected) GoldAccent else Color.White.copy(alpha = 0.14f),
                                backgroundColor = if (isSelected) GoldAccent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f)
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
                                            color = if (isSelected) GoldAccent else Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Kvalita: ${station.qualityLabel}",
                                            color = Color.White.copy(alpha = 0.65f),
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    }

                                    if (station.variants.size > 1) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(CyanAccent.copy(alpha = 0.16f))
                                                .clickable { stationForQualityDialog = station }
                                                .padding(horizontal = 8.dp, vertical = 5.dp)
                                        ) {
                                            Text(
                                                text = "Kvalita",
                                                color = CyanAccent,
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
                                            .background(GoldAccent.copy(alpha = 0.18f))
                                            .clickable { stationForPresetDialog = station }
                                            .padding(horizontal = 8.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = "★ Předvolba",
                                            color = GoldAccent,
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
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Horni lista aplikace – srovnana na 1 radek bez kolize
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                GlassCardBox(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .clickable {
                            scope.launch { drawerState.open() }
                        },
                    borderColor = GoldAccent.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "☰",
                            color = GoldAccent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Stanice (${allStations.size})",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Text(
                    text = "RÁDIO PM",
                    color = GoldAccent,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.align(Alignment.Center)
                )

                GlassCardBox(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clickable {
                            if (currentStation.variants.size > 1) {
                                stationForQualityDialog = currentStation
                            } else {
                                RadioController.refreshOnlineStations()
                            }
                        },
                    borderColor = CyanAccent.copy(alpha = 0.45f)
                ) {
                    Text(
                        text = currentStation.qualityLabel,
                        color = CyanAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }

            // Hlavni RDS skleneny panel
            GlassCardBox(
                modifier = Modifier.fillMaxWidth(),
                borderColor = if (isPlaying) GoldAccent.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.22f),
                backgroundColor = Color.White.copy(alpha = 0.08f)
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
                                color = if (isPlaying) PlayGreen else Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = currentStation.name,
                                color = GoldAccent,
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
                            .background(Color.Black.copy(alpha = 0.38f))
                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = rdsText,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee(iterations = Int.MAX_VALUE)
                        )
                    }
                }
            }

            // Mrizka 4 oblibenych predvoleb (2x2) s celymi nazvy a skutecnymi ikonami
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "OBLÍBENÉ PŘEDVOLBY (podržením uložíš aktuální)",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )

                for (row in 0 until 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for (col in 0 until 2) {
                            val index = row * 2 + col
                            val presetStation = presets.getOrNull(index)
                            val isCurrentPreset = presetStation != null && presetStation.id == currentStation.id

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
                                borderColor = if (isCurrentPreset) GoldAccent else Color.White.copy(alpha = 0.18f),
                                backgroundColor = if (isCurrentPreset) GoldAccent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.07f)
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
                                            color = if (isCurrentPreset) GoldAccent else Color.White,
                                            fontSize = 14.sp,
                                            lineHeight = 17.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        Text(
                                            text = "+ Předvolba ${index + 1}",
                                            color = Color.White.copy(alpha = 0.5f),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Ovladaci panel: Predchozi / Hlavni tlacitko Play-Stop / Dalsi
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassCardBox(
                    modifier = Modifier
                        .size(56.dp)
                        .clickable { RadioController.playPreviousStation() },
                    borderColor = Color.White.copy(alpha = 0.25f)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("⏮", color = Color.White, fontSize = 20.sp)
                    }
                }

                val mainBtnColor by animateColorAsState(if (isPlaying) StopRed else PlayGreen)
                Box(
                    modifier = Modifier
                        .weight(1f)
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

                GlassCardBox(
                    modifier = Modifier
                        .size(56.dp)
                        .clickable { RadioController.playNextStation() },
                    borderColor = Color.White.copy(alpha = 0.25f)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("⏭", color = Color.White, fontSize = 20.sp)
                    }
                }
            }

            // Spodni moduly: Casovac vypnuti a Predbuzeni (srovnano na 1 radek)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassCardBox(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { RadioController.cycleSleepTimer() },
                    borderColor = if (sleepMinutes > 0) GoldAccent else Color.White.copy(alpha = 0.18f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "⏱ ČASOVAČ VYPNUTÍ",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (sleepMinutes > 0) "Vypne za $sleepMinutes min" else "Vypnuto (klepni)",
                            color = if (sleepMinutes > 0) GoldAccent else Color.White,
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
                    borderColor = if (isPreAlarmEnabled) CyanAccent else Color.White.copy(alpha = 0.18f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "⏰ PŘEDBUZENÍ (-30 MIN)",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = preAlarmLabel,
                            color = if (isPreAlarmEnabled) CyanAccent else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    // Dialog pro ulozeni vybrane stanice do predvolby 1..4
    stationForPresetDialog?.let { st ->
        AlertDialog(
            onDismissRequest = { stationForPresetDialog = null },
            containerColor = Color(0xFF0E2233),
            titleContentColor = GoldAccent,
            textContentColor = Color.White,
            title = { Text("Uložit „${st.name}“ do předvolby", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (idx in 0 until 4) {
                        val existing = presets.getOrNull(idx)
                        Surface(
                            color = Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    RadioController.saveStationToPreset(idx, st)
                                    stationForPresetDialog = null
                                }
                        ) {
                            Text(
                                text = "Předvolba ${idx + 1}: ${existing?.name ?: "(volné místo)"}",
                                color = Color.White,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { stationForPresetDialog = null }) {
                    Text("Zavřít", color = CyanAccent)
                }
            }
        )
    }

    // Dialog pro vyber kvality streamu (bitrate)
    stationForQualityDialog?.let { st ->
        AlertDialog(
            onDismissRequest = { stationForQualityDialog = null },
            containerColor = Color(0xFF0E2233),
            titleContentColor = CyanAccent,
            textContentColor = Color.White,
            title = { Text("Kvalita streamu – ${st.name}", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    st.variants.forEach { variant ->
                        val isCurrent = variant.url == st.streamUrl
                        Surface(
                            color = if (isCurrent) CyanAccent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    RadioController.selectStationVariant(st, variant)
                                    stationForQualityDialog = null
                                }
                        ) {
                            Text(
                                text = if (isCurrent) "✓ ${variant.label}" else variant.label,
                                color = if (isCurrent) CyanAccent else Color.White,
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
                    Text("Zavřít", color = GoldAccent)
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
            Brush.linearGradient(listOf(GoldAccent, Color(0xFFFFA000)))
        } else {
            Brush.linearGradient(listOf(Color(0xFF1A3A54), Color(0xFF0F2537)))
        }
    }

    val cornerShape = RoundedCornerShape(size * 0.26f)

    Box(
        modifier = Modifier
            .size(size)
            .clip(cornerShape)
            .background(if (decodedBitmap != null) Color.White else Color.Transparent)
            .then(
                if (decodedBitmap == null) Modifier.background(bgBrush) else Modifier
            )
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) GoldAccent else Color.White.copy(alpha = 0.24f),
                shape = cornerShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (decodedBitmap != null) {
            Image(
                bitmap = decodedBitmap,
                contentDescription = station.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(cornerShape)
            )
        } else {
            Text(
                text = initials,
                color = if (isSelected) Color(0xFF121212) else GoldAccent,
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
    val cleaned = withoutParens
        .replace("Rádio", "", ignoreCase = true)
        .replace("Radio", "", ignoreCase = true)
        .replace("ČRo", "ČR", ignoreCase = true)
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
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
