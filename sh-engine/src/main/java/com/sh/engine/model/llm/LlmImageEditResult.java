package com.sh.engine.model.llm;

import java.util.Arrays;

/** 图片编辑供应商原始响应与已下载图片的组合，供业务审计。 */
public final class LlmImageEditResult {
    private final String rawResponse;
    private final byte[] imageData;

    public LlmImageEditResult(String rawResponse, byte[] imageData) {
        if (imageData == null || imageData.length == 0) {
            throw new IllegalArgumentException("edited image data must not be empty");
        }
        this.rawResponse = rawResponse;
        this.imageData = Arrays.copyOf(imageData, imageData.length);
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public byte[] getImageData() {
        return Arrays.copyOf(imageData, imageData.length);
    }
}
