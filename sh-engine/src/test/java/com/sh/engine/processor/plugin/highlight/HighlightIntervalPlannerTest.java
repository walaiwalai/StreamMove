package com.sh.engine.processor.plugin.highlight;

import com.sh.engine.model.highlight.core.HighlightCutPolicy;
import com.sh.engine.model.highlight.core.HighlightEvent;
import com.sh.engine.model.highlight.core.ScoredVideoInterval;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HighlightIntervalPlannerTest {
    private final HighlightIntervalPlanner planner = new HighlightIntervalPlanner();
    private final File video = new File("P01.mp4");

    @Test
    public void shouldApplyNegativeEventToClusterScore() {
        List<HighlightEvent> events = Arrays.asList(
                event(10, "SELF_KILL", 2f, 1, 0),
                event(14, "SELF_DEATH", -1f, 0, 1));

        List<ScoredVideoInterval> selected = select(
                events, valorantPolicy(), durations());

        Assert.assertTrue(selected.isEmpty());
    }

    @Test
    public void shouldMergeExpandedOverlappingIntervals() {
        List<HighlightEvent> events = Arrays.asList(
                event(10, "SELF_KILL", 2f, 1, 0),
                event(20, "SELF_KILL", 2f, 1, 0));

        List<ScoredVideoInterval> selected = select(
                events, valorantPolicy(), durations());

        Assert.assertEquals(1, selected.size());
        Assert.assertEquals(3.0, selected.get(0).getSecondFromVideoStart(), 0.001);
        Assert.assertEquals(26.0, selected.get(0).getSecondToVideoEnd(), 0.001);
        Assert.assertEquals(4.0f, selected.get(0).getScore(), 0.001f);
        Assert.assertEquals(2, selected.get(0).getPositiveCount());
    }

    @Test
    public void shouldApplyThresholdBeforeCoalescingExpandedIntervals() {
        List<HighlightEvent> events = Arrays.asList(
                event(10, "SELF_KILL", 1f, 1, 0),
                event(20, "SELF_KILL", 1f, 1, 0));

        List<ScoredVideoInterval> selected = select(
                events, valorantPolicy(), durations());

        Assert.assertTrue(selected.isEmpty());
    }

    @Test
    public void shouldClampIntervalToVideoBounds() {
        List<ScoredVideoInterval> selected = select(
                Collections.singletonList(event(98, "SELF_KILL", 2f, 1, 0)),
                valorantPolicy(), durations());

        Assert.assertEquals(1, selected.size());
        Assert.assertEquals(91.0, selected.get(0).getSecondFromVideoStart(), 0.001);
        Assert.assertEquals(100.0, selected.get(0).getSecondToVideoEnd(), 0.001);
    }

    @Test
    public void shouldIgnoreNullTimelineEntries() {
        List<ScoredVideoInterval> selected = planner.select(
                Collections.singletonList(null), valorantPolicy());

        Assert.assertTrue(selected.isEmpty());
    }

    @Test
    public void shouldKeepSourceVideosInNumericOrder() {
        File secondVideo = new File("2-P02-1080P 高清-AVC.mp4");
        File tenthVideo = new File("10-P10-1080P.mp4");
        List<HighlightEvent> events = Arrays.asList(
                event(tenthVideo, 10, "SELF_KILL", 3f, 1, 0),
                event(secondVideo, 20, "SELF_KILL", 2f, 1, 0));
        Map<File, Double> videoDurations = new HashMap<>();
        videoDurations.put(secondVideo, 100.0);
        videoDurations.put(tenthVideo, 100.0);

        List<ScoredVideoInterval> selected = select(
                events, valorantPolicy(), videoDurations);

        Assert.assertEquals(2, selected.size());
        Assert.assertEquals(secondVideo, selected.get(0).getFromVideo());
        Assert.assertEquals(tenthVideo, selected.get(1).getFromVideo());
    }

    @Test
    public void shouldAcceptVideoNameWithoutSegmentIndex() {
        File namedVideo = new File("valorant-highlight-source.mp4");
        HighlightEvent event = event(namedVideo, 20, "SELF_KILL", 2f, 1, 0);

        List<ScoredVideoInterval> selected = select(
                Collections.singletonList(event),
                valorantPolicy(),
                Collections.singletonMap(namedVideo, 100.0));

        Assert.assertEquals(1, selected.size());
        Assert.assertEquals(namedVideo, selected.get(0).getFromVideo());
    }

    private HighlightEvent event(double second,
                                 String eventType,
                                 float score,
                                 int positive,
                                 int negative) {
        return event(video, second, eventType, score, positive, negative);
    }

    private HighlightEvent event(File sourceVideo,
                                 double second,
                                 String eventType,
                                 float score,
                                 int positive,
                                 int negative) {
        return HighlightEvent.builder()
                .sourceVideo(sourceVideo)
                .secondFromVideoStart(second)
                .eventType(eventType)
                .score(score)
                .positiveCount(positive)
                .negativeCount(negative)
                .confidence(1f)
                .build();
    }

    private HighlightCutPolicy valorantPolicy() {
        return HighlightCutPolicy.builder()
                .clusterGapSeconds(8)
                .preRollSeconds(7)
                .postRollSeconds(6)
                .minScore(2f)
                .minPositiveCount(1)
                .topN(10)
                .build();
    }

    private Map<File, Double> durations() {
        Map<File, Double> durations = new HashMap<>();
        durations.put(video, 100.0);
        return durations;
    }

    @SuppressWarnings("unchecked")
    private List<ScoredVideoInterval> select(List<HighlightEvent> timeline,
                                             HighlightCutPolicy policy,
                                             Map<File, Double> videoDurations) {
        try {
            Method normalizeTimeline = HighlightIntervalPlanner.class
                    .getDeclaredMethod("normalizeTimeline", List.class);
            normalizeTimeline.setAccessible(true);
            List<HighlightEvent> normalizedTimeline =
                    (List<HighlightEvent>) normalizeTimeline.invoke(planner, timeline);

            Method selectNormalized = HighlightIntervalPlanner.class.getDeclaredMethod(
                    "selectNormalized", List.class, HighlightCutPolicy.class, Map.class);
            selectNormalized.setAccessible(true);
            return (List<ScoredVideoInterval>) selectNormalized.invoke(
                    planner, normalizedTimeline, policy, videoDurations);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
