package com.sh.engine.processor.plugin.lol;

import com.alibaba.fastjson.TypeReference;
import com.sh.config.utils.FileStoreUtil;
import com.sh.engine.model.ffmpeg.VideoDurationDetectCmd;
import com.sh.engine.model.highlight.lol.LolKdaTimelinePoint;
import com.sh.engine.model.highlight.lol.LoLPicData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntFunction;

import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.ADAPTIVE_COARSE_INTERVAL_SECONDS;
import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.ADAPTIVE_KDA_SNAPSHOT_DIR;
import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.ADAPTIVE_MIN_INTERVAL_SECONDS;
import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.MAX_ADAPTIVE_SNAPSHOTS;
import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.SEEK_RETRY_OFFSET_SECONDS;
import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.SNAP_INTERVAL_SECONDS;

/**
 * 使用 8 分钟粗粒度和 4 秒最小粒度，自适应查找 KDA 变化点。
 */
@Component
@Slf4j
public class LolAdaptiveKdaScanner {
    private static final String CACHE_FILE_NAME = "adaptive-kda-cache.json";

    @Resource
    private LolHighlightSnapshotService snapshotService;
    @Resource
    private LolTimestampFrameExtractor frameExtractor;
    @Resource
    private LolOcrClient ocrClient;

    public List<LolKdaTimelinePoint> scan(String recordPath, List<File> videos) {
        String cropExpression = snapshotService.findKdaCropExpression(recordPath, videos.get(0));
        File snapshotDirectory = new File(recordPath, ADAPTIVE_KDA_SNAPSHOT_DIR);
        snapshotDirectory.mkdirs();
        File cacheFile = new File(snapshotDirectory, CACHE_FILE_NAME);
        LolAdaptiveKdaCache cache = loadCache(cacheFile);
        ScanStatistics statistics = new ScanStatistics();

        List<LolKdaTimelinePoint> timeline = new ArrayList<>();
        for (File video : videos) {
            double durationSeconds = detectDuration(video);
            int lastTimelineSecond = calculateLastTimelineSecond(durationSeconds);
            LolAdaptiveKdaCache.VideoCache videoCache = prepareVideoCache(
                    cache, video, durationSeconds);
            ScanSession session = new ScanSession(
                    video, lastTimelineSecond, cropExpression, snapshotDirectory,
                    cacheFile, cache, videoCache, statistics);

            TreeMap<Integer, LoLPicData> videoTimeline = buildTimeline(
                    lastTimelineSecond, session::sample);
            for (Map.Entry<Integer, LoLPicData> entry : videoTimeline.entrySet()) {
                timeline.add(new LolKdaTimelinePoint(
                        video, entry.getKey(), entry.getValue()));
            }
        }

        log.info("adaptive KDA scan finished, logical points: {}, sampled points: {}, "
                        + "screenshots: {}, OCR requests: {}, cache hits: {}",
                timeline.size(), statistics.sampledPoints, statistics.screenshots,
                statistics.ocrRequests, statistics.cacheHits);
        return timeline;
    }

    TreeMap<Integer, LoLPicData> buildTimeline(int lastTimelineSecond,
                                               IntFunction<LoLPicData> sampler) {
        TreeMap<Integer, LoLPicData> timeline = new TreeMap<>();
        if (lastTimelineSecond <= 0) {
            timeline.put(0, normalize(sampler.apply(0)));
            return timeline;
        }

        List<Integer> anchors = buildCoarseAnchors(lastTimelineSecond);
        for (int i = 1; i < anchors.size(); i++) {
            scanRange(anchors.get(i - 1), anchors.get(i), sampler, timeline);
        }
        return timeline;
    }

