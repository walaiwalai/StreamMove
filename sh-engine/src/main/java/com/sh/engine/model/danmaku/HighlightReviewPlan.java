package com.sh.engine.model.danmaku;

import org.apache.commons.lang3.StringUtils;

/** 可审计的密集复核窗口规划结果。 */
public final class HighlightReviewPlan {
    private final HighlightClipRange range;
    private final String strategy;
    private final int anchorSecond;

    public HighlightReviewPlan(
            HighlightClipRange range, String strategy, int anchorSecond) {
        if (range == null || StringUtils.isBlank(strategy)
                || !range.contains(anchorSecond)) {
            throw new IllegalArgumentException("invalid highlight review plan");
        }
        this.range = range;
        this.strategy = strategy;
        this.anchorSecond = anchorSecond;
    }

    public HighlightClipRange getRange() {
        return range;
    }

    public String getStrategy() {
        return strategy;
    }

    public int getAnchorSecond() {
        return anchorSecond;
    }
}
