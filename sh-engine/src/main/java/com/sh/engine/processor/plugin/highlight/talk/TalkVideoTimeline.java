package com.sh.engine.processor.plugin.highlight.talk;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.ffmpeg.VideoDurationDetectCmd;
import com.sh.engine.model.highlight.VideoInterval;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把同一场直播的多个视频文件组织成连续时间线，并负责全局时间与源文件时间的转换。
 */
final class TalkVideoTimeline {
    private static final Pattern LEADING_INDEX_PATTERN = Pattern.compile("^(\\d+)[-_]");
    private static final List<String> SUPPORTED_EXTENSIONS = Arrays.asList(
            "mp4", "mkv", "mov", "avi", "flv", "webm", "m4v", "ts");
    private static final long DURATION_DETECT_TIMEOUT_SECONDS = 60L;

    private final List<SourceVideo> sourceVideos;
    private final int totalDurationSeconds;

    private TalkVideoTimeline(List<File> videoFiles, Map<File, Double> durations) {
        List<SourceVideo> videos = new ArrayList<>();
        double offsetSeconds = 0.0;
        for (File videoFile : videoFiles) {
            Double durationSeconds = durations.get(videoFile);
            if (durationSeconds == null || durationSeconds <= 0.0) {
                throw new StreamerRecordException(
                        ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                        "invalid video duration: " + videoFile.getAbsolutePath());
            }
            videos.add(new SourceVideo(videoFile, durationSeconds, offsetSeconds));
            offsetSeconds += durationSeconds;
        }
        this.sourceVideos = Collections.unmodifiableList(videos);
        this.totalDurationSeconds = (int) Math.ceil(offsetSeconds);
    }

    /**
     * 扫描目录中的视频并读取时长。带数字前缀的录像按编号排序，普通文件名稳定回退到名称排序。
     */
    static TalkVideoTimeline open(File inputDirectory) {
        if (inputDirectory == null || !inputDirectory.isDirectory()) {
            throw new StreamerRecordException(
                    ErrorEnum.INVALID_PARAM,
                    "talk highlight input directory does not exist: " + inputDirectory);
        }
        File[] listedFiles = inputDirectory.listFiles(TalkVideoTimeline::isSupportedVideo);
        if (listedFiles == null || listedFiles.length == 0) {
            throw new StreamerRecordException(
                    ErrorEnum.INVALID_PARAM,
                    "no supported video found in: " + inputDirectory.getAbsolutePath());
        }

        List<File> videoFiles = new ArrayList<>(Arrays.asList(listedFiles));
        videoFiles.sort(TalkVideoTimeline::compareVideoFile);
        Map<File, Double> durations = new HashMap<>();
        for (File videoFile : videoFiles) {
            VideoDurationDetectCmd detectCmd = new VideoDurationDetectCmd(videoFile.getAbsolutePath());
            detectCmd.execute(DURATION_DETECT_TIMEOUT_SECONDS);
            durations.put(videoFile, detectCmd.getDurationSeconds());
        }
        return new TalkVideoTimeline(videoFiles, durations);
    }

    /**
     * 将全场时间区间拆成一个或多个源文件内的剪辑区间。
     */
    List<VideoInterval> toVideoIntervals(int globalStartSeconds, int globalEndSeconds) {
        int boundedStart = Math.max(0, globalStartSeconds);
        int boundedEnd = Math.min(totalDurationSeconds, globalEndSeconds);
        if (boundedEnd <= boundedStart) {
            return Collections.emptyList();
        }

        List<VideoInterval> intervals = new ArrayList<>();
        for (SourceVideo sourceVideo : sourceVideos) {
            double overlapStart = Math.max(boundedStart, sourceVideo.getGlobalStartSeconds());
            double overlapEnd = Math.min(boundedEnd, sourceVideo.getGlobalEndSeconds());
            if (overlapEnd <= overlapStart) {
                continue;
            }
            intervals.add(new VideoInterval(
                    sourceVideo.getVideoFile(),
                    overlapStart - sourceVideo.getGlobalStartSeconds(),
                    overlapEnd - sourceVideo.getGlobalStartSeconds()));
        }
        return intervals;
    }

    List<SourceVideo> getSourceVideos() {
        return sourceVideos;
    }

    int getTotalDurationSeconds() {
        return totalDurationSeconds;
    }

    private static boolean isSupportedVideo(File file) {
        if (!file.isFile()) {
            return false;
        }
        String name = file.getName();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == name.length() - 1) {
            return false;
        }
        return SUPPORTED_EXTENSIONS.contains(name.substring(dotIndex + 1).toLowerCase(Locale.ROOT));
    }

    private static int compareVideoFile(File left, File right) {
        Integer leftIndex = leadingIndex(left.getName());
        Integer rightIndex = leadingIndex(right.getName());
        if (leftIndex != null && rightIndex != null) {
            int indexComparison = Integer.compare(leftIndex, rightIndex);
            return indexComparison != 0 ? indexComparison : left.getName().compareToIgnoreCase(right.getName());
        }
        if (leftIndex != null) {
            return -1;
        }
        if (rightIndex != null) {
            return 1;
        }
        return Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER).compare(left, right);
    }

    private static Integer leadingIndex(String fileName) {
        Matcher matcher = LEADING_INDEX_PATTERN.matcher(fileName);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    /**
     * 单个源视频在整场直播时间线中的位置。
     */
    static final class SourceVideo {
        private final File videoFile;
        private final double durationSeconds;
        private final double globalStartSeconds;

        private SourceVideo(File videoFile, double durationSeconds, double globalStartSeconds) {
            this.videoFile = videoFile;
            this.durationSeconds = durationSeconds;
            this.globalStartSeconds = globalStartSeconds;
        }

        File getVideoFile() {
            return videoFile;
        }

        double getDurationSeconds() {
            return durationSeconds;
        }

        double getGlobalStartSeconds() {
            return globalStartSeconds;
        }

        double getGlobalEndSeconds() {
            return globalStartSeconds + durationSeconds;
        }

        int toGlobalSecond(int sourceSecond) {
            return (int) Math.round(globalStartSeconds + sourceSecond);
        }
    }
}
