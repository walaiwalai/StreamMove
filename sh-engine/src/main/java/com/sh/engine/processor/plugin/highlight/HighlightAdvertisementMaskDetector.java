package com.sh.engine.processor.plugin.highlight;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.highlight.core.HighlightMaskPlan;
import com.sh.engine.model.highlight.core.InMemoryVideoFrame;
import com.sh.engine.model.highlight.core.OcrTextDetection;
import com.sh.engine.model.highlight.VideoInterval;
import com.sh.engine.service.LlmService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对高光源视频执行少量全屏 OCR 和大模型广告分类，生成整场直播复用的蒙层计划。
 */
@Component
@Slf4j
public class HighlightAdvertisementMaskDetector {
    private static final String FULL_FRAME_FILTER = "null";
    private static final int SAMPLE_COUNT = 3;
    private static final int MAX_OCR_CANDIDATES_PER_FRAME = 120;
    private static final float MINIMUM_OCR_SCORE = 0.55f;
    private static final double MINIMUM_LLM_CONFIDENCE = 0.75;

    @Resource
    private FfmpegFrameExtractor frameExtractor;
    @Resource
    private HighlightOcrClient ocrClient;
    @Resource
    private LlmService llmService;
    @Resource
    private AdvertisementRegionResolver regionResolver;

    /**
     * 从已选高光区间抽取代表帧并识别广告区域。任何外部识别故障都会降级为空计划，
     * 保持原有高光生成链路可用。
     *
     * @param intervals     已按时间排序的 TopN 高光区间
     * @param workDirectory 当前高光插件的工作目录
     * @return 整场直播共用的广告蒙层计划
     */
    public HighlightMaskPlan detect(List<? extends VideoInterval> intervals, File workDirectory) {
        if (intervals == null || intervals.isEmpty()) {
            return HighlightMaskPlan.empty();
        }
        try {
            List<FrameAnalysis> frames = analyzeFrames(intervals);
            Map<String, OcrCandidate> candidates = indexCandidates(frames);
            if (candidates.isEmpty()) {
                log.info("no OCR text found for highlight advertisement mask, path: {}",
                        workDirectory.getAbsolutePath());
                return HighlightMaskPlan.empty();
            }
            Set<String> advertisementIds = classifyAdvertisements(candidates);
            HighlightMaskPlan plan = resolveMaskPlan(frames, candidates, advertisementIds);
            log.info("highlight advertisement mask resolved, path: {}, masks: {}",
                    workDirectory.getAbsolutePath(), JSON.toJSONString(plan.getMasks()));
            return plan;
        } catch (RuntimeException e) {
            log.warn("highlight advertisement detection failed, continue without mask, work path: {}",
                    workDirectory.getAbsolutePath(), e);
            return HighlightMaskPlan.empty();
        }
    }

    /**
     * 按时间跨度选择代表帧；区间数不足时，同一区间内均匀取多个时间点。
     */
    private List<FrameAnalysis> analyzeFrames(List<? extends VideoInterval> intervals) {
        List<FrameSample> samples = selectSamples(intervals);
        List<FrameAnalysis> frames = new ArrayList<>();
        for (int index = 0; index < samples.size(); index++) {
            FrameSample sample = samples.get(index);
            InMemoryVideoFrame frame = frameExtractor.extract(
                    sample.sourceVideo, sample.timestampSeconds, FULL_FRAME_FILTER);
            byte[] jpegData = frame.getJpegData();
            BufferedImage image = readImage(jpegData, sample.sourceVideo, sample.timestampSeconds);
            List<OcrCandidate> candidates = toCandidates(
                    index + 1, image, ocrClient.recognize(
                            jpegData, frameName(sample.sourceVideo, sample.timestampSeconds)));
            frames.add(new FrameAnalysis(index + 1, image, candidates));
        }
        return frames;
    }

    private List<FrameSample> selectSamples(List<? extends VideoInterval> intervals) {
        int count = SAMPLE_COUNT;
        List<Integer> intervalIndexes = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int intervalIndex = count == 1
                    ? intervals.size() / 2
                    : (int) Math.round(index * (intervals.size() - 1.0) / (count - 1.0));
            intervalIndexes.add(intervalIndex);
        }

