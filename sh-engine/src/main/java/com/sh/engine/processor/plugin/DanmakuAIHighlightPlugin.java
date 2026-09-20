package com.sh.engine.processor.plugin;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.model.storage.FileStatusModel;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.danmaku.ConfirmedHighlight;
import com.sh.engine.model.danmaku.DanmakuTimeBucket;
import com.sh.engine.model.llm.LlmResponseException;
import com.sh.engine.processor.plugin.highlight.danmaku.DanmakuHighlightArtifactGenerator;
import com.sh.engine.processor.plugin.highlight.danmaku.DanmakuHighlightCandidateAnalyzer;
import com.sh.engine.processor.recorder.danmu.SimpleDanmaku;
import com.sh.engine.service.DanmakuAnalysisService;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 带弹幕直播高光处理入口。弹幕只召回候选，最终结果由多模态证据链确认。
 */
@Component
@Slf4j
public class DanmakuAIHighlightPlugin implements VideoProcessPlugin {
    private static final int MINIMUM_DANMAKU_COUNT = 500;
    @Resource
    private DanmakuAnalysisService danmakuAnalysisService;
    @Resource
    private DanmakuHighlightCandidateAnalyzer candidateAnalyzer;
    @Resource
    private DanmakuHighlightArtifactGenerator artifactGenerator;
    @Resource
    private MsgSendService msgSendService;

    @Override
    public String getPluginName() {
        return ProcessPluginEnum.DAN_MU_HL_VOD_CUT.getType();
    }

    @Override
    public int getMaxProcessParallel() {
        return 1;
    }

    /**
     * 执行候选召回、逐候选多模态验真和最终视频产物生成。
     */
    @Override
    public boolean process(String recordPath) {
        File recordDirectory = new File(recordPath);
        List<SimpleDanmaku> danmakus = readDanmaku(
                new File(recordDirectory, RecordConstant.DAMAKU_TXT_ALL_FILE));
        if (danmakus.size() < MINIMUM_DANMAKU_COUNT) {
            log.info("Danmaku count {} is less than minimum {}, skipping",
                    danmakus.size(), MINIMUM_DANMAKU_COUNT);
            return true;
        }
        List<File> videoFiles = VideoFileUtil.listIndexedMp4Files(recordPath);
        if (videoFiles.isEmpty()) {
            log.error("No source video files found: {}", recordPath);
            return false;
        }
        FileStatusModel fileStatus = FileStatusModel.loadFromFile(recordPath);
        List<DanmakuTimeBucket> candidates =
                danmakuAnalysisService.analyzeDanmakuPeak(recordPath, danmakus);
        List<ConfirmedHighlight> confirmed = analyzeCandidates(
                candidates, videoFiles, fileStatus,
                StreamerInfoHolder.getCurStreamerName());
        if (confirmed.isEmpty()) {
            artifactGenerator.clearGeneratedArtifacts(recordPath);
            log.info("No confirmed highlights found: {}", recordPath);
            return true;
        }
        File highlightFile = new File(recordDirectory, RecordConstant.HL_VIDEO);
        boolean success = artifactGenerator.generate(recordPath, highlightFile, confirmed);
        msgSendService.sendText((success
                ? "AI highlight generation completed! Path: "
                : "AI highlight generation failed! Path: ")
                + highlightFile.getAbsolutePath());
        return success;
    }

    private List<ConfirmedHighlight> analyzeCandidates(
            List<DanmakuTimeBucket> candidates,
            List<File> videoFiles,
            FileStatusModel fileStatus,
            String streamerName) {
        List<ConfirmedHighlight> confirmed = new ArrayList<>();
        int protocolFailureCount = 0;
        for (DanmakuTimeBucket candidate : candidates) {
            try {
                confirmed.addAll(analyzeCandidate(
                        candidate, videoFiles, fileStatus, streamerName));
            } catch (LlmResponseException e) {
                protocolFailureCount++;
                log.error("Skipping candidate after an unrecoverable invalid LLM response, "
                                + "range: {}-{}s; available raw responses were written to audit",
                        candidate.getStartTime(), candidate.getEndTime());
            }
        }
        if (protocolFailureCount > 0) {
            log.warn("Highlight candidate batch completed with {} isolated LLM "
                            + "protocol failures and {} confirmed highlights",
                    protocolFailureCount, confirmed.size());
        }
        return confirmed;
    }

    private List<ConfirmedHighlight> analyzeCandidate(
            DanmakuTimeBucket candidate,
            List<File> videoFiles,
            FileStatusModel fileStatus,
            String streamerName) {
        VideoLocation start = locate(
                videoFiles, fileStatus, candidate.getStartTime());
        int endLookupTime = Math.max(candidate.getStartTime(), candidate.getEndTime() - 1);
        VideoLocation end = locate(videoFiles, fileStatus, endLookupTime);
        if (start == null || end == null) {
            log.warn("Cannot find video location for candidate: {}-{}s",
                    candidate.getStartTime(), candidate.getEndTime());
            return Collections.emptyList();
        }
        if (!start.videoFile.equals(end.videoFile)) {
            log.info("Skipping cross-boundary candidate: {}-{}s, from {} to {}",
                    candidate.getStartTime(), candidate.getEndTime(),
                    start.videoFile.getName(), end.videoFile.getName());
            return Collections.emptyList();
        }
        int fileEnd = end.offsetSecond + 1;
        int sessionToFileOffset = candidate.getStartTime() - start.offsetSecond;
        return candidateAnalyzer.analyze(
                start.videoFile, candidate, streamerName,
                start.offsetSecond, fileEnd, sessionToFileOffset);
    }

    private VideoLocation locate(
            List<File> videoFiles, FileStatusModel fileStatus, int sessionSecond) {
        if (fileStatus == null || fileStatus.getMetaMap() == null) {
            return videoFiles.size() == 1
                    ? new VideoLocation(videoFiles.get(0), sessionSecond) : null;
        }
        long baseTimestamp = fileStatus.getMetaMap().values().stream()
                .filter(item -> item != null)
                .mapToLong(FileStatusModel.VideoMetaInfo::getRecordStartTimeStamp)
                .min().orElse(0L);
        long targetTimestamp = baseTimestamp + sessionSecond;
        for (File videoFile : videoFiles) {
            FileStatusModel.VideoMetaInfo metadata =
                    fileStatus.getMetaMap().get(videoFile.getName());
            if (metadata != null
                    && targetTimestamp >= metadata.getRecordStartTimeStamp()
                    && targetTimestamp < metadata.getRecordEndTimeStamp()) {
                return new VideoLocation(videoFile,
                        (int) (targetTimestamp - metadata.getRecordStartTimeStamp()));
            }
        }
        return null;
    }

    private List<SimpleDanmaku> readDanmaku(File file) {
        if (!file.isFile()) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "danmaku file does not exist: " + file.getAbsolutePath());
        }
        List<SimpleDanmaku> danmakus = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(file.toPath()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                SimpleDanmaku danmaku = line.trim().isEmpty()
                        ? null : SimpleDanmaku.fromLine(line);
                if (danmaku != null) {
                    danmakus.add(danmaku);
                }
            }
            return danmakus;
        } catch (IOException e) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "cannot read danmaku file: " + file.getAbsolutePath(), e);
        }
    }

    private static final class VideoLocation {
        private final File videoFile;
        private final int offsetSecond;

        private VideoLocation(File videoFile, int offsetSecond) {
            this.videoFile = videoFile;
            this.offsetSecond = offsetSecond;
        }
    }
}
