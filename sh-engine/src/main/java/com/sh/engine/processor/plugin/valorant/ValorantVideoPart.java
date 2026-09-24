package com.sh.engine.processor.plugin.valorant;

import java.io.File;

/**
 * 一个录像分片及其在整段直播时间线中的位置。
 */
final class ValorantVideoPart {
    private final File sourceVideo;
    private final double globalStartSecond;
    private final double globalEndSecond;

    ValorantVideoPart(File sourceVideo,
                      double globalStartSecond,
                      double globalEndSecond) {
        this.sourceVideo = sourceVideo;
        this.globalStartSecond = globalStartSecond;
        this.globalEndSecond = globalEndSecond;
    }

    File getSourceVideo() {
        return sourceVideo;
    }

    double getGlobalStartSecond() {
        return globalStartSecond;
    }

    double getGlobalEndSecond() {
        return globalEndSecond;
    }

    double duration() {
        return globalEndSecond - globalStartSecond;
    }
}
