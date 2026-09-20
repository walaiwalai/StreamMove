package com.sh.engine.model.danmaku;

/** 一段源视频在整场直播时间轴上的范围。 */
public final class SessionVideoRange {
    private final int startSecond;
    private final int endSecond;

    public SessionVideoRange(int startSecond, int endSecond) {
        if (startSecond < 0 || endSecond <= startSecond) {
            throw new IllegalArgumentException("invalid session video range");
        }
        this.startSecond = startSecond;
        this.endSecond = endSecond;
    }

    public boolean contains(int second) {
        return second >= startSecond && second < endSecond;
    }

    public boolean containsRange(int start, int end) {
        return start >= startSecond && end <= endSecond;
    }

    public int limitContextStart(int requestedStart) {
        return Math.max(startSecond, requestedStart);
    }

    public int limitContextEnd(int requestedEnd) {
        return Math.min(endSecond, requestedEnd);
    }

    public int getStartSecond() {
        return startSecond;
    }

    public int getEndSecond() {
        return endSecond;
    }
}
