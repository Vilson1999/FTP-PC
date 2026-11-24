package com.example.ftp

import com.example.storage.VirtualStorageManager
import org.apache.ftpserver.ftplet.FileSystemFactory
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.FtpFile
import org.apache.ftpserver.ftplet.User
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class VirtualFileSystemFactory(
    private val storage: VirtualStorageManager,
    private val isReadOnlyProvider: () -> Boolean,
    private val bufferSizeKbProvider: () -> Int,
    private val slotProvider: () -> Int? = { null }
) : FileSystemFactory {

    override fun createFileSystemView(user: User): FileSystemView {
        return VirtualFileSystemView(
            storage = storage,
            isReadOnlyProvider = isReadOnlyProvider,
            bufferSizeKbProvider = bufferSizeKbProvider,
            slotProvider = slotProvider
        )
    }
}

class VirtualFileSystemView(
    private val storage: VirtualStorageManager,
    private val isReadOnlyProvider: () -> Boolean,
    private val bufferSizeKbProvider: () -> Int,
    private val slotProvider: () -> Int? = { null }
) : FileSystemView {

    private var currentDir: String = "/"

    override fun getHomeDirectory(): FtpFile = makeFile("/")

    override fun getWorkingDirectory(): FtpFile = makeFile(currentDir)

    override fun changeWorkingDirectory(dir: String): Boolean {
        val target = storage.normalizePath(dir.ifBlank { "/" }, currentDir)
        return if (storage.isDirectory(target, slotProvider())) {
            currentDir = target
            true
        } else {
            false
        }
    }

    override fun getFile(file: String): FtpFile {
        val normalized = storage.normalizePath(file, currentDir)
        return makeFile(normalized)
    }

    override fun isRandomAccessible(): Boolean = true

    override fun dispose() {
        // No per-view unmanaged resources
    }

    private fun makeFile(virtualPath: String): VirtualFtpFile {
        return VirtualFtpFile(
            virtualPath = virtualPath,
            storage = storage,
            isReadOnlyProvider = isReadOnlyProvider,
            bufferSizeKbProvider = bufferSizeKbProvider,
            slotProvider = slotProvider
        )
    }
}

class VirtualFtpFile(
    private val virtualPath: String,
    private val storage: VirtualStorageManager,
    private val isReadOnlyProvider: () -> Boolean,
    private val bufferSizeKbProvider: () -> Int,
    private val slotProvider: () -> Int? = { null }
) : FtpFile {

    override fun getAbsolutePath(): String = virtualPath

    override fun getName(): String {
        if (virtualPath == "/") return "/"
        return virtualPath.substringAfterLast('/')
    }

    override fun isHidden(): Boolean = name.startsWith(".")

    override fun isDirectory(): Boolean = storage.isDirectory(virtualPath, slotProvider())

    override fun isFile(): Boolean {
        val entry = storage.stat(virtualPath, slotProvider()) ?: return false
        return !entry.isDirectory
    }

    override fun doesExist(): Boolean = storage.exists(virtualPath, slotProvider())

    override fun isReadable(): Boolean = doesExist()

    override fun isWritable(): Boolean {
        if (isReadOnlyProvider()) return false
        val slot = slotProvider()
        if (virtualPath == "/") return storage.isRootDirectlyWritable(slot)
        val parentPath = virtualPath.substringBeforeLast('/', "").ifEmpty { "/" }
        if (parentPath == "/" && !storage.isRootDirectlyWritable(slot)) {
            // Cannot create loose files directly at VirtualRoot '/', only inside mounted roots like /QuickShare
            val existing = storage.stat(virtualPath, slot)
            return existing != null && existing.isDirectory
        }
        return storage.isDirectory(parentPath, slot)
    }

    override fun isRemovable(): Boolean {
        if (isReadOnlyProvider()) return false
        if (virtualPath == "/") return false
        val entry = storage.stat(virtualPath, slotProvider()) ?: return false
        return !entry.isVirtualMountRoot
    }

    override fun getOwnerName(): String = "android"

    override fun getGroupName(): String = "android"

    override fun getLinkCount(): Int = if (isDirectory) 3 else 1

    override fun getLastModified(): Long {
        return storage.stat(virtualPath, slotProvider())?.lastModifiedMillis ?: System.currentTimeMillis()
    }

    override fun setLastModified(time: Long): Boolean = true

    override fun getSize(): Long {
        return storage.stat(virtualPath, slotProvider())?.size ?: 0L
    }

    override fun getPhysicalFile(): Any = virtualPath

    override fun mkdir(): Boolean {
        if (isReadOnlyProvider()) return false
        return storage.makeDirectory(virtualPath, slotProvider())
    }

    override fun delete(): Boolean {
        if (isReadOnlyProvider()) return false
        val slot = slotProvider()
        return if (isDirectory) {
            storage.deleteDirectory(virtualPath, slot)
        } else {
            storage.deleteFile(virtualPath, slot)
        }
    }

    override fun move(destination: FtpFile): Boolean {
        if (isReadOnlyProvider()) return false
        return storage.moveOrRename(virtualPath, destination.absolutePath, slotProvider())
    }

    override fun listFiles(): List<FtpFile> {
        val entries = storage.listDirectory(virtualPath, slotProvider()) ?: return emptyList()
        return entries.map { entry ->
            VirtualFtpFile(
                virtualPath = entry.virtualPath,
                storage = storage,
                isReadOnlyProvider = isReadOnlyProvider,
                bufferSizeKbProvider = bufferSizeKbProvider,
                slotProvider = slotProvider
            )
        }
    }

    @Throws(IOException::class)
    override fun createOutputStream(offset: Long): OutputStream {
        if (isReadOnlyProvider()) {
            throw IOException("Server is in Read-Only mode")
        }
        val raw = storage.openOutputStream(virtualPath, append = offset > 0L, slot = slotProvider())
            ?: throw IOException("Cannot open output stream for $virtualPath")
        val bufSize = bufferSizeKbProvider().coerceIn(16, 512) * 1024
        return BufferedOutputStream(raw, bufSize)
    }

    @Throws(IOException::class)
    override fun createInputStream(offset: Long): InputStream {
        val raw = storage.openInputStream(virtualPath, offset = offset, slot = slotProvider())
            ?: throw IOException("Cannot open input stream for $virtualPath")
        val bufSize = bufferSizeKbProvider().coerceIn(16, 512) * 1024
        return BufferedInputStream(raw, bufSize)
    }
}
