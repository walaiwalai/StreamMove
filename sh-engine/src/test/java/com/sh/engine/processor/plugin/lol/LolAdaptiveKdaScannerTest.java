package com.sh.engine.processor.plugin.lol;

import com.sh.engine.model.highlight.lol.LoLPicData;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntFunction;

public class LolAdaptiveKdaScannerTest {
    private final LolAdaptiveKdaScanner scanner = new LolAdaptiveKdaScanner();

    @Test
    public void shouldSkipEightMinuteRangesWhenKdaIsUnchanged() {
        Set<Integer> sampledSeconds = new HashSet<>();
        IntFunction<LoLPicData> sampler = second -> {
            sampledSeconds.add(second);
            return new LoLPicData(1, 2, 3);
        };

        TreeMap<Integer, LoLPicData> timeline = scanner.buildTimeline(960, sampler);

        Assert.assertEquals(241, timeline.size());
        Assert.assertEquals(3, sampledSeconds.size());
        Assert.assertTrue(sampledSeconds.contains(0));
        Assert.assertTrue(sampledSeconds.contains(480));
        Assert.assertTrue(sampledSeconds.contains(960));
    }

    @Test
    public void shouldFindEveryKdaChangeInsideOneCoarseRange() {
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

        TreeMap<Integer, LoLPicData> timeline = scanner.buildTimeline(480, sampler);

        assertKda(timeline.get(96), 0, 0, 0);
        assertKda(timeline.get(100), 1, 0, 0);
        assertKda(timeline.get(296), 1, 0, 0);
        assertKda(timeline.get(300), 1, 0, 1);
        Assert.assertTrue(sampledSeconds.size() < 30);
    }

    @Test
    public void shouldLocateKdaResetInsteadOfApplyingMonotonicAssumption() {
        IntFunction<LoLPicData> sampler = second -> second < 200
                ? new LoLPicData(5, 3, 7)
                : new LoLPicData(0, 0, 0);

        TreeMap<Integer, LoLPicData> timeline = scanner.buildTimeline(480, sampler);

        assertKda(timeline.get(196), 5, 3, 7);
        assertKda(timeline.get(200), 0, 0, 0);
    }

    @Test
    public void shouldSkipRangeWhenBothEndsAndMiddleAreInvalid() {
        Set<Integer> sampledSeconds = new HashSet<>();
        IntFunction<LoLPicData> sampler = second -> {
            sampledSeconds.add(second);
            return LoLPicData.genInvalid();
        };

        TreeMap<Integer, LoLPicData> timeline = scanner.buildTimeline(480, sampler);

        Assert.assertEquals(121, timeline.size());
        Assert.assertEquals(3, sampledSeconds.size());
        Assert.assertTrue(timeline.values().stream().allMatch(LoLPicData::beBlank));
    }

    private void assertKda(LoLPicData data, int kill, int death, int assist) {
        Assert.assertEquals(kill, data.getK());
        Assert.assertEquals(death, data.getD());
        Assert.assertEquals(assist, data.getA());
    }
}
