package com.sh.engine.service.impl;

import com.alibaba.fastjson.JSON;
import com.sh.engine.service.LlmService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
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
            this.chatModel = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .build();
            log.info("LlmService initialized, baseUrl: {}, model: {}", baseUrl, modelName);
        } catch (RuntimeException e) {
            log.warn("LLM client initialization failed, errorType: {}", e.getClass().getName());
        }
    }

    @Override
    public <T> T chat(String prompt, Class<T> resultType) {
        if (chatModel == null) {
            throw new UnsupportedOperationException("LLM is not available: check langchain4j.open-ai configuration");
        }
        log.info("LLM request, prompt:\n{}", prompt);

        String response = chatModel.generate(prompt);

        log.info("LLM response:\n{}", response);

        return parseResponse(response, resultType);
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
