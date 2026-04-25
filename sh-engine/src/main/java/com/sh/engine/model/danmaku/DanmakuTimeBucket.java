package com.sh.engine.model.danmaku;

import com.sh.engine.processor.recorder.danmu.SimpleDanmaku;
import lombok.Data;

import java.util.List;

/**
 * Time bucket for grouping danmaku within a time range
 */
@Data
public class DanmakuTimeBucket {
    /**
     * 视频开始秒
     */
    private int startTime;

    /**
     * 视频结束秒数
     */
    private int endTime;
    private int count;
    private List<SimpleDanmaku> danmakus;
}
