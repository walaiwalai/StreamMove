package com.sh.engine.model.ffmpeg;

import lombok.extern.slf4j.Slf4j;

import java.io.File;

/**
 * FFmpeg command for extracting audio from video segments.
 * <p>
 * Used for ASR (Automatic Speech Recognition) processing.
 * Outputs WAV format with 16kHz sample rate, mono channel, 16-bit PCM.
 *
 * @Author : caiwen
 * @Date: 2026/2/19
 */
@Slf4j
public class AudioExtractCmd extends AbstractCmd {

    private final File inputFile;
    private final File outputFile;
    private final int startSeconds;
    private final int durationSeconds;

    /**
     * Creates an audio extraction command.
     *
     * @param inputFile       the input video file
     * @param outputFile      the output audio file (should end with .wav)
     * @param startSeconds    start time in seconds
     * @param durationSeconds duration in seconds
     */
    public AudioExtractCmd(File inputFile, File outputFile, int startSeconds, int durationSeconds) {
        super(buildCommand(inputFile, outputFile, startSeconds, durationSeconds));
        this.inputFile = inputFile;
        this.outputFile = outputFile;
        this.startSeconds = startSeconds;
        this.durationSeconds = durationSeconds;
    }

    private static String buildCommand(File inputFile, File outputFile, int startSeconds, int durationSeconds) {
        // FFmpeg command format:
        // ffmpeg -i input.mp4 -ss startSeconds -t duration -vn -ar 16000 -ac 1 -acodec pcm_s16le -y output.wav
        // -vn: disable video
        // -ar 16000: sample rate 16kHz (recommended by Alibaba Cloud ASR)
        // -ac 1: mono channel
        // -acodec pcm_s16le: 16-bit PCM encoding
        // -y: overwrite output file
        return String.format(
                "ffmpeg -i \"%s\" -ss %d -t %d -vn -ar 16000 -ac 1 -acodec pcm_s16le -y \"%s\"",
                inputFile.getAbsolutePath(),
                startSeconds,
                durationSeconds,
                outputFile.getAbsolutePath()
        );
    }

    @Override
    protected void processOutputLine(String line) {
        log.debug("AudioExtractCmd output: {}", line);
    }

    @Override
    protected void processErrorLine(String line) {
        // FFmpeg outputs progress info to stderr
        log.debug("AudioExtractCmd error: {}", line);
    }

    public boolean isSuccess() {
        return isNormalExit() && outputFile.exists() && outputFile.length() > 0;
    }
}
