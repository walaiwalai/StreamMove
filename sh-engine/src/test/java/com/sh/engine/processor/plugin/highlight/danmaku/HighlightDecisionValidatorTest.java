package com.sh.engine.processor.plugin.highlight.danmaku;

import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.model.danmaku.HighlightClipRange;
import com.sh.engine.model.danmaku.HighlightEvidenceCatalog;
import com.sh.engine.model.danmaku.HighlightEvidenceItem;
import com.sh.engine.model.danmaku.HighlightEvidenceReference;
import com.sh.engine.model.danmaku.HighlightFactAlignment;
import com.sh.engine.model.danmaku.HighlightFactVerification;
import com.sh.engine.model.danmaku.HighlightPayoffAssessment;
import com.sh.engine.model.danmaku.HighlightPublicationAssessment;
import com.sh.engine.model.danmaku.HighlightPublicationQuality;
import com.sh.engine.model.danmaku.VisualObservation;
import com.sh.engine.model.danmaku.VisualTimelineResult;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

public class HighlightDecisionValidatorTest {

    @Test
    public void shouldAcceptIndependentFactAndPublicationReviews() {
        HighlightDecisionValidator.Decision decision = validator().validate(
                completeFacts(), publishableAssessment(), evidenceCatalog(), evidenceCatalog(),
                boundary(completeFacts(), evidenceCatalog(),
                        new HashSet<>(Arrays.asList(20, 30))));

        Assert.assertTrue(decision.isAccepted());
        Assert.assertEquals(5, decision.getClipRange().getStartSecond());
        Assert.assertEquals(33, decision.getClipRange().getEndSecond());
        Assert.assertEquals(Integer.valueOf(20), decision.getCoverTimestamp());
    }

    @Test
    public void shouldRejectWhenSpokenOutcomeHasUnresolvedSubject() {
        HighlightFactVerification facts = completeFacts();
        facts.setSubjectResolved(false);
        facts.setBlockingIssues(Collections.singletonList("无法确认画面中关键动作的主体"));

        HighlightDecisionValidator.Decision decision = validator().validate(
                facts, publishableAssessment(), evidenceCatalog(), evidenceCatalog(),
                boundary(facts, evidenceCatalog(), Collections.singleton(20)));

        Assert.assertFalse(decision.isAccepted());
        Assert.assertTrue(decision.getViolations().contains("关键主体未确认"));
        Assert.assertTrue(decision.getViolations().contains(
                "事实验真阻断项：无法确认画面中关键动作的主体"));
    }

    @Test
    public void shouldRejectWhenNumericChangeMeaningIsUnresolved() {
        HighlightFactVerification facts = completeFacts();
        facts.setOutcomeMeaningResolved(false);

        HighlightDecisionValidator.Decision decision = validator().validate(
                facts, publishableAssessment(), evidenceCatalog(), evidenceCatalog(),
                boundary(facts, evidenceCatalog(), Collections.singleton(20)));

        Assert.assertFalse(decision.isAccepted());
        Assert.assertTrue(decision.getViolations().contains(
                "结果状态或数值含义未确认"));
    }

    @Test
    public void shouldRejectCausalTitleWithoutVerifiedCausalLink() {
        HighlightPublicationAssessment publication = publishableAssessment();
        publication.setOneSentenceStory("人物走上舞台，结果却让灯光亮起");

        HighlightDecisionValidator.Decision decision = validator().validate(
                completeFacts(), publication, evidenceCatalog(), evidenceCatalog(),
                boundary(completeFacts(), evidenceCatalog(), Collections.singleton(20)));

        Assert.assertFalse(decision.isAccepted());
        Assert.assertTrue(decision.getViolations().contains(
                "未确认因果关系时，故事或标题不得声称因果"));
    }

    @Test
    public void shouldRejectUnknownEvidenceIdBeforePublication() {
        HighlightFactVerification facts = completeFacts();
        facts.setOutcomeEvidence(Collections.singletonList(reference("VISION-999")));

        Assert.assertTrue(validator().findFactViolations(
                facts, evidenceCatalog()).contains("结果证据编号不存在或与可信来源冲突"));
    }

    @Test
    public void shouldAcceptStableDanmakuIdForReactionEvidence() {
        HighlightFactVerification facts = completeFacts();
        facts.setReaction("观众集中表达惊讶");
        facts.setReactionEvidence(Collections.singletonList(reference("DANMAKU-001")));

        Assert.assertTrue(validator().findFactViolations(
                facts, evidenceCatalogWithDanmaku()).isEmpty());
    }

