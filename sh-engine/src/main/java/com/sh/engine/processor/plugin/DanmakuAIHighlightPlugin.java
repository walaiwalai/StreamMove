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
import com.sh.engine.service.LlmService;
import com.sh.engine.service.VideoMergeService;
import com.sh.engine.manager.CacheBizManager;
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
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI-powered highlight detection plugin using danmaku analysis and DeepSeek AI
 */
@Component
@Slf4j
public class DanmakuAIHighlightPlugin implements VideoProcessPlugin {

    private static final int MIN_DANMAKU_COUNT = 500;
    private static final int MIN_SCORE = 60;
    private static final String HIGHLIGHT_VIDEO = "highlight.mp4";

    @Resource
    private DanmakuAnalysisService danmakuAnalysisService;

    @Resource
    private AsrService asrService;

    @Resource
    private LlmService llmService;

    @Resource
    private VideoMergeService videoMergeService;

    @Resource
    private MsgSendService msgSendService;

    @Resource
    private CacheBizManager cacheBizManager;

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
        File danmakuFile = new File(recordPath, RecordConstant.DAMAKU_TXT_ALL_FILE);

        List<SimpleDanmaku> allDanmakus = readDanmakuFromFile(danmakuFile);
        if (allDanmakus.size() < MIN_DANMAKU_COUNT) {
            log.info("Danmaku count {} is less than minimum {}, skipping", allDanmakus.size(), MIN_DANMAKU_COUNT);
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

        // Process each peak bucket
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        List<ConfirmedHighlight> confirmedHighlights = new ArrayList<>();
        for (DanmakuTimeBucket bucket : peakBuckets) {
            ConfirmedHighlight confirmedHighlight = analyzePeakBucket(bucket, videoFiles, streamerName);
            if (confirmedHighlight == null) {
                continue;
            }
            confirmedHighlights.add(confirmedHighlight);
        }
        if (CollectionUtils.isEmpty(confirmedHighlights)) {
            log.info("No confirmed highlights found: {}", recordPath);
            return true;
        }

        int topK = Math.min(confirmedHighlights.size(), 10);
        confirmedHighlights.sort(Comparator.comparingInt(ConfirmedHighlight::getScore).reversed());
        List<ConfirmedHighlight> topHighlights = confirmedHighlights.subList(0, topK);
        topHighlights.sort(Comparator.comparingInt(ConfirmedHighlight::getStartTime));

        log.info("Found {} confirmed highlights, taking top {} by score", confirmedHighlights.size(), topK);

        List<VideoInterval> intervals = topHighlights.stream()
                .map(h -> new VideoInterval(h.getVideoFile(), h.getStartTime(), h.getEndTime()))
                .collect(Collectors.toList());

        // Generate title
        String timeStr = highlightFile.getParentFile().getName();
        String title = DateUtil.describeTime(timeStr, DateUtil.YYYY_MM_DD_HH_MM_SS_V2) + "\n" + StreamerInfoHolder.getCurStreamerName() + "直播精彩片段";

        // Merge video
        boolean success = videoMergeService.mergeWithCover(intervals, highlightFile, title);

        // Send notification
        String msgPrefix = success ? "AI highlight generation completed! Path: " : "AI highlight generation failed! Path: ";
        msgSendService.sendText(msgPrefix + highlightFile.getAbsolutePath());
        return success;
    }

