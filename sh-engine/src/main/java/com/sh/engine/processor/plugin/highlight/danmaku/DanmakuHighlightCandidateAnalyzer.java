package com.sh.engine.processor.plugin.highlight.danmaku;

import com.alibaba.fastjson.JSONObject;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.model.danmaku.ConfirmedHighlight;
import com.sh.engine.model.danmaku.DanmakuTimeBucket;
import com.sh.engine.model.danmaku.HighlightAnalysisResult;
import com.sh.engine.model.danmaku.HighlightClipRange;
import com.sh.engine.model.danmaku.HighlightDenseReviewContext;
import com.sh.engine.model.danmaku.HighlightEvidenceCatalog;
import com.sh.engine.model.danmaku.HighlightReviewContext;
import com.sh.engine.model.danmaku.HighlightReviewPlan;
import com.sh.engine.model.danmaku.OcrFrameEvidence;
import com.sh.engine.model.danmaku.VisualEvidenceBatch;
import com.sh.engine.model.danmaku.VisualFrameEvidence;
import com.sh.engine.model.danmaku.VisualTimelineResult;
import com.sh.engine.model.llm.LlmCallResult;
import com.sh.engine.model.llm.LlmResponseException;
import com.sh.engine.service.AsrService;
import com.sh.engine.service.LlmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * 对单个弹幕召回候选采集多模态证据、调用模型并执行确定性验真。
 */
@Component
@Slf4j
public class DanmakuHighlightCandidateAnalyzer {
    private static final String ASR_CACHE_VERSION = "story-v3-cover";
    private static final String OCR_CACHE_VERSION = "ocr-v1-text";
    private static final String VISION_CACHE_VERSION = "vision-v1-multiframe-original";
    private static final String DRAFT_CACHE_VERSION = "story-v9-evidence-id";
    private static final String FINAL_REVIEW_VERSION = "final-v1-fact-publication";
    private static final int MINIMUM_DENSE_REVIEW_SCORE = 45;

    @Resource
    private AsrService asrService;
    @Resource
    private LlmService llmService;
    @Resource
    private DanmakuVisualEvidenceCollector visualEvidenceCollector;
    @Resource
    private DanmakuVisualTimelineAnalyzer visualTimelineAnalyzer;
    @Resource
    private DanmakuHighlightEvidenceCollector ocrEvidenceCollector;
    @Resource
    private DanmakuHighlightPromptFactory promptFactory;
    @Resource
    private HighlightReviewWindowPlanner reviewWindowPlanner;
    @Resource
    private DanmakuHighlightDenseReviewer denseReviewer;
    @Resource
    private HighlightAnalysisCache analysisCache;
    @Resource
    private HighlightAnalysisAuditRepository auditRepository;