        Map<Integer, Integer> occurrences = new HashMap<>();
        for (Integer intervalIndex : intervalIndexes) {
            occurrences.put(intervalIndex, occurrences.getOrDefault(intervalIndex, 0) + 1);
        }
        Map<Integer, Integer> ordinals = new HashMap<>();
        List<FrameSample> samples = new ArrayList<>();
        for (Integer intervalIndex : intervalIndexes) {
            VideoInterval interval = intervals.get(intervalIndex);
            int ordinal = ordinals.getOrDefault(intervalIndex, 0) + 1;
            ordinals.put(intervalIndex, ordinal);
            double fraction = ordinal / (occurrences.get(intervalIndex) + 1.0);
            double timestamp = interval.getSecondFromVideoStart()
                    + (interval.getSecondToVideoEnd() - interval.getSecondFromVideoStart()) * fraction;
            samples.add(new FrameSample(interval.getFromVideo(), Math.max(0, (int) Math.round(timestamp))));
        }
        return samples;
    }

    /**
     * 解码内存帧并把不支持的图片格式统一转换为高光分析异常。
     */
    private BufferedImage readImage(byte[] jpegData,
                                    File sourceVideo,
                                    int timestampSeconds) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpegData));
            if (image == null) {
                throw new StreamerRecordException(
                        ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                        "unsupported highlight frame at " + timestampSeconds
                                + "s from " + sourceVideo.getAbsolutePath());
            }
            return image;
        } catch (IOException e) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "cannot read highlight frame at " + timestampSeconds
                            + "s from " + sourceVideo.getAbsolutePath(), e);
        }
    }

    private String frameName(File sourceVideo, int timestampSeconds) {
        return String.format("%s@%06d-advertisement.jpg", sourceVideo.getName(), timestampSeconds);
    }

    /**
     * 将 OCR 多边形转换为本地可信矩形；大模型只接触候选编号和文字，不负责坐标生成。
     */
    private List<OcrCandidate> toCandidates(int frameIndex,
                                            BufferedImage image,
                                            List<OcrTextDetection> detections) {
        if (detections == null || detections.isEmpty()) {
            return Collections.emptyList();
        }
        List<OcrCandidate> candidates = new ArrayList<>();
        for (OcrTextDetection detection : detections) {
            if (candidates.size() >= MAX_OCR_CANDIDATES_PER_FRAME) {
                break;
            }
            Rectangle bounds = toBounds(detection.getBoxes(), image.getWidth(), image.getHeight());
            if (detection.getScore() < MINIMUM_OCR_SCORE
                    || StringUtils.isBlank(detection.getText()) || bounds == null) {
                continue;
            }
            String candidateId = String.format("F%02d-C%03d", frameIndex, candidates.size() + 1);
            candidates.add(new OcrCandidate(
                    candidateId, frameIndex, detection.getText().trim(), detection.getScore(), bounds,
                    image.getWidth(), image.getHeight()));
        }
        return candidates;
    }

    /**
     * 将 OCR 八点多边形裁剪为位于源画面内的最小外接矩形。
     */
    private Rectangle toBounds(List<Integer> polygon, int frameWidth, int frameHeight) {
        if (polygon == null || polygon.size() < 8 || polygon.size() % 2 != 0) {
            return null;
        }
        int minimumX = frameWidth;
        int minimumY = frameHeight;
        int maximumX = 0;
        int maximumY = 0;
        for (int index = 0; index < polygon.size(); index += 2) {
            minimumX = Math.min(minimumX, polygon.get(index));
            maximumX = Math.max(maximumX, polygon.get(index));
            minimumY = Math.min(minimumY, polygon.get(index + 1));
            maximumY = Math.max(maximumY, polygon.get(index + 1));
        }
        minimumX = Math.max(0, Math.min(minimumX, frameWidth - 1));
        minimumY = Math.max(0, Math.min(minimumY, frameHeight - 1));
        maximumX = Math.max(minimumX + 1, Math.min(maximumX, frameWidth));
        maximumY = Math.max(minimumY + 1, Math.min(maximumY, frameHeight));
        return new Rectangle(minimumX, minimumY, maximumX - minimumX, maximumY - minimumY);
    }

    /**
     * 按不可伪造的本地候选编号建立索引，供校验 LLM 返回值使用。
     */
    private Map<String, OcrCandidate> indexCandidates(List<FrameAnalysis> frames) {
        Map<String, OcrCandidate> candidates = new LinkedHashMap<>();
        for (FrameAnalysis frame : frames) {
            for (OcrCandidate candidate : frame.candidates) {
                candidates.put(candidate.id, candidate);
            }
        }
        return candidates;
    }

    /**
     * 让大模型仅返回广告候选编号；无法解析或低置信度判断不会进入蒙层范围。
     */
    private Set<String> classifyAdvertisements(Map<String, OcrCandidate> candidates) {
        AdvertisementClassificationResult result = llmService.chat(
                buildPrompt(candidates), AdvertisementClassificationResult.class);
        Set<String> advertisementIds = new LinkedHashSet<>();
        if (result == null || result.getAdvertisements() == null) {
            return advertisementIds;
        }
        for (AdvertisementDecision decision : result.getAdvertisements()) {
            if (decision != null && candidates.containsKey(decision.getCandidateId())
                    && decision.getConfidence() >= MINIMUM_LLM_CONFIDENCE) {
                advertisementIds.add(decision.getCandidateId());
            }
        }
        return advertisementIds;
    }

    /**
     * 生成只允许返回候选编号的结构化广告审核提示词。
     */
    private String buildPrompt(Map<String, OcrCandidate> candidates) {
        JSONArray payload = new JSONArray();
        for (OcrCandidate candidate : candidates.values()) {
            JSONObject item = new JSONObject(true);
            item.put("candidateId", candidate.id);
            item.put("frameIndex", candidate.frameIndex);
            item.put("text", candidate.text);
            item.put("ocrScore", candidate.score);
            item.put("x", candidate.bounds.getX() / candidate.frameWidth);
            item.put("y", candidate.bounds.getY() / candidate.frameHeight);
            item.put("width", candidate.bounds.getWidth() / candidate.frameWidth);
            item.put("height", candidate.bounds.getHeight() / candidate.frameHeight);
            payload.add(item);
        }
        return "你是直播录像广告文字审核器。请从 OCR 候选项中识别主播植入、赞助商、商品、店铺、"
                + "购买引导、优惠或商业推广文字。游戏自带 HUD、角色名、比分、击杀信息、网络参数、"
                + "直播平台水印、主播名和普通字幕不是广告。相邻位置和多帧重复内容可以结合判断。"
                + "只允许引用输入中的 candidateId，禁止生成坐标。只返回 JSON 对象，格式为："
                + "{\"advertisements\":[{\"candidateId\":\"F01-C001\","
                + "\"confidence\":0.95,\"reason\":\"商品型号推广\"}]}。"
                + "没有广告时 advertisements 返回空数组。OCR 候选项：" + payload.toJSONString();
    }

    /**
     * 按帧聚合被选中的 OCR 框，再跨帧合并为整场直播的固定蒙层。
     */
    private HighlightMaskPlan resolveMaskPlan(List<FrameAnalysis> frames,
                                              Map<String, OcrCandidate> candidates,
                                              Set<String> advertisementIds) {
        if (advertisementIds.isEmpty()) {
            return HighlightMaskPlan.empty();
        }
        Map<Integer, List<Rectangle>> boxesByFrame = new HashMap<>();
        for (String advertisementId : advertisementIds) {
            OcrCandidate candidate = candidates.get(advertisementId);
            boxesByFrame.computeIfAbsent(candidate.frameIndex, key -> new ArrayList<>())
                    .add(candidate.bounds);
        }
        List<Rectangle> regions = new ArrayList<>();
        for (FrameAnalysis frame : frames) {
            List<Rectangle> boxes = boxesByFrame.get(frame.frameIndex);
            if (boxes != null && !boxes.isEmpty()) {
                regions.addAll(regionResolver.resolveFrame(frame.image, boxes));
            }
        }
        BufferedImage firstFrame = frames.get(0).image;
        return regionResolver.combineFrames(
                regions, firstFrame.getWidth(), firstFrame.getHeight());
    }

    /** 大模型广告分类响应，仅属于当前适配器的结构化协议。 */
    @Data
    public static class AdvertisementClassificationResult {
        private List<AdvertisementDecision> advertisements = Collections.emptyList();
    }

    /** 单个 OCR 候选项的广告判断；坐标始终以本地 OCR 为准。 */
    @Data
    public static class AdvertisementDecision {
        private String candidateId;
        private double confidence;
        private String reason;
    }

    private static final class FrameSample {
        private final File sourceVideo;
        private final int timestampSeconds;

        private FrameSample(File sourceVideo, int timestampSeconds) {
            this.sourceVideo = sourceVideo;
            this.timestampSeconds = timestampSeconds;
        }
    }

    private static final class FrameAnalysis {
        private final int frameIndex;
        private final BufferedImage image;
        private final List<OcrCandidate> candidates;

        private FrameAnalysis(int frameIndex,
                              BufferedImage image,
                              List<OcrCandidate> candidates) {
            this.frameIndex = frameIndex;
            this.image = image;
            this.candidates = candidates;
        }
    }

    private static final class OcrCandidate {
        private final String id;
        private final int frameIndex;
        private final String text;
        private final float score;
        private final Rectangle bounds;
        private final int frameWidth;
        private final int frameHeight;

        private OcrCandidate(String id,
                             int frameIndex,
                             String text,
                             float score,
                             Rectangle bounds,
                             int frameWidth,
                             int frameHeight) {
            this.id = id;
            this.frameIndex = frameIndex;
            this.text = text;
            this.score = score;
            this.bounds = bounds;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
        }
    }
}
