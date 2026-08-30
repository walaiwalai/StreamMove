package com.sh.engine.processor.plugin.highlight.valorant;

import com.sh.engine.model.highlight.core.OcrTextDetection;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ValorantKillFeedClassifierTest {
    private final ValorantKillFeedClassifier classifier = new ValorantKillFeedClassifier();

    @Test
    public void shouldClassifyMeOnLeftAsSelfKill() {
        ValorantKillFeedClassifier.Classification result = first(classifier.classifyRows(
                Arrays.asList(
                        detection("Me", 0.95f, 10, 30),
                        detection("Sova", 0.90f, 130, 180)),
                200,
                100,
                0.55f));

        Assert.assertNotNull(result);
        Assert.assertEquals(ValorantKillFeedClassifier.Side.LEFT, result.getSide());
    }

    @Test
    public void shouldClassifyChineseMarkerOnRightAsSelfDeath() {
        ValorantKillFeedClassifier.Classification result = first(classifier.classifyRows(
                Arrays.asList(
                        detection("Sova", 0.90f, 10, 60),
                        detection("我", 0.95f, 150, 175)),
                200,
                100,
                0.55f));

        Assert.assertNotNull(result);
        Assert.assertEquals(ValorantKillFeedClassifier.Side.RIGHT, result.getSide());
    }

    @Test
    public void shouldUseMarkerPositionInsideMergedChineseText() {
        ValorantKillFeedClassifier.Classification left = first(classifier.classifyRows(
                Collections.singletonList(detection("我幻棂", 0.95f, 0, 200)),
                200,
                100,
                0.55f));
        ValorantKillFeedClassifier.Classification right = first(classifier.classifyRows(
                Collections.singletonList(detection("幻棂我", 0.95f, 0, 200)),
                200,
                100,
                0.55f));

        Assert.assertNotNull(left);
        Assert.assertNotNull(right);
        Assert.assertEquals(ValorantKillFeedClassifier.Side.LEFT, left.getSide());
        Assert.assertEquals(ValorantKillFeedClassifier.Side.RIGHT, right.getSide());
    }

    @Test
    public void shouldNotTreatMeInsideAnotherWordAsMarker() {
        List<ValorantKillFeedClassifier.Classification> results = classifier.classifyRows(
                Collections.singletonList(detection("Mercy", 0.95f, 10, 60)),
                200,
                100,
                0.55f);

        Assert.assertTrue(results.isEmpty());
    }

    @Test
    public void shouldIgnoreLowConfidenceMarker() {
        List<ValorantKillFeedClassifier.Classification> results = classifier.classifyRows(
                Collections.singletonList(detection("Me", 0.40f, 10, 30)),
                200,
                100,
                0.55f);

        Assert.assertTrue(results.isEmpty());
    }

    @Test
    public void shouldClassifyMultipleKillFeedRowsFromSingleOcrResponse() {
        List<ValorantKillFeedClassifier.Classification> results = classifier.classifyRows(
                Arrays.asList(
                        detection("我", 0.99f, 20, 35, 5, 25),
                        detection("捷风", 0.95f, 120, 165, 7, 25),
                        detection("夜露", 0.96f, 10, 55, 55, 75),
                        detection("我", 0.98f, 165, 180, 55, 75),
                        detection("网络延迟", 0.99f, 20, 90, 130, 150)),
                200,
                200,
                0.55f);

        Assert.assertEquals(2, results.size());
        Assert.assertEquals(ValorantKillFeedClassifier.Side.LEFT, results.get(0).getSide());
        Assert.assertEquals(ValorantKillFeedClassifier.Side.RIGHT, results.get(1).getSide());
        Assert.assertEquals(15.5, results.get(0).getRowCenterY(), 0.01);
        Assert.assertEquals(65.0, results.get(1).getRowCenterY(), 0.01);
    }

    private OcrTextDetection detection(String text, float score, int left, int right) {
        return detection(text, score, left, right, 0, 20);
    }

    private OcrTextDetection detection(String text,
                                       float score,
                                       int left,
                                       int right,
                                       int top,
                                       int bottom) {
        return new OcrTextDetection(
                text,
                score,
                Arrays.asList(left, top, right, top, right, bottom, left, bottom));
    }

    private ValorantKillFeedClassifier.Classification first(
            List<ValorantKillFeedClassifier.Classification> classifications) {
        return classifications.isEmpty() ? null : classifications.get(0);
    }
}