    @Test
    public void shouldRejectDanmakuAsObjectiveOutcomeEvidence() {
        HighlightFactVerification facts = completeFacts();
        facts.setOutcomeEvidence(Collections.singletonList(reference("DANMAKU-001")));

        Assert.assertTrue(validator().findFactViolations(
                facts, evidenceCatalogWithDanmaku()).contains(
                        "结果不能用弹幕证明客观事件事实"));
    }

    @Test
    public void shouldAllowLimitationsOutsideTheAssertedEvent() {
        HighlightFactVerification facts = completeFacts();
        facts.setLimitations(Arrays.asList(
                "无法确认灯光变化的后台操作原因", "没有主张该人物导致灯光变化"));

        Assert.assertTrue(validator().findFactViolations(
                facts, evidenceCatalog()).isEmpty());
    }

    @Test
    public void shouldRejectOverallPublicationScoreBelowQualityThreshold() {
        HighlightPublicationAssessment publication = publishableAssessment();
        publication.setScore(62);

        HighlightDecisionValidator.Decision decision = validator().validate(
                completeFacts(), publication, evidenceCatalog(), evidenceCatalog(),
                boundary(completeFacts(), evidenceCatalog(), Collections.singleton(20)));

        Assert.assertFalse(decision.isAccepted());
        Assert.assertTrue(decision.getViolations().contains("发布价值总分评分为62，低于65"));
    }

    @Test
    public void shouldAllowModerateDensityWhenPayoffAndOverallValuePass() {
        HighlightPublicationAssessment publication = publishableAssessment();
        publication.getQuality().setContentDensity(62);

        HighlightDecisionValidator.Decision decision = validator().validate(
                completeFacts(), publication, evidenceCatalog(), evidenceCatalog(),
                boundary(completeFacts(), evidenceCatalog(), Collections.singleton(20)));

        Assert.assertTrue(decision.isAccepted());
    }

    @Test
    public void shouldKeepBorderlinePayoffForIndependentPublicationReview() {
        HighlightPayoffAssessment payoff = publishableAssessment().getPayoff();
        payoff.setStrength(62);

        Assert.assertTrue(validator().findPayoffViolations(payoff).isEmpty());
    }

    @Test
    public void shouldRejectUnsupportedCompoundFactInExactClip() {
        HighlightPublicationAssessment publication = publishableAssessment();
        publication.getFactAlignment().setActionSupported(false);
        publication.getFactAlignment().setUnsupportedClaims(
                Collections.singletonList("动作中的主体只在较早画面出现"));

        HighlightDecisionValidator.Decision decision = validator().validate(
                completeFacts(), publication, evidenceCatalog(), evidenceCatalog(),
                boundary(completeFacts(), evidenceCatalog(), Collections.singleton(20)));

        Assert.assertFalse(decision.isAccepted());
        Assert.assertTrue(decision.getViolations().contains(
                "精剪内动作主张未得到支持"));
        Assert.assertTrue(decision.getViolations().contains(
                "精剪事实不对齐：动作中的主体只在较早画面出现"));
    }

    @Test
    public void shouldRejectRoutineMovementWithoutMeaningfulPayoff() {
        HighlightPublicationAssessment publication = publishableAssessment();
        publication.getPayoff().setCategory("ROUTINE");
        publication.getPayoff().setMeaningful(false);
        publication.getPayoff().setStrength(30);

        HighlightDecisionValidator.Decision decision = validator().validate(
                completeFacts(), publication, evidenceCatalog(), evidenceCatalog(),
                boundary(completeFacts(), evidenceCatalog(), Collections.singleton(20)));

        Assert.assertFalse(decision.isAccepted());
        Assert.assertTrue(decision.getViolations().contains("片段仅为普通过程"));
        Assert.assertTrue(decision.getViolations().contains("片段没有实质看点"));
    }

    @Test
    public void shouldRejectInformationalLabelWithoutReusableTakeaway() {
        HighlightPublicationAssessment publication = publishableAssessment();
        publication.getPayoff().setCategory("INFORMATIONAL");
        publication.getPayoff().setReusableInformation(false);
        publication.getPayoff().setReusableTakeaway("");

        HighlightDecisionValidator.Decision decision = validator().validate(
                completeFacts(), publication, evidenceCatalog(), evidenceCatalog(),
                boundary(completeFacts(), evidenceCatalog(), Collections.singleton(20)));

        Assert.assertFalse(decision.isAccepted());
        Assert.assertTrue(decision.getViolations().contains(
                "信息型片段没有直接传递可复用信息"));
        Assert.assertTrue(decision.getViolations().contains(
                "信息型片段的可复用结论为空"));
    }

