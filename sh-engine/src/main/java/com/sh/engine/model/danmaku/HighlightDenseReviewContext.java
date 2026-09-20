package com.sh.engine.model.danmaku;

import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.processor.recorder.danmu.SimpleDanmaku;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** 单个召回候选在密集复核阶段共享的不可变上下文。 */
public final class HighlightDenseReviewContext {
    private final File videoFile;
    private final String streamerName;
    private final int sessionToFileOffset;
    private final String segmentKey;
    private final String finalReviewKey;
    private final List<AsrSegment> asrSegments;
    private final List<SimpleDanmaku> danmakus;

    public HighlightDenseReviewContext(
            File videoFile,
            String streamerName,
            int sessionToFileOffset,
            String segmentKey,
            String finalReviewKey,
            List<AsrSegment> asrSegments,
            List<SimpleDanmaku> danmakus) {
        if (videoFile == null || streamerName == null
                || segmentKey == null || finalReviewKey == null) {
            throw new IllegalArgumentException("invalid dense review context");
        }
        this.videoFile = videoFile;
        this.streamerName = streamerName;
        this.sessionToFileOffset = sessionToFileOffset;
        this.segmentKey = segmentKey;
        this.finalReviewKey = finalReviewKey;
        this.asrSegments = immutableCopy(asrSegments);
        this.danmakus = immutableCopy(danmakus);
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getStreamerName() {
        return streamerName;
    }

    public String getSegmentKey() {
        return segmentKey;
    }

    public int getSessionToFileOffset() {
        return sessionToFileOffset;
    }

    public String reviewKey(HighlightClipRange range) {
        return finalReviewKey + "-focus-"
                + range.getStartSecond() + "-" + range.getEndSecond();
    }

    /** 只提供与当前局部事件窗相交的语音证据。 */
    public List<AsrSegment> asrInside(HighlightClipRange range) {
        return asrSegments.stream()
                .filter(segment -> segment != null
                        && segment.getStartTime() < range.getEndSecond()
                        && segment.getEndTime() >= range.getStartSecond())
                .collect(Collectors.toList());
    }

    /** 将直播时间轴弹幕换算到当前源文件的局部时间轴。 */
    public List<SimpleDanmaku> danmakusInside(HighlightClipRange range) {
        return danmakus.stream()
                .filter(item -> item != null)
                .filter(item -> range.contains(
                        (int) item.getTime() - sessionToFileOffset))
                .collect(Collectors.toList());
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return values == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
