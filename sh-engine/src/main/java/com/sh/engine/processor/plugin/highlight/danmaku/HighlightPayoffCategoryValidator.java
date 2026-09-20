package com.sh.engine.processor.plugin.highlight.danmaku;

import com.sh.engine.model.danmaku.HighlightClipRange;
import com.sh.engine.model.danmaku.HighlightEvidenceCatalog;
import com.sh.engine.model.danmaku.HighlightEvidenceItem;
import com.sh.engine.model.danmaku.HighlightEvidenceReference;
import com.sh.engine.model.danmaku.HighlightPayoffAssessment;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 对不同看点类别执行各自必要、且只与该类别相关的证据校验。 */
final class HighlightPayoffCategoryValidator {
    private static final int MINIMUM_EMOTION_STRENGTH = 70;

    void appendViolations(
            List<String> violations,
            HighlightPayoffAssessment payoff,
            String category,
            HighlightEvidenceCatalog evidenceCatalog,
            HighlightClipRange clipRange) {
        appendSpectacleViolations(violations, payoff, category);
        appendSkillViolations(violations, payoff, category);
        appendReversalViolations(
                violations, payoff, category, evidenceCatalog, clipRange);
        appendTensionViolations(
                violations, payoff, category, evidenceCatalog, clipRange);
        appendSocialViolations(violations, payoff, category);
        appendInformationalViolations(
                violations, payoff, category, evidenceCatalog, clipRange);
    }

    private void appendSpectacleViolations(
            List<String> violations,
            HighlightPayoffAssessment payoff,
            String category) {
        if (!"SPECTACLE".equals(category)) {
            return;
        }
        if (!Boolean.TRUE.equals(payoff.getSpectacleVisualImpact())) {
            violations.add("视觉奇观没有单个主导性的高冲击视觉事件");
        }
        if (!Boolean.FALSE.equals(payoff.getSystemControlledPresentation())) {
            violations.add("视觉冲击主要来自标准系统动画、过场或固定结果演出");
        }
    }

    private void appendSkillViolations(
            List<String> violations,
            HighlightPayoffAssessment payoff,
            String category) {
        if ("SKILL".equals(category)
                && !Boolean.TRUE.equals(payoff.getParticipantActionAndResultVisible())) {
            violations.add("技巧看点没有同时直接呈现参与者动作和可归因结果");
        }
    }

    private void appendReversalViolations(
            List<String> violations,
            HighlightPayoffAssessment payoff,
            String category,
            HighlightEvidenceCatalog evidenceCatalog,
            HighlightClipRange clipRange) {
        if (!"REVERSAL".equals(category)) {
            return;
        }
        if (!Boolean.TRUE.equals(payoff.getReversalExpectationEstablished())) {
            violations.add("反转前没有在成片内建立明确预期或稳定状态");
        }
        List<HighlightEvidenceItem> expectation = resolveEvidence(
                violations, "反转前置预期", payoff.getReversalExpectationEvidence(),
                evidenceCatalog, clipRange);
        List<HighlightEvidenceItem> contradiction = resolveEvidence(
                violations, "反转后续推翻", payoff.getReversalContradictionEvidence(),
                evidenceCatalog, clipRange);
        appendOrderViolation(violations, expectation, contradiction,
                "反转证据没有先建立预期再出现推翻");
    }

    private void appendTensionViolations(
            List<String> violations,
            HighlightPayoffAssessment payoff,
            String category,
            HighlightEvidenceCatalog evidenceCatalog,
            HighlightClipRange clipRange) {
        if (!"TENSION".equals(category)) {
            return;
        }
        if (!Boolean.TRUE.equals(payoff.getEscalatingConflictAndResolution())) {
            violations.add("紧张事件没有形成风险或冲突升级并得到明确解决");
        }
        List<HighlightEvidenceItem> setup = resolveEvidence(
                violations, "紧张事件铺垫", payoff.getTensionSetupEvidence(),
                evidenceCatalog, clipRange);
        List<HighlightEvidenceItem> resolution = resolveEvidence(
                violations, "紧张事件解决", payoff.getTensionResolutionEvidence(),
                evidenceCatalog, clipRange);
        appendOrderViolation(violations, setup, resolution,
                "紧张事件证据没有先建立风险再出现解决");
    }

