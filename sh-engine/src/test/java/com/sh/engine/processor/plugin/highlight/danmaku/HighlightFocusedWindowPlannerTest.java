package com.sh.engine.processor.plugin.highlight.danmaku;

import com.sh.engine.model.danmaku.HighlightClipRange;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class HighlightFocusedWindowPlannerTest {
    private final HighlightFocusedWindowPlanner planner =
            new HighlightFocusedWindowPlanner();

    @Test
    public void shouldKeepShortReviewWindowWhole() {
        List<HighlightClipRange> windows = planner.plan(
                new HighlightClipRange(100, 150));

        Assert.assertEquals(1, windows.size());
        Assert.assertEquals(100, windows.get(0).getStartSecond());
        Assert.assertEquals(150, windows.get(0).getEndSecond());
    }

    @Test
    public void shouldCoverLongReviewWindowWithOverlappingFocusedWindows() {
        List<HighlightClipRange> windows = planner.plan(
                new HighlightClipRange(2802, 2877));

        Assert.assertEquals(2, windows.size());
        Assert.assertEquals(2802, windows.get(0).getStartSecond());
        Assert.assertEquals(2847, windows.get(0).getEndSecond());
        Assert.assertEquals(2832, windows.get(1).getStartSecond());
        Assert.assertEquals(2877, windows.get(1).getEndSecond());
        Assert.assertTrue(windows.get(0).getEndSecond()
                > windows.get(1).getStartSecond());
    }
}
