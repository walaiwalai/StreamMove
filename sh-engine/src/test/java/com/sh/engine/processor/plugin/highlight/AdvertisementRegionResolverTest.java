package com.sh.engine.processor.plugin.highlight;

import com.sh.engine.model.highlight.core.HighlightMaskPlan;
import org.junit.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AdvertisementRegionResolverTest {

    @Test
    public void shouldMergeTextAndExpandToPanelBoundary() {
        BufferedImage image = new BufferedImage(400, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(30, 30, 30));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(new Color(210, 210, 210));
        graphics.drawRect(20, 140, 110, 70);
        graphics.setColor(new Color(80, 80, 80));
        graphics.fillRect(21, 141, 109, 69);
        graphics.dispose();

        AdvertisementRegionResolver resolver = new AdvertisementRegionResolver();
        List<Rectangle> regions = resolver.resolveFrame(image, Arrays.asList(
                new Rectangle(35, 158, 52, 14),
                new Rectangle(33, 184, 70, 14)));

        assertEquals(1, regions.size());
        Rectangle region = regions.get(0);
        assertTrue(region.x <= 24);
        assertTrue(region.y <= 144);
        assertTrue(region.x + region.width >= 126);
        assertTrue(region.y + region.height >= 206);
    }

    @Test
    public void shouldMergeSameAdvertisementAcrossFramesButKeepDistantRegions() {
        AdvertisementRegionResolver resolver = new AdvertisementRegionResolver();
        HighlightMaskPlan plan = resolver.combineFrames(Arrays.asList(
                new Rectangle(10, 800, 280, 180),
                new Rectangle(18, 806, 270, 175),
                new Rectangle(1600, 900, 280, 150)), 1920, 1080);

        assertEquals(2, plan.getMasks().size());
    }
}
