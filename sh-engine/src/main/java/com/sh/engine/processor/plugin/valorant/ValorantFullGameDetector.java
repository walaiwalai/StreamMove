package com.sh.engine.processor.plugin.valorant;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.ffmpeg.VideoDurationDetectCmd;
import com.sh.engine.model.highlight.VideoInterval;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 扫描比分时间线并把完整比赛映射回 P01、P02 等源文件。
 */
@Component
@Slf4j
public class ValorantFullGameDetector {
    private static final int POST_MATCH_PADDING_SECONDS =
            ValorantMatchStateMachine.MISSING_CONFIRMATION_COUNT
                    * ValorantScoreTimelineScanner.SAMPLE_INTERVAL_SECONDS;

    private final ValorantScoreTimelineScanner timelineScanner;
    private final ValorantMatchStateMachine stateMachine;

    public ValorantFullGameDetector(ValorantScoreTimelineScanner timelineScanner,
                                    ValorantMatchStateMachine stateMachine) {
        this.timelineScanner = timelineScanner;
        this.stateMachine = stateMachine;
    }

    public List<ValorantDetectedMatch> detect(List<File> sourceVideos) {
        List<ValorantVideoPart> videoParts = loadVideoParts(sourceVideos);
        List<ValorantScoreObservation> observations = timelineScanner.scan(videoParts);
        List<ValorantMatchStateMachine.DetectedRange> ranges =
                stateMachine.detect(observations);
        List<ValorantDetectedMatch> matches = new ArrayList<>();
        for (ValorantMatchStateMachine.DetectedRange range : ranges) {
            double paddedEndSecond = Math.min(
                    range.getEndSecond() + POST_MATCH_PADDING_SECONDS,
                    videoParts.get(videoParts.size() - 1).getGlobalEndSecond());
            List<VideoInterval> intervals = mapToSourceIntervals(
                    range.getStartSecond(), paddedEndSecond, videoParts);
            if (!intervals.isEmpty()) {
                matches.add(new ValorantDetectedMatch(
                        range.getStartSecond(), paddedEndSecond, intervals));
            }
        }
        log.info("valorant score detection completed, source parts: {}, "
                        + "observations: {}, complete matches: {}",
                videoParts.size(), observations.size(), matches.size());
        return matches;
    }

    private List<ValorantVideoPart> loadVideoParts(List<File> sourceVideos) {
        List<ValorantVideoPart> parts = new ArrayList<>();
        double globalStart = 0;
        for (File sourceVideo : sourceVideos) {
            double duration = detectDuration(sourceVideo);
            if (duration <= 0) {
                throw analysisError("cannot detect video duration: " + sourceVideo, null);
            }
            parts.add(new ValorantVideoPart(
                    sourceVideo, globalStart, globalStart + duration));
            globalStart += duration;
        }
        return parts;
    }

    private List<VideoInterval> mapToSourceIntervals(
            double matchStartSecond,
            double matchEndSecond,
            List<ValorantVideoPart> videoParts) {
        List<VideoInterval> intervals = new ArrayList<>();
        for (ValorantVideoPart videoPart : videoParts) {
            double globalStart = Math.max(
                    matchStartSecond, videoPart.getGlobalStartSecond());
            double globalEnd = Math.min(
                    matchEndSecond, videoPart.getGlobalEndSecond());
            if (globalEnd <= globalStart) {
                continue;
            }
            intervals.add(new VideoInterval(
                    videoPart.getSourceVideo(),
                    globalStart - videoPart.getGlobalStartSecond(),
                    globalEnd - videoPart.getGlobalStartSecond()));
        }
        return intervals;
    }

    private double detectDuration(File video) {
        VideoDurationDetectCmd command = new VideoDurationDetectCmd(video.getAbsolutePath());
        command.execute(100);
        return command.getDurationSeconds();
    }

    private StreamerRecordException analysisError(String message, Throwable cause) {
        if (cause == null) {
            return new StreamerRecordException(ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR, message);
        }
        return new StreamerRecordException(
                ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR, message, cause);
    }
}
