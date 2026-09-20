package com.sh.engine.model.highlight;

import com.alibaba.fastjson.JSONObject;

import java.util.Arrays;

/** 事实不变色调迁移结果及其可审计的构造证明。 */
public final class FactPreservingCoverStyle {
    private static final String STRATEGY =
            "source-coordinate-monotonic-global-channel-transform";
    private final byte[] imageData;
    private final int width;
    private final int height;
    private final double[] channelGains;
    private final double[] channelOffsets;

    public FactPreservingCoverStyle(
            byte[] imageData,
            int width,
            int height,
            double[] channelGains,
            double[] channelOffsets) {
        if (imageData == null || imageData.length == 0 || width <= 0 || height <= 0
                || channelGains == null || channelGains.length != 3
                || channelOffsets == null || channelOffsets.length != 3) {
            throw new IllegalArgumentException("invalid fact-preserving cover style");
        }
        this.imageData = Arrays.copyOf(imageData, imageData.length);
        this.width = width;
        this.height = height;
        this.channelGains = Arrays.copyOf(channelGains, channelGains.length);
        this.channelOffsets = Arrays.copyOf(channelOffsets, channelOffsets.length);
    }

    public byte[] getImageData() {
        return Arrays.copyOf(imageData, imageData.length);
    }

    public double[] getChannelGains() {
        return Arrays.copyOf(channelGains, channelGains.length);
    }

    /** 记录为什么该结果无法从生成图带入新的空间对象或文字。 */
    public JSONObject toAuditMetadata() {
        JSONObject proof = new JSONObject(true);
        proof.put("strategy", STRATEGY);
        proof.put("width", width);
        proof.put("height", height);
        proof.put("pixelCount", (long) width * height);
        proof.put("sourceCoordinateRetention", true);
        proof.put("qwenSpatialPixelsUsed", false);
        proof.put("monotonicPerChannel", allPositive(channelGains));
        proof.put("channelGains", Arrays.copyOf(channelGains, channelGains.length));
        proof.put("channelOffsets", Arrays.copyOf(channelOffsets, channelOffsets.length));
        proof.put("formula", "out(x,y,c)=clamp(round(source(x,y,c)*gain[c]+offset[c]))");
        return proof;
    }

    private boolean allPositive(double[] values) {
        for (double value : values) {
            if (value <= 0.0) {
                return false;
            }
        }
        return true;
    }
}
