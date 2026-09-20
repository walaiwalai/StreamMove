package com.sh.engine.processor.plugin.highlight.danmaku;

import com.sh.engine.model.danmaku.HighlightAnalysisResult;
import com.sh.engine.model.danmaku.HighlightClipRange;
import com.sh.engine.model.danmaku.HighlightEvidenceCatalog;
import com.sh.engine.model.danmaku.HighlightEvidenceItem;
import com.sh.engine.model.danmaku.HighlightEvidenceReference;
import com.sh.engine.model.danmaku.HighlightReviewContext;
import com.sh.engine.model.danmaku.HighlightReviewPlan;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 根据稀疏证据初判规划局部密集复核窗口，不把不合规的模型剪辑范围直接带入下游。
 */
@Component
public class HighlightReviewWindowPlanner {
    private static final String VERIFIED_DRAFT_RANGE = "VERIFIED_DRAFT_RANGE";
    private static final String VERIFIED_STRUCTURED_EVIDENCE =
            "VERIFIED_STRUCTURED_EVIDENCE";
    private static final String EARLIEST_SPARSE_EVIDENCE =
            "EARLIEST_SPARSE_EVIDENCE";
    private static final String PRE_SIGNAL_CONTEXT = "PRE_SIGNAL_CONTEXT";
    private static final String DANMAKU_BURST = "DANMAKU_BURST";
    private static final int MAXIMUM_REVIEW_SECONDS = 75;
    private static final int MINIMUM_REVIEW_SECONDS = 20;
    private static final int CONTEXT_BEFORE_DANMAKU_BURST_SECONDS = 55;
    private static final int CONTEXT_AFTER_DANMAKU_BURST_SECONDS = 20;
    private static final int CONTEXT_BEFORE_EARLIEST_EVIDENCE_SECONDS = 40;
    private static final int CONTEXT_BEFORE_SIGNAL_SECONDS = 60;
    private static final int STRUCTURED_EVIDENCE_MARGIN_SECONDS = 10;
    private static final double DUPLICATE_WINDOW_OVERLAP_RATIO = 0.7D;

    /**
     * 只有可信的行动/结果证据才能采用模型范围；模糊初判由本地弹幕反应点定位。
     */
    public List<HighlightReviewPlan> plan(
            HighlightAnalysisResult draft,
            HighlightReviewContext context,
            HighlightEvidenceCatalog evidenceCatalog) {
        int candidateStart = context.getCandidateStartSecond();
        int candidateEnd = context.getCandidateEndSecond();
        if (candidateStart < 0 || candidateEnd - candidateStart < MINIMUM_REVIEW_SECONDS) {
            throw new IllegalArgumentException("candidate is too short for dense review");
        }
        List<EvidencePoint> structuredPoints = collectStructuredEvidencePoints(
                draft, candidateStart, candidateEnd, evidenceCatalog);
        HighlightClipRange proposed = isTrustedDraftLocation(
                draft, evidenceCatalog)
                ? parseProposedRange(draft, candidateStart, candidateEnd) : null;
        if (proposed != null
                && proposed.durationSeconds() >= MINIMUM_REVIEW_SECONDS
                && proposed.durationSeconds() <= MAXIMUM_REVIEW_SECONDS) {
            return Collections.singletonList(plan(proposed, VERIFIED_DRAFT_RANGE));
        }
        HighlightClipRange evidenceWindow = buildStructuredEvidenceWindow(
                draft, structuredPoints, candidateStart, candidateEnd,
                evidenceCatalog);
        if (evidenceWindow != null) {
            return Collections.singletonList(
                    plan(evidenceWindow, VERIFIED_STRUCTURED_EVIDENCE));
        }
        List<HighlightReviewPlan> plans = new ArrayList<>();
        Integer earliestEvidence = findEarliestPotentialEvidence(
                draft, candidateStart, candidateEnd, evidenceCatalog);
        if (earliestEvidence != null) {
            HighlightClipRange earlyWindow = aroundAnchor(
                    earliestEvidence, CONTEXT_BEFORE_EARLIEST_EVIDENCE_SECONDS,
                    candidateStart, candidateEnd);
            plans.add(new HighlightReviewPlan(
                    earlyWindow, EARLIEST_SPARSE_EVIDENCE, earliestEvidence));
        } else {
            int signalStart = context.findSignalStartOrCandidateCenter();
            HighlightClipRange preSignalWindow = aroundAnchor(
                    signalStart, CONTEXT_BEFORE_SIGNAL_SECONDS,
                    candidateStart, candidateEnd);
            plans.add(new HighlightReviewPlan(
                    preSignalWindow, PRE_SIGNAL_CONTEXT, signalStart));
        }
        int burstCenter = context.findDanmakuBurstCenterSecond();
        HighlightClipRange burstWindow = aroundDanmakuBurst(
                burstCenter, candidateStart, candidateEnd);
        HighlightReviewPlan burstPlan = new HighlightReviewPlan(
                burstWindow, DANMAKU_BURST, burstCenter);
        if (!substantiallyOverlaps(plans.get(0).getRange(), burstWindow)) {
            plans.add(burstPlan);
        }
        return Collections.unmodifiableList(plans);
    }