    @Test
    public void shouldRejectSpectacleMadeOnlyFromOrdinaryTransitions() {
        HighlightPayoffAssessment payoff = publishableAssessment().getPayoff();
        payoff.setCategory("SPECTACLE");
        payoff.setOrdinaryProcessOnly(true);
        payoff.setReversalExpectationEstablished(false);
        payoff.setSpectacleVisualImpact(false);

        java.util.List<String> violations = validator().findPayoffViolations(
                payoff, evidenceCatalog(), new HighlightClipRange(5, 33));

        Assert.assertTrue(violations.contains("片段只是普通流程或界面切换"));
        Assert.assertTrue(violations.contains(
                "视觉奇观没有单个主导性的高冲击视觉事件"));
    }

    @Test
    public void shouldRejectSystemControlledPresentationAsSpectacle() {
        HighlightPayoffAssessment payoff = publishableAssessment().getPayoff();
        payoff.setCategory("SPECTACLE");
        payoff.setReversalExpectationEstablished(false);
        payoff.setSpectacleVisualImpact(true);
        payoff.setSystemControlledPresentation(true);

        java.util.List<String> violations = validator().findPayoffViolations(
                payoff, evidenceCatalog(), new HighlightClipRange(5, 33));

        Assert.assertTrue(violations.contains(
                "视觉冲击主要来自标准系统动画、过场或固定结果演出"));
    }

    @Test
    public void shouldRejectReversalWithoutDistinctExpectationEvidence() {
        HighlightPayoffAssessment payoff = publishableAssessment().getPayoff();
        payoff.setPayoffEvidence(Collections.singletonList(reference("VISION-002")));
        payoff.setReversalExpectationEstablished(false);
        payoff.setReversalExpectationEvidence(Collections.emptyList());

        java.util.List<String> violations = validator().findPayoffViolations(
                payoff, evidenceCatalog(), new HighlightClipRange(5, 33));

        Assert.assertTrue(violations.contains(
                "反转前置预期缺少结构化一手证据映射"));
        Assert.assertTrue(violations.contains(
                "反转前没有在成片内建立明确预期或稳定状态"));
    }

    @Test
    public void shouldRejectReversalWhenContradictionDoesNotFollowExpectation() {
        HighlightPayoffAssessment payoff = publishableAssessment().getPayoff();
        payoff.setReversalExpectationEvidence(
                Collections.singletonList(reference("VISION-002")));
        payoff.setReversalContradictionEvidence(
                Collections.singletonList(reference("VISION-001")));

        java.util.List<String> violations = validator().findPayoffViolations(
                payoff, evidenceCatalog(), new HighlightClipRange(5, 33));

        Assert.assertTrue(violations.contains(
                "反转证据没有先建立预期再出现推翻"));
    }

    @Test
    public void shouldRejectSkillWithoutVisibleActionAndResult() {
        HighlightPayoffAssessment payoff = publishableAssessment().getPayoff();
        payoff.setCategory("SKILL");
        payoff.setReversalExpectationEstablished(false);
        payoff.setParticipantActionAndResultVisible(false);

        Assert.assertTrue(validator().findPayoffViolations(
                payoff, evidenceCatalog(), new HighlightClipRange(5, 33)).contains(
                        "技巧看点没有同时直接呈现参与者动作和可归因结果"));
    }

    @Test
    public void shouldAcceptEscalatingTensionWithOrderedResolution() {
        HighlightPayoffAssessment payoff = publishableAssessment().getPayoff();
        payoff.setCategory("TENSION");
        payoff.setEscalatingConflictAndResolution(true);
        payoff.setTensionSetupEvidence(
                Collections.singletonList(reference("VISION-001")));
        payoff.setTensionResolutionEvidence(
                Collections.singletonList(reference("VISION-002")));

        Assert.assertTrue(validator().findPayoffViolations(
                payoff, evidenceCatalog(), new HighlightClipRange(5, 33)).isEmpty());
    }

