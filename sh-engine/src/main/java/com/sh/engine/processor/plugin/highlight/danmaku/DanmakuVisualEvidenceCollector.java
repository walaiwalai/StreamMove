package com.sh.engine.processor.plugin.highlight.danmaku;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.danmaku.VisualFrameEvidence;
import com.sh.engine.model.highlight.core.InMemoryVideoFrame;
import com.sh.engine.processor.plugin.highlight.FfmpegFrameExtractor;
import com.sh.engine.processor.plugin.highlight.VisualChangeTimestampSelector;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 对候选全局和弹幕信号窗口分层抽帧，并保存模型实际看到的证据图片。
 */
@Component
@Slf4j
public class DanmakuVisualEvidenceCollector {
    private static final int OVERVIEW_FRAME_COUNT = 8;
    private static final int SIGNAL_FRAME_COUNT = 8;
    private static final int DENSE_FRAME_COUNT = 48;
    private static final int MINIMUM_SUCCESSFUL_FRAME_COUNT = 8;
    private static final int MINIMUM_DENSE_FRAME_COUNT = 36;
    private static final String FRAME_FILTER = "scale=768:-2";

    @Resource
    private FfmpegFrameExtractor frameExtractor;
    @Resource
    private VisualChangeTimestampSelector changeTimestampSelector;

    /**
     * 采集并落盘视觉证据。少量不可解码时间点允许跳过，但有效帧不足会明确失败。
     */
    public List<VisualFrameEvidence> collect(
            File sourceVideo,
            int candidateStart,
            int candidateEnd,
            int signalStart,
            int signalEnd,
            File evidenceDirectory) {
        validate(sourceVideo, candidateStart, candidateEnd,
                signalStart, signalEnd, evidenceDirectory);
        createDirectory(evidenceDirectory.toPath());
        Set<Integer> timestamps = sampleTimestamps(
                candidateStart, candidateEnd, signalStart, signalEnd);
        return collectAtTimestamps(sourceVideo, evidenceDirectory,
                timestamps, MINIMUM_SUCCESSFUL_FRAME_COUNT);
    }

    /**
     * 在已定位的复核窗口中密集抽帧，缩短相邻图片间隔以验证短时变化。
     */
    public List<VisualFrameEvidence> collectDense(
            File sourceVideo,
            int reviewStart,
            int reviewEnd,
            File evidenceDirectory) {
        validate(sourceVideo, reviewStart, reviewEnd,
                reviewStart, reviewEnd, evidenceDirectory);
        createDirectory(evidenceDirectory.toPath());
        Set<Integer> timestamps = new TreeSet<>();
        addEvenlySpaced(timestamps, reviewStart, reviewEnd, DENSE_FRAME_COUNT);
        log.info("Collecting dense visual evidence, video: {}, range: {}-{}s, frames: {}",
                sourceVideo.getName(), reviewStart, reviewEnd, timestamps.size());
        return collectAtTimestamps(sourceVideo, evidenceDirectory,
                timestamps, MINIMUM_DENSE_FRAME_COUNT);
    }

    /**
     * 在代码已确定的最终精剪内重新采样，兼顾完整时间线与动作变化点。
     */
    public List<VisualFrameEvidence> collectPublication(
            File sourceVideo,
            int clipStart,
            int clipEnd,
            File evidenceDirectory) {
        validate(sourceVideo, clipStart, clipEnd,
                clipStart, clipEnd, evidenceDirectory);
        createDirectory(evidenceDirectory.toPath());
        Set<Integer> timestamps = changeTimestampSelector.select(
                sourceVideo, clipStart, clipEnd, DENSE_FRAME_COUNT);
        log.info("Collecting publication visual evidence, video: {}, "
                        + "range: {}-{}s, frames: {}",
                sourceVideo.getName(), clipStart, clipEnd, timestamps.size());
        return collectAtTimestamps(sourceVideo, evidenceDirectory,
                timestamps, MINIMUM_DENSE_FRAME_COUNT);
    }

