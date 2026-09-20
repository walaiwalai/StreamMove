package com.sh.engine.model.highlight;

import lombok.Data;

import java.util.List;

/** 对事实不变封面背景的清晰度、曝光和技术可用性评估。 */
@Data
public class HighlightCoverVisualAssessment {
    private Boolean acceptable;
    private Integer visualQuality;
    private Boolean severeVisualArtifacts;
    private List<String> issues;
    private String rationale;

    public boolean hasRequiredFields() {
        return acceptable != null
                && visualQuality != null && visualQuality >= 0 && visualQuality <= 100
                && severeVisualArtifacts != null;
    }

    public boolean isQualified() {
        return hasRequiredFields() && Boolean.TRUE.equals(acceptable)
                && visualQuality >= 65
                && Boolean.FALSE.equals(severeVisualArtifacts);
    }
}
