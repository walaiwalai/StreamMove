package com.sh.engine.processor.plugin.highlight.valorant;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.ffmpeg.VideoDurationDetectCmd;
import com.sh.engine.model.highlight.core.HighlightEvent;
import com.sh.engine.model.highlight.core.HighlightProcessContext;
import com.sh.engine.model.highlight.core.InMemoryVideoFrame;
import com.sh.engine.model.highlight.core.OcrTextDetection;
import com.sh.engine.processor.plugin.highlight.FfmpegFrameExtractor;
import com.sh.engine.processor.plugin.highlight.HighlightOcrClient;
import com.sh.engine.processor.plugin.highlight.HighlightTimelineProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 无畏契约右上角击杀栏 OCR 时间线。
 */
@Component
@Slf4j
public class ValorantHighlightTimelineProvider implements HighlightTimelineProvider {
    private static final float OCR_MIN_CONFIDENCE = 0.55f;
    private static final int SAMPLE_INTERVAL_SECONDS = 4;
    private static final int OCR_WORKER_COUNT = 2;
    private static final int FRAME_QUEUE_CAPACITY = 8;
    private static final double FEED_ROW_HEIGHT = 0.042;
    private static final double FEED_TOTAL_HEIGHT = 0.232;
    private static final int PROGRESS_INTERVAL_SECONDS = 300;
    private static final int PROCESS_TIMEOUT_PADDING_SECONDS = 300;
    private static final int WORKER_FINISH_TIMEOUT_SECONDS = 120;
    private static final String KILL_FEED_CROP =
            "crop=trunc(iw*0.255/2)*2:trunc(ih*0.232/2)*2:"
                    + "trunc(iw*0.74/2)*2:trunc(ih*0.08/2)*2";
    private static final InMemoryVideoFrame END_OF_STREAM =
            new InMemoryVideoFrame(0, new byte[]{0});


    @Resource
    private FfmpegFrameExtractor frameStreamExtractor;
    @Resource
    private HighlightOcrClient ocrClient;
    @Resource
    private ValorantKillFeedClassifier classifier;

    @Override
    public List<HighlightEvent> buildScoredTimeline(HighlightProcessContext context) {
        List<HighlightEvent> events = new ArrayList<>();
        for (File sourceVideo : context.getSourceVideos()) {
            events.addAll(scanVideo(sourceVideo));
        }
        return events;
    }

    private List<HighlightEvent> scanVideo(File sourceVideo) {
        double duration = detectDuration(sourceVideo);
        List<RecognizedKillFeed> recognized = recognizeFrames(sourceVideo, duration);
        recognized.sort(Comparator
                .comparingInt(RecognizedKillFeed::getTimestampSeconds)
                .thenComparingDouble(item -> item.getClassification().getRowCenterY()));

        List<HighlightEvent> events = new ArrayList<>();
        ValorantEventDeduplicator deduplicator = new ValorantEventDeduplicator();
        for (RecognizedKillFeed item : recognized) {
            HighlightEvent event = buildEvent(
                    sourceVideo, item, deduplicator);
            if (event != null) {
                events.add(event);
            }
        }
        return events;
    }

    private List<RecognizedKillFeed> recognizeFrames(File sourceVideo, double duration) {
        BlockingQueue<InMemoryVideoFrame> frameQueue =
                new ArrayBlockingQueue<>(FRAME_QUEUE_CAPACITY);
        Queue<RecognizedKillFeed> recognized = new ConcurrentLinkedQueue<>();
        ThreadPoolExecutor executor = createOcrExecutor();
        List<Future<?>> workers = new ArrayList<>();
        for (int index = 0; index < OCR_WORKER_COUNT; index++) {
            workers.add(executor.submit(() -> consumeFrames(sourceVideo, frameQueue, recognized)));
        }

        try {
            try {
                long timeout = Math.max(
                        PROCESS_TIMEOUT_PADDING_SECONDS,
                        (long) Math.ceil(duration) + PROCESS_TIMEOUT_PADDING_SECONDS);
                int count = frameStreamExtractor.stream(
                        sourceVideo, SAMPLE_INTERVAL_SECONDS, KILL_FEED_CROP, timeout,
                        frame -> enqueueFrame(sourceVideo, frameQueue, workers, frame));
                log.info("valorant frame stream completed, video: {}, frames: {}",
                        sourceVideo.getName(), count);
            } finally {
                signalEndOfStream(frameQueue, workers);
            }
            awaitWorkers(workers, sourceVideo);
            return new ArrayList<>(recognized);
        } finally {
            executor.shutdownNow();
        }
    }