    /**
     * 分析单个召回候选中的独立局部窗口。依赖或审计失败时向上抛出。
     */
    public List<ConfirmedHighlight> analyze(
            File videoFile,
            DanmakuTimeBucket bucket,
            String streamerName,
            int fileStart,
            int fileEnd,
            int sessionToFileOffset) {
        String segmentKey = videoFile.getName() + "-" + fileStart + "-" + fileEnd
                + "-" + videoFile.length() + "-" + videoFile.lastModified();
        List<AsrSegment> asrSegments = transcribe(
                streamerName, ASR_CACHE_VERSION + "-" + segmentKey,
                videoFile, fileStart, fileEnd);
        VisualEvidenceBatch sparseVisualBatch = collectVisualEvidence(
                videoFile, bucket, fileStart, fileEnd, sessionToFileOffset);
        VisualTimelineResult sparseVisualTimeline = visualTimelineAnalyzer.analyze(
                streamerName, VISION_CACHE_VERSION + "-" + segmentKey,
                videoFile.getParentFile(), sparseVisualBatch, "vision-sparse");
        List<OcrFrameEvidence> sparseOcrEvidence = collectOcr(
                streamerName, OCR_CACHE_VERSION + "-sparse-" + segmentKey,
                videoFile, sparseVisualBatch.getFrames());
        HighlightEvidenceCatalog sparseEvidenceCatalog = new HighlightEvidenceCatalog(
                asrSegments, sparseOcrEvidence, sparseVisualTimeline);
        String sparsePrompt = promptFactory.buildSparseReview(
                streamerName, asrSegments, sparseOcrEvidence, sparseVisualTimeline,
                bucket.getDanmakus(), fileStart, fileEnd, sessionToFileOffset);
        String draftCacheKey = DRAFT_CACHE_VERSION + "-" + segmentKey;
        String finalReviewKey = FINAL_REVIEW_VERSION + "-" + segmentKey;
        HighlightAnalysisResult draft = loadOrAnalyzeDraft(
                streamerName, draftCacheKey, videoFile.getParentFile(), sparsePrompt);
        if (!requiresDenseReview(draft)) {
            auditRepository.append(videoFile.getParentFile(), "sparse-rejected",
                    finalReviewKey, sparsePrompt, null, draft, sparseVisualBatch);
            return Collections.emptyList();
        }

        HighlightReviewContext reviewContext = new HighlightReviewContext(
                fileStart, fileEnd,
                bucket.getSignalStartTime() - sessionToFileOffset,
                bucket.getSignalEndTime() - sessionToFileOffset,
                sessionToFileOffset, bucket.getDanmakus());
        List<HighlightReviewPlan> reviewPlans = reviewWindowPlanner.plan(
                draft, reviewContext, sparseEvidenceCatalog);
        HighlightDenseReviewContext denseContext = new HighlightDenseReviewContext(
                videoFile, streamerName, sessionToFileOffset,
                segmentKey, finalReviewKey, asrSegments, bucket.getDanmakus());
        List<ConfirmedHighlight> confirmed = new ArrayList<>();
        for (int index = 0; index < reviewPlans.size(); index++) {
            HighlightReviewPlan plan = reviewPlans.get(index);
            auditReviewWindow(videoFile.getParentFile(), finalReviewKey,
                    draft, plan, index + 1, reviewPlans.size());
            confirmed.addAll(denseReviewer.review(denseContext, plan));
        }
        return confirmed;
    }

    private List<AsrSegment> transcribe(
            String streamerName,
            String cacheKey,
            File videoFile,
            int fileStart,
            int fileEnd) {
        List<AsrSegment> cached = analysisCache.getAsr(streamerName, cacheKey);
        if (cached != null) {
            log.info("Using cached ASR result: {}", cacheKey);
            return cached;
        }
        List<AsrSegment> result = asrService.transcribeSegment(
                videoFile, fileStart, fileEnd);
        List<AsrSegment> segments = result == null ? Collections.emptyList() : result;
        analysisCache.saveAsr(streamerName, cacheKey, segments);
        return segments;
    }

    private List<OcrFrameEvidence> collectOcr(
            String streamerName,
            String cacheKey,
            File videoFile,
            List<VisualFrameEvidence> visualFrames) {
        List<OcrFrameEvidence> cached = analysisCache.getOcr(streamerName, cacheKey);
        if (cached != null) {
            log.info("Using cached OCR result: {}", cacheKey);
            return cached;
        }
        List<OcrFrameEvidence> evidence = ocrEvidenceCollector.collect(
                videoFile, visualFrames);
        analysisCache.saveOcr(streamerName, cacheKey, evidence);
        return evidence;
    }

    private VisualEvidenceBatch collectVisualEvidence(
            File videoFile,
            DanmakuTimeBucket bucket,
            int fileStart,
            int fileEnd,
            int sessionToFileOffset) {
        int signalStart = bucket.getSignalEndTime() > bucket.getSignalStartTime()
                ? bucket.getSignalStartTime() - sessionToFileOffset : fileStart;
        int signalEnd = bucket.getSignalEndTime() > bucket.getSignalStartTime()
                ? bucket.getSignalEndTime() - sessionToFileOffset : fileEnd;
        signalStart = Math.max(fileStart, signalStart);
        signalEnd = Math.min(fileEnd, signalEnd);
        if (signalEnd <= signalStart) {
            signalStart = fileStart;
            signalEnd = fileEnd;
        }
        File root = new File(videoFile.getParentFile(), ".highlight-evidence/vision");
        File directory = new File(root, visualEvidenceCollector.buildSegmentDirectoryName(
                videoFile, fileStart, fileEnd));
        return new VisualEvidenceBatch(visualEvidenceCollector.collect(
                videoFile, fileStart, fileEnd, signalStart, signalEnd, directory));
    }

