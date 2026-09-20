package com.sh.engine.model.llm;

import java.util.Arrays;

/**
 * 发送给多模态大模型的单张 JPEG 图片。
 */
public final class LlmImageInput {
    private final String name;
    private final byte[] jpegData;

    public LlmImageInput(String name, byte[] jpegData) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("image name must not be blank");
        }
        if (jpegData == null || jpegData.length == 0) {
            throw new IllegalArgumentException("image data must not be empty");
        }
        this.name = name;
        this.jpegData = Arrays.copyOf(jpegData, jpegData.length);
    }

    public String getName() {
        return name;
    }

    public byte[] getJpegData() {
        return Arrays.copyOf(jpegData, jpegData.length);
    }

    public int sizeInBytes() {
        return jpegData.length;
    }
}
