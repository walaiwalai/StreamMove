package com.sh.engine.model.danmaku;

import lombok.Data;

/**
 * Result of highlight analysis for a video segment.
 * <p>
 * Contains AI judgment on whether the segment is a highlight,
 * along with scoring, reasoning, and suggested clip times.
 */
@Data
public class HighlightAnalysisResult {
    /**
     * Whether this segment is considered a highlight moment
     */
    private boolean highlight;

    /**
     * Highlight score (1-10), higher means more interesting
     */
    private int score;

    /**
     * Reason for the judgment
     */
    private String reason;

    /**
     * Suggested exact clip start time (HH:mm:ss format)
     */
    private String exactClipStart;

    /**
     * Suggested exact clip end time (HH:mm:ss format)
     */
    private String exactClipEnd;

    /**
     * Suggested title for the highlight clip
     */
    private String suggestedTitle;
}
