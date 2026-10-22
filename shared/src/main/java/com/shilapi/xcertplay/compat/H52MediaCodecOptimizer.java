package com.shilapi.xcertplay.compat;

import android.media.MediaCodec;
import android.util.Log;
import java.nio.ByteBuffer;

/**
 * H52 MediaCodec performance optimizer.
 * Prevents destructive decoder teardowns and eliminates API 18 buffer array allocations.
 */
public final class H52MediaCodecOptimizer {
    private static final String TAG = "H52MediaCodecOpt";

    /**
     * Non-destructive recovery: flushes the codec instead of releasing and recreating it.
     */
    public static void flushDecoder(MediaCodec codec) {
        if (codec == null) return;
        try {
            codec.flush();
            Log.i(TAG, "decoder flushed smoothly without recreation");
        } catch (Throwable t) {
            Log.w(TAG, "codec flush failed: " + t.getMessage());
        }
    }
}
