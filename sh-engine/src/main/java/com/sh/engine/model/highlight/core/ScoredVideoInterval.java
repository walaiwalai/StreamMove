package com.sh.engine.model.highlight.core;

import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.model.highlight.VideoInterval;
import lombok.Getter;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Comparator;

/**
 * 已完成评分的视频区间，同时保留形成该区间的事件便于日志和调试。
 */
@Getter
public class ScoredVideoInterval extends VideoInterval implements Comparable<ScoredVideoInterval> {
    private final float score;
    private final int positiveCount;
    private final int negativeCount;
    private final List<HighlightEvent> events;

    private static final Comparator<ScoredVideoInterval> SOURCE_TIME_ORDER = Comparator
            .comparingInt((ScoredVideoInterval interval) ->
                    VideoFileUtil.getVideoIndex(interval.getFromVideo()))
            .thenComparing(interval -> interval.getFromVideo().getAbsolutePath())
            .thenComparingDouble(ScoredVideoInterval::getSecondFromVideoStart);

    @Override
    public int compareTo(ScoredVideoInterval other) {
        if (this == other) {
            return 0;
        }
        if (other == null) {
            return 1;
        }
        return SOURCE_TIME_ORDER.compare(this, other);
    }

    public ScoredVideoInterval(File fromVideo,
                               double secondFromVideoStart,
                               double secondToVideoEnd,
                               float score,
                               int positiveCount,
                               int negativeCount,
                               List<HighlightEvent> events) {
        super(fromVideo, secondFromVideoStart, secondToVideoEnd);
        this.score = score;
        this.positiveCount = positiveCount;
        this.negativeCount = negativeCount;
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
    }
}
