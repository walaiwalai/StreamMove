package com.sh.engine.service;

import com.sh.engine.model.danmaku.DanmakuTimeBucket;
import com.sh.engine.processor.recorder.danmu.SimpleDanmaku;

import java.util.List;

/**
 * Service for analyzing danmaku patterns and detecting peaks
 */
public interface DanmakuAnalysisService {

    /**
     * Analyze danmaku data to find peak intervals
     *
     * @param recordPath the path to the recorded video directory
     * @param danmakus list of danmaku messages
     * @return list of time buckets representing peak intervals
     */
    List<DanmakuTimeBucket> analyzeDanmakuPeak( String recordPath, List<SimpleDanmaku> danmakus);
}
