package com.sh.engine.model.video;

import lombok.Data;
import org.jetbrains.annotations.NotNull;

import java.io.File;

/**
 * @Author caiwen
 * @Date 2025 08 09 16 30
 **/
@Data
public class VideoSnapPoint implements Comparable<VideoSnapPoint> {
    /**
     * 截图文件
     */
    private File snapshotPic;

    /**
     * 截图的视频
     */
    private File fromVideo;

    /**
     * 从录制开始到截图的时间差
     */
    private double secondFromRecordingStart;

    /**
     * 从当前视频开始到截图的时间差
     */
    private double secondFromVideoStart;

    @Override
    public int compareTo(@NotNull VideoSnapPoint o) {
        // 按照secondFromRecordingStart进行排序
        return Double.compare(this.secondFromRecordingStart, o.secondFromRecordingStart);
    }
}
