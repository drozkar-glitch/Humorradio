package cz.taximares.radiopm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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

private fun bookCoverInitials(title: String): String {
    val clean = title.map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
        .joinToString("")
        .trim()
    val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
        words.size == 1 && words[0].length >= 2 -> words[0].take(2).uppercase()
        clean.isNotEmpty() -> clean.take(2).uppercase()
        else -> "KN"
    }
}

@Composable
internal fun BookCoverIcon(
    representativeItem: AudiobookItem?,
    title: String,
    coverBytes: ByteArray?,
    width: Dp = 46.dp,
    height: Dp = 58.dp,
    isActive: Boolean = false,
    isPlayed: Boolean = false
) {
    LaunchedEffect(representativeItem?.id, representativeItem?.coverKey) {
        if (representativeItem != null) {
            RadioController.requestAudiobookCover(representativeItem)
        }
    }

    val decodedCover = remember(coverBytes) {
        if (coverBytes != null && coverBytes.isNotEmpty()) decodeByteArrayToImageBitmap(coverBytes) else null
    }
    val initials = remember(title) { bookCoverInitials(title) }
    val greenDone = Color(0xFF00C853)
    val goldCol = Color(0xFFFFD54F)

    val bookShape = RoundedCornerShape(
        topStart = 3.dp,
        bottomStart = 3.dp,
        topEnd = 7.dp,
        bottomEnd = 7.dp
    )
    val pagesShape = RoundedCornerShape(
        topStart = 2.dp,
        bottomStart = 2.dp,
        topEnd = 7.dp,
        bottomEnd = 7.dp
    )

    val fallbackCoverBrush = when {
        isPlayed -> Brush.linearGradient(listOf(Color(0xFF144B36), Color(0xFF0A2B1E)))
        isActive -> Brush.linearGradient(listOf(Color(0xFF6B4300), Color(0xFF3A2200)))
        else -> Brush.linearGradient(listOf(Color(0xFF1B4363), Color(0xFF0D2438)))
    }
    val borderColor = when {
        isActive -> goldCol
        isPlayed -> greenDone
        else -> Color.White.copy(alpha = 0.28f)
    }

    Box(modifier = Modifier.size(width = width, height = height)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 5.dp, top = 2.dp)
                .clip(pagesShape)
                .background(Color(0xFFEBE4D3))
                .border(0.75.dp, Color(0xFF9E9583), pagesShape)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val x1 = size.width - 2.dp.toPx()
                drawLine(
                    color = Color(0x559E9583),
                    start = Offset(x1, 3.dp.toPx()),
                    end = Offset(x1, size.height - 3.dp.toPx()),
                    strokeWidth = 1f
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 3.5.dp, bottom = 2.dp)
                .clip(bookShape)
                .background(fallbackCoverBrush)
                .border(if (isActive) 1.5.dp else 1.dp, borderColor, bookShape)
        ) {
            if (decodedCover != null) {
                Image(
                    bitmap = decodedCover,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 6.dp)
                        .width(6.dp)
                        .height(13.dp)
                        .background(if (isActive) goldCol else Color(0xFFE53935))
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initials,
                        color = goldCol,
                        fontSize = (width.value * 0.30f).sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(7.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.42f),
                                Color.White.copy(alpha = 0.22f),
                                Color.Black.copy(alpha = 0.36f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        if (isPlayed) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(greenDone)
                    .border(1.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✓",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
internal fun ColumnAudiobookCatalog(
    palette: LiquidThemePalette,
    audiobooks: List<AudiobookItem>,
    audiobookCovers: kotlin.collections.Map<String, ByteArray>,
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

    var openedBookKey by remember { mutableStateOf<String?>(null) }

    val allBooks = remember(audiobooks, currentAudiobook?.id) {
        groupAudiobooksIntoBooks(audiobooks, currentAudiobook?.id)
    }

    val activeOpenedBook = remember(allBooks, openedBookKey) {
        if (openedBookKey == null) null else allBooks.find { it.key == openedBookKey }
    }

    LaunchedEffect(openedBookKey, activeOpenedBook) {
        if (openedBookKey != null && activeOpenedBook == null) {
            openedBookKey = null
        }
    }

    val playedTotalCount = remember(audiobooks) { audiobooks.count { it.isPlayed } }

    Column(modifier = modifier.fillMaxWidth()) {
        if (activeOpenedBook == null) {
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
                placeholder = { Text("Hledat knihu nebo díl...", fontSize = 13.sp) },
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
                    text = "⏳ Načítám knihy a obálky z audiosouborů...",
                    color = palette.cyanAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (playedTotalCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Knih: ${allBooks.size} • Přehraných dílů: $playedTotalCount",
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

            val filteredBooks = remember(allBooks, searchQuery) {
                val q = searchQuery.trim()
                if (q.isEmpty()) {
                    allBooks
                } else {
                    val norm = normalizeSearchText(q)
                    allBooks.filter { grp ->
                        normalizeSearchText(grp.title).contains(norm) ||
                            grp.chapters.any { normalizeSearchText(it.title).contains(norm) }
                    }
                }
            }

            if (filteredBooks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (allBooks.isEmpty()) {
                            "Knihovna audioknih je prázdný seznam.\n\nKlepni na „+ Celá složka“ nebo „+ Soubory / ZIP“ – každá složka či ZIP se zobrazí jako vázaná kniha s obálkou přímo z audiosouboru."
                        } else {
                            "Žádná kniha neodpovídá hledání „$searchQuery“."
                        },
                        color = palette.secondaryText,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    items(filteredBooks.size, key = { filteredBooks[it].key }) { idx ->
                        val book = filteredBooks[idx]
                        val isCurrentBook = playbackMode == PlaybackMode.AUDIOBOOK &&
                            currentAudiobook?.effectiveBookKey() == book.key
                        val isMultiPart = book.chapters.size > 1

                        GlassCardBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isMultiPart) {
                                        openedBookKey = book.key
                                    } else {
                                        onSelectAudiobook(book.chapters.first())
                                    }
                                },
                            borderColor = when {
                                isCurrentBook -> palette.goldAccent
                                book.isAllPlayed -> greenPlayed.copy(alpha = 0.55f)
                                else -> palette.cardBorder
                            },
                            backgroundColor = if (isCurrentBook) palette.activeCardBg else palette.cardBg
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BookCoverIcon(
                                    representativeItem = book.representativeItem,
                                    title = book.title,
                                    coverBytes = audiobookCovers[book.coverKey],
                                    width = 46.dp,
                                    height = 58.dp,
                                    isActive = isCurrentBook,
                                    isPlayed = book.isAllPlayed
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = book.title,
                                        color = when {
                                            isCurrentBook -> palette.goldAccent
                                            book.isAllPlayed -> palette.secondaryText
                                            else -> palette.primaryText
                                        },
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        lineHeight = 18.sp,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    if (isMultiPart) {
                                        val statusText = when {
                                            book.isAllPlayed -> "${book.chapters.size} dílů • ✓ Celá přehraná"
                                            book.hasProgress -> "${book.chapters.size} dílů • Rozposloucháno (${book.playedChaptersCount}/${book.chapters.size})"
                                            else -> "${book.chapters.size} dílů • Klepni pro výběr dílu"
                                        }
                                        val statusCol = when {
                                            book.isAllPlayed -> greenPlayed
                                            book.hasProgress -> palette.cyanAccent
                                            else -> palette.secondaryText
                                        }
                                        Text(
                                            text = statusText,
                                            color = statusCol,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    } else {
                                        val only = book.chapters.first()
                                        val singleLabel = when {
                                            only.isPlayed -> "1 díl • ✓ Přehráno"
                                            only.positionMs > 0L -> {
                                                val p = formatAudioDuration(only.positionMs)
                                                val d = if (only.durationMs > 0L) " / ${formatAudioDuration(only.durationMs)}" else ""
                                                "▶ Pokračovat od $p$d"
                                            }
                                            else -> "1 díl • ${only.extension.uppercase()}"
                                        }
                                        val singleColor = when {
                                            only.isPlayed -> greenPlayed
                                            only.positionMs > 0L -> palette.cyanAccent
                                            else -> palette.secondaryText
                                        }
                                        Text(
                                            text = singleLabel,
                                            color = singleColor,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (book.isAllPlayed) greenPlayed.copy(alpha = 0.22f) else palette.cardBg)
                                        .border(1.dp, if (book.isAllPlayed) greenPlayed else palette.cardBorder, RoundedCornerShape(8.dp))
                                        .clickable { RadioController.toggleAudiobookGroupPlayed(book.key) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "✓",
                                        color = if (book.isAllPlayed) greenPlayed else palette.secondaryText,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(5.dp))
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(dangerRed.copy(alpha = 0.16f))
                                        .border(1.dp, dangerRed.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                        .clickable { RadioController.removeAudiobookGroup(book.key) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "✕",
                                        color = dangerRed,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.cardBg)
                        .border(1.dp, palette.cardBorder, RoundedCornerShape(10.dp))
                        .clickable { openedBookKey = null }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "← Všechny knihy",
                        color = palette.cyanAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.goldAccent.copy(alpha = 0.22f))
                        .border(1.dp, palette.goldAccent.copy(alpha = 0.70f), RoundedCornerShape(10.dp))
                        .clickable { onSelectAudiobook(activeOpenedBook.resumeChapter) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (activeOpenedBook.hasProgress && !activeOpenedBook.isAllPlayed) "▶ Pokračovat" else "▶ Přehrát knihu",
                        color = palette.goldAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            GlassCardBox(
                modifier = Modifier.fillMaxWidth(),
                borderColor = palette.goldAccent.copy(alpha = 0.45f),
                backgroundColor = palette.cardBg
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BookCoverIcon(
                        representativeItem = activeOpenedBook.representativeItem,
                        title = activeOpenedBook.title,
                        coverBytes = audiobookCovers[activeOpenedBook.coverKey],
                        width = 42.dp,
                        height = 54.dp,
                        isActive = true,
                        isPlayed = activeOpenedBook.isAllPlayed
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeOpenedBook.title,
                            color = palette.goldAccent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 2,
                            lineHeight = 19.sp,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Přehráno ${activeOpenedBook.playedChaptersCount} z ${activeOpenedBook.chapters.size} dílů",
                            color = palette.secondaryText,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val chapters = activeOpenedBook.chapters
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(chapters.size, key = { chapters[it].id }) { index ->
                    val part = chapters[index]
                    val isCurrentPart = playbackMode == PlaybackMode.AUDIOBOOK && currentAudiobook?.id == part.id
                    GlassCardBox(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectAudiobook(part) },
                        borderColor = when {
                            isCurrentPart -> palette.goldAccent
                            part.isPlayed -> greenPlayed.copy(alpha = 0.50f)
                            else -> palette.cardBorder
                        },
                        backgroundColor = if (isCurrentPart) palette.activeCardBg else palette.cardBg
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isCurrentPart -> palette.goldAccent
                                            part.isPlayed -> greenPlayed.copy(alpha = 0.20f)
                                            else -> palette.rdsBoxBg
                                        }
                                    )
                                    .border(
                                        1.dp,
                                        when {
                                            isCurrentPart -> palette.goldAccent
                                            part.isPlayed -> greenPlayed
                                            else -> palette.cardBorder
                                        },
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = when {
                                        isCurrentPart -> "▶"
                                        part.isPlayed -> "✓"
                                        else -> "${index + 1}"
                                    },
                                    color = when {
                                        isCurrentPart -> Color(0xFF121212)
                                        part.isPlayed -> greenPlayed
                                        else -> palette.primaryText
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = part.title,
                                    color = when {
                                        isCurrentPart -> palette.goldAccent
                                        part.isPlayed -> palette.secondaryText
                                        else -> palette.primaryText
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val partState = when {
                                    part.isPlayed -> "✓ Přehráno"
                                    part.positionMs > 0L -> {
                                        val p = formatAudioDuration(part.positionMs)
                                        val d = if (part.durationMs > 0L) " / ${formatAudioDuration(part.durationMs)}" else ""
                                        "▶ Pokračovat od $p$d"
                                    }
                                    else -> "Nepřehráno • ${part.extension.uppercase()}"
                                }
                                Text(
                                    text = partState,
                                    color = when {
                                        part.isPlayed -> greenPlayed
                                        part.positionMs > 0L -> palette.cyanAccent
                                        else -> palette.secondaryText
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (part.isPlayed) greenPlayed.copy(alpha = 0.22f) else palette.cardBg)
                                    .border(1.dp, if (part.isPlayed) greenPlayed else palette.cardBorder, RoundedCornerShape(7.dp))
                                    .clickable { RadioController.toggleAudiobookPlayed(part.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "✓",
                                    color = if (part.isPlayed) greenPlayed else palette.secondaryText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(5.dp))
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(dangerRed.copy(alpha = 0.16f))
                                    .border(1.dp, dangerRed.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
                                    .clickable { RadioController.removeAudiobook(part.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "✕",
                                    color = dangerRed,
                                    fontSize = 11.sp,
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
