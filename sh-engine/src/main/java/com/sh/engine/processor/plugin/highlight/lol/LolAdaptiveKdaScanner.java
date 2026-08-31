package com.sh.engine.processor.plugin.highlight.lol;

import com.alibaba.fastjson.TypeReference;
import com.google.common.collect.Maps;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.utils.FileStoreUtil;
import com.sh.engine.model.ffmpeg.VideoDurationDetectCmd;
import com.sh.engine.model.highlight.core.InMemoryVideoFrame;
import com.sh.engine.model.highlight.lol.LoLPicData;
import com.sh.engine.model.highlight.lol.LolKdaTimelinePoint;
import com.sh.engine.processor.plugin.highlight.FfmpegFrameExtractor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntFunction;

/**
 * 生成每 4 秒一个逻辑点的 LoL KDA 时间线。
 *
 * <p>流程只有三步：先定位画面顶部的 KDA 区域，再按 8 分钟粗区间递归查找变化点，
 * 最后把识别结果保存到 JSON 缓存。所有送 OCR 的画面都在内存中，不落地截图。</p>
 */
@Component
@Slf4j
public class LolAdaptiveKdaScanner {
    private static final String KDA_ANALYSIS_CACHE_DIR = "kda-analysis-cache";
    private static final String CROP_CACHE_FILE_NAME = "accurate-corp.json";
    private static final String KDA_CACHE_DIR = "kda-adaptive-cache";
    private static final String KDA_CACHE_FILE_NAME = "adaptive-kda-cache.json";
    private static final String KDA_TEST_CROP_EXPRESSION = "crop=in_w/2:100:in_w/2:0";
    private static final String DEFAULT_KDA_CROP_EXPRESSION = "crop=80:30:in_w*867/1000:0";
    private static final int CROP_PROBE_INTERVAL_SECONDS = 80;
    private static final int KDA_SAMPLE_INTERVAL_SECONDS = 4;
    private static final int COARSE_INTERVAL_SECONDS = 8 * 60;
    private static final int SEEK_RETRY_OFFSET_SECONDS = 1;
    private static final int MIN_FRAME_BUDGET_PER_VIDEO = 200;

    @Resource
    private FfmpegFrameExtractor frameExtractor;
    @Resource
    private LolKdaRecognizer kdaRecognizer;

    /**
     * 扫描按播放顺序排列的视频分片，并返回每 4 秒一个点的 KDA 时间线。
     *
     * @param recordPath 录制目录，用于保存可复用的 JSON 缓存
     * @param videos 按播放顺序排列的源视频
     * @return 跨视频分片的 KDA 时间线；没有视频时返回空集合
     */
    public List<LolKdaTimelinePoint> scan(String recordPath, List<File> videos) {
        if (videos == null || videos.isEmpty()) {
            return Collections.emptyList();
        }

        String cropExpression = findKdaCropExpression(recordPath, videos.get(0));
        File cacheFile = prepareCacheFile(recordPath, KDA_CACHE_DIR, KDA_CACHE_FILE_NAME);
        KdaCache cache = loadKdaCache(cacheFile);
        ScanStatistics statistics = new ScanStatistics();
        List<LolKdaTimelinePoint> timeline = new ArrayList<>();

        for (File video : videos) {
            double durationSeconds = detectDuration(video);
            int lastSecond = calculateLastTimelineSecond(durationSeconds);
            VideoCache videoCache = prepareVideoCache(cache, video, durationSeconds);
            ScanSession session = new ScanSession(
                    video, lastSecond, cropExpression, cacheFile, cache, videoCache, statistics);
            TreeMap<Integer, LoLPicData> videoTimeline = buildTimeline(lastSecond, session::sample);
            for (Map.Entry<Integer, LoLPicData> entry : videoTimeline.entrySet()) {
                timeline.add(new LolKdaTimelinePoint(video, entry.getKey(), entry.getValue()));
            }
        }

        log.info("adaptive KDA scan finished, logical points: {}, sampled points: {}, "
                        + "memory frames: {}, OCR requests: {}, cache hits: {}",
                timeline.size(), statistics.sampledPoints, statistics.memoryFrames,
                statistics.ocrRequests, statistics.cacheHits);
        return timeline;
    }

