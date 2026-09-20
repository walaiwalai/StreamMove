package com.sh.engine.processor.plugin.highlight;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
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
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 图片编辑客户端。只增强真实游戏截图，不让模型生成标题文字或虚构事件。
 *
 * @see <a href="https://developers.openai.com/api/docs/guides/image-generation">
 * OpenAI Image generation guide</a>
 */
@Component
@Slf4j
public class OpenAiCoverClient {
    private static final String OPENAI_PROVIDER = "openai";
    private static final MediaType JPEG_MEDIA_TYPE = MediaType.parse("image/jpeg");
    private static final String COVER_PROMPT =
            "Turn this exact gameplay screenshot into a compelling esports video cover background. "
                    + "Preserve the original scene, HUD, players, weapons, vehicles and visible outcome. "
                    + "Do not add, remove or replace factual game elements, and do not invent explosions "
                    + "or combat. Improve contrast, sharpness, lighting and visual focus. "
                    + "Keep the lower third slightly darker for a later title overlay. "
                    + "Do not render text, logos, borders or watermarks.";

    @Value("${highlight.cover.provider:local}")
    private String provider;
    @Value("${highlight.cover.openai.api-key:}")
    private String apiKey;
    @Value("${highlight.cover.openai.base-url:https://api.openai.com/v1}")
    private String baseUrl;
    @Value("${highlight.cover.openai.model:gpt-image-2}")
    private String model;
    @Value("${highlight.cover.openai.size:1280x720}")
    private String size;
    @Value("${highlight.cover.openai.quality:medium}")
    private String quality;
    @Value("${highlight.cover.openai.connect-timeout-seconds:20}")
    private long connectTimeoutSeconds;
    @Value("${highlight.cover.openai.call-timeout-seconds:180}")
    private long callTimeoutSeconds;

    private OkHttpClient httpClient;
    private HttpUrl editEndpoint;

    /** 初始化可选的 OpenAI 封面客户端；本地模式不建立外部连接。 */
    @PostConstruct
    public void init() {
        if (!OPENAI_PROVIDER.equalsIgnoreCase(StringUtils.trimToEmpty(provider))) {
            return;
        }
        validateConfiguration();
        editEndpoint = HttpUrl.parse(StringUtils.removeEnd(baseUrl.trim(), "/")
                + "/images/edits");
        if (editEndpoint == null) {
            throw new StreamerRecordException(
                    ErrorEnum.INVALID_PARAM, "invalid OpenAI image base URL");
        }
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
                .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
                .build();
        log.info("OpenAI highlight cover initialized, endpoint: {}, model: {}, size: {}, quality: {}",
                editEndpoint, model, size, quality);
    }

    public boolean isEnabled() {
        return httpClient != null && editEndpoint != null;
    }

    /**
     * 使用 GPT Image 编辑真实截图，返回 JPEG 字节。
     */
    public byte[] enhance(byte[] sourceJpeg) {
        if (!isEnabled() || sourceJpeg == null || sourceJpeg.length == 0) {
            throw new StreamerRecordException(
                    ErrorEnum.INVALID_PARAM, "OpenAI cover client or source image is unavailable");
        }
        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("image[]", "highlight-frame.jpg",
                        RequestBody.create(JPEG_MEDIA_TYPE, sourceJpeg))
                .addFormDataPart("prompt", COVER_PROMPT)
                .addFormDataPart("size", size)
                .addFormDataPart("quality", quality)
                .addFormDataPart("output_format", "jpeg")
                .addFormDataPart("output_compression", "90")
                .build();
        Request request = new Request.Builder()
                .url(editEndpoint)
                .header("Authorization", "Bearer " + apiKey)
                .post(requestBody)
                .build();
        return execute(request);
    }

    private byte[] execute(Request request) {
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful()) {
                throw new StreamerRecordException(
                        ErrorEnum.COVER_GENERATION_ERROR,
                        "OpenAI image edit failed, HTTP status: " + response.code()
                                + ", requestId: " + response.header("x-request-id", "unknown"));
            }
            if (body == null) {
                throw new StreamerRecordException(
                        ErrorEnum.COVER_GENERATION_ERROR, "OpenAI image response body is empty");
            }
            return parseImage(body.string());
        } catch (IOException e) {
            throw new StreamerRecordException(
                    ErrorEnum.COVER_GENERATION_ERROR, "cannot call OpenAI image edit API", e);
        }
    }

    private byte[] parseImage(String response) {
        try {
            JSONObject responseJson = JSON.parseObject(response);
            JSONArray data = responseJson.getJSONArray("data");
            String encoded = data == null || data.isEmpty()
                    ? null : data.getJSONObject(0).getString("b64_json");
            if (StringUtils.isBlank(encoded)) {
                throw new IllegalArgumentException("missing data[0].b64_json");
            }
            return Base64.getDecoder().decode(encoded);
        } catch (RuntimeException e) {
            throw new StreamerRecordException(
                    ErrorEnum.COVER_GENERATION_ERROR, "invalid OpenAI image response", e);
        }
    }

    private void validateConfiguration() {
        if (StringUtils.isAnyBlank(apiKey, baseUrl, model, size, quality)
                || connectTimeoutSeconds <= 0 || callTimeoutSeconds <= 0) {
            throw new StreamerRecordException(
                    ErrorEnum.INVALID_PARAM, "invalid OpenAI highlight cover configuration");
        }
    }
}
