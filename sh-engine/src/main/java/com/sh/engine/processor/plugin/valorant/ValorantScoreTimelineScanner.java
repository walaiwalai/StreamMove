package com.sh.engine.processor.plugin.valorant;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.highlight.core.InMemoryVideoFrame;
import com.sh.engine.model.highlight.core.OcrTextDetection;
import com.sh.engine.processor.plugin.highlight.FfmpegFrameExtractor;
import com.sh.engine.processor.plugin.highlight.HighlightOcrClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 以固定间隔截取顶部比分区，每张截图独立进行 OCR。
 */
@Component
@Slf4j
public class ValorantScoreTimelineScanner {
    public static final int SAMPLE_INTERVAL_SECONDS = 4;
    public static final int BATCH_FRAME_COUNT = 1;
    private static final int OCR_WORKER_COUNT = 4;
    private static final int OCR_QUEUE_CAPACITY = 16;
    private static final int PROGRESS_INTERVAL_SECONDS = 300;
    private static final int PROCESS_TIMEOUT_PADDING_SECONDS = 300;
    private static final int OCR_FINISH_TIMEOUT_MINUTES = 30;
    private static final String SCOREBOARD_CROP =
            "crop=trunc(iw*0.20/2)*2:trunc(ih*0.10/2)*2:"
                    + "trunc(iw*0.40/2)*2:0,scale=960:-2";

    private final FfmpegFrameExtractor frameExtractor;
    private final HighlightOcrClient ocrClient;
    private final ValorantScoreOcrParser ocrParser;

    public ValorantScoreTimelineScanner(FfmpegFrameExtractor frameExtractor,
                                        HighlightOcrClient ocrClient,
                                        ValorantScoreOcrParser ocrParser) {
        this.frameExtractor = frameExtractor;
        this.ocrClient = ocrClient;
        this.ocrParser = ocrParser;
    }

    public List<ValorantScoreObservation> scan(List<ValorantVideoPart> videoParts) {
        ThreadPoolExecutor executor = createOcrExecutor();
        List<Future<ValorantScoreObservation>> futures = new ArrayList<>();
        AtomicInteger frameSequence = new AtomicInteger();
        try {
            for (ValorantVideoPart videoPart : videoParts) {
                scanVideo(videoPart, executor, futures, frameSequence);
            }
            executor.shutdown();
            List<ValorantScoreObservation> observations = awaitResults(futures);
            observations.sort(Comparator.comparingDouble(
                    ValorantScoreObservation::getGlobalSecond));
            return observations;
        } finally {
            executor.shutdownNow();
        }
    }

    private void scanVideo(
            ValorantVideoPart videoPart,
            ThreadPoolExecutor executor,
            List<Future<ValorantScoreObservation>> futures,
            AtomicInteger frameSequence) {
        long timeoutSeconds = Math.max(
                PROCESS_TIMEOUT_PADDING_SECONDS,
                (long) Math.ceil(videoPart.duration()) + PROCESS_TIMEOUT_PADDING_SECONDS);
        int frameCount = frameExtractor.stream(
                videoPart.getSourceVideo(), SAMPLE_INTERVAL_SECONDS,
                SCOREBOARD_CROP, timeoutSeconds, frame -> {
                    ScoreFrame scoreFrame = new ScoreFrame(
                            videoPart.getGlobalStartSecond() + frame.getTimestampSeconds(),
                            frame.getJpegData());
                    submitFrame(videoPart.getSourceVideo(), scoreFrame,
                            executor, futures, frameSequence.incrementAndGet());
                    logProgress(videoPart.getSourceVideo(), frame);
                });
        log.info("valorant score frame scan completed, video: {}, frames: {}",
                videoPart.getSourceVideo().getName(), frameCount);
    }

    private void submitFrame(
            File sourceVideo,
            ScoreFrame frame,
            ThreadPoolExecutor executor,
            List<Future<ValorantScoreObservation>> futures,
            int frameSequence) {
        futures.add(executor.submit(() -> recognizeFrame(
                sourceVideo, frame, frameSequence)));
    }

