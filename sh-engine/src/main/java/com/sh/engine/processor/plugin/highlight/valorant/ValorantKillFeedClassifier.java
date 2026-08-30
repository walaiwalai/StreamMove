package com.sh.engine.processor.plugin.highlight.valorant;

import com.sh.engine.model.highlight.core.OcrTextDetection;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 根据 OCR 文本框中“我/Me”的横向位置判断本人击杀或本人被击杀。
 */
@Component
public class ValorantKillFeedClassifier {
    private static final double ROW_GROUP_HEIGHT_RATIO = 0.06;
    private static final Pattern ME_PATTERN = Pattern.compile(
            "(^|[^a-z])me([^a-z]|$)", Pattern.CASE_INSENSITIVE);

    /**
     * 根据 OCR 框的纵向位置把右上角击杀栏拆成多行，并分别判断本人击杀或死亡。
     *
     * @param detections 整块击杀栏 OCR 结果
     * @param imageWidth 击杀栏图片宽度
     * @param imageHeight 击杀栏图片高度
     * @param minConfidence 最低 OCR 置信度
     * @return 所有包含“我/Me”的击杀行，按从上到下排列
     */
    public List<Classification> classifyRows(List<OcrTextDetection> detections,
                                             int imageWidth,
                                             int imageHeight,
                                             float minConfidence) {
        if (detections == null || detections.isEmpty()
                || imageWidth <= 0 || imageHeight <= 0) {
            return new ArrayList<>();
        }

        List<OcrTextDetection> usable = detections.stream()
                .filter(detection -> detection.getScore() >= minConfidence)
                .filter(detection -> detection.getBoxes() != null
                        && detection.getBoxes().size() >= 8)
                .filter(detection -> StringUtils.isNotBlank(detection.getText()))
                .sorted(Comparator.comparingDouble(
                        detection -> centerY(detection.getBoxes())))
                .collect(Collectors.toList());
        List<List<OcrTextDetection>> rows = groupRows(usable, imageHeight);
        List<Classification> classifications = new ArrayList<>();
        for (List<OcrTextDetection> row : rows) {
            Classification classification = classifyRow(row, imageWidth);
            if (classification != null) {
                classifications.add(classification);
            }
        }
        return classifications;
    }

    private List<List<OcrTextDetection>> groupRows(List<OcrTextDetection> detections,
                                                   int imageHeight) {
        List<List<OcrTextDetection>> rows = new ArrayList<>();
        double tolerance = imageHeight * ROW_GROUP_HEIGHT_RATIO;
        for (OcrTextDetection detection : detections) {
            if (rows.isEmpty()) {
                rows.add(new ArrayList<>());
            }
            List<OcrTextDetection> currentRow = rows.get(rows.size() - 1);
            if (!currentRow.isEmpty()
                    && Math.abs(centerY(detection.getBoxes()) - rowCenterY(currentRow))
                    > tolerance) {
                currentRow = new ArrayList<>();
                rows.add(currentRow);
            }
            currentRow.add(detection);
        }
        return rows;
    }

    private Classification classifyRow(List<OcrTextDetection> row, int imageWidth) {
        OcrTextDetection marker = row.stream()
                .filter(detection -> containsSelfMarker(detection.getText()))
                .max(Comparator.comparingDouble(OcrTextDetection::getScore))
                .orElse(null);
        if (marker == null) {
            return null;
        }

        double markerCenterX = centerX(marker.getBoxes());
        Side side = classifyMarkerSide(marker, markerCenterX, imageWidth);
        String text = row.stream()
                .sorted(Comparator.comparingDouble(detection -> leftX(detection.getBoxes())))
                .map(OcrTextDetection::getText)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining(" "));
        return new Classification(
                side, text, marker.getScore(), markerCenterX, rowCenterY(row));
    }

    /**
     * OCR 可能把“我/Me”和玩家名合并为横跨整行的文本框，此时文本中的相对位置
     * 比文本框中心更可靠；独立标记或两侧均有文本时再使用坐标。
     */
    private Side classifyMarkerSide(OcrTextDetection marker,
                                    double markerCenterX,
                                    int imageWidth) {
        String text = marker.getText().trim();
        int markerStart = text.indexOf("我");
        int markerEnd = markerStart < 0 ? -1 : markerStart + 1;
        if (markerStart < 0) {
            Matcher matcher = ME_PATTERN.matcher(text);
            if (matcher.find()) {
                markerStart = matcher.start();
                markerEnd = matcher.end();
            }
        }

        boolean hasTextBefore = markerStart > 0 && !text.substring(0, markerStart).trim().isEmpty();
        boolean hasTextAfter = markerEnd >= 0
                && markerEnd < text.length()
                && !text.substring(markerEnd).trim().isEmpty();
        if (!hasTextBefore && hasTextAfter) {
            return Side.LEFT;
        }
        if (hasTextBefore && !hasTextAfter) {
            return Side.RIGHT;
        }
        return markerCenterX < imageWidth * 0.5 ? Side.LEFT : Side.RIGHT;
    }

    static boolean containsSelfMarker(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String normalized = text.trim();
        return normalized.contains("我")
                || ME_PATTERN.matcher(normalized.toLowerCase(Locale.ROOT)).find();
    }

    private double centerX(List<Integer> boxes) {
        List<Integer> xValues = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i += 2) {
            xValues.add(boxes.get(i));
        }
        return xValues.stream().mapToInt(Integer::intValue).average().orElse(0.0);
    }

    private double centerY(List<Integer> boxes) {
        List<Integer> yValues = new ArrayList<>();
        for (int i = 1; i < boxes.size(); i += 2) {
            yValues.add(boxes.get(i));
        }
        return yValues.stream().mapToInt(Integer::intValue).average().orElse(0.0);
    }

    private double rowCenterY(List<OcrTextDetection> row) {
        return row.stream()
                .mapToDouble(detection -> centerY(detection.getBoxes()))
                .average()
                .orElse(0.0);
    }

    private double leftX(List<Integer> boxes) {
        if (boxes == null || boxes.isEmpty()) {
            return Double.MAX_VALUE;
        }
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < boxes.size(); i += 2) {
            min = Math.min(min, boxes.get(i));
        }
        return min;
    }

    public enum Side {
        LEFT,
        RIGHT
    }

    public static class Classification {
        private final Side side;
        private final String text;
        private final float confidence;
        private final double markerCenterX;
        private final double rowCenterY;

        Classification(Side side,
                       String text,
                       float confidence,
                       double markerCenterX,
                       double rowCenterY) {
            this.side = side;
            this.text = text;
            this.confidence = confidence;
            this.markerCenterX = markerCenterX;
            this.rowCenterY = rowCenterY;
        }

        public Side getSide() {
            return side;
        }

        public String getText() {
            return text;
        }

        public float getConfidence() {
            return confidence;
        }

        public double getMarkerCenterX() {
            return markerCenterX;
        }

        public double getRowCenterY() {
            return rowCenterY;
        }
    }
}
