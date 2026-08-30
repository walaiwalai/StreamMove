package com.sh.engine.model.highlight.core;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;

public class HighlightCutPolicyTest {

    @Test
    public void shouldSumEventScoresByDefault() {
        HighlightCutPolicy policy = policyBuilder().build();

        float score = policy.calculateClusterScore(Arrays.asList(event(2f), event(-1f)));

        Assert.assertEquals(1f, score, 0.001f);
    }

    @Test
    public void shouldUseConfiguredClusterScorer() {
        HighlightCutPolicy policy = policyBuilder()
                .clusterScorer(events -> events.size() * 3f)
                .build();

        float score = policy.calculateClusterScore(Arrays.asList(event(2f), event(-1f)));

        Assert.assertEquals(6f, score, 0.001f);
    }

    private HighlightCutPolicy.HighlightCutPolicyBuilder policyBuilder() {
        return HighlightCutPolicy.builder()
                .clusterGapSeconds(8)
                .preRollSeconds(7)
                .postRollSeconds(6)
                .minScore(2f)
                .minPositiveCount(1)
                .topN(10);
    }

    private HighlightEvent event(float score) {
        return HighlightEvent.builder()
                .sourceVideo(new File("P01.mp4"))
                .secondFromVideoStart(10)
                .eventType("TEST")
                .score(score)
                .confidence(1f)
                .build();
    }
}