    /**
     * Analyze a single peak bucket: locate video, run ASR + AI, return confirmed highlight if qualified
     */
    private ConfirmedHighlight analyzePeakBucket(DanmakuTimeBucket bucket, List<File> videoFiles, String streamerName) {
        VideoLocation startLoc = findVideoAndOffset(videoFiles, bucket.getStartTime());
        VideoLocation endLoc = findVideoAndOffset(videoFiles, bucket.getEndTime());
        if (startLoc == null || endLoc == null) {
            log.warn("Cannot find video location for bucket: {}-{}s", bucket.getStartTime(), bucket.getEndTime());
            return null;
        }

        if (!startLoc.getVideoFile().equals(endLoc.getVideoFile())) {
            log.info("Skipping cross-boundary segment: {}-{}s (starts in {}, ends in {})", bucket.getStartTime(), bucket.getEndTime(),
                    startLoc.getVideoFile().getName(), endLoc.getVideoFile().getName());
            return null;
        }

        File videoFile = startLoc.getVideoFile();
        int sessionStart = bucket.getStartTime();
        int fileStart = startLoc.getOffsetSecond();
        int fileEnd = endLoc.getOffsetSecond();
        int sessionToFileOffset = sessionStart - fileStart;

        // ASR (with cache)
        String cacheKey = videoFile.getName() + "-" + fileStart + "-" + fileEnd;
        List<AsrSegment> asrSegments = cacheBizManager.getAsrResult(streamerName, cacheKey);
        if (asrSegments == null) {
            asrSegments = asrService.transcribeSegment(videoFile, fileStart, fileEnd);
            if (asrSegments == null) {
                asrSegments = Collections.emptyList();
            }
            cacheBizManager.saveAsrResult(streamerName, cacheKey, asrSegments);
        }

        // Prompt uses file-relative times (danmaku timestamps adjusted by offset)
        HighlightAnalysisResult result = cacheBizManager.getHighlightAnalysis(streamerName, cacheKey);
        if (result == null) {
            String prompt = buildHighlightPrompt(asrSegments, bucket.getDanmakus(), fileStart, fileEnd, sessionToFileOffset);
            try {
                result = llmService.chat(prompt, HighlightAnalysisResult.class);
            } catch (Exception e) {
                log.error("Highlight analysis failed for segment {}-{}s", fileStart, fileEnd, e);
            }
            if (result != null) {
                cacheBizManager.saveHighlightAnalysis(streamerName, cacheKey, result);
            }
        }
        if (result == null || result.getScore() < MIN_SCORE) {
            return null;
        }
        // Parse LLM clip times, fallback to original range
        int clipStart = result.getExactClipStart() != null
                ? Math.max(parseTimeToSeconds(result.getExactClipStart()), fileStart) : fileStart;
        int clipEnd = result.getExactClipEnd() != null
                ? Math.min(parseTimeToSeconds(result.getExactClipEnd()), fileEnd) : fileEnd;
        log.info("Found confirmed highlight, clip: {}-{}s (original: {}-{}s), score: {}, reason: {}",
                clipStart, clipEnd, fileStart, fileEnd, result.getScore(), result.getReason());

        return new ConfirmedHighlight(videoFile, clipStart, clipEnd, result.getScore(), result.getReason());
    }


    private String buildHighlightPrompt(List<AsrSegment> asrSegments, List<SimpleDanmaku> danmakus,
                                        int segStart, int segEnd, int timeOffset) {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        String startStr = formatTime(segStart);
        String endStr = formatTime(segEnd);

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一名专业的游戏直播剪辑师，只筛选游戏中的精彩/搞笑片段。主播名字是「").append(streamerName).append("」。\n");
        prompt.append("以下是该主播直播中一段弹幕密集片段的数据（时间范围 ").append(startStr).append(" ~ ").append(endStr).append("）：\n\n");

        prompt.append("【主播语音】\n");
        if (asrSegments == null || asrSegments.isEmpty()) {
            prompt.append("（该片段未检测到语音）\n");
        } else {
            asrSegments.forEach(seg ->
                    prompt.append("[").append(formatTime(seg.getStartTime())).append("-").append(formatTime(seg.getEndTime()))
                            .append("] ").append(seg.getText()).append("\n"));
        }

        prompt.append("\n【观众弹幕统计】\n");
        if (danmakus == null || danmakus.isEmpty()) {
            prompt.append("（该片段没有弹幕）\n");
        } else {
            String aggregatedDanmaku = aggregateDanmakus(danmakus, timeOffset);
            prompt.append(aggregatedDanmaku);
        }

        prompt.append("\n请判断这段是否为游戏中的精彩/搞笑片段。\n");
        prompt.append("核心标准：精彩/搞笑的原因必须源于【游戏内发生的事情】，而非直播间内的人际互动或其他事。\n");
        prompt.append("无论弹幕多密集、观众反应多强烈，只要精彩的原因不在游戏本身，一律给低分（<30分）。\n");
        prompt.append("请严格打分，大部分片段不会是精彩片段，不要轻易给高分。\n\n");
        prompt.append("如果确认为高光片段，请根据弹幕和语音的时间分布，精确定位精彩片段的起止时间。\n");
        prompt.append("exactClipStart应定位到精彩事件发生前几秒，exactClipEnd应定位到观众反应结束后几秒。\n");
        prompt.append("不要简单地返回原始时间范围，要根据实际内容精确裁剪。\n\n");
        prompt.append("满分为100分进行打分，并严格按照以下JSON格式输出：\n");
        prompt.append("{\n");
        prompt.append("  \"highlight\": true,\n");
        prompt.append("  \"score\": 65,\n");
        prompt.append("  \"reason\": \"判断理由\",\n");
        prompt.append("  \"exactClipStart\": \"").append(startStr).append("\",\n");
        prompt.append("  \"exactClipEnd\": \"").append(endStr).append("\",\n");
        prompt.append("  \"suggestedTitle\": \"建议的标题\"\n");
        prompt.append("}\n");
        return prompt.toString();
    }

