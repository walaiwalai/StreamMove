package com.sh.engine.model.highlight;

import lombok.Data;

import java.util.List;

/** 视觉模型对图片编辑前后事实一致性和封面可用性的独立复核。 */
@Data
public class HighlightCoverAssessment {
    private Boolean acceptable;
    private Integer factualConsistency;
    private Integer visualQuality;
    private Boolean unexpectedText;
    private Boolean majorSubjectObscured;
    private Boolean severeVisualArtifacts;
    private List<String> issues;
    private String rationale;

    public boolean isQualified() {
        return Boolean.TRUE.equals(acceptable)
                && factualConsistency != null && factualConsistency >= 85
                && visualQuality != null && visualQuality >= 65
                && Boolean.FALSE.equals(unexpectedText)
                && Boolean.FALSE.equals(majorSubjectObscured)
                && Boolean.FALSE.equals(severeVisualArtifacts);
    }
}
