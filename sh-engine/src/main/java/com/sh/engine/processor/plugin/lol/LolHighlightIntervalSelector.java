package com.sh.engine.processor.plugin.lol;

import cn.hutool.core.io.FileUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.model.ffmpeg.VideoDurationDetectCmd;
import com.sh.engine.model.highlight.SnapshotVideoInterval;
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

    public List<SnapshotVideoInterval> select(List<File> snapshots, List<LoLPicData> sequence) {
        List<SnapshotVideoInterval> candidates = buildCandidates(snapshots, sequence);
        return mergeIntervals(candidates);
    }

    private List<SnapshotVideoInterval> buildCandidates(List<File> snapshots, List<LoLPicData> sequence) {
        List<File> sourceVideos = snapshots.stream()
                .map(VideoFileUtil::getSourceVideoFile)
                .distinct()
                .collect(Collectors.toList());
        Map<String, File> videoByPrefix = sourceVideos.stream()
                .collect(Collectors.toMap(FileUtil::getPrefix, video -> video));
        Map<String, Double> durationByPrefix = loadVideoDurations(sourceVideos);

        int candidateLimit = TOP_INTERVAL_LIMIT * sourceVideos.size();
        PriorityQueue<SnapshotVideoInterval> bestCandidates = new PriorityQueue<>(
                candidateLimit,
                Comparator.comparingDouble(SnapshotVideoInterval::getScore));

        for (int i = 0; i < snapshots.size(); i++) {
            if (sequence.get(i).getScore() < MIN_HIGHLIGHT_SCORE) {
                continue;
            }

            SnapshotVideoInterval candidate = createCandidate(
                    snapshots.get(i), sequence, i, videoByPrefix, durationByPrefix);
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

    private SnapshotVideoInterval createCandidate(File snapshot,
                                                   List<LoLPicData> sequence,
                                                   int sequenceIndex,
                                                   Map<String, File> videoByPrefix,
                                                   Map<String, Double> durationByPrefix) {
        int snapshotIndex = VideoFileUtil.getSnapshotIndex(snapshot);
        String sourcePrefix = VideoFileUtil.getSnapshotSourceFileName(snapshot);
        File sourceVideo = videoByPrefix.get(sourcePrefix);
        double videoDuration = durationByPrefix.getOrDefault(sourcePrefix, 0.0);
        double startSecond = (snapshotIndex - 1) * SNAP_INTERVAL_SECONDS;
        double endSecond = Math.min(snapshotIndex * SNAP_INTERVAL_SECONDS, videoDuration);

        return new SnapshotVideoInterval(
                sourceVideo,
                startSecond,
                endSecond,
                sequence.get(sequenceIndex).getScore(),
                calculateKillCount(sequence, sequenceIndex));
    }

    private int calculateKillCount(List<LoLPicData> sequence, int index) {
        if (index == 0 || !sequence.get(index - 1).beValid() || !sequence.get(index).beValid()) {
            return 0;
        }
        return Math.max(0, sequence.get(index).getK() - sequence.get(index - 1).getK());
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
