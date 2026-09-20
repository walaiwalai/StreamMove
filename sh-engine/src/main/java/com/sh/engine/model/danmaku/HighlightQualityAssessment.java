package com.sh.engine.model.danmaku;

import lombok.Data;

/**
 * 将事实成立与短视频观看价值拆开评分，避免把“有结果”等同于“值得发布”。
 */
@Data
public class HighlightQualityAssessment {
    /** 主体、动作和结果被一手证据共同确认的程度。 */
    private Integer factualCompleteness;

    /** 对不了解上下文的观众是否仍有明确看点。 */
    private Integer audienceValue;

    /** 建议剪辑内有意义进展的密度。 */
    private Integer contentDensity;

    /** 建议剪辑内任意模态都没有新进展的最长连续秒数。 */
    private Integer longestNoDevelopmentSeconds;

    /** 对上述评分的简短、可审计说明。 */
    private String rationale;
}
