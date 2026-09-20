package com.sh.engine.processor.plugin.highlight.danmaku;

import com.sh.engine.model.danmaku.HighlightClipRange;
import com.sh.engine.model.danmaku.HighlightEvidenceCatalog;
import com.sh.engine.model.danmaku.HighlightEvidenceItem;
import com.sh.engine.model.danmaku.HighlightFactVerification;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/**
 * 用已验真的一手证据确定唯一精剪边界。弹幕属于画外召回信号，不参与视频边界计算。
 */
@Component
public class HighlightClipBoundaryResolver {
    public static final int MINIMUM_CLIP_SECONDS = 12;
    public static final int MAXIMUM_CLIP_SECONDS = 65;
    private static final int CLIP_TAIL_SECONDS = 3;
    private static final int MAXIMUM_SETUP_TO_ACTION_SECONDS = 18;

    /** 从最接近动作的必要铺垫开始，在可见结果后及时结束。 */
    public Resolution resolve(
            HighlightFactVerification facts,
            HighlightClipRange reviewWindow,
            HighlightEvidenceCatalog evidenceCatalog,
            Set<Integer> coverCandidateTimestamps) {
        List<String> violations = new ArrayList<>();
        if (facts == null || reviewWindow == null || evidenceCatalog == null) {
            return Resolution.rejected("缺少精剪边界所需的事实、范围或证据目录");
        }
        List<HighlightEvidenceItem> actions = evidenceCatalog.resolveAll(
                facts.getActionEvidence());
        List<HighlightEvidenceItem> outcomes = evidenceCatalog.resolveAll(
                facts.getOutcomeEvidence());
        if (actions.isEmpty() || outcomes.isEmpty()) {
            return Resolution.rejected("动作或结果没有可解析的一手证据");
        }

        int firstAction = actions.stream().mapToInt(HighlightEvidenceItem::getStartSecond)
                .min().orElse(reviewWindow.getStartSecond());
        Integer setupAnchor = findLatestSetupBeforeAction(
                evidenceCatalog.resolveAll(facts.getSetupEvidence()), firstAction);
        if (setupAnchor == null) {
            violations.add("铺垫证据没有发生在关键动作之前");
        } else if (firstAction - setupAnchor > MAXIMUM_SETUP_TO_ACTION_SECONDS) {
            violations.add("最近的必要铺垫距离关键动作超过18秒");
        }
        int lastOutcome = outcomes.stream().mapToInt(HighlightEvidenceItem::getEndSecond)
                .max().orElse(reviewWindow.getEndSecond());
        if (lastOutcome <= firstAction) {
            violations.add("结果证据没有发生在关键动作之后");
        }
        if (!violations.isEmpty()) {
            return Resolution.rejected(violations);
        }

        int start = Math.max(reviewWindow.getStartSecond(),
                Math.min(setupAnchor, firstAction));
        int end = Math.min(reviewWindow.getEndSecond(), lastOutcome + CLIP_TAIL_SECONDS);
        int[] expanded = expandToMinimumDuration(
                start, end, reviewWindow.getEndSecond());
        int duration = expanded[1] - expanded[0];
        if (duration < MINIMUM_CLIP_SECONDS || duration > MAXIMUM_CLIP_SECONDS) {
            return Resolution.rejected("证据跨度无法形成12~65秒的完整精剪区间");
        }
        HighlightClipRange clipRange = new HighlightClipRange(expanded[0], expanded[1]);
        Integer coverTimestamp = findCoverTimestamp(
                facts, clipRange, evidenceCatalog, coverCandidateTimestamps);
        if (coverTimestamp == null) {
            return Resolution.rejected("关键动作或结果附近没有视觉模型认可的封面帧");
        }
        return Resolution.accepted(clipRange, coverTimestamp, lastOutcome);
    }

    private Integer findLatestSetupBeforeAction(
            List<HighlightEvidenceItem> setupItems, int firstAction) {
        OptionalInt latest = setupItems.stream()
                .filter(item -> item.getStartSecond() <= firstAction)
                .mapToInt(HighlightEvidenceItem::getStartSecond)
                .max();
        return latest.isPresent() ? latest.getAsInt() : null;
    }

    private int[] expandToMinimumDuration(
            int start, int end, int candidateEnd) {
        int missing = MINIMUM_CLIP_SECONDS - (end - start);
        if (missing <= 0) {
            return new int[]{start, end};
        }
        int extendAfter = Math.min(candidateEnd - end, missing);
        end += extendAfter;
        return new int[]{start, end};
    }

