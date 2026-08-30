package com.sh.engine.processor.plugin.highlight;

import com.sh.engine.model.ffmpeg.VideoDurationDetectCmd;
import com.sh.engine.model.highlight.core.HighlightCutPolicy;
import com.sh.engine.model.highlight.core.HighlightEvent;
import com.sh.engine.model.highlight.core.ScoredVideoInterval;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 将游戏识别事件规划为可输出的视频区间。
 *
 * <p>规划分为两个不同层次：先按事件时间间隔形成评分簇；再将通过门槛的簇扩展为
 * 视频区间，并统一归并发生重叠的区间，避免同一段画面重复输出。最后以归并后的区间
 * 为单位选取 TopN。</p>
 */
@Component
public class HighlightIntervalPlanner {

    /**
     * 规划高光区间，并探测事件所属视频的实际时长以约束区间边界。
     *
     * @param timeline 已去除识别层重复项的事件时间线
     * @param policy   事件评分、成簇、区间扩展及筛选策略
     * @return 按原视频时间顺序排列的高光区间
     */
    public List<ScoredVideoInterval> select(List<HighlightEvent> timeline,
                                            HighlightCutPolicy policy) {
        List<HighlightEvent> sortedTimeline = normalizeTimeline(timeline);
        if (sortedTimeline.isEmpty()) {
            return Collections.emptyList();
        }

        Map<File, Double> durationByVideo = loadVideoDurations(sortedTimeline);
        return selectNormalized(sortedTimeline, policy, durationByVideo);
    }

    /**
     * 对已规范化的时间线执行事件成簇、候选归并和 TopN 筛选。
     */
    private List<ScoredVideoInterval> selectNormalized(
            List<HighlightEvent> sortedTimeline,
            HighlightCutPolicy policy,
            Map<File, Double> durationByVideo) {
        List<List<HighlightEvent>> eventClusters = clusterEvents(
                sortedTimeline, policy.getClusterGapSeconds());
        List<ScoredVideoInterval> candidates = buildAndCoalesceQualifiedIntervals(
                eventClusters, policy, durationByVideo);
        return selectTopIntervals(candidates, policy.getTopN());
    }

