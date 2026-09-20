package com.sh.engine.processor.plugin.highlight.danmaku;

import com.alibaba.fastjson.JSONObject;
import com.sh.engine.model.danmaku.ConfirmedHighlight;
import com.sh.engine.model.danmaku.HighlightClipRange;
import com.sh.engine.model.danmaku.HighlightDenseReviewContext;
import com.sh.engine.model.danmaku.HighlightEvidenceCatalog;
import com.sh.engine.model.danmaku.HighlightEvidenceItem;
import com.sh.engine.model.danmaku.HighlightFactVerification;
import com.sh.engine.model.danmaku.HighlightPayoffAssessment;
import com.sh.engine.model.danmaku.HighlightPublicationAssessment;
import com.sh.engine.model.danmaku.HighlightReviewPlan;
import com.sh.engine.model.danmaku.OcrFrameEvidence;
import com.sh.engine.model.danmaku.VisualEvidenceBatch;
import com.sh.engine.model.danmaku.VisualFrameEvidence;
import com.sh.engine.model.danmaku.VisualTimelineResult;
import com.sh.engine.processor.recorder.danmu.SimpleDanmaku;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 对一个复核窗执行局部事实验真、发布审核与确定性裁决。 */
@Component
@Slf4j
public class DanmakuHighlightDenseReviewer {
    private static final String OCR_CACHE_VERSION = "ocr-v1-text";
    private static final String DENSE_VISION_CACHE_VERSION =
            "vision-v3-dense48-original";
    private static final String PUBLICATION_VISION_CACHE_VERSION =
            "vision-v1-publication-change48-original";
    private static final int MAXIMUM_DENSE_OCR_FRAMES = 16;
    private static final int MAXIMUM_PUBLICATION_OCR_FRAMES = 20;

    @Resource
    private DanmakuVisualEvidenceCollector visualEvidenceCollector;
    @Resource
    private DanmakuVisualTimelineAnalyzer visualTimelineAnalyzer;
    @Resource
    private DanmakuHighlightEvidenceCollector ocrEvidenceCollector;
    @Resource
    private DanmakuHighlightPromptFactory promptFactory;
    @Resource
    private DanmakuHighlightFinalReviewer finalReviewer;
    @Resource
    private HighlightDecisionValidator decisionValidator;
    @Resource
    private HighlightClipBoundaryResolver clipBoundaryResolver;
    @Resource
    private HighlightFocusedWindowPlanner focusedWindowPlanner;
    @Resource
    private HighlightAnalysisCache analysisCache;
    @Resource
    private HighlightAnalysisAuditRepository auditRepository;

    /** 长窗只做一次抽帧、视觉时间线和 OCR，再分局部窗独立验真。 */
    public List<ConfirmedHighlight> review(
            HighlightDenseReviewContext context,
            HighlightReviewPlan reviewPlan) {
        HighlightClipRange reviewWindow = reviewPlan.getRange();
        DenseEvidence evidence = collectEvidence(context, reviewWindow);
        List<HighlightClipRange> focusedWindows = focusedWindowPlanner.plan(reviewWindow);
        List<ConfirmedHighlight> confirmed = new ArrayList<>();
        for (int index = 0; index < focusedWindows.size(); index++) {
            HighlightClipRange focusedWindow = focusedWindows.get(index);
            auditFocusedWindow(context, reviewWindow, focusedWindow,
                    index + 1, focusedWindows.size());
            ConfirmedHighlight highlight = reviewFocusedWindow(
                    context, evidence, focusedWindow);
            if (highlight != null) {
                confirmed.add(highlight);
            }
        }
        return Collections.unmodifiableList(confirmed);
    }

    private DenseEvidence collectEvidence(
            HighlightDenseReviewContext context,
            HighlightClipRange reviewWindow) {
        File videoFile = context.getVideoFile();
        VisualEvidenceBatch visualBatch = collectDenseVisualEvidence(
                videoFile, reviewWindow);
        String visionCacheKey = DENSE_VISION_CACHE_VERSION + "-"
                + context.getSegmentKey() + "-" + reviewWindow.getStartSecond()
                + "-" + reviewWindow.getEndSecond();
        VisualTimelineResult visualTimeline = visualTimelineAnalyzer.analyze(
                context.getStreamerName(), visionCacheKey, videoFile.getParentFile(),
                visualBatch, "vision-dense");
        List<VisualFrameEvidence> ocrFrames = visualBatch.selectFramesForOcr(
                visualTimeline, MAXIMUM_DENSE_OCR_FRAMES);
        log.info("Collecting dense OCR evidence, selectedFrames: {}/{}",
                ocrFrames.size(), visualBatch.getFrames().size());
        List<OcrFrameEvidence> ocrEvidence = collectOcr(
                context.getStreamerName(),
                OCR_CACHE_VERSION + "-dense-" + visionCacheKey,
                videoFile, ocrFrames);
        Set<Integer> coverCandidates = visualBatch.coverCandidateTimestamps(
                visualTimeline);
        return new DenseEvidence(
                visualBatch, visualTimeline, ocrEvidence, coverCandidates);
    }

