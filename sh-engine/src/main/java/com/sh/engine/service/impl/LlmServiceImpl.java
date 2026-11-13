package com.sh.engine.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sh.engine.service.InferenceLimit;
import com.sh.engine.service.LlmService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

@Service
@Slf4j
public class LlmServiceImpl implements LlmService {
    @Value("${langchain4j.open-ai.api-key:}")
    private String apiKey;

    @Value("${langchain4j.open-ai.base-url:}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.model-name:}")
    private String modelName;

    private ChatLanguageModel chatModel;
    private ChatLanguageModel multimodalModel;

    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            4, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(128),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    @PostConstruct
    public void init() {
        if (StringUtils.isAnyBlank(apiKey, baseUrl, modelName)) {
            log.info("LLM is not configured");
            return;
        }
        try {
            OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .timeout(Duration.ofSeconds(InferenceLimit.LLM.timeoutSeconds())).maxRetries(0);
            this.chatModel = builder.build();
            this.multimodalModel = this.chatModel;
            log.info("LlmService initialized, baseUrl: {}, model: {}", baseUrl, modelName);
        } catch (RuntimeException e) {
            log.warn("LLM client initialization failed, errorType: {}", e.getClass().getName());
        }
    }

    @Override
    public boolean isEnabled() { return multimodalModel != null; }

    @Override
    public <T> T chat(String prompt, Class<T> resultType) {
        if (chatModel == null) {
            throw new UnsupportedOperationException("LLM is not available: check langchain4j.open-ai configuration");
        }
        log.info("LLM request, prompt:\n{}", prompt);

        String response = InferenceLimit.LLM.call(() -> chatModel.generate(prompt));

        log.info("LLM response:\n{}", response);

        return parseResponse(response, resultType);
    }

    @Override
    public JSONObject chat(List<Content> content) throws IOException {
        if (!isEnabled()) { throw new IOException("LLM is not available: check langchain4j.open-ai configuration"); }
        if (content == null || content.isEmpty()) { throw new IllegalArgumentException("Missing model input"); }
        long started = System.nanoTime();
        Response<AiMessage> response;
        try {
            response = InferenceLimit.LLM.call(
                    () -> multimodalModel.generate(Collections.singletonList(UserMessage.from(content))));
        } catch (RuntimeException error) {
            throw new IOException("OpenAI-compatible model request failed", error);
        }
        if (response == null || response.finishReason() != FinishReason.STOP || response.content() == null
                || response.content().hasToolExecutionRequests() || StringUtils.isBlank(response.content().text())) {
            throw new IOException("Model did not return a complete text reply");
        }
        JSONObject result = new JSONObject(true);
        result.put("transport", "openai-compatible-http");
        result.put("requestId", UUID.randomUUID().toString());
        // SDK 0.35 的 Response 不暴露服务端模型名，不能把配置值当作实际响应模型。
        result.put("requestedModel", modelName);
        result.put("finishReason", response.finishReason().name());
        result.put("finalResponse", response.content().text());
        result.put("seconds", (System.nanoTime() - started) / 1_000_000_000.0);
        return result;
    }

    @Override
    public <T> CompletableFuture<T> chatAsync(String prompt, Class<T> resultType) {
        return CompletableFuture.supplyAsync(() -> chat(prompt, resultType), EXECUTOR);
    }

    private <T> T parseResponse(String response, Class<T> resultType) {
        String jsonStr = extractJson(response);
        return JSON.parseObject(jsonStr, resultType);
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
}
