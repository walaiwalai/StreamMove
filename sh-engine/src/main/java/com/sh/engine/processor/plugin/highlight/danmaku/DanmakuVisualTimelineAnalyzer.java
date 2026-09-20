package com.sh.engine.processor.plugin.highlight.danmaku;

import com.alibaba.fastjson.JSONObject;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.danmaku.VisualEvidenceBatch;
import com.sh.engine.model.danmaku.VisualObservation;
import com.sh.engine.model.danmaku.VisualTimelineResult;
import com.sh.engine.model.llm.LlmCallResult;
import com.sh.engine.model.llm.LlmResponseException;
import com.sh.engine.service.LlmService;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 生成并校验逐帧视觉时间线；协议异常只允许一次基于原图的有界定点补采。
 */
@Component
public class DanmakuVisualTimelineAnalyzer {
    private static final int MAXIMUM_FRAMES_PER_REQUEST = 12;
    private static final int MAXIMUM_REPAIR_FRAMES = 8;

    @Resource
    private LlmService llmService;
    @Resource
    private HighlightAnalysisCache analysisCache;
    @Resource
    private HighlightAnalysisAuditRepository auditRepository;

    public VisualTimelineResult analyze(
            String streamerName,
            String cacheKey,
            File recordDirectory,
            VisualEvidenceBatch batch,
            String requestStage) {
        VisualTimelineResult timeline = analysisCache.getVisualTimeline(streamerName, cacheKey);
        if (timeline != null) {
            batch.attachTrustedTimestamps(timeline);
            validate(batch, timeline);
            auditRepository.append(recordDirectory, requestStage + "-cache", cacheKey,
                    buildPrompt(batch), null, timeline, batch);
            return timeline;
        }

        List<VisualEvidenceBatch> batches = batch.partition(MAXIMUM_FRAMES_PER_REQUEST);
        List<VisualTimelineResult> timelines = new ArrayList<>();
        for (int index = 0; index < batches.size(); index++) {
            String suffix = String.format(
                    "batch-%02d-of-%02d", index + 1, batches.size());
            VisualBatchTask task = new VisualBatchTask(
                    streamerName, recordDirectory, requestStage + "-" + suffix,
                    cacheKey + "-" + suffix, batches.get(index));
            timelines.add(analyzeBatch(task));
        }
        timeline = mergeTimelines(timelines);
        validate(batch, timeline);
        auditRepository.append(recordDirectory, requestStage, cacheKey,
                buildBatchSummary(batches.size()), null, timeline, batch);
        analysisCache.saveVisualTimeline(streamerName, cacheKey, timeline);
        return timeline;
    }

    /** 分析一个有界视觉小批；映射错误和空事实的恢复都限制在本批内。 */
    private VisualTimelineResult analyzeBatch(VisualBatchTask task) {
        String prompt = buildPrompt(task.batch);
        VisualTimelineResult timeline = analysisCache.getVisualTimeline(
                task.streamerName, task.cacheKey);
        if (timeline == null) {
            timeline = requestTimeline(task, prompt);
        } else {
            auditRepository.append(task.recordDirectory, task.stage + "-cache",
                    task.cacheKey, prompt, null, timeline, task.batch);
        }
        discardUnmappedObservations(task, prompt, timeline);
        if (!task.batch.hasExactFrameMapping(timeline)) {
            timeline = retryInvalidMapping(task, prompt, timeline);
        }
        repairIncompleteObservations(task, timeline);
        task.batch.attachTrustedTimestamps(timeline);
        validate(task.batch, timeline);
        analysisCache.saveVisualTimeline(
                task.streamerName, task.cacheKey, timeline);
        return timeline;
    }

