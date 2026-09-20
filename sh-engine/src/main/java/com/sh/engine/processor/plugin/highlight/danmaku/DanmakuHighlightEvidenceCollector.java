package com.sh.engine.processor.plugin.highlight.danmaku;

import com.sh.engine.model.highlight.core.InMemoryVideoFrame;
import com.sh.engine.model.highlight.core.OcrTextDetection;
import com.sh.engine.model.danmaku.OcrFrameEvidence;
import com.sh.engine.model.danmaku.VisualFrameEvidence;
import com.sh.engine.processor.plugin.highlight.FfmpegFrameExtractor;
import com.sh.engine.processor.plugin.highlight.HighlightOcrClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 对弹幕候选区间均匀抽帧并执行全屏 OCR，为大模型提供可追溯的游戏事实文本。
 */
@Component
@Slf4j
public class DanmakuHighlightEvidenceCollector {
    private static final int MINIMUM_SAMPLE_INTERVAL_SECONDS = 5;
    private static final int MAXIMUM_SAMPLE_COUNT = 24;
    private static final int MAXIMUM_TEXT_COUNT_PER_FRAME = 12;
    private static final float MINIMUM_OCR_SCORE = 0.55f;
    private static final String FULL_FRAME_FILTER = "scale=1280:-2";

    @Resource
    private FfmpegFrameExtractor frameExtractor;
    @Resource
    private HighlightOcrClient ocrClient;

    /**
     * 采集候选区间内的 OCR 证据。单帧失败不会中断其他时间点的采集。
     *
     * @param sourceVideo 源视频
     * @param startSecond 文件内开始秒
     * @param endSecond 文件内结束秒
     * @return 按时间排序的非空 OCR 证据
     */
    public List<OcrFrameEvidence> collect(
            File sourceVideo, int startSecond, int endSecond) {
        if (sourceVideo == null || !sourceVideo.isFile()
                || startSecond < 0 || endSecond <= startSecond) {
            throw new IllegalArgumentException("invalid danmaku OCR evidence range");
        }

        List<OcrFrameEvidence> evidence = new ArrayList<>();
        List<Integer> failedTimestamps = new ArrayList<>();
        int successfulRequestCount = 0;
        for (Integer timestamp : sampleTimestamps(startSecond, endSecond)) {
            try {
                OcrFrameEvidence frameEvidence = collectFrame(sourceVideo, timestamp);
                successfulRequestCount++;
                if (frameEvidence != null) {
                    evidence.add(frameEvidence);
                }
            } catch (RuntimeException e) {
                failedTimestamps.add(timestamp);
                log.debug("OCR request failed, video: {}, second: {}",
                        sourceVideo.getAbsolutePath(), timestamp, e);
            }
        }
        if (successfulRequestCount == 0) {
            throw new IllegalStateException(
                    "all OCR requests failed, timestamps: " + failedTimestamps);
        }
        if (!failedTimestamps.isEmpty()) {
            log.warn("OCR evidence collected with failed timestamps, video: {}, failed: {}",
                    sourceVideo.getAbsolutePath(), failedTimestamps);
        }
        return evidence;
    }

    /**
     * 对已经保存的视觉送审帧执行 OCR，确保文字与视觉模型看到的是同一批画面。
     * 全部 OCR 请求失败时抛出异常；请求成功但无文字是合法结果。
     */
    public List<OcrFrameEvidence> collect(
            File sourceVideo, List<VisualFrameEvidence> visualFrames) {
        if (sourceVideo == null || visualFrames == null || visualFrames.isEmpty()) {
            throw new IllegalArgumentException("invalid visual OCR evidence");
        }
        List<OcrFrameEvidence> evidence = new ArrayList<>();
        List<Integer> failedTimestamps = new ArrayList<>();
        int successfulRequestCount = 0;
        for (VisualFrameEvidence frame : visualFrames) {
            try {
                List<OcrTextDetection> detections = ocrClient.recognize(
                        frame.getJpegData(), sourceVideo.getName()
                                + "-" + frame.getTimestampSeconds() + ".jpg");
                successfulRequestCount++;
                List<OcrTextDetection> selected = selectDetections(detections);
                if (!selected.isEmpty()) {
                    evidence.add(new OcrFrameEvidence(frame.getTimestampSeconds(), selected));
                }
            } catch (RuntimeException e) {
                failedTimestamps.add(frame.getTimestampSeconds());
                log.debug("OCR request failed, video: {}, second: {}",
                        sourceVideo.getAbsolutePath(), frame.getTimestampSeconds(), e);
            }
        }
        if (successfulRequestCount == 0) {
            throw new IllegalStateException(
                    "all OCR requests failed, timestamps: " + failedTimestamps);
        }
        if (!failedTimestamps.isEmpty()) {
            log.warn("OCR evidence collected with failed timestamps, video: {}, failed: {}",
                    sourceVideo.getAbsolutePath(), failedTimestamps);
        }
        return evidence;
    }

    private List<Integer> sampleTimestamps(int startSecond, int endSecond) {
        int duration = endSecond - startSecond;
        int interval = Math.max(MINIMUM_SAMPLE_INTERVAL_SECONDS,
                (int) Math.ceil(duration / (double) (MAXIMUM_SAMPLE_COUNT - 1)));
        List<Integer> timestamps = new ArrayList<>();
        for (int second = startSecond;
             second < endSecond && timestamps.size() < MAXIMUM_SAMPLE_COUNT;
             second += interval) {
            timestamps.add(second);
        }
        int lastSecond = endSecond - 1;
        if (timestamps.isEmpty() || timestamps.get(timestamps.size() - 1) != lastSecond) {
            if (timestamps.size() == MAXIMUM_SAMPLE_COUNT) {
                timestamps.set(timestamps.size() - 1, lastSecond);
            } else {
                timestamps.add(lastSecond);
            }
        }
        return timestamps;
    }

    private OcrFrameEvidence collectFrame(File sourceVideo, int timestamp) {
        InMemoryVideoFrame frame = frameExtractor.extract(
                sourceVideo, timestamp, FULL_FRAME_FILTER);
        List<OcrTextDetection> detections = ocrClient.recognize(
                frame.getJpegData(), sourceVideo.getName() + "-" + timestamp + ".jpg");
        List<OcrTextDetection> selected = selectDetections(detections);
        if (selected.isEmpty()) {
            return null;
        }
        return new OcrFrameEvidence(frame.getTimestampSeconds(), selected);
    }

    private List<OcrTextDetection> selectDetections(List<OcrTextDetection> detections) {
        if (detections == null || detections.isEmpty()) {
            return Collections.emptyList();
        }
        List<OcrTextDetection> sorted = new ArrayList<>(detections);
        sorted.sort(Comparator.comparingDouble(OcrTextDetection::getScore).reversed());

        List<OcrTextDetection> selected = new ArrayList<>();
        Set<String> seenTexts = new HashSet<>();
        for (OcrTextDetection detection : sorted) {
            String text = StringUtils.trimToEmpty(detection.getText());
            if (detection.getScore() < MINIMUM_OCR_SCORE
                    || text.isEmpty() || !seenTexts.add(text)) {
                continue;
            }
            selected.add(detection);
            if (selected.size() >= MAXIMUM_TEXT_COUNT_PER_FRAME) {
                break;
            }
        }
        return selected;
    }

}