    private List<VisualFrameEvidence> collectAtTimestamps(
            File sourceVideo,
            File evidenceDirectory,
            Set<Integer> timestamps,
            int minimumSuccessfulCount) {
        List<VisualFrameEvidence> frames = new ArrayList<>();
        List<Integer> failedTimestamps = new ArrayList<>();
        for (Integer timestamp : timestamps) {
            try {
                frames.add(extractAndSave(
                        sourceVideo, timestamp, evidenceDirectory, frames.size() + 1));
            } catch (RuntimeException e) {
                failedTimestamps.add(timestamp);
                log.debug("visual frame extraction failed, video: {}, second: {}",
                        sourceVideo.getAbsolutePath(), timestamp, e);
            }
        }
        ensureEnoughFrames(sourceVideo, timestamps.size(), minimumSuccessfulCount,
                frames, failedTimestamps);
        return Collections.unmodifiableList(frames);
    }

    private void validate(
            File sourceVideo,
            int candidateStart,
            int candidateEnd,
            int signalStart,
            int signalEnd,
            File evidenceDirectory) {
        if (sourceVideo == null || !sourceVideo.isFile() || evidenceDirectory == null
                || candidateStart < 0 || candidateEnd <= candidateStart
                || signalStart < candidateStart || signalEnd > candidateEnd
                || signalEnd <= signalStart) {
            throw new IllegalArgumentException("invalid visual evidence range");
        }
    }

    private Set<Integer> sampleTimestamps(
            int candidateStart, int candidateEnd, int signalStart, int signalEnd) {
        Set<Integer> timestamps = new TreeSet<>();
        addEvenlySpaced(timestamps, candidateStart, candidateEnd, OVERVIEW_FRAME_COUNT);
        addEvenlySpaced(timestamps, signalStart, signalEnd, SIGNAL_FRAME_COUNT);
        return timestamps;
    }

    private void addEvenlySpaced(
            Set<Integer> timestamps, int start, int end, int sampleCount) {
        int lastSecond = end - 1;
        if (sampleCount <= 1 || lastSecond <= start) {
            timestamps.add(start);
            return;
        }
        for (int index = 0; index < sampleCount; index++) {
            double ratio = index / (double) (sampleCount - 1);
            timestamps.add(start + (int) Math.round((lastSecond - start) * ratio));
        }
    }

    private VisualFrameEvidence extractAndSave(
            File sourceVideo, int timestamp, File directory, int frameIndex) {
        InMemoryVideoFrame frame = frameExtractor.extract(sourceVideo, timestamp, FRAME_FILTER);
        byte[] jpegData = frame.getJpegData();
        String fileName = String.format("frame-%02d-%06ds.jpg",
                frameIndex, frame.getTimestampSeconds());
        Path path = directory.toPath().resolve(fileName);
        try {
            Files.write(path, jpegData);
        } catch (IOException e) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "cannot save visual evidence frame: " + path, e);
        }
        return new VisualFrameEvidence(
                frame.getTimestampSeconds(), path.toAbsolutePath().toString(),
                sha256(jpegData), jpegData);
    }

    private String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR, "SHA-256 is unavailable", e);
        }
    }

    private void ensureEnoughFrames(
            File sourceVideo,
            int requestedCount,
            int requestedMinimumCount,
            List<VisualFrameEvidence> frames,
            List<Integer> failedTimestamps) {
        int minimumCount = Math.min(requestedMinimumCount, requestedCount);
        if (frames.size() < minimumCount) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "insufficient visual evidence frames for " + sourceVideo.getAbsolutePath()
                            + ", expected at least " + minimumCount
                            + ", actual " + frames.size()
                            + ", failed timestamps " + failedTimestamps);
        }
        if (!failedTimestamps.isEmpty()) {
            log.warn("visual evidence collected with missing timestamps, video: {}, missing: {}",
                    sourceVideo.getAbsolutePath(), failedTimestamps);
        }
    }

    private void createDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "cannot create visual evidence directory: " + directory, e);
        }
    }

    /** 生成不包含用户原始文件名特殊字符的证据目录名。 */
    public String buildSegmentDirectoryName(File sourceVideo, int start, int end) {
        String baseName = FilenameUtils.getBaseName(sourceVideo.getName())
                .replaceAll("[^A-Za-z0-9_-]", "_");
        return baseName + "-" + start + "-" + end;
    }
}