    private ConfirmedHighlight reviewFocusedWindow(
            HighlightDenseReviewContext context,
            DenseEvidence evidence,
            HighlightClipRange focusedWindow) {
        FocusedEvidence focusedEvidence = collectFocusedEvidence(
                context, evidence, focusedWindow);
        String reviewKey = context.reviewKey(focusedWindow);
        HighlightFactVerification facts = finalReviewer.verifyFacts(
                context.getStreamerName(), reviewKey,
                context.getVideoFile().getParentFile(), focusedEvidence.promptContext,
                focusedEvidence.visualBatch);
        List<String> factViolations = decisionValidator.findFactViolations(
                facts, focusedEvidence.catalog);
        if (!factViolations.isEmpty()) {
            auditRejectedFacts(context, reviewKey,
                    focusedEvidence.promptContext, facts, factViolations);
            return null;
        }
        HighlightClipBoundaryResolver.Resolution boundary = clipBoundaryResolver.resolve(
                facts, focusedWindow, focusedEvidence.catalog, evidence.coverCandidates);
        auditBoundaryDecision(
                context, reviewKey, focusedEvidence.promptContext, boundary);
        if (!boundary.isAccepted()) {
            log.info("Rejecting candidate because clip boundary resolution failed: {}",
                    boundary.getViolations());
            return null;
        }
        PublicationEvidence publicationEvidence = collectPublicationEvidence(
                context, boundary.getClipRange());
        String publicationContext = publicationEvidence.promptContext;
        VisualEvidenceBatch publicationVisualBatch = publicationEvidence.visualBatch;
        HighlightPayoffAssessment payoff = finalReviewer.assessPayoff(
                context.getStreamerName(), reviewKey,
                context.getVideoFile().getParentFile(), publicationContext,
                facts, publicationVisualBatch);
        List<String> payoffViolations = decisionValidator.findPayoffViolations(
                payoff, publicationEvidence.catalog, boundary.getClipRange());
        auditPayoffDecision(
                context, reviewKey, publicationContext, payoff, payoffViolations);
        if (!payoffViolations.isEmpty()) {
            log.info("Rejecting candidate because payoff assessment failed: {}",
                    payoffViolations);
            return null;
        }
        HighlightPublicationAssessment publication = finalReviewer.reviewPublication(
                context.getStreamerName(), reviewKey,
                context.getVideoFile().getParentFile(), publicationContext,
                facts, payoff, publicationVisualBatch);
        HighlightDecisionValidator.Decision decision = decisionValidator.validate(
                facts, publication, focusedEvidence.catalog,
                publicationEvidence.catalog, boundary);
        if (decision.isAccepted()) {
            publication = finalReviewer.confirmPublication(
                    context.getStreamerName(), reviewKey,
                    context.getVideoFile().getParentFile(), publicationContext,
                    facts, payoff, publicationVisualBatch);
            decision = decisionValidator.validate(
                    facts, publication, focusedEvidence.catalog,
                    publicationEvidence.catalog, boundary);
        }
        auditFinalDecision(context, reviewKey,
                publicationContext, publication, decision);
        return toConfirmedHighlight(context.getVideoFile(), publication, decision);
    }

    private FocusedEvidence collectFocusedEvidence(
            HighlightDenseReviewContext context,
            DenseEvidence evidence,
            HighlightClipRange focusedWindow) {
        VisualEvidenceBatch visualBatch = evidence.visualBatch.inside(focusedWindow);
        VisualTimelineResult visualTimeline = evidence.visualTimeline.inside(focusedWindow);
        List<com.sh.engine.model.asr.AsrSegment> asr = context.asrInside(focusedWindow);
        List<OcrFrameEvidence> ocr = filterOcr(evidence.ocrEvidence, focusedWindow);
        List<SimpleDanmaku> danmakus = context.danmakusInside(focusedWindow);
        List<HighlightEvidenceItem> danmakuEvidence = promptFactory.selectDanmakuEvidence(
                danmakus, context.getSessionToFileOffset());
        HighlightEvidenceCatalog catalog = new HighlightEvidenceCatalog(
                asr, ocr, visualTimeline, danmakuEvidence);
        String promptContext = promptFactory.buildDenseEvidenceContext(
                context.getStreamerName(), asr, ocr, visualTimeline,
                danmakus, focusedWindow.getStartSecond(),
                focusedWindow.getEndSecond(), context.getSessionToFileOffset());
        return new FocusedEvidence(visualBatch, catalog, promptContext);
    }

