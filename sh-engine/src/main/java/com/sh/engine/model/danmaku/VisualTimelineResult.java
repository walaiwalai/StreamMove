package com.sh.engine.model.danmaku;

import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** 多帧视觉模型生成的中立画面时间线。 */
@Data
public class VisualTimelineResult {
    private List<VisualObservation> observations;
    /** 仅汇总多帧共同直接支持的视觉事实。 */
    private String summary;
    /** 无法从离散帧确认的动作、主体或因果。 */
    private List<String> uncertainties;

    /** 保留指定局部窗内的可观测事实，不改写原始帧编号和时间。 */
    public VisualTimelineResult inside(HighlightClipRange range) {
        if (range == null || observations == null) {
            throw new IllegalArgumentException("visual timeline or range is empty");
        }
        List<VisualObservation> selected = observations.stream()
                .filter(item -> item != null)
                .filter(item -> {
                    Integer timestamp = parseTime(item.getTimestamp());
                    return timestamp != null && range.contains(timestamp);
                })
                .collect(Collectors.toList());
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("focused window contains no visual observations");
        }
        VisualTimelineResult result = new VisualTimelineResult();
        result.setObservations(Collections.unmodifiableList(new ArrayList<>(selected)));
        result.setSummary(null);
        result.setUncertainties(Collections.emptyList());
        return result;
    }

    private Integer parseTime(String value) {
        if (value == null) {
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