    @Test
    public void shouldRejectTensionWithoutOrderedResolution() {
        HighlightPayoffAssessment payoff = publishableAssessment().getPayoff();
        payoff.setCategory("TENSION");
        payoff.setEscalatingConflictAndResolution(true);
        payoff.setTensionSetupEvidence(
                Collections.singletonList(reference("VISION-002")));
        payoff.setTensionResolutionEvidence(
                Collections.singletonList(reference("VISION-001")));

        Assert.assertTrue(validator().findPayoffViolations(
                payoff, evidenceCatalog(), new HighlightClipRange(5, 33)).contains(
                        "紧张事件证据没有先建立风险再出现解决"));
    }

    @Test
    public void shouldRejectSocialPayoffWithoutCompletedSetup() {
        HighlightPayoffAssessment payoff = publishableAssessment().getPayoff();
        payoff.setCategory("COMEDY");
        payoff.setReversalExpectationEstablished(false);
        payoff.setCompletedSetupAndPayoff(false);

        Assert.assertTrue(validator().findPayoffViolations(
                payoff, evidenceCatalog(), new HighlightClipRange(5, 33)).contains(
                        "社交或情绪看点没有在精剪内完整呈现具体铺垫与兑现"));
    }

    @Test
    public void shouldRejectOrdinaryLowIntensityEmotion() {
        HighlightPayoffAssessment payoff = publishableAssessment().getPayoff();
        payoff.setCategory("EMOTION");
        payoff.setStrength(58);
        payoff.setCompletedSetupAndPayoff(true);
        payoff.setDistinctiveEmotionalEscalation(false);

        java.util.List<String> violations = validator().findPayoffViolations(
                payoff, evidenceCatalog(), new HighlightClipRange(5, 33));

        Assert.assertTrue(violations.contains(
                "情绪看点没有呈现明显超出日常反应的升级"));
        Assert.assertTrue(violations.contains("情绪看点强度低于70"));
    }

    @Test
    public void shouldRejectInformationalPayoffWithoutSpokenExplanation() {
        HighlightPayoffAssessment payoff = publishableAssessment().getPayoff();
        payoff.setCategory("INFORMATIONAL");
        payoff.setReversalExpectationEstablished(false);
        payoff.setReusableInformation(true);
        payoff.setReusableTakeaway("界面变化表示状态已经更新");
        payoff.setPayoffEvidence(Collections.singletonList(reference("VISION-002")));

        Assert.assertTrue(validator().findPayoffViolations(
                payoff, evidenceCatalog(), new HighlightClipRange(5, 33)).contains(
                        "信息型片段缺少成片内口头解释的一手证据"));
    }

    @Test
    public void shouldRejectUnsafeEditorialCopy() {
        HighlightPublicationAssessment publication = publishableAssessment();
        publication.setSuggestedTitle("灯光亮起后辱骂傻狗");

        HighlightDecisionValidator.Decision decision = validator().validate(
                completeFacts(), publication, evidenceCatalog(), evidenceCatalog(),
                boundary(completeFacts(), evidenceCatalog(), Collections.singleton(20)));

        Assert.assertFalse(decision.isAccepted());
        Assert.assertTrue(decision.getViolations().contains(
                "标题包含脏话、侮辱性称呼或人身攻击"));
    }

    private HighlightDecisionValidator validator() {
        return new HighlightDecisionValidator();
    }

    private HighlightClipBoundaryResolver.Resolution boundary(
            HighlightFactVerification facts,
            HighlightEvidenceCatalog evidenceCatalog,
            java.util.Set<Integer> coverCandidates) {
        return new HighlightClipBoundaryResolver().resolve(
                facts, new HighlightClipRange(0, 60), evidenceCatalog, coverCandidates);
    }

    private HighlightFactVerification completeFacts() {
        HighlightFactVerification facts = new HighlightFactVerification();
        facts.setSetup("说话者宣布开始一次尝试");
        facts.setSetupEvidence(Collections.singletonList(reference("ASR-001")));
        facts.setAction("人物从画面左侧走到舞台中央");
        facts.setActionEvidence(Collections.singletonList(reference("VISION-001")));
        facts.setOutcome("人物到达中央后舞台灯光变亮");
        facts.setOutcomeEvidence(Collections.singletonList(reference("VISION-002")));
        facts.setReaction("");
        facts.setReactionEvidence(Collections.emptyList());
        facts.setSubjectResolved(true);
        facts.setActionDirectlySupported(true);
        facts.setOutcomeDirectlySupported(true);
        facts.setOutcomeMeaningResolved(true);
        facts.setEventContinuityResolved(true);
        facts.setClaimsCausalLink(false);
        facts.setCausalLinkResolved(false);
        facts.setBlockingIssues(Collections.emptyList());
        facts.setLimitations(Collections.emptyList());
        facts.setCoverTimestamp("00:00:20");
        return facts;
    }

