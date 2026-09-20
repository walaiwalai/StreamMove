package com.sh.engine.processor.plugin.highlight.danmaku;

import com.sh.engine.manager.CacheBizManager;
import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.model.danmaku.HighlightAnalysisResult;
import com.sh.engine.model.danmaku.HighlightFactVerification;
import com.sh.engine.model.danmaku.HighlightPayoffAssessment;
import com.sh.engine.model.danmaku.HighlightPublicationAssessment;
import com.sh.engine.model.danmaku.OcrFrameEvidence;
import com.sh.engine.model.danmaku.VisualTimelineResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/** 将允许降级的高光缓存异常集中隔离在业务流程之外。 */
@Component
@Slf4j
public class HighlightAnalysisCache {
    @Resource
    private CacheBizManager cacheBizManager;

    public List<AsrSegment> getAsr(String streamerName, String cacheKey) {
        try {
            return cacheBizManager.getAsrResult(streamerName, cacheKey);
        } catch (RuntimeException e) {
            log.warn("ASR cache read failed, cacheKey: {}", cacheKey, e);
            return null;
        }
    }

    public void saveAsr(
            String streamerName, String cacheKey, List<AsrSegment> segments) {
        try {
            cacheBizManager.saveAsrResult(streamerName, cacheKey, segments);
        } catch (RuntimeException e) {
            log.warn("ASR cache write failed, cacheKey: {}", cacheKey, e);
        }
    }

    public List<OcrFrameEvidence> getOcr(
            String streamerName, String cacheKey) {
        try {
            return cacheBizManager.getOcrEvidence(streamerName, cacheKey);
        } catch (RuntimeException e) {
            log.warn("OCR cache read failed, cacheKey: {}", cacheKey, e);
            return null;
        }
    }

    public void saveOcr(
            String streamerName,
            String cacheKey,
            List<OcrFrameEvidence> evidence) {
        try {
            cacheBizManager.saveOcrEvidence(streamerName, cacheKey, evidence);
        } catch (RuntimeException e) {
            log.warn("OCR cache write failed, cacheKey: {}", cacheKey, e);
        }
    }

    public HighlightAnalysisResult getAnalysis(String streamerName, String cacheKey) {
        try {
            return cacheBizManager.getHighlightAnalysis(streamerName, cacheKey);
        } catch (RuntimeException e) {
            log.warn("highlight cache read failed, cacheKey: {}", cacheKey, e);
            return null;
        }
    }

    public void saveAnalysis(
            String streamerName, String cacheKey, HighlightAnalysisResult result) {
        try {
            cacheBizManager.saveHighlightAnalysis(streamerName, cacheKey, result);
        } catch (RuntimeException e) {
            log.warn("highlight cache write failed, cacheKey: {}", cacheKey, e);
        }
    }

    public HighlightFactVerification getFactVerification(
            String streamerName, String cacheKey) {
        try {
            return cacheBizManager.getHighlightFactVerification(streamerName, cacheKey);
        } catch (RuntimeException e) {
            log.warn("highlight fact cache read failed, cacheKey: {}", cacheKey, e);
            return null;
        }
    }

    public void saveFactVerification(
            String streamerName,
            String cacheKey,
            HighlightFactVerification verification) {
        try {
            cacheBizManager.saveHighlightFactVerification(
                    streamerName, cacheKey, verification);
        } catch (RuntimeException e) {
            log.warn("highlight fact cache write failed, cacheKey: {}", cacheKey, e);
        }
    }

    public HighlightPayoffAssessment getPayoffAssessment(
            String streamerName, String cacheKey) {
        try {
            return cacheBizManager.getHighlightPayoffAssessment(streamerName, cacheKey);
        } catch (RuntimeException e) {
            log.warn("highlight payoff cache read failed, cacheKey: {}", cacheKey, e);
            return null;
        }
    }

    public void savePayoffAssessment(
            String streamerName,
            String cacheKey,
            HighlightPayoffAssessment assessment) {
        try {
            cacheBizManager.saveHighlightPayoffAssessment(
                    streamerName, cacheKey, assessment);
        } catch (RuntimeException e) {
            log.warn("highlight payoff cache write failed, cacheKey: {}", cacheKey, e);
        }
    }

    public HighlightPublicationAssessment getPublicationAssessment(
            String streamerName, String cacheKey) {
        try {
            return cacheBizManager.getHighlightPublicationAssessment(streamerName, cacheKey);
        } catch (RuntimeException e) {
            log.warn("highlight publication cache read failed, cacheKey: {}", cacheKey, e);
            return null;
        }
    }

    public void savePublicationAssessment(
            String streamerName,
            String cacheKey,
            HighlightPublicationAssessment assessment) {
        try {
            cacheBizManager.saveHighlightPublicationAssessment(
                    streamerName, cacheKey, assessment);
        } catch (RuntimeException e) {
            log.warn("highlight publication cache write failed, cacheKey: {}", cacheKey, e);
        }
    }

    public VisualTimelineResult getVisualTimeline(String streamerName, String cacheKey) {
        try {
            return cacheBizManager.getVisualTimeline(streamerName, cacheKey);
        } catch (RuntimeException e) {
            log.warn("visual timeline cache read failed, cacheKey: {}", cacheKey, e);
            return null;
        }
    }

    public void saveVisualTimeline(
            String streamerName, String cacheKey, VisualTimelineResult timeline) {
        try {
            cacheBizManager.saveVisualTimeline(streamerName, cacheKey, timeline);
        } catch (RuntimeException e) {
            log.warn("visual timeline cache write failed, cacheKey: {}", cacheKey, e);
        }
    }
}
