package com.sh.engine.model.danmaku;

import lombok.Data;

import java.io.File;

/**
 * Represents a highlight segment identified by AI analysis
 */
@Data
public class HighlightSegment {
    private int startTime;
    private int endTime;
    private int emotionScore;
    private File videoFile;
    private double videoOffset;
    private String asrText;
    private boolean isHighlight;
    private int score;
    private String reason;
    private String suggestedTitle;
    private String exactStartTime;
    private String exactEndTime;
}
