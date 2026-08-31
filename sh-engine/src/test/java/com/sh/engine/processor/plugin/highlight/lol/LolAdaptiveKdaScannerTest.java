package com.sh.engine.processor.plugin.highlight.lol;

import com.sh.engine.model.highlight.lol.LoLPicData;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntFunction;

public class LolAdaptiveKdaScannerTest {
    private final LolAdaptiveKdaScanner scanner = new LolAdaptiveKdaScanner();

    @Test
    public void shouldSkipEightMinuteRangesWhenKdaIsUnchanged() throws Exception {
        Set<Integer> sampledSeconds = new HashSet<>();
        IntFunction<LoLPicData> sampler = second -> {
            sampledSeconds.add(second);
            return new LoLPicData(1, 2, 3);
        };

        TreeMap<Integer, LoLPicData> timeline = buildTimeline(960, sampler);

        Assert.assertEquals(241, timeline.size());
        Assert.assertEquals(3, sampledSeconds.size());
        Assert.assertTrue(sampledSeconds.containsAll(Arrays.asList(0, 480, 960)));
    }

    @Test
    public void shouldFindEveryKdaChangeInsideOneCoarseRange() throws Exception {
        Set<Integer> sampledSeconds = new HashSet<>();
        IntFunction<LoLPicData> sampler = second -> {
            sampledSeconds.add(second);
            if (second < 100) {
                return new LoLPicData(0, 0, 0);
            }
            if (second < 300) {
                return new LoLPicData(1, 0, 0);
            }
            return new LoLPicData(1, 0, 1);
        };

        TreeMap<Integer, LoLPicData> timeline = buildTimeline(480, sampler);

        assertKda(timeline.get(96), 0, 0, 0);
        assertKda(timeline.get(100), 1, 0, 0);
        assertKda(timeline.get(296), 1, 0, 0);
        assertKda(timeline.get(300), 1, 0, 1);
        Assert.assertTrue(sampledSeconds.size() < 30);
    }

    @Test
    public void shouldLocateKdaResetInsteadOfApplyingMonotonicAssumption() throws Exception {
        IntFunction<LoLPicData> sampler = second -> second < 200
                ? new LoLPicData(5, 3, 7)
                : new LoLPicData(0, 0, 0);

        TreeMap<Integer, LoLPicData> timeline = buildTimeline(480, sampler);

        assertKda(timeline.get(196), 5, 3, 7);
        assertKda(timeline.get(200), 0, 0, 0);
    }

    @Test
    public void shouldSkipRangeWhenBothEndsAndMiddleAreInvalid() throws Exception {
        Set<Integer> sampledSeconds = new HashSet<>();
        IntFunction<LoLPicData> sampler = second -> {
            sampledSeconds.add(second);
            return LoLPicData.genInvalid();
        };

        TreeMap<Integer, LoLPicData> timeline = buildTimeline(480, sampler);

        Assert.assertEquals(121, timeline.size());
        Assert.assertEquals(3, sampledSeconds.size());
        Assert.assertTrue(timeline.values().stream().allMatch(LoLPicData::beBlank));
    }

    @Test
    public void shouldCreateCropExpressionFromDetectedBox() throws Exception {
        List<List<Integer>> points = Arrays.asList(
                Arrays.asList(100, 20),
                Arrays.asList(180, 20),
                Arrays.asList(180, 50),
                Arrays.asList(100, 50));

        Assert.assertEquals("crop=100:40:in_w/2+100:15", createCropExpression(points));
        Assert.assertNull(createCropExpression(Arrays.asList(Arrays.asList(100, 20))));
    }

    @Test
    public void shouldScaleFrameBudgetWithVideoTimeline() throws Exception {
        Assert.assertEquals(200, calculateFrameBudget(480));
        Assert.assertEquals(901, calculateFrameBudget(3600));
    }

    @SuppressWarnings("unchecked")
    private TreeMap<Integer, LoLPicData> buildTimeline(
            int lastSecond,
            IntFunction<LoLPicData> sampler) throws Exception {
        Method method = LolAdaptiveKdaScanner.class.getDeclaredMethod(
                "buildTimeline", int.class, IntFunction.class);
        method.setAccessible(true);
        return (TreeMap<Integer, LoLPicData>) method.invoke(scanner, lastSecond, sampler);
    }

    private String createCropExpression(List<List<Integer>> boxes) throws Exception {
        Method method = LolAdaptiveKdaScanner.class.getDeclaredMethod(
                "createCropExpression", List.class);
        method.setAccessible(true);
        return (String) method.invoke(null, boxes);
    }

    private int calculateFrameBudget(int lastTimelineSecond) throws Exception {
        Method method = LolAdaptiveKdaScanner.class.getDeclaredMethod(
                "calculateFrameBudget", int.class);
        method.setAccessible(true);
        return (Integer) method.invoke(scanner, lastTimelineSecond);
    }

    private void assertKda(LoLPicData data, int kill, int death, int assist) {
        Assert.assertEquals(kill, data.getK());
        Assert.assertEquals(death, data.getD());
        Assert.assertEquals(assist, data.getA());
    }
}