    private void consumeFrames(File sourceVideo,
                               BlockingQueue<InMemoryVideoFrame> frameQueue,
                               Queue<RecognizedKillFeed> recognized) {
        while (true) {
            InMemoryVideoFrame frame = takeFrame(sourceVideo, frameQueue);
            if (frame == END_OF_STREAM) {
                return;
            }
            byte[] jpegData = frame.getJpegData();
            BufferedImage feed = decodeFrame(sourceVideo, frame, jpegData);
            List<OcrTextDetection> detections = ocrClient.recognize(
                    jpegData,
                    String.format(Locale.ROOT, "%s@%06d-kill-feed.jpg",
                            prefix(sourceVideo), frame.getTimestampSeconds()));
            List<ValorantKillFeedClassifier.Classification> classifications =
                    classifier.classifyRows(
                            detections, feed.getWidth(), feed.getHeight(), OCR_MIN_CONFIDENCE);
            for (ValorantKillFeedClassifier.Classification classification : classifications) {
                recognized.add(new RecognizedKillFeed(
                        frame.getTimestampSeconds(), feed, classification));
            }
        }
    }

    private HighlightEvent buildEvent(File sourceVideo,
                                      RecognizedKillFeed item,
                                      ValorantEventDeduplicator deduplicator) {
        ValorantKillFeedClassifier.Classification classification = item.getClassification();
        String eventType = classification.getSide() == ValorantKillFeedClassifier.Side.LEFT
                ? "SELF_KILL" : "SELF_DEATH";
        BufferedImage rowImage = cropRow(item.getFeed(), classification.getRowCenterY());
        long imageHash = ValorantEventDeduplicator.differenceHash(rowImage);
        if (deduplicator.isDuplicate(
                eventType, item.getTimestampSeconds(), classification.getText(), imageHash)) {
            return null;
        }

        Map<String, Object> evidence = new HashMap<>();
        evidence.put("text", classification.getText());
        evidence.put("markerCenterX", classification.getMarkerCenterX());
        evidence.put("rowCenterY", classification.getRowCenterY());
        float score = "SELF_KILL".equals(eventType) ? 2.0f : -1.0f;
        log.info("valorant event detected, video: {}, second: {}, type: {}, text: {}",
                sourceVideo.getName(), item.getTimestampSeconds(),
                eventType, classification.getText());
        return HighlightEvent.builder()
                .sourceVideo(sourceVideo)
                .secondFromVideoStart(item.getTimestampSeconds())
                .eventType(eventType)
                .score(score)
                .positiveCount("SELF_KILL".equals(eventType) ? 1 : 0)
                .negativeCount("SELF_DEATH".equals(eventType) ? 1 : 0)
                .confidence(classification.getConfidence())
                .evidence(evidence)
                .build();
    }

    private BufferedImage cropRow(BufferedImage feed, double rowCenterY) {
        int rowHeight = Math.max(1, (int) Math.round(
                FEED_ROW_HEIGHT / FEED_TOTAL_HEIGHT * feed.getHeight()));
        int y = (int) Math.round(rowCenterY - rowHeight / 2.0);
        y = Math.max(0, Math.min(y, feed.getHeight() - 1));
        rowHeight = Math.min(rowHeight, feed.getHeight() - y);
        return feed.getSubimage(0, y, feed.getWidth(), rowHeight);
    }

