package com.sh.engine.model.danmaku;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class VisualEvidenceBatchTest {

    @Test
    public void shouldDiscardOnlyAdditionalUnmappedObservation() {
        VisualEvidenceBatch batch = batch(10, 20);
        VisualTimelineResult timeline = timeline(observation(1), observation(2), observation(3));

        Assert.assertEquals(Collections.singletonList(3),
                batch.discardUnmappedObservations(timeline));
        batch.attachTrustedTimestamps(timeline);
        batch.validate(timeline);
        Assert.assertEquals(2, timeline.getObservations().size());
    }

    @Test
    public void shouldNotRepairTimelineWhenARealFrameIsMissing() {
        VisualEvidenceBatch batch = batch(10, 20);
        VisualTimelineResult timeline = timeline(observation(1), observation(3));

        Assert.assertTrue(batch.discardUnmappedObservations(timeline).isEmpty());
        try {
            batch.attachTrustedTimestamps(timeline);
            Assert.fail("Missing real frame must remain invalid");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("invalid frame index"));
        }
    }

    @Test
    public void shouldKeepFrameOrderAndValidateTimelineMapping() {
        VisualEvidenceBatch batch = new VisualEvidenceBatch(frames());
        VisualTimelineResult timeline = timeline(false);

        batch.validate(timeline);

        Assert.assertEquals(8, batch.toLlmInputs().size());
        Assert.assertEquals(8, batch.toAuditMetadata().size());
        Assert.assertTrue(batch.buildFrameIndexText().contains("图片8=00:01:10"));
        Assert.assertEquals(Collections.singleton(70),
                batch.coverCandidateTimestamps(timeline));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectTimestampThatDoesNotMatchFrameIndex() {
        new VisualEvidenceBatch(frames()).validate(timeline(true));
    }

    @Test
    public void shouldReplaceModelTimestampWithTrustedFrameTimestamp() {
        VisualEvidenceBatch batch = new VisualEvidenceBatch(frames());
        VisualTimelineResult timeline = timeline(true);

        batch.attachTrustedTimestamps(timeline);
        batch.validate(timeline);

        Assert.assertEquals("00:00:00",
                timeline.getObservations().get(0).getTimestamp());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectTimelineThatOmitsAFrame() {
        VisualTimelineResult timeline = timeline(false);
        timeline.setObservations(timeline.getObservations().subList(0, 7));

        new VisualEvidenceBatch(frames()).validate(timeline);
    }

    @Test
    public void shouldBoundDenseOcrFramesAndKeepCoverCandidate() {
        int[] timestamps = new int[20];
        VisualObservation[] observations = new VisualObservation[20];
        for (int index = 0; index < 20; index++) {
            timestamps[index] = index;
            observations[index] = observation(index + 1);
            observations[index].setCoverCandidate(index == 10);
        }
        VisualEvidenceBatch batch = batch(timestamps);

        List<VisualFrameEvidence> selected = batch.selectFramesForOcr(
                timeline(observations), 8);

        Assert.assertEquals(8, selected.size());
        Assert.assertTrue(selected.stream()
                .anyMatch(frame -> frame.getTimestampSeconds() == 0));
        Assert.assertTrue(selected.stream()
                .anyMatch(frame -> frame.getTimestampSeconds() == 10));
        Assert.assertTrue(selected.stream()
                .anyMatch(frame -> frame.getTimestampSeconds() == 19));
    }

    @Test
    public void shouldKeepOnlyFramesInsideExactClip() {
        VisualEvidenceBatch exact = batch(10, 20, 30, 40)
                .inside(new HighlightClipRange(20, 40));

        Assert.assertEquals(2, exact.getFrames().size());
        Assert.assertEquals(20, exact.getFrames().get(0).getTimestampSeconds());
        Assert.assertEquals(30, exact.getFrames().get(1).getTimestampSeconds());
    }

    private List<VisualFrameEvidence> frames() {
        List<VisualFrameEvidence> frames = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            int timestamp = index * 10;
            frames.add(new VisualFrameEvidence(
                    timestamp, "C:/evidence/frame-" + index + ".jpg",
                    "hash-" + index, new byte[]{1, 2, (byte) index}));
        }
        return frames;
    }

    private VisualEvidenceBatch batch(int... timestamps) {
        List<VisualFrameEvidence> frames = new ArrayList<>();
        for (int index = 0; index < timestamps.length; index++) {
            frames.add(new VisualFrameEvidence(
                    timestamps[index], "C:/evidence/frame-" + index + ".jpg",
                    "hash-" + index, new byte[]{1, 2, (byte) index}));
        }
        return new VisualEvidenceBatch(frames);
    }

    private VisualObservation observation(int frameIndex) {
        VisualObservation observation = new VisualObservation();
        observation.setFrameIndex(frameIndex);
        observation.setObservableFacts("画面中可见对象" + frameIndex);
        observation.setCertainty("high");
        observation.setCoverCandidate(false);
        return observation;
    }

    private VisualTimelineResult timeline(VisualObservation... observations) {
        VisualTimelineResult result = new VisualTimelineResult();
        result.setObservations(new ArrayList<>(Arrays.asList(observations)));
        result.setSummary("画面发生变化");
        result.setUncertainties(Collections.emptyList());
        return result;
    }

    private VisualTimelineResult timeline(boolean wrongTimestamp) {
        List<VisualObservation> observations = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            VisualObservation observation = new VisualObservation();
            observation.setFrameIndex(index + 1);
            int timestamp = index * 10;
            observation.setTimestamp(wrongTimestamp && index == 0
                    ? "00:00:01" : String.format("00:%02d:%02d",
                    timestamp / 60, timestamp % 60));
            observation.setObservableFacts("画面中可见对象" + index);
            observation.setCertainty("high");
            observation.setCoverCandidate(index == 7);
            observations.add(observation);
        }
        VisualTimelineResult result = new VisualTimelineResult();
        result.setObservations(observations);
        result.setSummary("画面发生变化");
        result.setUncertainties(Collections.emptyList());
        return result;
    }
}
