package cz.taximares.radiopm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class LiquidThemePalette(
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

internal fun getPalette(isDark: Boolean): LiquidThemePalette {
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

internal fun normalizeSearchText(input: String): String {
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

@Composable
internal fun AudiobookBadgeIcon(
    size: Dp,
    isActive: Boolean,
    isPlayed: Boolean
) {
    val greenDone = Color(0xFF00C853)
    val cornerShape = RoundedCornerShape(size * 0.26f)
    val bgBrush = when {
        isPlayed -> Brush.linearGradient(listOf(Color(0xFF0E3B28), Color(0xFF082619)))
        isActive -> Brush.linearGradient(listOf(Color(0xFFFFD54F), Color(0xFFFFA000)))
        else -> Brush.linearGradient(listOf(Color(0xFF15384E), Color(0xFF0C2233)))
    }
    val borderCol = when {
        isPlayed -> greenDone
        isActive -> Color(0xFFFFD54F)
        else -> Color(0xFF4DD0E1).copy(alpha = 0.45f)
    }
    val symbol = when {
        isPlayed -> "✓"
        isActive -> "▶"
        else -> "📚"
    }
    val symbolColor = when {
        isPlayed -> greenDone
        isActive -> Color(0xFF121212)
        else -> Color.White
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(cornerShape)
            .background(bgBrush)
            .border(if (isActive) 1.5.dp else 1.dp, borderCol, cornerShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            color = symbolColor,
            fontSize = (size.value * 0.40f).sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
    }
}

@Composable
internal fun ColumnAudiobookCatalog(
    palette: LiquidThemePalette,
    audiobooks: List<AudiobookItem>,
    currentAudiobook: AudiobookItem?,
    playbackMode: PlaybackMode,
    isImporting: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSelectAudiobook: (AudiobookItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val greenPlayed = Color(0xFF00C853)
    val dangerRed = Color(0xFFE53935)

    val filteredBooks = remember(audiobooks, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            audiobooks
        } else {
            val norm = normalizeSearchText(q)
            audiobooks.filter { item ->
                normalizeSearchText(item.title).contains(norm) ||
                    normalizeSearchText(item.groupTitle).contains(norm)
            }
        }
    }
    val playedCount = remember(audiobooks) { audiobooks.count { it.isPlayed } }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.goldAccent.copy(alpha = 0.20f))
                    .border(1.dp, palette.goldAccent.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                    .clickable { RadioController.pickAudiobookFiles() }
                    .padding(vertical = 10.dp, horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+ Soubory / ZIP",
                    color = palette.goldAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.cyanAccent.copy(alpha = 0.18f))
                    .border(1.dp, palette.cyanAccent.copy(alpha = 0.60f), RoundedCornerShape(12.dp))
                    .clickable { RadioController.pickAudiobookFolder() }
                    .padding(vertical = 10.dp, horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+ Celá složka",
                    color = palette.cyanAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Hledat kapitolu nebo knihu...", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = palette.primaryText,
                unfocusedTextColor = palette.primaryText,
                focusedBorderColor = palette.cyanAccent,
                unfocusedBorderColor = palette.cardBorder,
                focusedPlaceholderColor = palette.secondaryText,
                unfocusedPlaceholderColor = palette.secondaryText
            )
        )

        if (isImporting) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "⏳ Načítám audio soubory a obsah ZIP archivů...",
                color = palette.cyanAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (playedCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Přehráno: $playedCount z ${audiobooks.size}",
                    color = palette.secondaryText,
                    fontSize = 11.sp
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(dangerRed.copy(alpha = 0.16f))
                        .border(1.dp, dangerRed.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .clickable { RadioController.removePlayedAudiobooks() }
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Smazat přehrané",
                        color = dangerRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (filteredBooks.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (audiobooks.isEmpty()) {
                        "Seznam audioknih je prázdný.\n\nKlepni nahoře na „+ Soubory / ZIP“ nebo „+ Celá složka“ a vyber audio soubory (MP3, M4B, HE-AACv2, FLAC, OGG, WAV) nebo přímo .ZIP archiv."
                    } else {
                        "Žádná položka neodpovídá hledání „$searchQuery“."
                    },
                    color = palette.secondaryText,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(filteredBooks.size, key = { filteredBooks[it].id }) { index ->
                    val book = filteredBooks[index]
                    val isCurrent = playbackMode == PlaybackMode.AUDIOBOOK && currentAudiobook?.id == book.id
                    GlassCardBox(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectAudiobook(book) },
                        borderColor = when {
                            isCurrent -> palette.goldAccent
                            book.isPlayed -> greenPlayed.copy(alpha = 0.55f)
                            else -> palette.cardBorder
                        },
                        backgroundColor = if (isCurrent) palette.activeCardBg else palette.cardBg
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AudiobookBadgeIcon(
                                size = 40.dp,
                                isActive = isCurrent,
                                isPlayed = book.isPlayed
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = book.title,
                                    color = when {
                                        isCurrent -> palette.goldAccent
                                        book.isPlayed -> palette.secondaryText
                                        else -> palette.primaryText
                                    },
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (book.groupTitle.isNotBlank()) {
                                    Text(
                                        text = book.groupTitle,
                                        color = palette.secondaryText,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                val stateLabel = when {
                                    book.isPlayed -> "✓ Přehráno"
                                    book.positionMs > 0L -> {
                                        val p = formatAudioDuration(book.positionMs)
                                        val d = if (book.durationMs > 0L) " / ${formatAudioDuration(book.durationMs)}" else ""
                                        "▶ Pokračovat od $p$d"
                                    }
                                    else -> {
                                        val zipTag = if (book.zipEntryPath.isNotEmpty()) " • ZIP" else ""
                                        "Nepřehráno • ${book.extension.uppercase()}$zipTag"
                                    }
                                }
                                val stateColor = when {
                                    book.isPlayed -> greenPlayed
                                    book.positionMs > 0L -> palette.cyanAccent
                                    else -> palette.secondaryText
                                }
                                Text(
                                    text = stateLabel,
                                    color = stateColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (book.isPlayed) greenPlayed.copy(alpha = 0.22f)
                                        else palette.cardBg
                                    )
                                    .border(
                                        1.dp,
                                        if (book.isPlayed) greenPlayed else palette.cardBorder,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { RadioController.toggleAudiobookPlayed(book.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "✓",
                                    color = if (book.isPlayed) greenPlayed else palette.secondaryText,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(dangerRed.copy(alpha = 0.16f))
                                    .border(1.dp, dangerRed.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                    .clickable { RadioController.removeAudiobook(book.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "✕",
                                    color = dangerRed,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AudiobookPlayerBox(
    palette: LiquidThemePalette,
    positionMs: Long,
    durationMs: Long
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }

    val safeDuration = durationMs.coerceAtLeast(0L)
    val liveFraction = if (safeDuration > 0L) {
        (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shownFraction = if (isDragging) dragFraction else liveFraction
    val shownPositionMs = if (isDragging && safeDuration > 0L) {
        (dragFraction * safeDuration.toFloat()).toLong()
    } else {
        positionMs
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.rdsBoxBg)
            .border(1.dp, palette.cardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Slider(
            value = shownFraction,
            onValueChange = { frac ->
                if (safeDuration > 0L) {
                    isDragging = true
                    dragFraction = frac.coerceIn(0f, 1f)
                }
            },
            onValueChangeFinished = {
                if (isDragging && safeDuration > 0L) {
                    val targetMs = (dragFraction * safeDuration.toFloat()).toLong()
                    isDragging = false
                    RadioController.seekAudiobookTo(targetMs)
                } else {
                    isDragging = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            colors = SliderDefaults.colors(
                thumbColor = palette.goldAccent,
                activeTrackColor = palette.cyanAccent,
                inactiveTrackColor = palette.cardBorder
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatAudioDuration(shownPositionMs),
                color = palette.primaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (safeDuration > 0L) formatAudioDuration(safeDuration) else "--:--",
                color = palette.secondaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AudiobookMiniActionBtn(
                label = "⏪ -15 s",
                palette = palette,
                onClick = { RadioController.seekAudiobookRelative(-15_000L) },
                modifier = Modifier.weight(1f)
            )
            AudiobookMiniActionBtn(
                label = "⏮ Předch.",
                palette = palette,
                onClick = { RadioController.playPreviousStation() },
                modifier = Modifier.weight(1f)
            )
            AudiobookMiniActionBtn(
                label = "Další ⏭",
                palette = palette,
                onClick = { RadioController.playNextStation() },
                modifier = Modifier.weight(1f)
            )
            AudiobookMiniActionBtn(
                label = "+30 s ⏩",
                palette = palette,
                onClick = { RadioController.seekAudiobookRelative(30_000L) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun AudiobookMiniActionBtn(
    label: String,
    palette: LiquidThemePalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(palette.cardBg)
            .border(1.dp, palette.cardBorder, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = palette.cyanAccent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}
