package com.sh.engine.model.danmaku;

/** 通过边界校验的源视频精剪区间。 */
public final class HighlightClipRange {
    private final int startSecond;
    private final int endSecond;

    public HighlightClipRange(int startSecond, int endSecond) {
        if (startSecond < 0 || endSecond <= startSecond) {
            throw new IllegalArgumentException("invalid highlight clip range");
        }
        this.startSecond = startSecond;
        this.endSecond = endSecond;
    }

    public boolean contains(int timestamp) {
        return timestamp >= startSecond && timestamp < endSecond;
    }

    public boolean containsInclusive(int start, int end) {
        return start >= startSecond && end <= endSecond;
    }

    public int durationSeconds() {
        return endSecond - startSecond;
    }

    public int getStartSecond() {
        return startSecond;
    }

    public int getEndSecond() {
        return endSecond;
    }
}
