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

    /** 触发当前上下文召回的原始统计窗口开始秒。 */
    private int signalStartTime;

    /** 触发当前上下文召回的原始统计窗口结束秒。 */
    private int signalEndTime;

    /** 仅用于候选排序的通用弹幕统计分，不代表高光质量。 */
    private double recallScore;
}