    /** 调用视觉模型并在同一外部边界记录成功原文或失败信封。 */
    private VisualTimelineResult requestTimeline(
            VisualBatchTask task, String prompt) {
        LlmCallResult<VisualTimelineResult> call;
        try {
            call = llmService.analyzeImagesWithRaw(
                    prompt, task.batch.toLlmInputs(), VisualTimelineResult.class);
        } catch (RuntimeException e) {
            JSONObject failure = new JSONObject(true);
            failure.put("exception", e.getClass().getName());
            failure.put("message", e.getMessage());
            String rawEnvelope = e instanceof LlmResponseException
                    ? ((LlmResponseException) e).getRawEnvelope() : null;
            auditRepository.append(task.recordDirectory, task.stage + "-error",
                    task.cacheKey, prompt, rawEnvelope, failure, task.batch);
            throw e;
        }
        VisualTimelineResult timeline = requireResult(call.getResult(), "vision");
        auditRepository.append(task.recordDirectory, task.stage + "-response",
                task.cacheKey, prompt, call.getRawResponse(), timeline, task.batch);
        return timeline;
    }

    /** 图片序号缺失或重复时，原样重试当前小批一次。 */
    private VisualTimelineResult retryInvalidMapping(
            VisualBatchTask task,
            String prompt,
            VisualTimelineResult invalidTimeline) {
        auditRepository.append(task.recordDirectory, task.stage + "-mapping-invalid",
                task.cacheKey, prompt, null, invalidTimeline, task.batch);
        String retryPrompt = prompt + "\n上一次响应的图片序号不完整或重复。"
                + "本次仍只依据这些原图重新记录，确保每张图片恰好一项。";
        VisualBatchTask retryTask = task.withStageSuffix("-mapping-retry", task.batch);
        VisualTimelineResult retried = requestTimeline(retryTask, retryPrompt);
        discardUnmappedObservations(retryTask, retryPrompt, retried);
        return retried;
    }

