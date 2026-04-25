package com.sh.engine.service.impl.asr;

import cn.hutool.core.io.FileUtil;
import com.alibaba.dashscope.audio.asr.transcription.*;
import com.alibaba.dashscope.common.TaskStatus;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.model.ffmpeg.AudioExtractCmd;
import com.sh.engine.service.AsrService;
import com.sh.engine.service.OssUploadService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Alibaba Cloud DashScope ASR (Fun-ASR) implementation.
 * <p>
 * Uses the DashScope SDK for recorded speech recognition.
 * <p>
 * Configuration properties:
 * <ul>
 *   <li>asr.provider - Set to "aliyun" to enable this implementation</li>
 *   <li>asr.aliyun.api-key - DashScope API Key</li>
 *   <li>asr.aliyun.model - Model name (default: fun-asr)</li>
 * </ul>
 *
 * @Author : caiwen
 * @Date: 2026/2/19
 * @see <a href="https://help.aliyun.com/zh/model-studio/fun-asr-recorded-speech-recognition-java-sdk">Fun-ASR Java SDK</a>
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "asr.provider", havingValue = "aliyun")
public class AliyunAsrServiceImpl implements AsrService {

    @Value("${asr.aliyun.api-key}")
    private String apiKey;

    @Value("${asr.aliyun.model}")
    private String model;

    @Resource
    private OssUploadService ossUploadService;

    private final Gson gson = new Gson();

    @Override
    public List<AsrSegment> transcribeSegment(File videoFile, int startSeconds, int endSeconds) {
        // 1. Extract audio from video segment
        File tempAudioFile = extractAudio(videoFile, startSeconds, endSeconds);
        if (tempAudioFile == null) {
            return Collections.emptyList();
        }

        // 2. Upload to OSS to get public URL
        // Note: Alibaba Cloud ASR requires public HTTP URL, does not support local file path
        String ossKey = "asr/" + StreamerInfoHolder.getCurStreamerName() + "/" + tempAudioFile.getName();
        String audioUrl = ossUploadService.uploadAndGetUrl(tempAudioFile, ossKey);

        // 3. Submit ASR task
        return submitAsrTask(audioUrl, startSeconds);
    }

    /**
     * Extract audio from video segment using FFmpeg
     */
    private File extractAudio(File videoFile, int startSeconds, int endSeconds) {
        File tempDir = new File(videoFile.getParentFile(), "asr");
        FileUtil.mkdir(tempDir);

        File audioFile = new File(tempDir, FileUtil.getPrefix(videoFile) + "-" + startSeconds + "-" + endSeconds + ".wav");
        int duration = endSeconds - startSeconds;
        AudioExtractCmd cmd = new AudioExtractCmd(videoFile, audioFile, startSeconds, duration);
        cmd.execute(300);

        if (cmd.isSuccess()) {
            log.error("Audio extraction successfully, video: {}, segment: {}-{}s", videoFile.getName(), startSeconds, endSeconds);
            return audioFile;
        } else {
            log.error("Audio extraction failed, video: {}, segment: {}-{}s", videoFile.getName(), startSeconds, endSeconds);
            return null;
        }
    }

    /**
     * Submit ASR task and wait for result
     */
    private List<AsrSegment> submitAsrTask(String audioUrl, int segmentOffsetSeconds) {
        // Build request parameters
        TranscriptionParam param = TranscriptionParam.builder()
                .apiKey(apiKey)
                .model(model)
                .fileUrls(Collections.singletonList(audioUrl))
                .parameter("language_hints", new String[]{"zh", "en"})
                .build();

        Transcription transcription = new Transcription();

        // Submit async task
        TranscriptionResult result = transcription.asyncCall(param);
        String taskId = result.getTaskId();
        log.info("ASR task submitted, taskId: {}", taskId);

        // Wait for task completion (blocking)
        TranscriptionQueryParam queryParam = TranscriptionQueryParam.FromTranscriptionParam(param, taskId);
        result = transcription.wait(queryParam);

        // Parse results
        return parseAsrResult(result, segmentOffsetSeconds);
    }

    /**
     * Parse ASR result from transcription response
     */
    private List<AsrSegment> parseAsrResult(TranscriptionResult result, int segmentOffsetSeconds) {
        List<AsrSegment> segments = new ArrayList<>();
        if (CollectionUtils.isEmpty(result.getResults())) {
            log.warn("ASR result is empty");
            return segments;
        }

        for (TranscriptionTaskResult taskResult : result.getResults()) {
            if (taskResult.getSubTaskStatus() != TaskStatus.SUCCEEDED) {
                log.warn("ASR subtask failed: status={}, message={}",
                        taskResult.getSubTaskStatus(), taskResult.getMessage());
                continue;
            }

            String transcriptionUrl = taskResult.getTranscriptionUrl();
            if (transcriptionUrl == null || transcriptionUrl.isEmpty()) {
                log.warn("ASR transcription URL is empty");
                continue;
            }

            // Download and parse transcription JSON
            List<AsrSegment> taskSegments = downloadAndParseTranscription(transcriptionUrl, segmentOffsetSeconds);
            segments.addAll(taskSegments);
        }

        return segments;
    }

    /**
     * Download and parse transcription result JSON
     */
    private List<AsrSegment> downloadAndParseTranscription(String url, int segmentOffsetSeconds) {
        List<AsrSegment> segments = new ArrayList<>();
        Request request = new Request.Builder().url(url).get().build();
        String json = OkHttpClientUtil.execute(request);
        JsonObject jsonResult = gson.fromJson(json, JsonObject.class);

        JsonArray transcripts = jsonResult.getAsJsonArray("transcripts");
        if (transcripts == null || transcripts.isJsonNull()) {
            log.warn("No transcripts in ASR result");
            return segments;
        }

        for (JsonElement transcript : transcripts) {
            JsonArray sentences = transcript.getAsJsonObject().getAsJsonArray("sentences");
            if (sentences == null) {
                continue;
            }
            for (JsonElement sentence : sentences) {
                JsonObject sentObj = sentence.getAsJsonObject();
                int startMs = sentObj.get("begin_time").getAsInt();
                int endMs = sentObj.get("end_time").getAsInt();
                String text = sentObj.get("text").getAsString();

                segments.add(AsrSegment.builder()
                        .startTime(segmentOffsetSeconds + (startMs / 1000))
                        .endTime(segmentOffsetSeconds + (endMs / 1000))
                        .text(text)
                        .build());
            }
        }
        return segments;
    }
}
