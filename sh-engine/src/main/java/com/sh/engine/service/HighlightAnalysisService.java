package com.sh.engine.service;

import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.model.danmaku.HighlightAnalysisResult;
import com.sh.engine.processor.recorder.danmu.SimpleDanmaku;

import java.util.List;

/**
 * Service for analyzing video segments to determine if they are highlight moments.
 * <p>
 * This service uses AI (e.g., DeepSeek LLM) to analyze ASR text and danmaku
 * to judge whether a segment contains truly highlight-worthy content.
 */
public interface HighlightAnalysisService {

    /**
     * Analyze a video segment to determine if it's a highlight moment.
     *
     * @param asrSegments      List of ASR (speech recognition) segments with timestamps
     * @param danmakus         List of danmaku (chat messages) during the segment
     * @param segmentStartTime Segment start time in seconds
     * @param segmentEndTime   Segment end time in seconds
     * @return HighlightAnalysisResult containing AI analysis result
     */
    HighlightAnalysisResult analyze(List<AsrSegment> asrSegments, List<SimpleDanmaku> danmakus,
                                      int segmentStartTime, int segmentEndTime);
}