    /** 序号完整但事实为空时，只补采对应原图一次。 */
    private void repairIncompleteObservations(
            VisualBatchTask task,
            VisualTimelineResult timeline) {
        List<Integer> incomplete = task.batch.incompleteObservationIndices(timeline);
        if (incomplete.isEmpty()) {
            return;
        }
        if (incomplete.size() > MAXIMUM_REPAIR_FRAMES) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "too many incomplete visual observations: " + incomplete.size());
        }
        VisualEvidenceBatch repairBatch = task.batch.subBatch(incomplete);
        String repairPrompt = buildPrompt(repairBatch)
                + "\n本次仅补采缺失观察，不重判其他图片。仍须逐张输出直接可见事实。";
        VisualBatchTask repairTask = task.withStageSuffix("-repair", repairBatch);
        VisualTimelineResult repaired = requestTimeline(repairTask, repairPrompt);
        discardUnmappedObservations(repairTask, repairPrompt, repaired);
        repairBatch.attachTrustedTimestamps(repaired);
        validate(repairBatch, repaired);
        task.batch.replaceIncompleteObservations(timeline, incomplete, repaired);

        JSONObject applied = new JSONObject(true);
        applied.put("repairedFrameIndices", incomplete);
        applied.put("repairRequestFrames", repairBatch.toAuditMetadata());
        auditRepository.append(
                task.recordDirectory, repairTask.stage + "-applied", task.cacheKey,
                repairPrompt, null, applied, repairBatch);
    }

    /** 只在真实图片完整唯一时丢弃模型额外生成的越界记录。 */
    private void discardUnmappedObservations(
            VisualBatchTask task,
            String prompt,
            VisualTimelineResult timeline) {
        List<Integer> discarded = task.batch.discardUnmappedObservations(timeline);
        if (discarded.isEmpty()) {
            return;
        }
        JSONObject normalization = new JSONObject(true);
        normalization.put("discardedFrameIndices", discarded);
        normalization.put("reason", "all real frames were present exactly once; "
                + "only unmapped model observations were discarded");
        auditRepository.append(task.recordDirectory,
                task.stage + "-normalization", task.cacheKey,
                prompt, null, normalization, task.batch);
    }

    private void validate(
            VisualEvidenceBatch batch, VisualTimelineResult timeline) {
        try {
            batch.validate(timeline);
        } catch (IllegalArgumentException e) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "invalid visual timeline returned by LLM", e);
        }
    }

    private String buildPrompt(VisualEvidenceBatch batch) {
        return "你是中立的直播画面证据记录员。以下图片按时间顺序来自未知题材的视频候选。\n"
                + "图片与源视频时间映射：" + batch.buildFrameIndexText() + "。\n"
                + "逐张记录直接可见的主体、场景、动作、界面状态和结果。只能比较相邻图片中"
                + "确实可见的变化；不得推断两帧之间发生的过程、因果、人物身份或内容类型，"
                + "不得根据常识补全不可见事实。看不清时也要在 observableFacts 明确写‘画面无法辨认’，"
                + "并将原因写入 uncertainties，不得省略字段。\n"
                + "每张图片必须恰好对应一个 observation，frameIndex 必须照抄图片序号；"
                + "timestamp 由本地代码按 frameIndex 回填，不要输出 timestamp。"
                + "observableFacts 控制在 60 个汉字内且不能为空，visibleChange 控制在 40 个汉字内，"
                + "避免重复界面文字。coverCandidate 只有在画面清晰、有明确视觉焦点、能独立代表关键变化，"
                + "且不是加载、菜单、过场、纯结果页、明显模糊或被大面积既有文字遮挡时才为 true。\n"
                + "JSON 结构示例：{\"observations\":[{\"frameIndex\":1,"
                + "\"observableFacts\":\"直接可见事实\",\"visibleChange\":\"与上一帧的可见变化\","
                + "\"certainty\":\"high\",\"coverCandidate\":false}],\"summary\":\"可见事实摘要\","
                + "\"uncertainties\":[\"无法从离散帧确认的内容\"]}。\n"
                + "只输出 JSON，不要 Markdown。字段：observations（对象数组，每项含 frameIndex、"
                + "observableFacts、visibleChange、certainty、coverCandidate）、summary、"
                + "uncertainties（字符串数组）。";
    }

    private VisualTimelineResult mergeTimelines(
            List<VisualTimelineResult> timelines) {
        VisualTimelineResult merged = new VisualTimelineResult();
        List<VisualObservation> observations = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        Set<String> uncertainties = new LinkedHashSet<>();
        int frameOffset = 0;
        for (VisualTimelineResult timeline : timelines) {
            for (VisualObservation observation : timeline.getObservations()) {
                observations.add(copyWithFrameOffset(observation, frameOffset));
            }
            if (timeline.getSummary() != null && !timeline.getSummary().trim().isEmpty()) {
                summaries.add(timeline.getSummary().trim());
            }
            if (timeline.getUncertainties() != null) {
                uncertainties.addAll(timeline.getUncertainties());
            }
            frameOffset += timeline.getObservations().size();
        }
        merged.setObservations(observations);
        merged.setSummary(String.join("；", summaries));
        merged.setUncertainties(new ArrayList<>(uncertainties));
        return merged;
    }

    private VisualObservation copyWithFrameOffset(
            VisualObservation source, int frameOffset) {
        VisualObservation target = new VisualObservation();
        target.setFrameIndex(source.getFrameIndex() + frameOffset);
        target.setTimestamp(source.getTimestamp());
        target.setObservableFacts(source.getObservableFacts());
        target.setVisibleChange(source.getVisibleChange());
        target.setCertainty(source.getCertainty());
        target.setCoverCandidate(source.getCoverCandidate());
        return target;
    }

    private String buildBatchSummary(int batchCount) {
        return "视觉时间线由本地程序合并；模型请求批数=" + batchCount
                + "，每批最多图片数=" + MAXIMUM_FRAMES_PER_REQUEST + "。";
    }

    private <T> T requireResult(T result, String stage) {
        if (result == null) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "LLM returned empty result during " + stage);
        }
        return result;
    }

    /** 单个视觉小批的缓存与审计上下文。 */
    private static final class VisualBatchTask {
        private final String streamerName;
        private final File recordDirectory;
        private final String stage;
        private final String cacheKey;
        private final VisualEvidenceBatch batch;

        private VisualBatchTask(
                String streamerName,
                File recordDirectory,
                String stage,
                String cacheKey,
                VisualEvidenceBatch batch) {
            this.streamerName = streamerName;
            this.recordDirectory = recordDirectory;
            this.stage = stage;
            this.cacheKey = cacheKey;
            this.batch = batch;
        }

        private VisualBatchTask withStageSuffix(
                String suffix, VisualEvidenceBatch replacementBatch) {
            return new VisualBatchTask(streamerName, recordDirectory,
                    stage + suffix, cacheKey, replacementBatch);
        }
    }
}
