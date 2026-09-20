package com.sh.engine.model.danmaku;

import com.sh.engine.model.asr.AsrSegment;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 当前候选的一手证据目录。来源、可信时间和原文由本地代码维护，模型只返回稳定编号。
 */
public final class HighlightEvidenceCatalog {
    private static final String ASR_SOURCE = "ASR";
    private static final String OCR_SOURCE = "OCR";
    private static final String VISION_SOURCE = "VISION";

    private final Map<String, HighlightEvidenceItem> itemsById = new LinkedHashMap<>();

    public HighlightEvidenceCatalog(
            List<AsrSegment> asrSegments,
            List<OcrFrameEvidence> ocrFrames,
            VisualTimelineResult visualTimeline) {
        this(asrSegments, ocrFrames, visualTimeline, Collections.emptyList());
    }

    public HighlightEvidenceCatalog(
            List<AsrSegment> asrSegments,
            List<OcrFrameEvidence> ocrFrames,
            VisualTimelineResult visualTimeline,
            List<HighlightEvidenceItem> additionalItems) {
        addAsrItems(asrSegments);
        addOcrItems(ocrFrames);
        addVisualItems(visualTimeline);
        if (additionalItems != null) {
            additionalItems.stream()
                    .filter(item -> item != null
                            && StringUtils.isNotBlank(item.getEvidenceId()))
                    .forEach(this::register);
        }
    }

    /** 只接受目录中真实存在的编号；模型复写的时间和文本不作为事实来源。 */
    public boolean supports(HighlightEvidenceReference reference) {
        return resolve(reference) != null;
    }

    /** 将模型引用解析为本地可信证据；来源冲突时拒绝。 */
    public HighlightEvidenceItem resolve(HighlightEvidenceReference reference) {
        if (reference == null || StringUtils.isBlank(reference.getEvidenceId())) {
            return null;
        }
        HighlightEvidenceItem item = resolve(reference.getEvidenceId());
        if (item == null) {
            return null;
        }
        String claimedSource = StringUtils.trimToEmpty(reference.getSource());
        if (StringUtils.isNotBlank(claimedSource)
                && !item.getSource().equalsIgnoreCase(claimedSource)) {
            return null;
        }
        return item;
    }

    /** 解析不携带模型复写来源的稳定证据编号。 */
    public HighlightEvidenceItem resolve(String evidenceId) {
        if (StringUtils.isBlank(evidenceId)) {
            return null;
        }
        return itemsById.get(evidenceId.trim().toUpperCase(Locale.ROOT));
    }

    /** 按写入顺序返回指定来源的证据，供提示词展示稳定编号。 */
    public List<HighlightEvidenceItem> items(String source) {
        if (StringUtils.isBlank(source)) {
            return Collections.emptyList();
        }
        String normalized = source.trim().toUpperCase(Locale.ROOT);
        List<HighlightEvidenceItem> matches = itemsById.values().stream()
                .filter(item -> normalized.equals(item.getSource()))
                .collect(Collectors.toList());
        return Collections.unmodifiableList(matches);
    }

    /** 返回指定来源中完全位于精剪区间内的证据，并保留原始稳定编号。 */
    public List<HighlightEvidenceItem> itemsInside(
            String source, HighlightClipRange clipRange) {
        if (clipRange == null) {
            return Collections.emptyList();
        }
        List<HighlightEvidenceItem> matches = items(source).stream()
                .filter(item -> item.isInside(clipRange))
                .collect(Collectors.toList());
        return Collections.unmodifiableList(matches);
    }

    /** 批量解析模型返回的证据编号；不存在或来源冲突的编号由调用方判错。 */
    @SafeVarargs
    public final List<HighlightEvidenceItem> resolveAll(
            List<HighlightEvidenceReference>... referenceGroups) {
        List<HighlightEvidenceItem> resolved = new ArrayList<>();
        if (referenceGroups == null) {
            return resolved;
        }
        for (List<HighlightEvidenceReference> references : referenceGroups) {
            if (references == null) {
                continue;
            }
            for (HighlightEvidenceReference reference : references) {
                HighlightEvidenceItem item = resolve(reference);
                if (item != null) {
                    resolved.add(item);
                }
            }
        }
        return Collections.unmodifiableList(resolved);
    }

    private void addAsrItems(List<AsrSegment> segments) {
        if (segments == null) {
            return;
        }
        int index = 1;
        for (AsrSegment segment : segments) {
            if (segment == null || StringUtils.isBlank(segment.getText())
                    || segment.getStartTime() < 0
                    || segment.getEndTime() < segment.getStartTime()) {
                continue;
            }
            register(item(ASR_SOURCE, index++, segment.getStartTime(),
                    segment.getEndTime(), segment.getText()));
        }
    }

    private void addOcrItems(List<OcrFrameEvidence> frames) {
        if (frames == null) {
            return;
        }
        int index = 1;
        for (OcrFrameEvidence frame : frames) {
            if (frame == null || frame.getTimestampSeconds() < 0
                    || frame.getTexts() == null) {
                continue;
            }
            String text = frame.getTexts().stream()
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.joining(" | "));
            if (StringUtils.isBlank(text)) {
                continue;
            }
            register(item(OCR_SOURCE, index++, frame.getTimestampSeconds(),
                    frame.getTimestampSeconds(), text));
        }
    }

    private void addVisualItems(VisualTimelineResult timeline) {
        if (timeline == null || timeline.getObservations() == null) {
            return;
        }
        int index = 1;
        for (VisualObservation observation : timeline.getObservations()) {
            Integer timestamp = observation == null
                    ? null : parseTime(observation.getTimestamp());
            if (timestamp == null || StringUtils.isBlank(observation.getObservableFacts())) {
                continue;
            }
            StringBuilder text = new StringBuilder("帧#")
                    .append(observation.getFrameIndex()).append(" 可见事实：")
                    .append(observation.getObservableFacts());
            if (StringUtils.isNotBlank(observation.getVisibleChange())) {
                text.append("；相邻帧变化：").append(observation.getVisibleChange());
            }
            text.append("；置信度：")
                    .append(StringUtils.defaultString(observation.getCertainty()));
            register(item(VISION_SOURCE, index++, timestamp, timestamp, text.toString()));
        }
    }

    private HighlightEvidenceItem item(
            String source, int index, int start, int end, String text) {
        return new HighlightEvidenceItem(
                String.format("%s-%03d", source, index), source, start, end, text);
    }

    private void register(HighlightEvidenceItem item) {
        itemsById.put(item.getEvidenceId(), item);
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
}