    private HighlightReviewPlan plan(HighlightClipRange range, String strategy) {
        int center = range.getStartSecond() + range.durationSeconds() / 2;
        return new HighlightReviewPlan(range, strategy, center);
    }

    private Integer findEarliestPotentialEvidence(
            HighlightAnalysisResult draft,
            int candidateStart,
            int candidateEnd,
            HighlightEvidenceCatalog evidenceCatalog) {
        if (draft == null || evidenceCatalog == null
                || CollectionUtils.isEmpty(draft.getEvidence())) {
            return null;
        }
        Integer earliest = null;
        for (String evidenceId : draft.getEvidence()) {
            HighlightEvidenceItem item = evidenceCatalog.resolve(evidenceId);
            if (item != null && item.getStartSecond() >= candidateStart
                    && item.getStartSecond() < candidateEnd
                    && (earliest == null || item.getStartSecond() < earliest)) {
                earliest = item.getStartSecond();
            }
        }
        return earliest;
    }

    private boolean substantiallyOverlaps(
            HighlightClipRange first, HighlightClipRange second) {
        int overlap = Math.max(0, Math.min(first.getEndSecond(), second.getEndSecond())
                - Math.max(first.getStartSecond(), second.getStartSecond()));
        int shorterDuration = Math.min(
                first.durationSeconds(), second.durationSeconds());
        return overlap / (double) shorterDuration >= DUPLICATE_WINDOW_OVERLAP_RATIO;
    }

    private HighlightClipRange parseProposedRange(
            HighlightAnalysisResult draft, int candidateStart, int candidateEnd) {
        if (draft == null) {
            return null;
        }
        Integer start = parseTime(draft.getExactClipStart());
        Integer end = parseTime(draft.getExactClipEnd());
        if (start == null || end == null || start < candidateStart
                || end > candidateEnd || end <= start) {
            return null;
        }
        return new HighlightClipRange(start, end);
    }

    private List<EvidencePoint> collectStructuredEvidencePoints(
            HighlightAnalysisResult draft,
            int candidateStart,
            int candidateEnd,
            HighlightEvidenceCatalog evidenceCatalog) {
        List<EvidencePoint> points = new ArrayList<>();
        if (draft == null) {
            return points;
        }
        addReferences(points, draft.getActionEvidence(),
                candidateStart, candidateEnd, evidenceCatalog);
        addReferences(points, draft.getOutcomeEvidence(),
                candidateStart, candidateEnd, evidenceCatalog);
        return points;
    }

    private boolean isTrustedDraftLocation(
            HighlightAnalysisResult draft,
            HighlightEvidenceCatalog evidenceCatalog) {
        return draft != null
                && Boolean.TRUE.equals(draft.getHighlight())
                && hasSupportedReference(draft.getActionEvidence(), evidenceCatalog)
                && hasSupportedReference(draft.getOutcomeEvidence(), evidenceCatalog);
    }

