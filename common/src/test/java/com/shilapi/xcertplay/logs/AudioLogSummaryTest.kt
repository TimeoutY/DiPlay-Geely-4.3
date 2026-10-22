package com.shilapi.xcertplay.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLogSummaryTest {
    @Test
    fun extractsLatestFocusPlayAndStats() {
        val lines = listOf(
            "10-21 16:00:00.000 I/DiPlay-AudioFocus( 100): legacy focus request reason=session stream=MUSIC gain=GAIN granted=false",
            "10-21 16:00:01.000 I/DiPlay-AudioFocus( 100): legacy focus request reason=media stream=MUSIC gain=GAIN granted=true musicVolume=9/15",
            "10-21 16:00:02.000 I/DiPlay-AudioFocus( 100): legacy focus change=LOSS held=true",
            "10-21 16:00:03.000 I/DiPlay-AudioStats( 100): audio play() audioType=media channel=MEDIA trackStream=3 trackState=1 playState=3 stream=3 volume=0/15 muted=true mode=0 musicActive=true sinkFocus=off",
            "10-21 16:00:04.000 I/DiPlay-AudioStats( 100): audio stats audioType=media channel=MEDIA writtenFrames=44100 totalWrittenFrames=44100 playbackHeadFrames=40000 playbackAdvanceFrames=40000 estimatedQueuedFrames=4100",
            "10-21 16:00:05.000 I/DiPlay-AudioStats( 100): audio stats audioType=media channel=MEDIA writtenFrames=44000 totalWrittenFrames=88100 playbackHeadFrames=84000 playbackAdvanceFrames=44000 estimatedQueuedFrames=4100",
            "10-21 16:00:06.000 I/DiPlay-AudioFocus( 100): iPhone playing=true",
        )
        val summary = AudioLogSummary.from(lines)
        assertEquals("true", summary.focusGranted)
        assertEquals("LOSS", summary.lastFocusChange)
        assertEquals("3", summary.playState)
        assertEquals("0/15", summary.volume)
        assertEquals("true", summary.muted)
        assertEquals(44000L, summary.writtenFrames)
        assertEquals(44100L, summary.previousWrittenFrames)
        assertEquals(44000L, summary.playbackAdvanceFrames)
        assertEquals("true", summary.iphonePlaying)
        assertTrue(summary.pcmFlowing == true)
    }

    @Test
    fun firstStatsWindowUsesHeadPosition() {
        val summary = AudioLogSummary.from(listOf(
            "I/DiPlay-AudioStats( 1): audio stats audioType=media channel=MEDIA writtenFrames=88200 playbackHeadFrames=88200 playbackAdvanceFrames=-1",
        ))
        assertTrue(summary.pcmFlowing == true)
        assertEquals(88200L, summary.headProgress)
    }

    @Test
    fun emptyLogHasNoFacts() {
        val summary = AudioLogSummary.from(emptyList())
        assertNull(summary.focusGranted)
        assertNull(summary.pcmFlowing)
    }
}
