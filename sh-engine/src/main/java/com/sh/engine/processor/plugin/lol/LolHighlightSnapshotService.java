package com.sh.engine.processor.plugin.lol;

import com.alibaba.fastjson.TypeReference;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.utils.FileStoreUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.model.ffmpeg.ScreenshotCmd;
import com.sh.engine.model.ffmpeg.VideoDurationDetectCmd;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.DEFAULT_KDA_CROP_EXPRESSION;
import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.KDA_SNAPSHOT_DIR;
import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.KDA_TEST_CROP_EXPRESSION;
import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.KDA_TEST_SNAPSHOT_DIR;
import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.SNAP_INTERVAL_SECONDS;

/**
 * 负责定位 KDA 区域并按固定间隔生成 KDA 截图。
 */
@Component
@Slf4j
public class LolHighlightSnapshotService {
    private static final String SNAPSHOT_RECORD_FILE = "snap-record.json";
    private static final String ACCURATE_CROP_RECORD_FILE = "accurate-corp.json";

    @Resource
    private LolOcrClient ocrClient;

    public List<File> createKdaSnapshots(String recordPath, List<File> videos) {
        String cropExpression = findKdaCropExpression(recordPath, videos.get(0));
        File snapshotDirectory = new File(recordPath, KDA_SNAPSHOT_DIR);
        snapshotDirectory.mkdirs();

        List<File> snapshots = new ArrayList<>();
        for (File video : videos) {
            snapshots.addAll(createSnapshotsForVideo(video, snapshotDirectory, cropExpression));
        }
        snapshots.sort(this::compareSnapshots);
        return snapshots;
    }

    private List<File> createSnapshotsForVideo(File video, File snapshotDirectory, String cropExpression) {
        File recordFile = new File(snapshotDirectory, SNAPSHOT_RECORD_FILE);
        Map<String, List<String>> videoSnapshots = loadSnapshotRecord(recordFile);

        if (videoSnapshots.containsKey(video.getName())) {
            return videoSnapshots.get(video.getName()).stream()
                    .map(name -> new File(snapshotDirectory, name))
                    .collect(Collectors.toList());
        }

        ScreenshotCmd command = new ScreenshotCmd(
                video, snapshotDirectory, 0, 99999, cropExpression,
                SNAP_INTERVAL_SECONDS, 1, false);
        command.execute(3600);
        List<File> snapshots = Lists.newArrayList(command.getSnapshotFiles());

        videoSnapshots.put(video.getName(), snapshots.stream()
                .map(File::getName)
                .collect(Collectors.toList()));
        FileStoreUtil.saveToFile(recordFile, videoSnapshots);
        return snapshots;
    }

    private Map<String, List<String>> loadSnapshotRecord(File recordFile) {
        if (!recordFile.exists()) {
            return new HashMap<>();
        }
        return FileStoreUtil.loadFromFile(
                recordFile,
                new TypeReference<Map<String, List<String>>>() {
                });
    }

    public String findKdaCropExpression(String recordPath, File sampleVideo) {
        File testSnapshotDirectory = new File(recordPath, KDA_TEST_SNAPSHOT_DIR);
        testSnapshotDirectory.mkdirs();

        File cropRecordFile = new File(testSnapshotDirectory, ACCURATE_CROP_RECORD_FILE);
        if (cropRecordFile.exists()) {
            Map<String, String> cropByVideo = FileStoreUtil.loadFromFile(
                    cropRecordFile,
                    new TypeReference<Map<String, String>>() {
                    });
            if (cropByVideo.containsKey(sampleVideo.getName())) {
                String cachedCrop = cropByVideo.get(sampleVideo.getName());
                log.info("find kda corp exp success, video: {}, corpExp: {}",
                        sampleVideo.getName(), cachedCrop);
                return cachedCrop;
            }
        }

        String detectedCrop = detectAccurateCrop(sampleVideo, testSnapshotDirectory);
        if (StringUtils.isBlank(detectedCrop)) {
            detectedCrop = DEFAULT_KDA_CROP_EXPRESSION;
        }

        log.info("find accurate kda corp exp: {}", detectedCrop);
        FileStoreUtil.saveToFile(
                cropRecordFile,
                Maps.newHashMap(ImmutableMap.of(sampleVideo.getName(), detectedCrop)));
        return detectedCrop;
    }

    private String detectAccurateCrop(File sampleVideo, File testSnapshotDirectory) {
        VideoDurationDetectCmd durationCommand = new VideoDurationDetectCmd(sampleVideo.getAbsolutePath());
        durationCommand.execute(60);
        double videoEndSecond = durationCommand.getDurationSeconds();

        int screenshotsPerBatch = 20;
        int startSecond = 0;
        while (startSecond < videoEndSecond) {
            ScreenshotCmd screenshotCommand = new ScreenshotCmd(
                    sampleVideo, testSnapshotDirectory, startSecond, screenshotsPerBatch,
                    KDA_TEST_CROP_EXPRESSION, SNAP_INTERVAL_SECONDS, 1, false);
            screenshotCommand.execute(1800);

            String cropExpression = detectCropFromSnapshots(screenshotCommand.getSnapshotFiles());
            if (StringUtils.isNotBlank(cropExpression)) {
                return cropExpression;
            }
            startSecond += screenshotsPerBatch * SNAP_INTERVAL_SECONDS;
        }
        return null;
    }

    private String detectCropFromSnapshots(List<File> snapshots) {
        for (File snapshot : snapshots) {
            List<List<Integer>> kdaBoxes;
            try {
                kdaBoxes = ocrClient.detectKdaBox(snapshot);
            } catch (Exception e) {
                log.error("error to detect kda box, path: {}", snapshot.getAbsolutePath(), e);
                continue;
            }
            if (CollectionUtils.isNotEmpty(kdaBoxes)) {
                String cropExpression = createCropExpression(kdaBoxes);
                if (StringUtils.isNotBlank(cropExpression)) {
                    return cropExpression;
                }
            }
        }
        return null;
    }

    static String createCropExpression(List<List<Integer>> boxes) {
        if (boxes == null || boxes.size() != 4) {
            log.error("The boxes list must contain exactly 4 points, boxes: {}", boxes);
            return null;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (List<Integer> point : boxes) {
            int x = point.get(0);
            int y = point.get(1);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        int width = maxX - minX;
        int height = maxY - minY;
        return String.format("crop=%d:%d:in_w/2+%d:%d", width + 20, height + 10, minX, minY - 5);
    }

    private int compareSnapshots(File first, File second) {
        Integer firstVideo = VideoFileUtil.getSnapshotVid(first);
        Integer secondVideo = VideoFileUtil.getSnapshotVid(second);
        if (Objects.equals(firstVideo, secondVideo)) {
            return Integer.compare(
                    VideoFileUtil.getSnapshotIndex(first),
                    VideoFileUtil.getSnapshotIndex(second));
        }
        return firstVideo.compareTo(secondVideo);
    }
}
