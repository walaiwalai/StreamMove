package com.sh.engine.processor.plugin;

import com.sh.config.model.storage.FileStatusModel;
import com.sh.config.utils.DateUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.model.danmaku.HighlightAnalysisResult;
import com.sh.engine.model.danmaku.DanmakuTimeBucket;
import com.sh.engine.model.highlight.VideoInterval;
import com.sh.engine.processor.recorder.danmu.SimpleDanmaku;
import com.sh.engine.service.AsrService;
import com.sh.engine.service.DanmakuAnalysisService;
import com.sh.engine.service.HighlightAnalysisService;
import com.sh.engine.service.VideoMergeService;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI-powered highlight detection plugin using danmaku analysis and DeepSeek AI
 */
@Component
@Slf4j
public class DanmakuAIHighlightPlugin implements VideoProcessPlugin {

    private static final int MIN_DANMAKU_COUNT = 500;
    private static final int MIN_SCORE = 7;
    private static final String HIGHLIGHT_VIDEO = "ai-highlight.mp4";

    @Resource
    private DanmakuAnalysisService danmakuAnalysisService;

    @Resource
    private AsrService asrService;

    @Resource
    private HighlightAnalysisService highlightAnalysisService;

    @Resource
    private VideoMergeService videoMergeService;

    @Resource
    private MsgSendService msgSendService;

    @Override
    public String getPluginName() {
        return ProcessPluginEnum.DAN_MU_HL_VOD_CUT.getType();
    }

    @Override
    public int getMaxProcessParallel() {
        return 1;
    }

    @Override
    public boolean process(String recordPath) {
        File highlightFile = new File(recordPath, HIGHLIGHT_VIDEO);
        if (highlightFile.exists()) {
            log.info("AI highlight file already exists, skipping: {}", recordPath);
            return true;
        }

        // Read danmaku file
        File danmakuFile = new File(recordPath, RecordConstant.DAMAKU_TXT_ALL_FILE);
        if (!danmakuFile.exists()) {
            log.info("Danmaku file does not exist: {}", danmakuFile.getAbsolutePath());
            return true;
        }

        List<SimpleDanmaku> allDanmakus = readDanmakuFromFile(danmakuFile);
        if (allDanmakus.size() < MIN_DANMAKU_COUNT) {
            log.info("Danmaku count {} is less than minimum {}, skipping",
                    allDanmakus.size(), MIN_DANMAKU_COUNT);
            return true;
        }

        // Get video files sorted
        List<File> videoFiles = new ArrayList<>(FileUtils.listFiles(new File(recordPath), new String[]{"mp4"}, false))
                .stream()
                .filter(file -> file.getName().startsWith("P"))
                .sorted(Comparator.comparingInt(VideoFileUtil::getVideoIndex))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(videoFiles)) {
            log.info("No video files found: {}", recordPath);
            return true;
        }

        // Analyze danmaku peaks
        List<DanmakuTimeBucket> peakBuckets = danmakuAnalysisService.analyzeDanmakuPeak(recordPath, allDanmakus);
        if (CollectionUtils.isEmpty(peakBuckets)) {
            log.info("No peak buckets found: {}", recordPath);
            return true;
        }

        log.info("Found {} peak buckets for analysis", peakBuckets.size());

        // Process each peak bucket
        List<ConfirmedHighlight> confirmedHighlights = new ArrayList<>();

        for (DanmakuTimeBucket bucket : peakBuckets) {
            // Find video location for this bucket
            VideoLocation startLocation = findVideoAndOffset(videoFiles, bucket.getStartTime());
            VideoLocation endLocation = findVideoAndOffset(videoFiles, bucket.getEndTime());

            if (startLocation == null || endLocation == null) {
                log.warn("Cannot find video location for bucket: {}-{}s", bucket.getStartTime(), bucket.getEndTime());
                continue;
            }

            // Skip if crosses video boundary
            if (!startLocation.getVideoFile().equals(endLocation.getVideoFile())) {
                log.info("Skipping cross-boundary segment: {}-{}s (starts in {}, ends in {})",
                        bucket.getStartTime(), bucket.getEndTime(),
                        startLocation.getVideoFile().getName(),
                        endLocation.getVideoFile().getName());
                continue;
            }

            File videoFile = startLocation.getVideoFile();
            int segmentStart = bucket.getStartTime();
            int segmentEnd = bucket.getEndTime();

            // Get ASR text if available
            List<AsrSegment> asrSegments = null;
            if (asrService != null) {
                try {
                    asrSegments = asrService.transcribeSegment(videoFile, segmentStart, segmentEnd);
                } catch (Exception e) {
                    log.warn("ASR transcription failed for segment {}-{}s: {}",
                            segmentStart, segmentEnd, e.getMessage());
                }
            }

            // Filter danmakus for this segment
            List<SimpleDanmaku> segmentDanmakus = filterDanmakusByTime(allDanmakus, segmentStart, segmentEnd);

            // Analyze with DeepSeek
            HighlightAnalysisResult result = highlightAnalysisService.analyze(
                    asrSegments, segmentDanmakus, segmentStart, segmentEnd);

            if (result == null) {
                log.warn("Highlight analysis returned null for segment {}-{}s", segmentStart, segmentEnd);
                continue;
            }

            log.info("Highlight analysis result: isHighlight={}, score={}, reason={}",
                    result.isHighlight(), result.getScore(), result.getReason());

            // Check if it's a highlight with sufficient score
            if (result.isHighlight() && result.getScore() >= MIN_SCORE) {
                // Parse exact clip times if provided
                int clipStart = segmentStart;
                int clipEnd = segmentEnd;

                if (result.getExactClipStart() != null) {
                    clipStart = parseTimeToSeconds(result.getExactClipStart());
                }
                if (result.getExactClipEnd() != null) {
                    clipEnd = parseTimeToSeconds(result.getExactClipEnd());
                }

                // Ensure clip times are within segment bounds
                clipStart = Math.max(clipStart, segmentStart);
                clipEnd = Math.min(clipEnd, segmentEnd);

                confirmedHighlights.add(new ConfirmedHighlight(
                        videoFile, clipStart, clipEnd, result.getScore(), result.getReason()
                ));
            }
        }

