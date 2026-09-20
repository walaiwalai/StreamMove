package com.sh.engine.model.danmaku;

import lombok.Data;

import java.util.List;

/**
 * Result of highlight analysis for a video segment.
 */
@Data
public class HighlightAnalysisResult {
    /** Whether this candidate contains a publishable story. */
    private Boolean highlight;

    /**
     * Highlight score (0-100), higher means more interesting
     */
    private int score;

    /**
     * Reason for the judgment
     */
    private String reason;

    /** One-sentence description that a viewer can understand without context. */
    private String oneSentenceStory;

    /** What establishes the situation before the key event. */
    private String setup;

    /** The decisive action or change. */
    private String action;

    /** The visible or audible outcome. */
    private String outcome;

    /** Streamer or audience reaction after the outcome. */
    private String reaction;

    /** Estimated delay from the video event to the danmaku response. */
    private Integer danmakuDelaySeconds;

    /** Timestamped facts used to support the decision. */
    private List<String> evidence;

    /** Timestamped ASR/OCR/VISION facts that directly support the proposed title. */
    private List<HighlightEvidenceReference> titleEvidence;

    /** Timestamped ASR/OCR/VISION facts that directly support the key action. */
    private List<HighlightEvidenceReference> actionEvidence;

    /** Timestamped ASR/OCR/VISION facts that directly support the stated outcome. */
    private List<HighlightEvidenceReference> outcomeEvidence;

    /** Main uncertainty or reason that this candidate may be rejected. */
    private String risk;

    /** Independent quality axes; a complete outcome alone is not enough to pass. */
    private HighlightQualityAssessment quality;

    /**
     * Suggested exact clip start time (HH:mm:ss format)
     */
    private String exactClipStart;

    /**
     * Suggested exact clip end time (HH:mm:ss format)
     */
    private String exactClipEnd;

    /**
     * Recommended source-video frame for the cover (HH:mm:ss format).
     */
    private String coverTimestamp;

    /**
     * Suggested title for the highlight clip
     */
    private String suggestedTitle;
}