    private ThreadPoolExecutor createOcrExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> new Thread(
                runnable, "valorant-ocr-worker-" + sequence.incrementAndGet());
        return new ThreadPoolExecutor(
                OCR_WORKER_COUNT, OCR_WORKER_COUNT, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(OCR_WORKER_COUNT),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    private void enqueueFrame(File sourceVideo,
                              BlockingQueue<InMemoryVideoFrame> frameQueue,
                              List<Future<?>> workers,
                              InMemoryVideoFrame frame) {
        try {
            while (!frameQueue.offer(frame, 5, TimeUnit.SECONDS)) {
                assertWorkersHealthy(workers, sourceVideo);
            }
            int second = frame.getTimestampSeconds();
            if (second > 0 && second % PROGRESS_INTERVAL_SECONDS == 0) {
                log.info("valorant kill feed scan progress, video: {}, second: {}",
                        sourceVideo.getName(), second);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw analysisError("interrupted while queuing frame: " + sourceVideo, e);
        }
    }

    private InMemoryVideoFrame takeFrame(File sourceVideo,
                                         BlockingQueue<InMemoryVideoFrame> frameQueue) {
        try {
            return frameQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw analysisError("interrupted while waiting for OCR frame: " + sourceVideo, e);
        }
    }

    private void signalEndOfStream(BlockingQueue<InMemoryVideoFrame> frameQueue,
                                   List<Future<?>> workers) {
        for (int index = 0; index < OCR_WORKER_COUNT; index++) {
            try {
                while (!frameQueue.offer(END_OF_STREAM, 5, TimeUnit.SECONDS)) {
                    assertWorkersHealthy(workers, null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw analysisError("interrupted while closing OCR frame queue", e);
            }
        }
    }

    private void assertWorkersHealthy(List<Future<?>> workers, File sourceVideo) {
        for (Future<?> worker : workers) {
            if (!worker.isDone()) {
                continue;
            }
            try {
                worker.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw analysisError("interrupted while checking OCR workers", e);
            } catch (ExecutionException e) {
                throw analysisError("OCR worker failed for video: " + sourceVideo, e.getCause());
            }
        }
    }

    private void awaitWorkers(List<Future<?>> workers, File sourceVideo) {
        for (Future<?> worker : workers) {
            try {
                worker.get(WORKER_FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw analysisError("interrupted while waiting for OCR workers: " + sourceVideo, e);
            } catch (ExecutionException e) {
                throw analysisError("OCR worker failed for video: " + sourceVideo, e.getCause());
            } catch (TimeoutException e) {
                throw analysisError("OCR worker timeout for video: " + sourceVideo, e);
            }
        }
    }

    private BufferedImage decodeFrame(File sourceVideo,
                                      InMemoryVideoFrame frame,
                                      byte[] jpegData) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpegData));
            if (image == null) {
                throw analysisError("cannot decode frame at "
                        + frame.getTimestampSeconds() + "s from " + sourceVideo, null);
            }
            return image;
        } catch (IOException e) {
            throw analysisError("cannot decode frame at "
                    + frame.getTimestampSeconds() + "s from " + sourceVideo, e);
        }
    }

    private double detectDuration(File video) {
        VideoDurationDetectCmd command = new VideoDurationDetectCmd(video.getAbsolutePath());
        command.execute(100);
        if (command.getDurationSeconds() <= 0) {
            throw analysisError("cannot detect video duration: " + video, null);
        }
        return command.getDurationSeconds();
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
        return new StreamerRecordException(ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR, message, cause);
    }

    private static final class RecognizedKillFeed {
        private final int timestampSeconds;
        private final BufferedImage feed;
        private final ValorantKillFeedClassifier.Classification classification;

        private RecognizedKillFeed(
                int timestampSeconds,
                BufferedImage feed,
                ValorantKillFeedClassifier.Classification classification) {
            this.timestampSeconds = timestampSeconds;
            this.feed = feed;
            this.classification = classification;
        }

        private int getTimestampSeconds() {
            return timestampSeconds;
        }

        private BufferedImage getFeed() {
            return feed;
        }

        private ValorantKillFeedClassifier.Classification getClassification() {
            return classification;
        }
    }
}