    private void scanRange(int leftSecond,
                           int rightSecond,
                           IntFunction<LoLPicData> sampler,
                           TreeMap<Integer, LoLPicData> timeline) {
        LoLPicData left = sampler.apply(leftSecond);
        LoLPicData right = sampler.apply(rightSecond);
        if (rightSecond - leftSecond <= ADAPTIVE_MIN_INTERVAL_SECONDS) {
            timeline.put(leftSecond, normalize(left));
            timeline.put(rightSecond, normalize(right));
            return;
        }

        if (left.beValid() && right.beValid() && left.compareKda(right)) {
            fillRange(timeline, leftSecond, rightSecond, left);
            return;
        }

        int middleSecond = middleTimelineSecond(leftSecond, rightSecond);
        LoLPicData middle = sampler.apply(middleSecond);
        if (!left.beValid() && !right.beValid() && !middle.beValid()) {
            fillRange(timeline, leftSecond, rightSecond, LoLPicData.genBlank());
            return;
        }

        scanRange(leftSecond, middleSecond, sampler, timeline);
        scanRange(middleSecond, rightSecond, sampler, timeline);
    }

    private List<Integer> buildCoarseAnchors(int lastTimelineSecond) {
        List<Integer> anchors = new ArrayList<>();
        for (int second = 0; second <= lastTimelineSecond;
             second += ADAPTIVE_COARSE_INTERVAL_SECONDS) {
            anchors.add(second);
        }
        if (anchors.get(anchors.size() - 1) != lastTimelineSecond) {
            anchors.add(lastTimelineSecond);
        }
        return anchors;
    }

    private int middleTimelineSecond(int leftSecond, int rightSecond) {
        int intervalSteps = (rightSecond - leftSecond) / SNAP_INTERVAL_SECONDS;
        int leftSteps = Math.max(1, intervalSteps / 2);
        return leftSecond + leftSteps * SNAP_INTERVAL_SECONDS;
    }

    private void fillRange(TreeMap<Integer, LoLPicData> timeline,
                           int leftSecond,
                           int rightSecond,
                           LoLPicData value) {
        for (int second = leftSecond; second <= rightSecond; second += SNAP_INTERVAL_SECONDS) {
            timeline.put(second, copy(value));
        }
    }

    private LoLPicData normalize(LoLPicData value) {
        return value != null && value.beValid() ? copy(value) : LoLPicData.genBlank();
    }

    private LoLPicData copy(LoLPicData value) {
        return new LoLPicData(value.getK(), value.getD(), value.getA());
    }

    private double detectDuration(File video) {
        VideoDurationDetectCmd command = new VideoDurationDetectCmd(video.getAbsolutePath());
        command.execute(100);
        return command.getDurationSeconds();
    }

    private int calculateLastTimelineSecond(double durationSeconds) {
        int pointCount = Math.max(1, (int) Math.ceil(durationSeconds / SNAP_INTERVAL_SECONDS));
        return (pointCount - 1) * SNAP_INTERVAL_SECONDS;
    }

    private LolAdaptiveKdaCache loadCache(File cacheFile) {
        if (!cacheFile.exists()) {
            return new LolAdaptiveKdaCache();
        }
        try {
            LolAdaptiveKdaCache cache = FileStoreUtil.loadFromFile(
                    cacheFile,
                    new TypeReference<LolAdaptiveKdaCache>() {
                    });
            if (cache != null && cache.getVersion() == 1 && cache.getVideos() != null) {
                return cache;
            }
        } catch (RuntimeException e) {
            log.warn("cannot load adaptive KDA cache, rebuild it, path: {}",
                    cacheFile.getAbsolutePath(), e);
        }
        return new LolAdaptiveKdaCache();
    }

    private LolAdaptiveKdaCache.VideoCache prepareVideoCache(LolAdaptiveKdaCache cache,
                                                              File video,
                                                              double durationSeconds) {
        LolAdaptiveKdaCache.VideoCache videoCache = cache.getVideos().get(video.getName());
        if (videoCache == null
                || videoCache.getSamples() == null
                || !videoCache.matches(video.length(), video.lastModified(), durationSeconds)) {
            videoCache = new LolAdaptiveKdaCache.VideoCache();
            videoCache.setFileSize(video.length());
            videoCache.setLastModified(video.lastModified());
            videoCache.setDurationSeconds(durationSeconds);
            cache.getVideos().put(video.getName(), videoCache);
        }
        return videoCache;
    }