    /**
     * 先以 8 分钟为锚点检查区间；端点 KDA 相同即可整段填充，否则递归二分到 4 秒。
     * 这样仍保留每 4 秒的逻辑时间线，但不会每 4 秒都解码和 OCR。
     */
    private TreeMap<Integer, LoLPicData> buildTimeline(
            int lastTimelineSecond,
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

    /**
     * KDA 在同一局内单调递增：左右端点相同代表区间内没有变化，可直接跳过 OCR。
     * 换局导致 KDA 回退或 OCR 无效时不会套用该假设，而是继续拆分区间。
     */
    private void scanRange(int leftSecond,
                           int rightSecond,
                           IntFunction<LoLPicData> sampler,
                           TreeMap<Integer, LoLPicData> timeline) {
        LoLPicData left = sampler.apply(leftSecond);
        LoLPicData right = sampler.apply(rightSecond);
        if (rightSecond - leftSecond <= KDA_SAMPLE_INTERVAL_SECONDS) {
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
        for (int second = 0; second <= lastTimelineSecond; second += COARSE_INTERVAL_SECONDS) {
            anchors.add(second);
        }
        if (anchors.get(anchors.size() - 1) != lastTimelineSecond) {
            anchors.add(lastTimelineSecond);
        }
        return anchors;
    }

    private int middleTimelineSecond(int leftSecond, int rightSecond) {
        int intervalSteps = (rightSecond - leftSecond) / KDA_SAMPLE_INTERVAL_SECONDS;
        return leftSecond + Math.max(1, intervalSteps / 2) * KDA_SAMPLE_INTERVAL_SECONDS;
    }

    private void fillRange(TreeMap<Integer, LoLPicData> timeline,
                           int leftSecond,
                           int rightSecond,
                           LoLPicData value) {
        for (int second = leftSecond; second <= rightSecond; second += KDA_SAMPLE_INTERVAL_SECONDS) {
            timeline.put(second, copyKda(value));
        }
    }

    private LoLPicData normalize(LoLPicData value) {
        return value != null && value.beValid() ? copyKda(value) : LoLPicData.genBlank();
    }

    private LoLPicData copyKda(LoLPicData value) {
        return new LoLPicData(value.getK(), value.getD(), value.getA());
    }

    /**
     * 读取已经定位过的裁剪区域；没有缓存时每 80 秒探测一次顶部右半区域。
     */
    private String findKdaCropExpression(String recordPath, File sampleVideo) {
        File cropCacheFile = prepareCacheFile(
                recordPath, KDA_ANALYSIS_CACHE_DIR, CROP_CACHE_FILE_NAME);
        Map<String, String> cropByVideo = loadCropCache(cropCacheFile);
        String cachedCrop = cropByVideo.get(sampleVideo.getName());
        if (StringUtils.isNotBlank(cachedCrop)) {
            log.info("find KDA crop expression from cache, video: {}, expression: {}",
                    sampleVideo.getName(), cachedCrop);
            return cachedCrop;
        }

        String detectedCrop = detectKdaCrop(sampleVideo);
        if (StringUtils.isBlank(detectedCrop)) {
            detectedCrop = DEFAULT_KDA_CROP_EXPRESSION;
        }
        cropByVideo.put(sampleVideo.getName(), detectedCrop);
        FileStoreUtil.saveToFile(cropCacheFile, cropByVideo);
        log.info("find KDA crop expression, video: {}, expression: {}",
                sampleVideo.getName(), detectedCrop);
        return detectedCrop;
    }

    private Map<String, String> loadCropCache(File cropCacheFile) {
        if (!cropCacheFile.exists()) {
            return Maps.newHashMap();
        }
        Map<String, String> cache = FileStoreUtil.loadFromFile(
                cropCacheFile,
                new TypeReference<Map<String, String>>() {
                });
        return cache == null ? Maps.newHashMap() : cache;
    }

    private String detectKdaCrop(File sampleVideo) {
        double videoEndSecond = detectDuration(sampleVideo);
        for (int second = 0; second < videoEndSecond; second += CROP_PROBE_INTERVAL_SECONDS) {
            try {
                InMemoryVideoFrame frame = frameExtractor.extract(
                        sampleVideo, second, KDA_TEST_CROP_EXPRESSION);
                List<List<Integer>> boxes = kdaRecognizer.detectKdaBox(
                        frame.getJpegData(), frameName(sampleVideo, second, "kda-crop"));
                String cropExpression = createCropExpression(boxes);
                if (StringUtils.isNotBlank(cropExpression)) {
                    return cropExpression;
                }
            } catch (RuntimeException e) {
                log.warn("cannot detect KDA box, video: {}, timestamp: {}s",
                        sampleVideo.getAbsolutePath(), second, e);
            }
        }
        return null;
    }

    /**
     * OCR 返回的是右半张探测图中的坐标，因此 X 坐标需加回 in_w/2。
     * 四周额外扩展少量像素，避免紧贴文字边缘导致后续 OCR 截断。
     */
    private static String createCropExpression(List<List<Integer>> boxes) {
        if (boxes == null || boxes.size() != 4) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (List<Integer> point : boxes) {
            if (point == null || point.size() < 2) {
                return null;
            }
            minX = Math.min(minX, point.get(0));
            minY = Math.min(minY, point.get(1));
            maxX = Math.max(maxX, point.get(0));
            maxY = Math.max(maxY, point.get(1));
        }
        return String.format(
                "crop=%d:%d:in_w/2+%d:%d",
                maxX - minX + 20, maxY - minY + 10, minX, Math.max(0, minY - 5));
    }

    private File prepareCacheFile(String recordPath, String directoryName, String fileName) {
        File cacheDirectory = new File(recordPath, directoryName);
        if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "cannot create KDA cache directory: " + cacheDirectory.getAbsolutePath());
        }
        return new File(cacheDirectory, fileName);
    }

