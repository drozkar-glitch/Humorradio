package cz.taximares.radiopm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

internal object AudiobookStorageAndroid {

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
                results.add(
                    AudiobookItem(
                        id = "and_file_${uriStr.hashCode()}",
                        title = cleanTitle.ifEmpty { displayName },
                        groupTitle = "",
                        sourceUri = uriStr,
                        zipEntryPath = "",
                        extension = ext
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
        val results = mutableListOf<AudiobookItem>()
        scanFolderRecursive(
            context = context,
            treeUri = treeUri,
            parentDocId = rootDocId,
            groupLabel = rootName,
            out = results,
            depth = 0
        )
        return results.sortedWith { a, b ->
            val g = naturalCompareStrings(a.groupTitle, b.groupTitle)
            if (g != 0) g else naturalCompareStrings(a.title, b.title)
        }
    }

    private fun scanFolderRecursive(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        groupLabel: String,
        out: MutableList<AudiobookItem>,
        depth: Int
    ) {
        if (depth > 8) return
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
            return
        }

        entries.sortWith { a, b -> naturalCompareStrings(a.second, b.second) }

        for ((docId, name, mime) in entries) {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                val nextGroup = if (groupLabel.isNotBlank()) "$groupLabel • $name" else name
                scanFolderRecursive(context, treeUri, docId, nextGroup, out, depth + 1)
            } else if (name.endsWith(".zip", ignoreCase = true)) {
                val zipGroup = if (groupLabel.isNotBlank()) {
                    "$groupLabel • ${name.substringBeforeLast('.')}"
                } else {
                    name.substringBeforeLast('.')
                }
                out.addAll(scanZipUri(context, docUri, zipGroup))
            } else if (isSupportedAudioFileName(name)) {
                val ext = name.substringAfterLast('.', "mp3").lowercase()
                val cleanTitle = name.substringBeforeLast('.').replace('_', ' ').trim()
                val uriStr = docUri.toString()
                out.add(
                    AudiobookItem(
                        id = "and_file_${uriStr.hashCode()}",
                        title = cleanTitle.ifEmpty { name },
                        groupTitle = groupLabel,
                        sourceUri = uriStr,
                        zipEntryPath = "",
                        extension = ext
                    )
                )
            }
        }
    }

    private fun scanZipUri(context: Context, zipUri: Uri, zipGroupTitle: String): List<AudiobookItem> {
        val uriStr = zipUri.toString()
        for (charset in ZIP_CHARSETS) {
            val attemptList = mutableListOf<AudiobookItem>()
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
                        if (!entry.isDirectory && !entryName.contains("__MACOSX") && isSupportedAudioFileName(entryName)) {
                            val leaf = entryName.substringAfterLast('/')
                            val ext = leaf.substringAfterLast('.', "mp3").lowercase()
                            val cleanTitle = leaf.substringBeforeLast('.').replace('_', ' ').trim()
                            val subDir = entryName.substringBeforeLast('/', "").substringAfterLast('/')
                            val group = if (subDir.isNotEmpty() && !subDir.equals(zipGroupTitle, ignoreCase = true)) {
                                "$zipGroupTitle • $subDir"
                            } else {
                                zipGroupTitle
                            }
                            attemptList.add(
                                AudiobookItem(
                                    id = "and_zip_${(uriStr + "::" + entryName).hashCode()}",
                                    title = cleanTitle.ifEmpty { leaf },
                                    groupTitle = group,
                                    sourceUri = uriStr,
                                    zipEntryPath = entryName,
                                    extension = ext
                                )
                            )
                        }
                        zis.closeEntry()
                    }
                }
            } catch (_: IllegalArgumentException) {
                failedCharset = true
            } catch (_: Exception) {
            }
            if (!failedCharset) {
                return attemptList.sortedWith { a, b ->
                    naturalCompareStrings(a.zipEntryPath, b.zipEntryPath)
                }
            }
        }
        return emptyList()
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
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
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
            val cacheDir = File(context.cacheDir, "audiobook_zip_cache")
            cacheDir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
