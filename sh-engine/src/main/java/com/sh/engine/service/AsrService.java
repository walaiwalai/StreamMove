package com.sh.engine.service;

import com.sh.engine.model.asr.AsrSegment;

import java.io.File;
import java.util.List;

/**
 * ASR (Automatic Speech Recognition) Service interface for audio transcription.
 * <p>
 * This service provides methods to transcribe audio from video files.
 * Multiple implementations can be provided for different ASR providers
 * (e.g., Aliyun, Xunfei, Whisper).
 *
 * @Author : caiwen
 * @Date: 2026/2/19
 */
public interface AsrService {

    /**
     * Transcribe a segment of the video file.
     * <p>
     * Extracts audio from the specified time range and returns transcribed text segments.
     *
     * @param videoFile    the video file to transcribe
     * @param startSeconds start time in seconds (inclusive)
     * @param endSeconds   end time in seconds (exclusive)
     * @return list of transcribed segments, ordered by time
     */
    List<AsrSegment> transcribeSegment(File videoFile, int startSeconds, int endSeconds);
}
