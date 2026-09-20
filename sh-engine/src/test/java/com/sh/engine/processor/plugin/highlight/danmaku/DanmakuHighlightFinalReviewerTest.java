package com.sh.engine.processor.plugin.highlight.danmaku;

import com.sh.engine.model.danmaku.HighlightFactVerification;
import com.sh.engine.model.danmaku.HighlightEvidenceReference;
import com.sh.engine.model.danmaku.HighlightFactAlignment;
import com.sh.engine.model.danmaku.HighlightPayoffAssessment;
import com.sh.engine.model.danmaku.HighlightPublicationAssessment;
import com.sh.engine.model.danmaku.HighlightPublicationQuality;
import com.sh.engine.model.danmaku.VisualEvidenceBatch;
import com.sh.engine.model.danmaku.VisualFrameEvidence;
import com.sh.engine.model.llm.LlmCallResult;
import com.sh.engine.model.llm.LlmResponseException;
import com.sh.engine.service.LlmService;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DanmakuHighlightFinalReviewerTest {

    @Test
    public void shouldSeparateBlindVisualFactsFromPublicationValue() throws Exception {
        LlmService llmService = mock(LlmService.class);
        HighlightAnalysisCache cache = mock(HighlightAnalysisCache.class);
        HighlightAnalysisAuditRepository audit = mock(HighlightAnalysisAuditRepository.class);
        HighlightFactVerification facts = completeFacts();
        HighlightPublicationAssessment publication = completePublication();
        publication.setPublishable(false);
        when(llmService.analyzeImagesWithRaw(
                anyString(), anyList(), eq(HighlightFactVerification.class)))
                .thenThrow(new LlmResponseException("invalid fact", "bad-fact"))
                .thenReturn(new LlmCallResult<>("fact-raw", facts));
        when(llmService.analyzeImagesWithRaw(
                anyString(), anyList(), eq(HighlightPublicationAssessment.class)))
                .thenThrow(new LlmResponseException("invalid publication", "bad-publication"))
                .thenReturn(new LlmCallResult<>("publication-raw", publication));
        DanmakuHighlightFinalReviewer reviewer = new DanmakuHighlightFinalReviewer();
        inject(reviewer, "llmService", llmService);
        inject(reviewer, "analysisCache", cache);
        inject(reviewer, "auditRepository", audit);
        VisualEvidenceBatch batch = new VisualEvidenceBatch(
                Collections.singletonList(new VisualFrameEvidence(
                        25, "frame.jpg", "sha256", new byte[]{1, 2, 3})));

        HighlightFactVerification actualFacts = reviewer.verifyFacts(
                "streamer", "segment", new File("."), "中立密集证据", batch);
        HighlightPublicationAssessment actualPublication = reviewer.reviewPublication(
                "streamer", "segment", new File("."), "中立密集证据",
                facts, completePayoff(), batch);

        Assert.assertSame(facts, actualFacts);
        Assert.assertSame(publication, actualPublication);
        ArgumentCaptor<String> factPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmService, times(2)).analyzeImagesWithRaw(
                factPrompt.capture(), anyList(), eq(HighlightFactVerification.class));
        Assert.assertTrue(factPrompt.getValue().contains("不负责判断是否精彩"));
        Assert.assertTrue(factPrompt.getValue().contains("原始图片时间映射"));
        Assert.assertTrue(factPrompt.getValue().contains("时间顺序和连续性，不要求证明"));
        Assert.assertFalse(factPrompt.getValue().contains("稀疏证据初判"));
        ArgumentCaptor<String> publicationPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmService, times(2)).analyzeImagesWithRaw(
                publicationPrompt.capture(), anyList(),
                eq(HighlightPublicationAssessment.class));
        Assert.assertTrue(publicationPrompt.getValue().contains("verifiedFacts"));
        Assert.assertTrue(publicationPrompt.getValue().contains("verifiedPayoff"));
        Assert.assertTrue(publicationPrompt.getValue().contains("精剪内证据"));
        Assert.assertTrue(publicationPrompt.getValue().contains("待发布精剪真实图片"));
        Assert.assertTrue(publicationPrompt.getValue().contains("图片1=00:00:25"));
        Assert.assertTrue(publicationPrompt.getValue().contains("factAlignment"));
        Assert.assertTrue(publicationPrompt.getValue().contains("不重新分类看点"));
        Assert.assertTrue(publicationPrompt.getValue().contains(
                "必须同时体现片段内可见的具体行为/变化和结果/反差"));
        Assert.assertTrue(publicationPrompt.getValue().contains("4~12个汉字"));
        Assert.assertFalse(publicationPrompt.getValue().contains("factualCompleteness"));
        Assert.assertFalse(publicationPrompt.getValue().contains("稀疏证据初判"));
    }

    @Test
    public void shouldConfirmUnexplainedEmptyFactBeforeCaching() throws Exception {
        LlmService llmService = mock(LlmService.class);
        HighlightAnalysisCache cache = mock(HighlightAnalysisCache.class);
        HighlightAnalysisAuditRepository audit = mock(HighlightAnalysisAuditRepository.class);
        HighlightFactVerification empty = new HighlightFactVerification();
        empty.setSetup("");
        empty.setAction("");
        empty.setOutcome("");
        empty.setReaction("");
        empty.setBlockingIssues(Collections.emptyList());
        empty.setLimitations(Collections.emptyList());
        HighlightFactVerification confirmed = completeFacts();
        when(llmService.analyzeImagesWithRaw(
                anyString(), anyList(), eq(HighlightFactVerification.class)))
                .thenReturn(new LlmCallResult<>("empty-raw", empty))
                .thenReturn(new LlmCallResult<>("confirmed-raw", confirmed));
        DanmakuHighlightFinalReviewer reviewer = new DanmakuHighlightFinalReviewer();
        inject(reviewer, "llmService", llmService);
        inject(reviewer, "analysisCache", cache);
        inject(reviewer, "auditRepository", audit);
        VisualEvidenceBatch batch = new VisualEvidenceBatch(
                Collections.singletonList(new VisualFrameEvidence(
                        25, "frame.jpg", "sha256", new byte[]{1, 2, 3})));

        HighlightFactVerification actual = reviewer.verifyFacts(
                "streamer", "segment", new File("."), "中立密集证据", batch);

        Assert.assertSame(confirmed, actual);
        verify(llmService, times(2)).analyzeImagesWithRaw(
                anyString(), anyList(), eq(HighlightFactVerification.class));
        verify(audit).append(eq(new File(".")),
                eq("fact-verification-vision"), anyString(), anyString(),
                eq("empty-raw"), eq(empty), eq(batch));
        verify(audit).append(eq(new File(".")),
                eq("fact-verification-independent-recall"), anyString(), anyString(),
                eq("confirmed-raw"), eq(confirmed), eq(batch));
        verify(cache).saveFactVerification("streamer",
                "fact-v9-continuity-wording-segment", confirmed);
    }

    @Test
    public void shouldIndependentlyRecallAfterExplainedNegativeFact() throws Exception {
        LlmService llmService = mock(LlmService.class);
        HighlightAnalysisCache cache = mock(HighlightAnalysisCache.class);
        HighlightAnalysisAuditRepository audit = mock(HighlightAnalysisAuditRepository.class);
        HighlightFactVerification explainedNegative = new HighlightFactVerification();
        explainedNegative.setBlockingIssues(
                Collections.singletonList("缺少可见结果证据"));
        explainedNegative.setLimitations(Collections.emptyList());
        HighlightFactVerification recovered = completeFacts();
        when(llmService.analyzeImagesWithRaw(
                anyString(), anyList(), eq(HighlightFactVerification.class)))
                .thenReturn(new LlmCallResult<>("negative-raw", explainedNegative))
                .thenReturn(new LlmCallResult<>("recovered-raw", recovered));
        DanmakuHighlightFinalReviewer reviewer = new DanmakuHighlightFinalReviewer();
        inject(reviewer, "llmService", llmService);
        inject(reviewer, "analysisCache", cache);
        inject(reviewer, "auditRepository", audit);
        VisualEvidenceBatch batch = new VisualEvidenceBatch(
                Collections.singletonList(new VisualFrameEvidence(
                        25, "frame.jpg", "sha256", new byte[]{1, 2, 3})));

        HighlightFactVerification actual = reviewer.verifyFacts(
                "streamer", "segment", new File("."), "中立密集证据", batch);

        Assert.assertSame(recovered, actual);
        verify(llmService, times(2)).analyzeImagesWithRaw(
                anyString(), anyList(), eq(HighlightFactVerification.class));
        verify(cache).saveFactVerification("streamer",
                "fact-v9-continuity-wording-segment", recovered);
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(llmService, times(2)).analyzeImagesWithRaw(
                prompts.capture(), anyList(), eq(HighlightFactVerification.class));
        String recallPrompt = prompts.getAllValues().get(1);
        Assert.assertTrue(recallPrompt.contains("降低漏检"));
        Assert.assertTrue(recallPrompt.contains("状态转换"));
        Assert.assertTrue(recallPrompt.contains("正常镜头切换不等于"));
        Assert.assertFalse(recallPrompt.contains("缺少可见结果证据"));
    }

    @Test
    public void shouldAssessPayoffWithoutPublicationResponsibilities()
            throws Exception {
        LlmService llmService = mock(LlmService.class);
        HighlightAnalysisCache cache = mock(HighlightAnalysisCache.class);
        HighlightAnalysisAuditRepository audit = mock(HighlightAnalysisAuditRepository.class);
        HighlightPayoffAssessment payoff = completePayoff();
        when(llmService.analyzeImagesWithRaw(
                anyString(), anyList(), eq(HighlightPayoffAssessment.class)))
                .thenReturn(new LlmCallResult<>("payoff-raw", payoff));
        DanmakuHighlightFinalReviewer reviewer = new DanmakuHighlightFinalReviewer();
        inject(reviewer, "llmService", llmService);
        inject(reviewer, "analysisCache", cache);
        inject(reviewer, "auditRepository", audit);
        VisualEvidenceBatch batch = new VisualEvidenceBatch(
                Collections.singletonList(new VisualFrameEvidence(
                        25, "frame.jpg", "sha256", new byte[]{1, 2, 3})));

        HighlightPayoffAssessment actual = reviewer.assessPayoff(
                "streamer", "segment", new File("."),
                "精剪内一手证据", completeFacts(), batch);

        Assert.assertSame(payoff, actual);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llmService).analyzeImagesWithRaw(
                prompt.capture(), anyList(), eq(HighlightPayoffAssessment.class));
        Assert.assertTrue(prompt.getValue().contains("只负责判断"));
        Assert.assertTrue(prompt.getValue().contains("SPECTACLE必须"));
        Assert.assertTrue(prompt.getValue().contains("dominantMoment"));
        Assert.assertTrue(prompt.getValue().contains("普通镜头切换"));
        Assert.assertTrue(prompt.getValue().contains("systemControlledPresentation"));
        Assert.assertTrue(prompt.getValue().contains("reversalExpectationEvidence"));
        Assert.assertTrue(prompt.getValue().contains("completedSetupAndPayoff"));
        Assert.assertTrue(prompt.getValue().contains("TENSION必须"));
        Assert.assertTrue(prompt.getValue().contains("即时结果提示可以佐证结果"));
        Assert.assertTrue(prompt.getValue().contains("distinctiveEmotionalEscalation"));
        Assert.assertTrue(prompt.getValue().contains("弹幕只能证明观众预期或反应"));
        Assert.assertTrue(prompt.getValue().contains("不做事实提取"));
        Assert.assertFalse(prompt.getValue().contains("suggestedTitle"));
        verify(cache).savePayoffAssessment(
                eq("streamer"),
                org.mockito.ArgumentMatchers.startsWith(
                        "payoff-v7-emotion-distinctive-protocol-segment-"),
                eq(payoff));
    }

    @Test
    public void shouldBindPublicationCacheToFactsAndVisualFrames() throws Exception {
        LlmService llmService = mock(LlmService.class);
        HighlightAnalysisCache cache = mock(HighlightAnalysisCache.class);
        HighlightAnalysisAuditRepository audit = mock(HighlightAnalysisAuditRepository.class);
        HighlightPublicationAssessment publication = completePublication();
        when(llmService.analyzeImagesWithRaw(
                anyString(), anyList(), eq(HighlightPublicationAssessment.class)))
                .thenReturn(new LlmCallResult<>("publication-raw", publication));
        DanmakuHighlightFinalReviewer reviewer = new DanmakuHighlightFinalReviewer();
        inject(reviewer, "llmService", llmService);
        inject(reviewer, "analysisCache", cache);
        inject(reviewer, "auditRepository", audit);
        VisualEvidenceBatch batch = new VisualEvidenceBatch(
                Collections.singletonList(new VisualFrameEvidence(
                        25, "frame.jpg", "sha256", new byte[]{1, 2, 3})));
        HighlightFactVerification firstFacts = new HighlightFactVerification();
        firstFacts.setAction("第一个动作");
        HighlightFactVerification secondFacts = new HighlightFactVerification();
        secondFacts.setAction("不同动作");

        reviewer.reviewPublication(
                "streamer", "same-segment", new File("."),
                "相同精剪证据", firstFacts, completePayoff(), batch);
        reviewer.reviewPublication(
                "streamer", "same-segment", new File("."),
                "相同精剪证据", secondFacts, completePayoff(), batch);

        ArgumentCaptor<String> cacheKeys = ArgumentCaptor.forClass(String.class);
        verify(cache, times(2)).savePublicationAssessment(
                eq("streamer"), cacheKeys.capture(), eq(publication));
        Assert.assertNotEquals(
                cacheKeys.getAllValues().get(0), cacheKeys.getAllValues().get(1));
        Assert.assertTrue(cacheKeys.getValue().startsWith(
                "publication-v9-protocol-complete-fingerprint-same-segment-"));
    }

    @Test
    public void shouldConfirmPreliminaryPositiveWithIndependentVetoPrompt()
            throws Exception {
        LlmService llmService = mock(LlmService.class);
        HighlightAnalysisCache cache = mock(HighlightAnalysisCache.class);
        HighlightAnalysisAuditRepository audit = mock(HighlightAnalysisAuditRepository.class);
        HighlightPublicationAssessment confirmation = completePublication();
        when(llmService.analyzeImagesWithRaw(
                anyString(), anyList(), eq(HighlightPublicationAssessment.class)))
                .thenReturn(new LlmCallResult<>("confirmation-raw", confirmation));
        DanmakuHighlightFinalReviewer reviewer = new DanmakuHighlightFinalReviewer();
        inject(reviewer, "llmService", llmService);
        inject(reviewer, "analysisCache", cache);
        inject(reviewer, "auditRepository", audit);
        VisualEvidenceBatch batch = new VisualEvidenceBatch(
                Collections.singletonList(new VisualFrameEvidence(
                        25, "frame.jpg", "sha256", new byte[]{1, 2, 3})));

        HighlightPublicationAssessment actual = reviewer.confirmPublication(
                "streamer", "segment", new File("."),
                "精剪内一手证据", completeFacts(), completePayoff(), batch);

        Assert.assertSame(confirmation, actual);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llmService).analyzeImagesWithRaw(
                prompt.capture(), anyList(), eq(HighlightPublicationAssessment.class));
        Assert.assertTrue(prompt.getValue().contains("独立否决审核员"));
        Assert.assertTrue(prompt.getValue().contains("不重新分类"));
        Assert.assertTrue(prompt.getValue().contains("只检查最终精剪"));
        Assert.assertTrue(prompt.getValue().contains("不得仅因题材内常见"));
        Assert.assertFalse(prompt.getValue().contains("初审答案和分数："));
        verify(cache).savePublicationAssessment(
                eq("streamer"),
                org.mockito.ArgumentMatchers.startsWith(
                        "publication-confirm-v3-protocol-complete-veto-segment-"),
                eq(confirmation));
    }

    @Test
    public void shouldRetryParseablePublicationWhenRequiredFieldIsMissing()
            throws Exception {
        LlmService llmService = mock(LlmService.class);
        HighlightAnalysisCache cache = mock(HighlightAnalysisCache.class);
        HighlightAnalysisAuditRepository audit = mock(HighlightAnalysisAuditRepository.class);
        HighlightPublicationAssessment incomplete = completePublication();
        incomplete.getFactAlignment().setUnsupportedClaims(null);
        HighlightPublicationAssessment complete = completePublication();
        when(llmService.analyzeImagesWithRaw(
                anyString(), anyList(), eq(HighlightPublicationAssessment.class)))
                .thenReturn(new LlmCallResult<>("incomplete-raw", incomplete))
                .thenReturn(new LlmCallResult<>("complete-raw", complete));
        DanmakuHighlightFinalReviewer reviewer = new DanmakuHighlightFinalReviewer();
        inject(reviewer, "llmService", llmService);
        inject(reviewer, "analysisCache", cache);
        inject(reviewer, "auditRepository", audit);
        VisualEvidenceBatch batch = new VisualEvidenceBatch(
                Collections.singletonList(new VisualFrameEvidence(
                        25, "frame.jpg", "sha256", new byte[]{1, 2, 3})));

        HighlightPublicationAssessment actual = reviewer.confirmPublication(
                "streamer", "segment", new File("."),
                "精剪内一手证据", completeFacts(), completePayoff(), batch);

        Assert.assertSame(complete, actual);
        verify(llmService, times(2)).analyzeImagesWithRaw(
                anyString(), anyList(), eq(HighlightPublicationAssessment.class));
        verify(audit).append(eq(new File(".")),
                eq("publication-confirmation-error"), anyString(), anyString(),
                eq("incomplete-raw"),
                org.mockito.ArgumentMatchers.any(), eq(batch));
        verify(audit).append(eq(new File(".")),
                eq("publication-confirmation"), anyString(), anyString(),
                eq("complete-raw"), eq(complete), eq(batch));
    }

    @Test
    public void shouldNormalizeOverlongCopyWithoutChangingContentDecision() throws Exception {
        LlmService llmService = mock(LlmService.class);
        HighlightAnalysisCache cache = mock(HighlightAnalysisCache.class);
        HighlightAnalysisAuditRepository audit = mock(HighlightAnalysisAuditRepository.class);
        HighlightPublicationAssessment publication = completePublication();
        publication.setSuggestedTitle("52发子弹上架交易行，税后价远超回收价");
        publication.setCoverText("交易行税后价远超军需处回收价格");
        when(llmService.analyzeImagesWithRaw(
                anyString(), anyList(), eq(HighlightPublicationAssessment.class)))
                .thenReturn(new LlmCallResult<>("publication-raw", publication));
        DanmakuHighlightFinalReviewer reviewer = new DanmakuHighlightFinalReviewer();
        inject(reviewer, "llmService", llmService);
        inject(reviewer, "analysisCache", cache);
        inject(reviewer, "auditRepository", audit);
        VisualEvidenceBatch batch = new VisualEvidenceBatch(
                Collections.singletonList(new VisualFrameEvidence(
                        25, "frame.jpg", "sha256", new byte[]{1, 2, 3})));

        HighlightPublicationAssessment actual = reviewer.reviewPublication(
                "streamer", "segment", new File("."), "中立密集证据",
                new HighlightFactVerification(), completePayoff(), batch);

        Assert.assertEquals("52发子弹上架交易行", actual.getSuggestedTitle());
        Assert.assertEquals("交易行税后价远超军需处回", actual.getCoverText());
    }

    @Test
    public void shouldNormalizeEmptyClaimSentinelBeforeCachingConfirmation()
            throws Exception {
        LlmService llmService = mock(LlmService.class);
        HighlightAnalysisCache cache = mock(HighlightAnalysisCache.class);
        HighlightAnalysisAuditRepository audit = mock(HighlightAnalysisAuditRepository.class);
        HighlightPublicationAssessment confirmation = completePublication();
        confirmation.getFactAlignment().setUnsupportedClaims(
                Collections.singletonList("无"));
        when(llmService.analyzeImagesWithRaw(
                anyString(), anyList(), eq(HighlightPublicationAssessment.class)))
                .thenReturn(new LlmCallResult<>("confirmation-raw", confirmation));
        DanmakuHighlightFinalReviewer reviewer = new DanmakuHighlightFinalReviewer();
        inject(reviewer, "llmService", llmService);
        inject(reviewer, "analysisCache", cache);
        inject(reviewer, "auditRepository", audit);
        VisualEvidenceBatch batch = new VisualEvidenceBatch(
                Collections.singletonList(new VisualFrameEvidence(
                        25, "frame.jpg", "sha256", new byte[]{1, 2, 3})));

        HighlightPublicationAssessment actual = reviewer.confirmPublication(
                "streamer", "segment", new File("."),
                "精剪内一手证据", completeFacts(), completePayoff(), batch);

        Assert.assertTrue(actual.getFactAlignment().getUnsupportedClaims().isEmpty());
        verify(cache).savePublicationAssessment(
                eq("streamer"), anyString(), eq(confirmation));
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = DanmakuHighlightFinalReviewer.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static HighlightFactVerification completeFacts() {
        HighlightFactVerification facts = new HighlightFactVerification();
        facts.setSetup("可见初始状态");
        facts.setSetupEvidence(Collections.singletonList(reference("VISION-001")));
        facts.setAction("可见状态转换");
        facts.setActionEvidence(Collections.singletonList(reference("VISION-002")));
        facts.setOutcome("界面明确显示结果");
        facts.setOutcomeEvidence(Collections.singletonList(reference("OCR-001")));
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
        return facts;
    }

    private static HighlightPayoffAssessment completePayoff() {
        HighlightPayoffAssessment payoff = new HighlightPayoffAssessment();
        payoff.setCategory("REVERSAL");
        payoff.setMeaningful(true);
        payoff.setStrength(80);
        payoff.setDominantMoment("状态由稳定转为异常");
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
        return payoff;
    }

    private static HighlightPublicationAssessment completePublication() {
        HighlightFactAlignment alignment = new HighlightFactAlignment();
        alignment.setSetupSupported(true);
        alignment.setActionSupported(true);
        alignment.setOutcomeSupported(true);
        alignment.setSameEventSupported(true);
        alignment.setUnsupportedClaims(Collections.emptyList());
        alignment.setRationale("事实逐项对齐");
        HighlightPublicationQuality quality = new HighlightPublicationQuality();
        quality.setAudienceValue(80);
        quality.setContentDensity(80);
        quality.setLongestNoDevelopmentSeconds(3);
        quality.setRationale("持续推进");
        HighlightPublicationAssessment publication = new HighlightPublicationAssessment();
        publication.setPublishable(true);
        publication.setScore(80);
        publication.setReason("具备发布价值");
        publication.setOneSentenceStory("完整事件故事");
        publication.setSuggestedTitle("完整事件标题示例");
        publication.setCoverText("关键结果画面");
        publication.setFactAlignment(alignment);
        publication.setQuality(quality);
        return publication;
    }

    private static HighlightEvidenceReference reference(String evidenceId) {
        HighlightEvidenceReference reference = new HighlightEvidenceReference();
        reference.setEvidenceId(evidenceId);
        return reference;
    }
}
