package com.sh.engine.service.impl;

import com.sh.config.model.storage.FileStatusModel;
import com.sh.engine.model.danmaku.DanmakuTimeBucket;
import com.sh.engine.processor.recorder.danmu.SimpleDanmaku;
import com.sh.engine.service.DanmakuAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Implementation of DanmakuAnalysisService for analyzing danmaku patterns
 */
@Service
@Slf4j
public class DanmakuAnalysisServiceImpl implements DanmakuAnalysisService {

    /**
     * Time bucket size in seconds (10 seconds)
     */
    private static final int BUCKET_SIZE = 10;

    /**
     * Extension time in seconds before and after peak (30 seconds)
     */
    private static final int EXTEND_TIME = 30;

    /**
     * Z-Score threshold for peak detection (2.0)
     */
    private static final double Z_SCORE_THRESHOLD = 2.0;

    /**
     * Pattern for repeated characters (e.g., 哈哈哈, 666, 啊啊啊)
     */
    private static final Pattern REPEATED_CHARS_PATTERN = Pattern.compile("(.)\\1{2,}");

    /**
     * Pattern for high-emotion punctuation (??? or !!! or combinations)
     */
    private static final Pattern HIGH_EMOTION_PUNCTUATION_PATTERN = Pattern.compile("([?！])\\1{2,}|([?!！？])\\1+");

    @Override
    public List<DanmakuTimeBucket> analyzeDanmakuPeak( String recordPath, List<SimpleDanmaku> danmakus) {
        if (StringUtils.isBlank(recordPath) || CollectionUtils.isEmpty(danmakus)) {
            return Collections.emptyList();
        }

        // 1. Load FileStatusModel and get video file durations
        FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(recordPath);
        if (fileStatusModel == null || fileStatusModel.getMetaMap() == null) {
            log.warn("No fileStatus.json found or metaMap is empty: {}", recordPath);
            return Collections.emptyList();
        }

        Map<File, Double> videoDurations = getVideoDurations(recordPath, fileStatusModel);
        if (videoDurations.isEmpty()) {
            log.warn("No video files found in record path: {}", recordPath);
            return Collections.emptyList();
        }

        // 2. Group danmaku into 10-second buckets
        List<DanmakuTimeBucket> buckets = groupIntoBuckets(danmakus);
        if (buckets.isEmpty()) {
            log.warn("No time buckets created from danmaku data");
            return Collections.emptyList();
        }

        // 3. Detect peaks using Z-Score algorithm
        List<DanmakuTimeBucket> peakBuckets = detectPeaks(buckets);

        // 4. Extend peak time and check video boundaries
        List<DanmakuTimeBucket> extendedBuckets = extendAndValidatePeaks(peakBuckets, videoDurations, fileStatusModel);

        log.info("Danmaku analysis completed: {} peaks detected from {} buckets", extendedBuckets.size(), buckets.size());
        return extendedBuckets;
    }

    private int calculateEmotionScore(String text) {
        if (StringUtils.isBlank(text)) {
            return 1;
        }

        int score = 1; // Base score

        // Add 2 for repeated characters (哈哈哈, 666, 啊啊啊)
        if (REPEATED_CHARS_PATTERN.matcher(text).find()) {
            score += 2;
        }

        // Add 2 for high-emotion punctuation (???, !!!)
        if (HIGH_EMOTION_PUNCTUATION_PATTERN.matcher(text).find()) {
            score += 2;
        }

        return score;
    }

    private List<DanmakuTimeBucket> detectPeaks( List<DanmakuTimeBucket> buckets) {
        if (CollectionUtils.isEmpty(buckets)) {
            return Collections.emptyList();
        }

        // Calculate mean (μ) and standard deviation (σ)
        double mean = buckets.stream()
                .mapToInt(DanmakuTimeBucket::getEmotionScore)
                .average()
                .orElse(0.0);

        double variance = buckets.stream()
                .mapToInt(DanmakuTimeBucket::getEmotionScore)
                .mapToDouble(score -> Math.pow(score - mean, 2))
                .average()
                .orElse(0.0);

        double stdDev = Math.sqrt(variance);

        log.debug("Danmaku statistics: mean={}, stdDev={}, threshold={}", mean, stdDev, mean + Z_SCORE_THRESHOLD * stdDev);

        // Mark buckets as peaks if emotionScore > μ + 2σ
        double threshold = mean + Z_SCORE_THRESHOLD * stdDev;
        List<DanmakuTimeBucket> peaks = new ArrayList<>();

        for (DanmakuTimeBucket bucket : buckets) {
            if (bucket.getEmotionScore() > threshold) {
                peaks.add(bucket);
            }
        }

        return peaks;
    }

    /**
     * Group danmaku into 10-second time buckets
     */
    private List<DanmakuTimeBucket> groupIntoBuckets( List<SimpleDanmaku> danmakus) {
        // Find the maximum time to determine number of buckets
        float maxTime = danmakus.stream()
                .map(SimpleDanmaku::getTime)
                .max(Float::compare)
                .orElse(0.0f);

        int numBuckets = (int) Math.ceil(maxTime / BUCKET_SIZE) + 1;

        // Initialize buckets
        List<DanmakuTimeBucket> buckets = new ArrayList<>();
        for (int i = 0; i < numBuckets; i++) {
            DanmakuTimeBucket bucket = new DanmakuTimeBucket();
            bucket.setStartTime(i * BUCKET_SIZE);
            bucket.setEndTime((i + 1) * BUCKET_SIZE);
            bucket.setCount(0);
            bucket.setEmotionScore(0);
            bucket.setDanmakus(new ArrayList<>());
            buckets.add(bucket);
        }

        // Group danmaku into buckets
        for (SimpleDanmaku danmaku : danmakus) {
            int bucketIndex = (int) (danmaku.getTime() / BUCKET_SIZE);
            if (bucketIndex >= 0 && bucketIndex < buckets.size()) {
                DanmakuTimeBucket bucket = buckets.get(bucketIndex);
                bucket.getDanmakus().add(danmaku);
                bucket.setCount(bucket.getCount() + 1);
                bucket.setEmotionScore(bucket.getEmotionScore() + calculateEmotionScore(danmaku.getText()));
            }
        }

        // Remove empty buckets
        return buckets.stream()
                .filter(b -> b.getCount() > 0)
                .collect(Collectors.toList());
    }

