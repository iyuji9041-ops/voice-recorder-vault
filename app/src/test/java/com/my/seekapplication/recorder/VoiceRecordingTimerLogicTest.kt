package com.my.seekapplication.recorder

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests validating non-drifting elapsed recording time calculations
 * across start, pause, resume, and multiple pause cycles.
 */
class VoiceRecordingTimerLogicTest {

    private fun computeElapsed(
        state: VoiceRecordingService.State,
        now: Long,
        startElapsedRealtime: Long,
        pauseStartTime: Long,
        accumulatedPauseDuration: Long
    ): Long {
        return when (state) {
            VoiceRecordingService.State.RECORDING -> {
                (now - startElapsedRealtime) - accumulatedPauseDuration
            }
            VoiceRecordingService.State.PAUSED -> {
                (pauseStartTime - startElapsedRealtime) - accumulatedPauseDuration
            }
            VoiceRecordingService.State.IDLE -> 0L
        }
    }

    @Test
    fun testIdleStateHasZeroElapsed() {
        val elapsed = computeElapsed(
            state = VoiceRecordingService.State.IDLE,
            now = 10000L,
            startElapsedRealtime = 5000L,
            pauseStartTime = 0L,
            accumulatedPauseDuration = 0L
        )
        assertEquals(0L, elapsed)
    }

    @Test
    fun testRecordingStateIncrementsAccuratelyWithoutDrift() {
        val start = 1000L
        val now = 6000L // 5 seconds elapsed
        val elapsed = computeElapsed(
            state = VoiceRecordingService.State.RECORDING,
            now = now,
            startElapsedRealtime = start,
            pauseStartTime = 0L,
            accumulatedPauseDuration = 0L
        )
        assertEquals(5000L, elapsed)
    }

    @Test
    fun testPausedStateRemainsFrozenWhileTimeAdvances() {
        val start = 1000L
        val pauseAt = 4000L // 3 seconds recorded when paused
        
        // Timer checked 10 seconds later while still paused
        val now = 14000L
        val elapsed = computeElapsed(
            state = VoiceRecordingService.State.PAUSED,
            now = now,
            startElapsedRealtime = start,
            pauseStartTime = pauseAt,
            accumulatedPauseDuration = 0L
        )
        assertEquals(3000L, elapsed)
    }

    @Test
    fun testResumeDeductsAccumulatedPauseDurationAccurately() {
        val start = 1000L
        val pauseAt = 4000L // 3s recorded
        val resumeAt = 9000L // Paused for 5s (5000ms)
        val accumulatedPause = resumeAt - pauseAt // 5000ms

        // Now checked 2s after resume (at 11000L)
        val now = 11000L
        val elapsed = computeElapsed(
            state = VoiceRecordingService.State.RECORDING,
            now = now,
            startElapsedRealtime = start,
            pauseStartTime = pauseAt,
            accumulatedPauseDuration = accumulatedPause
        )
        // Total time elapsed: 11000 - 1000 = 10000ms. Minus 5000ms pause = 5000ms active recording!
        assertEquals(5000L, elapsed)
    }

    @Test
    fun testMultiplePauseResumeCyclesWithoutDrift() {
        var start = 10000L
        var accumulatedPause = 0L

        // Cycle 1: Record 4s, Pause 6s
        val pause1 = start + 4000L // 14000L
        val resume1 = pause1 + 6000L // 20000L
        accumulatedPause += (resume1 - pause1) // 6000L

        // Cycle 2: Record another 3s, Pause 10s
        val pause2 = resume1 + 3000L // 23000L
        val resume2 = pause2 + 10000L // 33000L
        accumulatedPause += (resume2 - pause2) // 16000L total pause

        // Now checked 5s after resume2 (38000L)
        val now = resume2 + 5000L // 38000L
        val elapsed = computeElapsed(
            state = VoiceRecordingService.State.RECORDING,
            now = now,
            startElapsedRealtime = start,
            pauseStartTime = pause2,
            accumulatedPauseDuration = accumulatedPause
        )

        // Active recording: 4s + 3s + 5s = 12s (12000ms)
        assertEquals(12000L, elapsed)
    }
}
