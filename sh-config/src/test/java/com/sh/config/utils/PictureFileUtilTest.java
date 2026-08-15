package com.sh.config.utils;

import org.junit.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PictureFileUtilTest {

    @Test
    public void shrinksVerticalTitleIntoSafeAreaWithoutChangingExplicitLines() {
        BufferedImage image = new BufferedImage(1080, 1920, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            PictureFileUtil.TextLayout layout = PictureFileUtil.createTextLayout(
                    graphics,
                    "2026-08-15 中午1点直播\n芒果鱼直播精彩片段",
                    1080,
                    1920,
                    1920 / 13);

            assertEquals(2, layout.lines.size());
            assertTrue(layout.font.getSize() < 1920 / 13);
            assertFitsSafeArea(layout, 1080, 1920);
        } finally {
            graphics.dispose();
        }
    }

    @Test
    public void wrapsVeryLongTitleWhenMinimumReadableFontStillDoesNotFit() {
        BufferedImage image = new BufferedImage(120, 500, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            PictureFileUtil.TextLayout layout = PictureFileUtil.createTextLayout(
                    graphics,
                    "这是一个非常非常长的精彩视频标题",
                    120,
                    500,
                    20);

            assertTrue(layout.lines.size() > 1);
            assertFitsSafeArea(layout, 120, 500);
        } finally {
            graphics.dispose();
        }
    }

    @Test
    public void wrappedLineKeepsTheColorIndexOfItsOriginalLine() {
        BufferedImage image = new BufferedImage(120, 500, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            PictureFileUtil.TextLayout layout = PictureFileUtil.createTextLayout(
                    graphics,
                    "短标题\n这是第二行非常非常长的白色标题",
                    120,
                    500,
                    20);

            assertEquals(0, layout.lines.get(0).sourceLineIndex);
            for (int i = 1; i < layout.lines.size(); i++) {
                assertEquals(1, layout.lines.get(i).sourceLineIndex);
            }
            assertFitsSafeArea(layout, 120, 500);
        } finally {
            graphics.dispose();
        }
    }

    private static void assertFitsSafeArea(PictureFileUtil.TextLayout layout, int width, int height) {
        int availableWidth = width - 2 * layout.horizontalPadding;
        int availableHeight = height - 2 * layout.verticalPadding;
        for (PictureFileUtil.TextLine line : layout.lines) {
            assertTrue(layout.fontMetrics.stringWidth(line.text) <= availableWidth);
        }
        assertTrue(layout.lines.size() * layout.fontMetrics.getHeight() <= availableHeight);
    }
}
