package com.sh.engine.model.highlight.core;

import com.sh.config.utils.VideoFileUtil;
import lombok.Builder;
import lombok.Getter;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Comparator;
import java.util.Map;

/**
 * 游戏识别逻辑输出的统一高光事件。
 */
@Getter
public final class HighlightEvent implements Comparable<HighlightEvent> {
    private final File sourceVideo;
    private final double secondFromVideoStart;
    private final String eventType;
    private final float score;
    private final int positiveCount;
    private final int negativeCount;
    private final float confidence;
    private final Map<String, Object> evidence;

    private static final Comparator<HighlightEvent> SOURCE_TIME_ORDER = Comparator
            .comparingInt((HighlightEvent event) -> VideoFileUtil.getVideoIndex(event.getSourceVideo()))
            .thenComparing(event -> event.getSourceVideo().getAbsolutePath())
            .thenComparingDouble(HighlightEvent::getSecondFromVideoStart);

    @Override
    public int compareTo(HighlightEvent other) {
        if (other == null) {
            return 1;
        }
        if (this == other) {
            return 0;
        }
        return SOURCE_TIME_ORDER.compare(this, other);
    }

    @Builder
    public HighlightEvent(File sourceVideo,
                          double secondFromVideoStart,
                          String eventType,
                          float score,
                          int positiveCount,
                          int negativeCount,
                          float confidence,
                          Map<String, Object> evidence) {
        if (sourceVideo == null) {
            throw new IllegalArgumentException("sourceVideo must not be null");
        }
        if (secondFromVideoStart < 0) {
            throw new IllegalArgumentException("event second must not be negative");
        }
        if (eventType == null || eventType.trim().isEmpty()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (confidence < 0f || confidence > 1f) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        this.sourceVideo = sourceVideo;
        this.secondFromVideoStart = secondFromVideoStart;
        this.eventType = eventType;
        this.score = score;
        this.positiveCount = Math.max(0, positiveCount);
        this.negativeCount = Math.max(0, negativeCount);
        this.confidence = confidence;
        this.evidence = evidence == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(evidence));
    }
}
