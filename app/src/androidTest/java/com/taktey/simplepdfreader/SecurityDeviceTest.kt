package com.taktey.simplepdfreader

import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun sample(): File {
        val file = File(context.filesDir, "shared/device-fixture/sample.pdf")
        file.parentFile!!.mkdirs()
        InstrumentationRegistry.getInstrumentation().context.assets.open("sample.pdf").use { input -> file.outputStream().use(input::copyTo) }
        return file
    }
    private fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    @Test fun importsDeduplicatesSharesAndRejectsExpiredOrWritableShares() = runBlocking {
        val store = DocumentStore(context)
        store.clearDocuments()
        val source = sample()
        val imported = store.import(uri(source))
        assertArrayEquals(source.readBytes(), imported.readBytes())
        assertEquals(imported, store.import(uri(source)))
        val copy = store.prepareShare(imported)
        val link = uri(copy)
        assertArrayEquals(source.readBytes(), context.contentResolver.openInputStream(link)!!.use { it.readBytes() })
        try { context.contentResolver.openFileDescriptor(link, "w")?.close(); fail("Write accepted") } catch (_: SecurityException) { }
        try { uri(imported); fail("Private document exposed") } catch (_: IllegalArgumentException) { }
        copy.setLastModified(System.currentTimeMillis() - DocumentStore.SHARE_AGE - 2000)
        try { context.contentResolver.openInputStream(link)?.close(); fail("Expired share accepted") } catch (_: java.io.FileNotFoundException) { }
        store.clearDocuments()
    }

    @Test fun cancellationInterruptsBlockedProviderAndNextImportWorks() = runBlocking {
        val store = DocumentStore(context)
        val start = SystemClock.elapsedRealtime()
        val job = launch(Dispatchers.Default) { store.import(Uri.parse("content://${context.packageName}.blocked-test/document")) }
        try {
            withTimeout(8000) { while (BlockedTestProvider.writer == null) delay(50) }
            withTimeout(5000) { job.cancelAndJoin() }
            assertTrue(SystemClock.elapsedRealtime() - start < 13000)
            val source = sample()
            assertTrue(store.import(uri(source)).isFile)
        } finally { job.cancel(); BlockedTestProvider.writer?.close(); BlockedTestProvider.writer = null }
    }

    @Test fun watchdogKillsStalledIsolatedWorkerAndUiProcessSurvives() = runBlocking {
        val channel = WorkerChannel(context, WatchdogTestService::class.java)
        val start = SystemClock.elapsedRealtime()
        try {
            channel.connect()
            try { channel.request(WorkerProtocol.SIZE); fail("Stalled operation returned") }
            catch (_: WorkerFailure) { }
            catch (_: WorkerTimeout) { }
            assertTrue(SystemClock.elapsedRealtime() - start in 14000..22000)
            assertEquals(context.applicationInfo.uid, android.os.Process.myUid())
        } finally { channel.close() }
    }

    @Test fun protectionToggleSetsWindowFlagAndSurvivesRecreation() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val privacy = context.getSharedPreferences("privacy", android.content.Context.MODE_PRIVATE)
        privacy.edit().putBoolean("protectScreen", false).commit()
        try {
            androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var observedNodes = ""
                withTimeoutOrNull(5000) {
                    while (true) {
                        val nodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                        fun visit(node: android.view.accessibility.AccessibilityNodeInfo?) {
                            if (node == null) return
                            nodes.add(node)
                            for (index in 0 until node.childCount) visit(node.getChild(index))
                        }
                        visit(instrumentation.uiAutomation.rootInActiveWindow)
                        observedNodes = nodes.joinToString { "${it.text}/${it.contentDescription}/checkable=${it.isCheckable}/clickable=${it.isClickable}" }
                        val toggle = nodes.singleOrNull { it.isCheckable && it.isClickable }
                        if (toggle != null) {
                            assertTrue(toggle.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                            return@withTimeoutOrNull true
                        }
                        delay(100)
                    }
                } ?: throw AssertionError("Toggle not found: $observedNodes")
                withTimeoutOrNull(5000) {
                    while (true) {
                        var secure = false
                        scenario.onActivity { secure = it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0 }
                        if (secure) return@withTimeoutOrNull true
                        delay(100)
                    }
                } ?: throw AssertionError("Toggle clicked but FLAG_SECURE was not set")
                scenario.recreate()
                scenario.onActivity { assertNotEquals(0, it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE) }
            }
        } finally { privacy.edit().putBoolean("protectScreen", false).commit() }
    }

    @Test fun closingAndReopeningRendererRemainsUsable() = runBlocking {
        val file = sample()
        repeat(4) {
            PdfSession.open(context, file).use { session ->
                assertNotEquals(context.applicationInfo.uid, session.workerUid)
                assertTrue(session.render(it % 3, 300).width > 0)
            }
        }
    }
}
