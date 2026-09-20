package com.sh.engine.model.danmaku;

import lombok.Data;

import java.util.List;

/** 最终精剪证据与上一步事实主张之间的独立对齐结果。 */
@Data
public class HighlightFactAlignment {
    private Boolean setupSupported;
    private Boolean actionSupported;
    private Boolean outcomeSupported;
    private Boolean sameEventSupported;
    private List<String> unsupportedClaims;
    private String rationale;

    /** 区分“明确输出空列表”和“模型漏掉字段”。 */
    public boolean hasCompleteProtocol() {
        return setupSupported != null
                && actionSupported != null
                && outcomeSupported != null
                && sameEventSupported != null
                && unsupportedClaims != null
                && rationale != null;
    }

    /** 兼容模型用“无”表达空数组的常见协议差异。 */
    public void normalizeProtocolValues() {
        unsupportedClaims = HighlightProtocolNormalizer.normalizeIssueList(
                unsupportedClaims);
    }

    /** 所有故事阶段均在精剪内得到支持，且没有借用不连续画面拼接事实。 */
    public boolean isFullyAligned() {
        return Boolean.TRUE.equals(setupSupported)
                && Boolean.TRUE.equals(actionSupported)
                && Boolean.TRUE.equals(outcomeSupported)
                && Boolean.TRUE.equals(sameEventSupported)
                && unsupportedClaims != null
                && unsupportedClaims.stream().noneMatch(
                        claim -> claim != null && !claim.trim().isEmpty());
    }
}
