package com.sh.engine.processor.plugin.valorant;

import com.sh.engine.model.highlight.core.OcrTextDetection;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按文字框坐标把 OCR 结果还原为每个采样帧的左比分、时间和右比分。
 */
@Component
public class ValorantScoreOcrParser {
    private static final float MINIMUM_CONFIDENCE = 0.75f;
    private static final double LEFT_SCORE_MIN_X_RATIO = 0.04;
    private static final double LEFT_SCORE_MAX_X_RATIO = 0.30;
    private static final double TIMER_MIN_X_RATIO = 0.30;
    private static final double TIMER_MAX_X_RATIO = 0.70;
    private static final double RIGHT_SCORE_MIN_X_RATIO = 0.70;
    private static final double RIGHT_SCORE_MAX_X_RATIO = 0.96;
    private static final double MAXIMUM_CENTER_Y_RATIO = 0.78;
    private static final Pattern SCORE_PATTERN = Pattern.compile("^\\d{1,2}$");
    private static final Pattern TIMER_PATTERN = Pattern.compile("^(\\d{1,2}):(\\d{1,2})$");

    public List<ValorantScoreObservation> parse(
            List<OcrTextDetection> detections,
            List<Double> frameSeconds,
            int cellWidth,
            int cellHeight,
            int columnCount) {
        if (frameSeconds == null || frameSeconds.isEmpty()
                || cellWidth <= 0 || cellHeight <= 0 || columnCount <= 0) {
            return new ArrayList<>();
        }

        MutableObservation[] observations = new MutableObservation[frameSeconds.size()];
        for (int index = 0; index < observations.length; index++) {
            observations[index] = new MutableObservation();
        }
        if (detections != null) {
            for (OcrTextDetection detection : detections) {
                applyDetection(detection, observations, cellWidth, cellHeight, columnCount);
            }
        }

        List<ValorantScoreObservation> result = new ArrayList<>(frameSeconds.size());
        for (int index = 0; index < observations.length; index++) {
            MutableObservation observation = observations[index];
            result.add(new ValorantScoreObservation(
                    frameSeconds.get(index), observation.leftScore,
                    observation.roundTime, observation.rightScore));
        }
        return result;
    }

    private void applyDetection(OcrTextDetection detection,
                                MutableObservation[] observations,
                                int cellWidth,
                                int cellHeight,
                                int columnCount) {
        if (detection == null || detection.getScore() < MINIMUM_CONFIDENCE) {
            return;
        }
        Bounds bounds = Bounds.from(detection.getBoxes());
        if (bounds == null) {
            return;
        }
        int column = (int) (bounds.centerX() / cellWidth);
        int row = (int) (bounds.centerY() / cellHeight);
        int frameIndex = row * columnCount + column;
        if (column < 0 || column >= columnCount
                || row < 0 || frameIndex < 0 || frameIndex >= observations.length) {
            return;
        }

        double localCenterX = bounds.centerX() - column * cellWidth;
        double localCenterY = bounds.centerY() - row * cellHeight;
        double xRatio = localCenterX / cellWidth;
        double yRatio = localCenterY / cellHeight;
        if (yRatio < 0 || yRatio > MAXIMUM_CENTER_Y_RATIO) {
            return;
        }

        String text = normalize(detection.getText());
        MutableObservation observation = observations[frameIndex];
        if (xRatio >= LEFT_SCORE_MIN_X_RATIO && xRatio < LEFT_SCORE_MAX_X_RATIO) {
            observation.setLeftScore(parseScore(text), detection.getScore());
        } else if (xRatio >= TIMER_MIN_X_RATIO && xRatio <= TIMER_MAX_X_RATIO) {
            observation.setRoundTime(parseTime(text), detection.getScore());
        } else if (xRatio > RIGHT_SCORE_MIN_X_RATIO && xRatio <= RIGHT_SCORE_MAX_X_RATIO) {
            observation.setRightScore(parseScore(text), detection.getScore());
        }
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim()
                .replace('O', '0')
                .replace('o', '0');
    }

    private Integer parseScore(String text) {
        return SCORE_PATTERN.matcher(text).matches()
                ? Integer.valueOf(text) : null;
    }

    private String parseTime(String text) {
        Matcher matcher = TIMER_PATTERN.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        int seconds = Integer.parseInt(matcher.group(2));
        return seconds <= 59 ? text : null;
    }

    private static final class MutableObservation {
        private Integer leftScore;
        private float leftConfidence;
        private String roundTime;
        private float timeConfidence;
        private Integer rightScore;
        private float rightConfidence;

        private void setLeftScore(Integer value, float confidence) {
            if (value != null && confidence >= leftConfidence) {
                leftScore = value;
                leftConfidence = confidence;
            }
        }

        private void setRoundTime(String value, float confidence) {
            if (value != null && confidence >= timeConfidence) {
                roundTime = value;
                timeConfidence = confidence;
            }
        }

        private void setRightScore(Integer value, float confidence) {
            if (value != null && confidence >= rightConfidence) {
                rightScore = value;
                rightConfidence = confidence;
            }
        }
    }

    private static final class Bounds {
        private final int minX;
        private final int minY;
        private final int maxX;
        private final int maxY;

        private Bounds(int minX, int minY, int maxX, int maxY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }

        private static Bounds from(List<Integer> boxes) {
            if (boxes == null || boxes.size() < 8 || boxes.size() % 2 != 0) {
                return null;
            }
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            for (int index = 0; index < boxes.size(); index += 2) {
                minX = Math.min(minX, boxes.get(index));
                maxX = Math.max(maxX, boxes.get(index));
                minY = Math.min(minY, boxes.get(index + 1));
                maxY = Math.max(maxY, boxes.get(index + 1));
            }
            return new Bounds(minX, minY, maxX, maxY);
        }

        private double centerX() {
            return (minX + maxX) / 2.0;
        }

        private double centerY() {
            return (minY + maxY) / 2.0;
        }
    }
}
