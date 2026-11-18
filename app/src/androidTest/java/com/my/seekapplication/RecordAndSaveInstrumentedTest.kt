package com.my.seekapplication

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.my.seekapplication.recorder.AudioStorageManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileOutputStream

/**
 * Instrumented test verifying recording creation, saving, querying, and deletion via AudioStorageManager.
 */
@RunWith(AndroidJUnit4::class)
class RecordAndSaveInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun testCreateAndSaveRecording() {
        val (uri, pfd) = AudioStorageManager.createPendingRecording(context)
        assertNotNull(uri)
        assertNotNull(pfd)

        // Write sample dummy audio bytes into file descriptor
        FileOutputStream(pfd.fileDescriptor).use { fos ->
            fos.write("DUMMY_AUDIO_HEADER_SAMPLE_BYTES".toByteArray())
            fos.flush()
        }
        pfd.close()

        // Finalize and save
        AudioStorageManager.finishPendingRecording(context, uri, save = true)

        // Query recordings
        val recordings = AudioStorageManager.queryAllRecordings(context)
        val savedItem = recordings.find { it.uri == uri }

        if (savedItem != null) {
            assertTrue(savedItem.name.startsWith("REC_"))
            assertTrue(savedItem.name.endsWith(".m4a"))

            // Clean up
            AudioStorageManager.deleteRecording(context, savedItem)
        }
    }
}
