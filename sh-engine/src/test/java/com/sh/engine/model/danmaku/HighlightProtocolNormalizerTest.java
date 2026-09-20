package com.sh.engine.model.danmaku;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class HighlightProtocolNormalizerTest {

    @Test
    public void shouldTreatEquivalentEmptyClaimsAsEmptyList() {
        HighlightFactAlignment alignment = new HighlightFactAlignment();
        alignment.setUnsupportedClaims(Arrays.asList("无", " ", "NONE。"));

        alignment.normalizeProtocolValues();

        Assert.assertEquals(Collections.emptyList(), alignment.getUnsupportedClaims());
    }

    @Test
    public void shouldPreserveSubstantiveClaims() {
        HighlightFactAlignment alignment = new HighlightFactAlignment();
        alignment.setUnsupportedClaims(Arrays.asList(
                "无证据证明两段画面属于同一事件", "主体只在较早画面出现"));

        alignment.normalizeProtocolValues();

        Assert.assertEquals(2, alignment.getUnsupportedClaims().size());
    }

    @Test
    public void shouldNormalizeFactIssuesAndOptionalTakeaway() {
        HighlightFactVerification facts = new HighlightFactVerification();
        facts.setBlockingIssues(Collections.singletonList("无阻断项"));
        facts.setLimitations(Collections.singletonList("没有"));
        HighlightPayoffAssessment payoff = new HighlightPayoffAssessment();
        payoff.setReusableTakeaway("N/A");

        facts.normalizeProtocolValues();
        payoff.normalizeProtocolValues();

        Assert.assertTrue(facts.getBlockingIssues().isEmpty());
        Assert.assertTrue(facts.getLimitations().isEmpty());
        Assert.assertEquals("", payoff.getReusableTakeaway());
    }

    @Test
    public void shouldKeepNullToExposeMissingRequiredField() {
        HighlightFactAlignment alignment = new HighlightFactAlignment();

        alignment.normalizeProtocolValues();

        Assert.assertNull(alignment.getUnsupportedClaims());
    }

    @Test
    public void shouldRequireOnlyFieldsForSelectedPayoffCategory() {
        HighlightPayoffAssessment payoff = completeCommonPayoff("SKILL");
        payoff.setParticipantActionAndResultVisible(true);

        Assert.assertTrue(payoff.hasCompleteProtocol());
        payoff.setParticipantActionAndResultVisible(null);
        Assert.assertFalse(payoff.hasCompleteProtocol());
    }

    @Test
    public void shouldRequireSystemPresentationFlagForSpectacle() {
        HighlightPayoffAssessment payoff = completeCommonPayoff("SPECTACLE");
        payoff.setSpectacleVisualImpact(true);

        Assert.assertFalse(payoff.hasCompleteProtocol());
        payoff.setSystemControlledPresentation(false);
        Assert.assertTrue(payoff.hasCompleteProtocol());
    }

    @Test
    public void shouldRequireBothStagesForTension() {
        HighlightPayoffAssessment payoff = completeCommonPayoff("TENSION");
        payoff.setEscalatingConflictAndResolution(true);
        payoff.setTensionSetupEvidence(Collections.emptyList());

        Assert.assertFalse(payoff.hasCompleteProtocol());
        payoff.setTensionResolutionEvidence(Collections.emptyList());
        Assert.assertTrue(payoff.hasCompleteProtocol());
    }

    @Test
    public void shouldRequireDistinctiveEscalationForEmotionProtocol() {
        HighlightPayoffAssessment payoff = completeCommonPayoff("EMOTION");
        payoff.setCompletedSetupAndPayoff(true);

        Assert.assertFalse(payoff.hasCompleteProtocol());
        payoff.setDistinctiveEmotionalEscalation(false);
        Assert.assertTrue(payoff.hasCompleteProtocol());
    }

    private HighlightPayoffAssessment completeCommonPayoff(String category) {
        HighlightPayoffAssessment payoff = new HighlightPayoffAssessment();
        payoff.setCategory(category);
        payoff.setMeaningful(true);
        payoff.setStrength(70);
        payoff.setDominantMoment("可定位的兑现时刻");
        payoff.setPayoffEvidence(Collections.emptyList());
        payoff.setDominantMomentDirectlySupported(true);
        payoff.setOrdinaryProcessOnly(false);
        payoff.setStandalonePayoff(true);
        return payoff;
    }
}
