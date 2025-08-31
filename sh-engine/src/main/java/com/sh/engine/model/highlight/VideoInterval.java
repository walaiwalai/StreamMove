package com.sh.engine.model.highlight;

import lombok.Data;

import java.io.File;

/**
 * @Author caiwen
 * @Date 2025 08 12 23 38
 **/
@Data
public class VideoInterval {
    /**
     * 截图的视频
     */
    private File fromVideo;

    /**
     * 视频开始时间
     */
    private double secondFromVideoStart;

    /**
     * 视频结束时间
     */
    private double secondToVideoEnd;

    public VideoInterval(File fromVideo, double secondFromVideoStart, double secondToVideoEnd) {
        this.fromVideo = fromVideo;
        this.secondFromVideoStart = secondFromVideoStart;
        this.secondToVideoEnd = secondToVideoEnd;
    }
}
