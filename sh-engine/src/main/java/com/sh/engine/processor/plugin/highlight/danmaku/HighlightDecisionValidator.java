package com.sh.engine.processor.plugin.highlight.danmaku;

import com.sh.engine.model.danmaku.HighlightClipRange;
import com.sh.engine.model.danmaku.HighlightEvidenceCatalog;
import com.sh.engine.model.danmaku.HighlightEvidenceItem;
import com.sh.engine.model.danmaku.HighlightEvidenceReference;
import com.sh.engine.model.danmaku.HighlightFactAlignment;
import com.sh.engine.model.danmaku.HighlightFactVerification;
import com.sh.engine.model.danmaku.HighlightPayoffAssessment;
import com.sh.engine.model.danmaku.HighlightPublicationAssessment;
import com.sh.engine.model.danmaku.HighlightPublicationQuality;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 对事实验真和独立发布评审执行与题材无关的本地硬校验。 */
@Component
public class HighlightDecisionValidator {
    public static final int MINIMUM_SCORE = 65;
    private static final int MINIMUM_AUDIENCE_VALUE = 65;
    private static final int MINIMUM_CONTENT_DENSITY = 55;
    private static final int MINIMUM_PAYOFF_RECALL_STRENGTH = 55;
    private static final int MAXIMUM_NO_DEVELOPMENT_SECONDS = 12;
    private static final int MINIMUM_TITLE_CODE_POINTS = 8;
    private static final int MAXIMUM_TITLE_CODE_POINTS = 18;
    private static final int MINIMUM_COVER_TEXT_CODE_POINTS = 4;
    private static final int MAXIMUM_COVER_TEXT_CODE_POINTS = 12;
    private static final Pattern CAUSAL_LANGUAGE = Pattern.compile(
            "导致|致使|造成|所以|因此|从而|害得|结果是|结果却");
    private static final Pattern UNSAFE_EDITORIAL_LANGUAGE = Pattern.compile(
            "傻[逼币比狗子]|煞笔|沙比|蠢货|废物|弱智|脑残|狗东西|狗日|妈的|他妈|"
                    + "操你|草你|滚蛋|fuck|shit",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Set<String> PAYOFF_CATEGORIES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "SPECTACLE", "SKILL", "REVERSAL", "TENSION", "COMEDY", "EMOTION",
                    "INTERACTION", "INFORMATIONAL", "ROUTINE")));
    private final HighlightPayoffCategoryValidator payoffCategoryValidator =
            new HighlightPayoffCategoryValidator();

    /**
     * 对两阶段结果进行最终决策，并返回代码计算出的可信剪辑区间和封面时间。
     */
    public Decision validate(
            HighlightFactVerification facts,
            HighlightPublicationAssessment publication,
            HighlightEvidenceCatalog factEvidenceCatalog,
            HighlightEvidenceCatalog publicationEvidenceCatalog,
            HighlightClipBoundaryResolver.Resolution boundary) {
        List<String> violations = findFactViolations(facts, factEvidenceCatalog);
        HighlightClipRange clipRange = boundary == null ? null : boundary.getClipRange();
        appendPublicationViolations(
                violations, facts, publication, publicationEvidenceCatalog, clipRange);
        if (boundary == null) {
            violations.add("精剪边界决策为空");
        } else {
            violations.addAll(boundary.getViolations());
        }
        return violations.isEmpty()
                ? Decision.accepted(boundary.getClipRange(), boundary.getCoverTimestamp(),
                        boundary.getEventAnchorTimestamp())
                : Decision.rejected(violations);
    }

    /** 返回事实模型结论与一手证据之间的全部结构性冲突。 */
    public List<String> findFactViolations(
            HighlightFactVerification facts,
            HighlightEvidenceCatalog evidenceCatalog) {
        List<String> violations = new ArrayList<>();
        if (facts == null) {
            violations.add("事实验真结果为空");
            return violations;
        }
        appendRequiredText(violations, "铺垫", facts.getSetup());
        appendRequiredText(violations, "动作", facts.getAction());
        appendRequiredText(violations, "结果", facts.getOutcome());
        appendRequiredFlag(violations, "关键主体未确认", facts.getSubjectResolved());
        appendRequiredFlag(violations, "关键动作没有直接证据", facts.getActionDirectlySupported());
        appendRequiredFlag(violations, "结果没有直接证据", facts.getOutcomeDirectlySupported());
        appendRequiredFlag(violations, "结果状态或数值含义未确认", facts.getOutcomeMeaningResolved());
        appendRequiredFlag(violations, "动作与结果不属于可确认的同一连续事件",
                facts.getEventContinuityResolved());
        appendBlockingIssueViolations(violations, facts);
        if (facts.getClaimsCausalLink() == null
                || facts.getCausalLinkResolved() == null) {
            violations.add("因果声明或因果证据状态缺失");
        }
        if (Boolean.TRUE.equals(facts.getClaimsCausalLink())
                && !Boolean.TRUE.equals(facts.getCausalLinkResolved())) {
            violations.add("事件描述声称因果，但因果关系没有直接证据");
        }
        appendReferenceViolations(
                violations, "铺垫", facts.getSetupEvidence(), evidenceCatalog, false);
        appendReferenceViolations(
                violations, "动作", facts.getActionEvidence(), evidenceCatalog, false);
        appendReferenceViolations(
                violations, "结果", facts.getOutcomeEvidence(), evidenceCatalog, false);
        if (StringUtils.isNotBlank(facts.getReaction())) {
            appendReferenceViolations(
                    violations, "反应", facts.getReactionEvidence(), evidenceCatalog, true);
        }
        appendEventOrderViolations(violations, facts, evidenceCatalog);
        return violations;
    }

    private void appendBlockingIssueViolations(
            List<String> violations, HighlightFactVerification facts) {
        if (facts.getBlockingIssues() == null || facts.getLimitations() == null) {
            violations.add("事实核验未区分阻断项和非阻断限制");
            return;
        }
        facts.getBlockingIssues().stream()
                .filter(StringUtils::isNotBlank)
                .forEach(issue -> violations.add("事实验真阻断项：" + issue));
    }

    private void appendPublicationViolations(
            List<String> violations,
            HighlightFactVerification facts,
            HighlightPublicationAssessment publication,
            HighlightEvidenceCatalog evidenceCatalog,
            HighlightClipRange clipRange) {
        if (publication == null) {
            violations.add("发布价值评审为空");
            return;
        }
        if (!Boolean.TRUE.equals(publication.getPublishable())) {
            violations.add("独立发布价值评审未通过");
        }
        appendMinimumViolation(
                violations, "发布价值总分", publication.getScore(), MINIMUM_SCORE);
        appendRequiredText(violations, "发布评审原因", publication.getReason());
        appendRequiredText(violations, "一句话故事", publication.getOneSentenceStory());
        appendTextLengthViolation(violations, "标题", publication.getSuggestedTitle(),
                MINIMUM_TITLE_CODE_POINTS, MAXIMUM_TITLE_CODE_POINTS);
        appendTextLengthViolation(violations, "封面文案", publication.getCoverText(),
                MINIMUM_COVER_TEXT_CODE_POINTS, MAXIMUM_COVER_TEXT_CODE_POINTS);
        appendEditorialSafetyViolation(
                violations, "标题", publication.getSuggestedTitle());
        appendEditorialSafetyViolation(
                violations, "封面文案", publication.getCoverText());
        if (facts != null && !Boolean.TRUE.equals(facts.getCausalLinkResolved())) {
            String claims = StringUtils.defaultString(publication.getOneSentenceStory())
                    + " " + StringUtils.defaultString(publication.getSuggestedTitle());
            if (CAUSAL_LANGUAGE.matcher(claims).find()) {
                violations.add("未确认因果关系时，故事或标题不得声称因果");
            }
        }
        appendFactAlignmentViolations(violations, publication.getFactAlignment());
        appendPayoffViolations(
                violations, publication.getPayoff(), evidenceCatalog, clipRange);
        appendQualityViolations(violations, publication.getQuality());
    }

    private void appendFactAlignmentViolations(
            List<String> violations, HighlightFactAlignment alignment) {
        if (alignment == null) {
            violations.add("缺少精剪事实对齐复核");
            return;
        }
        appendRequiredText(violations, "事实对齐说明", alignment.getRationale());
        if (alignment.isFullyAligned()) {
            return;
        }
        if (!Boolean.TRUE.equals(alignment.getSetupSupported())) {
            violations.add("精剪内铺垫主张未得到支持");
        }
        if (!Boolean.TRUE.equals(alignment.getActionSupported())) {
            violations.add("精剪内动作主张未得到支持");
        }
        if (!Boolean.TRUE.equals(alignment.getOutcomeSupported())) {
            violations.add("精剪内结果主张未得到支持");
        }
        if (!Boolean.TRUE.equals(alignment.getSameEventSupported())) {
            violations.add("精剪内铺垫、动作和结果未形成同一连续事件");
        }
        if (alignment.getUnsupportedClaims() == null) {
            violations.add("未输出不受支持主张列表");
        } else {
            alignment.getUnsupportedClaims().stream()
                    .filter(StringUtils::isNotBlank)
                    .forEach(claim -> violations.add("精剪事实不对齐：" + claim));
        }
    }

    private void appendPayoffViolations(
            List<String> violations,
            HighlightPayoffAssessment payoff,
            HighlightEvidenceCatalog evidenceCatalog,
            HighlightClipRange clipRange) {
        violations.addAll(findPayoffViolations(payoff, evidenceCatalog, clipRange));
    }

    /** 返回独立看点评估未达到发布门槛的原因。 */
    public List<String> findPayoffViolations(HighlightPayoffAssessment payoff) {
        return findPayoffViolations(payoff, null, null);
    }

    /** 返回看点协议及其一手证据映射未达到发布门槛的原因。 */
    public List<String> findPayoffViolations(
            HighlightPayoffAssessment payoff,
            HighlightEvidenceCatalog evidenceCatalog,
            HighlightClipRange clipRange) {
        List<String> violations = new ArrayList<>();
        if (payoff == null) {
            violations.add("缺少实质看点评估");
            return violations;
        }
        appendRequiredText(violations, "看点类别", payoff.getCategory());
        appendRequiredText(violations, "主导兑现时刻", payoff.getDominantMoment());
        String category = StringUtils.trimToEmpty(payoff.getCategory())
                .toUpperCase(Locale.ROOT);
        if (!category.isEmpty() && !PAYOFF_CATEGORIES.contains(category)) {
            violations.add("看点类别不在允许范围内");
        }
        if (!Boolean.TRUE.equals(payoff.getMeaningful())) {
            violations.add("片段没有实质看点");
        }
        if (!Boolean.TRUE.equals(payoff.getStandalonePayoff())) {
            violations.add("片段自身没有可独立感知的兑现点");
        }
        if (!Boolean.TRUE.equals(payoff.getDominantMomentDirectlySupported())) {
            violations.add("主导兑现时刻没有成片内直接音画证据");
        }
        if (!Boolean.FALSE.equals(payoff.getOrdinaryProcessOnly())) {
            violations.add("片段只是普通流程或界面切换");
        }
        if (payoff.isRoutine()) {
            violations.add("片段仅为普通过程");
        }
        appendPayoffEvidenceViolations(
                violations, payoff, category, evidenceCatalog, clipRange);
        payoffCategoryValidator.appendViolations(
                violations, payoff, category, evidenceCatalog, clipRange);
        appendMinimumViolation(
                violations, "实质看点召回强度", payoff.getStrength(),
                MINIMUM_PAYOFF_RECALL_STRENGTH);
        return violations;
    }

    private void appendPayoffEvidenceViolations(
            List<String> violations,
            HighlightPayoffAssessment payoff,
            String category,
            HighlightEvidenceCatalog evidenceCatalog,
            HighlightClipRange clipRange) {
        List<HighlightEvidenceReference> references = payoff.getPayoffEvidence();
        if (CollectionUtils.isEmpty(references)) {
            violations.add("主导兑现时刻缺少结构化一手证据映射");
            return;
        }
        if (evidenceCatalog == null) {
            return;
        }
        Set<String> uniqueIds = new HashSet<>();
        List<HighlightEvidenceItem> resolved = new ArrayList<>();
        for (HighlightEvidenceReference reference : references) {
            HighlightEvidenceItem item = evidenceCatalog.resolve(reference);
            if (item == null) {
                violations.add("看点证据编号不存在或与可信来源冲突");
            } else if ("DANMAKU".equals(item.getSource())) {
                violations.add("弹幕不能直接证明主导兑现时刻");
            } else if (!uniqueIds.add(item.getEvidenceId())) {
                violations.add("看点重复引用同一证据编号");
            } else if (clipRange != null && !item.isInside(clipRange)) {
                violations.add("看点证据不在最终精剪区间内");
            } else {
                resolved.add(item);
            }
        }
        if ("SPECTACLE".equals(category) && resolved.stream()
                .noneMatch(item -> "VISION".equals(item.getSource()))) {
            violations.add("视觉奇观看点缺少直接画面证据");
        }
    }

    private void appendEventOrderViolations(
            List<String> violations,
            HighlightFactVerification facts,
            HighlightEvidenceCatalog evidenceCatalog) {
        List<HighlightEvidenceItem> actions = evidenceCatalog.resolveAll(
                facts.getActionEvidence());
        List<HighlightEvidenceItem> outcomes = evidenceCatalog.resolveAll(
                facts.getOutcomeEvidence());
        if (actions.isEmpty() || outcomes.isEmpty()) {
            return;
        }
        int firstAction = actions.stream().mapToInt(HighlightEvidenceItem::getStartSecond)
                .min().orElse(Integer.MAX_VALUE);
        int lastOutcome = outcomes.stream().mapToInt(HighlightEvidenceItem::getEndSecond)
                .max().orElse(Integer.MIN_VALUE);
        if (lastOutcome <= firstAction) {
            violations.add("结果证据没有发生在关键动作之后");
        }
    }

    private void appendReferenceViolations(
            List<String> violations,
            String fieldName,
            List<HighlightEvidenceReference> references,
            HighlightEvidenceCatalog evidenceCatalog,
            boolean danmakuAllowed) {
        if (CollectionUtils.isEmpty(references)) {
            violations.add(fieldName + "缺少结构化一手证据映射");
            return;
        }
        Set<String> uniqueIds = new HashSet<>();
        for (HighlightEvidenceReference reference : references) {
            HighlightEvidenceItem item = evidenceCatalog == null
                    ? null : evidenceCatalog.resolve(reference);
            if (item == null) {
                violations.add(fieldName + "证据编号不存在或与可信来源冲突");
            } else if (!danmakuAllowed && "DANMAKU".equals(item.getSource())) {
                violations.add(fieldName + "不能用弹幕证明客观事件事实");
            } else if (!uniqueIds.add(item.getEvidenceId())) {
                violations.add(fieldName + "重复引用同一证据编号");
            }
        }
    }

    private void appendQualityViolations(
            List<String> violations, HighlightPublicationQuality quality) {
        if (quality == null) {
            violations.add("缺少独立的观看价值和内容密度评分");
            return;
        }
        appendRequiredText(violations, "质量评分说明", quality.getRationale());
        appendMinimumViolation(violations, "陌生观众观看价值",
                quality.getAudienceValue(), MINIMUM_AUDIENCE_VALUE);
        appendMinimumViolation(violations, "内容密度",
                quality.getContentDensity(), MINIMUM_CONTENT_DENSITY);
        Integer longestGap = quality.getLongestNoDevelopmentSeconds();
        if (longestGap == null || longestGap < 0) {
            violations.add("最长无进展时长缺失或无效");
        } else if (longestGap > MAXIMUM_NO_DEVELOPMENT_SECONDS) {
            violations.add("最长无进展时长为" + longestGap + "秒，超过"
                    + MAXIMUM_NO_DEVELOPMENT_SECONDS + "秒");
        }
    }

    private void appendMinimumViolation(
            List<String> violations, String name, Integer value, int minimum) {
        if (value == null || value < 0 || value > 100) {
            violations.add(name + "评分缺失或无效");
        } else if (value < minimum) {
            violations.add(name + "评分为" + value + "，低于" + minimum);
        }
    }

    private void appendRequiredText(
            List<String> violations, String name, String value) {
        if (StringUtils.isBlank(value)) {
            violations.add(name + "为空");
        }
    }

    private void appendRequiredFlag(
            List<String> violations, String message, Boolean value) {
        if (!Boolean.TRUE.equals(value)) {
            violations.add(message);
        }
    }

    private void appendTextLengthViolation(
            List<String> violations,
            String name,
            String value,
            int minimum,
            int maximum) {
        if (StringUtils.isBlank(value)) {
            violations.add(name + "为空");
            return;
        }
        int length = value.codePointCount(0, value.length());
        if (length < minimum || length > maximum) {
            violations.add(name + "长度为" + length + "，必须为" + minimum + "~" + maximum + "字");
        }
    }

    private void appendEditorialSafetyViolation(
            List<String> violations, String name, String value) {
        if (StringUtils.isNotBlank(value)
                && UNSAFE_EDITORIAL_LANGUAGE.matcher(value).find()) {
            violations.add(name + "包含脏话、侮辱性称呼或人身攻击");
        }
    }

    /** 最终本地决策；拒绝结果不携带可用于产出的区间或封面。 */
    public static final class Decision {
        private final HighlightClipRange clipRange;
        private final Integer coverTimestamp;
        private final Integer eventAnchorTimestamp;
        private final List<String> violations;

        private Decision(
                HighlightClipRange clipRange,
                Integer coverTimestamp,
                Integer eventAnchorTimestamp,
                List<String> violations) {
            this.clipRange = clipRange;
            this.coverTimestamp = coverTimestamp;
            this.eventAnchorTimestamp = eventAnchorTimestamp;
            this.violations = Collections.unmodifiableList(new ArrayList<>(violations));
        }

        private static Decision accepted(
                HighlightClipRange clipRange,
                Integer coverTimestamp,
                Integer eventAnchorTimestamp) {
            return new Decision(clipRange, coverTimestamp,
                    eventAnchorTimestamp, Collections.emptyList());
        }

        private static Decision rejected(List<String> violations) {
            return new Decision(null, null, null, violations);
        }

        public boolean isAccepted() {
            return violations.isEmpty();
        }

        public HighlightClipRange getClipRange() {
            return clipRange;
        }

        public Integer getCoverTimestamp() {
            return coverTimestamp;
        }

        public Integer getEventAnchorTimestamp() {
            return eventAnchorTimestamp;
        }

        public List<String> getViolations() {
            return violations;
        }
    }
}
