package com.sh.engine.service;

import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.data.message.Content;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface LlmService {

    boolean isEnabled();

    <T> T chat(String prompt, Class<T> resultType);

    /** 单次多模态请求；仅接受完整的模型文字回复，不使用会话历史或工具调用。 */
    JSONObject chat(List<Content> content) throws IOException;

    <T> CompletableFuture<T> chatAsync(String prompt, Class<T> resultType);
}
