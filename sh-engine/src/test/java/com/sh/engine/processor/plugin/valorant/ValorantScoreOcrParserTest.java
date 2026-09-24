package com.sh.engine.processor.plugin.valorant;

import com.sh.engine.model.highlight.core.OcrTextDetection;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ValorantScoreOcrParserTest {
    private final ValorantScoreOcrParser parser = new ValorantScoreOcrParser();

    @Test
    public void shouldParseEachGridCellByCoordinates() {
        List<OcrTextDetection> detections = Arrays.asList(
                detection("0", 0.99f, 100, 80, 160, 160),
                detection("0:23", 0.99f, 380, 50, 580, 180),
                detection("0", 0.99f, 790, 80, 855, 160),
                detection("4", 0.99f, 1060, 80, 1120, 160),
                detection("3", 0.99f, 1750, 80, 1815, 160));

        List<ValorantScoreObservation> observations = parser.parse(
                detections, Arrays.asList(100.0, 104.0), 960, 270, 2);

        assertEquals(2, observations.size());
        assertEquals(Integer.valueOf(0), observations.get(0).getLeftScore());
        assertEquals("0:23", observations.get(0).getRoundTime());
        assertEquals(Integer.valueOf(0), observations.get(0).getRightScore());
        assertEquals(Integer.valueOf(4), observations.get(1).getLeftScore());
        assertNull(observations.get(1).getRoundTime());
        assertEquals(Integer.valueOf(3), observations.get(1).getRightScore());
    }

    @Test
    public void shouldIgnoreLowConfidenceAndInvalidTimer() {
        List<OcrTextDetection> detections = Arrays.asList(
                detection("9", 0.50f, 100, 80, 160, 160),
                detection("1:75", 0.99f, 380, 50, 580, 180),
                detection("E", 0.99f, 790, 80, 855, 160));

        ValorantScoreObservation observation = parser.parse(
                detections, Arrays.asList(100.0), 960, 270, 2).get(0);

        assertNull(observation.getLeftScore());
        assertNull(observation.getRoundTime());
        assertNull(observation.getRightScore());
    }

    private OcrTextDetection detection(String text, float confidence,
                                       int left, int top, int right, int bottom) {
        return new OcrTextDetection(text, confidence, Arrays.asList(
                left, top, right, top, right, bottom, left, bottom));
    }
}
