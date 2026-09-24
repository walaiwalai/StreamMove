package com.sh.engine.processor.plugin.valorant;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.engine.model.ffmpeg.FFmpegProcessCmd;
import com.sh.engine.model.highlight.VideoInterval;
import com.sh.engine.service.VideoMergeService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 使用流复制快速导出每一局完整比赛。
 */
@Component
@Slf4j
public class ValorantFullGameExporter {
    public static final String OUTPUT_DIRECTORY_NAME = "full-games";
    private static final String WORK_DIRECTORY_NAME = ".work";
    private static final long FFMPEG_TIMEOUT_SECONDS = 2 * 3600L;

    private final VideoMergeService videoMergeService;

    public ValorantFullGameExporter(VideoMergeService videoMergeService) {
        this.videoMergeService = videoMergeService;
    }

    public List<File> export(String recordPath,
                             List<ValorantDetectedMatch> matches) {
        File outputDirectory = new File(recordPath, OUTPUT_DIRECTORY_NAME);
        File workDirectory = new File(outputDirectory, WORK_DIRECTORY_NAME);
        prepareDirectory(outputDirectory, workDirectory);

        List<File> outputs = new ArrayList<>();
        try {
            for (int index = 0; index < matches.size(); index++) {
                ValorantDetectedMatch match = matches.get(index);
                File target = new File(outputDirectory,
                        String.format(Locale.ROOT, "game-%03d.mp4", index + 1));
                exportMatch(match, index + 1, target, workDirectory);
                outputs.add(target);
            }
            deleteStaleOutputs(outputDirectory, outputs);
            writeManifest(outputDirectory, matches, outputs);
            return outputs;
        } finally {
            FileUtils.deleteQuietly(workDirectory);
        }
    }

    private void prepareDirectory(File outputDirectory, File workDirectory) {
        try {
            FileUtils.forceMkdir(outputDirectory);
            FileUtils.deleteDirectory(workDirectory);
            FileUtils.forceMkdir(workDirectory);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "cannot prepare valorant full game output directory", e);
        }
    }

    private void exportMatch(ValorantDetectedMatch match,
                             int matchIndex,
                             File target,
                             File workDirectory) {
        List<String> segmentPaths = new ArrayList<>();
        for (int index = 0; index < match.getIntervals().size(); index++) {
            VideoInterval interval = match.getIntervals().get(index);
            File segment = new File(workDirectory,
                    String.format(Locale.ROOT, "game-%03d-part-%02d.mp4",
                            matchIndex, index + 1));
            cutInterval(interval, segment);
            segmentPaths.add(segment.getAbsolutePath());
        }

        File completed = new File(workDirectory,
                String.format(Locale.ROOT, "game-%03d-complete.mp4", matchIndex));
        if (segmentPaths.size() == 1) {
            moveReplacing(new File(segmentPaths.get(0)), completed);
        } else if (!videoMergeService.concatWithSameVideo(segmentPaths, completed)) {
            throw new IllegalStateException("cannot concatenate valorant match " + matchIndex);
        }
        moveReplacing(completed, target);
        log.info("valorant full game exported, target: {}, start: {}, end: {}, parts: {}",
                target.getAbsolutePath(), match.getGlobalStartSecond(),
                match.getGlobalEndSecond(), match.getIntervals().size());
    }

    private void cutInterval(VideoInterval interval, File target) {
        double duration = interval.getSecondToVideoEnd()
                - interval.getSecondFromVideoStart();
        if (duration <= 0) {
            throw new IllegalArgumentException("invalid valorant match interval duration");
        }
        String command = String.format(Locale.ROOT,
                "ffmpeg -y -hide_banner -loglevel error -i \"%s\" "
                        + "-ss %.3f -t %.3f -map 0:v:0 -map 0:a? -c copy "
                        + "-avoid_negative_ts make_zero \"%s\"",
                interval.getFromVideo().getAbsolutePath(),
                interval.getSecondFromVideoStart(), duration,
                target.getAbsolutePath());
        FFmpegProcessCmd process = new FFmpegProcessCmd(command);
        process.execute(FFMPEG_TIMEOUT_SECONDS);
        if (!process.isEndNormal() || !target.isFile() || target.length() == 0) {
            throw new IllegalStateException(
                    "cannot cut valorant match interval from "
                            + interval.getFromVideo().getAbsolutePath());
        }
    }

    private void deleteStaleOutputs(File outputDirectory, List<File> outputs) {
        Set<String> currentNames = new HashSet<>();
        for (File output : outputs) {
            currentNames.add(output.getName());
        }
        File[] existingOutputs = outputDirectory.listFiles(
                (directory, name) -> name.matches("game-\\d{3}\\.mp4"));
        if (existingOutputs == null) {
            return;
        }
        for (File existingOutput : existingOutputs) {
            if (currentNames.contains(existingOutput.getName())) {
                continue;
            }
            try {
                Files.deleteIfExists(existingOutput.toPath());
            } catch (IOException e) {
                throw new IllegalStateException(
                        "cannot delete stale valorant output " + existingOutput, e);
            }
        }
    }

    private void writeManifest(File outputDirectory,
                               List<ValorantDetectedMatch> matches,
                               List<File> outputs) {
        JSONObject root = new JSONObject(true);
        root.put("sampleIntervalSeconds",
                ValorantScoreTimelineScanner.SAMPLE_INTERVAL_SECONDS);
        root.put("ocrBatchFrames",
                ValorantScoreTimelineScanner.BATCH_FRAME_COUNT);
        JSONArray items = new JSONArray();
        for (int index = 0; index < matches.size(); index++) {
            ValorantDetectedMatch match = matches.get(index);
            JSONObject item = new JSONObject(true);
            item.put("file", outputs.get(index).getName());
            item.put("globalStartSecond", match.getGlobalStartSecond());
            item.put("globalEndSecond", match.getGlobalEndSecond());
            JSONArray intervals = new JSONArray();
            for (VideoInterval interval : match.getIntervals()) {
                JSONObject intervalJson = new JSONObject(true);
                intervalJson.put("source", interval.getFromVideo().getName());
                intervalJson.put("startSecond", interval.getSecondFromVideoStart());
                intervalJson.put("endSecond", interval.getSecondToVideoEnd());
                intervals.add(intervalJson);
            }
            item.put("intervals", intervals);
            items.add(item);
        }
        root.put("matches", items);
        try {
            FileUtils.writeStringToFile(
                    new File(outputDirectory, "manifest.json"),
                    JSON.toJSONString(root, true), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write valorant full game manifest", e);
        }
    }

    private void moveReplacing(File source, File target) {
        try {
            Files.move(source.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "cannot move valorant full game output to " + target, e);
        }
    }
}
