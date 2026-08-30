package com.sh.engine.model.highlight.lol;

import java.io.File;

/**
 * 某个源视频、某个时间点识别到的 KDA。
 *
 * <p>时间线固定为每 4 秒一个逻辑点，但只有自适应扫描命中的时间点会真正截帧 OCR。</p>
 */
public class LolKdaTimelinePoint {
    private final File sourceVideo;
    private final int secondFromVideoStart;
    private final LoLPicData picData;

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
}
