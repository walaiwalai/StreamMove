package com.sh.engine.service.impl;

import com.google.common.base.Preconditions;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.model.storage.FileStatusModel;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.model.danmaku.DanmakuRecallWindow;
import com.sh.engine.model.danmaku.DanmakuTimeBucket;
import com.sh.engine.model.danmaku.SessionVideoRange;
import com.sh.engine.processor.recorder.danmu.SimpleDanmaku;
import com.sh.engine.service.DanmakuAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** 编排直播时间轴、候选排名和分析上下文的弹幕召回服务。 */
@Service
@Slf4j
public class DanmakuAnalysisServiceImpl implements DanmakuAnalysisService {
    private static final int CONTEXT_BEFORE_SIGNAL_WINDOW_SECONDS = 60;

    private final DanmakuCandidateRanker candidateRanker;

    public DanmakuAnalysisServiceImpl(DanmakuCandidateRanker candidateRanker) {
        this.candidateRanker = candidateRanker;
    }

    /**
     * 以 80 秒滑动故事窗评分，再围绕反应窗起点扩展为最多 140 秒的分析上下文。
     */
    @Override
    public List<DanmakuTimeBucket> analyzeDanmakuPeak(
            String recordPath, List<SimpleDanmaku> danmakus) {
        Preconditions.checkNotNull(recordPath, "recordPath is null");
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(danmakus), "danmakus is empty");

        FileStatusModel fileStatus = FileStatusModel.loadFromFile(recordPath);
        List<SessionVideoRange> videoRanges = buildVideoRanges(recordPath, fileStatus);
        if (videoRanges.isEmpty()) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "cannot build video ranges for danmaku highlight: " + recordPath);
        }

        List<SimpleDanmaku> orderedDanmakus = danmakus.stream()
                .filter(item -> item != null && item.getTime() >= 0)
                .sorted(Comparator.comparingDouble(SimpleDanmaku::getTime))
                .collect(Collectors.toList());
        int timelineEnd = videoRanges.get(videoRanges.size() - 1).getEndSecond();
        List<DanmakuRecallWindow> selectedWindows = candidateRanker.rank(
                orderedDanmakus, timelineEnd, videoRanges);
        return buildCandidateBuckets(
                selectedWindows, orderedDanmakus, videoRanges);
    }

    private List<SessionVideoRange> buildVideoRanges(
            String recordPath, FileStatusModel fileStatus) {
        List<SessionVideoRange> ranges = new ArrayList<>();
        if (fileStatus == null || fileStatus.getMetaMap() == null) {
            return ranges;
        }
        List<File> orderedVideos = VideoFileUtil.listIndexedMp4Files(recordPath);

        long baseTimestamp = orderedVideos.stream()
                .map(fileStatus.getMetaMap()::get)
                .filter(metadata -> metadata != null
                        && metadata.getRecordStartTimeStamp() > 0)
                .mapToLong(FileStatusModel.VideoMetaInfo::getRecordStartTimeStamp)
                .min()
                .orElse(0L);
        int fallbackStartSecond = 0;
        for (File sourceVideo : orderedVideos) {
            FileStatusModel.VideoMetaInfo metadata =
                    fileStatus.getMetaMap().get(sourceVideo.getName());
            if (metadata == null || metadata.getDurationSecond() <= 0) {
                continue;
            }
            int startSecond = resolveVideoStart(
                    metadata, baseTimestamp, fallbackStartSecond);
            int endSecond = startSecond + metadata.getDurationSecond();
            ranges.add(new SessionVideoRange(startSecond, endSecond));
            fallbackStartSecond = endSecond;
        }
        return ranges;
    }

    private int resolveVideoStart(
            FileStatusModel.VideoMetaInfo metadata,
            long baseTimestamp,
            int fallbackStartSecond) {
        if (baseTimestamp <= 0 || metadata.getRecordStartTimeStamp() < baseTimestamp) {
            return fallbackStartSecond;
        }
        long relativeStart = metadata.getRecordStartTimeStamp() - baseTimestamp;
        return relativeStart <= Integer.MAX_VALUE
                ? (int) relativeStart : fallbackStartSecond;
    }

    private List<DanmakuTimeBucket> buildCandidateBuckets(
            List<DanmakuRecallWindow> windows,
            List<SimpleDanmaku> danmakus,
            List<SessionVideoRange> videoRanges) {
        List<DanmakuTimeBucket> buckets = new ArrayList<>();
        for (DanmakuRecallWindow window : windows) {
            SessionVideoRange videoRange = findVideoRange(
                    window.centerSecond(), videoRanges);
            if (videoRange == null) {
                continue;
            }
            int start = videoRange.limitContextStart(
                    window.getStartSecond() - CONTEXT_BEFORE_SIGNAL_WINDOW_SECONDS);
            int end = videoRange.limitContextEnd(window.getEndSecond());
            List<SimpleDanmaku> contextDanmakus = danmakus.stream()
                    .filter(item -> item.getTime() >= start && item.getTime() < end)
                    .collect(Collectors.toList());
            DanmakuTimeBucket bucket = new DanmakuTimeBucket();
            bucket.setStartTime(start);
            bucket.setEndTime(end);
            bucket.setCount(contextDanmakus.size());
            bucket.setDanmakus(contextDanmakus);
            bucket.setSignalStartTime(window.getStartSecond());
            bucket.setSignalEndTime(window.getEndSecond());
            bucket.setRecallScore(window.getScore());
            buckets.add(bucket);
            log.info("danmaku story candidate, range: {}-{}s, score: {}, danmaku: {}",
                    start, end, window.getScore(), contextDanmakus.size());
        }
        return buckets;
    }

    private SessionVideoRange findVideoRange(
            int second, List<SessionVideoRange> ranges) {
        for (SessionVideoRange range : ranges) {
            if (range.contains(second)) {
                return range;
            }
        }
        return null;
    }
}
