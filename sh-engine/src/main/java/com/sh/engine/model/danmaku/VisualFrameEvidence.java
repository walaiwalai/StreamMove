package com.sh.engine.model.danmaku;

import com.sh.engine.model.llm.LlmImageInput;

import java.util.Arrays;

/**
 * 可复盘的候选视频帧，包含源视频时间、落盘路径、内容哈希和图片数据。
 */
public final class VisualFrameEvidence {
    private final int timestampSeconds;
    private final String evidencePath;
    private final String sha256;
    private final byte[] jpegData;

    public VisualFrameEvidence(
            int timestampSeconds, String evidencePath, String sha256, byte[] jpegData) {
        if (timestampSeconds < 0 || evidencePath == null || evidencePath.trim().isEmpty()
                || sha256 == null || sha256.trim().isEmpty()
                || jpegData == null || jpegData.length == 0) {
            throw new IllegalArgumentException("invalid visual frame evidence");
        }
        this.timestampSeconds = timestampSeconds;
        this.evidencePath = evidencePath;
        this.sha256 = sha256;
        this.jpegData = Arrays.copyOf(jpegData, jpegData.length);
    }

    public int getTimestampSeconds() {
        return timestampSeconds;
    }

    public String getEvidencePath() {
        return evidencePath;
    }

    public String getSha256() {
        return sha256;
    }

    public byte[] getJpegData() {
        return Arrays.copyOf(jpegData, jpegData.length);
    }

    /**
     * 转换为多模态服务输入，名称保留时间戳供请求审计定位。
     */
    public LlmImageInput toLlmInput(int frameIndex) {
        return new LlmImageInput(
                String.format("frame-%02d-%06ds.jpg", frameIndex, timestampSeconds), jpegData);
    }
}
