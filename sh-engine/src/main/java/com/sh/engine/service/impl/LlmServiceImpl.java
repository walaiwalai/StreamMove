package com.sh.engine.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.llm.LlmCallResult;
import com.sh.engine.model.llm.LlmImageEditResult;
import com.sh.engine.model.llm.LlmImageInput;
import com.sh.engine.model.llm.LlmResponseException;
import com.sh.engine.service.LlmService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@ConditionalOnProperty(name = "llm.provider", havingValue = "langchain4j")
public class LlmServiceImpl implements LlmService {
    private static final String IMAGE_SIZE = "1280*720";
    private static final String JPEG_DATA_URL_PREFIX = "data:image/jpeg;base64,";
    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.parse("application/json; charset=utf-8");
    private static final int CHAT_CONNECT_TIMEOUT_SECONDS = 20;
    private static final int CHAT_TIMEOUT_SECONDS = 90;
    private static final int CHAT_MAX_OUTPUT_TOKENS = 4000;
    private static final int VISION_MAX_OUTPUT_TOKENS = 5000;
    private static final int MAXIMUM_VISION_IMAGE_COUNT = 48;
    private static final long MAXIMUM_VISION_RAW_IMAGE_BYTES = 30L * 1024L * 1024L;
    private static final int IMAGE_CONNECT_TIMEOUT_SECONDS = 20;
    private static final int IMAGE_CALL_TIMEOUT_SECONDS = 180;
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            4, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(128),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    @Value("${langchain4j.open-ai.api-key}")
    private String chatApiKey;

    @Value("${langchain4j.open-ai.base-url}")
    private String chatBaseUrl;

    @Value("${langchain4j.open-ai.model-name}")
    private String chatModelName;

    @Value("${langchain4j.open-ai.proxy-url:}")
    private String proxyUrl;

    @Value("${llm.vision.model-name:}")
    private String visionModelName;

    @Value("${llm.image.api-key:}")
    private String imageApiKey;

    @Value("${llm.image.base-url}")
    private String imageBaseUrl;

    @Value("${llm.image.model-name}")
    private String imageModelName;

    private OkHttpClient chatHttpClient;
    private HttpUrl chatCompletionEndpoint;
    private OkHttpClient imageHttpClient;
    private HttpUrl imageEditEndpoint;

