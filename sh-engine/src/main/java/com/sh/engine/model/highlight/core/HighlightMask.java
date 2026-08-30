package com.sh.engine.model.highlight.core;

import lombok.Getter;

/**
 * 基于源画面宽高归一化的矩形蒙层，便于同一直播中不同视频分片复用。
 */
@Getter
public final class HighlightMask {
    private final double x;
    private final double y;
    private final double width;
    private final double height;

    /**
     * 创建归一化蒙层，区域必须完整位于 0～1 的源画面内。
     */
    public HighlightMask(double x, double y, double width, double height) {
        if (!isFinite(x) || !isFinite(y) || !isFinite(width) || !isFinite(height)
                || x < 0 || y < 0 || width <= 0 || height <= 0
                || x + width > 1.000001 || y + height > 1.000001) {
            throw new IllegalArgumentException("highlight mask must stay inside normalized frame");
        }
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /**
     * 将归一化区域转换为指定画面尺寸下的横坐标。
     */
    public int pixelX(int frameWidth) {
        validateDimension(frameWidth);
        return Math.max(0, Math.min(
                (int) Math.floor(x * frameWidth), frameWidth - 1));
    }

    /**
     * 将归一化区域转换为指定画面尺寸下的纵坐标。
     */
    public int pixelY(int frameHeight) {
        validateDimension(frameHeight);
        return Math.max(0, Math.min(
                (int) Math.floor(y * frameHeight), frameHeight - 1));
    }

    /**
     * 返回指定画面尺寸下的蒙层宽度，并保证不越过右边界。
     */
    public int pixelWidth(int frameWidth) {
        validateDimension(frameWidth);
        return Math.max(1, Math.min(
                (int) Math.ceil(width * frameWidth), frameWidth - pixelX(frameWidth)));
    }

    /**
     * 返回指定画面尺寸下的蒙层高度，并保证不越过下边界。
     */
    public int pixelHeight(int frameHeight) {
        validateDimension(frameHeight);
        return Math.max(1, Math.min(
                (int) Math.ceil(height * frameHeight), frameHeight - pixelY(frameHeight)));
    }

    private boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private void validateDimension(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("frame dimension must be positive");
        }
    }
}
