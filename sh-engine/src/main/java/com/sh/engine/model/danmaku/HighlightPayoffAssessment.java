package com.sh.engine.model.danmaku;

import lombok.Data;

import java.util.List;
import java.util.Locale;

/** 对精剪片段是否具有实质看点的结构化判断。 */
@Data
public class HighlightPayoffAssessment {
    private String category;
    private Boolean meaningful;
    private Integer strength;
    /** 一个具体、可定位的音画兑现时刻；普通流程时置空。 */
    private String dominantMoment;
    /** 直接支持兑现时刻的一手证据编号，不能只引用弹幕。 */
    private List<HighlightEvidenceReference> payoffEvidence;
    /** 兑现时刻是否由成片内音画直接支持，而非解释或题材常识。 */
    private Boolean dominantMomentDirectlySupported;
    /** 成片是否只呈现普通流程或界面切换。 */
    private Boolean ordinaryProcessOnly;
    /** REVERSAL 是否先在成片内建立了明确预期，再被后续状态推翻。 */
    private Boolean reversalExpectationEstablished;
    /** REVERSAL 中直接建立前置预期或稳定状态的一手证据。 */
    private List<HighlightEvidenceReference> reversalExpectationEvidence;
    /** REVERSAL 中直接推翻前置预期或稳定状态的一手证据。 */
    private List<HighlightEvidenceReference> reversalContradictionEvidence;
    /** SPECTACLE 是否存在单个主导性的高冲击视觉事件。 */
    private Boolean spectacleVisualImpact;
    /** 视觉冲击是否主要来自标准系统动画、过场或固定结果演出。 */
    private Boolean systemControlledPresentation;
    /** SKILL 是否同时直接呈现了参与者动作和可归因结果。 */
    private Boolean participantActionAndResultVisible;
    /** 社交类看点是否在精剪内完整呈现了具体铺垫与兑现。 */
    private Boolean completedSetupAndPayoff;
    /** EMOTION 是否呈现了明显超出日常反应的情绪升级。 */
    private Boolean distinctiveEmotionalEscalation;
    /** TENSION 是否在精剪内形成风险或冲突升级并得到明确解决。 */
    private Boolean escalatingConflictAndResolution;
    /** TENSION 中建立风险、冲突或不确定状态的一手证据。 */
    private List<HighlightEvidenceReference> tensionSetupEvidence;
    /** TENSION 中解决风险或冲突的一手证据。 */
    private List<HighlightEvidenceReference> tensionResolutionEvidence;
    /** 片段本身是否呈现了无需外围上下文也能感知的兑现点。 */
    private Boolean standalonePayoff;
    /** INFORMATIONAL 类片段是否直接传递了可复用的信息。 */
    private Boolean reusableInformation;
    /** INFORMATIONAL 类片段在成片内直接给出的具体可复用结论。 */
    private String reusableTakeaway;

    /** 必填字段齐全时才允许把响应当作有效协议结果。 */
    public boolean hasCompleteProtocol() {
        return category != null
                && meaningful != null
                && strength != null
                && dominantMoment != null
                && payoffEvidence != null
                && dominantMomentDirectlySupported != null
                && ordinaryProcessOnly != null
                && standalonePayoff != null
                && hasCompleteCategoryProtocol();
    }

    private boolean hasCompleteCategoryProtocol() {
        String normalized = category == null
                ? "" : category.trim().toUpperCase(Locale.ROOT);
        if ("REVERSAL".equals(normalized)) {
            return reversalExpectationEstablished != null
                    && reversalExpectationEvidence != null
                    && reversalContradictionEvidence != null;
        }
        if ("SPECTACLE".equals(normalized)) {
            return spectacleVisualImpact != null
                    && systemControlledPresentation != null;
        }
        if ("SKILL".equals(normalized)) {
            return participantActionAndResultVisible != null;
        }
        if ("EMOTION".equals(normalized)) {
            return completedSetupAndPayoff != null
                    && distinctiveEmotionalEscalation != null;
        }
        if ("COMEDY".equals(normalized) || "INTERACTION".equals(normalized)) {
            return completedSetupAndPayoff != null;
        }
        if ("TENSION".equals(normalized)) {
            return escalatingConflictAndResolution != null
                    && tensionSetupEvidence != null
                    && tensionResolutionEvidence != null;
        }
        if ("INFORMATIONAL".equals(normalized)) {
            return reusableInformation != null && reusableTakeaway != null;
        }
        return true;
    }

    /** 统一可选结论字段中的等价空值，避免把“无”当成有效知识结论。 */
    public void normalizeProtocolValues() {
        reusableTakeaway = HighlightProtocolNormalizer.normalizeOptionalText(
                reusableTakeaway);
    }

    public boolean isRoutine() {
        return "ROUTINE".equalsIgnoreCase(category == null ? "" : category.trim());
    }
}
