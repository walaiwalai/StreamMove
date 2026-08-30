package com.sh.engine.processor.plugin.highlight.lol;

import com.sh.engine.model.highlight.lol.LOLHeroPositionEnum;
import com.sh.engine.model.highlight.lol.LoLPicData;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public class LolHighlightTimelineProviderTest {
    private final LolHighlightTimelineProvider provider = new LolHighlightTimelineProvider();

    @Test
    public void shouldApplyStableKdaWeights() throws Exception {
        Assert.assertEquals(6.0f, score(new LoLPicData(1, 0, 0), new LoLPicData(2, 0, 0)), 0.001f);
        Assert.assertEquals(2.0f, score(new LoLPicData(1, 0, 0), new LoLPicData(1, 0, 1)), 0.001f);
        Assert.assertEquals(3.0f, score(new LoLPicData(1, 0, 0), new LoLPicData(2, 1, 0)), 0.001f);
        Assert.assertEquals(0.0f, score(LoLPicData.genInvalid(), new LoLPicData(0, 0, 0)), 0.001f);
    }

    @Test
    public void shouldCapDetailGainAndRewardMultipleKills() throws Exception {
        LoLPicData current = new LoLPicData(3, 0, 0);
        List<List<Float>> boxes = Arrays.asList(
                Arrays.asList(0f, 0f, 10f, 10f),
                Arrays.asList(11f, 0f, 20f, 10f),
                Arrays.asList(0f, 20f, 10f, 30f),
                Arrays.asList(11f, 20f, 20f, 30f));
        current.setHeroKADetail(new LoLPicData.HeroKillOrAssistDetail(
                boxes,
                Arrays.asList(
                        LOLHeroPositionEnum.MYSELF_KILL.getLabelId(),
                        LOLHeroPositionEnum.E_KILLED.getLabelId(),
                        LOLHeroPositionEnum.MYSELF_KILL.getLabelId(),
                        LOLHeroPositionEnum.E_KILLED.getLabelId())));

        // 2 次击杀基础 12 分 + 连杀 2 分 + 详情封顶 10 分。
        Assert.assertEquals(24.0f, score(new LoLPicData(1, 0, 0), current), 0.001f);
    }

    private float score(LoLPicData previous, LoLPicData current) throws Exception {
        Method method = LolHighlightTimelineProvider.class.getDeclaredMethod(
                "calculateScore", LoLPicData.class, LoLPicData.class);
        method.setAccessible(true);
        return (Float) method.invoke(provider, previous, current);
    }
}