    private class ScanSession {
        private final File video;
        private final int lastTimelineSecond;
        private final String cropExpression;
        private final File snapshotDirectory;
        private final File cacheFile;
        private final LolAdaptiveKdaCache cache;
        private final LolAdaptiveKdaCache.VideoCache videoCache;
        private final ScanStatistics statistics;
        private final Map<Integer, LoLPicData> runtimeSamples = new TreeMap<>();
        private int screenshotsForVideo;

        private ScanSession(File video,
                            int lastTimelineSecond,
                            String cropExpression,
                            File snapshotDirectory,
                            File cacheFile,
                            LolAdaptiveKdaCache cache,
                            LolAdaptiveKdaCache.VideoCache videoCache,
                            ScanStatistics statistics) {
            this.video = video;
            this.lastTimelineSecond = lastTimelineSecond;
            this.cropExpression = cropExpression;
            this.snapshotDirectory = snapshotDirectory;
            this.cacheFile = cacheFile;
            this.cache = cache;
            this.videoCache = videoCache;
            this.statistics = statistics;
        }

        private LoLPicData sample(int timestampSeconds) {
            LoLPicData runtime = runtimeSamples.get(timestampSeconds);
            if (runtime != null) {
                return runtime;
            }
            statistics.sampledPoints++;

            LolAdaptiveKdaCache.CachedKda cached = videoCache.getSamples()
                    .get(String.valueOf(timestampSeconds));
            if (cached != null) {
                statistics.cacheHits++;
                LoLPicData cachedData = cached.isValid()
                        ? new LoLPicData(cached.getKill(), cached.getDeath(), cached.getAssist())
                        : LoLPicData.genInvalid();
                runtimeSamples.put(timestampSeconds, cachedData);
                return cachedData;
            }

            LoLPicData recognized = recognizeWithRetry(timestampSeconds);
            runtimeSamples.put(timestampSeconds, recognized);
            if (recognized.beValid()) {
                videoCache.getSamples().put(
                        String.valueOf(timestampSeconds),
                        new LolAdaptiveKdaCache.CachedKda(
                                recognized.getK(), recognized.getD(), recognized.getA()));
            } else {
                videoCache.getSamples().put(
                        String.valueOf(timestampSeconds),
                        LolAdaptiveKdaCache.CachedKda.invalid());
            }
            FileStoreUtil.saveToFile(cacheFile, cache);
            return recognized;
        }

        private LoLPicData recognizeWithRetry(int timestampSeconds) {
            Set<Integer> retrySeconds = new LinkedHashSet<>();
            retrySeconds.add(timestampSeconds);
            retrySeconds.add(Math.max(0, timestampSeconds - SEEK_RETRY_OFFSET_SECONDS));
            retrySeconds.add(Math.min(lastTimelineSecond, timestampSeconds + SEEK_RETRY_OFFSET_SECONDS));

            for (Integer retrySecond : retrySeconds) {
                ensureWithinSnapshotLimit();
                File snapshot = frameExtractor.extract(
                        video, retrySecond, cropExpression, snapshotDirectory);
                screenshotsForVideo++;
                statistics.screenshots++;
                statistics.ocrRequests++;
                List<Integer> kda = ocrClient.recognizeKda(snapshot);
                if (isValidKda(kda)) {
                    return new LoLPicData(kda.get(0), kda.get(1), kda.get(2));
                }
            }
            return LoLPicData.genInvalid();
        }

        private void ensureWithinSnapshotLimit() {
            if (screenshotsForVideo >= MAX_ADAPTIVE_SNAPSHOTS) {
                throw new LolAdaptiveScanException(
                        "adaptive screenshot count reached " + MAX_ADAPTIVE_SNAPSHOTS
                                + " for " + video.getName());
            }
        }

        private boolean isValidKda(List<Integer> kda) {
            return kda != null && kda.size() >= 3
                    && kda.get(0) >= 0 && kda.get(1) >= 0 && kda.get(2) >= 0;
        }
    }

    private static class ScanStatistics {
        private int sampledPoints;
        private int screenshots;
        private int ocrRequests;
        private int cacheHits;
    }
}