    private String formatTime(int seconds) {
        return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }


    /**
     * Aggregate danmakus: repeated texts show with count, unique texts grouped by second keep top 2 longest.
     */
    private String aggregateDanmakus(List<SimpleDanmaku> danmakus, int timeOffset) {
        // Step 1: Group by text content to count occurrences
        Map<String, List<SimpleDanmaku>> textGroups = new LinkedHashMap<>();
        for (SimpleDanmaku d : danmakus) {
            textGroups.computeIfAbsent(d.getText(), k -> new ArrayList<>()).add(d);
        }

        // Step 2: Split into repeated (count >= 2) and unique (count == 1)
        List<String> lines = new ArrayList<>();
        // Collect unique danmaku grouped by second for later processing
        Map<Integer, List<SimpleDanmaku>> uniqueBySecond = new TreeMap<>();

        for (Map.Entry<String, List<SimpleDanmaku>> entry : textGroups.entrySet()) {
            List<SimpleDanmaku> items = entry.getValue();
            if (items.size() >= 2) {
                // Repeated text: show with count at earliest time
                int earliestTime = items.stream()
                        .mapToInt(d -> (int) d.getTime() - timeOffset)
                        .min().orElse(0);
                lines.add("[" + formatTime(earliestTime) + "] " + entry.getKey() + " x" + items.size());
            } else {
                // Unique text: group by second for filtering
                SimpleDanmaku d = items.get(0);
                int second = (int) d.getTime() - timeOffset;
                uniqueBySecond.computeIfAbsent(second, k -> new ArrayList<>()).add(d);
            }
        }

        // Step 3: For each second of unique danmaku, keep top 2 longest content
        for (Map.Entry<Integer, List<SimpleDanmaku>> entry : uniqueBySecond.entrySet()) {
            String timeStr = formatTime(entry.getKey());
            List<SimpleDanmaku> list = entry.getValue();
            list.sort((a, b) -> Integer.compare(b.getText().length(), a.getText().length()));
            int limit = Math.min(list.size(), 2);
            for (int i = 0; i < limit; i++) {
                lines.add("[" + timeStr + "] " + list.get(i).getText());
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        sb.append("\n弹幕总数: ").append(danmakus.size());
        return sb.toString();
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
    private VideoLocation findVideoAndOffset(List<File> videoFiles, int sessionTime) {
        File recordPath = videoFiles.get(0).getParentFile();
        FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(recordPath.getAbsolutePath());

        if (fileStatusModel == null || fileStatusModel.getMetaMap() == null) {
            if (videoFiles.size() == 1) {
                return new VideoLocation(videoFiles.get(0), sessionTime);
            }
            return null;
        }

        long baseTimestamp = fileStatusModel.getMetaMap().values().stream()
                .mapToLong(FileStatusModel.VideoMetaInfo::getRecordStartTimeStamp)
                .min().orElse(0L);
        long targetTimestamp = baseTimestamp + sessionTime;

        for (File videoFile : videoFiles) {
            FileStatusModel.VideoMetaInfo metaInfo = fileStatusModel.getMetaMap().get(videoFile.getName());
            if (metaInfo == null) {
                continue;
            }

            long startTime = metaInfo.getRecordStartTimeStamp();
            long endTime = metaInfo.getRecordEndTimeStamp();

            if (targetTimestamp >= startTime && targetTimestamp <= endTime) {
                int offset = (int) (targetTimestamp - startTime);
                return new VideoLocation(videoFile, offset);
            }
        }

        return null;
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
        private final int offsetSecond;

        public VideoLocation(File videoFile, int offsetSecond) {
            this.videoFile = videoFile;
            this.offsetSecond = offsetSecond;
        }

        public File getVideoFile() {
            return videoFile;
        }

        public int getOffsetSecond() {
            return offsetSecond;
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
