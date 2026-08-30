package com.sh.engine.model.highlight.core;

import java.util.Arrays;

/**
 * FFmpeg 流式输出的 JPEG 帧，时间戳相对于当前源视频。
 */
public final class InMemoryVideoFrame {
    private final int timestampSeconds;
    private final byte[] jpegData;

    public InMemoryVideoFrame(int timestampSeconds, byte[] jpegData) {
        if (timestampSeconds < 0) {
            throw new IllegalArgumentException("timestampSeconds must not be negative");
        }
        if (jpegData == null || jpegData.length == 0) {
            throw new IllegalArgumentException("jpegData must not be empty");
        }
        this.timestampSeconds = timestampSeconds;
        this.jpegData = Arrays.copyOf(jpegData, jpegData.length);
    }

    public int getTimestampSeconds() {
        return timestampSeconds;
    }

    public byte[] getJpegData() {
        return Arrays.copyOf(jpegData, jpegData.length);
    }
}
