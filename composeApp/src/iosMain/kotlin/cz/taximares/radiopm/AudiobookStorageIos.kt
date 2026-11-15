package cz.taximares.radiopm

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
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
import platform.posix.memcpy
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion

@OptIn(ExperimentalForeignApi::class)
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

    private fun ensureAudiobooksDir(): String {
        val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        val docs = (paths.firstOrNull() as? String) ?: NSTemporaryDirectory()
        val dir = "$docs/audiobooks_store"
        NSFileManager.defaultManager.createDirectoryAtPath(
            dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
        return dir
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
                    dispatch_async(dispatch_get_main_queue()) {
                        onResult(imported)
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
                val folderUrl = didPickDocumentsAtURLs.firstOrNull() as? NSURL
                if (folderUrl == null) {
                    onResult(emptyList())
                    return
                }
                dispatch_async(dispatch_get_global_queue(0, 0u)) {
                    val accessed = folderUrl.startAccessingSecurityScopedResource()
                    val collected = mutableListOf<NSURL>()
                    try {
                        val enumerator = NSFileManager.defaultManager.enumeratorAtURL(
                            folderUrl,
                            includingPropertiesForKeys = null,
                            options = 0u,
                            errorHandler = null
                        )
                        while (true) {
                            val nextObj = enumerator?.nextObject() as? NSURL ?: break
                            val name = nextObj.lastPathComponent ?: continue
                            if (isSupportedAudioFileName(name) || name.endsWith(".zip", ignoreCase = true)) {
                                collected.add(nextObj)
                            }
                        }
                        val imported = importPickedUrls(collected, folderUrl.lastPathComponent.orEmpty())
                        dispatch_async(dispatch_get_main_queue()) {
                            onResult(imported)
                        }
                    } finally {
                        if (accessed) {
                            folderUrl.stopAccessingSecurityScopedResource()
                        }
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

    private fun importPickedUrls(urls: List<NSURL>, defaultGroup: String = ""): List<AudiobookItem> {
        val storeDir = ensureAudiobooksDir()
        val fm = NSFileManager.defaultManager
        val results = mutableListOf<AudiobookItem>()

        for (url in urls) {
            val origName = url.lastPathComponent ?: continue
            val safeFileName = origName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val destPath = "$storeDir/${origName.hashCode().toUInt()}_$safeFileName"
            if (!fm.fileExistsAtPath(destPath)) {
                val srcPath = url.path
                if (srcPath != null) {
                    fm.copyItemAtPath(srcPath, toPath = destPath, error = null)
                }
            }
            if (!fm.fileExistsAtPath(destPath)) continue

            if (origName.endsWith(".zip", ignoreCase = true)) {
                val zipGroup = origName.substringBeforeLast('.')
                results.addAll(scanZipArchive(destPath, zipGroup))
            } else if (isSupportedAudioFileName(origName)) {
                val ext = origName.substringAfterLast('.', "mp3").lowercase()
                val title = origName.substringBeforeLast('.').replace('_', ' ').trim()
                val itemId = "ios_file_${destPath.hashCode()}"
                results.add(
                    AudiobookItem(
                        id = itemId,
                        title = title.ifEmpty { origName },
                        groupTitle = defaultGroup,
                        sourceUri = destPath,
                        zipEntryPath = "",
                        extension = ext
                    )
                )
            }
        }
        return results.sortedWith { a, b ->
            val gCmp = naturalCompareStrings(a.groupTitle, b.groupTitle)
            if (gCmp != 0) gCmp else naturalCompareStrings(a.title, b.title)
        }
    }

    private fun decodeZipFileName(bytes: ByteArray, isUtf8Flag: Boolean): String {
        if (isUtf8Flag) {
            return bytes.decodeToString()
        }
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
        ((b[off].toLong() and 0xFFL)) or
            ((b[off + 1].toLong() and 0xFFL) shl 8) or
            ((b[off + 2].toLong() and 0xFFL) shl 16) or
            ((b[off + 3].toLong() and 0xFFL) shl 24)

    private fun loadFileBytes(path: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        val len = data.length.toInt()
        if (len <= 0) return null
        val bytes = ByteArray(len)
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
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

        for (idx in 0 until totalEntries) {
            if (cdPos + 46 > bytes.size) break
            if (readLe32(bytes, cdPos) != 0x02014B50L) break
            val flags = readLe16(bytes, cdPos + 8)
            val nameLen = readLe16(bytes, cdPos + 28)
            val extraLen = readLe16(bytes, cdPos + 30)
            val commentLen = readLe16(bytes, cdPos + 32)
            if (cdPos + 46 + nameLen > bytes.size) break

            val isEncrypted = (flags and 0x01) != 0
            val isUtf8 = (flags and 0x800) != 0
            val entryName = decodeZipFileName(bytes.copyOfRange(cdPos + 46, cdPos + 46 + nameLen), isUtf8)
            if (!isEncrypted && !entryName.endsWith("/") && !entryName.contains("__MACOSX") && isSupportedAudioFileName(entryName)) {
                val cleanLeaf = entryName.substringAfterLast('/').substringBeforeLast('.')
                val ext = entryName.substringAfterLast('.', "mp3").lowercase()
                val subFolder = entryName.substringBeforeLast('/', "").substringAfterLast('/')
                val group = if (subFolder.isNotEmpty() && !subFolder.equals(zipTitle, ignoreCase = true)) {
                    "$zipTitle • $subFolder"
                } else {
                    zipTitle
                }
                found.add(
                    AudiobookItem(
                        id = "ios_zip_${(zipPath + "::" + entryName).hashCode()}",
                        title = cleanLeaf.ifEmpty { entryName },
                        groupTitle = group,
                        sourceUri = zipPath,
                        zipEntryPath = entryName,
                        extension = ext
                    )
                )
            }
            cdPos += 46 + nameLen + extraLen + commentLen
        }
        return found.sortedWith { a, b -> naturalCompareStrings(a.zipEntryPath, b.zipEntryPath) }
    }

    fun resolvePlayablePath(item: AudiobookItem): String? {
        if (item.zipEntryPath.isEmpty()) {
            return if (NSFileManager.defaultManager.fileExistsAtPath(item.sourceUri)) item.sourceUri else null
        }
        val cacheDir = "${NSTemporaryDirectory()}audiobook_zip_cache"
        val fm = NSFileManager.defaultManager
        fm.createDirectoryAtPath(cacheDir, withIntermediateDirectories = true, attributes = null, error = null)
        val outPath = "$cacheDir/current_chapter.${item.extension.ifEmpty { "mp3" }}"
        fm.removeItemAtPath(outPath, error = null)

        val bytes = loadFileBytes(item.sourceUri) ?: return null
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
        if (eocdOffset < 0) return null
        val totalEntries = readLe16(bytes, eocdOffset + 10)
        var cdPos = readLe32(bytes, eocdOffset + 16).toInt()

        for (idx in 0 until totalEntries) {
            if (cdPos + 46 > bytes.size || readLe32(bytes, cdPos) != 0x02014B50L) break
            val flags = readLe16(bytes, cdPos + 8)
            val method = readLe16(bytes, cdPos + 10)
            val compSize = readLe32(bytes, cdPos + 20).toInt()
            val uncompSize = readLe32(bytes, cdPos + 24).toInt()
            val nameLen = readLe16(bytes, cdPos + 28)
            val extraLen = readLe16(bytes, cdPos + 30)
            val commentLen = readLe16(bytes, cdPos + 32)
            val localOff = readLe32(bytes, cdPos + 42).toInt()
            val entryName = decodeZipFileName(bytes.copyOfRange(cdPos + 46, cdPos + 46 + nameLen), (flags and 0x800) != 0)

            if (entryName == item.zipEntryPath && localOff + 30 <= bytes.size) {
                val lNameLen = readLe16(bytes, localOff + 26)
                val lExtraLen = readLe16(bytes, localOff + 28)
                val dataStart = localOff + 30 + lNameLen + lExtraLen
                if (dataStart + compSize > bytes.size) return null
                val rawSlice = bytes.copyOfRange(dataStart, dataStart + compSize)
                val extracted = when (method) {
                    0 -> rawSlice
                    8 -> inflateRawDeflate(rawSlice, uncompSize)
                    else -> null
                } ?: return null
                extracted.usePinned { pinned ->
                    val nsData = NSData.create(bytes = pinned.addressOf(0), length = extracted.size.toULong())
                    nsData.writeToFile(outPath, atomically = true)
                }
                return outPath
            }
            cdPos += 46 + nameLen + extraLen + commentLen
        }
        return null
    }

    private fun inflateRawDeflate(compressed: ByteArray, expectedSize: Int): ByteArray? {
        if (compressed.isEmpty()) return ByteArray(0)
        val outBytes = ByteArray(expectedSize.coerceAtLeast(compressed.size * 2).coerceAtLeast(4096))
        val uIn = compressed.asUByteArray()
        val uOut = outBytes.asUByteArray()
        return memScoped {
            val strm = alloc<z_stream>()
            if (inflateInit2_(strm.ptr, -15, zlibVersion(), sizeOf<z_stream>().toInt()) != Z_OK) {
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