    private ValorantScoreObservation recognizeFrame(
            File sourceVideo,
            ScoreFrame frame,
            int frameSequence) {
        BufferedImage image = decodeFrame(sourceVideo, frame);
        List<OcrTextDetection> detections = ocrClient.recognize(
                frame.jpegData,
                String.format(Locale.ROOT, "%s-score-frame-%06d.jpg",
                        prefix(sourceVideo), frameSequence));
        ValorantScoreObservation observation = ocrParser.parse(
                detections,
                Collections.singletonList(frame.globalSecond),
                image.getWidth(),
                image.getHeight(),
                1).get(0);
        log.info("valorant score OCR frame, video: {}, second: {}, raw: {}, parsed: {}",
                sourceVideo.getName(), frame.globalSecond,
                summarizeDetections(detections), summarizeObservation(observation));
        return observation;
    }

    private List<String> summarizeDetections(List<OcrTextDetection> detections) {
        List<String> result = new ArrayList<>(detections.size());
        for (OcrTextDetection detection : detections) {
            result.add(String.format(Locale.ROOT, "%s(%.2f)",
                    detection.getText(), detection.getScore()));
        }
        return result;
    }

    private String summarizeObservation(ValorantScoreObservation observation) {
        return String.format(Locale.ROOT, "%.0fs=%s/%s/%s",
                observation.getGlobalSecond(),
                valueOrDash(observation.getLeftScore()),
                valueOrDash(observation.getRoundTime()),
                valueOrDash(observation.getRightScore()));
    }

    private String valueOrDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private BufferedImage decodeFrame(File sourceVideo, ScoreFrame frame) {
        try {
            BufferedImage image = ImageIO.read(
                    new ByteArrayInputStream(frame.jpegData));
            if (image == null) {
                throw analysisError("cannot decode score frame from " + sourceVideo, null);
            }
            return image;
        } catch (IOException e) {
            throw analysisError("cannot decode score frame from " + sourceVideo, e);
        }
    }

    private List<ValorantScoreObservation> awaitResults(
            List<Future<ValorantScoreObservation>> futures) {
        List<ValorantScoreObservation> observations = new ArrayList<>();
        for (Future<ValorantScoreObservation> future : futures) {
            try {
                observations.add(future.get(
                        OCR_FINISH_TIMEOUT_MINUTES, TimeUnit.MINUTES));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw analysisError("interrupted while waiting for score OCR", e);
            } catch (ExecutionException e) {
                throw analysisError("valorant score OCR worker failed", e.getCause());
            } catch (TimeoutException e) {
                throw analysisError("valorant score OCR worker timeout", e);
            }
        }
        return observations;
    }

    private ThreadPoolExecutor createOcrExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> new Thread(
                runnable, "valorant-score-ocr-" + sequence.incrementAndGet());
        return new ThreadPoolExecutor(
                OCR_WORKER_COUNT, OCR_WORKER_COUNT, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(OCR_QUEUE_CAPACITY),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private void logProgress(File sourceVideo, InMemoryVideoFrame frame) {
        int second = frame.getTimestampSeconds();
        if (second > 0 && second % PROGRESS_INTERVAL_SECONDS == 0) {
            log.info("valorant score scan progress, video: {}, second: {}",
                    sourceVideo.getName(), second);
        }
    }

    private String prefix(File sourceVideo) {
        String name = sourceVideo.getName();
        int suffixStart = name.lastIndexOf('.');
        return suffixStart > 0 ? name.substring(0, suffixStart) : name;
    }

    private StreamerRecordException analysisError(String message, Throwable cause) {
        if (cause == null) {
            return new StreamerRecordException(ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR, message);
        }
        return new StreamerRecordException(
                ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR, message, cause);
    }

    private static final class ScoreFrame {
        private final double globalSecond;
        private final byte[] jpegData;

        private ScoreFrame(double globalSecond, byte[] jpegData) {
            this.globalSecond = globalSecond;
            this.jpegData = jpegData;
        }
    }
}
