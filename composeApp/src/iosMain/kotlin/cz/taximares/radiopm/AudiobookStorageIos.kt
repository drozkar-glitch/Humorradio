package cz.taximares.radiopm

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.ftello
import platform.posix.memcpy
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion

@OptIn(ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class)
internal object AudiobookStorageIos {

    private var activeDelegate: NSObject? = null

    private val CP852_HIGH_TABLE = charArrayOf(
        '\u00C7', '\u00FC', '\u00E9', '\u00E2', '\u00E4', '\u016F', '\u0107', '\u00E7',
        '\u0142', '\u00EB', '\u0150', '\u0151', '\u00EE', '\u0179', '\u00C4', '\u0106',
        '\u00C9', '\u0139', '\u013A', '\u00F4', '\u00F6', '\u013D', '\u013E', '\u015A',
        '\u015B', '\u00D6', '\u00DC', '\u0164', '\u0165', '\u0141', '\u00D7', '\u010D',
        '\u00E1', '\u00ED', '\u00F3', '\u00FA', '\u0104', '\u0105', '\u017D', '\u017E',
        '\u0118', '\u0119', '\u00AC', '\u017A', '\u010C', '\u015F', '\u00AB', '\u00BB',
        '\u2591', '\u2592', '\u2593', '\u2502', '\u2524', '\u00C1', '\u00C2', '\u011A',
        '\u015E', '\u2563', '\u2551', '\u2557', '\u255D', '\u017B', '\u017C', '\u2510',
        '\u2514', '\u2534', '\u252C', '\u251C', '\u2500', '\u253C', '\u0102', '\u0103',
        '\u255A', '\u2554', '\u2569', '\u2566', '\u2560', '\u2550', '\u256C', '\u00A4',
        '\u0111', '\u0110', '\u010E', '\u00CB', '\u010F', '\u0147', '\u00CD', '\u00CE',
        '\u011B', '\u2518', '\u250C', '\u2588', '\u2584', '\u0162', '\u016E', '\u2580',
        '\u00D3', '\u00DF', '\u00D4', '\u0143', '\u0144', '\u0148', '\u0160', '\u0161',
        '\u0154', '\u00DA', '\u0155', '\u0170', '\u00FD', '\u00DD', '\u0163', '\u00B4',
        '\u00AD', '\u02DD', '\u02DB', '\u02C7', '\u02D8', '\u00A7', '\u00F7', '\u00B8',
        '\u00B0', '\u00A8', '\u02D9', '\u0171', '\u0158', '\u0159', '\u25A0', '\u00A0'
    )

    private fun topViewController(): UIViewController? {
        val app = UIApplication.sharedApplication
        val window = app.keyWindow ?: (app.windows.firstOrNull() as? UIWindow)
        var vc = window?.rootViewController
        while (vc?.presentedViewController != null) {
            vc = vc.presentedViewController
        }
        return vc
    }

    private fun docsRootDir(): String {
        val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        return (paths.firstOrNull() as? String) ?: NSTemporaryDirectory()
    }

    private fun ensureAudiobooksDir(): String {
        val dir = "${docsRootDir()}/audiobooks_store"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        return dir
    }

    private fun ensureCoversDir(): String {
        val dir = "${docsRootDir()}/audiobook_covers"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        return dir
    }

    private fun coverCacheFilePath(coverKey: String): String {
        return "${ensureCoversDir()}/cov_${coverKey.hashCode().toUInt()}.img"
    }

    private fun saveCoverToCache(coverKey: String, bytes: ByteArray) {
        if (coverKey.isBlank() || bytes.isEmpty()) return
        val path = coverCacheFilePath(coverKey)
        bytes.usePinned { pinned ->
            val nsData = NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            nsData.writeToFile(path, atomically = true)
        }
    }

