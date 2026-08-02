package com.sh.engine.processor.plugin.lol;

import cn.hutool.core.io.FileUtil;
import com.sh.engine.model.ffmpeg.VideoDurationDetectCmd;
import com.sh.engine.model.highlight.SnapshotVideoInterval;
import com.sh.engine.model.highlight.lol.LolKdaTimelinePoint;
import com.sh.engine.model.highlight.lol.LoLPicData;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.stream.Collectors;

import static com.sh.engine.constant.RecordConstant.POTENTIAL_INTERVAL_POST_N;
import static com.sh.engine.constant.RecordConstant.POTENTIAL_INTERVAL_PRE_N;
import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.MIN_HIGHLIGHT_SCORE;
import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.SNAP_INTERVAL_SECONDS;
import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.TOP_INTERVAL_LIMIT;

/**
 * 从已评分的 KDA 序列中挑选、扩展并合并精彩视频区间。
 */
@Component
public class LolHighlightIntervalSelector {

    public List<SnapshotVideoInterval> select(List<LolKdaTimelinePoint> timeline) {
        List<SnapshotVideoInterval> candidates = buildCandidates(timeline);
        return mergeIntervals(candidates);
    }

    private List<SnapshotVideoInterval> buildCandidates(List<LolKdaTimelinePoint> timeline) {
        List<File> sourceVideos = timeline.stream()
                .map(LolKdaTimelinePoint::getSourceVideo)
                .distinct()
                .collect(Collectors.toList());
        Map<String, Double> durationByPrefix = loadVideoDurations(sourceVideos);

        int candidateLimit = TOP_INTERVAL_LIMIT * sourceVideos.size();
        PriorityQueue<SnapshotVideoInterval> bestCandidates = new PriorityQueue<>(
                candidateLimit,
                Comparator.comparingDouble(SnapshotVideoInterval::getScore));

        for (int i = 0; i < timeline.size(); i++) {
            if (timeline.get(i).getPicData().getScore() < MIN_HIGHLIGHT_SCORE) {
                continue;
            }

            SnapshotVideoInterval candidate = createCandidate(timeline, i, durationByPrefix);
            keepBestCandidate(bestCandidates, candidate, candidateLimit);
        }

        List<SnapshotVideoInterval> candidates = new ArrayList<>(bestCandidates);
        expandCandidates(candidates, durationByPrefix);
        return candidates;
    }

    private Map<String, Double> loadVideoDurations(List<File> videos) {
        return videos.stream().collect(Collectors.toMap(FileUtil::getPrefix, video -> {
            VideoDurationDetectCmd command = new VideoDurationDetectCmd(video.getAbsolutePath());
            command.execute(100);
            return command.getDurationSeconds();
        }));
    }

    private SnapshotVideoInterval createCandidate(List<LolKdaTimelinePoint> timeline,
                                                   int timelineIndex,
                                                   Map<String, Double> durationByPrefix) {
        LolKdaTimelinePoint point = timeline.get(timelineIndex);
        File sourceVideo = point.getSourceVideo();
        String sourcePrefix = FileUtil.getPrefix(sourceVideo);
        double videoDuration = durationByPrefix.getOrDefault(sourcePrefix, 0.0);
        double startSecond = point.getSecondFromVideoStart();
        double endSecond = Math.min(startSecond + SNAP_INTERVAL_SECONDS, videoDuration);

        return new SnapshotVideoInterval(
                sourceVideo,
                startSecond,
                endSecond,
                point.getPicData().getScore(),
                calculateKillCount(timeline, timelineIndex));
    }

    private int calculateKillCount(List<LolKdaTimelinePoint> timeline, int index) {
        LoLPicData current = timeline.get(index).getPicData();
        if (index == 0 || !timeline.get(index - 1).getPicData().beValid() || !current.beValid()) {
            return 0;
        }
        return Math.max(
                0,
                current.getK() - timeline.get(index - 1).getPicData().getK());
    }

    private void keepBestCandidate(PriorityQueue<SnapshotVideoInterval> candidates,
                                   SnapshotVideoInterval candidate,
                                   int candidateLimit) {
        if (candidates.size() < candidateLimit) {
            candidates.add(candidate);
        } else if (candidate.getScore() > candidates.peek().getScore()) {
            candidates.poll();
            candidates.add(candidate);
        }
    }

    private void expandCandidates(List<SnapshotVideoInterval> candidates,
                                  Map<String, Double> durationByPrefix) {
        for (SnapshotVideoInterval candidate : candidates) {
            String sourcePrefix = FileUtil.getPrefix(candidate.getFromVideo());
            double videoDuration = durationByPrefix.getOrDefault(sourcePrefix, 0.0);
            double expandedStart = Math.max(
                    0.0,
                    candidate.getSecondFromVideoStart()
                            - POTENTIAL_INTERVAL_PRE_N * SNAP_INTERVAL_SECONDS);
            double expandedEnd = Math.min(
                    candidate.getSecondToVideoEnd()
                            + POTENTIAL_INTERVAL_POST_N * SNAP_INTERVAL_SECONDS,
                    videoDuration);
            candidate.setSecondFromVideoStart(expandedStart);
            candidate.setSecondToVideoEnd(expandedEnd);
        }
    }

    List<SnapshotVideoInterval> mergeIntervals(List<SnapshotVideoInterval> rawIntervals) {
        Map<File, List<SnapshotVideoInterval>> intervalsByVideo = rawIntervals.stream()
                .collect(Collectors.groupingBy(SnapshotVideoInterval::getFromVideo));

        List<SnapshotVideoInterval> mergedIntervals = new ArrayList<>();
        for (List<SnapshotVideoInterval> videoIntervals : intervalsByVideo.values()) {
            videoIntervals.sort(Comparator.comparingDouble(
                    SnapshotVideoInterval::getSecondFromVideoStart));
            mergeSingleVideoIntervals(videoIntervals, mergedIntervals);
        }

        return mergedIntervals.stream()
                .sorted(Comparator.comparingInt(interval -> (int) (interval.getScore() * -100f)))
                .limit(TOP_INTERVAL_LIMIT)
                .sorted()
                .collect(Collectors.toList());
    }

    private void mergeSingleVideoIntervals(List<SnapshotVideoInterval> source,
                                           List<SnapshotVideoInterval> target) {
        List<SnapshotVideoInterval> merged = new ArrayList<>();
        for (SnapshotVideoInterval interval : source) {
            if (merged.isEmpty()
                    || merged.get(merged.size() - 1).getSecondToVideoEnd()
                    < interval.getSecondFromVideoStart()) {
                merged.add(interval.copy());
            } else {
                int lastIndex = merged.size() - 1;
                merged.set(lastIndex, merged.get(lastIndex).merge(interval));
            }
        }
        target.addAll(merged);
    }
}
