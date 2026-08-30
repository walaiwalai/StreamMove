package com.sh.engine.model.highlight.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HighlightMaskTest {

    @Test
    public void shouldConvertNormalizedMaskToClippedPixels() {
        HighlightMask mask = new HighlightMask(0.9, 0.8, 0.1, 0.2);

        assertEquals(1728, mask.pixelX(1920));
        assertEquals(864, mask.pixelY(1080));
        assertEquals(192, mask.pixelWidth(1920));
        assertEquals(216, mask.pixelHeight(1080));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectMaskOutsideFrame() {
        new HighlightMask(0.9, 0.2, 0.2, 0.1);
    }
}