        if (CollectionUtils.isEmpty(confirmedHighlights)) {
            log.info("No confirmed highlights found: {}", recordPath);
            return true;
        }

        log.info("Found {} confirmed highlights", confirmedHighlights.size());

        // Sort by score descending and take the best one
        confirmedHighlights.sort(Comparator.comparingInt(ConfirmedHighlight::getScore).reversed());
        ConfirmedHighlight bestHighlight = confirmedHighlights.get(0);

        log.info("Best highlight: {}-{}s, score={}, reason={}",
                bestHighlight.getStartTime(), bestHighlight.getEndTime(),
                bestHighlight.getScore(), bestHighlight.getReason());

        // Create video interval for merging
        VideoInterval interval = new VideoInterval(
                bestHighlight.getVideoFile(),
                bestHighlight.getStartTime(),
                bestHighlight.getEndTime()
        );

        // Generate title
        String timeStr = highlightFile.getParentFile().getName();
        String title = DateUtil.describeTime(timeStr, DateUtil.YYYY_MM_DD_HH_MM_SS_V2)
                + "\n" + StreamerInfoHolder.getCurStreamerName() + "直播精彩片段";

        // Merge video
        List<VideoInterval> intervals = Collections.singletonList(interval);
        boolean success = videoMergeService.mergeWithCover(intervals, highlightFile, title);

        // Send notification
        String msgPrefix = success ? "AI highlight generation completed! Path: "
                : "AI highlight generation failed! Path: ";
        msgSendService.sendText(msgPrefix + highlightFile.getAbsolutePath());

        return success;
    }

    /**
     * Read danmaku from file
     */
    private List<SimpleDanmaku> readDanmakuFromFile(File file) {
        List<SimpleDanmaku> danmakus = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    SimpleDanmaku danmaku = SimpleDanmaku.fromLine(line);
                    if (danmaku != null) {
                        danmakus.add(danmaku);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error reading danmaku file: {}", file.getAbsolutePath(), e);
        }
        return danmakus;
    }

    /**
     * Find which video file contains the given timestamp and the offset within that video
     */
    private VideoLocation findVideoAndOffset(List<File> videoFiles, int timestampSeconds) {
        File recordPath = videoFiles.get(0).getParentFile();
        FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(recordPath.getAbsolutePath());

        if (fileStatusModel == null || fileStatusModel.getMetaMap() == null) {
            // Fallback: assume single video
            if (videoFiles.size() == 1) {
                return new VideoLocation(videoFiles.get(0), timestampSeconds);
            }
            return null;
        }

        long targetTimestamp = timestampSeconds * 1000L; // Convert to milliseconds

        for (File videoFile : videoFiles) {
            FileStatusModel.VideoMetaInfo metaInfo = fileStatusModel.getMetaMap().get(videoFile.getName());
            if (metaInfo == null) {
                continue;
            }

            long startTime = metaInfo.getRecordStartTimeStamp();
            long endTime = metaInfo.getRecordEndTimeStamp();

            if (targetTimestamp >= startTime && targetTimestamp <= endTime) {
                double offset = (targetTimestamp - startTime) / 1000.0;
                return new VideoLocation(videoFile, offset);
            }
        }

        return null;
    }

    /**
     * Filter danmakus by time range
     */
    private List<SimpleDanmaku> filterDanmakusByTime(List<SimpleDanmaku> danmakus, int startTime, int endTime) {
        return danmakus.stream()
                .filter(d -> d.getTime() >= startTime && d.getTime() <= endTime)
                .collect(Collectors.toList());
    }

    /**
     * Parse HH:mm:ss or mm:ss to seconds
     */
    private int parseTimeToSeconds(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) {
            return 0;
        }

        String[] parts = timeStr.split(":");
        if (parts.length == 3) {
            // HH:mm:ss
            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            int seconds = Integer.parseInt(parts[2]);
            return hours * 3600 + minutes * 60 + seconds;
        } else if (parts.length == 2) {
            // mm:ss
            int minutes = Integer.parseInt(parts[0]);
            int seconds = Integer.parseInt(parts[1]);
            return minutes * 60 + seconds;
        } else if (parts.length == 1) {
            // ss
            return Integer.parseInt(parts[0]);
        }
        return 0;
    }

    /**
     * Helper class to store video location information
     */
    private static class VideoLocation {
        private final File videoFile;
        private final double offset;

        public VideoLocation(File videoFile, double offset) {
            this.videoFile = videoFile;
            this.offset = offset;
        }

        public File getVideoFile() {
            return videoFile;
        }

        public double getOffset() {
            return offset;
        }
    }

    /**
     * Helper class to store confirmed highlight information
     */
    private static class ConfirmedHighlight {
        private final File videoFile;
        private final int startTime;
        private final int endTime;
        private final int score;
        private final String reason;

        public ConfirmedHighlight(File videoFile, int startTime, int endTime, int score, String reason) {
            this.videoFile = videoFile;
            this.startTime = startTime;
            this.endTime = endTime;
            this.score = score;
            this.reason = reason;
        }

        public File getVideoFile() {
            return videoFile;
        }

        public int getStartTime() {
            return startTime;
        }

        public int getEndTime() {
            return endTime;
        }

        public int getScore() {
            return score;
        }

        public String getReason() {
            return reason;
        }
    }
}
