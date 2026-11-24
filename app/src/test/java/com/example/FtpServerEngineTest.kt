package com.example

import com.example.data.AppSettings
import com.example.ftp.FtpServerEngine
import com.example.ftp.ServerRunState
import com.example.storage.VirtualStorageManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class FtpServerEngineTest {

    private lateinit var tempDir: File
    private lateinit var storage: VirtualStorageManager
    private lateinit var engine: FtpServerEngine
    private var testPort: Int = 21210

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("beamftp_test").toFile()
        storage = VirtualStorageManager(context = null, customQuickShareDir = tempDir)
        engine = FtpServerEngine(storage)
        testPort = ServerSocket(0).use { it.localPort }
    }

    @After
    fun tearDown() {
        engine.stop()
        tempDir.deleteRecursively()
    }

    @Test
    fun pathNormalization_preventsDirectoryTraversal() {
        assertEquals("/", storage.normalizePath("/../../.."))
        assertEquals("/etc/passwd", storage.normalizePath("/../../etc/passwd"))
        assertEquals("/QuickShare/notes.txt", storage.normalizePath("../QuickShare/./notes.txt", "/QuickShare"))
        assertEquals("/QuickShare/sub/file.bin", storage.normalizePath("sub\\file.bin", "/QuickShare"))
    }

    @Test
    fun ftpUploadListMoveDownloadAndDelete_endToEnd() {
        val settings = AppSettings(
            allowAnonymous = false,
            username = "pc",
            password = "secret",
            port = testPort
        )
        engine.start(settings)

        val deadline = System.currentTimeMillis() + 3000L
        while (engine.telemetry.value.runState != ServerRunState.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertEquals(ServerRunState.RUNNING, engine.telemetry.value.runState)

        Socket("127.0.0.1", testPort).use { ctrl ->
            ctrl.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(ctrl.getInputStream(), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(ctrl.getOutputStream(), StandardCharsets.UTF_8))

            assertTrue(reader.readLine().startsWith("220"))

            writer.write("USER pc\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("331"))

            writer.write("PASS wrong\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("530"))

            writer.write("USER pc\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("331"))

            writer.write("PASS secret\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("230"))

            writer.write("CWD /QuickShare\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("250"))

            writer.write("MKD TestFolder\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("257"))

            // Upload file via PASV
            val uploadPort = enterPasv(writer, reader)
            val dataUpload = Socket("127.0.0.1", uploadPort)
            writer.write("STOR /QuickShare/hello.txt\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("150"))
            dataUpload.getOutputStream().use { it.write("Hello Windows PC!".toByteArray(StandardCharsets.UTF_8)) }
            assertTrue(reader.readLine().startsWith("226"))

            // Move file into TestFolder
            writer.write("RNFR /QuickShare/hello.txt\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("350"))
            writer.write("RNTO /QuickShare/TestFolder/moved.txt\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("250"))

            // Download moved file via PASV
            val dlPort = enterPasv(writer, reader)
            val dataDl = Socket("127.0.0.1", dlPort)
            writer.write("RETR /QuickShare/TestFolder/moved.txt\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("150"))
            val content = dataDl.getInputStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }
            assertEquals("Hello Windows PC!", content)
            assertTrue(reader.readLine().startsWith("226"))

            // List directory via PASV
            val listPort = enterPasv(writer, reader)
            val dataList = Socket("127.0.0.1", listPort)
            writer.write("LIST /QuickShare/TestFolder\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("150"))
            val listing = dataList.getInputStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }
            assertTrue("Expected moved.txt in LIST output: $listing", listing.contains("moved.txt"))
            assertTrue(reader.readLine().startsWith("226"))

            // Delete file and folder
            writer.write("DELE /QuickShare/TestFolder/moved.txt\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("250"))
            writer.write("RMD /QuickShare/TestFolder\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("250"))
            assertFalse(storage.exists("/QuickShare/TestFolder"))

            writer.write("QUIT\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("221"))
        }
    }

    @Test
    fun readOnlyMode_blocksUploadsAndDeletes() {
        val settings = AppSettings(
            allowAnonymous = true,
            readOnly = true,
            port = testPort
        )
        engine.start(settings)

        val deadline = System.currentTimeMillis() + 3000L
        while (engine.telemetry.value.runState != ServerRunState.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertEquals(ServerRunState.RUNNING, engine.telemetry.value.runState)

        Socket("127.0.0.1", testPort).use { ctrl ->
            ctrl.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(ctrl.getInputStream(), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(ctrl.getOutputStream(), StandardCharsets.UTF_8))

            assertTrue(reader.readLine().startsWith("220"))
            writer.write("USER anonymous\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("331"))
            writer.write("PASS pc@test\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("230"))

            writer.write("MKD /QuickShare/Blocked\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("550"))

            val uploadPort = enterPasv(writer, reader)
            Socket("127.0.0.1", uploadPort).use {
                writer.write("STOR /QuickShare/blocked.txt\r\n"); writer.flush()
                assertTrue(reader.readLine().startsWith("550"))
            }

            writer.write("QUIT\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("221"))
        }
    }

    @Test
    fun liveSettingsToggle_switchesReadOnlyToReadWriteWithoutRestart() {
        val readOnlySettings = AppSettings(
            allowAnonymous = true,
            readOnly = true,
            port = testPort
        )
        engine.start(readOnlySettings)

        val deadline = System.currentTimeMillis() + 3000L
        while (engine.telemetry.value.runState != ServerRunState.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertEquals(ServerRunState.RUNNING, engine.telemetry.value.runState)

        // Toggle read-only OFF live
        engine.updateSettings(readOnlySettings.copy(readOnly = false))

        Socket("127.0.0.1", testPort).use { ctrl ->
            ctrl.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(ctrl.getInputStream(), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(ctrl.getOutputStream(), StandardCharsets.UTF_8))

            assertTrue(reader.readLine().startsWith("220"))
            writer.write("USER anonymous\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("331"))
            writer.write("PASS pc@test\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("230"))

            writer.write("MKD /QuickShare/LiveToggleDir\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("257"))

            writer.write("QUIT\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("221"))
        }
    }

    @Test
    fun perSlotDirectRoot_servesIndependentRootAtSlash() {
        val slot1Dir = Files.createTempDirectory("beamftp_slot1").toFile()
        val slot2Dir = Files.createTempDirectory("beamftp_slot2").toFile()
        try {
            File(slot1Dir, "internal_note.txt").writeText("slot1-data", StandardCharsets.UTF_8)
            File(slot2Dir, "sdcard_note.txt").writeText("slot2-data", StandardCharsets.UTF_8)

            val slotStorage = VirtualStorageManager(
                context = null,
                customQuickShareDir = tempDir,
                customSlotRootDirs = mapOf(1 to slot1Dir, 2 to slot2Dir)
            )
            val slotEngine = FtpServerEngine(slotStorage)
            val port = ServerSocket(0).use { it.localPort }

            val slot1Settings = AppSettings(selectedFtpSlot = 1, allowAnonymous = true, port = port)
            slotEngine.start(slot1Settings)
            val deadline = System.currentTimeMillis() + 3000L
            while (slotEngine.telemetry.value.runState != ServerRunState.RUNNING && System.currentTimeMillis() < deadline) {
                Thread.sleep(25)
            }
            assertEquals(ServerRunState.RUNNING, slotEngine.telemetry.value.runState)

            Socket("127.0.0.1", port).use { ctrl ->
                ctrl.soTimeout = 3000
                val reader = BufferedReader(InputStreamReader(ctrl.getInputStream(), StandardCharsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(ctrl.getOutputStream(), StandardCharsets.UTF_8))

                assertTrue(reader.readLine().startsWith("220"))
                writer.write("USER anonymous\r\n"); writer.flush()
                assertTrue(reader.readLine().startsWith("331"))
                writer.write("PASS pc@test\r\n"); writer.flush()
                assertTrue(reader.readLine().startsWith("230"))

                // Slot 1: upload directly at '/' and list '/'
                val upPort = enterPasv(writer, reader)
                val dataUp = Socket("127.0.0.1", upPort)
                writer.write("STOR /direct_upload.txt\r\n"); writer.flush()
                assertTrue(reader.readLine().startsWith("150"))
                dataUp.getOutputStream().use { it.write("DirectRoot!".toByteArray(StandardCharsets.UTF_8)) }
                assertTrue(reader.readLine().startsWith("226"))
                assertTrue(File(slot1Dir, "direct_upload.txt").exists())

                // Switch live to Slot 2 -> '/' now lists slot2Dir
                slotEngine.updateSettings(slot1Settings.copy(selectedFtpSlot = 2))
                val listPort = enterPasv(writer, reader)
                val dataList = Socket("127.0.0.1", listPort)
                writer.write("LIST /\r\n"); writer.flush()
                assertTrue(reader.readLine().startsWith("150"))
                val listing = dataList.getInputStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }
                assertTrue("Expected sdcard_note.txt in slot 2 LIST: $listing", listing.contains("sdcard_note.txt"))
                assertFalse("Should not list slot 1 file in slot 2: $listing", listing.contains("internal_note.txt"))
                assertTrue(reader.readLine().startsWith("226"))

                writer.write("QUIT\r\n"); writer.flush()
                assertTrue(reader.readLine().startsWith("221"))
            }

            slotEngine.stop()
        } finally {
            slot1Dir.deleteRecursively()
            slot2Dir.deleteRecursively()
        }
    }

    @Test
    fun twoFtpSlots_runSimultaneouslyOnDifferentPortsAndRoots() {
        val slot1Dir = Files.createTempDirectory("beamftp_concurrent_1").toFile()
        val slot2Dir = Files.createTempDirectory("beamftp_concurrent_2").toFile()
        try {
            File(slot1Dir, "internal_only.txt").writeText("from-slot-1", StandardCharsets.UTF_8)
            File(slot2Dir, "sdcard_only.txt").writeText("from-slot-2", StandardCharsets.UTF_8)

            val slotStorage = VirtualStorageManager(
                context = null,
                customQuickShareDir = tempDir,
                customSlotRootDirs = mapOf(1 to slot1Dir, 2 to slot2Dir)
            )
            val multiEngine = FtpServerEngine(slotStorage)
            val port1 = ServerSocket(0).use { it.localPort }
            val port2 = ServerSocket(0).use { it.localPort }
            val settings = AppSettings(allowAnonymous = true)

            multiEngine.startSlot(1, settings, customPort = port1)
            multiEngine.startSlot(2, settings, customPort = port2)

            val deadline = System.currentTimeMillis() + 4000L
            while (
                (multiEngine.slotTelemetries.value[1]?.runState != ServerRunState.RUNNING ||
                    multiEngine.slotTelemetries.value[2]?.runState != ServerRunState.RUNNING) &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(25)
            }

            assertEquals(ServerRunState.RUNNING, multiEngine.slotTelemetries.value[1]?.runState)
            assertEquals(ServerRunState.RUNNING, multiEngine.slotTelemetries.value[2]?.runState)
            assertTrue(multiEngine.hasAnyActiveSlot())

            // Client 1 connected to FTP 1 while Client 2 connected to FTP 2 concurrently
            Socket("127.0.0.1", port1).use { ctrl1 ->
                Socket("127.0.0.1", port2).use { ctrl2 ->
                    ctrl1.soTimeout = 3000
                    ctrl2.soTimeout = 3000
                    val reader1 = BufferedReader(InputStreamReader(ctrl1.getInputStream(), StandardCharsets.UTF_8))
                    val writer1 = BufferedWriter(OutputStreamWriter(ctrl1.getOutputStream(), StandardCharsets.UTF_8))
                    val reader2 = BufferedReader(InputStreamReader(ctrl2.getInputStream(), StandardCharsets.UTF_8))
                    val writer2 = BufferedWriter(OutputStreamWriter(ctrl2.getOutputStream(), StandardCharsets.UTF_8))

                    assertTrue(reader1.readLine().startsWith("220"))
                    assertTrue(reader2.readLine().startsWith("220"))

                    writer1.write("USER anonymous\r\n"); writer1.flush()
                    writer2.write("USER anonymous\r\n"); writer2.flush()
                    assertTrue(reader1.readLine().startsWith("331"))
                    assertTrue(reader2.readLine().startsWith("331"))

                    writer1.write("PASS pc1@test\r\n"); writer1.flush()
                    writer2.write("PASS pc2@test\r\n"); writer2.flush()
                    assertTrue(reader1.readLine().startsWith("230"))
                    assertTrue(reader2.readLine().startsWith("230"))

                    val dlPort1 = enterPasv(writer1, reader1)
                    val dlSock1 = Socket("127.0.0.1", dlPort1)
                    writer1.write("RETR /internal_only.txt\r\n"); writer1.flush()
                    assertTrue(reader1.readLine().startsWith("150"))
                    assertEquals("from-slot-1", dlSock1.getInputStream().use { it.readBytes().toString(StandardCharsets.UTF_8) })
                    assertTrue(reader1.readLine().startsWith("226"))

                    val dlPort2 = enterPasv(writer2, reader2)
                    val dlSock2 = Socket("127.0.0.1", dlPort2)
                    writer2.write("RETR /sdcard_only.txt\r\n"); writer2.flush()
                    assertTrue(reader2.readLine().startsWith("150"))
                    assertEquals("from-slot-2", dlSock2.getInputStream().use { it.readBytes().toString(StandardCharsets.UTF_8) })
                    assertTrue(reader2.readLine().startsWith("226"))

                    writer1.write("QUIT\r\n"); writer1.flush()
                    writer2.write("QUIT\r\n"); writer2.flush()
                }
            }

            // Stopping FTP 1 must leave FTP 2 running
            multiEngine.stopSlot(1)
            assertEquals(ServerRunState.STOPPED, multiEngine.slotTelemetries.value[1]?.runState)
            assertEquals(ServerRunState.RUNNING, multiEngine.slotTelemetries.value[2]?.runState)
            assertTrue(multiEngine.hasAnyActiveSlot())

            multiEngine.stopSlot(2)
            assertEquals(ServerRunState.STOPPED, multiEngine.slotTelemetries.value[2]?.runState)
            assertFalse(multiEngine.hasAnyActiveSlot())
        } finally {
            slot1Dir.deleteRecursively()
            slot2Dir.deleteRecursively()
        }
    }

    @Test
    fun startAll_runsAllFourFtpSlotsSimultaneously() {
        val dirs = (1..4).associateWith { Files.createTempDirectory("beamftp_all_$it").toFile() }
        try {
            dirs.forEach { (slot, dir) ->
                File(dir, "slot_${slot}.txt").writeText("content-$slot", StandardCharsets.UTF_8)
            }
            val slotStorage = VirtualStorageManager(
                context = null,
                customQuickShareDir = tempDir,
                customSlotRootDirs = dirs
            )
            val allEngine = FtpServerEngine(slotStorage)
            val ports = (1..4).associateWith { ServerSocket(0).use { sock -> sock.localPort } }
            val settings = AppSettings(allowAnonymous = true)

            allEngine.startAll(settings, customPorts = ports)

            val deadline = System.currentTimeMillis() + 5000L
            while ((1..4).any { allEngine.slotTelemetries.value[it]?.runState != ServerRunState.RUNNING } &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(25)
            }

            for (slot in 1..4) {
                assertEquals(ServerRunState.RUNNING, allEngine.slotTelemetries.value[slot]?.runState)
                Socket("127.0.0.1", ports.getValue(slot)).use { ctrl ->
                    ctrl.soTimeout = 3000
                    val reader = BufferedReader(InputStreamReader(ctrl.getInputStream(), StandardCharsets.UTF_8))
                    val writer = BufferedWriter(OutputStreamWriter(ctrl.getOutputStream(), StandardCharsets.UTF_8))
                    assertTrue(reader.readLine().startsWith("220"))
                    writer.write("USER anonymous\r\n"); writer.flush()
                    assertTrue(reader.readLine().startsWith("331"))
                    writer.write("PASS pc@test\r\n"); writer.flush()
                    assertTrue(reader.readLine().startsWith("230"))

                    val dlPort = enterPasv(writer, reader)
                    val dlSock = Socket("127.0.0.1", dlPort)
                    writer.write("RETR /slot_${slot}.txt\r\n"); writer.flush()
                    assertTrue(reader.readLine().startsWith("150"))
                    assertEquals("content-$slot", dlSock.getInputStream().use { it.readBytes().toString(StandardCharsets.UTF_8) })
                    assertTrue(reader.readLine().startsWith("226"))
                    writer.write("QUIT\r\n"); writer.flush()
                }
            }

            allEngine.stopAll()
            assertFalse(allEngine.hasAnyActiveSlot())
            for (slot in 1..4) {
                assertEquals(ServerRunState.STOPPED, allEngine.slotTelemetries.value[slot]?.runState)
            }
        } finally {
            dirs.values.forEach { it.deleteRecursively() }
        }
    }

    private fun enterPasv(writer: BufferedWriter, reader: BufferedReader): Int {
        writer.write("PASV\r\n")
        writer.flush()
        val resp = reader.readLine()
        assertTrue("Expected 227 PASV response, got: $resp", resp.startsWith("227"))
        val inside = resp.substringAfter("(").substringBefore(")")
        val parts = inside.split(",").map { it.trim().toInt() }
        return (parts[4] shl 8) or parts[5]
    }
}
