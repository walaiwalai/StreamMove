package com.sh.engine.model.danmaku;

import lombok.Data;

import java.util.List;

/**
 * 密集画面阶段得到的事件事实。该模型不负责判断是否精彩，只描述证据能够直接证明的内容。
 */
@Data
public class HighlightFactVerification {
    private String setup;
    private List<HighlightEvidenceReference> setupEvidence;
    private String action;
    private List<HighlightEvidenceReference> actionEvidence;
    private String outcome;
    private List<HighlightEvidenceReference> outcomeEvidence;
    private String reaction;
    private List<HighlightEvidenceReference> reactionEvidence;

    /** 事件关键主体是否明确，不能用含糊指代代替。 */
    private Boolean subjectResolved;
    /** 关键动作是否被引用的一手证据直接支持。 */
    private Boolean actionDirectlySupported;
    /** 结果是否被引用的一手证据直接支持。 */
    private Boolean outcomeDirectlySupported;
    /** 结果字段、数值或状态变化的业务含义是否明确。 */
    private Boolean outcomeMeaningResolved;
    /** 动作与结果是否属于同一个连续事件，而非时间相邻的无关内容。 */
    private Boolean eventContinuityResolved;
    /** 事件描述是否声称动作导致了结果。 */
    private Boolean claimsCausalLink;
    /** 当声称因果时，因果关系是否有直接证据。 */
    private Boolean causalLinkResolved;

    /** 会使当前事件主张无法成立的阻断项；没有时必须为空数组。 */
    private List<String> blockingIssues;
    /** 未被事件主张使用、因此不影响成立的背景限制；没有时必须为空数组。 */
    private List<String> limitations;
    /** 适合作为事件代表画面的真实源视频时间。 */
    private String coverTimestamp;

    /** 兼容模型用“无”表达空数组的常见协议差异。 */
    public void normalizeProtocolValues() {
        blockingIssues = HighlightProtocolNormalizer.normalizeIssueList(blockingIssues);
        limitations = HighlightProtocolNormalizer.normalizeIssueList(limitations);
    }

    /**
     * 首轮事实召回是否不足以作为确定的负结论。
     *
     * <p>事实阶段承担的是高召回职责。只要事件三段、证据映射、必要状态或阻断结论中
     * 任一项没有闭合，就应由一次独立视角重新查看原始帧。第二次召回仍须经过本地证据
     * 校验和发布价值审核，因此这不会直接放宽最终准入。</p>
     */
    public boolean requiresIndependentRecallPass() {
        return isBlank(setup)
                || isBlank(action)
                || isBlank(outcome)
                || isEmpty(setupEvidence)
                || isEmpty(actionEvidence)
                || isEmpty(outcomeEvidence)
                || !Boolean.TRUE.equals(subjectResolved)
                || !Boolean.TRUE.equals(actionDirectlySupported)
                || !Boolean.TRUE.equals(outcomeDirectlySupported)
                || !Boolean.TRUE.equals(outcomeMeaningResolved)
                || !Boolean.TRUE.equals(eventContinuityResolved)
                || !isEmpty(blockingIssues)
                || claimsCausalLink == null
                || causalLinkResolved == null
                || (Boolean.TRUE.equals(claimsCausalLink)
                && !Boolean.TRUE.equals(causalLinkResolved));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isEmpty(List<?> values) {
        return values == null || values.isEmpty();
    }
}