    private boolean hasSupportedReference(
            List<HighlightEvidenceReference> references,
            HighlightEvidenceCatalog evidenceCatalog) {
        return evidenceCatalog != null
                && CollectionUtils.isNotEmpty(references)
                && references.stream().anyMatch(evidenceCatalog::supports);
    }

    private void addReferences(
            List<EvidencePoint> points,
            List<HighlightEvidenceReference> references,
            int candidateStart,
            int candidateEnd,
            HighlightEvidenceCatalog evidenceCatalog) {
        if (CollectionUtils.isEmpty(references)) {
            return;
        }
        for (HighlightEvidenceReference reference : references) {
            HighlightEvidenceItem item = evidenceCatalog == null
                    ? null : evidenceCatalog.resolve(reference);
            if (item == null) {
                continue;
            }
            addPoint(points, item.getStartSecond(), candidateStart, candidateEnd);
            addPoint(points, item.getEndSecond(), candidateStart, candidateEnd);
        }
    }

    private void addPoint(
            List<EvidencePoint> points,
            Integer timestamp,
            int candidateStart,
            int candidateEnd) {
        if (timestamp != null && timestamp >= candidateStart && timestamp < candidateEnd) {
            points.add(new EvidencePoint(timestamp));
        }
    }

    private HighlightClipRange buildStructuredEvidenceWindow(
            HighlightAnalysisResult draft,
            List<EvidencePoint> points,
            int candidateStart,
            int candidateEnd,
            HighlightEvidenceCatalog evidenceCatalog) {
        if (!isTrustedDraftLocation(draft, evidenceCatalog)) {
            return null;
        }
        int first = points.stream().mapToInt(EvidencePoint::getTimestamp).min()
                .orElse(candidateStart);
        int last = points.stream().mapToInt(EvidencePoint::getTimestamp).max()
                .orElse(candidateEnd);
        if (last - first + STRUCTURED_EVIDENCE_MARGIN_SECONDS * 2
                > MAXIMUM_REVIEW_SECONDS) {
            return null;
        }
        int start = Math.max(candidateStart,
                first - STRUCTURED_EVIDENCE_MARGIN_SECONDS);
        int end = Math.min(candidateEnd,
                last + STRUCTURED_EVIDENCE_MARGIN_SECONDS);
        if (end - start < MINIMUM_REVIEW_SECONDS) {
            int focus = first + (last - first) / 2;
            return centerWindow(focus, MINIMUM_REVIEW_SECONDS,
                    candidateStart, candidateEnd);
        }
        return new HighlightClipRange(start, end);
    }

    private HighlightClipRange aroundDanmakuBurst(
            int focus, int candidateStart, int candidateEnd) {
        return aroundAnchor(focus, CONTEXT_BEFORE_DANMAKU_BURST_SECONDS,
                candidateStart, candidateEnd);
    }

    private HighlightClipRange aroundAnchor(
            int focus, int contextBeforeSeconds,
            int candidateStart, int candidateEnd) {
        int duration = Math.min(MAXIMUM_REVIEW_SECONDS,
                candidateEnd - candidateStart);
        int start = focus - contextBeforeSeconds;
        start = Math.max(candidateStart, Math.min(start, candidateEnd - duration));
        return new HighlightClipRange(start, start + duration);
    }

    private HighlightClipRange centerWindow(
            int focus, int duration, int candidateStart, int candidateEnd) {
        int boundedDuration = Math.min(duration, candidateEnd - candidateStart);
        int start = focus - boundedDuration / 2;
        start = Math.max(candidateStart,
                Math.min(start, candidateEnd - boundedDuration));
        return new HighlightClipRange(start, start + boundedDuration);
    }

    private Integer parseTime(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String[] parts = value.trim().split(":");
        if (parts.length != 3) {
            return null;
        }
        try {
            return Integer.parseInt(parts[0]) * 3600
                    + Integer.parseInt(parts[1]) * 60
                    + Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class EvidencePoint {
        private final int timestamp;

        private EvidencePoint(int timestamp) {
            this.timestamp = timestamp;
        }

        private int getTimestamp() {
            return timestamp;
        }
    }
}
