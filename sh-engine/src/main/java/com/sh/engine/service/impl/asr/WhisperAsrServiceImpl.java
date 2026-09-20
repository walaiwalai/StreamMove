package com.sh.engine.service.impl.asr;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.model.ffmpeg.AudioExtractCmd;
import com.sh.engine.service.AsrService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Whisper ASR Webservice 适配器。先抽取 16 kHz 单声道 WAV，再调用官方 multipart /asr 接口。
 *
 * @see <a href="https://github.com/ahmetoner/whisper-asr-webservice/blob/main/docs/endpoints.md">
 * Whisper ASR Webservice endpoints</a>
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "asr.provider", havingValue = "whisper")
public class WhisperAsrServiceImpl implements AsrService {
    private static final MediaType WAV_MEDIA_TYPE = MediaType.parse("audio/wav");
    private static final int AUDIO_EXTRACTION_TIMEOUT_SECONDS = 300;
    private static final boolean VAD_FILTER = true;
    private static final int CONNECT_TIMEOUT_SECONDS = 20;
    private static final int CALL_TIMEOUT_SECONDS = 900;
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Value("${asr.whisper.base-url}")
    private String baseUrl;
    @Value("${asr.whisper.token}")
    private String token;
    @Value("${asr.whisper.language:zh}")
    private String language;

    private OkHttpClient httpClient;
    private HttpUrl asrEndpoint;

    /** 初始化带独立长超时的 Whisper HTTP 客户端。 */
    @PostConstruct
    public void init() {
        validateConfiguration();
        HttpUrl configuredUrl = HttpUrl.parse(StringUtils.trimToEmpty(baseUrl));
        if (configuredUrl == null) {
            throw new StreamerRecordException(
                    ErrorEnum.INVALID_PARAM, "invalid Whisper base URL");
        }
        asrEndpoint = configuredUrl.newBuilder()
                .addPathSegment("asr")
                .addQueryParameter("output", "json")
                .addQueryParameter("task", "transcribe")
                .addQueryParameter("language", language)
                .addQueryParameter("vad_filter", Boolean.toString(VAD_FILTER))
                .build();
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
        log.info("Whisper ASR initialized, endpoint: {}, language: {}, vadFilter: {}",
                asrEndpoint, language, VAD_FILTER);
    }

    private void validateConfiguration() {
        if (StringUtils.isAnyBlank(language, token)) {
            throw new StreamerRecordException(
                    ErrorEnum.INVALID_PARAM, "invalid Whisper ASR configuration");
        }
    }

    /**
     * 转写视频内的指定区间，返回时间已经换算为源视频内时间的分段。
     */
    @Override
    public List<AsrSegment> transcribeSegment(
            File videoFile, int startSeconds, int endSeconds) {
        validateSegment(videoFile, startSeconds, endSeconds);
        File audioFile = extractAudio(videoFile, startSeconds, endSeconds);
        try {
            return requestTranscription(audioFile, startSeconds);
        } finally {
            deleteTemporaryAudio(audioFile);
        }
    }

    private void validateSegment(File videoFile, int startSeconds, int endSeconds) {
        if (videoFile == null || !videoFile.isFile()
                || startSeconds < 0 || endSeconds <= startSeconds) {
            throw new StreamerRecordException(
                    ErrorEnum.INVALID_PARAM, "invalid Whisper video segment");
        }
    }

    private File extractAudio(File videoFile, int startSeconds, int endSeconds) {
        Path workDirectory = videoFile.getParentFile().toPath()
                .resolve(".highlight-work").resolve("whisper-asr");
        try {
            Files.createDirectories(workDirectory);
        } catch (IOException e) {
            throw new StreamerRecordException(
                    ErrorEnum.ASR_REQUEST_ERROR, "cannot create Whisper work directory", e);
        }

        String fileName = stripExtension(videoFile.getName())
                + "-" + startSeconds + "-" + endSeconds + ".wav";
        File audioFile = workDirectory.resolve(fileName).toFile();
        AudioExtractCmd command = new AudioExtractCmd(
                videoFile, audioFile, startSeconds, endSeconds - startSeconds);
        command.execute(AUDIO_EXTRACTION_TIMEOUT_SECONDS);
        if (!command.isSuccess()) {
            throw new StreamerRecordException(
                    ErrorEnum.ASR_REQUEST_ERROR,
                    "cannot extract audio for Whisper: " + videoFile.getName());
        }
        return audioFile;
    }

    private List<AsrSegment> requestTranscription(File audioFile, int segmentOffsetSeconds) {
        Request request = buildTranscriptionRequest(audioFile);

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new StreamerRecordException(
                        ErrorEnum.ASR_REQUEST_ERROR,
                        "Whisper request failed, HTTP status: " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new StreamerRecordException(
                        ErrorEnum.ASR_REQUEST_ERROR, "Whisper response body is empty");
            }
            return parseTranscription(body.string(), segmentOffsetSeconds);
        } catch (IOException e) {
            throw new StreamerRecordException(
                    ErrorEnum.ASR_REQUEST_ERROR, "cannot call Whisper ASR", e);
        }
    }

    private Request buildTranscriptionRequest(File audioFile) {
        RequestBody fileBody = RequestBody.create(WAV_MEDIA_TYPE, audioFile);
        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audio_file", audioFile.getName(), fileBody)
                .build();
        return new Request.Builder()
                .url(asrEndpoint)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + token)
                .post(requestBody)
                .build();
    }

    private List<AsrSegment> parseTranscription(String response, int segmentOffsetSeconds) {
        if (StringUtils.isBlank(response)) {
            return Collections.emptyList();
        }
        JSONObject responseJson;
        try {
            responseJson = JSON.parseObject(response);
        } catch (RuntimeException e) {
            throw new StreamerRecordException(
                    ErrorEnum.ASR_REQUEST_ERROR, "invalid Whisper JSON response", e);
        }
        JSONArray responseSegments = responseJson.getJSONArray("segments");
        if (responseSegments == null || responseSegments.isEmpty()) {
            return Collections.emptyList();
        }

        List<AsrSegment> segments = new ArrayList<>(responseSegments.size());
        for (int index = 0; index < responseSegments.size(); index++) {
            JSONObject responseSegment = responseSegments.getJSONObject(index);
            String text = StringUtils.trimToEmpty(responseSegment.getString("text"));
            if (text.isEmpty() || responseSegment.get("start") == null
                    || responseSegment.get("end") == null) {
                continue;
            }
            segments.add(AsrSegment.builder()
                    .startTime(segmentOffsetSeconds
                            + (int) Math.floor(responseSegment.getDoubleValue("start")))
                    .endTime(segmentOffsetSeconds
                            + (int) Math.ceil(responseSegment.getDoubleValue("end")))
                    .text(text)
                    .build());
        }
        return segments;
    }

    private String stripExtension(String fileName) {
        int separator = fileName.lastIndexOf('.');
        return separator > 0 ? fileName.substring(0, separator) : fileName;
    }

    private void deleteTemporaryAudio(File audioFile) {
        try {
            if (audioFile != null && !Files.deleteIfExists(audioFile.toPath())) {
                log.debug("Whisper temporary audio already absent: {}", audioFile);
            }
        } catch (IOException e) {
            log.warn("cannot delete Whisper temporary audio: {}", audioFile, e);
        }
    }
}
