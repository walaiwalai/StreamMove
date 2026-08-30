package com.sh.engine.processor.plugin;

import com.sh.engine.model.highlight.core.HighlightCutPolicy;
import com.sh.engine.model.highlight.core.HighlightEvent;
import com.sh.engine.processor.plugin.highlight.lol.LolHighlightTimelineProvider;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;

public class LoLVodHighLightCutPluginTest {

    @Test
    public void shouldAddComboBonusForMultipleKillEvents() {
        HighlightCutPolicy policy = new LoLVodHighLightCutPlugin().cutPolicy();
        HighlightEvent firstKill = HighlightEvent.builder()
                .sourceVideo(new File("P01.mp4"))
                .secondFromVideoStart(10)
                .eventType(LolHighlightTimelineProvider.SELF_KILL_EVENT)
                .score(6f)
                .positiveCount(1)
                .confidence(1f)
                .build();
        HighlightEvent secondKill = HighlightEvent.builder()
                .sourceVideo(new File("P01.mp4"))
                .secondFromVideoStart(20)
                .eventType(LolHighlightTimelineProvider.SELF_KILL_EVENT)
                .score(6f)
                .positiveCount(1)
                .confidence(1f)
                .build();

        float score = policy.calculateClusterScore(Arrays.asList(firstKill, secondKill));

        Assert.assertEquals(14f, score, 0.001f);
    }
}
