package com.my.seekapplication.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for MediaTypeHelper:
 * 1. Correct classification by stored MIME type.
 * 2. Extensionless file identification via MIME type.
 * 3. Fallback to file extension when MIME type is missing or generic.
 * 4. Correct category mapping ("Photos", "Videos", "Audios").
 */
class MediaTypeHelperTest {

    @Test
    fun testClassificationByMimeType() {
        assertTrue(MediaTypeHelper.isImage("image/jpeg"))
        assertTrue(MediaTypeHelper.isImage("image/png"))
        assertTrue(MediaTypeHelper.isImage("image/webp"))
        assertTrue(MediaTypeHelper.isImage("image/heic"))

        assertTrue(MediaTypeHelper.isVideo("video/mp4"))
        assertTrue(MediaTypeHelper.isVideo("video/webm"))
        assertTrue(MediaTypeHelper.isVideo("video/3gpp"))
        assertTrue(MediaTypeHelper.isVideo("video/mkv"))

        assertTrue(MediaTypeHelper.isAudio("audio/mpeg"))
        assertTrue(MediaTypeHelper.isAudio("audio/mp4"))
        assertTrue(MediaTypeHelper.isAudio("audio/x-wav"))
        assertTrue(MediaTypeHelper.isAudio("audio/flac"))
        assertTrue(MediaTypeHelper.isAudio("audio/ogg"))
    }

    @Test
    fun testExtensionlessFileClassificationByMime() {
        val extensionlessVideoName = "recording_20261118_112000"
        val extensionlessAudioName = "voice_memo_001"
        val extensionlessPhotoName = "camera_snapshot"

        assertTrue(MediaTypeHelper.isVideo("video/mp4", extensionlessVideoName))
        assertFalse(MediaTypeHelper.isImage("video/mp4", extensionlessVideoName))

        assertTrue(MediaTypeHelper.isAudio("audio/mp4", extensionlessAudioName))
        assertFalse(MediaTypeHelper.isVideo("audio/mp4", extensionlessAudioName))

        assertTrue(MediaTypeHelper.isImage("image/jpeg", extensionlessPhotoName))
        assertFalse(MediaTypeHelper.isAudio("image/jpeg", extensionlessPhotoName))
    }

    @Test
    fun testFallbackToFilenameExtension() {
        assertTrue(MediaTypeHelper.isImage("application/octet-stream", "picture.jpg"))
        assertTrue(MediaTypeHelper.isImage(null, "photo.PNG"))
        assertTrue(MediaTypeHelper.isVideo(null, "clip.mp4"))
        assertTrue(MediaTypeHelper.isVideo("application/octet-stream", "movie.mkv"))
        assertTrue(MediaTypeHelper.isAudio(null, "song.mp3"))
        assertTrue(MediaTypeHelper.isAudio("application/octet-stream", "voice.m4a"))
    }

    @Test
    fun testGetCategory() {
        assertEquals("Photos", MediaTypeHelper.getCategory("image/jpeg", "test.jpg"))
        assertEquals("Videos", MediaTypeHelper.getCategory("video/mp4", "clip.mp4"))
        assertEquals("Audios", MediaTypeHelper.getCategory("audio/mpeg", "song.mp3"))
        assertEquals("Audios", MediaTypeHelper.getCategory(null, "audio.wav"))
        assertEquals("Videos", MediaTypeHelper.getCategory(null, "video.webm"))
        assertEquals("Photos", MediaTypeHelper.getCategory(null, "pic.webp"))
    }
}