    private double detectDuration(File video) {
        VideoDurationDetectCmd command = new VideoDurationDetectCmd(video.getAbsolutePath());
        command.execute(100);
        return command.getDurationSeconds();
    }

    private int calculateLastTimelineSecond(double durationSeconds) {
        int pointCount = Math.max(1, (int) Math.ceil(durationSeconds / KDA_SAMPLE_INTERVAL_SECONDS));
        return (pointCount - 1) * KDA_SAMPLE_INTERVAL_SECONDS;
    }

    /**
     * 每个逻辑时间点至少允许一次抽帧，短视频保留固定余量供 OCR 前后秒重试。
     */
    private int calculateFrameBudget(int lastTimelineSecond) {
        int logicalPointCount = lastTimelineSecond / KDA_SAMPLE_INTERVAL_SECONDS + 1;
        return Math.max(MIN_FRAME_BUDGET_PER_VIDEO, logicalPointCount);
    }

    private KdaCache loadKdaCache(File cacheFile) {
        if (!cacheFile.exists()) {
            return new KdaCache();
        }
        try {
            KdaCache cache = FileStoreUtil.loadFromFile(
                    cacheFile,
                    new TypeReference<KdaCache>() {
                    });
            if (cache != null && cache.version == 1 && cache.videos != null) {
                return cache;
            }
        } catch (RuntimeException e) {
            log.warn("cannot load adaptive KDA cache, rebuild it, path: {}",
                    cacheFile.getAbsolutePath(), e);
        }
        return new KdaCache();
    }

    private VideoCache prepareVideoCache(KdaCache cache, File video, double durationSeconds) {
        VideoCache videoCache = cache.videos.get(video.getName());
        if (videoCache == null
                || videoCache.samples == null
                || !videoCache.matches(video.length(), video.lastModified(), durationSeconds)) {
            videoCache = new VideoCache();
            videoCache.fileSize = video.length();
            videoCache.lastModified = video.lastModified();
            videoCache.durationSeconds = durationSeconds;
            cache.videos.put(video.getName(), videoCache);
        }
        return videoCache;
    }

    private String frameName(File video, int timestampSeconds, String purpose) {
        return String.format("%s@%06d-%s.jpg", video.getName(), timestampSeconds, purpose);
    }

    /**
     * 保存单个视频扫描期间的运行缓存和计数，避免递归过程重复 OCR 同一个时间点。
     */
    private class ScanSession {
        private final File video;
        private final int lastTimelineSecond;
        private final String cropExpression;
        private final File cacheFile;
        private final KdaCache cache;
        private final VideoCache videoCache;
        private final ScanStatistics statistics;
        private final Map<Integer, LoLPicData> runtimeSamples = new TreeMap<>();
        private final int frameBudget;
        private int framesForVideo;
        private boolean frameBudgetReached;

        private ScanSession(File video,
                            int lastTimelineSecond,
                            String cropExpression,
                            File cacheFile,
                            KdaCache cache,
                            VideoCache videoCache,
                            ScanStatistics statistics) {
            this.video = video;
            this.lastTimelineSecond = lastTimelineSecond;
            this.cropExpression = cropExpression;
            this.cacheFile = cacheFile;
            this.cache = cache;
            this.videoCache = videoCache;
            this.statistics = statistics;
            this.frameBudget = calculateFrameBudget(lastTimelineSecond);
        }

