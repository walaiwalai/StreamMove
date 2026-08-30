package com.sh.engine.model.highlight.core;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 将高光事件转换成视频区间时使用的通用策略。
 */
@Getter
public final class HighlightCutPolicy {
    private final int clusterGapSeconds;
    private final int preRollSeconds;
    private final int postRollSeconds;
    private final float minScore;
    private final int minPositiveCount;
    private final int topN;
    private final ClusterScorer clusterScorer;

    @Builder
    private HighlightCutPolicy(int clusterGapSeconds,
                               int preRollSeconds,
                               int postRollSeconds,
                               float minScore,
                               int minPositiveCount,
                               int topN,
                               ClusterScorer clusterScorer) {
        if (clusterGapSeconds < 0 || preRollSeconds < 0 || postRollSeconds < 0) {
            throw new IllegalArgumentException("highlight time values must not be negative");
        }
        if (minPositiveCount < 0 || topN <= 0) {
            throw new IllegalArgumentException(
                    "minPositiveCount must be non-negative and topN positive");
        }
        this.clusterGapSeconds = clusterGapSeconds;
        this.preRollSeconds = preRollSeconds;
        this.postRollSeconds = postRollSeconds;
        this.minScore = minScore;
        this.minPositiveCount = minPositiveCount;
        this.topN = topN;
        this.clusterScorer = clusterScorer == null
                ? HighlightCutPolicy::sumEventScores
                : clusterScorer;
    }

    /**
     * 按当前策略计算事件簇得分；未配置特殊规则时直接累加事件分数。
     */
    public float calculateClusterScore(List<HighlightEvent> events) {
        return clusterScorer.calculate(events);
    }

    private static float sumEventScores(List<HighlightEvent> events) {
        float score = 0f;
        for (HighlightEvent event : events) {
            score += event.getScore();
        }
        return score;
    }

    /**
     * 只属于剪辑策略的可选评分扩展，放在策略内部避免形成孤立顶层接口。
     */
    @FunctionalInterface
    public interface ClusterScorer {
        float calculate(List<HighlightEvent> events);
    }
}