    /**
     * Get video file durations from FileStatusModel
     */
    private Map<File, Double> getVideoDurations(String recordPath, FileStatusModel fileStatusModel) {
        Map<File, Double> durations = new LinkedHashMap<>();
        File dir = new File(recordPath);

        if (!dir.exists() || !dir.isDirectory()) {
            return durations;
        }

        Map<String, FileStatusModel.VideoMetaInfo> metaMap = fileStatusModel.getMetaMap();

        // Find all mp4 files and sort by name (P01.mp4, P02.mp4, etc.)
        File[] videoFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".mp4"));
        if (videoFiles == null) {
            return durations;
        }

        Arrays.sort(videoFiles, Comparator.comparing(File::getName));

        double accumulatedTime = 0;
        for (File videoFile : videoFiles) {
            FileStatusModel.VideoMetaInfo metaInfo = metaMap.get(videoFile.getName());
            if (metaInfo != null && metaInfo.getDurationSecond() > 0) {
                durations.put(videoFile, accumulatedTime);
                accumulatedTime += metaInfo.getDurationSecond();
            } else {
                log.warn("No metadata found for video: {}", videoFile.getName());
            }
        }

        return durations;
    }

    /**
     * Extend peak time by 30 seconds before and after, and validate against video boundaries
     */
    private List<DanmakuTimeBucket> extendAndValidatePeaks( List<DanmakuTimeBucket> peakBuckets, Map<File, Double> videoDurations,
                                                            FileStatusModel fileStatusModel) {
        if (CollectionUtils.isEmpty(peakBuckets)) {
            return Collections.emptyList();
        }

        // Convert video durations map to a list for easier processing
        List<Map.Entry<File, Double>> videoEntries = new ArrayList<>(videoDurations.entrySet());
        if (videoEntries.isEmpty()) {
            return peakBuckets;
        }

        Map<String, FileStatusModel.VideoMetaInfo> metaMap = fileStatusModel.getMetaMap();
        List<DanmakuTimeBucket> extendedBuckets = new ArrayList<>();

        for (DanmakuTimeBucket peak : peakBuckets) {
            int originalStart = peak.getStartTime();
            int originalEnd = peak.getEndTime();

            // Extend by 30 seconds before and after
            int extendedStart = Math.max(0, originalStart - EXTEND_TIME);
            int extendedEnd = originalEnd + EXTEND_TIME;

            // Find which video file this peak belongs to
            File targetVideo = findVideoForTime(originalStart, videoEntries, metaMap);
            if (targetVideo == null) {
                log.warn("Cannot find video file for time: {}", originalStart);
                continue;
            }

            // Get video boundaries from FileStatusModel
            double videoStartOffset = videoDurations.getOrDefault(targetVideo, 0.0);
            FileStatusModel.VideoMetaInfo metaInfo = metaMap.get(targetVideo.getName());
            if (metaInfo == null) {
                log.warn("No metadata found for video: {}", targetVideo.getName());
                continue;
            }
            double videoDuration = metaInfo.getDurationSecond();
            double videoEndOffset = videoStartOffset + videoDuration;

            // Check if extended segment crosses video boundaries
            if (extendedStart < videoStartOffset || extendedEnd > videoEndOffset) {
                log.debug("Peak at {}-{} crosses video boundaries, skipping", originalStart, originalEnd);
                continue;
            }

            // Create extended bucket
            DanmakuTimeBucket extendedBucket = new DanmakuTimeBucket();
            extendedBucket.setStartTime(extendedStart);
            extendedBucket.setEndTime(extendedEnd);
            extendedBucket.setEmotionScore(peak.getEmotionScore());
            extendedBucket.setCount(peak.getCount());
            extendedBucket.setDanmakus(peak.getDanmakus());

            extendedBuckets.add(extendedBucket);
        }

        return extendedBuckets;
    }

    /**
     * Find the video file that contains the given time
     */
    private File findVideoForTime(int time, List<Map.Entry<File, Double>> videoEntries,
                                  Map<String, FileStatusModel.VideoMetaInfo> metaMap) {
        for (int i = 0; i < videoEntries.size(); i++) {
            Map.Entry<File, Double> entry = videoEntries.get(i);
            double videoStart = entry.getValue();

            double videoEnd;
            if (i + 1 < videoEntries.size()) {
                videoEnd = videoEntries.get(i + 1).getValue();
            } else {
                // Last video - get duration from FileStatusModel
                FileStatusModel.VideoMetaInfo metaInfo = metaMap.get(entry.getKey().getName());
                if (metaInfo != null) {
                    videoEnd = videoStart + metaInfo.getDurationSecond();
                } else {
                    log.warn("No metadata found for last video: {}", entry.getKey().getName());
                    continue;
                }
            }

            if (time >= videoStart && time < videoEnd) {
                return entry.getKey();
            }
        }
        return null;
    }
}
