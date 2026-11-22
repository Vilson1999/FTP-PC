package com.example.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.example.data.FtpSlotStorage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

data class StorageEntry(
    val name: String,
    val virtualPath: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModifiedMillis: Long,
    val isVirtualMountRoot: Boolean = false
)

/**
 * Path-safe virtual filesystem mapping '/' to the active FTP slot's storage root
 * (or '/QuickShare' when running in virtual-root test mode).
 */
class VirtualStorageManager(
    private val context: Context?,
    private val customQuickShareDir: File? = null,
    private val customSlotRootDirs: Map<Int, File> = emptyMap()
) {

    @Volatile
    private var activeFtpSlot: Int = 1

    @Volatile
    private var slotStorages: Map<Int, FtpSlotStorage> = emptyMap()

    @Volatile
    var showHiddenFiles: Boolean = false

    @Volatile
    var allowRecursiveDelete: Boolean = true

    @Volatile
    var overwriteExisting: Boolean = true

    val quickShareDir: File by lazy {
        val dir = customQuickShareDir
            ?: context?.getExternalFilesDir("QuickShare")
            ?: File(context!!.filesDir, "QuickShare")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    private val resolverOrNull: ContentResolver?
        get() = context?.contentResolver

    fun updateSlotStorageConfig(slot: Int, storages: Map<Int, FtpSlotStorage>) {
        activeFtpSlot = slot.coerceIn(1, 4)
        slotStorages = storages
    }

    fun normalizePath(rawPath: String, currentDir: String = "/"): String {
        val unified = rawPath.replace('\\', '/').trim()
        val combined = if (unified.startsWith("/")) {
            unified
        } else {
            val base = if (currentDir.endsWith("/")) currentDir else "$currentDir/"
            base + unified
        }

        val segments = ArrayDeque<String>()
        for (part in combined.split('/')) {
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> if (segments.isNotEmpty()) segments.removeLast()
                part.contains('\u0000') -> Unit
                else -> segments.addLast(part)
            }
        }
        return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
    }

    private sealed interface ResolvedTarget {
        data object VirtualRoot : ResolvedTarget
        data class LocalTarget(val mountName: String, val root: File, val file: File, val virtualPath: String) : ResolvedTarget
        data class SafTarget(
            val mountName: String,
            val treeUri: Uri,
            val relativeSegments: List<String>,
            val virtualPath: String
        ) : ResolvedTarget
    }

    private sealed interface SlotDirectRoot {
        data class Local(val label: String, val dir: File) : SlotDirectRoot
        data class Saf(val label: String, val treeUri: Uri) : SlotDirectRoot
    }

    private fun resolveActiveDirectRoot(): SlotDirectRoot? {
        val slot = activeFtpSlot.coerceIn(1, 4)
        val slotConfig = slotStorages[slot]
        val configuredUri = slotConfig?.uriString
        if (!configuredUri.isNullOrBlank()) {
            val parsed = runCatching { Uri.parse(configuredUri) }.getOrNull()
            if (parsed != null) {
                val label = slotConfig.displayName?.takeIf { it.isNotBlank() } ?: "FTP $slot"
                return SlotDirectRoot.Saf(label, parsed)
            }
        }

        customSlotRootDirs[slot]?.let { customDir ->
            if (!customDir.exists()) customDir.mkdirs()
            return SlotDirectRoot.Local("FTP$slot", customDir)
        }

        // Preserve VirtualRoot (/QuickShare) when running unit tests with customQuickShareDir
        val appContext = context
        if (appContext == null || customQuickShareDir != null) {
            return null
        }

        return when (slot) {
            1 -> {
                val primaryPublic = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
                if (primaryPublic.isReadableDir()) {
                    SlotDirectRoot.Local("InternalStorage", primaryPublic!!)
                } else {
                    SlotDirectRoot.Local("InternalStorage", quickShareDir)
                }
            }
            2 -> {
                val volumeSdRoot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching {
                        val sm = appContext.getSystemService(StorageManager::class.java)
                        sm?.storageVolumes
                            ?.firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
                            ?.directory
                    }.getOrNull()
                } else null

                val sdAppDir = runCatching {
                    appContext.getExternalFilesDirs(null).drop(1).firstOrNull { it != null && it.exists() }
                }.getOrNull()
                val sdVolumeRoot = volumeSdRoot ?: sdAppDir?.parentFile?.parentFile?.parentFile?.parentFile
                when {
                    sdVolumeRoot.isReadableDir() -> SlotDirectRoot.Local("SDCard", sdVolumeRoot!!)
                    sdAppDir != null -> SlotDirectRoot.Local("SDCard", sdAppDir)
                    else -> SlotDirectRoot.Local("SDCard", File(quickShareDir, "SDCard").apply { if (!exists()) mkdirs() })
                }
            }
            3 -> {
                val publicDownloads = runCatching {
                    @Suppress("DEPRECATION")
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                }.getOrNull()
                if (publicDownloads != null && (publicDownloads.exists() || publicDownloads.mkdirs()) && publicDownloads.isReadableDir()) {
                    SlotDirectRoot.Local("Download", publicDownloads)
                } else {
                    val appDownloads = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: File(quickShareDir, "Download")
                    if (!appDownloads.exists()) appDownloads.mkdirs()
                    SlotDirectRoot.Local("Download", appDownloads)
                }
            }
            else -> SlotDirectRoot.Local("FTP$slot", File(quickShareDir, "FTP$slot").apply { if (!exists()) mkdirs() })
        }
    }

    private fun File?.isReadableDir(): Boolean {
        return this != null && exists() && isDirectory && canRead() && listFiles() != null
    }

    fun isRootDirectlyWritable(): Boolean = resolveActiveDirectRoot() != null

    private fun resolveLocalTarget(mountName: String, rootDir: File, relativeSegments: List<String>, normPath: String): ResolvedTarget? {
        var targetFile = rootDir
        for (seg in relativeSegments) {
            targetFile = File(targetFile, seg)
        }
        val canonicalRoot = rootDir.canonicalFile
        val canonicalTarget = targetFile.canonicalFile
        if (canonicalTarget != canonicalRoot &&
            !canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)
        ) {
            return null
        }
        return ResolvedTarget.LocalTarget(mountName, canonicalRoot, canonicalTarget, normPath)
    }

    private fun resolveTarget(virtualPath: String): ResolvedTarget? {
        val norm = normalizePath(virtualPath)
        val segments = if (norm == "/") emptyList() else norm.removePrefix("/").split('/').filter { it.isNotEmpty() }

        return when (val directRoot = resolveActiveDirectRoot()) {
            is SlotDirectRoot.Local -> resolveLocalTarget(directRoot.label, directRoot.dir, segments, norm)
            is SlotDirectRoot.Saf -> ResolvedTarget.SafTarget(directRoot.label, directRoot.treeUri, segments, norm)
            null -> {
                if (norm == "/" || segments.isEmpty()) return ResolvedTarget.VirtualRoot
                if (segments.first() != "QuickShare") return null
                resolveLocalTarget("QuickShare", quickShareDir, segments.drop(1), norm)
            }
        }
    }

    fun exists(virtualPath: String): Boolean = stat(virtualPath) != null

    fun isDirectory(virtualPath: String): Boolean = stat(virtualPath)?.isDirectory == true

    fun stat(virtualPath: String): StorageEntry? {
        val target = resolveTarget(virtualPath) ?: return null
        return when (target) {
            ResolvedTarget.VirtualRoot -> StorageEntry(
                name = "",
                virtualPath = "/",
                isDirectory = true,
                size = 0L,
                lastModifiedMillis = System.currentTimeMillis(),
                isVirtualMountRoot = true
            )
            is ResolvedTarget.LocalTarget -> {
                if (!target.file.exists()) return null
                StorageEntry(
                    name = if (target.file == target.root) target.mountName else target.file.name,
                    virtualPath = target.virtualPath,
                    isDirectory = target.file.isDirectory,
                    size = if (target.file.isDirectory) 0L else target.file.length(),
                    lastModifiedMillis = target.file.lastModified(),
                    isVirtualMountRoot = target.file == target.root
                )
            }
            is ResolvedTarget.SafTarget -> {
                val docId = findSafDocumentId(target.treeUri, target.relativeSegments) ?: return null
                querySafDocEntry(
                    treeUri = target.treeUri,
                    docId = docId,
                    virtualPath = target.virtualPath,
                    overrideName = if (target.relativeSegments.isEmpty()) target.mountName else null,
                    isMountRoot = target.relativeSegments.isEmpty()
                ) ?: if (target.relativeSegments.isEmpty()) {
                    StorageEntry(
                        name = target.mountName,
                        virtualPath = target.virtualPath,
                        isDirectory = true,
                        size = 0L,
                        lastModifiedMillis = System.currentTimeMillis(),
                        isVirtualMountRoot = true
                    )
                } else null
            }
        }
    }

    fun listDirectory(virtualPath: String): List<StorageEntry>? {
        val target = resolveTarget(virtualPath) ?: return null
        return when (target) {
            ResolvedTarget.VirtualRoot -> listOf(
                StorageEntry(
                    name = "QuickShare",
                    virtualPath = "/QuickShare",
                    isDirectory = true,
                    size = 0L,
                    lastModifiedMillis = quickShareDir.lastModified(),
                    isVirtualMountRoot = true
                )
            )
            is ResolvedTarget.LocalTarget -> {
                if (!target.file.exists() || !target.file.isDirectory) return null
                val children = target.file.listFiles() ?: emptyArray()
                children
                    .filter { showHiddenFiles || !it.name.startsWith(".") }
                    .map { child ->
                        val childPath = if (target.virtualPath == "/") "/${child.name}" else "${target.virtualPath}/${child.name}"
                        StorageEntry(
                            name = child.name,
                            virtualPath = childPath,
                            isDirectory = child.isDirectory,
                            size = if (child.isDirectory) 0L else child.length(),
                            lastModifiedMillis = child.lastModified()
                        )
                    }
                    .sortedWith(compareByDescending<StorageEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            }
            is ResolvedTarget.SafTarget -> {
                val parentDocId = findSafDocumentId(target.treeUri, target.relativeSegments) ?: return null
                if (target.relativeSegments.isNotEmpty()) {
                    val parentEntry = querySafDocEntry(target.treeUri, parentDocId, target.virtualPath) ?: return null
                    if (!parentEntry.isDirectory) return null
                }
                listSafChildren(target.treeUri, parentDocId, target.virtualPath)
                    .filter { showHiddenFiles || !it.name.startsWith(".") }
                    .sortedWith(compareByDescending<StorageEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            }
        }
    }

    fun openInputStream(virtualPath: String, offset: Long = 0L): InputStream? {
        val target = resolveTarget(virtualPath) ?: return null
        val stream = when (target) {
            ResolvedTarget.VirtualRoot -> return null
            is ResolvedTarget.LocalTarget -> {
                if (!target.file.exists() || target.file.isDirectory) return null
                FileInputStream(target.file)
            }
            is ResolvedTarget.SafTarget -> {
                val res = resolverOrNull ?: return null
                val docId = findSafDocumentId(target.treeUri, target.relativeSegments) ?: return null
                val docUri = DocumentsContract.buildDocumentUriUsingTree(target.treeUri, docId)
                res.openInputStream(docUri) ?: return null
            }
        }
        if (offset > 0L) {
            var remaining = offset
            while (remaining > 0L) {
                val skipped = stream.skip(remaining)
                if (skipped <= 0L) {
                    if (stream.read() == -1) break
                    remaining--
                } else {
                    remaining -= skipped
                }
            }
        }
        return stream
    }

    fun openOutputStream(virtualPath: String, append: Boolean = false): OutputStream? {
        val norm = normalizePath(virtualPath)
        val parentPath = norm.substringBeforeLast('/', "").ifEmpty { "/" }
        val fileName = norm.substringAfterLast('/')
        if (fileName.isBlank()) return null

        val parentTarget = resolveTarget(parentPath) ?: return null
        return when (parentTarget) {
            ResolvedTarget.VirtualRoot -> null
            is ResolvedTarget.LocalTarget -> {
                if (!parentTarget.file.exists() || !parentTarget.file.isDirectory) return null
                val dest = File(parentTarget.file, fileName)
                if (dest.exists() && !append && !overwriteExisting) return null
                FileOutputStream(dest, append)
            }
            is ResolvedTarget.SafTarget -> {
                val resolver = resolverOrNull ?: return null
                val parentDocId = findSafDocumentId(parentTarget.treeUri, parentTarget.relativeSegments) ?: return null
                val existingDocId = findSafChildId(parentTarget.treeUri, parentDocId, fileName)
                val targetDocUri = if (existingDocId != null) {
                    if (!append && !overwriteExisting) return null
                    DocumentsContract.buildDocumentUriUsingTree(parentTarget.treeUri, existingDocId)
                } else {
                    val parentUri = DocumentsContract.buildDocumentUriUsingTree(parentTarget.treeUri, parentDocId)
                    val mime = guessMimeType(fileName)
                    DocumentsContract.createDocument(resolver, parentUri, mime, fileName) ?: return null
                }
                val mode = if (append) "wa" else "wt"
                runCatching { resolver.openOutputStream(targetDocUri, mode) }.getOrElse {
                    runCatching { resolver.openOutputStream(targetDocUri, "w") }.getOrNull()
                }
            }
        }
    }

    fun makeDirectory(virtualPath: String): Boolean {
        val norm = normalizePath(virtualPath)
        val parentPath = norm.substringBeforeLast('/', "").ifEmpty { "/" }
        val dirName = norm.substringAfterLast('/')
        if (dirName.isBlank()) return false

        val parentTarget = resolveTarget(parentPath) ?: return false
        return when (parentTarget) {
            ResolvedTarget.VirtualRoot -> false
            is ResolvedTarget.LocalTarget -> {
                if (!parentTarget.file.isDirectory) return false
                val targetDir = File(parentTarget.file, dirName)
                if (targetDir.exists()) return false
                targetDir.mkdir()
            }
            is ResolvedTarget.SafTarget -> {
                val res = resolverOrNull ?: return false
                val parentDocId = findSafDocumentId(parentTarget.treeUri, parentTarget.relativeSegments) ?: return false
                if (findSafChildId(parentTarget.treeUri, parentDocId, dirName) != null) return false
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(parentTarget.treeUri, parentDocId)
                runCatching {
                    DocumentsContract.createDocument(
                        res,
                        parentUri,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        dirName
                    ) != null
                }.getOrDefault(false)
            }
        }
    }

    fun deleteFile(virtualPath: String): Boolean {
        val target = resolveTarget(virtualPath) ?: return false
        return when (target) {
            ResolvedTarget.VirtualRoot -> false
            is ResolvedTarget.LocalTarget -> {
                if (target.file == target.root || !target.file.exists() || target.file.isDirectory) return false
                target.file.delete()
            }
            is ResolvedTarget.SafTarget -> {
                val res = resolverOrNull ?: return false
                if (target.relativeSegments.isEmpty()) return false
                val docId = findSafDocumentId(target.treeUri, target.relativeSegments) ?: return false
                val entry = querySafDocEntry(target.treeUri, docId, target.virtualPath) ?: return false
                if (entry.isDirectory) return false
                val docUri = DocumentsContract.buildDocumentUriUsingTree(target.treeUri, docId)
                runCatching { DocumentsContract.deleteDocument(res, docUri) }.getOrDefault(false)
            }
        }
    }

    fun deleteDirectory(virtualPath: String): Boolean {
        val target = resolveTarget(virtualPath) ?: return false
        return when (target) {
            ResolvedTarget.VirtualRoot -> false
            is ResolvedTarget.LocalTarget -> {
                if (target.file == target.root || !target.file.exists() || !target.file.isDirectory) return false
                val children = target.file.listFiles() ?: emptyArray()
                if (children.isNotEmpty() && !allowRecursiveDelete) return false
                target.file.deleteRecursively()
            }
            is ResolvedTarget.SafTarget -> {
                val res = resolverOrNull ?: return false
                if (target.relativeSegments.isEmpty()) return false
                val docId = findSafDocumentId(target.treeUri, target.relativeSegments) ?: return false
                val entry = querySafDocEntry(target.treeUri, docId, target.virtualPath) ?: return false
                if (!entry.isDirectory) return false
                val children = listSafChildren(target.treeUri, docId, target.virtualPath)
                if (children.isNotEmpty() && !allowRecursiveDelete) return false
                val docUri = DocumentsContract.buildDocumentUriUsingTree(target.treeUri, docId)
                runCatching { DocumentsContract.deleteDocument(res, docUri) }.getOrDefault(false)
            }
        }
    }

    fun moveOrRename(fromVirtualPath: String, toVirtualPath: String): Boolean {
        val fromNorm = normalizePath(fromVirtualPath)
        val toNorm = normalizePath(toVirtualPath)
        if (fromNorm == "/" || toNorm == "/" || fromNorm == toNorm) return false

        val fromTarget = resolveTarget(fromNorm) ?: return false
        val toParentPath = toNorm.substringBeforeLast('/', "").ifEmpty { "/" }
        val toName = toNorm.substringAfterLast('/')
        if (toName.isBlank()) return false
        val toParentTarget = resolveTarget(toParentPath) ?: return false
        if (toParentTarget is ResolvedTarget.VirtualRoot) return false

        if (fromTarget is ResolvedTarget.LocalTarget && fromTarget.file == fromTarget.root) return false
        if (fromTarget is ResolvedTarget.SafTarget && fromTarget.relativeSegments.isEmpty()) return false
        if (exists(toNorm) && !overwriteExisting) return false

        if (fromTarget is ResolvedTarget.LocalTarget &&
            toParentTarget is ResolvedTarget.LocalTarget &&
            fromTarget.root == toParentTarget.root
        ) {
            if (!fromTarget.file.exists() || !toParentTarget.file.isDirectory) return false
            val destFile = File(toParentTarget.file, toName)
            if (destFile.exists()) {
                if (overwriteExisting) destFile.deleteRecursively() else return false
            }
            if (fromTarget.file.renameTo(destFile)) return true
        }

        if (fromTarget is ResolvedTarget.SafTarget &&
            toParentTarget is ResolvedTarget.SafTarget &&
            fromTarget.treeUri == toParentTarget.treeUri
        ) {
            val fromParentSegments = fromTarget.relativeSegments.dropLast(1)
            val srcDocId = findSafDocumentId(fromTarget.treeUri, fromTarget.relativeSegments) ?: return false
            val srcUri = DocumentsContract.buildDocumentUriUsingTree(fromTarget.treeUri, srcDocId)
            val resolver = resolverOrNull ?: return false

            if (fromParentSegments == toParentTarget.relativeSegments) {
                val renamed = runCatching {
                    DocumentsContract.renameDocument(resolver, srcUri, toName) != null
                }.getOrDefault(false)
                if (renamed) return true
            } else {
                val srcParentDocId = findSafDocumentId(fromTarget.treeUri, fromParentSegments)
                val dstParentDocId = findSafDocumentId(toParentTarget.treeUri, toParentTarget.relativeSegments)
                if (srcParentDocId != null && dstParentDocId != null) {
                    val srcParentUri = DocumentsContract.buildDocumentUriUsingTree(fromTarget.treeUri, srcParentDocId)
                    val dstParentUri = DocumentsContract.buildDocumentUriUsingTree(toParentTarget.treeUri, dstParentDocId)
                    val movedUri = runCatching {
                        DocumentsContract.moveDocument(resolver, srcUri, srcParentUri, dstParentUri)
                    }.getOrNull()
                    if (movedUri != null) {
                        val oldName = fromNorm.substringAfterLast('/')
                        if (oldName != toName) {
                            runCatching { DocumentsContract.renameDocument(resolver, movedUri, toName) }
                        }
                        return true
                    }
                }
            }
        }

        return copyRecursive(fromNorm, toNorm) && deleteAny(fromNorm)
    }

    private fun deleteAny(virtualPath: String): Boolean {
        val entry = stat(virtualPath) ?: return false
        return if (entry.isDirectory) deleteDirectory(virtualPath) else deleteFile(virtualPath)
    }

    private fun copyRecursive(srcPath: String, dstPath: String): Boolean {
        val srcEntry = stat(srcPath) ?: return false
        if (srcEntry.isDirectory) {
            if (!exists(dstPath) && !makeDirectory(dstPath)) return false
            val children = listDirectory(srcPath) ?: return false
            for (child in children) {
                if (!copyRecursive(child.virtualPath, "$dstPath/${child.name}")) return false
            }
            return true
        } else {
            val input = openInputStream(srcPath) ?: return false
            val output = openOutputStream(dstPath, append = false) ?: run {
                input.close()
                return false
            }
            return try {
                input.use { ins -> output.use { outs -> ins.copyTo(outs, bufferSize = 64 * 1024) } }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun findSafDocumentId(treeUri: Uri, relativeSegments: List<String>): String? {
        var currentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        for (segment in relativeSegments) {
            currentId = findSafChildId(treeUri, currentId, segment) ?: return null
        }
        return currentId
    }

    private fun findSafChildId(treeUri: Uri, parentDocId: String, childName: String): String? {
        val res = resolverOrNull ?: return null
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        res.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (idIdx >= 0 && nameIdx >= 0 && cursor.getString(nameIdx) == childName) {
                    return cursor.getString(idIdx)
                }
            }
        }
        return null
    }

    private fun querySafDocEntry(
        treeUri: Uri,
        docId: String,
        virtualPath: String,
        overrideName: String? = null,
        isMountRoot: Boolean = false
    ): StorageEntry? {
        val res = resolverOrNull ?: return null
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        return runCatching {
            res.query(docUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = overrideName ?: cursor.getStringOrDefault(
                        cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        "item"
                    )
                    val mime = cursor.getStringOrDefault(
                        cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE),
                        ""
                    )
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = cursor.getLongOrDefault(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE), 0L)
                    val mod = cursor.getLongOrDefault(
                        cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                        System.currentTimeMillis()
                    )
                    StorageEntry(
                        name = name,
                        virtualPath = virtualPath,
                        isDirectory = isDir,
                        size = if (isDir) 0L else size,
                        lastModifiedMillis = mod,
                        isVirtualMountRoot = isMountRoot
                    )
                } else null
            }
        }.getOrNull()
    }

    private fun listSafChildren(treeUri: Uri, parentDocId: String, parentVirtualPath: String): List<StorageEntry> {
        val res = resolverOrNull ?: return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val entries = mutableListOf<StorageEntry>()
        runCatching {
            res.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val name = cursor.getStringOrDefault(nameIdx, "")
                    if (name.isEmpty()) continue
                    val mime = cursor.getStringOrDefault(mimeIdx, "")
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = cursor.getLongOrDefault(sizeIdx, 0L)
                    val mod = cursor.getLongOrDefault(modIdx, System.currentTimeMillis())
                    val childPath = if (parentVirtualPath == "/") "/$name" else "$parentVirtualPath/$name"
                    entries.add(
                        StorageEntry(
                            name = name,
                            virtualPath = childPath,
                            isDirectory = isDir,
                            size = if (isDir) 0L else size,
                            lastModifiedMillis = mod
                        )
                    )
                }
            }
        }
        return entries
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun android.database.Cursor.getStringOrDefault(idx: Int, default: String): String {
        if (idx < 0 || isNull(idx)) return default
        return getString(idx) ?: default
    }

    private fun android.database.Cursor.getLongOrDefault(idx: Int, default: Long): Long {
        if (idx < 0 || isNull(idx)) return default
        return getLong(idx)
    }
}
