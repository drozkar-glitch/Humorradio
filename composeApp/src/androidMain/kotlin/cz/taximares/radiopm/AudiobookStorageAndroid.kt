package cz.taximares.radiopm

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

internal object AudiobookStorageAndroid {

    private const val MAX_COVER_PX = 320

    private val ZIP_CHARSETS: List<Charset> by lazy {
        val list = mutableListOf<Charset>(Charsets.UTF_8)
        try {
            list.add(Charset.forName("CP852"))
        } catch (_: Exception) {
        }
        try {
            list.add(Charset.forName("windows-1250"))
        } catch (_: Exception) {
        }
        list.add(Charsets.ISO_8859_1)
        list
    }

    private fun releaseRetrieverSafely(mmr: MediaMetadataRetriever) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mmr.close()
            } else {
                mmr.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun coversDir(context: Context): File {
        val dir = File(context.filesDir, "audiobook_covers")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun coverCacheFile(context: Context, coverKey: String): File {
        return File(coversDir(context), "cov_${coverKey.hashCode().toUInt()}.jpg")
    }

    private fun hasCachedCover(context: Context, coverKey: String): Boolean {
        if (coverKey.isBlank()) return false
        val f = coverCacheFile(context, coverKey)
        return f.exists() && f.length() > 0L
    }

    private fun downscaleAndSaveCover(context: Context, coverKey: String, rawImageBytes: ByteArray): ByteArray? {
        if (coverKey.isBlank() || rawImageBytes.isEmpty()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawImageBytes, 0, rawImageBytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_COVER_PX && bounds.outHeight / (sample * 2) >= MAX_COVER_PX) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(rawImageBytes, 0, rawImageBytes.size, decodeOpts) ?: return null

            val maxDim = maxOf(decoded.width, decoded.height)
            val finalBmp = if (maxDim > MAX_COVER_PX) {
                val ratio = MAX_COVER_PX.toFloat() / maxDim.toFloat()
                val w = (decoded.width * ratio).toInt().coerceAtLeast(1)
                val h = (decoded.height * ratio).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(decoded, w, h, true)
                if (scaled !== decoded) decoded.recycle()
                scaled
            } else {
                decoded
            }

            val out = ByteArrayOutputStream()
            finalBmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            finalBmp.recycle()
            val jpegBytes = out.toByteArray()
            if (jpegBytes.isNotEmpty()) {
                FileOutputStream(coverCacheFile(context, coverKey)).use { it.write(jpegBytes) }
                jpegBytes
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun loadOrExtractCoverBytes(context: Context, item: AudiobookItem): ByteArray? {
        val key = item.effectiveCoverKey()
        if (key.isBlank()) return null
        val cached = coverCacheFile(context, key)
        if (cached.exists() && cached.length() > 0L) {
            try {
                return cached.readBytes()
            } catch (_: Exception) {
            }
        }

        val rawCover: ByteArray? = if (item.zipEntryPath.isBlank()) {
            try {
                extractEmbeddedMetaFromUri(context, Uri.parse(item.sourceUri), wantCover = true).second
            } catch (_: Exception) {
                null
            }
        } else {
            extractEmbeddedCoverFromZipAudio(context, item) ?: extractImageEntryFromZip(context, Uri.parse(item.sourceUri))
        }

        return if (rawCover != null && rawCover.isNotEmpty()) {
            downscaleAndSaveCover(context, key, rawCover)
        } else {
            null
        }
    }

    private fun extractEmbeddedMetaFromUri(
        context: Context,
        uri: Uri,
        wantCover: Boolean
    ): Pair<String, ByteArray?> {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim().orEmpty()
            val pic = if (wantCover) mmr.embeddedPicture else null
            album to pic
        } catch (_: Exception) {
            "" to null
        } finally {
            releaseRetrieverSafely(mmr)
        }
    }

    private fun extractEmbeddedCoverFromZipAudio(context: Context, item: AudiobookItem): ByteArray? {
        val localUri = resolvePlayableUri(context, item) ?: return null
        val path = localUri.path ?: return null
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            mmr.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            releaseRetrieverSafely(mmr)
        }
    }

    fun takePersistableReadPermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    fun importDocumentUris(context: Context, uris: List<Uri>): List<AudiobookItem> {
        val results = mutableListOf<AudiobookItem>()
        for (uri in uris) {
            takePersistableReadPermission(context, uri)
            val displayName = queryDisplayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Audio"
            if (displayName.endsWith(".zip", ignoreCase = true)) {
                val zipTitle = displayName.substringBeforeLast('.')
                results.addAll(scanZipUri(context, uri, zipTitle))
            } else if (isSupportedAudioFileName(displayName)) {
                val ext = displayName.substringAfterLast('.', "mp3").lowercase()
                val cleanTitle = displayName.substringBeforeLast('.').replace('_', ' ').trim()
                val uriStr = uri.toString()
                val itemId = "and_file_${uriStr.hashCode()}"
                val (albumName, rawPic) = extractEmbeddedMetaFromUri(context, uri, wantCover = true)
                val covKey = if (albumName.isNotBlank()) {
                    "book_${albumName.lowercase()}"
                } else {
                    "single_$itemId"
                }
                if (rawPic != null && rawPic.isNotEmpty() && !hasCachedCover(context, covKey)) {
                    downscaleAndSaveCover(context, covKey, rawPic)
                }
                results.add(
                    AudiobookItem(
                        id = itemId,
                        title = cleanTitle.ifEmpty { displayName },
                        groupTitle = albumName,
                        sourceUri = uriStr,
                        zipEntryPath = "",
                        extension = ext,
                        coverKey = covKey
                    )
                )
            }
        }
        return results.sortedWith { a, b ->
            val g = naturalCompareStrings(a.groupTitle, b.groupTitle)
            if (g != 0) g else naturalCompareStrings(a.title, b.title)
        }
    }

    fun importFolderTreeUri(context: Context, treeUri: Uri): List<AudiobookItem> {
        takePersistableReadPermission(context, treeUri)
        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            return emptyList()
        }
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        val rootName = queryDisplayName(context, rootDocUri)
            ?: rootDocId.substringAfterLast(':').substringAfterLast('/')

        val rootChildren = queryFolderChildren(context, treeUri, rootDocId)
        val hasDirectAudioInRoot = rootChildren.any { (_, name, mime) ->
            mime != DocumentsContract.Document.MIME_TYPE_DIR &&
                (isSupportedAudioFileName(name) || name.endsWith(".zip", ignoreCase = true))
        }

        val results = mutableListOf<AudiobookItem>()
        if (hasDirectAudioInRoot) {
            scanBookFolder(
                context = context,
                treeUri = treeUri,
                folderDocId = rootDocId,
                bookTitle = rootName,
                subPrefix = "",
                out = results,
                depth = 0
            )
        } else {
            for ((docId, name, mime) in rootChildren) {
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    scanBookFolder(
                        context = context,
                        treeUri = treeUri,
                        folderDocId = docId,
                        bookTitle = name,
                        subPrefix = "",
                        out = results,
                        depth = 0
                    )
                }
            }
        }
        return results.sortedWith { a, b ->
            val g = naturalCompareStrings(a.groupTitle, b.groupTitle)
            if (g != 0) g else naturalCompareStrings(a.title, b.title)
        }
    }

    private fun queryFolderChildren(
        context: Context,
        treeUri: Uri,
        parentDocId: String
    ): List<Triple<String, String, String>> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val entries = mutableListOf<Triple<String, String, String>>()
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val docId = if (idCol >= 0) cursor.getString(idCol).orEmpty() else ""
                    val name = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else ""
                    if (docId.isNotEmpty() && name.isNotEmpty() && !name.startsWith(".")) {
                        entries.add(Triple(docId, name, mime))
                    }
                }
            }
        } catch (_: Exception) {
        }
        entries.sortWith { a, b -> naturalCompareStrings(a.second, b.second) }
        return entries
    }

    private fun scanBookFolder(
        context: Context,
        treeUri: Uri,
        folderDocId: String,
        bookTitle: String,
        subPrefix: String,
        out: MutableList<AudiobookItem>,
        depth: Int
    ) {
        if (depth > 8) return
        val entries = queryFolderChildren(context, treeUri, folderDocId)
        val covKey = "book_${bookTitle.trim().lowercase()}"
        var fallbackImageUri: Uri? = null

        for ((docId, name, mime) in entries) {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                val nextSub = if (subPrefix.isNotBlank()) "$subPrefix • $name" else name
                scanBookFolder(context, treeUri, docId, bookTitle, nextSub, out, depth + 1)
            } else if (name.endsWith(".zip", ignoreCase = true)) {
                val zipBook = name.substringBeforeLast('.')
                out.addAll(scanZipUri(context, docUri, zipBook))
            } else if (isSupportedAudioFileName(name)) {
                if (!hasCachedCover(context, covKey)) {
                    val (_, embeddedPic) = extractEmbeddedMetaFromUri(context, docUri, wantCover = true)
                    if (embeddedPic != null && embeddedPic.isNotEmpty()) {
                        downscaleAndSaveCover(context, covKey, embeddedPic)
                    }
                }
                val ext = name.substringAfterLast('.', "mp3").lowercase()
                val leafTitle = name.substringBeforeLast('.').replace('_', ' ').trim()
                val fullChapterTitle = if (subPrefix.isNotBlank()) "$subPrefix — $leafTitle" else leafTitle
                val uriStr = docUri.toString()
                out.add(
                    AudiobookItem(
                        id = "and_file_${uriStr.hashCode()}",
                        title = fullChapterTitle.ifEmpty { name },
                        groupTitle = bookTitle,
                        sourceUri = uriStr,
                        zipEntryPath = "",
                        extension = ext,
                        coverKey = covKey
                    )
                )
            } else if (isSupportedImageFileName(name)) {
                val low = name.lowercase()
                if (fallbackImageUri == null || low.contains("cover") || low.contains("folder") || low.contains("front")) {
                    fallbackImageUri = docUri
                }
            }
        }

        if (!hasCachedCover(context, covKey) && fallbackImageUri != null) {
            try {
                context.contentResolver.openInputStream(fallbackImageUri)?.use { stream ->
                    val bytes = stream.readBytes()
                    if (bytes.isNotEmpty()) downscaleAndSaveCover(context, covKey, bytes)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun scanZipUri(context: Context, zipUri: Uri, zipGroupTitle: String): List<AudiobookItem> {
        val uriStr = zipUri.toString()
        val covKey = "book_${zipGroupTitle.trim().lowercase()}"
        for (charset in ZIP_CHARSETS) {
            val attemptList = mutableListOf<AudiobookItem>()
            var fallbackZipImage: ByteArray? = null
            var failedCharset = false
            try {
                val rawStream = context.contentResolver.openInputStream(zipUri) ?: return emptyList()
                ZipInputStream(BufferedInputStream(rawStream), charset).use { zis ->
                    while (true) {
                        val entry: ZipEntry = try {
                            zis.nextEntry ?: break
                        } catch (_: IllegalArgumentException) {
                            failedCharset = true
                            break
                        }
                        val entryName = entry.name.replace('\\', '/')
                        if (!entry.isDirectory && !entryName.contains("__MACOSX")) {
                            if (isSupportedAudioFileName(entryName)) {
                                val leaf = entryName.substringAfterLast('/')
                                val ext = leaf.substringAfterLast('.', "mp3").lowercase()
                                val cleanLeaf = leaf.substringBeforeLast('.').replace('_', ' ').trim()
                                val subDir = entryName.substringBeforeLast('/', "").substringAfterLast('/')
                                val chTitle = if (subDir.isNotEmpty() && !subDir.equals(zipGroupTitle, ignoreCase = true)) {
                                    "$subDir — $cleanLeaf"
                                } else {
                                    cleanLeaf
                                }
                                attemptList.add(
                                    AudiobookItem(
                                        id = "and_zip_${(uriStr + "::" + entryName).hashCode()}",
                                        title = chTitle.ifEmpty { leaf },
                                        groupTitle = zipGroupTitle,
                                        sourceUri = uriStr,
                                        zipEntryPath = entryName,
                                        extension = ext,
                                        coverKey = covKey
                                    )
                                )
                            } else if (isSupportedImageFileName(entryName) && fallbackZipImage == null) {
                                val out = ByteArrayOutputStream()
                                val buf = ByteArray(8192)
                                var n: Int
                                while (zis.read(buf).also { n = it } != -1) {
                                    out.write(buf, 0, n)
                                    if (out.size() > 6 * 1024 * 1024) break
                                }
                                if (out.size() > 0) fallbackZipImage = out.toByteArray()
                            }
                        }
                        zis.closeEntry()
                    }
                }
            } catch (_: IllegalArgumentException) {
                failedCharset = true
            } catch (_: Exception) {
            }
            if (!failedCharset) {
                val sorted = attemptList.sortedWith { a, b ->
                    naturalCompareStrings(a.zipEntryPath, b.zipEntryPath)
                }
                if (!hasCachedCover(context, covKey)) {
                    val firstChapterPic = sorted.firstOrNull()?.let { extractEmbeddedCoverFromZipAudio(context, it) }
                    val chosen = firstChapterPic ?: fallbackZipImage
                    if (chosen != null && chosen.isNotEmpty()) {
                        downscaleAndSaveCover(context, covKey, chosen)
                    }
                }
                return sorted
            }
        }
        return emptyList()
    }

    private fun extractImageEntryFromZip(context: Context, zipUri: Uri): ByteArray? {
        for (charset in ZIP_CHARSETS) {
            var charsetError = false
            try {
                val rawStream = context.contentResolver.openInputStream(zipUri) ?: return null
                ZipInputStream(BufferedInputStream(rawStream), charset).use { zis ->
                    while (true) {
                        val entry = try {
                            zis.nextEntry ?: break
                        } catch (_: IllegalArgumentException) {
                            charsetError = true
                            break
                        }
                        val name = entry.name.replace('\\', '/')
                        if (!entry.isDirectory && !name.contains("__MACOSX") && isSupportedImageFileName(name)) {
                            val out = ByteArrayOutputStream()
                            val buf = ByteArray(8192)
                            var n: Int
                            while (zis.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n)
                                if (out.size() > 6 * 1024 * 1024) break
                            }
                            if (out.size() > 0) return out.toByteArray()
                        }
                        zis.closeEntry()
                    }
                }
            } catch (_: IllegalArgumentException) {
                charsetError = true
            } catch (_: Exception) {
            }
            if (!charsetError) break
        }
        return null
    }

    fun resolvePlayableUri(context: Context, item: AudiobookItem): Uri? {
        if (item.zipEntryPath.isBlank()) {
            return try {
                Uri.parse(item.sourceUri)
            } catch (_: Exception) {
                null
            }
        }
        val cacheDir = File(context.cacheDir, "audiobook_zip_cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val safeExt = item.extension.ifBlank { "mp3" }.lowercase()
        val targetFile = File(cacheDir, "chapter_${item.id.hashCode().toUInt()}.$safeExt")
        if (targetFile.exists() && targetFile.length() > 0L) {
            return Uri.fromFile(targetFile)
        }
        cacheDir.listFiles()?.forEach { old ->
            if (old.name != targetFile.name) {
                try {
                    old.delete()
                } catch (_: Exception) {
                }
            }
        }

        val zipUri = try {
            Uri.parse(item.sourceUri)
        } catch (_: Exception) {
            return null
        }

        for (charset in ZIP_CHARSETS) {
            var charsetError = false
            try {
                val rawStream = context.contentResolver.openInputStream(zipUri) ?: return null
                ZipInputStream(BufferedInputStream(rawStream), charset).use { zis ->
                    while (true) {
                        val entry = try {
                            zis.nextEntry ?: break
                        } catch (_: IllegalArgumentException) {
                            charsetError = true
                            break
                        }
                        val normName = entry.name.replace('\\', '/')
                        if (!entry.isDirectory && normName == item.zipEntryPath) {
                            FileOutputStream(targetFile).use { fos ->
                                val buffer = ByteArray(16 * 1024)
                                var read: Int
                                while (zis.read(buffer).also { read = it } != -1) {
                                    fos.write(buffer, 0, read)
                                }
                                fos.flush()
                            }
                            return Uri.fromFile(targetFile)
                        }
                        zis.closeEntry()
                    }
                }
            } catch (_: IllegalArgumentException) {
                charsetError = true
            } catch (_: Exception) {
                try {
                    targetFile.delete()
                } catch (_: Exception) {
                }
            }
            if (!charsetError && targetFile.exists() && targetFile.length() > 0L) {
                return Uri.fromFile(targetFile)
            }
        }
        return null
    }

    fun clearZipCache(context: Context) {
        try {
            File(context.cacheDir, "audiobook_zip_cache").listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
