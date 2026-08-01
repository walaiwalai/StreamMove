package com.sh.engine.processor.plugin.lol;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class LolHighlightSnapshotServiceTest {
    @Test
    public void shouldCreateCropExpressionFromDetectedBox() {
        List<List<Integer>> points = Arrays.asList(
                Arrays.asList(100, 20),
                Arrays.asList(180, 20),
                Arrays.asList(180, 50),
                Arrays.asList(100, 50));

        Assert.assertEquals(
                "crop=100:40:in_w/2+100:15",
                LolHighlightSnapshotService.createCropExpression(points));
    }

    @Test
    public void shouldRejectIncompleteDetectedBox() {
        Assert.assertNull(LolHighlightSnapshotService.createCropExpression(
                Arrays.asList(Arrays.asList(100, 20))));
    }
}
