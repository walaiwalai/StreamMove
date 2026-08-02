package com.sh.engine.model.highlight.lol;

import java.io.File;

/**
 * KDA 在源视频中的一个逻辑时间点。
 *
 * 时间线可以包含没有实体截图的点，从而让评分逻辑保持每 4 秒一个点，
 * 同时避免为 KDA 没有变化的时间段生成图片。
 */
public class LolKdaTimelinePoint {
    private final File sourceVideo;
    private final int secondFromVideoStart;
    private LoLPicData picData;

    public LolKdaTimelinePoint(File sourceVideo, int secondFromVideoStart, LoLPicData picData) {
        this.sourceVideo = sourceVideo;
        this.secondFromVideoStart = secondFromVideoStart;
        this.picData = picData;
    }

    public File getSourceVideo() {
        return sourceVideo;
    }

    public int getSecondFromVideoStart() {
        return secondFromVideoStart;
    }

    public LoLPicData getPicData() {
        return picData;
    }

    public void setPicData(LoLPicData picData) {
        this.picData = picData;
    }
}
