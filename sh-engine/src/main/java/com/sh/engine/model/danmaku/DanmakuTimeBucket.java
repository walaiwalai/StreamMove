package com.sh.engine.model.danmaku;

import com.sh.engine.processor.recorder.danmu.SimpleDanmaku;
import lombok.Data;

import java.util.List;

/**
 * Time bucket for grouping danmaku within a time range
 */
@Data
public class DanmakuTimeBucket {
    private int startTime;
    private int endTime;
    private int count;
    private int emotionScore;
    private List<SimpleDanmaku> danmakus;
}
