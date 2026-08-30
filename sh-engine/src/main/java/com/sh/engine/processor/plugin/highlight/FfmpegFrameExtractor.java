package com.sh.engine.processor.plugin.highlight;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.highlight.core.InMemoryVideoFrame;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 通用 FFmpeg 内存取帧器，支持指定时间单帧和固定间隔连续帧。
 */
@Component
public class FfmpegFrameExtractor {
    private static final int FRAME_EXTRACTION_TIMEOUT_SECONDS = 60;
    private static final int READER_FINISH_TIMEOUT_SECONDS = 30;
    private static final int JPEG_START_MARKER = 0xD8;
    private static final int JPEG_END_MARKER = 0xD9;
    private static final int MARKER_PREFIX = 0xFF;
    private static final int MAX_FRAME_BYTES = 10 * 1024 * 1024;
    private static final Semaphore FFMPEG_PERMITS = new Semaphore(2, true);

    /**
     * 流式抽取视频区域。调用方消费速度较慢时，FFmpeg 会通过管道自然背压。
     *
     * @param sourceVideo 源视频
     * @param intervalSeconds 采样间隔秒数
     * @param cropExpression FFmpeg crop 过滤器
     * @param timeoutSeconds 整个 FFmpeg 进程超时
     * @param frameConsumer 内存帧消费者
     * @return 实际输出的帧数
     */
    public int stream(File sourceVideo,
                      int intervalSeconds,
                      String cropExpression,
                      long timeoutSeconds,
                      FrameConsumer frameConsumer) {
        validate(sourceVideo, intervalSeconds, cropExpression, timeoutSeconds, frameConsumer);
        acquirePermit();
        try {
            return streamWithPermit(
                    sourceVideo, intervalSeconds, cropExpression, timeoutSeconds, frameConsumer);
        } finally {
            FFMPEG_PERMITS.release();
        }
    }