    /**
     * 丢弃无效列表元素并统一按分片及时间排序。
     */
    private List<HighlightEvent> normalizeTimeline(List<HighlightEvent> timeline) {
        if (CollectionUtils.isEmpty(timeline)) {
            return Collections.emptyList();
        }
        return timeline.stream()
                .filter(Objects::nonNull)
                .filter(event -> event.getSourceVideo() != null)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * 将事件簇转换为合格候选，并统一归并扩展后发生重叠的区间。
     */
    private List<ScoredVideoInterval> buildAndCoalesceQualifiedIntervals(
            List<List<HighlightEvent>> eventClusters,
            HighlightCutPolicy policy,
            Map<File, Double> durationByVideo) {
        List<ScoredVideoInterval> candidates = new ArrayList<>();
        for (List<HighlightEvent> events : eventClusters) {
            ScoredVideoInterval interval = buildInterval(
                    events, policy, durationByVideo);
            if (interval.getScore() >= policy.getMinScore()
                    && interval.getPositiveCount() >= policy.getMinPositiveCount()) {
                candidates.add(interval);
            }
        }

        candidates.sort(Comparator.naturalOrder());
        List<ScoredVideoInterval> merged = new ArrayList<>();
        for (ScoredVideoInterval interval : candidates) {
            if (merged.isEmpty()) {
                merged.add(interval);
                continue;
            }
            ScoredVideoInterval previous = merged.get(merged.size() - 1);
            boolean overlaps = previous.getFromVideo().equals(interval.getFromVideo())
                    && previous.getSecondToVideoEnd() >= interval.getSecondFromVideoStart();
            if (!overlaps) {
                merged.add(interval);
                continue;
            }

            List<HighlightEvent> events = new ArrayList<>(previous.getEvents());
            events.addAll(interval.getEvents());
            events.sort(Comparator.naturalOrder());
            merged.set(merged.size() - 1, new ScoredVideoInterval(
                    previous.getFromVideo(),
                    Math.min(previous.getSecondFromVideoStart(), interval.getSecondFromVideoStart()),
                    Math.max(previous.getSecondToVideoEnd(), interval.getSecondToVideoEnd()),
                    policy.calculateClusterScore(events),
                    events.stream().mapToInt(event -> Math.max(0, event.getPositiveCount())).sum(),
                    events.stream().mapToInt(event -> Math.max(0, event.getNegativeCount())).sum(),
                    events));
        }
        return merged;
    }

    /**
     * 先按分数保留 TopN，再恢复到原视频时间顺序供后续拼接。
     */
    private List<ScoredVideoInterval> selectTopIntervals(List<ScoredVideoInterval> source,
                                                          int topN) {
        List<ScoredVideoInterval> candidates = new ArrayList<>(source);
        candidates.sort(Comparator.comparingDouble(ScoredVideoInterval::getScore).reversed()
                .thenComparing(Comparator.naturalOrder()));
        if (candidates.size() > topN) {
            candidates = new ArrayList<>(candidates.subList(0, topN));
        }
        candidates.sort(Comparator.naturalOrder());
        return candidates;
    }

    /**
     * 每个源视频只执行一次时长探测，供前后扩展时限制结束边界。
     */
    private Map<File, Double> loadVideoDurations(List<HighlightEvent> timeline) {
        Map<File, Double> durations = new HashMap<>();
        for (HighlightEvent event : timeline) {
            File sourceVideo = event.getSourceVideo();
            if (sourceVideo == null || durations.containsKey(sourceVideo)) {
                continue;
            }
            VideoDurationDetectCmd command = new VideoDurationDetectCmd(sourceVideo.getAbsolutePath());
            command.execute(100);
            durations.put(sourceVideo, command.getDurationSeconds());
        }
        return durations;
    }

    /**
     * 按同一视频中相邻事件的时间差形成评分簇；该步骤不考虑区间前后扩展量。
     */
    private List<List<HighlightEvent>> clusterEvents(List<HighlightEvent> sorted,
                                                      int gapSeconds) {
        List<List<HighlightEvent>> clusters = new ArrayList<>();
        List<HighlightEvent> current = null;
        HighlightEvent previous = null;
        for (HighlightEvent event : sorted) {
            boolean sameCluster = previous != null
                    && previous.getSourceVideo().equals(event.getSourceVideo())
                    && event.getSecondFromVideoStart() - previous.getSecondFromVideoStart()
                    <= gapSeconds;
            if (!sameCluster) {
                current = new ArrayList<>();
                clusters.add(current);
            }
            current.add(event);
            previous = event;
        }
        return clusters;
    }

    /**
     * 将一个评分簇扩展为视频区间，并聚合其分数和正负事件数。
     */
    private ScoredVideoInterval buildInterval(List<HighlightEvent> events,
                                              HighlightCutPolicy policy,
                                              Map<File, Double> durationByVideo) {
        HighlightEvent first = events.get(0);
        HighlightEvent last = events.get(events.size() - 1);
        double duration = durationByVideo.getOrDefault(first.getSourceVideo(), 0.0);
        double start = Math.max(0.0, first.getSecondFromVideoStart() - policy.getPreRollSeconds());
        double end = last.getSecondFromVideoStart() + policy.getPostRollSeconds();
        if (duration > 0) {
            end = Math.min(end, duration);
        }
        return new ScoredVideoInterval(
                first.getSourceVideo(),
                start,
                end,
                policy.calculateClusterScore(events),
                events.stream().mapToInt(event -> Math.max(0, event.getPositiveCount())).sum(),
                events.stream().mapToInt(event -> Math.max(0, event.getNegativeCount())).sum(),
                events);
    }
}