    private HighlightPublicationAssessment publishableAssessment() {
        HighlightPublicationAssessment publication = new HighlightPublicationAssessment();
        publication.setPublishable(true);
        publication.setScore(82);
        publication.setReason("变化清楚且可以独立理解");
        publication.setOneSentenceStory("人物走上舞台，随后灯光突然亮起");
        publication.setSuggestedTitle("走上舞台灯光突然亮起");
        publication.setCoverText("灯光突然亮起");
        HighlightFactAlignment alignment = new HighlightFactAlignment();
        alignment.setSetupSupported(true);
        alignment.setActionSupported(true);
        alignment.setOutcomeSupported(true);
        alignment.setSameEventSupported(true);
        alignment.setUnsupportedClaims(Collections.emptyList());
        alignment.setRationale("精剪图片和证据逐项支持故事的三个阶段");
        publication.setFactAlignment(alignment);
        HighlightPayoffAssessment payoff = new HighlightPayoffAssessment();
        payoff.setCategory("REVERSAL");
        payoff.setMeaningful(true);
        payoff.setStrength(82);
        payoff.setDominantMoment("舞台灯光从暗转亮");
        payoff.setPayoffEvidence(Arrays.asList(
                reference("VISION-001"), reference("VISION-002")));
        payoff.setDominantMomentDirectlySupported(true);
        payoff.setOrdinaryProcessOnly(false);
        payoff.setReversalExpectationEstablished(true);
        payoff.setReversalExpectationEvidence(
                Collections.singletonList(reference("VISION-001")));
        payoff.setReversalContradictionEvidence(
                Collections.singletonList(reference("VISION-002")));
        payoff.setSpectacleVisualImpact(false);
        payoff.setSystemControlledPresentation(false);
        payoff.setParticipantActionAndResultVisible(false);
        payoff.setCompletedSetupAndPayoff(false);
        payoff.setStandalonePayoff(true);
        payoff.setReusableInformation(false);
        payoff.setReusableTakeaway("");
        publication.setPayoff(payoff);
        HighlightPublicationQuality quality = new HighlightPublicationQuality();
        quality.setAudienceValue(80);
        quality.setContentDensity(85);
        quality.setLongestNoDevelopmentSeconds(5);
        quality.setRationale("事实完整且过程紧凑");
        publication.setQuality(quality);
        return publication;
    }

    private HighlightEvidenceCatalog evidenceCatalog() {
        VisualObservation action = observation(
                1, "00:00:20", "人物位于舞台中央", "人物从左侧移动到中央", true);
        VisualObservation outcome = observation(
                2, "00:00:30", "舞台灯光明亮", "灯光由暗变亮", true);
        VisualTimelineResult timeline = new VisualTimelineResult();
        timeline.setObservations(Arrays.asList(action, outcome));
        return new HighlightEvidenceCatalog(
                Collections.singletonList(AsrSegment.builder()
                        .startTime(5).endTime(7).text("现在开始尝试").build()),
                Collections.emptyList(), timeline);
    }

    private HighlightEvidenceCatalog evidenceCatalogWithDanmaku() {
        VisualTimelineResult timeline = new VisualTimelineResult();
        timeline.setObservations(Arrays.asList(
                observation(1, "00:00:20", "人物位于舞台中央",
                        "人物从左侧移动到中央", true),
                observation(2, "00:00:30", "舞台灯光明亮",
                        "灯光由暗变亮", true)));
        return new HighlightEvidenceCatalog(
                Collections.singletonList(AsrSegment.builder()
                        .startTime(5).endTime(7).text("现在开始尝试").build()),
                Collections.emptyList(), timeline,
                Collections.singletonList(new HighlightEvidenceItem(
                        "DANMAKU-001", "DANMAKU", 32, 32, "太离谱了！")));
    }

    private VisualObservation observation(
            int index, String time, String fact, String change, boolean coverCandidate) {
        VisualObservation observation = new VisualObservation();
        observation.setFrameIndex(index);
        observation.setTimestamp(time);
        observation.setObservableFacts(fact);
        observation.setVisibleChange(change);
        observation.setCertainty("high");
        observation.setCoverCandidate(coverCandidate);
        return observation;
    }

    private HighlightEvidenceReference reference(String evidenceId) {
        HighlightEvidenceReference reference = new HighlightEvidenceReference();
        reference.setEvidenceId(evidenceId);
        return reference;
    }
}
