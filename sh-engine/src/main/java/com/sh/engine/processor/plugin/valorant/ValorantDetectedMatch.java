package com.sh.engine.processor.plugin.valorant;

import com.sh.engine.model.highlight.VideoInterval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一局完整比赛及其在各录像分片中的区间。
 */
public final class ValorantDetectedMatch {
    private final double globalStartSecond;
    private final double globalEndSecond;
    private final List<VideoInterval> intervals;

    public ValorantDetectedMatch(double globalStartSecond,
                                 double globalEndSecond,
                                 List<VideoInterval> intervals) {
        this.globalStartSecond = globalStartSecond;
        this.globalEndSecond = globalEndSecond;
        this.intervals = Collections.unmodifiableList(new ArrayList<>(intervals));
    }

    public double getGlobalStartSecond() {
        return globalStartSecond;
    }

    public double getGlobalEndSecond() {
        return globalEndSecond;
    }

    public List<VideoInterval> getIntervals() {
        return intervals;
    }
}