        private LoLPicData sample(int timestampSeconds) {
            LoLPicData runtime = runtimeSamples.get(timestampSeconds);
            if (runtime != null) {
                return runtime;
            }
            statistics.sampledPoints++;

            CachedKda cached = videoCache.samples.get(String.valueOf(timestampSeconds));
            if (cached != null && cached.valid) {
                statistics.cacheHits++;
                LoLPicData cachedData = new LoLPicData(cached.kill, cached.death, cached.assist);
                runtimeSamples.put(timestampSeconds, cachedData);
                return cachedData;
            }
            if (cached != null) {
                videoCache.samples.remove(String.valueOf(timestampSeconds));
            }

            LoLPicData recognized = recognizeWithRetry(timestampSeconds);
            runtimeSamples.put(timestampSeconds, recognized);
            if (recognized.beValid()) {
                videoCache.samples.put(String.valueOf(timestampSeconds),
                        new CachedKda(recognized.getK(), recognized.getD(), recognized.getA()));
            }
            FileStoreUtil.saveToFile(cacheFile, cache);
            return recognized;
        }

        /**
         * 精确 seek 偶尔会落到画面切换帧；失败时再尝试前后 1 秒，提高 OCR 稳定性。
         */
        private LoLPicData recognizeWithRetry(int timestampSeconds) {
            Set<Integer> retrySeconds = new LinkedHashSet<>();
            retrySeconds.add(timestampSeconds);
            retrySeconds.add(Math.max(0, timestampSeconds - SEEK_RETRY_OFFSET_SECONDS));
            retrySeconds.add(Math.min(lastTimelineSecond, timestampSeconds + SEEK_RETRY_OFFSET_SECONDS));
            for (Integer retrySecond : retrySeconds) {
                if (!reserveFrame()) {
                    return LoLPicData.genInvalid();
                }
                InMemoryVideoFrame frame = frameExtractor.extract(video, retrySecond, cropExpression);
                statistics.memoryFrames++;
                statistics.ocrRequests++;
                List<Integer> kda = kdaRecognizer.recognizeKda(
                        frame.getJpegData(), frameName(video, retrySecond, "kda"));
                if (isValidKda(kda)) {
                    return new LoLPicData(kda.get(0), kda.get(1), kda.get(2));
                }
            }
            return LoLPicData.genInvalid();
        }

        /**
         * 预算耗尽后停止新增 OCR，将剩余无法确认的区间按空白处理，保留已识别高光。
         */
        private boolean reserveFrame() {
            if (framesForVideo < frameBudget) {
                framesForVideo++;
                return true;
            }
            if (!frameBudgetReached) {
                log.warn("adaptive frame budget reached, continue with partial timeline, "
                                + "video: {}, budget: {}, logical points: {}",
                        video.getAbsolutePath(), frameBudget,
                        lastTimelineSecond / KDA_SAMPLE_INTERVAL_SECONDS + 1);
                frameBudgetReached = true;
            }
            return false;
        }

        private boolean isValidKda(List<Integer> kda) {
            return kda != null && kda.size() >= 3
                    && kda.get(0) >= 0 && kda.get(1) >= 0 && kda.get(2) >= 0;
        }
    }

    private static class ScanStatistics {
        private int sampledPoints;
        private int memoryFrames;
        private int ocrRequests;
        private int cacheHits;
    }

    @Data
    private static class KdaCache {
        private int version = 1;
        private Map<String, VideoCache> videos = new HashMap<>();
    }

    @Data
    private static class VideoCache {
        private long fileSize;
        private long lastModified;
        private double durationSeconds;
        private Map<String, CachedKda> samples = new HashMap<>();

        private boolean matches(long expectedSize, long expectedModified, double expectedDuration) {
            return fileSize == expectedSize
                    && lastModified == expectedModified
                    && Math.abs(durationSeconds - expectedDuration) < 0.001;
        }
    }

    @Data
    private static class CachedKda {
        private boolean valid = true;
        private int kill;
        private int death;
        private int assist;

        public CachedKda() {
        }

        private CachedKda(int kill, int death, int assist) {
            this.kill = kill;
            this.death = death;
            this.assist = assist;
        }
    }
}
