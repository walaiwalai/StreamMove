package com.sh.engine.model.danmaku;

import lombok.Data;

/** 发布层只评估观看价值和节奏，不重复裁决已经验真的事件事实。 */
@Data
public class HighlightPublicationQuality {
    /** 对不了解上下文的观众是否仍有明确看点，取 0~100 的整数。 */
    private Integer audienceValue;

    /** 建议剪辑内有意义进展的密度，取 0~100 的整数。 */
    private Integer contentDensity;

    /** 建议剪辑内任意模态都没有新进展的最长连续秒数。 */
    private Integer longestNoDevelopmentSeconds;

    /** 对观看价值和节奏评分的简短、可审计说明。 */
    private String rationale;

    /** 必填质量字段齐全时才允许进入业务门槛判断。 */
    public boolean hasCompleteProtocol() {
        return audienceValue != null
                && contentDensity != null
                && longestNoDevelopmentSeconds != null
                && rationale != null;
    }
}