    private int streamWithPermit(File sourceVideo,
                                 int intervalSeconds,
                                 String cropExpression,
                                 long timeoutSeconds,
                                 FrameConsumer frameConsumer) {
        Process process = startProcess(sourceVideo, intervalSeconds, cropExpression);
        ThreadPoolExecutor readerExecutor = createReaderExecutor();
        Future<Integer> readerFuture = readerExecutor.submit(
                () -> readFrames(process.getInputStream(), intervalSeconds, frameConsumer));
        try {
            awaitProcess(process, timeoutSeconds, sourceVideo);
            return readerFuture.get(READER_FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw analysisError("interrupted while streaming frames: " + sourceVideo, e);
        } catch (ExecutionException e) {
            throw analysisError("cannot read FFmpeg frame stream: " + sourceVideo, e.getCause());
        } catch (TimeoutException e) {
            throw analysisError("timeout while finishing FFmpeg frame reader: " + sourceVideo, e);
        } finally {
            readerFuture.cancel(true);
            readerExecutor.shutdownNow();
            process.destroyForcibly();
        }
    }

    /**
     * 按指定时间随机抽取单张内存 JPEG；快速 seek 失败时使用预解码重试。
     *
     * @param sourceVideo 源视频
     * @param timestampSeconds 视频内时间戳
     * @param cropExpression FFmpeg 画面过滤器
     * @return 未落盘的 JPEG 帧
     */
    public InMemoryVideoFrame extract(File sourceVideo,
                                      int timestampSeconds,
                                      String cropExpression) {
        if (sourceVideo == null || !sourceVideo.isFile()
                || timestampSeconds < 0 || StringUtils.isBlank(cropExpression)) {
            throw new IllegalArgumentException("invalid FFmpeg frame extraction arguments");
        }
        acquirePermit();
        try {
            byte[] jpegData = tryExtract(sourceVideo, timestampSeconds, cropExpression, 0);
            if (jpegData == null) {
                jpegData = tryExtract(sourceVideo, timestampSeconds, cropExpression, 5);
            }
            if (jpegData == null) {
                throw analysisError("cannot extract frame at " + timestampSeconds
                        + "s from " + sourceVideo.getAbsolutePath(), null);
            }
            return new InMemoryVideoFrame(timestampSeconds, jpegData);
        } finally {
            FFMPEG_PERMITS.release();
        }
    }

    private byte[] tryExtract(File sourceVideo,
                              int timestampSeconds,
                              String cropExpression,
                              int preRollSeconds) {
        Process process = startSingleFrameProcess(
                sourceVideo, timestampSeconds, cropExpression, preRollSeconds);
        ThreadPoolExecutor readerExecutor = createReaderExecutor();
        Future<byte[]> readerFuture = readerExecutor.submit(
                () -> readSingleFrame(process.getInputStream()));
        try {
            awaitProcess(process, FRAME_EXTRACTION_TIMEOUT_SECONDS, sourceVideo);
            return readerFuture.get(READER_FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw analysisError("interrupted while extracting frame: " + sourceVideo, e);
        } catch (ExecutionException e) {
            throw analysisError("cannot read FFmpeg frame: " + sourceVideo, e.getCause());
        } catch (TimeoutException e) {
            throw analysisError("timeout while reading FFmpeg frame: " + sourceVideo, e);
        } catch (RuntimeException e) {
            return null;
        } finally {
            readerFuture.cancel(true);
            readerExecutor.shutdownNow();
            process.destroyForcibly();
        }
    }

    private Process startProcess(File sourceVideo,
                                 int intervalSeconds,
                                 String cropExpression) {
        List<String> command = Arrays.asList(
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-i", sourceVideo.getAbsolutePath(), "-an",
                "-vf", String.format(Locale.ROOT, "fps=1/%d,%s", intervalSeconds, cropExpression),
                "-q:v", "5", "-f", "image2pipe", "-vcodec", "mjpeg", "pipe:1");
        try {
            return new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
        } catch (IOException e) {
            throw analysisError("cannot start FFmpeg frame stream: " + sourceVideo, e);
        }
    }

    private Process startSingleFrameProcess(File sourceVideo,
                                            int timestampSeconds,
                                            String cropExpression,
                                            int preRollSeconds) {
        int inputSeekSecond = Math.max(0, timestampSeconds - preRollSeconds);
        int outputSeekSecond = timestampSeconds - inputSeekSecond;
        List<String> command = new ArrayList<>();
        command.addAll(Arrays.asList(
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-ss", String.valueOf(inputSeekSecond), "-accurate_seek",
                "-i", sourceVideo.getAbsolutePath()));
        if (outputSeekSecond > 0) {
            command.addAll(Arrays.asList("-ss", String.valueOf(outputSeekSecond)));
        }
        command.addAll(Arrays.asList(
                "-an", "-vf", cropExpression, "-frames:v", "1", "-q:v", "5",
                "-f", "image2pipe", "-vcodec", "mjpeg", "pipe:1"));
        try {
            return new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
        } catch (IOException e) {
            throw analysisError("cannot start FFmpeg frame extraction: " + sourceVideo, e);
        }
    }

    private ThreadPoolExecutor createReaderExecutor() {
        return new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                runnable -> new Thread(runnable, "highlight-ffmpeg-frame-reader"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private byte[] readSingleFrame(InputStream inputStream) throws IOException {
        try (InputStream stream = inputStream) {
            return readNextJpeg(stream);
        }
    }

    private int readFrames(InputStream inputStream,
                           int intervalSeconds,
                           FrameConsumer frameConsumer) throws IOException {
        int frameIndex = 0;
        try (InputStream stream = inputStream) {
            byte[] jpegData;
            while ((jpegData = readNextJpeg(stream)) != null) {
                frameConsumer.accept(new InMemoryVideoFrame(
                        frameIndex * intervalSeconds, jpegData));
                frameIndex++;
            }
        }
        return frameIndex;
    }

    /**
     * 按 JPEG 起止标记从 FFmpeg image2pipe 中读取一帧，避免额外的单调用方读取器类。
     */
    private byte[] readNextJpeg(InputStream inputStream) throws IOException {
        ByteArrayOutputStream frame = null;
        int previous = -1;
        int current;
        while ((current = inputStream.read()) != -1) {
            if (frame == null) {
                if (previous == MARKER_PREFIX && current == JPEG_START_MARKER) {
                    frame = new ByteArrayOutputStream();
                    frame.write(MARKER_PREFIX);
                    frame.write(JPEG_START_MARKER);
                }
            } else {
                frame.write(current);
                if (frame.size() > MAX_FRAME_BYTES) {
                    throw new IOException("MJPEG frame exceeds " + MAX_FRAME_BYTES + " bytes");
                }
                if (previous == MARKER_PREFIX && current == JPEG_END_MARKER) {
                    return frame.toByteArray();
                }
            }
            previous = current;
        }
        if (frame != null) {
            throw new IOException("incomplete JPEG frame from FFmpeg output");
        }
        return null;
    }

    private void awaitProcess(Process process,
                              long timeoutSeconds,
                              File sourceVideo) throws InterruptedException {
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw analysisError("FFmpeg frame stream timeout: " + sourceVideo, null);
        }
        if (process.exitValue() != 0) {
            throw analysisError("FFmpeg frame stream failed: " + sourceVideo, null);
        }
    }

    private void validate(File sourceVideo,
                          int intervalSeconds,
                          String cropExpression,
                          long timeoutSeconds,
                          FrameConsumer frameConsumer) {
        if (sourceVideo == null || !sourceVideo.isFile()
                || intervalSeconds <= 0 || timeoutSeconds <= 0
                || StringUtils.isBlank(cropExpression) || frameConsumer == null) {
            throw new IllegalArgumentException("invalid FFmpeg frame stream arguments");
        }
    }

    private void acquirePermit() {
        try {
            FFMPEG_PERMITS.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw analysisError("interrupted while waiting for FFmpeg frame extraction", e);
        }
    }

    private StreamerRecordException analysisError(String message, Throwable cause) {
        if (cause == null) {
            return new StreamerRecordException(ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR, message);
        }
        return new StreamerRecordException(ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR, message, cause);
    }

    @FunctionalInterface
    public interface FrameConsumer {
        void accept(InMemoryVideoFrame frame);
    }
}
