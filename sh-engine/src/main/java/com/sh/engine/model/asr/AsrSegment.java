package com.sh.engine.model.asr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ASR (Automatic Speech Recognition) segment representing a transcribed portion of audio.
 *
 * @Author : caiwen
 * @Date: 2026/2/19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsrSegment {
    /**
     * Start time in seconds (relative to the video)
     */
    private int startTime;

    /**
     * End time in seconds (relative to the video)
     */
    private int endTime;

    /**
     * Transcribed text
     */
    private String text;

    /**
     * Confidence score (0.0 - 1.0), may be null if not provided by ASR provider
     */
    private Double confidence;
}