    private Integer findCoverTimestamp(
            HighlightFactVerification facts,
            HighlightClipRange clip,
            HighlightEvidenceCatalog evidenceCatalog,
            Set<Integer> coverCandidateTimestamps) {
        List<HighlightEvidenceItem> eventItems = evidenceCatalog.resolveAll(
                facts.getActionEvidence(), facts.getOutcomeEvidence());
        int eventStart = eventItems.stream().mapToInt(HighlightEvidenceItem::getStartSecond)
                .min().orElse(clip.getStartSecond());
        int eventEnd = eventItems.stream().mapToInt(HighlightEvidenceItem::getEndSecond)
                .max().orElse(clip.getEndSecond());
        Integer requested = parseTime(facts.getCoverTimestamp());
        Set<Integer> preferred = coverCandidatesInsideEvent(
                coverCandidateTimestamps, clip, eventStart, eventEnd);
        Integer selected = selectNearest(preferred, requested, eventStart, eventEnd);
        if (selected != null) {
            return selected;
        }
        Set<Integer> verifiedVisualEventFrames = new HashSet<>();
        eventItems.stream()
                .filter(item -> "VISION".equals(item.getSource()))
                .map(HighlightEvidenceItem::getStartSecond)
                .filter(clip::contains)
                .forEach(verifiedVisualEventFrames::add);
        selected = selectNearest(
                verifiedVisualEventFrames, requested, eventStart, eventEnd);
        if (selected != null) {
            return selected;
        }
        Set<Integer> preferredInsideClip = new HashSet<>();
        if (coverCandidateTimestamps != null) {
            coverCandidateTimestamps.stream()
                    .filter(timestamp -> timestamp != null && clip.contains(timestamp))
                    .forEach(preferredInsideClip::add);
        }
        selected = selectNearest(preferredInsideClip, requested, eventStart, eventEnd);
        if (selected != null) {
            return selected;
        }
        Set<Integer> anyVisualFrame = new HashSet<>();
        evidenceCatalog.itemsInside("VISION", clip).stream()
                .map(HighlightEvidenceItem::getStartSecond)
                .forEach(anyVisualFrame::add);
        return selectNearest(anyVisualFrame, requested, eventStart, eventEnd);
    }

    private Set<Integer> coverCandidatesInsideEvent(
            Set<Integer> coverCandidateTimestamps,
            HighlightClipRange clip,
            int eventStart,
            int eventEnd) {
        if (CollectionUtils.isEmpty(coverCandidateTimestamps)) {
            return Collections.emptySet();
        }
        Set<Integer> eligible = new HashSet<>();
        for (Integer timestamp : coverCandidateTimestamps) {
            if (timestamp != null && clip.contains(timestamp)
                    && timestamp >= eventStart && timestamp <= eventEnd) {
                eligible.add(timestamp);
            }
        }
        return eligible;
    }

    private Integer selectNearest(
            Set<Integer> candidates,
            Integer requested,
            int eventStart,
            int eventEnd) {
        if (CollectionUtils.isEmpty(candidates)) {
            return null;
        }
        if (requested != null && candidates.contains(requested)) {
            return requested;
        }
        int focus = eventStart + (eventEnd - eventStart) / 2;
        return candidates.stream().min((first, second) -> Integer.compare(
                Math.abs(first - focus), Math.abs(second - focus))).orElse(null);
    }

    private Integer parseTime(String time) {
        if (StringUtils.isBlank(time)) {
            return null;
        }
        String[] parts = time.trim().split(":");
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

    /** 已解析边界或明确拒绝原因。 */
    public static final class Resolution {
        private final HighlightClipRange clipRange;
        private final Integer coverTimestamp;
        private final Integer eventAnchorTimestamp;
        private final List<String> violations;

        private Resolution(
                HighlightClipRange clipRange,
                Integer coverTimestamp,
                Integer eventAnchorTimestamp,
                List<String> violations) {
            this.clipRange = clipRange;
            this.coverTimestamp = coverTimestamp;
            this.eventAnchorTimestamp = eventAnchorTimestamp;
            this.violations = Collections.unmodifiableList(new ArrayList<>(violations));
        }

        private static Resolution accepted(
                HighlightClipRange clipRange,
                Integer coverTimestamp,
                Integer eventAnchorTimestamp) {
            return new Resolution(clipRange, coverTimestamp,
                    eventAnchorTimestamp, Collections.emptyList());
        }

        private static Resolution rejected(String violation) {
            return rejected(Collections.singletonList(violation));
        }

        private static Resolution rejected(List<String> violations) {
            return new Resolution(null, null, null, violations);
        }

        public boolean isAccepted() {
            return violations.isEmpty();
        }

        public HighlightClipRange getClipRange() {
            return clipRange;
        }

        public Integer getCoverTimestamp() {
            return coverTimestamp;
        }

        public Integer getEventAnchorTimestamp() {
            return eventAnchorTimestamp;
        }

        public List<String> getViolations() {
            return violations;
        }
    }
}
