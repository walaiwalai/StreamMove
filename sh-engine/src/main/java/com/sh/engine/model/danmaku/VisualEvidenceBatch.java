package com.sh.engine.model.danmaku;

import com.alibaba.fastjson.JSONObject;
import com.sh.engine.model.llm.LlmImageInput;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一次视觉请求的有序帧集合，负责维护图片序号、时间映射和返回结果不变量。
 */
public final class VisualEvidenceBatch {
    private final List<VisualFrameEvidence> frames;

    public VisualEvidenceBatch(List<VisualFrameEvidence> frames) {
        if (frames == null || frames.isEmpty() || frames.stream().anyMatch(item -> item == null)) {
            throw new IllegalArgumentException("visual evidence frames must not be empty");
        }
        this.frames = Collections.unmodifiableList(new ArrayList<>(frames));
    }

    public List<VisualFrameEvidence> getFrames() {
        return frames;
    }

    /** 返回严格落在待发布精剪范围内的真实帧，供最终视觉评审直接查看。 */
    public VisualEvidenceBatch inside(HighlightClipRange range) {
        if (range == null) {
            throw new IllegalArgumentException("highlight clip range must not be null");
        }
        List<VisualFrameEvidence> selected = frames.stream()
                .filter(frame -> range.contains(frame.getTimestampSeconds()))
                .collect(Collectors.toList());
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("highlight clip contains no visual evidence");
        }
        return new VisualEvidenceBatch(selected);
    }

    /**
     * 将长时间线拆成有界小批，避免模型用一次超长结构化输出描述全部图片。
     */
    public List<VisualEvidenceBatch> partition(int maximumFrames) {
        if (maximumFrames <= 0) {
            throw new IllegalArgumentException("maximum visual batch size must be positive");
        }
        List<VisualEvidenceBatch> batches = new ArrayList<>();
        for (int start = 0; start < frames.size(); start += maximumFrames) {
            int end = Math.min(frames.size(), start + maximumFrames);
            batches.add(new VisualEvidenceBatch(frames.subList(start, end)));
        }
        return Collections.unmodifiableList(batches);
    }

    /** 转换为保持相同顺序的模型图片输入。 */
    public List<LlmImageInput> toLlmInputs() {
        List<LlmImageInput> inputs = new ArrayList<>();
        for (int index = 0; index < frames.size(); index++) {
            inputs.add(frames.get(index).toLlmInput(index + 1));
        }
        return Collections.unmodifiableList(inputs);
    }

    /** 构造图片序号和源视频时间的不可歧义映射。 */
    public String buildFrameIndexText() {
        List<String> mappings = new ArrayList<>();
        for (int index = 0; index < frames.size(); index++) {
            mappings.add("图片" + (index + 1) + "="
                    + formatTime(frames.get(index).getTimestampSeconds()));
        }
        return String.join("，", mappings);
    }

    /**
     * 时间戳来自本地抽帧结果，不能依赖模型复写；模型只需返回有效图片序号。
     */
    public void attachTrustedTimestamps(VisualTimelineResult timeline) {
        if (timeline == null || timeline.getObservations() == null) {
            throw new IllegalArgumentException("visual timeline is empty");
        }
        for (VisualObservation observation : timeline.getObservations()) {
            if (observation == null || observation.getFrameIndex() == null
                    || observation.getFrameIndex() < 1
                    || observation.getFrameIndex() > frames.size()) {
                throw new IllegalArgumentException("visual observation has invalid frame index");
            }
            observation.setTimestamp(formatTime(
                    frames.get(observation.getFrameIndex() - 1).getTimestampSeconds()));
        }
    }

    /**
     * 当模型完整返回所有真实帧、但又追加了越界项时，确定性丢弃这些越界项。
     * 真实帧缺失或重复时不做修复，交由严格校验报错。
     *
     * @return 被丢弃的模型图片序号；空集合表示未归一化
     */
    public List<Integer> discardUnmappedObservations(VisualTimelineResult timeline) {
        if (timeline == null || timeline.getObservations() == null) {
            return Collections.emptyList();
        }
        List<VisualObservation> mapped = new ArrayList<>();
        List<Integer> unmappedIndices = new ArrayList<>();
        Set<Integer> mappedIndices = new HashSet<>();
        for (VisualObservation observation : timeline.getObservations()) {
            Integer frameIndex = observation == null ? null : observation.getFrameIndex();
            if (frameIndex == null || frameIndex < 1 || frameIndex > frames.size()) {
                unmappedIndices.add(frameIndex);
                continue;
            }
            mapped.add(observation);
            mappedIndices.add(frameIndex);
        }
        if (unmappedIndices.isEmpty() || mapped.size() != frames.size()
                || mappedIndices.size() != frames.size()) {
            return Collections.emptyList();
        }
        timeline.setObservations(mapped);
        return Collections.unmodifiableList(unmappedIndices);
    }

    /**
     * 仅在图片序号完整且唯一时，返回缺少可见事实的图片序号，供一次定点补采。
     */
    public List<Integer> incompleteObservationIndices(VisualTimelineResult timeline) {
        if (!hasExactFrameMapping(timeline)) {
            return Collections.emptyList();
        }
        List<Integer> incomplete = new ArrayList<>();
        for (VisualObservation observation : timeline.getObservations()) {
            if (StringUtils.isBlank(observation.getObservableFacts())) {
                incomplete.add(observation.getFrameIndex());
            }
        }
        Collections.sort(incomplete);
        return Collections.unmodifiableList(incomplete);
    }

    /** 是否恰好包含每个真实图片序号一次；不把内容字段完整性混入映射判断。 */
    public boolean hasExactFrameMapping(VisualTimelineResult timeline) {
        if (timeline == null || timeline.getObservations() == null
                || timeline.getObservations().size() != frames.size()) {
            return false;
        }
        Set<Integer> indices = new HashSet<>();
        for (VisualObservation observation : timeline.getObservations()) {
            Integer frameIndex = observation == null ? null : observation.getFrameIndex();
            if (frameIndex == null || frameIndex < 1 || frameIndex > frames.size()
                    || !indices.add(frameIndex)) {
                return false;
            }
        }
        return indices.size() == frames.size();
    }

    /** 按原批次图片序号构造定点补采批次，补采批次内部重新从 1 编号。 */
    public VisualEvidenceBatch subBatch(List<Integer> frameIndices) {
        if (frameIndices == null || frameIndices.isEmpty()) {
            throw new IllegalArgumentException("visual repair frame indices must not be empty");
        }
        List<VisualFrameEvidence> selected = new ArrayList<>();
        Set<Integer> unique = new HashSet<>();
        for (Integer frameIndex : frameIndices) {
            if (frameIndex == null || frameIndex < 1 || frameIndex > frames.size()
                    || !unique.add(frameIndex)) {
                throw new IllegalArgumentException("invalid visual repair frame index");
            }
            selected.add(frames.get(frameIndex - 1));
        }
        return new VisualEvidenceBatch(selected);
    }

    /** 用定点补采结果替换原时间线中的空观察，不允许覆盖已有事实。 */
    public void replaceIncompleteObservations(
            VisualTimelineResult timeline,
            List<Integer> originalFrameIndices,
            VisualTimelineResult repairedTimeline) {
        if (timeline == null || timeline.getObservations() == null
                || repairedTimeline == null || repairedTimeline.getObservations() == null
                || originalFrameIndices == null
                || repairedTimeline.getObservations().size() != originalFrameIndices.size()) {
            throw new IllegalArgumentException("invalid visual repair result");
        }
        for (VisualObservation repaired : repairedTimeline.getObservations()) {
            Integer repairIndex = repaired == null ? null : repaired.getFrameIndex();
            if (repairIndex == null || repairIndex < 1
                    || repairIndex > originalFrameIndices.size()
                    || StringUtils.isBlank(repaired.getObservableFacts())) {
                throw new IllegalArgumentException("invalid repaired visual observation");
            }
            int originalFrameIndex = originalFrameIndices.get(repairIndex - 1);
            int position = findObservationPosition(timeline, originalFrameIndex);
            VisualObservation original = timeline.getObservations().get(position);
            if (original != null && StringUtils.isNotBlank(original.getObservableFacts())) {
                throw new IllegalArgumentException("visual repair cannot replace existing facts");
            }
            repaired.setFrameIndex(originalFrameIndex);
            timeline.getObservations().set(position, repaired);
        }
    }

    private int findObservationPosition(VisualTimelineResult timeline, int frameIndex) {
        for (int index = 0; index < timeline.getObservations().size(); index++) {
            VisualObservation observation = timeline.getObservations().get(index);
            if (observation != null && observation.getFrameIndex() != null
                    && observation.getFrameIndex() == frameIndex) {
                return index;
            }
        }
        throw new IllegalArgumentException("visual repair target is missing");
    }

    /**
     * 校验视觉模型只能引用真实送审图片及其准确时间，避免错位证据进入下游。
     */
    public void validate(VisualTimelineResult timeline) {
        if (timeline == null || timeline.getObservations() == null) {
            throw new IllegalArgumentException("visual timeline is empty");
        }
        if (timeline.getObservations().size() != frames.size()) {
            throw new IllegalArgumentException(
                    "visual timeline must contain exactly one observation per frame");
        }
        Set<Integer> referencedFrames = new HashSet<>();
        for (VisualObservation observation : timeline.getObservations()) {
            validateObservation(observation, referencedFrames);
        }
        if (referencedFrames.size() != frames.size()) {
            throw new IllegalArgumentException("visual timeline must reference every frame once");
        }
    }

    private void validateObservation(
            VisualObservation observation, Set<Integer> referencedFrames) {
        if (observation == null || observation.getFrameIndex() == null
                || observation.getFrameIndex() < 1
                || observation.getFrameIndex() > frames.size()) {
            throw new IllegalArgumentException("visual observation has invalid frame index");
        }
        int frameIndex = observation.getFrameIndex();
        String expectedTimestamp = formatTime(frames.get(frameIndex - 1).getTimestampSeconds());
        if (!expectedTimestamp.equals(observation.getTimestamp())) {
            throw new IllegalArgumentException("visual observation timestamp does not match frame");
        }
        if (StringUtils.isBlank(observation.getObservableFacts())) {
            throw new IllegalArgumentException("visual observation facts are blank");
        }
        referencedFrames.add(frameIndex);
    }

    /** 返回不含图片内容、但可定位原始帧的审计元数据。 */
    public List<JSONObject> toAuditMetadata() {
        List<JSONObject> metadata = new ArrayList<>();
        for (int index = 0; index < frames.size(); index++) {
            VisualFrameEvidence frame = frames.get(index);
            JSONObject item = new JSONObject();
            item.put("frameIndex", index + 1);
            item.put("timestamp", formatTime(frame.getTimestampSeconds()));
            item.put("path", frame.getEvidencePath());
            item.put("sha256", frame.getSha256());
            metadata.add(item);
        }
        return Collections.unmodifiableList(metadata);
    }

    /** 取得视觉模型明确标记可用于封面的真实帧时间。 */
    public Set<Integer> coverCandidateTimestamps(VisualTimelineResult timeline) {
        if (timeline == null || timeline.getObservations() == null) {
            return Collections.emptySet();
        }
        return timeline.getObservations().stream()
                .filter(item -> item != null && Boolean.TRUE.equals(item.getCoverCandidate()))
                .map(VisualObservation::getFrameIndex)
                .filter(index -> index != null && index >= 1 && index <= frames.size())
                .map(index -> frames.get(index - 1).getTimestampSeconds())
                .collect(Collectors.toSet());
    }

    /**
     * 为独立 OCR 选择有界帧集：优先保留少量封面候选，再均匀覆盖完整复核窗口。
     * 视觉模型仍查看全部帧，避免把 OCR 延迟与视觉采样密度绑定。
     */
    public List<VisualFrameEvidence> selectFramesForOcr(
            VisualTimelineResult timeline, int maximumFrames) {
        if (maximumFrames <= 0) {
            throw new IllegalArgumentException("maximum OCR frames must be positive");
        }
        if (frames.size() <= maximumFrames) {
            return frames;
        }
        Set<Integer> selectedIndices = new LinkedHashSet<>();
        List<Integer> coverIndices = coverFrameIndices(timeline);
        int coverLimit = Math.min(Math.max(1, maximumFrames / 4), coverIndices.size());
        addEvenlySpaced(selectedIndices, coverIndices, coverLimit);

        List<Integer> allIndices = new ArrayList<>();
        for (int index = 0; index < frames.size(); index++) {
            allIndices.add(index);
        }
        addEvenlySpaced(selectedIndices, allIndices,
                maximumFrames - selectedIndices.size());
        for (Integer index : allIndices) {
            if (selectedIndices.size() >= maximumFrames) {
                break;
            }
            selectedIndices.add(index);
        }
        List<Integer> ordered = new ArrayList<>(selectedIndices);
        Collections.sort(ordered);
        List<VisualFrameEvidence> selected = ordered.stream()
                .limit(maximumFrames)
                .map(frames::get)
                .collect(Collectors.toList());
        return Collections.unmodifiableList(selected);
    }

    private List<Integer> coverFrameIndices(VisualTimelineResult timeline) {
        if (timeline == null || timeline.getObservations() == null) {
            return Collections.emptyList();
        }
        return timeline.getObservations().stream()
                .filter(item -> item != null && Boolean.TRUE.equals(item.getCoverCandidate()))
                .map(VisualObservation::getFrameIndex)
                .filter(index -> index != null && index >= 1 && index <= frames.size())
                .map(index -> index - 1)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private void addEvenlySpaced(
            Set<Integer> target, List<Integer> candidates, int count) {
        if (candidates.isEmpty() || count <= 0) {
            return;
        }
        if (count == 1) {
            target.add(candidates.get(candidates.size() / 2));
            return;
        }
        for (int slot = 0; slot < count; slot++) {
            int position = (int) Math.round(
                    slot * (candidates.size() - 1D) / (count - 1D));
            target.add(candidates.get(position));
        }
    }

    private String formatTime(int seconds) {
        return String.format("%02d:%02d:%02d", seconds / 3600,
                seconds % 3600 / 60, seconds % 60);
    }
}