    private HighlightAnalysisResult loadOrAnalyzeDraft(
            String streamerName,
            String draftCacheKey,
            File recordDirectory,
            String evidencePrompt) {
        HighlightAnalysisResult draft = analysisCache.getAnalysis(streamerName, draftCacheKey);
        if (draft != null) {
            auditRepository.append(recordDirectory, "draft-cache", draftCacheKey,
                    evidencePrompt, null, draft, null);
            return draft;
        }
        Supplier<LlmCallResult<HighlightAnalysisResult>> request =
                () -> llmService.chatWithRaw(
                        evidencePrompt, HighlightAnalysisResult.class);
        LlmAuditTask task = new LlmAuditTask(
                recordDirectory, "draft", draftCacheKey, evidencePrompt, null);
        LlmCallResult<HighlightAnalysisResult> call;
        try {
            call = callWithFailureAudit(task, request);
        } catch (LlmResponseException firstInvalidResponse) {
            task = task.withStage("draft-retry");
            call = callWithFailureAudit(task, request);
        }
        draft = requireResult(call.getResult(), "draft analysis");
        auditRepository.append(recordDirectory, task.stage, draftCacheKey,
                evidencePrompt, call.getRawResponse(), draft, null);
        analysisCache.saveAnalysis(streamerName, draftCacheKey, draft);
        return draft;
    }

    private <T> T requireResult(T result, String stage) {
        if (result == null) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "LLM returned empty result during " + stage);
        }
        return result;
    }

    private <T> LlmCallResult<T> callWithFailureAudit(
            LlmAuditTask task, Supplier<LlmCallResult<T>> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            JSONObject failure = new JSONObject(true);
            failure.put("exception", e.getClass().getName());
            failure.put("message", e.getMessage());
            String rawEnvelope = e instanceof LlmResponseException
                    ? ((LlmResponseException) e).getRawEnvelope() : null;
            auditRepository.append(task.recordDirectory, task.stage + "-error",
                    task.cacheKey, task.prompt, rawEnvelope,
                    failure, task.visualEvidence);
            throw e;
        }
    }

    /** 单次模型调用所需的审计上下文，避免调用边界出现长参数列表。 */
    private static final class LlmAuditTask {
        private final File recordDirectory;
        private final String stage;
        private final String cacheKey;
        private final String prompt;
        private final VisualEvidenceBatch visualEvidence;

        private LlmAuditTask(
                File recordDirectory,
                String stage,
                String cacheKey,
                String prompt,
                VisualEvidenceBatch visualEvidence) {
            this.recordDirectory = recordDirectory;
            this.stage = stage;
            this.cacheKey = cacheKey;
            this.prompt = prompt;
            this.visualEvidence = visualEvidence;
        }

        private LlmAuditTask withStage(String replacementStage) {
            return new LlmAuditTask(recordDirectory, replacementStage,
                    cacheKey, prompt, visualEvidence);
        }
    }

    private boolean requiresDenseReview(HighlightAnalysisResult result) {
        return result != null && (Boolean.TRUE.equals(result.getHighlight())
                || result.getScore() >= MINIMUM_DENSE_REVIEW_SCORE);
    }

    private void auditReviewWindow(
            File recordDirectory,
            String cacheKey,
            HighlightAnalysisResult draft,
            HighlightReviewPlan reviewPlan,
            int windowIndex,
            int windowCount) {
        HighlightClipRange reviewWindow = reviewPlan.getRange();
        JSONObject decision = new JSONObject(true);
        decision.put("draftHighlight", draft.getHighlight());
        decision.put("draftScore", draft.getScore());
        decision.put("strategy", reviewPlan.getStrategy());
        decision.put("anchorSecond", reviewPlan.getAnchorSecond());
        decision.put("windowIndex", windowIndex);
        decision.put("windowCount", windowCount);
        decision.put("reviewStartSecond", reviewWindow.getStartSecond());
        decision.put("reviewEndSecond", reviewWindow.getEndSecond());
        decision.put("reviewDurationSeconds", reviewWindow.durationSeconds());
        auditRepository.append(recordDirectory, "dense-review-window", cacheKey,
                "deterministic local review-window planning", null, decision, null);
    }

}