    private PublicationEvidence collectPublicationEvidence(
            HighlightDenseReviewContext context,
            HighlightClipRange clipRange) {
        File videoFile = context.getVideoFile();
        VisualEvidenceBatch visualBatch = collectPublicationVisualEvidence(
                videoFile, clipRange);
        String visionCacheKey = PUBLICATION_VISION_CACHE_VERSION + "-"
                + context.getSegmentKey() + "-" + clipRange.getStartSecond()
                + "-" + clipRange.getEndSecond();
        VisualTimelineResult visualTimeline = visualTimelineAnalyzer.analyze(
                context.getStreamerName(), visionCacheKey, videoFile.getParentFile(),
                visualBatch, "vision-publication");
        List<VisualFrameEvidence> ocrFrames = visualBatch.selectFramesForOcr(
                visualTimeline, MAXIMUM_PUBLICATION_OCR_FRAMES);
        List<OcrFrameEvidence> ocr = collectOcr(
                context.getStreamerName(), OCR_CACHE_VERSION + "-" + visionCacheKey,
                videoFile, ocrFrames);
        List<com.sh.engine.model.asr.AsrSegment> asr = context.asrInside(clipRange);
        List<SimpleDanmaku> danmakus = context.danmakusInside(clipRange);
        List<HighlightEvidenceItem> danmakuEvidence = promptFactory.selectDanmakuEvidence(
                danmakus, context.getSessionToFileOffset());
        HighlightEvidenceCatalog catalog = new HighlightEvidenceCatalog(
                asr, ocr, visualTimeline, danmakuEvidence);
        String promptContext = promptFactory.buildPublicationEvidenceContext(
                context.getStreamerName(), catalog, clipRange);
        return new PublicationEvidence(visualBatch, catalog, promptContext);
    }

    private VisualEvidenceBatch collectDenseVisualEvidence(
            File videoFile, HighlightClipRange reviewWindow) {
        File root = new File(videoFile.getParentFile(),
                ".highlight-evidence/vision-dense-v3");
        File directory = new File(root, visualEvidenceCollector.buildSegmentDirectoryName(
                videoFile, reviewWindow.getStartSecond(), reviewWindow.getEndSecond()));
        return new VisualEvidenceBatch(visualEvidenceCollector.collectDense(
                videoFile, reviewWindow.getStartSecond(),
                reviewWindow.getEndSecond(), directory));
    }

    private VisualEvidenceBatch collectPublicationVisualEvidence(
            File videoFile, HighlightClipRange clipRange) {
        File root = new File(videoFile.getParentFile(),
                ".highlight-evidence/vision-publication-v1");
        File directory = new File(root, visualEvidenceCollector.buildSegmentDirectoryName(
                videoFile, clipRange.getStartSecond(), clipRange.getEndSecond()));
        return new VisualEvidenceBatch(visualEvidenceCollector.collectPublication(
                videoFile, clipRange.getStartSecond(),
                clipRange.getEndSecond(), directory));
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
        List<OcrFrameEvidence> result = ocrEvidenceCollector.collect(
                videoFile, visualFrames);
        analysisCache.saveOcr(streamerName, cacheKey, result);
        return result;
    }

    private List<OcrFrameEvidence> filterOcr(
            List<OcrFrameEvidence> frames, HighlightClipRange window) {
        if (frames == null) {
            return Collections.emptyList();
        }
        return frames.stream()
                .filter(frame -> frame != null
                        && window.contains(frame.getTimestampSeconds()))
                .collect(Collectors.toList());
    }

    private void auditFocusedWindow(
            HighlightDenseReviewContext context,
            HighlightClipRange reviewWindow,
            HighlightClipRange focusedWindow,
            int windowIndex,
            int windowCount) {
        JSONObject decision = new JSONObject(true);
        decision.put("parentStartSecond", reviewWindow.getStartSecond());
        decision.put("parentEndSecond", reviewWindow.getEndSecond());
        decision.put("focusedStartSecond", focusedWindow.getStartSecond());
        decision.put("focusedEndSecond", focusedWindow.getEndSecond());
        decision.put("windowIndex", windowIndex);
        decision.put("windowCount", windowCount);
        auditRepository.append(context.getVideoFile().getParentFile(),
                "focused-fact-window", context.reviewKey(focusedWindow),
                "deterministic overlapping local fact window", null, decision, null);
    }

    private void auditRejectedFacts(
            HighlightDenseReviewContext context,
            String reviewKey,
            String prompt,
            HighlightFactVerification facts,
            List<String> violations) {
        JSONObject decision = new JSONObject(true);
        decision.put("accepted", false);
        decision.put("facts", facts);
        decision.put("violations", violations);
        auditRepository.append(context.getVideoFile().getParentFile(),
                "fact-validation", reviewKey, prompt, null, decision, null);
        log.info("Rejecting candidate because fact validation failed: {}", violations);
    }

