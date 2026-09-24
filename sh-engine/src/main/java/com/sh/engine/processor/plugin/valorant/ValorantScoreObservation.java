package com.sh.engine.processor.plugin.valorant;

/**
 * 一次顶部比分区域 OCR 的结构化结果。
 */
public final class ValorantScoreObservation {
    private final double globalSecond;
    private final Integer leftScore;
    private final String roundTime;
    private final Integer rightScore;

    public ValorantScoreObservation(double globalSecond,
                                    Integer leftScore,
                                    String roundTime,
                                    Integer rightScore) {
        this.globalSecond = globalSecond;
        this.leftScore = leftScore;
        this.roundTime = roundTime;
        this.rightScore = rightScore;
    }

    public double getGlobalSecond() {
        return globalSecond;
    }

    public Integer getLeftScore() {
        return leftScore;
    }

    public String getRoundTime() {
        return roundTime;
    }

    public Integer getRightScore() {
        return rightScore;
    }

    public boolean hasCompleteScore() {
        return leftScore != null && rightScore != null;
    }

    public boolean isOpeningScore() {
        return hasCompleteScore()
                && leftScore == 0
                && rightScore == 0
                && roundTime != null;
    }
}
