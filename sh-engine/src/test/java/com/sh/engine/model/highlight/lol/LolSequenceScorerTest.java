package com.sh.engine.model.highlight.lol;

import com.sh.engine.model.highlight.SnapshotVideoInterval;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class LolSequenceScorerTest {
    @Test
    public void shouldApplyStableKdaWeights() {
        Assert.assertEquals(6.0f, score(new LoLPicData(1, 0, 0), new LoLPicData(2, 0, 0)), 0.001f);
        Assert.assertEquals(2.0f, score(new LoLPicData(1, 0, 0), new LoLPicData(1, 0, 1)), 0.001f);
        Assert.assertEquals(3.0f, score(new LoLPicData(1, 0, 0), new LoLPicData(2, 1, 0)), 0.001f);
        Assert.assertEquals(0.0f, score(LoLPicData.genInvalid(), new LoLPicData(0, 0, 0)), 0.001f);
    }

    @Test
    public void shouldCapDetailGainAndRewardMultipleKills() {
        LoLPicData current = new LoLPicData(3, 0, 0);
        List<List<Float>> boxes = Arrays.asList(
                Arrays.asList(0f, 0f, 10f, 10f),
                Arrays.asList(11f, 0f, 20f, 10f),
                Arrays.asList(0f, 20f, 10f, 30f),
                Arrays.asList(11f, 20f, 20f, 30f)
        );
        current.setHeroKADetail(new LoLPicData.HeroKillOrAssistDetail(
                boxes,
                Arrays.asList(
                        LOLHeroPositionEnum.MYSELF_KILL.getLabelId(),
                        LOLHeroPositionEnum.E_KILLED.getLabelId(),
                        LOLHeroPositionEnum.MYSELF_KILL.getLabelId(),
                        LOLHeroPositionEnum.E_KILLED.getLabelId()
                )
        ));

        // 2次击杀基础12分 + 连杀2分 + 详情封顶10分。
        Assert.assertEquals(24.0f, score(new LoLPicData(1, 0, 0), current), 0.001f);
    }

    @Test
    public void shouldAddComboGainWhenKillIntervalsMerge() {
        File video = new File("P01.mp4");
        SnapshotVideoInterval first = new SnapshotVideoInterval(video, 0, 28, 9, 1);
        SnapshotVideoInterval second = new SnapshotVideoInterval(video, 20, 48, 9, 1);

        SnapshotVideoInterval merged = first.merge(second);
        Assert.assertEquals(20.0f, merged.getScore(), 0.001f);
        Assert.assertEquals(2, merged.getKillCount());
    }

    private float score(LoLPicData previous, LoLPicData current) {
        List<LoLPicData> sequence = new LolSequenceScorer(Arrays.asList(previous, current)).getSequences();
        return sequence.get(1).getScore();
    }
}
