package com.sh.engine.processor.plugin.highlight.danmaku;

import com.sh.engine.model.danmaku.VisualEvidenceBatch;
import com.sh.engine.model.danmaku.VisualFrameEvidence;
import com.sh.engine.model.danmaku.VisualObservation;
import com.sh.engine.model.danmaku.VisualTimelineResult;
import com.sh.engine.model.llm.LlmCallResult;
import com.sh.engine.model.llm.LlmImageInput;
import com.sh.engine.service.LlmService;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DanmakuVisualTimelineAnalyzerTest {

    @Test
    public void shouldRepairOnlyBlankObservationWithOriginalImage() throws Exception {
        LlmService llmService = mock(LlmService.class);
        HighlightAnalysisCache cache = mock(HighlightAnalysisCache.class);
        HighlightAnalysisAuditRepository audit = mock(HighlightAnalysisAuditRepository.class);
        VisualTimelineResult initial = timeline(
                observation(1, "事实1"),
                observation(2, null),
                observation(3, "事实3"),
                observation(4, "越界观察"));
        VisualTimelineResult repaired = timeline(observation(1, "补采后的事实2"));
        when(llmService.analyzeImagesWithRaw(
                anyString(), anyList(), eq(VisualTimelineResult.class)))
                .thenReturn(new LlmCallResult<>("initial-raw", initial))
                .thenReturn(new LlmCallResult<>("repair-raw", repaired));
        DanmakuVisualTimelineAnalyzer analyzer = new DanmakuVisualTimelineAnalyzer();
        inject(analyzer, "llmService", llmService);
        inject(analyzer, "analysisCache", cache);
        inject(analyzer, "auditRepository", audit);
        VisualEvidenceBatch batch = new VisualEvidenceBatch(Arrays.asList(
                frame(10), frame(20), frame(30)));

        VisualTimelineResult result = analyzer.analyze(
                "streamer", "cache-key", new File("."), batch, "vision-dense");

        Assert.assertEquals(3, result.getObservations().size());
        Assert.assertEquals("补采后的事实2",
                result.getObservations().get(1).getObservableFacts());
        Assert.assertEquals("00:00:20", result.getObservations().get(1).getTimestamp());
        verify(llmService, times(2)).analyzeImagesWithRaw(
                anyString(), anyList(), eq(VisualTimelineResult.class));
        verify(cache).saveVisualTimeline("streamer", "cache-key", result);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldRetryOnlyInvalidSmallBatchAndMergeTrustedIndices() throws Exception {
        LlmService llmService = mock(LlmService.class);
        HighlightAnalysisCache cache = mock(HighlightAnalysisCache.class);
        HighlightAnalysisAuditRepository audit = mock(HighlightAnalysisAuditRepository.class);
        when(llmService.analyzeImagesWithRaw(
                anyString(), anyList(), eq(VisualTimelineResult.class)))
                .thenReturn(new LlmCallResult<>("incomplete", timelineRange(1, 11)))
                .thenReturn(new LlmCallResult<>("retried", timelineRange(1, 12)))
                .thenReturn(new LlmCallResult<>("last", timelineRange(1, 1)));
        DanmakuVisualTimelineAnalyzer analyzer = analyzer(llmService, cache, audit);
        List<VisualFrameEvidence> frames = new ArrayList<>();
        for (int index = 1; index <= 13; index++) {
            frames.add(frame(index));
        }

        VisualTimelineResult result = analyzer.analyze(
                "streamer", "batched-key", new File("."),
                new VisualEvidenceBatch(frames), "vision-dense");

        Assert.assertEquals(13, result.getObservations().size());
        Assert.assertEquals(Integer.valueOf(13),
                result.getObservations().get(12).getFrameIndex());
        Assert.assertEquals("00:00:13",
                result.getObservations().get(12).getTimestamp());
        ArgumentCaptor<List<LlmImageInput>> images = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(3)).analyzeImagesWithRaw(
                anyString(), images.capture(), eq(VisualTimelineResult.class));
        Assert.assertEquals(Arrays.asList(12, 12, 1), images.getAllValues().stream()
                .map(List::size).collect(java.util.stream.Collectors.toList()));
        verify(cache).saveVisualTimeline("streamer", "batched-key", result);
    }

    @Test
    public void shouldSplitFortyEightFramesIntoFourRequests() throws Exception {
        LlmService llmService = mock(LlmService.class);
        HighlightAnalysisCache cache = mock(HighlightAnalysisCache.class);
        HighlightAnalysisAuditRepository audit = mock(HighlightAnalysisAuditRepository.class);
        when(llmService.analyzeImagesWithRaw(
                anyString(), anyList(), eq(VisualTimelineResult.class)))
                .thenReturn(new LlmCallResult<>("batch-1", timelineRange(1, 12)))
                .thenReturn(new LlmCallResult<>("batch-2", timelineRange(1, 12)))
                .thenReturn(new LlmCallResult<>("batch-3", timelineRange(1, 12)))
                .thenReturn(new LlmCallResult<>("batch-4", timelineRange(1, 12)));
        DanmakuVisualTimelineAnalyzer analyzer = analyzer(llmService, cache, audit);
        List<VisualFrameEvidence> frames = new ArrayList<>();
        for (int index = 1; index <= 48; index++) {
            frames.add(frame(index));
        }

        VisualTimelineResult result = analyzer.analyze(
                "streamer", "forty-eight-key", new File("."),
                new VisualEvidenceBatch(frames), "vision-dense");

        Assert.assertEquals(48, result.getObservations().size());
        Assert.assertEquals(Integer.valueOf(48),
                result.getObservations().get(47).getFrameIndex());
        Assert.assertEquals("00:00:48",
                result.getObservations().get(47).getTimestamp());
        verify(llmService, times(4)).analyzeImagesWithRaw(
                anyString(), anyList(), eq(VisualTimelineResult.class));
    }

    private VisualFrameEvidence frame(int timestamp) {
        return new VisualFrameEvidence(timestamp, "frame-" + timestamp + ".jpg",
                "hash-" + timestamp, new byte[]{1, 2, (byte) timestamp});
    }

    private VisualObservation observation(int index, String facts) {
        VisualObservation observation = new VisualObservation();
        observation.setFrameIndex(index);
        observation.setObservableFacts(facts);
        observation.setVisibleChange("变化" + index);
        observation.setCertainty("high");
        observation.setCoverCandidate(false);
        return observation;
    }

    private VisualTimelineResult timeline(VisualObservation... observations) {
        VisualTimelineResult timeline = new VisualTimelineResult();
        timeline.setObservations(Arrays.asList(observations));
        timeline.setSummary("摘要");
        timeline.setUncertainties(Collections.emptyList());
        return timeline;
    }

    private VisualTimelineResult timelineRange(int start, int end) {
        List<VisualObservation> observations = new ArrayList<>();
        for (int index = start; index <= end; index++) {
            observations.add(observation(index, "事实" + index));
        }
        return timeline(observations.toArray(new VisualObservation[0]));
    }

    private DanmakuVisualTimelineAnalyzer analyzer(
            LlmService llmService,
            HighlightAnalysisCache cache,
            HighlightAnalysisAuditRepository audit) throws Exception {
        DanmakuVisualTimelineAnalyzer analyzer = new DanmakuVisualTimelineAnalyzer();
        inject(analyzer, "llmService", llmService);
        inject(analyzer, "analysisCache", cache);
        inject(analyzer, "auditRepository", audit);
        return analyzer;
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = DanmakuVisualTimelineAnalyzer.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
