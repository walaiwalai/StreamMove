package com.sh.engine.model.danmaku;

/** 经会话内相对评分筛出的弹幕信号窗口。 */
public final class DanmakuRecallWindow {
    private final int startSecond;
    private final int endSecond;
    private final double score;

    public DanmakuRecallWindow(int startSecond, int endSecond, double score) {
        if (startSecond < 0 || endSecond <= startSecond
                || !Double.isFinite(score) || score < 0D || score > 100D) {
            throw new IllegalArgumentException("invalid danmaku recall window");
        }
        this.startSecond = startSecond;
        this.endSecond = endSecond;
        this.score = score;
    }

    public int centerSecond() {
        return startSecond + (endSecond - startSecond) / 2;
    }

    public boolean overlapsWithContext(
            DanmakuRecallWindow other, int contextBeforeSeconds) {
        int thisContextStart = startSecond - contextBeforeSeconds;
        int otherContextStart = other.startSecond - contextBeforeSeconds;
        return thisContextStart < other.endSecond
                && endSecond > otherContextStart;
    }

    public int getStartSecond() {
        return startSecond;
    }

    public int getEndSecond() {
        return endSecond;
    }

    public double getScore() {
        return score;
    }
}
