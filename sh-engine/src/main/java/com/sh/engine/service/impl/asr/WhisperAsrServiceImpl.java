package com.sh.engine.service.impl.asr;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.model.ffmpeg.AudioExtractCmd;
import com.sh.config.utils.EnvUtil;
import com.sh.engine.task.ComputeTasks;
import com.sh.engine.service.AsrService;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 本地 Whisper HTTP 转写；仅处理调用方已经召回的候选范围。 */
@Service
@ConditionalOnProperty(name = "asr.provider", havingValue = "whisper")
public class WhisperAsrServiceImpl implements AsrService {
    @javax.annotation.Resource private ComputeTasks computeTasks;
    @Value("${asr.whisper.url:}")
    private String url;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build();

    @Override
    public List<AsrSegment> transcribeSegment(File video, int start, int end) {
        if (!EnvUtil.isTaskMode()) {
            return computeTasks.transcribeSegment(video, start, end);
        }
        if (start < 0 || end <= start || url.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid Whisper interval or service URL");
        }
        File audio = null;
        try {
            audio = Files.createTempFile("highlight-asr-", ".wav").toFile();
            AudioExtractCmd command = new AudioExtractCmd(video, audio, start, end - start);
            command.execute(120);
            if (!command.isSuccess()) {
                throw new IllegalStateException("Candidate audio extraction failed");
            }
            return transcribeAudio(audio, start);
        } catch (Exception error) {
            throw new IllegalStateException("Whisper candidate transcription failed", error);
        } finally {
            if (audio != null) {
                audio.delete();
            }
        }
    }

    @Override
    public List<AsrSegment> transcribeAudio(File audio, int offsetSeconds) {
        if (!EnvUtil.isTaskMode()) {
            return computeTasks.transcribeAudio(audio, offsetSeconds);
        }
        if (audio == null || !audio.isFile() || offsetSeconds < 0 || url.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid Whisper audio or service URL");
        }
        try {
            HttpUrl endpoint = HttpUrl.parse(url);
            if (endpoint == null) {
                throw new IllegalArgumentException("Invalid Whisper URL");
            }
            RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("audio_file", "candidate.wav",
                            RequestBody.create(MediaType.parse("audio/wav"), audio)).build();
            Request request = new Request.Builder().url(endpoint.newBuilder()
                    .addQueryParameter("language", "zh").addQueryParameter("output", "json")
                    .addQueryParameter("vad_filter", "true").build()).post(body).build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IllegalStateException("Whisper HTTP " + response.code());
                }
                return parse(response.body().string(), offsetSeconds);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Whisper candidate transcription failed", error);
        }
    }

    static List<AsrSegment> parse(String response, int offset) {
        JSONObject json = JSON.parseObject(response);
        JSONArray segments = json.getJSONArray("segments");
        if (segments == null) {
            throw new IllegalStateException("Whisper response is missing timestamped segments");
        }
        List<AsrSegment> result = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            JSONObject segment = segments.getJSONObject(i);
            result.add(AsrSegment.builder()
                    .startTime(offset + (int) Math.floor(segment.getDoubleValue("start")))
                    .endTime(offset + (int) Math.ceil(segment.getDoubleValue("end")))
                    .text(segment.getString("text")).build());
        }
        return result;
    }
}
