package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(val nalus: ByteArray, val receivedNs: Long = System.nanoTime()) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    data object Resync : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec)
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Wi-Fi delivers frames in bursts after a radio gap; the decoder's 250 ms age check bounds latency.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val jobs = LinkedBlockingQueue<VideoJob>()
    // Running totals of queued frames: the old per-offer filterIsInstance/sumOf allocated a list
    // for every frame and walked the queue, adding GC_FOR_ALLOC pauses on 4.4's tiny heaps.
    private var frameCount = 0
    private var frameBytes = 0L

    @Synchronized fun offer(job: VideoJob) {
        if (job is VideoJob.Frame) {
            if (frameCount >= maxFrames || frameBytes + job.nalus.size > maxBytes) {
                discardFrames()
                jobs.offer(VideoJob.Resync)
            }
            // A single oversized frame is also a lost reference chain.
            if (job.nalus.size > maxBytes) return
            frameCount++
            frameBytes += job.nalus.size
        }
        jobs.offer(job)
    }

    @Synchronized fun discardFrames() {
        // Collection.removeIf is a Java default method added in API 24; use the iterator on 4.3.
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            when (iterator.next()) {
                is VideoJob.Frame, is VideoJob.Resync -> iterator.remove()
                else -> Unit
            }
        }
        frameCount = 0
        frameBytes = 0L
    }

    fun poll(timeoutMillis: Long): VideoJob? {
        val job = jobs.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        if (job is VideoJob.Frame) onTaken(job)
        return job
    }

    @Synchronized private fun onTaken(job: VideoJob.Frame) {
        if (frameCount > 0) frameCount--
        frameBytes = (frameBytes - job.nalus.size).coerceAtLeast(0L)
    }

    /** Frames waiting for the decoder (diagnostic). */
    @Synchronized fun queuedFrames(): Int = frameCount
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. */
internal object VideoInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