    fun loadOrExtractCoverBytes(item: AudiobookItem): ByteArray? {
        val key = item.effectiveCoverKey()
        val cachedPath = coverCacheFilePath(key)
        if (NSFileManager.defaultManager.fileExistsAtPath(cachedPath)) {
            val existing = loadFileBytes(cachedPath)
            if (existing != null && existing.isNotEmpty()) return existing
        }
        val extracted = if (item.zipEntryPath.isNotEmpty()) {
            extractZipImageOrFirstAudioCover(item)
        } else {
            extractAudioMetaAndCover(item.sourceUri).second
        }
        if (extracted != null && extracted.isNotEmpty()) {
            saveCoverToCache(key, extracted)
        }
        return extracted
    }

    fun pickFilesOrZip(onResult: (List<AudiobookItem>) -> Unit) {
        val vc = topViewController() ?: run {
            onResult(emptyList())
            return
        }
        val types = listOf("public.audio", "com.pkware.zip-archive", "public.zip-archive", "public.item")
        val picker = UIDocumentPickerViewController(
            documentTypes = types,
            inMode = UIDocumentPickerMode.UIDocumentPickerModeImport
        )
        picker.allowsMultipleSelection = true
        val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                activeDelegate = null
                val urls = didPickDocumentsAtURLs.mapNotNull { it as? NSURL }
                dispatch_async(dispatch_get_global_queue(0, 0u)) {
                    val imported = importPickedUrls(urls)
                    dispatch_async(dispatch_get_main_queue()) { onResult(imported) }
                }
            }

            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                activeDelegate = null
                onResult(emptyList())
            }
        }
        activeDelegate = delegate
        picker.delegate = delegate
        vc.presentViewController(picker, animated = true, completion = null)
    }

    fun pickFolder(onResult: (List<AudiobookItem>) -> Unit) {
        val vc = topViewController() ?: run {
            onResult(emptyList())
            return
        }
        val picker = UIDocumentPickerViewController(
            documentTypes = listOf("public.folder"),
            inMode = UIDocumentPickerMode.UIDocumentPickerModeOpen
        )
        val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                activeDelegate = null
                val folderUrl = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: run {
                    onResult(emptyList())
                    return
                }
                dispatch_async(dispatch_get_global_queue(0, 0u)) {
                    val accessed = folderUrl.startAccessingSecurityScopedResource()
                    try {
                        val rootPath = folderUrl.path.orEmpty()
                        val rootName = folderUrl.lastPathComponent.orEmpty()
                        val audioEntries = mutableListOf<Pair<NSURL, String>>()
                        val folderImages = LinkedHashMap<String, NSURL>()
                        val enumerator = NSFileManager.defaultManager.enumeratorAtURL(
                            folderUrl,
                            includingPropertiesForKeys = null,
                            options = 0u,
                            errorHandler = null
                        )
                        while (true) {
                            val nextUrl = enumerator?.nextObject() as? NSURL ?: break
                            val name = nextUrl.lastPathComponent ?: continue
                            if (name.startsWith(".")) continue
                            val fullPath = nextUrl.path.orEmpty()
                            val rel = fullPath.removePrefix(rootPath).trimStart('/')
                            val firstSub = rel.substringBefore('/', "")
                            val bookGroup = if (rel.contains('/') && firstSub.isNotEmpty()) firstSub else rootName
                            if (isSupportedAudioFileName(name) || name.endsWith(".zip", ignoreCase = true)) {
                                audioEntries.add(nextUrl to bookGroup)
                            } else if (isSupportedImageFileName(name)) {
                                val prev = folderImages[bookGroup]?.lastPathComponent?.lowercase().orEmpty()
                                val low = name.lowercase()
                                val isPreferred = low.contains("cover") || low.contains("folder") || low.contains("front")
                                if (!folderImages.containsKey(bookGroup) || (isPreferred && !prev.contains("cover"))) {
                                    folderImages[bookGroup] = nextUrl
                                }
                            }
                        }
                        for ((grp, imgUrl) in folderImages) {
                            val imgPath = imgUrl.path ?: continue
                            val bytes = loadFileBytes(imgPath)
                            if (bytes != null && bytes.isNotEmpty()) {
                                saveCoverToCache("book_${grp.trim().lowercase()}", bytes)
                            }
                        }
                        val imported = importGroupedUrls(audioEntries)
                        dispatch_async(dispatch_get_main_queue()) { onResult(imported) }
                    } finally {
                        if (accessed) folderUrl.stopAccessingSecurityScopedResource()
                    }
                }
            }

            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                activeDelegate = null
                onResult(emptyList())
            }
        }
        activeDelegate = delegate
        picker.delegate = delegate
        vc.presentViewController(picker, animated = true, completion = null)
    }

    private fun importPickedUrls(urls: List<NSURL>): List<AudiobookItem> {
        return importGroupedUrls(urls.map { it to "" })
    }

    private fun importGroupedUrls(entries: List<Pair<NSURL, String>>): List<AudiobookItem> {
        val storeDir = ensureAudiobooksDir()
        val fm = NSFileManager.defaultManager
        val results = mutableListOf<AudiobookItem>()

        for ((url, presetGroup) in entries) {
            val origName = url.lastPathComponent ?: continue
            val safeFileName = origName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val destPath = "$storeDir/${(origName + presetGroup).hashCode().toUInt()}_$safeFileName"
            if (!fm.fileExistsAtPath(destPath)) {
                val srcPath = url.path
                if (srcPath != null) fm.copyItemAtPath(srcPath, toPath = destPath, error = null)
            }
            if (!fm.fileExistsAtPath(destPath)) continue

            if (origName.endsWith(".zip", ignoreCase = true)) {
                val zipGroup = origName.substringBeforeLast('.')
                results.addAll(scanZipArchive(destPath, zipGroup))
            } else if (isSupportedAudioFileName(origName)) {
                val ext = origName.substringAfterLast('.', "mp3").lowercase()
                val title = origName.substringBeforeLast('.').replace('_', ' ').trim()
                val itemId = "ios_file_${destPath.hashCode()}"
                val (albumTag, coverBytes) = extractAudioMetaAndCover(destPath)
                val finalGroup = presetGroup.ifBlank { albumTag }
                val covKey = if (finalGroup.isNotBlank()) "book_${finalGroup.trim().lowercase()}" else "single_$itemId"
                if (coverBytes != null && coverBytes.isNotEmpty()) {
                    saveCoverToCache(covKey, coverBytes)
                }
                results.add(
                    AudiobookItem(
                        id = itemId,
                        title = title.ifEmpty { origName },
                        groupTitle = finalGroup,
                        sourceUri = destPath,
                        zipEntryPath = "",
                        extension = ext,
                        coverKey = covKey
                    )
                )
            }
        }
        return results.sortedWith { a, b ->
            val gCmp = naturalCompareStrings(a.groupTitle, b.groupTitle)
            if (gCmp != 0) gCmp else naturalCompareStrings(a.title, b.title)
        }
    }

    private fun extractAudioMetaAndCover(path: String): Pair<String, ByteArray?> {
        val fp = fopen(path, "rb") ?: return "" to null
        try {
            if (fseeko(fp, 0L, SEEK_END) != 0) return "" to null
            val fileLen = ftello(fp)
            if (fileLen < 16L) return "" to null

            fun readAt(offset: Long, count: Int): ByteArray? {
                if (offset < 0L || count <= 0 || offset + count > fileLen) return null
                if (fseeko(fp, offset, SEEK_SET) != 0) return null
                val buf = ByteArray(count)
                val got = buf.usePinned { p -> fread(p.addressOf(0), 1uL, count.toULong(), fp).toInt() }
                return if (got == count) buf else null
            }

            val head12 = readAt(0L, 12) ?: return "" to null
            if (head12[0] == 'I'.code.toByte() && head12[1] == 'D'.code.toByte() && head12[2] == '3'.code.toByte()) {
                val ver = head12[3].toInt() and 0xFF
                val tagSize = ((head12[6].toInt() and 0x7F) shl 21) or
                    ((head12[7].toInt() and 0x7F) shl 14) or
                    ((head12[8].toInt() and 0x7F) shl 7) or
                    (head12[9].toInt() and 0x7F)
                val toRead = minOf(tagSize.toLong(), fileLen - 10L, 3L * 1024L * 1024L).toInt()
                val tagBuf = readAt(10L, toRead) ?: return "" to null
                return parseId3Tag(tagBuf, ver)
            }
            if (head12[0] == 'f'.code.toByte() && head12[1] == 'L'.code.toByte() &&
                head12[2] == 'a'.code.toByte() && head12[3] == 'C'.code.toByte()
            ) {
                var pos = 4L
                while (pos + 4L <= fileLen) {
                    val bh = readAt(pos, 4) ?: break
                    val isLast = (bh[0].toInt() and 0x80) != 0
                    val bType = bh[0].toInt() and 0x7F
                    val bLen = ((bh[1].toInt() and 0xFF) shl 16) or ((bh[2].toInt() and 0xFF) shl 8) or (bh[3].toInt() and 0xFF)
                    if (bType == 6 && bLen in 32..(4 * 1024 * 1024)) {
                        val pic = readAt(pos + 4L, bLen)
                        val img = pic?.let { extractEmbeddedImageSignature(it) }
                        if (img != null) return "" to img
                    }
                    pos += 4L + bLen.toLong()
                    if (isLast) break
                }
            }
            if (head12[4] == 'f'.code.toByte() && head12[5] == 't'.code.toByte() &&
                head12[6] == 'y'.code.toByte() && head12[7] == 'p'.code.toByte()
            ) {
                var off = 0L
                while (off + 8L <= fileLen) {
                    val ah = readAt(off, 8) ?: break
                    var atomSize = readBe32U(ah, 0)
                    val type = ah.decodeToString(4, 8)
                    var hdrLen = 8L
                    if (atomSize == 1L) {
                        val ext = readAt(off + 8L, 8) ?: break
                        atomSize = (readBe32U(ext, 0) shl 32) or readBe32U(ext, 4)
                        hdrLen = 16L
                    } else if (atomSize == 0L) {
                        atomSize = fileLen - off
                    }
                    if (atomSize < hdrLen) break
                    if (type == "moov") {
                        val moovPayload = (atomSize - hdrLen).coerceAtMost(6L * 1024L * 1024L).toInt()
                        val moovBytes = readAt(off + hdrLen, moovPayload)
                        if (moovBytes != null) return parseMp4Moov(moovBytes)
                        break
                    }
                    off += atomSize
                }
            }
            return "" to null
        } finally {
            fclose(fp)
        }
    }

    private fun readBe32U(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFFL) shl 24) or
            ((b[off + 1].toLong() and 0xFFL) shl 16) or
            ((b[off + 2].toLong() and 0xFFL) shl 8) or
            (b[off + 3].toLong() and 0xFFL)

    private fun extractEmbeddedImageSignature(data: ByteArray, startFrom: Int = 0): ByteArray? {
        for (i in startFrom until (data.size - 8)) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = data[i + 1].toInt() and 0xFF
            val b2 = data[i + 2].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
                return data.copyOfRange(i, data.size)
            }
            if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && (data[i + 3].toInt() and 0xFF) == 0x47) {
                return data.copyOfRange(i, data.size)
            }
        }
        return null
    }

    private fun parseId3Tag(buf: ByteArray, version: Int): Pair<String, ByteArray?> {
        var album = ""
        var cover: ByteArray? = null
        var p = 0
        val hdrSize = if (version == 2) 6 else 10
        while (p + hdrSize <= buf.size) {
            if (buf[p].toInt() == 0) break
            val id: String
            val fSize: Int
            if (version == 2) {
                id = buf.decodeToString(p, p + 3)
                fSize = ((buf[p + 3].toInt() and 0xFF) shl 16) or ((buf[p + 4].toInt() and 0xFF) shl 8) or (buf[p + 5].toInt() and 0xFF)
            } else {
                id = buf.decodeToString(p, p + 4)
                fSize = if (version == 4) {
                    ((buf[p + 4].toInt() and 0x7F) shl 21) or ((buf[p + 5].toInt() and 0x7F) shl 14) or
                        ((buf[p + 6].toInt() and 0x7F) shl 7) or (buf[p + 7].toInt() and 0x7F)
                } else {
                    readBe32U(buf, p + 4).toInt()
                }
            }
            if (fSize <= 0 || p + hdrSize + fSize > buf.size) break
            val slice = buf.copyOfRange(p + hdrSize, p + hdrSize + fSize)
            if ((id == "TALB" || id == "TAL") && album.isEmpty() && slice.size > 1) {
                album = decodeId3Text(slice)
            } else if ((id == "APIC" || id == "PIC") && cover == null && slice.size > 16) {
                cover = extractEmbeddedImageSignature(slice, 1)
            }
            if (album.isNotEmpty() && cover != null) break
            p += hdrSize + fSize
        }
        return album to cover
    }

    private fun decodeId3Text(slice: ByteArray): String {
        val enc = slice[0].toInt() and 0xFF
        val body = slice.copyOfRange(1, slice.size)
        val raw = when (enc) {
            1, 2 -> {
                val start = if (body.size >= 2 && ((body[0].toInt() and 0xFF) == 0xFF || (body[0].toInt() and 0xFF) == 0xFE)) 2 else 0
                val le = body.size >= 2 && (body[0].toInt() and 0xFF) == 0xFF
                val sb = StringBuilder()
                var i = start
                while (i + 1 < body.size) {
                    val ch = if (le) ((body[i].toInt() and 0xFF) or ((body[i + 1].toInt() and 0xFF) shl 8))
                    else (((body[i].toInt() and 0xFF) shl 8) or (body[i + 1].toInt() and 0xFF))
                    if (ch == 0) break
                    sb.append(ch.toChar())
                    i += 2
                }
                sb.toString()
            }
            else -> body.decodeToString().trimEnd('\u0000')
        }
        return raw.trim()
    }

    private fun parseMp4Moov(moov: ByteArray): Pair<String, ByteArray?> {
        var album = ""
        var cover: ByteArray? = null
        fun walk(start: Int, end: Int) {
            var pos = start
            while (pos + 8 <= end) {
                val sz = readBe32U(moov, pos).toInt()
                if (sz < 8 || pos + sz > end) break
                val b0 = moov[pos + 4].toInt() and 0xFF
                val tail3 = moov.decodeToString(pos + 5, pos + 8)
                val fullType = if (b0 in 32..126) "${b0.toChar()}$tail3" else ""
                when {
                    fullType == "udta" || fullType == "ilst" -> walk(pos + 8, pos + sz)
                    fullType == "meta" && pos + 12 <= pos + sz -> walk(pos + 12, pos + sz)
                    fullType == "covr" && cover == null -> {
                        if (pos + 24 < pos + sz) {
                            cover = extractEmbeddedImageSignature(moov.copyOfRange(pos + 16, pos + sz))
                        }
                    }
                    b0 == 0xA9 && tail3 == "alb" && album.isEmpty() -> {
                        if (pos + 24 <= pos + sz) {
                            val dSz = readBe32U(moov, pos + 8).toInt()
                            if (dSz > 16 && pos + 8 + dSz <= pos + sz) {
                                album = moov.copyOfRange(pos + 24, pos + 8 + dSz).decodeToString().trim()
                            }
                        }
                    }
                }
                pos += sz
            }
        }
        walk(0, moov.size)
        return album to cover
    }

    private fun decodeZipFileName(bytes: ByteArray, isUtf8Flag: Boolean): String {
        if (isUtf8Flag) return bytes.decodeToString()
        val utfAttempt = try {
            val s = bytes.decodeToString()
            if (!s.contains('\uFFFD')) s else null
        } catch (_: Exception) {
            null
        }
        if (utfAttempt != null) return utfAttempt
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x80) sb.append(v.toChar()) else sb.append(CP852_HIGH_TABLE[v - 0x80])
        }
        return sb.toString()
    }

    private fun readLe16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readLe32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFFL) or
            ((b[off + 1].toLong() and 0xFFL) shl 8) or
            ((b[off + 2].toLong() and 0xFFL) shl 16) or
            ((b[off + 3].toLong() and 0xFFL) shl 24)

    private fun loadFileBytes(path: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        val len = data.length.toInt()
        if (len <= 0) return null
        val bytes = ByteArray(len)
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        return bytes
    }

    private fun scanZipArchive(zipPath: String, zipTitle: String): List<AudiobookItem> {
        val bytes = loadFileBytes(zipPath) ?: return emptyList()
        var eocdOffset = -1
        val minSearch = (bytes.size - 65557).coerceAtLeast(0)
        for (i in (bytes.size - 22) downTo minSearch) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte() &&
                bytes[i + 2] == 0x05.toByte() && bytes[i + 3] == 0x06.toByte()
            ) {
                eocdOffset = i
                break
            }
        }
        if (eocdOffset < 0) return emptyList()
        val totalEntries = readLe16(bytes, eocdOffset + 10)
        var cdPos = readLe32(bytes, eocdOffset + 16).toInt()
        val found = mutableListOf<AudiobookItem>()
        val covKey = "book_${zipTitle.trim().lowercase()}"
        var bestImageEntry: String? = null

        for (idx in 0 until totalEntries) {
            if (cdPos + 46 > bytes.size || readLe32(bytes, cdPos) != 0x02014B50L) break
            val flags = readLe16(bytes, cdPos + 8)
            val nameLen = readLe16(bytes, cdPos + 28)
            val extraLen = readLe16(bytes, cdPos + 30)
            val commentLen = readLe16(bytes, cdPos + 32)
            if (cdPos + 46 + nameLen > bytes.size) break

            val isEncrypted = (flags and 0x01) != 0
            val entryName = decodeZipFileName(bytes.copyOfRange(cdPos + 46, cdPos + 46 + nameLen), (flags and 0x800) != 0)
            if (!isEncrypted && !entryName.endsWith("/") && !entryName.contains("__MACOSX")) {
                if (isSupportedAudioFileName(entryName)) {
                    val cleanLeaf = entryName.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').trim()
                    val ext = entryName.substringAfterLast('.', "mp3").lowercase()
                    val subFolder = entryName.substringBeforeLast('/', "").substringAfterLast('/')
                    val chTitle = if (subFolder.isNotEmpty() && !subFolder.equals(zipTitle, ignoreCase = true)) {
                        "$subFolder — $cleanLeaf"
                    } else {
                        cleanLeaf
                    }
                    found.add(
                        AudiobookItem(
                            id = "ios_zip_${(zipPath + "::" + entryName).hashCode()}",
                            title = chTitle.ifEmpty { entryName },
                            groupTitle = zipTitle,
                            sourceUri = zipPath,
                            zipEntryPath = entryName,
                            extension = ext,
                            coverKey = covKey
                        )
                    )
                } else if (isSupportedImageFileName(entryName)) {
                    val low = entryName.lowercase()
                    if (bestImageEntry == null || low.contains("cover") || low.contains("folder") || low.contains("front")) {
                        bestImageEntry = entryName
                    }
                }
            }
            cdPos += 46 + nameLen + extraLen + commentLen
        }
        if (bestImageEntry != null) {
            extractZipEntryRawBytes(bytes, bestImageEntry)?.let { saveCoverToCache(covKey, it) }
        }
        return found.sortedWith { a, b -> naturalCompareStrings(a.zipEntryPath, b.zipEntryPath) }
    }

    private fun extractZipImageOrFirstAudioCover(item: AudiobookItem): ByteArray? {
        val playable = resolvePlayablePath(item) ?: return null
        return extractAudioMetaAndCover(playable).second
    }

    private fun extractZipEntryRawBytes(zipBytes: ByteArray, targetEntry: String): ByteArray? {
        var eocdOffset = -1
        val minSearch = (zipBytes.size - 65557).coerceAtLeast(0)
        for (i in (zipBytes.size - 22) downTo minSearch) {
            if (zipBytes[i] == 0x50.toByte() && zipBytes[i + 1] == 0x4B.toByte() &&
                zipBytes[i + 2] == 0x05.toByte() && zipBytes[i + 3] == 0x06.toByte()
            ) {
                eocdOffset = i
                break
            }
        }
        if (eocdOffset < 0) return null
        val totalEntries = readLe16(zipBytes, eocdOffset + 10)
        var cdPos = readLe32(zipBytes, eocdOffset + 16).toInt()
        for (idx in 0 until totalEntries) {
            if (cdPos + 46 > zipBytes.size || readLe32(zipBytes, cdPos) != 0x02014B50L) break
            val flags = readLe16(zipBytes, cdPos + 8)
            val method = readLe16(zipBytes, cdPos + 10)
            val compSize = readLe32(zipBytes, cdPos + 20).toInt()
            val uncompSize = readLe32(zipBytes, cdPos + 24).toInt()
            val nameLen = readLe16(zipBytes, cdPos + 28)
            val extraLen = readLe16(zipBytes, cdPos + 30)
            val commentLen = readLe16(zipBytes, cdPos + 32)
            val localOff = readLe32(zipBytes, cdPos + 42).toInt()
            val entryName = decodeZipFileName(zipBytes.copyOfRange(cdPos + 46, cdPos + 46 + nameLen), (flags and 0x800) != 0)
            if (entryName == targetEntry && localOff + 30 <= zipBytes.size) {
                val lNameLen = readLe16(zipBytes, localOff + 26)
                val lExtraLen = readLe16(zipBytes, localOff + 28)
                val dataStart = localOff + 30 + lNameLen + lExtraLen
                if (dataStart + compSize > zipBytes.size) return null
                val rawSlice = zipBytes.copyOfRange(dataStart, dataStart + compSize)
                return when (method) {
                    0 -> rawSlice
                    8 -> inflateRawDeflate(rawSlice, uncompSize)
                    else -> null
                }
            }
            cdPos += 46 + nameLen + extraLen + commentLen
        }
        return null
    }

    fun resolvePlayablePath(item: AudiobookItem): String? {
        if (item.zipEntryPath.isEmpty()) {
            return if (NSFileManager.defaultManager.fileExistsAtPath(item.sourceUri)) item.sourceUri else null
        }
        val cacheDir = "${NSTemporaryDirectory()}audiobook_zip_cache"
        val fm = NSFileManager.defaultManager
        fm.createDirectoryAtPath(cacheDir, true, null, null)
        val outPath = "$cacheDir/chapter_${item.id.hashCode().toUInt()}.${item.extension.ifEmpty { "mp3" }}"
        if (fm.fileExistsAtPath(outPath)) return outPath

        val bytes = loadFileBytes(item.sourceUri) ?: return null
        val extracted = extractZipEntryRawBytes(bytes, item.zipEntryPath) ?: return null
        extracted.usePinned { pinned ->
            val nsData = NSData.create(bytes = pinned.addressOf(0), length = extracted.size.toULong())
            nsData.writeToFile(outPath, atomically = true)
        }
        return outPath
    }

    private fun inflateRawDeflate(compressed: ByteArray, expectedSize: Int): ByteArray? {
        if (compressed.isEmpty()) return ByteArray(0)
        val capacity = if (expectedSize > 0) expectedSize + 4096 else (compressed.size * 4).coerceAtLeast(65536)
        val outBytes = ByteArray(capacity)
        val uIn = compressed.asUByteArray()
        val uOut = outBytes.asUByteArray()
        val zlibVerStr = zlibVersion()?.toKString()
        return memScoped {
            val strm = alloc<z_stream>()
            if (inflateInit2_(strm.ptr, -15, zlibVerStr, sizeOf<z_stream>().toInt()) != Z_OK) {
                return@memScoped null
            }
            try {
                uIn.usePinned { inPin ->
                    uOut.usePinned { outPin ->
                        strm.next_in = inPin.addressOf(0)
                        strm.avail_in = uIn.size.toUInt()
                        strm.next_out = outPin.addressOf(0)
                        strm.avail_out = uOut.size.toUInt()
                        val ret = inflate(strm.ptr, Z_NO_FLUSH)
                        if (ret != Z_STREAM_END && ret != Z_OK) return@memScoped null
                        val total = strm.total_out.toInt()
                        outBytes.copyOfRange(0, total.coerceAtMost(outBytes.size))
                    }
                }
            } finally {
                inflateEnd(strm.ptr)
            }
        }
    }
}