    private void auditBoundaryDecision(
            HighlightDenseReviewContext context,
            String reviewKey,
            String evidenceContext,
            HighlightClipBoundaryResolver.Resolution resolution) {
        JSONObject decision = new JSONObject(true);
        decision.put("accepted", resolution.isAccepted());
        decision.put("violations", resolution.getViolations());
        if (resolution.getClipRange() != null) {
            decision.put("clipStartSecond", resolution.getClipRange().getStartSecond());
            decision.put("clipEndSecond", resolution.getClipRange().getEndSecond());
            decision.put("coverTimestamp", resolution.getCoverTimestamp());
            decision.put("eventAnchorTimestamp",
                    resolution.getEventAnchorTimestamp());
        }
        auditRepository.append(context.getVideoFile().getParentFile(),
                "clip-boundary-decision", reviewKey,
                evidenceContext, null, decision, null);
    }

    private void auditFinalDecision(
            HighlightDenseReviewContext context,
            String reviewKey,
            String prompt,
            HighlightPublicationAssessment publication,
            HighlightDecisionValidator.Decision result) {
        JSONObject decision = new JSONObject(true);
        decision.put("accepted", result.isAccepted());
        decision.put("publication", publication);
        decision.put("violations", result.getViolations());
        if (result.getClipRange() != null) {
            decision.put("clipStartSecond", result.getClipRange().getStartSecond());
            decision.put("clipEndSecond", result.getClipRange().getEndSecond());
            decision.put("coverTimestamp", result.getCoverTimestamp());
            decision.put("eventAnchorTimestamp",
                    result.getEventAnchorTimestamp());
        }
        auditRepository.append(context.getVideoFile().getParentFile(),
                "final-local-decision", reviewKey, prompt, null, decision, null);
        if (!result.isAccepted()) {
            log.info("Rejecting candidate after publication review: {}",
                    result.getViolations());
        }
    }

    private void auditPayoffDecision(
            HighlightDenseReviewContext context,
            String reviewKey,
            String prompt,
            HighlightPayoffAssessment payoff,
            List<String> violations) {
        JSONObject decision = new JSONObject(true);
        decision.put("accepted", violations.isEmpty());
        decision.put("payoff", payoff);
        decision.put("violations", violations);
        auditRepository.append(context.getVideoFile().getParentFile(),
                "payoff-local-decision", reviewKey, prompt, null, decision, null);
    }

    private ConfirmedHighlight toConfirmedHighlight(
            File videoFile,
            HighlightPublicationAssessment publication,
            HighlightDecisionValidator.Decision decision) {
        if (!decision.isAccepted()) {
            return null;
        }
        HighlightClipRange clip = decision.getClipRange();
        log.info("Found confirmed highlight, clip: {}-{}s, score: {}, reason: {}",
                clip.getStartSecond(), clip.getEndSecond(),
                publication.getScore(), publication.getReason());
        return new ConfirmedHighlight(
                videoFile, clip.getStartSecond(), clip.getEndSecond(),
                decision.getCoverTimestamp(), decision.getEventAnchorTimestamp(),
                publication.getScore(),
                publication.getQuality().getAudienceValue(),
                publication.getQuality().getContentDensity(),
                publication.getReason(), publication.getSuggestedTitle(),
                publication.getCoverText());
    }

    private static final class DenseEvidence {
        private final VisualEvidenceBatch visualBatch;
        private final VisualTimelineResult visualTimeline;
        private final List<OcrFrameEvidence> ocrEvidence;
        private final Set<Integer> coverCandidates;

        private DenseEvidence(
                VisualEvidenceBatch visualBatch,
                VisualTimelineResult visualTimeline,
                List<OcrFrameEvidence> ocrEvidence,
                Set<Integer> coverCandidates) {
            this.visualBatch = visualBatch;
            this.visualTimeline = visualTimeline;
            this.ocrEvidence = ocrEvidence;
            this.coverCandidates = coverCandidates;
        }
    }

    private static final class PublicationEvidence {
        private final VisualEvidenceBatch visualBatch;
        private final HighlightEvidenceCatalog catalog;
        private final String promptContext;

        private PublicationEvidence(
                VisualEvidenceBatch visualBatch,
                HighlightEvidenceCatalog catalog,
                String promptContext) {
            this.visualBatch = visualBatch;
            this.catalog = catalog;
            this.promptContext = promptContext;
        }
    }

    private static final class FocusedEvidence {
        private final VisualEvidenceBatch visualBatch;
        private final HighlightEvidenceCatalog catalog;
        private final String promptContext;

        private FocusedEvidence(
                VisualEvidenceBatch visualBatch,
                HighlightEvidenceCatalog catalog,
                String promptContext) {
            this.visualBatch = visualBatch;
            this.catalog = catalog;
            this.promptContext = promptContext;
        }
    }
}
