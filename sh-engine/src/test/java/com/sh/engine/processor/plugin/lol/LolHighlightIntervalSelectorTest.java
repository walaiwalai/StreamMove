package com.sh.engine.processor.plugin.lol;

import com.sh.engine.model.highlight.SnapshotVideoInterval;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LolHighlightIntervalSelectorTest {
    private final LolHighlightIntervalSelector selector = new LolHighlightIntervalSelector();

    @Test
    public void shouldReturnNothingWhenThereAreNoCandidates() {
        Assert.assertTrue(selector.mergeIntervals(Collections.emptyList()).isEmpty());
    }

    @Test
    public void shouldMergeOverlappingIntervalsAndKeepComboScore() {
        File video = new File("P01.mp4");
        List<SnapshotVideoInterval> intervals = new ArrayList<>();
        intervals.add(new SnapshotVideoInterval(video, 0, 28, 9, 1));
        intervals.add(new SnapshotVideoInterval(video, 20, 48, 9, 1));

        List<SnapshotVideoInterval> merged = selector.mergeIntervals(intervals);

        Assert.assertEquals(1, merged.size());
        Assert.assertEquals(0.0, merged.get(0).getSecondFromVideoStart(), 0.001);
        Assert.assertEquals(48.0, merged.get(0).getSecondToVideoEnd(), 0.001);
        Assert.assertEquals(20.0f, merged.get(0).getScore(), 0.001f);
        Assert.assertEquals(2, merged.get(0).getKillCount());
    }

    @Test
    public void shouldKeepTheTenBestIntervalsInTimelineOrder() {
        File video = new File("P01.mp4");
        List<SnapshotVideoInterval> intervals = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            intervals.add(new SnapshotVideoInterval(video, i * 10, i * 10 + 1, i + 1));
        }

        List<SnapshotVideoInterval> selected = selector.mergeIntervals(intervals);

        Assert.assertEquals(10, selected.size());
        Assert.assertEquals(20.0, selected.get(0).getSecondFromVideoStart(), 0.001);
        Assert.assertEquals(110.0, selected.get(9).getSecondFromVideoStart(), 0.001);
    }
}
