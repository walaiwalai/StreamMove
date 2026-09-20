package com.sh.engine.model.danmaku;

import com.sh.engine.model.highlight.VideoInterval;

import java.io.File;

/**
 * 已通过多模态证据校验、可进入产物阶段的单个高光区间。
 */
public final class ConfirmedHighlight {
    private static final double DUPLICATE_CLIP_OVERLAP_RATIO = 0.5D;
    private static final int SAME_EVENT_ANCHOR_DISTANCE_SECONDS = 10;

    private final File videoFile;
    private final int startTime;
    private final int endTime;
    private final int coverTimestamp;
    private final int eventAnchorTimestamp;
    private final int score;
    private final int audienceValue;
    private final int contentDensity;
    private final String reason;
    private final String suggestedTitle;
    private final String coverText;

    public ConfirmedHighlight(
            File videoFile,
            int startTime,
            int endTime,
            int coverTimestamp,
            int score,
            int audienceValue,
            int contentDensity,
            String reason,
            String suggestedTitle,
            String coverText) {
        this(videoFile, startTime, endTime, coverTimestamp, coverTimestamp,
                score, audienceValue, contentDensity, reason, suggestedTitle, coverText);
    }

    public ConfirmedHighlight(
            File videoFile,
            int startTime,
            int endTime,
            int coverTimestamp,
            int eventAnchorTimestamp,
            int score,
            int audienceValue,
            int contentDensity,
            String reason,
            String suggestedTitle,
            String coverText) {
        if (videoFile == null || !videoFile.isFile()
                || startTime < 0 || endTime <= startTime
                || coverTimestamp < startTime || coverTimestamp >= endTime
                || eventAnchorTimestamp < startTime || eventAnchorTimestamp >= endTime
                || score < 0 || score > 100
                || audienceValue < 0 || audienceValue > 100
                || contentDensity < 0 || contentDensity > 100
                || suggestedTitle == null || suggestedTitle.trim().isEmpty()
                || coverText == null || coverText.trim().isEmpty()) {
            throw new IllegalArgumentException("invalid confirmed highlight");
        }
        this.videoFile = videoFile;
        this.startTime = startTime;
        this.endTime = endTime;
        this.coverTimestamp = coverTimestamp;
        this.eventAnchorTimestamp = eventAnchorTimestamp;
        this.score = score;
        this.audienceValue = audienceValue;
        this.contentDensity = contentDensity;
        this.reason = reason;
        this.suggestedTitle = suggestedTitle;
        this.coverText = coverText;
    }

    public VideoInterval toVideoInterval() {
        return new VideoInterval(videoFile, startTime, endTime);
    }

    /**
     * 判断两个精剪是否属于同一连续事件。高重叠直接视为重复；短重叠还要求
     * 已验真结果锚点接近，避免只因较长铺垫相交就合并不同事件。
     */
    public boolean isSameEventAs(ConfirmedHighlight other) {
        if (other == null || !videoFile.equals(other.videoFile)) {
            return false;
        }
        int overlap = Math.max(0,
                Math.min(endTime, other.endTime)
                        - Math.max(startTime, other.startTime));
        if (overlap <= 0) {
            return false;
        }
        int shorterDuration = Math.min(
                endTime - startTime, other.endTime - other.startTime);
        if (overlap / (double) shorterDuration >= DUPLICATE_CLIP_OVERLAP_RATIO) {
            return true;
        }
        return Math.abs(eventAnchorTimestamp - other.eventAnchorTimestamp)
                <= SAME_EVENT_ANCHOR_DISTANCE_SECONDS;
    }

    public File getVideoFile() {
        return videoFile;
    }

    public int getStartTime() {
        return startTime;
    }

    public int getEndTime() {
        return endTime;
    }

    public int getCoverTimestamp() {
        return coverTimestamp;
    }

    public int getEventAnchorTimestamp() {
        return eventAnchorTimestamp;
    }

    public int getScore() {
        return score;
    }

    public int getAudienceValue() {
        return audienceValue;
    }

    public int getContentDensity() {
        return contentDensity;
    }

    public String getReason() {
        return reason;
    }

    public String getSuggestedTitle() {
        return suggestedTitle;
    }

    public String getCoverText() {
        return coverText;
    }
}
