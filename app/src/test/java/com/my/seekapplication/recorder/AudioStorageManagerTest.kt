package com.my.seekapplication.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Unit tests for AudioStorageManager formatting, sanitization, and helper logic.
 */
class AudioStorageManagerTest {

    @Test
    fun testGenerateRecordingDisplayNameFormat() {
        val name = AudioStorageManager.generateRecordingDisplayName()
        val regex = Regex("^REC_\\d{8}_\\d{6}\\.m4a$")
        assertTrue("Generated filename '$name' must match REC_YYYYMMDD_HHMMSS.m4a pattern", regex.matches(name))
    }

    @Test
    fun testGenerateRecordingDisplayNameDeterministic() {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.set(2026, Calendar.NOVEMBER, 18, 14, 30, 45)
        val name = AudioStorageManager.generateRecordingDisplayName(calendar.time)
        // Check prefix and suffix
        assertTrue(name.startsWith("REC_"))
        assertTrue(name.endsWith(".m4a"))
    }

    @Test
    fun testSanitizeRecordingName() {
        // Appends .m4a if missing
        assertEquals("meeting_notes.m4a", AudioStorageManager.sanitizeRecordingName("meeting_notes"))
        
        // Retains .m4a if already present
        assertEquals("meeting_notes.m4a", AudioStorageManager.sanitizeRecordingName("meeting_notes.m4a"))
        assertEquals("interview.M4A", AudioStorageManager.sanitizeRecordingName("interview.M4A"))

        // Replaces illegal path / filesystem characters
        assertEquals("voice_memo_1.m4a", AudioStorageManager.sanitizeRecordingName("voice/memo:1"))
        assertEquals("test_file_name.m4a", AudioStorageManager.sanitizeRecordingName("test*file?name"))
        assertEquals("quote_file_.m4a", AudioStorageManager.sanitizeRecordingName("quote\"file|"))

        // Handles blank or empty input safely
        assertEquals("recording.m4a", AudioStorageManager.sanitizeRecordingName(""))
        assertEquals("recording.m4a", AudioStorageManager.sanitizeRecordingName("   "))

        // Trims leading/trailing whitespace
        assertEquals("trimmed_note.m4a", AudioStorageManager.sanitizeRecordingName("  trimmed_note  "))
    }

    @Test
    fun testFormatDurationMs() {
        assertEquals("00:00", AudioStorageManager.formatDurationMs(0L))
        assertEquals("00:05", AudioStorageManager.formatDurationMs(5_000L))
        assertEquals("01:05", AudioStorageManager.formatDurationMs(65_000L))
        assertEquals("10:30", AudioStorageManager.formatDurationMs(630_000L))
        assertEquals("00:00", AudioStorageManager.formatDurationMs(-1000L))
    }

    @Test
    fun testRelativeRecordingsPath() {
        assertEquals("Recordings/MyVoiceRecordings", AudioStorageManager.RELATIVE_RECORDINGS_PATH)
    }
}
