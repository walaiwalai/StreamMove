package com.sh.engine.model.llm;

/** 一次 LLM 调用的原始文本与解析结果。 */
public final class LlmCallResult<T> {
    private final String rawResponse;
    private final String rawEnvelope;
    private final T result;

    public LlmCallResult(String rawResponse, T result) {
        this(rawResponse, rawResponse, result);
    }

    public LlmCallResult(String rawResponse, String rawEnvelope, T result) {
        this.rawResponse = rawResponse;
        this.rawEnvelope = rawEnvelope;
        this.result = result;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public String getRawEnvelope() {
        return rawEnvelope;
    }

    public T getResult() {
        return result;
    }
}
