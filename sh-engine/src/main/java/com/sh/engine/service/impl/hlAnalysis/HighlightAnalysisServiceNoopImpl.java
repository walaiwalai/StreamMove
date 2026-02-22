package com.sh.engine.service.impl.hlAnalysis;

import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.model.danmaku.HighlightAnalysisResult;
import com.sh.engine.processor.recorder.danmu.SimpleDanmaku;
import com.sh.engine.service.HighlightAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * No-op implementation of HighlightAnalysisService.
 * <p>
 * This implementation returns a default non-highlight result and is used when
 * highlight analysis is disabled or no AI provider is configured.
 *
 * @Author : caiwen
 * @Date: 2026/2/21
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "highlight.analysis.provider", havingValue = "none", matchIfMissing = true)
public class HighlightAnalysisServiceNoopImpl implements HighlightAnalysisService {

    @Override
    public HighlightAnalysisResult analyze(List<AsrSegment> asrSegments, List<SimpleDanmaku> danmakus,
                                           int segmentStartTime, int segmentEndTime) {
        log.debug("No-op highlight analysis service called for segment: {}s to {}s",
                segmentStartTime, segmentEndTime);

        HighlightAnalysisResult result = new HighlightAnalysisResult();
        result.setHighlight(false);
        result.setScore(0);
        result.setReason("Highlight analysis is disabled");
        result.setExactClipStart(formatTime(segmentStartTime));
        result.setExactClipEnd(formatTime(segmentEndTime));
        result.setSuggestedTitle("");
        return result;
    }

    private String formatTime(int seconds) {
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }
}
