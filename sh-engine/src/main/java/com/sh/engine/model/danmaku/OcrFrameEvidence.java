package com.sh.engine.model.danmaku;

import com.sh.engine.model.highlight.core.OcrTextDetection;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** 单个视频时间点经过置信度过滤后的 OCR 文本证据。 */
public class OcrFrameEvidence {
    private int timestampSeconds;
    private List<String> texts = new ArrayList<>();

    public OcrFrameEvidence() {
    }

    public OcrFrameEvidence(
            int timestampSeconds, List<OcrTextDetection> detections) {
        this.timestampSeconds = timestampSeconds;
        this.texts = detections == null
                ? new ArrayList<>()
                : detections.stream()
                        .filter(item -> item != null && StringUtils.isNotBlank(item.getText()))
                        .map(OcrTextDetection::getText)
                        .collect(Collectors.toList());
    }

    public int getTimestampSeconds() {
        return timestampSeconds;
    }

    public void setTimestampSeconds(int timestampSeconds) {
        this.timestampSeconds = timestampSeconds;
    }

    public List<String> getTexts() {
        return Collections.unmodifiableList(texts);
    }

    public void setTexts(List<String> texts) {
        this.texts = texts == null ? new ArrayList<>() : new ArrayList<>(texts);
    }
}
