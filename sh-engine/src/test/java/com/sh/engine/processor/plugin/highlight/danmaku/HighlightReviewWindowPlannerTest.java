package com.sh.engine.processor.plugin.highlight.danmaku;

import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.model.danmaku.HighlightAnalysisResult;
import com.sh.engine.model.danmaku.HighlightClipRange;
import com.sh.engine.model.danmaku.HighlightEvidenceCatalog;
import com.sh.engine.model.danmaku.HighlightEvidenceReference;
import com.sh.engine.model.danmaku.HighlightReviewContext;
import com.sh.engine.model.danmaku.HighlightReviewPlan;
import com.sh.engine.processor.recorder.danmu.SimpleDanmaku;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class HighlightReviewWindowPlannerTest {

    @Test
    public void shouldKeepCompliantProposedWindow() {
        HighlightAnalysisResult draft = new HighlightAnalysisResult();
        draft.setHighlight(true);
        draft.setExactClipStart("00:00:20");
        draft.setExactClipEnd("00:01:10");
        draft.setActionEvidence(Collections.singletonList(reference("ASR-001")));
        draft.setOutcomeEvidence(Collections.singletonList(reference("ASR-002")));
        HighlightEvidenceCatalog catalog = catalogAt(30, 60);

        HighlightReviewPlan plan = new HighlightReviewWindowPlanner().plan(
                draft, context(0, 140, 60, 140, Collections.emptyList()), catalog)
                .get(0);
        HighlightClipRange window = plan.getRange();

        Assert.assertEquals(20, window.getStartSecond());
        Assert.assertEquals(70, window.getEndSecond());
        Assert.assertEquals("VERIFIED_DRAFT_RANGE", plan.getStrategy());
    }

    @Test
    public void shouldFallBackToSignalCenterWhenStructuredEvidenceCannotFit() {
        HighlightAnalysisResult draft = new HighlightAnalysisResult();
        draft.setHighlight(true);
        draft.setExactClipStart("00:00:00");
        draft.setExactClipEnd("00:02:20");
        draft.setActionEvidence(Collections.singletonList(reference("ASR-001")));
        draft.setOutcomeEvidence(Collections.singletonList(reference("ASR-002")));
        draft.setTitleEvidence(Arrays.asList(
                reference("ASR-002"), reference("ASR-002")));
        draft.setCoverTimestamp("00:01:43");
        HighlightEvidenceCatalog catalog = new HighlightEvidenceCatalog(Arrays.asList(
                AsrSegment.builder().startTime(10).endTime(15).text("开始").build(),
                AsrSegment.builder().startTime(100).endTime(105).text("结果").build()),
                Collections.emptyList(), null);

        List<HighlightReviewPlan> plans = new HighlightReviewWindowPlanner().plan(
                draft, context(0, 140, 60, 140, Collections.emptyList()), catalog);

        Assert.assertEquals(2, plans.size());
        Assert.assertEquals("PRE_SIGNAL_CONTEXT", plans.get(0).getStrategy());
        Assert.assertEquals(0, plans.get(0).getRange().getStartSecond());
        Assert.assertEquals(75, plans.get(0).getRange().getEndSecond());
        Assert.assertEquals("DANMAKU_BURST", plans.get(1).getStrategy());
        Assert.assertEquals(45, plans.get(1).getRange().getStartSecond());
        Assert.assertEquals(120, plans.get(1).getRange().getEndSecond());
    }

    @Test
    public void shouldUseDanmakuBurstInsteadOfGenericEvidenceForAmbiguousDraft() {
        HighlightAnalysisResult draft = new HighlightAnalysisResult();
        draft.setHighlight(false);
        draft.setScore(48);
        draft.setExactClipStart("00:00:00");
        draft.setExactClipEnd("00:02:20");
        draft.setEvidence(Arrays.asList("ASR-001", "ASR-002", "UNKNOWN-001"));
        draft.setCoverTimestamp("00:02:10");
        HighlightEvidenceCatalog catalog = catalogAt(30, 105);
        List<SimpleDanmaku> danmakus = Arrays.asList(
                danmaku(91, "反应一"), danmaku(92, "反应二"),
                danmaku(93, "反应三"), danmaku(93, "反应四"));

        List<HighlightReviewPlan> plans = new HighlightReviewWindowPlanner().plan(
                draft, context(0, 140, 60, 140, danmakus), catalog);

        Assert.assertEquals(2, plans.size());
        Assert.assertEquals("EARLIEST_SPARSE_EVIDENCE", plans.get(0).getStrategy());
        Assert.assertEquals(0, plans.get(0).getRange().getStartSecond());
        Assert.assertEquals(75, plans.get(0).getRange().getEndSecond());
        Assert.assertEquals("DANMAKU_BURST", plans.get(1).getStrategy());
        Assert.assertEquals(37, plans.get(1).getRange().getStartSecond());
        Assert.assertEquals(112, plans.get(1).getRange().getEndSecond());
        Assert.assertEquals(92, plans.get(1).getAnchorSecond());
    }

    @Test
    public void shouldIgnoreUnverifiedProposedRangeEvenWhenDurationIsValid() {
        HighlightAnalysisResult draft = new HighlightAnalysisResult();
        draft.setHighlight(false);
        draft.setExactClipStart("00:01:05");
        draft.setExactClipEnd("00:02:20");
        draft.setCoverTimestamp("00:02:10");
        List<SimpleDanmaku> danmakus = Arrays.asList(
                danmaku(70, "不同反应一"), danmaku(71, "不同反应二"));

        List<HighlightReviewPlan> plans = new HighlightReviewWindowPlanner().plan(
                draft, context(0, 140, 60, 140, danmakus), emptyCatalog());
        HighlightReviewPlan plan = plans.get(0);
        HighlightClipRange window = plan.getRange();

        Assert.assertEquals(1, plans.size());
        Assert.assertEquals("PRE_SIGNAL_CONTEXT", plan.getStrategy());
        Assert.assertEquals(0, window.getStartSecond());
        Assert.assertEquals(75, window.getEndSecond());
    }

    private HighlightEvidenceReference reference(String evidenceId) {
        HighlightEvidenceReference reference = new HighlightEvidenceReference();
        reference.setEvidenceId(evidenceId);
        return reference;
    }

    private HighlightEvidenceCatalog emptyCatalog() {
        return new HighlightEvidenceCatalog(
                Collections.emptyList(), Collections.emptyList(), null);
    }

    private HighlightEvidenceCatalog catalogAt(int first, int second) {
        return new HighlightEvidenceCatalog(Arrays.asList(
                AsrSegment.builder().startTime(first).endTime(first).text("行动").build(),
                AsrSegment.builder().startTime(second).endTime(second).text("结果").build()),
                Collections.emptyList(), null);
    }

    private HighlightReviewContext context(
            int start, int end, int signalStart, int signalEnd,
            List<SimpleDanmaku> danmakus) {
        return new HighlightReviewContext(
                start, end, signalStart, signalEnd, 0, danmakus);
    }

    private SimpleDanmaku danmaku(float time, String text) {
        return new SimpleDanmaku(time, 0L, text, "ffffff");
    }
}