    private void appendSocialViolations(
            List<String> violations,
            HighlightPayoffAssessment payoff,
            String category) {
        if (isSocialPayoff(category)
                && !Boolean.TRUE.equals(payoff.getCompletedSetupAndPayoff())) {
            violations.add("社交或情绪看点没有在精剪内完整呈现具体铺垫与兑现");
        }
        if ("EMOTION".equals(category)) {
            if (!Boolean.TRUE.equals(payoff.getDistinctiveEmotionalEscalation())) {
                violations.add("情绪看点没有呈现明显超出日常反应的升级");
            }
            Integer strength = payoff.getStrength();
            if (strength == null || strength < MINIMUM_EMOTION_STRENGTH) {
                violations.add("情绪看点强度低于70");
            }
        }
    }

    private void appendInformationalViolations(
            List<String> violations,
            HighlightPayoffAssessment payoff,
            String category,
            HighlightEvidenceCatalog evidenceCatalog,
            HighlightClipRange clipRange) {
        if (!"INFORMATIONAL".equals(category)) {
            return;
        }
        if (!Boolean.TRUE.equals(payoff.getReusableInformation())) {
            violations.add("信息型片段没有直接传递可复用信息");
        }
        if (StringUtils.isBlank(payoff.getReusableTakeaway())) {
            violations.add("信息型片段的可复用结论为空");
        }
        if (evidenceCatalog != null && !containsSource(
                payoff.getPayoffEvidence(), evidenceCatalog, clipRange, "ASR")) {
            violations.add("信息型片段缺少成片内口头解释的一手证据");
        }
    }

    private void appendOrderViolation(
            List<String> violations,
            List<HighlightEvidenceItem> before,
            List<HighlightEvidenceItem> after,
            String orderViolation) {
        if (before.isEmpty() || after.isEmpty()) {
            return;
        }
        int latestBefore = before.stream().mapToInt(HighlightEvidenceItem::getEndSecond)
                .max().orElse(Integer.MAX_VALUE);
        int earliestAfter = after.stream().mapToInt(HighlightEvidenceItem::getStartSecond)
                .min().orElse(Integer.MIN_VALUE);
        if (latestBefore >= earliestAfter) {
            violations.add(orderViolation);
        }
    }

    private List<HighlightEvidenceItem> resolveEvidence(
            List<String> violations,
            String name,
            List<HighlightEvidenceReference> references,
            HighlightEvidenceCatalog evidenceCatalog,
            HighlightClipRange clipRange) {
        if (CollectionUtils.isEmpty(references)) {
            violations.add(name + "缺少结构化一手证据映射");
            return Collections.emptyList();
        }
        if (evidenceCatalog == null) {
            return Collections.emptyList();
        }
        List<HighlightEvidenceItem> resolved = new ArrayList<>();
        for (HighlightEvidenceReference reference : references) {
            HighlightEvidenceItem item = evidenceCatalog.resolve(reference);
            if (item == null) {
                violations.add(name + "证据编号不存在或与可信来源冲突");
            } else if ("DANMAKU".equals(item.getSource())) {
                violations.add(name + "不能只用弹幕证明");
            } else if (clipRange != null && !item.isInside(clipRange)) {
                violations.add(name + "证据不在最终精剪区间内");
            } else {
                resolved.add(item);
            }
        }
        return resolved;
    }

    private boolean containsSource(
            List<HighlightEvidenceReference> references,
            HighlightEvidenceCatalog evidenceCatalog,
            HighlightClipRange clipRange,
            String source) {
        if (references == null) {
            return false;
        }
        return references.stream()
                .map(evidenceCatalog::resolve)
                .anyMatch(item -> item != null && source.equals(item.getSource())
                        && (clipRange == null || item.isInside(clipRange)));
    }

    private boolean isSocialPayoff(String category) {
        return "COMEDY".equals(category)
                || "EMOTION".equals(category)
                || "INTERACTION".equals(category);
    }
}