    @PostConstruct
    public void init() {
        Proxy proxy = buildProxy();
        this.chatCompletionEndpoint = buildChatCompletionEndpoint(chatBaseUrl);
        this.imageEditEndpoint = HttpUrl.parse(imageBaseUrl);
        if (imageEditEndpoint == null) {
            throw new StreamerRecordException(
                    ErrorEnum.INVALID_PARAM, "invalid LLM image API URL");
        }
        OkHttpClient.Builder chatHttpClientBuilder = new OkHttpClient.Builder()
                .connectTimeout(CHAT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(CHAT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(CHAT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CHAT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        this.imageHttpClient = new OkHttpClient.Builder()
                .connectTimeout(IMAGE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(IMAGE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(IMAGE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(IMAGE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
        if (proxy != null) {
            chatHttpClientBuilder.proxy(proxy);
        }
        this.chatHttpClient = chatHttpClientBuilder.build();
        log.info("LlmService initialized, chatBaseUrl: {}, chatModel: {}, "
                        + "visionModel: {}, imageBaseUrl: {}, imageModel: {}, "
                        + "chatProxyConfigured: {}",
                chatBaseUrl, chatModelName, visionModelName,
                imageBaseUrl, imageModelName, proxy != null);
    }

    @Override
    public <T> T chat(String prompt, Class<T> resultType) {
        return chatWithRaw(prompt, resultType).getResult();
    }

    @Override
    public <T> LlmCallResult<T> chatWithRaw(String prompt, Class<T> resultType) {
        log.info("LLM request, promptLength: {}", prompt.length());
        log.debug("LLM request prompt:\n{}", prompt);
        RawChatResponse response = executeChat(createChatRequest(prompt));
        log.debug("LLM response:\n{}", response.assistantContent);
        return toCallResult(response, resultType);
    }

    @Override
    public <T> CompletableFuture<T> chatAsync(String prompt, Class<T> resultType) {
        return CompletableFuture.supplyAsync(() -> chat(prompt, resultType), EXECUTOR);
    }

    /**
     * 使用 DeepSeek OpenAI 兼容多图片输入生成结构化视觉时间线。
     */
    @Override
    public <T> T analyzeImages(
            String prompt, List<LlmImageInput> images, Class<T> resultType) {
        return analyzeImagesWithRaw(prompt, images, resultType).getResult();
    }

    @Override
    public <T> LlmCallResult<T> analyzeImagesWithRaw(
            String prompt, List<LlmImageInput> images, Class<T> resultType) {
        validateVisionRequest(prompt, images, resultType);
        log.info("LLM vision request, promptLength: {}, imageCount: {}",
                prompt.length(), images.size());
        log.debug("LLM vision request prompt:\n{}", prompt);
        RawChatResponse response = executeChat(createVisionRequest(prompt, images));
        log.debug("LLM vision response:\n{}", response.assistantContent);
        return toCallResult(response, resultType);
    }

    /** 构造 OpenAI 兼容聊天请求；DeepSeek V4 明确关闭默认思考模式。 */
    private Request createChatRequest(String prompt) {
        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", prompt);
        JSONArray messages = new JSONArray();
        messages.add(message);

        JSONObject responseFormat = new JSONObject();
        responseFormat.put("type", "json_object");
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", chatModelName);
        requestBody.put("messages", messages);
        requestBody.put("stream", false);
        requestBody.put("max_tokens", CHAT_MAX_OUTPUT_TOKENS);
        requestBody.put("response_format", responseFormat);
        if (isDeepSeekEndpoint()) {
            JSONObject thinking = new JSONObject();
            thinking.put("type", "disabled");
            requestBody.put("thinking", thinking);
        }
        return new Request.Builder()
                .url(chatCompletionEndpoint)
                .header("Authorization", "Bearer " + chatApiKey)
                .post(RequestBody.create(JSON_MEDIA_TYPE, requestBody.toJSONString()))
                .build();
    }

    /** 构造图片只出现在 user 消息中的 OpenAI 兼容视觉请求。 */
    private Request createVisionRequest(String prompt, List<LlmImageInput> images) {
        JSONArray content = new JSONArray();
        JSONObject textContent = new JSONObject();
        textContent.put("type", "text");
        textContent.put("text", prompt);
        content.add(textContent);
        for (LlmImageInput image : images) {
            JSONObject imageUrl = new JSONObject();
            imageUrl.put("url", JPEG_DATA_URL_PREFIX
                    + Base64.getEncoder().encodeToString(image.getJpegData()));
            imageUrl.put("detail", "original");
            JSONObject imageContent = new JSONObject();
            imageContent.put("type", "image_url");
            imageContent.put("image_url", imageUrl);
            content.add(imageContent);
        }

        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", content);
        JSONArray messages = new JSONArray();
        messages.add(message);
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", visionModelName);
        requestBody.put("messages", messages);
        requestBody.put("stream", false);
        requestBody.put("max_tokens", VISION_MAX_OUTPUT_TOKENS);
        JSONObject responseFormat = new JSONObject();
        responseFormat.put("type", "json_object");
        requestBody.put("response_format", responseFormat);
        if (isDeepSeekEndpoint()) {
            JSONObject thinking = new JSONObject();
            thinking.put("type", "disabled");
            requestBody.put("thinking", thinking);
        }
        return new Request.Builder()
                .url(chatCompletionEndpoint)
                .header("Authorization", "Bearer " + chatApiKey)
                .post(RequestBody.create(JSON_MEDIA_TYPE, requestBody.toJSONString()))
                .build();
    }

    private <T> void validateVisionRequest(
            String prompt, List<LlmImageInput> images, Class<T> resultType) {
        if (StringUtils.isBlank(prompt) || resultType == null
                || StringUtils.isBlank(visionModelName)
                || images == null || images.isEmpty()) {
            throw new StreamerRecordException(
                    ErrorEnum.INVALID_PARAM, "invalid LLM vision request");
        }
        if (images.size() > MAXIMUM_VISION_IMAGE_COUNT) {
            throw new StreamerRecordException(
                    ErrorEnum.INVALID_PARAM,
                    "LLM vision image count exceeds limit: "
                            + images.size() + "/" + MAXIMUM_VISION_IMAGE_COUNT);
        }
        long totalImageBytes = 0L;
        for (LlmImageInput image : images) {
            if (image == null) {
                throw new StreamerRecordException(
                        ErrorEnum.INVALID_PARAM, "LLM vision image must not be null");
            }
            totalImageBytes += image.sizeInBytes();
        }
        if (totalImageBytes > MAXIMUM_VISION_RAW_IMAGE_BYTES) {
            throw new StreamerRecordException(
                    ErrorEnum.INVALID_PARAM,
                    "LLM vision image bytes exceed limit: "
                            + totalImageBytes + "/" + MAXIMUM_VISION_RAW_IMAGE_BYTES);
        }
    }

    /** 调用统一聊天接口并提取助手文本。 */
    private RawChatResponse executeChat(Request request) {
        try (Response response = chatHttpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String responseContent = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                JSONObject error = parseJsonObject(responseContent);
                throw new LlmResponseException(
                        "LLM chat failed, HTTP status: " + response.code()
                                + ", requestId: " + error.getString("request_id"),
                        responseContent);
            }
            return new RawChatResponse(
                    responseContent, parseChatContent(responseContent));
        } catch (IOException e) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR, "cannot call LLM chat API", e);
        }
    }

    /** 从 OpenAI 兼容响应中读取首个助手消息。 */
    private String parseChatContent(String responseContent) {
        try {
            JSONObject response = JSON.parseObject(responseContent);
            JSONArray choices = response == null ? null : response.getJSONArray("choices");
            JSONObject choice = choices == null || choices.isEmpty()
                    ? null : choices.getJSONObject(0);
            JSONObject message = choice == null ? null : choice.getJSONObject("message");
            String content = message == null ? null : message.getString("content");
            if (StringUtils.isBlank(content)) {
                String finishReason = choice == null
                        ? null : choice.getString("finish_reason");
                int reasoningLength = message == null ? 0
                        : StringUtils.length(message.getString("reasoning_content"));
                throw new LlmResponseException(
                        "missing LLM chat response content, finishReason: "
                                + finishReason + ", reasoningLength: " + reasoningLength,
                        responseContent);
            }
            return content;
        } catch (LlmResponseException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmResponseException(
                    "invalid LLM chat response", responseContent, e);
        }
    }

    /** 兼容基础地址与完整聊天地址两种配置形式。 */
    private HttpUrl buildChatCompletionEndpoint(String baseUrl) {
        String normalized = StringUtils.removeEnd(StringUtils.trimToEmpty(baseUrl), "/");
        String endpoint = normalized.endsWith("/chat/completions")
                ? normalized : normalized + "/chat/completions";
        HttpUrl parsed = HttpUrl.parse(endpoint);
        if (parsed == null) {
            throw new StreamerRecordException(
                    ErrorEnum.INVALID_PARAM, "invalid LLM chat API URL");
        }
        return parsed;
    }

    private boolean isDeepSeekEndpoint() {
        return chatCompletionEndpoint != null
                && chatCompletionEndpoint.host().toLowerCase().endsWith("deepseek.com");
    }

    /** 使用千问图像模型增强输入画面，业务层无需感知底层模型及协议。 */
    @Override
    public byte[] editImage(byte[] sourceImage, String prompt) {
        return editImageWithRaw(sourceImage, prompt).getImageData();
    }

    @Override
    public LlmImageEditResult editImageWithRaw(byte[] sourceImage, String prompt) {
        if (sourceImage == null || sourceImage.length == 0 || StringUtils.isBlank(prompt)
                || StringUtils.isBlank(imageApiKey) || StringUtils.isBlank(imageModelName)
                || imageHttpClient == null || imageEditEndpoint == null) {
            throw new StreamerRecordException(
                    ErrorEnum.INVALID_PARAM, "invalid LLM image edit request");
        }
        RequestBody requestBody = RequestBody.create(
                JSON_MEDIA_TYPE, createImageEditRequest(sourceImage, prompt));
        Request request = new Request.Builder()
                .url(imageEditEndpoint)
                .header("Authorization", "Bearer " + imageApiKey)
                .post(requestBody)
                .build();
        return executeImageEdit(request);
    }

    /** 构造 DashScope 千问图像编辑请求，输入图片以内联 Base64 传递。 */
    private String createImageEditRequest(byte[] sourceImage, String prompt) {
        JSONArray content = new JSONArray();
        JSONObject imageContent = new JSONObject();
        imageContent.put("image", JPEG_DATA_URL_PREFIX
                + Base64.getEncoder().encodeToString(sourceImage));
        content.add(imageContent);
        JSONObject textContent = new JSONObject();
        textContent.put("text", prompt);
        content.add(textContent);

        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", content);
        JSONArray messages = new JSONArray();
        messages.add(message);
        JSONObject input = new JSONObject();
        input.put("messages", messages);

        JSONObject parameters = new JSONObject();
        parameters.put("prompt_extend", false);
        parameters.put("enable_thinking", true);
        parameters.put("n", 1);
        parameters.put("size", IMAGE_SIZE);
        parameters.put("watermark", false);

        JSONObject request = new JSONObject();
        request.put("model", imageModelName);
        request.put("input", input);
        request.put("parameters", parameters);
        return request.toJSONString();
    }

    /** 调用同步生图接口，并立即下载仅短期有效的结果图片。 */
    private LlmImageEditResult executeImageEdit(Request request) {
        try (Response response = imageHttpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String responseContent = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                JSONObject error = parseJsonObject(responseContent);
                throw new StreamerRecordException(
                        ErrorEnum.COVER_GENERATION_ERROR,
                        "LLM image edit failed, HTTP status: " + response.code()
                                + ", code: " + error.getString("code")
                                + ", requestId: " + error.getString("request_id"));
            }
            if (StringUtils.isBlank(responseContent)) {
                throw new StreamerRecordException(
                        ErrorEnum.COVER_GENERATION_ERROR, "LLM image response body is empty");
            }
            byte[] imageData = downloadGeneratedImage(parseImageUrl(responseContent));
            return new LlmImageEditResult(responseContent, imageData);
        } catch (IOException e) {
            throw new StreamerRecordException(
                    ErrorEnum.COVER_GENERATION_ERROR, "cannot call LLM image edit API", e);
        }
    }

    /** 从千问同步响应中提取生成图片的临时下载地址。 */
    private String parseImageUrl(String response) {
        try {
            JSONObject responseJson = JSON.parseObject(response);
            JSONObject output = responseJson.getJSONObject("output");
            JSONArray choices = output == null ? null : output.getJSONArray("choices");
            JSONObject choice = choices == null || choices.isEmpty()
                    ? null : choices.getJSONObject(0);
            JSONObject message = choice == null ? null : choice.getJSONObject("message");
            JSONArray content = message == null ? null : message.getJSONArray("content");
            String imageUrl = content == null || content.isEmpty()
                    ? null : content.getJSONObject(0).getString("image");
            if (StringUtils.isBlank(imageUrl)) {
                throw new IllegalArgumentException("missing output image URL");
            }
            return imageUrl;
        } catch (RuntimeException e) {
            throw new StreamerRecordException(
                    ErrorEnum.COVER_GENERATION_ERROR, "invalid LLM image response", e);
        }
    }

    /** 下载生成结果，避免依赖 DashScope 仅保留 24 小时的临时地址。 */
    private byte[] downloadGeneratedImage(String imageUrl) {
        Request request = new Request.Builder().url(imageUrl).get().build();
        try (Response response = imageHttpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new StreamerRecordException(
                        ErrorEnum.COVER_GENERATION_ERROR,
                        "cannot download generated image, HTTP status: " + response.code());
            }
            byte[] image = body.bytes();
            if (image.length == 0) {
                throw new StreamerRecordException(
                        ErrorEnum.COVER_GENERATION_ERROR, "generated image is empty");
            }
            return image;
        } catch (IOException e) {
            throw new StreamerRecordException(
                    ErrorEnum.COVER_GENERATION_ERROR, "cannot download generated image", e);
        }
    }

    /** 尝试解析错误响应；非 JSON 响应由上层保留 HTTP 状态进行诊断。 */
    private JSONObject parseJsonObject(String content) {
        try {
            JSONObject parsed = JSON.parseObject(content);
            return parsed == null ? new JSONObject() : parsed;
        } catch (RuntimeException e) {
            return new JSONObject();
        }
    }

    private Proxy buildProxy() {
        if (StringUtils.isBlank(proxyUrl)) {
            return null;
        }
        HttpUrl parsedProxyUrl = HttpUrl.parse(proxyUrl);
        if (parsedProxyUrl == null || !"http".equalsIgnoreCase(parsedProxyUrl.scheme())) {
            throw new StreamerRecordException(
                    ErrorEnum.INVALID_PARAM, "invalid LLM HTTP proxy URL");
        }
        return new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress(parsedProxyUrl.host(), parsedProxyUrl.port()));
    }

    private <T> T parseResponse(String response, Class<T> resultType) {
        String jsonStr = extractJson(response);
        return JSON.parseObject(jsonStr, resultType);
    }

    private <T> LlmCallResult<T> toCallResult(
            RawChatResponse response, Class<T> resultType) {
        try {
            T result = parseResponse(response.assistantContent, resultType);
            return new LlmCallResult<>(response.assistantContent,
                    response.rawEnvelope, result);
        } catch (RuntimeException e) {
            throw new LlmResponseException(
                    "invalid LLM structured response", response.rawEnvelope, e);
        }
    }

    private String extractJson(String content) {
        if (content == null) {
            return "{}";
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
    }

    private static final class RawChatResponse {
        private final String rawEnvelope;
        private final String assistantContent;

        private RawChatResponse(String rawEnvelope, String assistantContent) {
            this.rawEnvelope = rawEnvelope;
            this.assistantContent = assistantContent;
        }
    }
}
